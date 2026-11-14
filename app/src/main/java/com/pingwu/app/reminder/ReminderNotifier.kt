package com.pingwu.app.reminder

import com.pingwu.app.ui.theme.LauncherAlias

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pingwu.app.MainActivity
import com.pingwu.app.R

/** 到点提醒：高优先级渠道 + 全屏 Intent，锁屏也能把人叫起来拍一张。 */
object ReminderNotifier {

    // v2：老渠道建立时没指定铃声，系统默认可能很轻甚至静音。
    // 换一个新 id 才能把「闹钟类铃声 + 长振动」真正写进渠道。
    const val CHANNEL_ID = "pw_reminder_v2"

    // v3：静音渠道。声音和振动改由 AlarmPlayer 统一负责——这样用户才能自己选铃声/上传铃声，
    // 而渠道的声音一旦创建就改不了，两个声音还会叠在一起。
    const val CHANNEL_SILENT_ID = "pw_reminder_v3"
    private const val NOTIFICATION_ID = 7101

    // 全屏页的启动延迟：给它一点时间把界面画出来，声音再跟上，
    // 免得「先响铃、几秒后才看到页面」。超时没起来就由兜底播放。
    private const val FULL_SCREEN_LEAD_MS = 800L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "到点提醒（旧）",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "旧渠道，保留以兼容已发出的提醒。"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 240, 500, 240, 800)
                runCatching {
                    val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(uri, attrs)
                }
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
        if (manager.getNotificationChannel(CHANNEL_SILENT_ID) == null) {
            val silent = NotificationChannel(
                CHANNEL_SILENT_ID,
                "到点提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "到点提醒你拍一张物品照片；铃声与振动由 App 自己播放，可在「我的」里设置。"
                setSound(null, null)
                enableVibration(false)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(silent)
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Android 14 起，全屏 Intent 还要单独授权；没有就退化成高优先级通知。 */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    @SuppressLint("MissingPermission") // canPost() 已在上面显式检查；这里再兜底 try/catch
    fun notify(
        context: Context,
        itemName: String,
        countPerDose: Int,
        timeLabel: String,
        notificationId: Int = NOTIFICATION_ID,
        fullScreen: Boolean = true,
        coverPhotoKey: String = "",
    ) {
        if (!canPost(context)) return
        ensureChannel(context)

        val fullScreenIntent = Intent(context, ReminderActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ReminderActivity.EXTRA_ITEM_NAME, itemName)
            putExtra(ReminderActivity.EXTRA_COUNT, countPerDose)
            putExtra(ReminderActivity.EXTRA_TIME_LABEL, timeLabel)
            putExtra(ReminderActivity.EXTRA_COVER, coverPhotoKey)
        }
        val pending = PendingIntent.getActivity(
            context,
            8101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 经 OpenAppReceiver 转发：点下去的那一刻才按当前主题挑入口，避免通知里的入口
        // 后来被切主题禁用掉、点了没反应。
        val openCapture = Intent(context, OpenAppReceiver::class.java).apply {
            putExtra(MainActivity.EXTRA_ROUTE, "capture")
        }
        val capturePending = PendingIntent.getBroadcast(
            context,
            8102,
            openCapture,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_SILENT_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("到点该拍了")
            .setContentText("$timeLabel · $itemName ×$countPerDose")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$timeLabel · 拍一张「$itemName」，才算这一次完成。"),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 必须是 ALARM/CALL 类别：Android 14 起全屏 Intent 只对这两类放行，
            // 用 CATEGORY_REMINDER 的话系统会把全屏降级成横幅，锁屏时也弹不出来。
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .addAction(0, "去拍照", capturePending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)

        // 全屏页是加分项：没授权或不是第一条时，退回普通高优先级通知，不硬闯。
        ReminderLaunch.markPosted(context)
        val fullScreenAllowed = canUseFullScreen(context)
        Log.d("PwReminder", "post id=$notificationId fullScreen=$fullScreen canUseFullScreen=$fullScreenAllowed sdk=${Build.VERSION.SDK_INT}")
        if (fullScreen && fullScreenAllowed) {
            builder.setFullScreenIntent(pending, true)
        } else {
            Log.w("PwReminder", "full-screen intent not set: fullScreen=$fullScreen allowed=$fullScreenAllowed")
        }

        // canPost 已经查过权限，但用户可能刚好在这一瞬间撤销；兜底不崩。
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
        // 全屏页起得比声音慢：先让通知把 Activity 拉起来（它 onCreate 里自己开声音），
        // 这里只做兜底——万一全屏被系统拦下、页面没起来，声音也不能缺席。
        AlarmPlayer.startLater(context, reason = "notify-fallback", delayMs = FULL_SCREEN_LEAD_MS)
    }

    /** 到点自检用的通知：说明这是一次测试，不落到凭物里。 */
    @SuppressLint("MissingPermission") // canPost() 已检查，这里再兜底 try/catch
    fun notifySelfTest(context: Context) {
        if (!canPost(context)) return
        ensureChannel(context)
        val open = PendingIntent.getBroadcast(
            context,
            8103,
            Intent(context, OpenAppReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_SILENT_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("到点自检")
            .setContentText("这一条是测试：说明闹钟能按时叫醒你。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (canUseFullScreen(context)) {
            // 自检也走全屏，和真实提醒同一条链路，才有意义。
            val full = PendingIntent.getActivity(
                context,
                8104,
                Intent(context, ReminderActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ).putExtra(ReminderActivity.EXTRA_ITEM_NAME, "自检")
                    .putExtra(ReminderActivity.EXTRA_COUNT, 1)
                    .putExtra(ReminderActivity.EXTRA_TIME_LABEL, "现在"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(full, true)
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + 99, builder.build())
        }
        AlarmPlayer.start(context, reason = "self-test")
    }

    /** 同一次响铃里有多条待拍时，每条一个通知 id，避免后一条覆盖前一条。 */
    fun notificationId(index: Int): Int = NOTIFICATION_ID + index

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
