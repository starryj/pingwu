package com.pingwu.app.reminder

import com.pingwu.app.ui.theme.LauncherAlias

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pingwu.app.MainActivity
import com.pingwu.app.ui.screens.AlarmScreen
import com.pingwu.app.ui.theme.PingWuTheme
import com.pingwu.app.ui.theme.ThemePrefs

/**
 * 锁屏全屏到点页：到点时由通知的全屏 Intent 打开。
 * 内容与前台应用内的到点页共用 AlarmScreen。
 */
class ReminderActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ITEM_NAME = "item_name"
        const val EXTRA_COUNT = "count"
        const val EXTRA_TIME_LABEL = "time_label"
        const val EXTRA_COVER = "cover_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)

        // 铃声与振动由这里自己播，不走通知渠道：渠道声音会被系统/ROM 的通知设置吞掉。
        AlarmPlayer.start(this, reason = "activity")

        // 全屏页起来了，悬浮层就不该再压着
        ReminderOverlay.hide()
        val itemName = intent.getStringExtra(EXTRA_ITEM_NAME) ?: "那件东西"
        val count = intent.getIntExtra(EXTRA_COUNT, 1)
        val timeLabel = intent.getStringExtra(EXTRA_TIME_LABEL) ?: "该拍了"
        val cover = intent.getStringExtra(EXTRA_COVER).orEmpty()

        setContent {
            var mode by remember { mutableStateOf(ThemePrefs.load()) }
            PingWuTheme(mode) {
                AlarmScreen(
                    itemName = itemName,
                    count = count,
                    timeLabel = timeLabel,
                    coverPhotoKey = cover,
                    onCapture = {
                        startActivity(
                            Intent(this@ReminderActivity, LauncherAlias.entryClass())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                .putExtra(MainActivity.EXTRA_ROUTE, "capture"),
                        )
                        finish()
                    },
                    onLater = {
                        // 「稍后」= 推迟 10 分钟再提醒，不是把这次放过。
                        ReminderScheduler.snooze(this@ReminderActivity, 10)
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        AlarmPlayer.stop()
        super.onDestroy()
    }
}
