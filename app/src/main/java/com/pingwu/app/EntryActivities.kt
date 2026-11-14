package com.pingwu.app

/**
 * 开屏跟主题走。
 *
 * Android 12+ 的系统启动画面（图标 + 背景色）由 system_server 在进程起来之前绘制，
 * 用的是 Manifest 里**被启动的那个 Activity** 的 theme——`setTheme()` 在 `onCreate`
 * 里已经太晚，`activity-alias` 上的 theme 也不参与启动画面。
 *
 * 所以真的做两个入口：一个深墨、一个纸白，都继承 [MainActivity]（逻辑只有一份），
 * 切主题时启用对应的那个、禁用另一个（见 `ui/theme/LauncherAlias.kt`）。
 */
class DarkEntryActivity : MainActivity()

class PaperEntryActivity : MainActivity()
