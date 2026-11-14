package com.pingwu.app.data

import android.content.Context
import java.io.File

/**
 * 到点提醒的声音偏好：铃声（系统铃声或用户上传的文件）、是否震动。
 *
 * 提醒的声音不走通知渠道——渠道建好之后系统不允许改声音；
 * 这里的选择由 [com.pingwu.app.reminder.AlarmPlayer] 在响铃时读取并播放。
 */
data class ReminderSound(
    val ringtoneUri: String = "",
    val ringtoneLabel: String = "",
    val customPath: String = "",
    val customLabel: String = "",
    val vibrate: Boolean = true,
    val silent: Boolean = false,
) {
    /** 界面上显示的「当前铃声」。 */
    val displayName: String
        get() = when {
            silent -> "静音"
            customPath.isNotBlank() -> customLabel.ifBlank { "自定义铃声" }
            ringtoneUri.isNotBlank() -> ringtoneLabel.ifBlank { "系统铃声" }
            else -> "系统默认闹钟铃声"
        }
}

object ReminderSoundPrefs {

    private const val FILE = "pingwu_reminder_sound"
    private const val KEY_URI = "ringtone_uri"
    private const val KEY_LABEL = "ringtone_label"
    private const val KEY_CUSTOM_PATH = "custom_path"
    private const val KEY_CUSTOM_LABEL = "custom_label"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_SILENT = "silent"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): ReminderSound {
        val sp = prefs(context)
        return ReminderSound(
            ringtoneUri = sp.getString(KEY_URI, "") ?: "",
            ringtoneLabel = sp.getString(KEY_LABEL, "") ?: "",
            customPath = sp.getString(KEY_CUSTOM_PATH, "") ?: "",
            customLabel = sp.getString(KEY_CUSTOM_LABEL, "") ?: "",
            vibrate = sp.getBoolean(KEY_VIBRATE, true),
            silent = sp.getBoolean(KEY_SILENT, false),
        )
    }

    fun save(context: Context, sound: ReminderSound) {
        prefs(context).edit()
            .putString(KEY_URI, sound.ringtoneUri)
            .putString(KEY_LABEL, sound.ringtoneLabel)
            .putString(KEY_CUSTOM_PATH, sound.customPath)
            .putString(KEY_CUSTOM_LABEL, sound.customLabel)
            .putBoolean(KEY_VIBRATE, sound.vibrate)
            .putBoolean(KEY_SILENT, sound.silent)
            .apply()
    }

    fun setVibrate(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATE, enabled).apply()
    }

    /**
     * 用户从文件里选的铃声拷进 App 私有目录，重启、清理缓存都不怕。
     *
     * @param extension 文件扩展名（如 mp3、m4a），按 MIME 类型推断，决定 MediaPlayer 用哪个解码器。
     */
    fun importCustom(
        context: Context,
        displayName: String,
        extension: String,
        copy: (File) -> Unit,
    ): ReminderSound {
        val dir = File(context.filesDir, "sounds").apply { mkdirs() }
        val safeExt = extension.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "mp3" }
        val target = File(dir, "custom_ringtone.$safeExt")
        if (target.exists()) target.delete()
        copy(target)
        val sound = load(context).copy(
            customPath = target.absolutePath,
            customLabel = displayName,
            silent = false,
        )
        save(context, sound)
        return sound
    }

    /** 清掉自定义铃声，回到系统铃声。 */
    fun clearCustom(context: Context): ReminderSound {
        val old = load(context)
        if (old.customPath.isNotBlank()) {
            runCatching { File(old.customPath).delete() }
        }
        val sound = old.copy(customPath = "", customLabel = "", silent = false)
        save(context, sound)
        return sound
    }
}
