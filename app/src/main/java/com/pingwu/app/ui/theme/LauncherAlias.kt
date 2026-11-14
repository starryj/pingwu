package com.pingwu.app.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.pingwu.app.DarkEntryActivity
import com.pingwu.app.PaperEntryActivity

/**
 * 开屏跟主题走。
 *
 * Android 12+ 的系统启动画面（那个图标 + 背景色）是 system_server 在进程起来之前、
 * 按 Manifest 里**被启动的那个 Activity** 的主题先画好的——`setTheme()` 在 `onCreate`
 * 里已经太晚，`activity-alias` 上写的主题也不参与启动画面（实测仍走目标 Activity 的主题）。
 *
 * 解法：真做两个入口 Activity（`DarkEntryActivity` / `PaperEntryActivity`，都继承
 * `MainActivity`），各自在 Manifest 里带自己的主题和 MAIN/LAUNCHER；
 * 切主题时启用目标那个、禁用另一个。下次从桌面点图标，系统用的就是当前主题的入口。
 */
object LauncherAlias {

    private const val DARK_ALIAS = ".DarkEntryActivity"
    private const val PAPER_ALIAS = ".PaperEntryActivity"

    /**
     * 当前主题对应的入口 Activity。
     *
     * 凡是从通知、闹钟、全屏提醒跳回 App 的地方都用它启动——直接启 MainActivity 的话，
     * 启动窗口会按 Manifest 上的深墨主题画，纸白用户会先看到一瞬间深色底。
     */
    fun entryClass(mode: ThemeMode = ThemePrefs.load()): Class<*> =
        if (mode == ThemeMode.PAPER) PaperEntryActivity::class.java else DarkEntryActivity::class.java

    fun sync(context: Context, mode: ThemeMode) {
        val pkg = context.packageName
        val dark = ComponentName(pkg, pkg + DARK_ALIAS)
        val paper = ComponentName(pkg, pkg + PAPER_ALIAS)
        val want = if (mode == ThemeMode.PAPER) paper else dark
        val other = if (mode == ThemeMode.PAPER) dark else paper
        val pm = context.packageManager
        // 先开目标、再关另一个：桌面刷新时不会出现图标消失的一瞬。
        if (pm.getComponentEnabledSetting(want) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            pm.setComponentEnabledSetting(
                want,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        if (pm.getComponentEnabledSetting(other) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            pm.setComponentEnabledSetting(
                other,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
