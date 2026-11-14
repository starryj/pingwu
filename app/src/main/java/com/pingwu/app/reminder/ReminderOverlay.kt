package com.pingwu.app.reminder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.pingwu.app.BuildConfig
import com.pingwu.app.MainActivity
import com.pingwu.app.ui.theme.LauncherAlias

/**
 * 解锁状态下的到点页：系统只肯给一条横幅（全屏 Intent 被静默换掉），
 * 普通 startActivity 也会被后台启动限制拦下。这种时候唯一稳的是自己画一层悬浮窗，
 * 只要用户给过「显示在其他应用上层」，它就能真的盖在桌面上。
 *
 * 锁屏时不用它——那条走通知的全屏 Intent，才是点亮屏幕的正路。
 */
object ReminderOverlay {

    private const val TAG = "PwOverlay"
    private const val INK = 0xFF0B1216.toInt()
    private const val AMBER = 0xFFF0B45A.toInt()
    private const val PAPER = 0xFFF5F2E8.toInt()
    private const val FAINT = 0xFFA6A192.toInt()

    private var shown: View? = null

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(context)

    /** 解锁、亮屏、且拿到了悬浮窗权限时才用悬浮层。 */
    fun shouldUse(context: Context): Boolean {
        if (!canShow(context)) return false
        val kg = context.getSystemService(KeyguardManager::class.java)
        return kg?.isKeyguardLocked != true
    }

    fun hide() {
        val v = shown ?: return
        if (BuildConfig.DEBUG) {
            // 谁把它收走的，调试时看一眼就够；正式包里不留这段。
            Log.d(TAG, "hide from " + Throwable().stackTrace.drop(1).take(3).joinToString(" <- ") { "${it.methodName}:${it.lineNumber}" })
        }
        runCatching {
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
        shown = null
    }

    fun show(context: Context, itemName: String?, count: Int, timeLabel: String?) {
        if (!canShow(context)) return
        hide()
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = app.resources.displayMetrics.density

        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(INK)
            setPadding(dp(28), dp(48), dp(28), dp(48))
        }

        root.addView(TextView(app).apply {
            text = timeLabel?.let { "到点该拍了 · $it" } ?: "到点该拍了"
            setTextColor(AMBER)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(app).apply {
            text = itemName ?: "那件东西"
            setTextColor(PAPER)
            textSize = 30f
            typeface = Typeface.create("serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        })

        root.addView(TextView(app).apply {
            text = "× $count · 拍一张，才把这次记成完成"
            setTextColor(FAINT)
            textSize = 14f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(app).apply {
            text = "这件事用照片解锁；不拍不算做完。"
            setTextColor(FAINT)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(26))
        })

        root.addView(TextView(app).apply {
            text = "去 拍 照"
            setTextColor(INK)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(AMBER)
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setOnClickListener {
                hide()
                runCatching {
                    app.startActivity(
                        Intent(app, LauncherAlias.entryClass())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra(MainActivity.EXTRA_ROUTE, "capture"),
                    )
                }
            }
        })

        root.addView(TextView(app).apply {
            text = "稍后 10 分钟"
            setTextColor(FAINT)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
            setOnClickListener {
                hide()
                ReminderScheduler.snooze(app, 10)
            }
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.OPAQUE,
        )
        val added = runCatching { wm.addView(root, lp); true }
            .onFailure { Log.w(TAG, "addView failed ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
        if (added) {
            shown = root
            Log.d(TAG, "shown item=$itemName")
        }
    }
}
