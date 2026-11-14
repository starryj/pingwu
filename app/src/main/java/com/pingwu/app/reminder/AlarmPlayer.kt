package com.pingwu.app.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.pingwu.app.data.ReminderSound
import com.pingwu.app.data.ReminderSoundPrefs
import java.io.File

/**
 * 到点时的铃声与振动。
 *
 * 不走通知渠道：通知渠道的声音会被系统「通知声音」开关、静音模式、部分 ROM 的前台抑制吞掉，
 * 而闹钟应该用 USAGE_ALARM 直接播——静音和勿扰下也能听见（除非用户关掉闹钟音量或禁止闹钟响铃）。
 *
 * 铃声与震动读 [ReminderSoundPrefs]：用户可以选系统铃声、上传自己的铃声、关掉震动。
 */
object AlarmPlayer {

    private const val TAG = "PwAlarm"

    private val VIBRATION = longArrayOf(0, 600, 300, 600, 300, 900)

    /** 响多久自己停：闹钟类 App 的常规做法，5 分钟没人理就不再制造噪音。 */
    private const val AUTO_STOP_MS = 5 * 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }
    private var pendingStart: Runnable? = null

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focusRequest: AudioFocusRequest? = null

    fun start(context: Context, reason: String = "") {
        // 已经在响就不再重来：全屏页和通知的兜底可能几乎同时调到这里。
        if (player != null) {
            Log.d(TAG, "start ignored (already playing) reason=$reason")
            return
        }
        val app = context.applicationContext
        val sound = ReminderSoundPrefs.load(app)
        Log.d(TAG, "start reason=$reason ring=${sound.displayName} vibrate=${sound.vibrate}")
        if (sound.silent) {
            Log.d(TAG, "sound skipped: silent in settings")
        } else {
            startSound(app, sound)
        }
        startVibration(app, sound.vibrate)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MS)
    }

    private fun audioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun startSound(context: Context, sound: ReminderSound) {
        if (player != null) return
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            var volume = am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: -1
            val max = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: -1
            // 闹钟音量为 0 时叫不醒人：抬到一半（不让用户彻底听不见，也不直接拉满）。
            if (am != null && max > 0 && volume <= 0) {
                val target = (max / 2).coerceAtLeast(1)
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
                Log.w(TAG, "alarm volume was $volume, raised to $target/$max")
                volume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            val mp = MediaPlayer()
            mp.setAudioAttributes(audioAttributes())
            val source = prepareSource(context, mp, sound)
            mp.isLooping = true
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.prepare()
            mp.start()
            player = mp
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes())
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                focusRequest = request
                am.requestAudioFocus(request)
            }
            Log.d(TAG, "sound start source=$source alarmVolume=$volume/$max")
        }.onFailure { Log.w(TAG, "sound failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    /**
     * 依次尝试：用户上传的铃声 → 用户选的系统铃声 → 系统默认闹钟铃声。
     * 任何一步打不开（文件被删、URI 权限失效）都退到下一步，不让到点变哑巴。
     */
    private fun prepareSource(context: Context, mp: MediaPlayer, sound: ReminderSound): String {
        if (sound.customPath.isNotBlank()) {
            val file = File(sound.customPath)
            if (file.exists()) {
                runCatching {
                    mp.setDataSource(file.absolutePath)
                    return "custom:${file.name}"
                }.onFailure { Log.w(TAG, "custom ring failed: ${it.message}") }
            }
        }
        if (sound.ringtoneUri.isNotBlank()) {
            runCatching {
                mp.setDataSource(context, Uri.parse(sound.ringtoneUri))
                return "uri:${sound.ringtoneUri}"
            }.onFailure { Log.w(TAG, "picked ring failed: ${it.message}") }
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        mp.setDataSource(context, uri)
        return "default:$uri"
    }

    private fun startVibration(context: Context, enabled: Boolean) {
        if (vibrator != null) return
        if (!enabled) {
            Log.d(TAG, "vibrate skipped: disabled in settings")
            return
        }
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v == null || !v.hasVibrator()) {
                Log.w(TAG, "vibrate skipped: no vibrator")
                return
            }
            val effect = VibrationEffect.createWaveform(VIBRATION, 0)
            // 关键：不指定 usage 时系统按「未知用途」处理，Android 15+ 会把后台振动直接忽略
            // （vibrator_manager 里记成 ignored_background）。闹钟必须显式声明 USAGE_ALARM。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
                v.vibrate(effect, attrs)
            } else {
                v.vibrate(effect)
            }
            vibrator = v
            Log.d(TAG, "vibrate start usage=ALARM sdk=${Build.VERSION.SDK_INT}")
        }.onFailure { Log.w(TAG, "vibrate failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    /**
     * 延迟开声：全屏页的启动比响铃慢，先让页面起来再响，两边才对得上。
     * 期间被 [stop]（比如用户已经点了「稍后」）就撤销，不会再突然响一下。
     */
    fun startLater(context: Context, reason: String, delayMs: Long) {
        val app = context.applicationContext
        pendingStart?.let { handler.removeCallbacks(it) }
        val task = Runnable { start(app, reason) }
        pendingStart = task
        handler.postDelayed(task, delayMs)
        Log.d(TAG, "startLater reason=$reason in ${delayMs}ms")
    }

    fun stop() {
        handler.removeCallbacks(autoStop)
        pendingStart?.let { handler.removeCallbacks(it) }
        pendingStart = null
        runCatching {
            player?.let { if (it.isPlaying) it.stop() }
            player?.release()
        }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        focusRequest = null
        Log.d(TAG, "stopped")
    }
}
