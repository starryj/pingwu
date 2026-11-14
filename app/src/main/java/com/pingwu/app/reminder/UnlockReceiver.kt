package com.pingwu.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pingwu.app.MainActivity
import com.pingwu.app.data.core.PingWuDatabase
import com.pingwu.app.data.core.PingWuRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 解锁补弹：到点时如果手机是解锁状态，系统不会放行全屏 Intent（只给一条横幅），
 * 于是人很容易根本没看见。这里在「解锁完成」的瞬间再检查一次——还有该拍的、App 又不在
 * 前台，就把到点页补上。
 */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (MainActivity.isVisible) return@launch
                val repo = PingWuRepository(PingWuDatabase.get(app))
                repo.ensureSeeded()
                val task = repo.duePendingList(System.currentTimeMillis()).firstOrNull() ?: return@launch
                if (ReminderLaunch.shouldSkip(app, task.occurrenceId)) return@launch
                Log.d("PwLaunch", "unlock repop occ=${task.occurrenceId}")
                ReminderLaunch.launch(app, task, "unlock")
            } finally {
                pending.finish()
            }
        }
    }
}
