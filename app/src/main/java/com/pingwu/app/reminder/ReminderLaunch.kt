package com.pingwu.app.reminder

import android.content.Context
import android.content.Intent
import android.util.Log
import com.pingwu.app.data.core.PendingTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「到点页真的弹出来」这摊事单独收在这里。
 *
 * 系统规则：通知的全屏 Intent 只在锁屏 / 息屏时放行，解锁状态下会被静默换成一条横幅
 * （Android 14 起更严格，只有通话 / 闹钟类才有资格）。所以除了通知本身，我们还要：
 *   1. 发完通知再显式 startActivity 一次（小米 / 红米允许「后台弹出界面」时能弹）；
 *   2. 用户解锁手机时（ACTION_USER_PRESENT）再补一次，别让人只在通知栏里错过。
 *
 * 同一条待拍 10 分钟内不重复弹，避免每次亮屏解锁都跳脸。
 */
object ReminderLaunch {

    private const val PREFS = "pingwu_launch"
    private const val KEY_POST_AT = "last_post_at"
    private const val KEY_LAUNCH_AT = "last_launch_at"
    private const val KEY_LAUNCH_HOW = "last_launch_how"
    private const val KEY_LAUNCH_OCC = "last_launch_occ"
    private const val KEY_LAST_RESULT = "last_result"

    /** 同一条待拍在这个间隔内不重复弹。 */
    private const val SAME_TASK_GAP_MS = 10 * 60_000L

    /** 到点那次尝试之后，多久内不再重复「直接拉起」。 */
    private const val IMMEDIATE_GAP_MS = 30_000L

    fun markPosted(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_POST_AT, at).apply()
    }

    /** 真的把到点页拉起来。返回是否成功（被系统拦下时 startActivity 会抛，或静默不显示）。 */
    fun launch(context: Context, task: PendingTask?, how: String): Boolean {
        val intent = Intent(context, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (task != null) {
            intent.putExtra(ReminderActivity.EXTRA_ITEM_NAME, task.itemName)
                .putExtra(ReminderActivity.EXTRA_COUNT, task.countPerDose)
                .putExtra(ReminderActivity.EXTRA_TIME_LABEL, task.timeLabel)
                .putExtra(ReminderActivity.EXTRA_COVER, task.coverPhotoKey)
        }
        val ok = runCatching { context.startActivity(intent) }
            .onFailure { Log.w("PwLaunch", "start failed how=$how ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_LAUNCH_AT, now)
            .putString(KEY_LAUNCH_HOW, how)
            .putLong(KEY_LAUNCH_OCC, task?.occurrenceId ?: -1L)
            .putString(KEY_LAST_RESULT, if (ok) "$how ok" else "$how failed")
            .apply()
        Log.d("PwLaunch", "launch how=$how occ=${task?.occurrenceId} ok=$ok")
        return ok
    }

    /**
     * 同一条待拍刚弹过就别再弹。
     *
     * 到点那次「直接拉起」在解锁状态下多半被系统拦掉（只留一条横幅、窗口没到前台），
     * 所以它只压 30 秒——给解锁补弹留出机会；补弹本身压满 10 分钟，免得每次解锁都跳脸。
     */
    fun shouldSkip(context: Context, occurrenceId: Long?): Boolean {
        if (occurrenceId == null) return false
        val p = prefs(context)
        val lastOcc = p.getLong(KEY_LAUNCH_OCC, -1L)
        if (lastOcc != occurrenceId) return false
        val at = p.getLong(KEY_LAUNCH_AT, 0L)
        val gap = if (p.getString(KEY_LAUNCH_HOW, "") == "unlock") SAME_TASK_GAP_MS else IMMEDIATE_GAP_MS
        return System.currentTimeMillis() - at < gap
    }

    fun lastPostLabel(context: Context): String = label(prefs(context).getLong(KEY_POST_AT, 0L))

    fun lastLaunchLabel(context: Context): String {
        val p = prefs(context)
        val at = p.getLong(KEY_LAUNCH_AT, 0L)
        if (at == 0L) return "还没有"
        val how = when (p.getString(KEY_LAUNCH_HOW, null)) {
            "notify" -> "到点直接弹"
            "unlock" -> "解锁时补弹"
            else -> p.getString(KEY_LAUNCH_HOW, "") ?: ""
        }
        return "${label(at)} · $how"
    }

    fun lastResult(context: Context): String = prefs(context).getString(KEY_LAST_RESULT, "还没有") ?: "还没有"

    private fun label(at: Long): String =
        if (at == 0L) "还没有"
        else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(at))

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
