package com.pingwu.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import com.pingwu.app.data.core.PingWuDatabase
import com.pingwu.app.data.core.PingWuRepository
import com.pingwu.app.reminder.ReminderNotifier
import com.pingwu.app.reminder.ReminderOverlay
import com.pingwu.app.reminder.ReminderScheduler
import com.pingwu.app.ui.PingWuRoot
import com.pingwu.app.ui.theme.LauncherAlias
import com.pingwu.app.ui.theme.PingWuTheme
import com.pingwu.app.ui.theme.ThemeMode
import com.pingwu.app.ui.theme.ThemePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {

    companion object {
        /** 通知点「去拍照」时带的路由：直接进拍照验证页。 */
        const val EXTRA_ROUTE = "route"
        private const val ROUTE_CAPTURE = "capture"

        /** App 是否正显示在前台；解锁补弹靠它判断要不要跳出来。 */
        @Volatile
        var isVisible: Boolean = false

        /** 封面停留多久再淡出（毫秒）。 */
        private const val SPLASH_HOLD_MS = 1000L
        private const val SPLASH_FADE_MS = 320L
    }

    private var initialRoute by mutableStateOf<String?>(null)


    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不影响主流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 先把主题定下来再 super.onCreate：系统启动画面（Android 12+）和窗口底色
        // 是在这一瞬间就画出来的，晚一步就会「先闪一下深墨，再变纸白」。
        val startMode = ThemePrefs.load()
        setTheme(
            if (startMode == ThemeMode.PAPER) R.style.Theme_PingWu_Paper else R.style.Theme_PingWu,
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initialRoute = intent?.getStringExtra(EXTRA_ROUTE)
        // 调试：`adb shell am start ... --ez debug_overlay true` 直接盖一层悬浮提醒，
        // 用来验「解锁态到点页」这条路的渲染和按钮，不依赖闹钟真的到点。
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("debug_overlay", false) == true) {
            // 延后一点点：onResume 里会 hide()，这一下要压在它后面才看得见。
            window.decorView.postDelayed(
                { ReminderOverlay.show(this, "测试物品", 4, "测试提醒") },
                1200L,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 打开 App 时补齐今天的待办并排下一个闹钟；用户改完时间/重启后也能自愈。
        CoroutineScope(Dispatchers.IO).launch {
            val repo = PingWuRepository(PingWuDatabase.get(applicationContext))
            repo.ensureSeeded()
            repo.markMissed()
            ReminderNotifier.ensureChannel(applicationContext)
            ReminderScheduler.scheduleNext(applicationContext)
        }

        // 封面用普通 View 画，不用 Compose：系统的启动画面要等到 App 画出第一帧才收走，
        // 而 Compose 的第一帧要等组合/测量走完（冷启动约 0.6–0.9s），这段空档以前是一块
        // 纯色，现在直接被封面图补上——点图标就是带文字的封面，中间不留空帧。
        val root = FrameLayout(this)
        val handler = Handler(Looper.getMainLooper())

        // 从通知 / 到点闹钟点进来时直接落在目标页，不盖封面——那会儿用户是要处理事情，不是来看图的。
        val cover: ImageView? = if (initialRoute == null) {
            val paper = startMode == ThemeMode.PAPER
            ImageView(this).apply {
                setBackgroundColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (paper) R.color.pw_paper else R.color.pw_ink,
                    ),
                )
                setImageResource(if (paper) R.drawable.splash_paper else R.drawable.splash_dark)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        } else {
            null
        }

        // 第一帧只画封面：封面是普通 View，这一帧几乎立刻就能出来，系统启动画面
        // 于是马上收走——点图标就是带文字的封面，中间不留纯色空档。
        if (cover != null) root.addView(cover, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)

        // Compose 放到第一帧画完之后再加（压到封面底下）：组合/测量要几百毫秒，放在
        // 第一帧里会把首帧一起拖慢，系统启动画面就得一直顶着。封面的停留计时也从
        // 第一帧画完开始算，不然冷启动慢的时候封面还没露脸就该淡出了。
        root.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    // 桌面图标走哪个入口，决定下次冷启动的系统启动画面用哪套主题；
                    // 这一步要读/写 PackageManager，放在首帧之后做，免得拖慢启动。
                    handler.post {
                        LauncherAlias.sync(this@MainActivity, startMode)
                        addComposeContent(root)
                    }
                    if (cover != null) {
                        handler.postDelayed({
                            cover.animate()
                                .alpha(0f)
                                .setDuration(SPLASH_FADE_MS)
                                .withEndAction { root.removeView(cover) }
                                .start()
                        }, SPLASH_HOLD_MS)
                    }
                    return true
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        // 人自己回到 App 了，悬浮的到点页就该让位
        com.pingwu.app.reminder.ReminderOverlay.hide()
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        initialRoute = intent.getStringExtra(EXTRA_ROUTE) ?: initialRoute
    }

    /** 把 Compose 内容压到封面底下；第一帧画完之后才调用，避免拖慢首帧。 */
    private fun addComposeContent(root: FrameLayout) {
        val content = ComposeView(this).apply {
            setContent {
                var mode by remember { mutableStateOf(ThemePrefs.load()) }
                PingWuTheme(mode) {
                    PingWuRoot(
                        mode = mode,
                        onToggleTheme = {
                            val next = if (mode == ThemeMode.DARK) ThemeMode.PAPER else ThemeMode.DARK
                            mode = next
                            ThemePrefs.save(next)
                            // 桌面图标切到对应主题的入口，下次冷启动的系统启动画面才跟着换。
                            LauncherAlias.sync(this@MainActivity, next)
                        },
                        initialRoute = initialRoute,
                    )
                }
            }
        }
        // index 0：压到封面下面
        root.addView(content, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
}




