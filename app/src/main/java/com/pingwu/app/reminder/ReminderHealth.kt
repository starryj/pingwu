package com.pingwu.app.reminder

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 到点提醒的健康记录。
 *
 * 国内 ROM（尤其小米）会把后台闹钟压后、甚至直接把 App 停掉，
 * 看起来就像「到点没提醒」。这里只记三件事，用来在界面上老实说明：
 *  - 最近一次计划什么时候响；
 *  - 最近一次实际什么时候响、晚了多久；
 *  - 是否正在做一次自检。
 */
object ReminderHealth {

    private const val FILE = "pingwu_reminder_health"
    private const val KEY_PLANNED = "planned_at"
    private const val KEY_FIRED = "fired_at"
    private const val KEY_LATE = "late_ms"
    private const val KEY_SELFTEST = "selftest_at"
    private const val KEY_SELFTEST_RESULT = "selftest_result"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    private fun p(context: Context): SharedPreferences {
        init(context)
        return prefs!!
    }

    /** 排下一条提醒时调用。 */
    fun markPlanned(context: Context, at: Long) {
        p(context).edit().putLong(KEY_PLANNED, at).apply()
    }

    /** 闹钟真的响了：记录实际时间和延迟。 */
    fun markFired(context: Context, now: Long) {
        val planned = p(context).getLong(KEY_PLANNED, 0L)
        val late = if (planned > 0L) (now - planned).coerceAtLeast(0L) else 0L
        val selfTestAt = p(context).getLong(KEY_SELFTEST, 0L)
        val editor = p(context).edit()
            .putLong(KEY_FIRED, now)
            .putLong(KEY_LATE, late)
        // 如果这次响的正好是自检闹钟，把结果写下来
        if (selfTestAt > 0L && kotlin.math.abs(now - selfTestAt) < 5 * 60_000L) {
            editor.putLong(KEY_SELFTEST, 0L)
            editor.putString(KEY_SELFTEST_RESULT, if (late < 60_000L) "ON_TIME" else "LATE:$late")
        }
        editor.apply()
    }

    fun plannedAt(context: Context): Long = p(context).getLong(KEY_PLANNED, 0L)

    fun firedAt(context: Context): Long = p(context).getLong(KEY_FIRED, 0L)

    fun lateMs(context: Context): Long = p(context).getLong(KEY_LATE, 0L)

    fun selfTestAt(context: Context): Long = p(context).getLong(KEY_SELFTEST, 0L)

    fun selfTestResult(context: Context): String = p(context).getString(KEY_SELFTEST_RESULT, "") ?: ""

    fun markSelfTest(context: Context, at: Long) {
        p(context).edit().putLong(KEY_SELFTEST, at).putString(KEY_SELFTEST_RESULT, "PENDING").apply()
    }

    /**
     * 自检结果人话版。
     * 返回 null 表示没做过自检。
     */
    fun selfTestText(context: Context): String? {
        val at = selfTestAt(context)
        if (at > 0L) {
            val left = at - System.currentTimeMillis()
            return if (left > 0L) "自检中 · ${left / 1000} 秒后响" else "自检闹钟该响了，等一下"
        }
        return when {
            selfTestResult(context) == "PENDING" -> "自检还没响过（可能被系统拦住了）"
            selfTestResult(context).startsWith("ON_TIME") -> "上次自检 · 准点响了"
            selfTestResult(context).startsWith("LATE:") -> {
                val ms = selfTestResult(context).removePrefix("LATE:").toLongOrNull() ?: 0L
                "上次自检 · 晚了 ${ms / 60_000} 分"
            }
            else -> null
        }
    }

    fun lastFireText(context: Context): String? {
        val fired = firedAt(context)
        if (fired <= 0L) return null
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(fired))
        val late = lateMs(context)
        return if (late < 60_000L) "$time 准点" else "$time · 晚了 ${late / 60_000} 分"
    }
}
