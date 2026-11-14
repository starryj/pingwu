package com.pingwu.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pingwu.app.MainActivity
import com.pingwu.app.ui.theme.LauncherAlias

/**
 * 通知 / 状态栏图标点进来时走这里，而不是直接把 Intent 指向某个 Activity。
 *
 * 为什么不直接指：入口有两个（深墨 / 纸白），切主题时会禁掉另一个。通知是一段时间
 * 前就发出去的，如果它当时指向的入口后来被禁用了，点通知就没反应。
 * 这里在**点下去的这一刻**才读当前主题、挑对应的入口，永远是对的。
 */
class OpenAppReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val target = Intent(context, LauncherAlias.entryClass()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.getStringExtra(MainActivity.EXTRA_ROUTE)?.let {
                putExtra(MainActivity.EXTRA_ROUTE, it)
            }
        }
        runCatching { context.startActivity(target) }
    }
}
