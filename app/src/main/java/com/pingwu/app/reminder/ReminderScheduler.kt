package com.pingwu.app.reminder


import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pingwu.app.MainActivity
import com.pingwu.app.data.core.PingWuDatabase
import com.pingwu.app.data.core.PingWuRepository

/**
 * 只安排「下一个」提醒，响过之后再排下一个。
 * 这样不用给每个提醒都注册闹钟，也不会一次排几百个。
 *
 * 关于「到点了屏幕不亮」：普通精确闹钟（setExactAndAllowWhileIdle）在息屏 / Doze 下
 * 会被系统往后挪（真机 上实测晚了 2 分 19 秒才投递）。所以这里改用 setAlarmClock：
 * 系统把它当成真正的闹钟对待，会按时唤醒设备，状态栏也会出现闹钟图标。
 */
object ReminderScheduler {

    private const val REQUEST_CODE = 4101
    private const val REQUEST_CODE_SELFTEST = 4102

    fun actionSelfTest(): String = "com.pingwu.app.SELFTEST"

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 自检用单独的 PendingIntent，不会覆盖下一个真实提醒。 */
    private fun selfTestIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SELFTEST,
            Intent(context, ReminderReceiver::class.java).setAction(actionSelfTest()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 状态栏那个闹钟图标点一下会回到 App。 */
    private fun showIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, OpenAppReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    suspend fun scheduleNext(context: Context) {
        val app = context.applicationContext
        val repo = PingWuRepository(PingWuDatabase.get(app))
        val next = repo.nextScheduledAfter()
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        if (next == null) {
            alarmManager.cancel(pending)
            return
        }
        setReliable(app, alarmManager, next, pending)
    }

    /**
     * 推迟：N 分钟后再响一次同一条提醒。
     * 用同一个 PendingIntent，会覆盖掉原定的下一个闹钟——
     * 没关系，这次响完之后 ReminderReceiver 会按当前数据重排下一个。
     */
    fun snooze(context: Context, minutes: Int = 10) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + minutes * 60_000L
        setReliable(app, alarmManager, at, pendingIntent(app))
    }

    /**
     * 到点自检：N 分钟后响一次测试闹钟。
     * 用来在真机上分清「系统把我们拦了」还是「App 没排上」。
     */
    fun scheduleSelfTest(context: Context, minutes: Int = 1) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + minutes * 60_000L
        ReminderHealth.markSelfTest(app, at)
        setReliable(app, alarmManager, at, selfTestIntent(app), isSelfTest = true)
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    /**
     * 闹钟类调度：能唤醒设备，不受 Doze 打折。
     * 用户没给「精确闹钟」权限时退到不精确的 while-idle，至少不会完全不响。
     */
    private fun setReliable(
        app: Context,
        alarmManager: AlarmManager,
        at: Long,
        pending: PendingIntent,
        isSelfTest: Boolean = false,
    ) {
        if (!isSelfTest) ReminderHealth.markPlanned(app, at)
        val canExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(app)), pending)
            Log.d("PwReminder", "setAlarmClock at=$at selfTest=$isSelfTest")
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            Log.w("PwReminder", "no exact permission, setAndAllowWhileIdle at=$at")
        }
    }
}
