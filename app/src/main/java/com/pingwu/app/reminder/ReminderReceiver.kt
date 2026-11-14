package com.pingwu.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pingwu.app.data.core.PingWuDatabase
import com.pingwu.app.data.core.PingWuRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 闹钟到点：看看现在该拍哪件东西，发通知，再排下一个。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext

        // 到点自检：只证明「闹钟这条链路能响」，不动凭物数据。
        if (intent?.action == ReminderScheduler.actionSelfTest()) {
            ReminderHealth.markFired(app, System.currentTimeMillis())
            ReminderNotifier.notifySelfTest(app)
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                ReminderHealth.markFired(app, now)
                val repo = PingWuRepository(PingWuDatabase.get(app))
                // 冷启动 / 第二天第一次响时，保证今天的待办已经生成。
                repo.ensureSeeded()
                // 先取到点的待拍，再标漏拍：否则不精确闹钟晚到几分钟时，
                // 这一条会被当成「漏拍」而彻底不提醒（M6 修）。
                // 提前 5 秒算到点：系统偶尔会早投递几百毫秒，给一点余量；
                // 但不给一分钟，否则会提前响（作者 2026-11-13 反馈）。
                val due = repo.duePendingList(now, leadMs = 5_000L)
                due.forEachIndexed { index, task ->
                    ReminderNotifier.notify(
                        app,
                        task.itemName,
                        task.countPerDose,
                        task.timeLabel,
                        notificationId = ReminderNotifier.notificationId(index),
                        // 只有第一条抢全屏；其余用高优先级通知排队，避免叠一堆全屏页。
                        fullScreen = index == 0,
                        coverPhotoKey = task.coverPhotoKey,
                    )
                }
                repo.markMissed()
                ReminderScheduler.scheduleNext(app)
            } finally {
                pending.finish()
            }
        }
    }
}
