package com.pingwu.app.ui.screens

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.pingwu.app.BuildConfig
import com.pingwu.app.R
import com.pingwu.app.data.ReminderSoundPrefs
import com.pingwu.app.data.mock.MockEvidenceDay
import com.pingwu.app.network.AiConfig
import com.pingwu.app.network.AiPreset
import com.pingwu.app.reminder.AlarmPlayer
import com.pingwu.app.reminder.ReminderActivity
import com.pingwu.app.reminder.ReminderHealth
import com.pingwu.app.reminder.ReminderScheduler
import com.pingwu.app.reminder.ReminderNotifier
import com.pingwu.app.ui.PingWuViewModel
import com.pingwu.app.ui.components.Avatar
import com.pingwu.app.ui.components.DashedDivider
import com.pingwu.app.ui.components.InfoRow
import com.pingwu.app.ui.components.PwLabel
import com.pingwu.app.ui.components.RecordPickerDialog
import com.pingwu.app.ui.components.TicketCard
import com.pingwu.app.ui.components.TopBar
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType
import com.pingwu.app.ui.theme.ThemeMode
import com.pingwu.app.util.EvidenceCleaner
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    mode: ThemeMode,
    onToggleTheme: () -> Unit,
    vm: PingWuViewModel,
    onAvatarClick: () -> Unit,
    onNameClick: () -> Unit = {},
    days: List<MockEvidenceDay> = emptyList(),
) {
    val p = LocalPwPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }
    var showRecordPicker by remember { mutableStateOf(false) }
    var photoStats by remember { mutableStateOf<EvidenceCleaner.PhotoStats?>(null) }

    LaunchedEffect(vm) {
        photoStats = vm.photoStats()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(right = { PwLabel("v" + BuildConfig.VERSION_NAME) })

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(vm.avatar, photoKey = vm.avatarPhoto, onClick = onAvatarClick)
            Spacer(Modifier.width(12.dp))
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onNameClick),
                ) {
                    Text(text = vm.displayName, style = PwType.title, color = p.ink)
                    Text(
                        text = "  改",
                        style = PwType.tiny,
                        color = p.amber,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
                Text(
                    text = "东西在，事就没忘",
                    style = PwType.small,
                    color = p.faint,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "点头像换头像，点名字改称呼",
                    style = PwType.tiny,
                    color = p.faint.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // 主题
        PwLabel("主题")
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ThemeChoice(
                title = "深墨",
                sub = "默认",
                selected = mode == ThemeMode.DARK,
                swatch = Color(0xFF0A1014),
                accent = Color(0xFFF0B45A),
                modifier = Modifier.weight(1f),
                onClick = { if (mode != ThemeMode.DARK) onToggleTheme() },
            )
            ThemeChoice(
                title = "纸白",
                sub = "浅色",
                selected = mode == ThemeMode.PAPER,
                swatch = Color(0xFFE8E4D7),
                accent = Color(0xFF8A5A14),
                modifier = Modifier.weight(1f),
                onClick = { if (mode != ThemeMode.PAPER) onToggleTheme() },
            )
        }

        // 提醒声音（M11.1：铃声自选/上传、震动开关）
        ReminderSoundCard()

        // 在线识别（新增）
        OnlineAiCard(vm = vm)

        // 到点提醒
        ReminderPermissionCard()

        // 真机验收用的测试入口（仅 debug 包显示）
        DebugCard()

        // 设置
        CollapseCard(
            title = "设置",
            summary = "窗口 · 提醒 · 存储",
            modifier = Modifier.padding(top = 20.dp),
        ) {
            InfoRow("验证窗口", "物品详情里改 · 默认 30 分钟")
            InfoRow("提醒方式", "通知 · 震动 · 锁屏全屏")
            InfoRow("照片存储", "只存本机")
            InfoRow("连续提醒", "开启")
            DashedDivider(Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "专注模式 · 到点钉住提醒页", style = PwType.small, color = p.inkSoft)
                Switch(
                    checked = vm.focusLock,
                    onCheckedChange = { vm.applyFocusLock(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = p.onAmber,
                        checkedTrackColor = p.amber,
                        uncheckedThumbColor = p.faint,
                        uncheckedTrackColor = p.surfaceAlt,
                    ),
                )
            }
            Text(
                text = if (vm.focusLock) {
                    "到点时会把提醒页固定在屏幕上（系统「屏幕固定」）。要离开得按住返回+多任务，等于明确选择「这次不拍」。"
                } else {
                    "关着的时候：上滑回桌面拦不住，但一回到 App 就还是这一页。打开后到点会钉住，防手滑。"
                },
                style = PwType.tiny,
                color = if (vm.focusLock) p.amber else p.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 凭物数据（M5：导出 / 清理）
        val stats = photoStats
        CollapseCard(
            title = "凭物数据",
            summary = if (stats == null) "统计中…" else "${stats.count} 张 · ${formatBytes(stats.bytes)}",
        ) {
            InfoRow(
                "本机照片",
                if (stats == null) "统计中…" else "${stats.count} 张 · ${formatBytes(stats.bytes)}",
            )
            InfoRow("导出格式", "CSV · Excel 可直接打开")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmallButton("导出 CSV", primary = true, modifier = Modifier.weight(1f)) {
                    scope.launch {
                        val file = vm.exportCsvFile()
                        if (file == null) {
                            Toast.makeText(context, "导出失败，稍后再试", Toast.LENGTH_SHORT).show()
                        } else {
                            shareCsv(context, file)
                        }
                    }
                }
                SmallButton("清理旧照片", primary = false, modifier = Modifier.weight(1f)) {
                    vm.cleanupPhotos { removed ->
                        Toast.makeText(
                            context,
                            if (removed == 0) {
                                "没有可清理的照片"
                            } else {
                                "清理了 $removed 张（含已删物品的照片）"
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                        scope.launch { photoStats = vm.photoStats() }
                    }
                }
            }
            Text(
                text = "照片只存本机；导出的文件在缓存里，7 天后自动清理。" +
                    "删掉的物品，照片也会在这清掉，凭物里那条记录还留着。",
                style = PwType.tiny,
                color = p.faint,
                modifier = Modifier.padding(top = 10.dp),
            )
            DashedDivider(modifier = Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showRecordPicker = true }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "自定义删除", style = PwType.small, color = p.ink)
                    Text(
                        text = "按物品挑出某几条记录，连同照片一起清掉",
                        style = PwType.label,
                        color = p.faint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(text = "挑着删 ›", style = PwType.label, color = p.amber)
            }
        }

        CollapseCard(
            title = "关于",
            summary = "拍下来，才算真的做过",
        ) {
            InfoRow("版本", "v" + BuildConfig.VERSION_NAME)
            InfoRow("数据", "照片存本机 · 可导出 CSV")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable { showClearDialog = true },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "清空全部凭物", style = PwType.small, color = p.red)
                Text(text = "需二次确认", style = PwType.label, color = p.faint)
            }
        }

        // 页脚品牌：小的猫箱 lockup（跟着主题换色），下面一行老话。
        Image(
            painter = painterResource(
                if (mode == ThemeMode.PAPER) R.drawable.pw_lockup_paper else R.drawable.pw_lockup_dark,
            ),
            contentDescription = "凭物 MEMENTO",
            modifier = Modifier
                .padding(top = 30.dp)
                .width(148.dp)
                .alpha(0.92f),
        )
        Text(
            text = "拍下来，才算真的做过",
            style = PwType.label,
            color = p.faint,
            modifier = Modifier.padding(top = 10.dp, bottom = 30.dp),
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空全部凭物？", style = PwType.title, color = p.ink) },
            text = {
                Text(
                    "照片记录会删除，物品和提醒保留。这个操作不能撤销。",
                    style = PwType.small,
                    color = p.inkSoft,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearEvidence()
                    showClearDialog = false
                }) { Text("清空", color = p.red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("算了", color = p.faint) }
            },
            containerColor = p.surface,
        )
    }

    if (showRecordPicker) {
        RecordPickerDialog(
            days = days,
            onDismiss = { showRecordPicker = false },
            onConfirm = { ids ->
                showRecordPicker = false
                vm.deleteRecords(ids) { removed ->
                    Toast.makeText(
                        context,
                        if (removed == 0) "没有删除任何记录" else "删掉了 $removed 条记录",
                        Toast.LENGTH_SHORT,
                    ).show()
                    scope.launch { photoStats = vm.photoStats() }
                }
            },
        )
    }
}

/**
 * 到点提醒的权限状态：通知（Android 13+）与精确闹钟（Android 12+）。
 * 没有权限也尽量提醒，只是可能晚几分钟——这里让人看见并一键去开。
 */
@Composable
private fun ReminderPermissionCard() {
    val p = LocalPwPalette.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ReminderNotifier.canPost(context)) }
    var exact by remember { mutableStateOf(canScheduleExact(context)) }
    var fullScreen by remember { mutableStateOf(ReminderNotifier.canUseFullScreen(context)) }
    var lastFire by remember { mutableStateOf(ReminderHealth.lastFireText(context)) }
    var selfTest by remember { mutableStateOf(ReminderHealth.selfTestText(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = ReminderNotifier.canPost(context)
                exact = canScheduleExact(context)
                fullScreen = ReminderNotifier.canUseFullScreen(context)
                lastFire = ReminderHealth.lastFireText(context)
                selfTest = ReminderHealth.selfTestText(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
    }

    CollapseCard(
        title = "到点提醒",
        summary = if (granted) "已开启" else "未开启",
        modifier = Modifier.padding(top = 20.dp),
        initiallyExpanded = !granted,
    ) {
        Text(
            text = "到点发一条高优先级通知；锁屏上点开就是全屏验证页。",
            style = PwType.tiny,
            color = p.faint,
            modifier = Modifier.padding(top = 6.dp),
        )
        DashedDivider(Modifier.padding(vertical = 12.dp))
        InfoRow("通知权限", if (granted) "已允许" else "未允许")
        InfoRow("精确闹钟", if (exact) "已允许" else "未允许 · 可能晚几分钟")
        InfoRow("锁屏全屏", if (fullScreen) "已允许" else "未允许 · 只弹通知")
        InfoRow("最近一次提醒", lastFire ?: "还没响过")
        if (selfTest != null) InfoRow("到点自检", selfTest!!)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallButton("通知设置", primary = false, modifier = Modifier.weight(1f)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                }
            }
            SmallButton("精确闹钟", primary = true, modifier = Modifier.weight(1f)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${context.packageName}")),
                        )
                    }
                } else {
                    exact = true
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmallButton("锁屏全屏", primary = false, modifier = Modifier.weight(1f)) {
                    openLockScreenSettings(context)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallButton("到点自检（1 分钟）", primary = true, modifier = Modifier.weight(1f)) {
                ReminderScheduler.scheduleSelfTest(context, 1)
                Toast.makeText(
                    context,
                    "1 分钟后响。把屏幕按灭，等它自己亮起来。",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        Text(
            text = "自检不会写进凭物。响过之后回来看这里，会写「准点」还是「晚了 N 分」。",
            style = PwType.tiny,
            color = p.faint,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (isMiuiLike()) {
            Text(
                text = "小米 / 红米还要在系统权限里允许「后台弹出界面」，锁屏全屏页才会弹出来。",
                style = PwType.tiny,
                color = p.amber,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmallButton("自启动", primary = false, modifier = Modifier.weight(1f)) {
                    openAutoStartSettings(context)
                }
                SmallButton("省电策略", primary = false, modifier = Modifier.weight(1f)) {
                    openPowerSettings(context)
                }
            }
            Text(
                text = "这两项给成「允许」「无限制」，息屏到点才会准时亮屏。",
                style = PwType.tiny,
                color = p.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "还有一点要说清楚：在最近任务里把凭物清理掉，等于强制停止，"
                    + "系统会连它的闹钟一起取消——那之后到点就不会响，直到你再打开一次。"
                    + "想让它常驻，可以在最近任务里长按凭物 → 上锁。",
                style = PwType.tiny,
                color = p.amber,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            text = "验证窗口可在物品详情里改（默认 30 分钟）。",
            style = PwType.tiny,
            color = p.faint,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private fun canScheduleExact(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

/** 小米 / 红米 / POCO 的系统对全屏页多一道「后台弹出界面」权限。 */
private fun isMiuiLike(): Boolean {
    val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    return "xiaomi" in brand || "redmi" in brand || "poco" in brand
}

/** 打开 MIUI「自启动管理」，让 App 在息屏后还能收到闹钟广播。 */
private fun openAutoStartSettings(context: android.content.Context) {
    val candidates = listOf(
        Intent().setComponent(
            android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        ),
        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    }
}

/** 打开 MIUI「省电策略」，把 App 设成「无限制」。 */
private fun openPowerSettings(context: android.content.Context) {
    val candidates = listOf(
        Intent().setComponent(
            android.content.ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ),
        ).putExtra("package_name", context.packageName).putExtra("package_label", "凭物"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    }
}

/** 优先打开 MIUI 的权限编辑页；不是小米或打不开时退回系统「全屏通知」页。 */
private fun openLockScreenSettings(context: android.content.Context) {
    val miui = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
        putExtra("extra_pkgname", context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val standard = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching {
        if (isMiuiLike() && miui.resolveActivity(context.packageManager) != null) {
            context.startActivity(miui)
        } else {
            context.startActivity(standard)
        }
        true
    }.getOrDefault(false)
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * 真机验收入口：不用等到点，手动发一条通知 / 打开全屏验证页。
 * 只在 debug 包里出现，发布版不会有这块。
 */
@Composable
private fun DebugCard() {
    if (!BuildConfig.DEBUG) return
    val p = LocalPwPalette.current
    val context = LocalContext.current
    CollapseCard(
        title = "测试入口 · 仅 debug",
        summary = "手动发提醒",
    ) {
        Text(
            text = "真机验收时用：不用等到点，立刻发一条提醒，或打开全屏验证页。",
            style = PwType.tiny,
            color = p.faint,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallButton("发测试提醒", primary = true, modifier = Modifier.weight(1f)) {
                ReminderNotifier.notify(context, "测试物品", 4, "测试提醒")
                Toast.makeText(context, "已发；下拉通知栏或锁屏看看", Toast.LENGTH_SHORT).show()
            }
            SmallButton("全屏验证页", primary = false, modifier = Modifier.weight(1f)) {
                context.startActivity(
                    Intent(context, ReminderActivity::class.java).apply {
                        putExtra(ReminderActivity.EXTRA_ITEM_NAME, "测试物品")
                        putExtra(ReminderActivity.EXTRA_COUNT, 4)
                        putExtra(ReminderActivity.EXTRA_TIME_LABEL, "测试提醒")
                    },
                )
            }
        }
        SmallButton(
            "5 秒后发提醒（先锁屏）",
            primary = false,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Toast.makeText(context, "5 秒后发；现在按电源键锁屏", Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                ReminderNotifier.notify(context, "测试物品", 4, "测试提醒")
            }, 5_000)
        }
    }
}

/**
 * 在线大模型识别（用户自备 Key，OpenAI 兼容端点）。
 * 默认关闭；开启后照片会发到用户填的服务商——文案必须说清楚。
 */
@Composable
private fun OnlineAiCard(vm: PingWuViewModel) {
    val p = LocalPwPalette.current
    val scope = rememberCoroutineScope()
    var draft by remember(vm.aiConfig) { mutableStateOf(vm.aiConfig) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    CollapseCard(
        title = "在线识别 · 实验",
        summary = when {
            draft.enabled && draft.isReady -> "已开启"
            draft.enabled -> "开启中 · 还没填完"
            else -> "关闭 · 本机判定"
        },
        modifier = Modifier.padding(top = 20.dp),
        initiallyExpanded = draft.enabled,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "用在线模型判定", style = PwType.small, color = p.inkSoft)
            Switch(
                checked = draft.enabled,
                onCheckedChange = { on ->
                    val next = draft.copy(enabled = on)
                    draft = next
                    testResult = null
                    savedHint = null
                    // 开关是用户的真实选择，立刻落盘：否则离开这一页（或进程被回收）就被当成「没开」。
                    vm.saveAiConfig(next)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = p.onAmber,
                    checkedTrackColor = p.amber,
                    uncheckedThumbColor = p.faint,
                    uncheckedTrackColor = p.surfaceAlt,
                ),
            )
        }
        Text(
            text = "开关会马上保存，离开这一页也不会被关掉。",
            style = PwType.tiny,
            color = p.mint,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "开启后，每次按快门都由模型判断这张照片算不算完成：会把这次拍的照片和它的登记照一起发过去比较，没通过不记入凭物。默认关闭。",
            style = PwType.tiny,
            color = p.faint,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "开启后，照片会发送到你填写的服务商；Key 只存本机，不上传。",
            style = PwType.tiny,
            color = p.amber,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "关闭模型思考", style = PwType.small, color = p.inkSoft)
            Switch(
                checked = draft.disableThinking,
                onCheckedChange = { on ->
                    val next = draft.copy(disableThinking = on)
                    draft = next
                    testResult = null
                    savedHint = null
                    // 和上面的开关一样：这是用户的真实选择，立刻落盘。
                    vm.saveAiConfig(next)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = p.onAmber,
                    checkedTrackColor = p.amber,
                    uncheckedThumbColor = p.faint,
                    uncheckedTrackColor = p.surfaceAlt,
                ),
            )
        }
        Text(
            text = "识物不需要思考链：关掉更快更省（实测 deepseek-flash 2.3 秒 → 0.9 秒、思考 token 归零）。" +
                "有的服务商不认这个参数，会自动去掉再试一次，不影响判定。",
            style = PwType.tiny,
            color = p.faint,
            modifier = Modifier.padding(top = 6.dp),
        )

        DashedDivider(Modifier.padding(vertical = 12.dp))

        // 预设
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiConfig.PRESETS.take(3).forEach { preset ->
                PresetChip(preset.name) {
                    draft = draft.copy(baseUrl = preset.baseUrl, model = preset.model)
                    testResult = null
                    savedHint = null
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiConfig.PRESETS.drop(3).forEach { preset ->
                PresetChip(preset.name) {
                    draft = draft.copy(baseUrl = preset.baseUrl, model = preset.model)
                    testResult = null
                    savedHint = null
                }
            }
        }

        if (draft.baseUrl.contains("deepseek", ignoreCase = true)) {
            Text(
                text = "deepseek-chat 和 deepseek-flash 能看图，v4-pro 不收图；" +
                    "「测试连接」会发一张小图，认不出颜色就说明这个模型看不了图。",
                style = PwType.tiny,
                color = p.amber,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        AiField("端点（Base URL）", draft.baseUrl, "https://api.openai.com/v1") {
            draft = draft.copy(baseUrl = it); testResult = null
        }
        AiField("模型名", draft.model, "gpt-4o-mini / qwen-vl-max …") {
            draft = draft.copy(model = it); testResult = null
        }
        AiField(
            label = "API Key",
            value = draft.apiKey,
            placeholder = "sk-…",
            password = true,
            keyboardType = KeyboardType.Password,
        ) { draft = draft.copy(apiKey = it); testResult = null }

        if (testResult != null) {
            Text(
                text = testResult.orEmpty(),
                style = PwType.tiny,
                color = if (testResult!!.startsWith("通过")) p.mint else p.red,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (savedHint != null) {
            Text(text = savedHint.orEmpty(), style = PwType.tiny, color = p.mint, modifier = Modifier.padding(top = 8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallButton("保存配置", primary = false, modifier = Modifier.weight(1f)) {
                vm.saveAiConfig(draft)
                savedHint = "已保存到本机"
                testResult = null
            }
            SmallButton(
                text = if (testing) "测试中…" else "测试连接",
                primary = true,
                enabled = !testing,
                modifier = Modifier.weight(1f),
            ) {
                val config = draft.copy(baseUrl = draft.baseUrl.trim(), model = draft.model.trim(), apiKey = draft.apiKey.trim())
                if (config.baseUrl.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) {
                    testResult = "端点、模型名和 Key 都要填"
                    return@SmallButton
                }
                testing = true
                testResult = null
                scope.launch {
                    val result = vm.testAi(config)
                    testing = false
                    testResult = result.fold(
                        onSuccess = {
                            // 连通性验过，就把这份配置一起存下：用户会认为「测通＝生效」，不该还要再点一次保存。
                            vm.saveAiConfig(config)
                            savedHint = "已保存到本机"
                            "通过：${it.take(40)}"
                        },
                        onFailure = { "失败：${it.message?.take(160) ?: "未知错误"}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetChip(text: String, onClick: () -> Unit) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, p.line, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = text, style = PwType.label.copy(letterSpacing = 0.3.sp), color = p.inkSoft)
    }
}

@Composable
private fun AiField(
    label: String,
    value: String,
    placeholder: String,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val p = LocalPwPalette.current
    Text(text = label, style = PwType.tiny, color = p.faint, modifier = Modifier.padding(top = 10.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        placeholder = { Text(placeholder, style = PwType.small, color = p.faint) },
        textStyle = PwType.small,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.amber,
            unfocusedBorderColor = p.line,
            focusedTextColor = p.ink,
            unfocusedTextColor = p.ink,
            cursorColor = p.amber,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SmallButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalPwPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) p.amber.copy(alpha = if (enabled) 1f else 0.5f) else Color.Transparent)
            .border(1.dp, if (primary) Color.Transparent else p.line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PwType.small.copy(fontWeight = FontWeight.Medium),
            color = if (primary) (if (p.isDark) Color(0xFF0A1014) else Color(0xFFFBF6EC)) else p.inkSoft,
        )
    }
}

@Composable
private fun ThemeChoice(
    title: String,
    sub: String,
    selected: Boolean,
    swatch: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPwPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(p.surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) p.amber else p.lineSoft,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(swatch),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = PwType.body.copy(fontWeight = FontWeight.Medium), color = p.ink)
            Text(text = if (selected) "●" else sub, style = PwType.label, color = if (selected) p.amber else p.faint)
        }
    }
}

/**
 * 提醒声音：选系统铃声、上传自己的铃声、开关震动，并给一个试听。
 *
 * 铃声不走通知渠道（渠道建立后改不了声音），由 AlarmPlayer 在响铃时读取这里的偏好播放。
 */
@Composable
private fun ReminderSoundCard() {
    val p = LocalPwPalette.current
    val context = LocalContext.current
    var sound by remember { mutableStateOf(ReminderSoundPrefs.load(context)) }

    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        @Suppress("DEPRECATION")
        val uri: Uri? = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val next = if (uri == null) {
            // 选了「无 / 静音」：到点只震动，不响铃；同时清掉之前上传的自定义铃声。
            ReminderSoundPrefs.clearCustom(context).copy(
                ringtoneUri = "",
                ringtoneLabel = "",
                silent = true,
            )
        } else {
            val label = runCatching {
                RingtoneManager.getRingtone(context, uri)?.getTitle(context)?.toString()
            }.getOrNull()
            // 换了系统铃声就清掉自定义铃声，否则自定义仍优先，用户会觉得「选了没变化」。
            ReminderSoundPrefs.clearCustom(context).copy(
                ringtoneUri = uri.toString(),
                ringtoneLabel = label ?: "系统铃声",
                silent = false,
            )
        }
        sound = next
        ReminderSoundPrefs.save(context, next)
        Toast.makeText(context, "铃声已保存：${next.displayName}", Toast.LENGTH_SHORT).show()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val resolver = context.contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull().orEmpty().ifBlank { "自定义铃声" }
            val mime = resolver.getType(uri).orEmpty()
            val ext = extensionForMime(mime)
                .ifBlank { name.substringAfterLast('.', "").ifBlank { "mp3" } }
            val next = ReminderSoundPrefs.importCustom(context, name, ext) { target ->
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("打不开这个文件")
            }
            sound = next
            Toast.makeText(context, "已设为提醒铃声：$name", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    CollapseCard(
        title = "提醒声音",
        summary = sound.displayName + if (sound.vibrate) " · 震动开" else " · 震动关",
        modifier = Modifier.padding(top = 20.dp),
    ) {
        InfoRow("当前铃声", sound.displayName)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallButton("选系统铃声", primary = true, modifier = Modifier.weight(1f)) {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                    if (sound.ringtoneUri.isNotBlank()) {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(sound.ringtoneUri))
                    }
                }
                runCatching { ringtonePicker.launch(intent) }.onFailure {
                    Toast.makeText(context, "系统没有铃声选择器", Toast.LENGTH_SHORT).show()
                }
            }
            SmallButton("从文件选", primary = false, modifier = Modifier.weight(1f)) {
                runCatching { filePicker.launch(arrayOf("audio/*")) }.onFailure {
                    Toast.makeText(context, "打不开文件选择器", Toast.LENGTH_SHORT).show()
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallButton("试听 3 秒", primary = false, modifier = Modifier.weight(1f)) {
                AlarmPlayer.start(context, reason = "preview")
                Handler(Looper.getMainLooper()).postDelayed({ AlarmPlayer.stop() }, 3_000)
            }
            if (sound.customPath.isNotBlank()) {
                SmallButton("移除自定义", primary = false, modifier = Modifier.weight(1f)) {
                    sound = ReminderSoundPrefs.clearCustom(context)
                }
            }
        }
        DashedDivider(Modifier.padding(vertical = 12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "震动", style = PwType.small, color = p.inkSoft)
            Switch(
                checked = sound.vibrate,
                onCheckedChange = { checked ->
                    val next = sound.copy(vibrate = checked)
                    sound = next
                    ReminderSoundPrefs.save(context, next)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = p.onAmber,
                    checkedTrackColor = p.amber,
                    uncheckedThumbColor = p.faint,
                    uncheckedTrackColor = p.surfaceAlt,
                ),
            )
        }
        Text(
            text = "到点时用闹钟音量响铃，静音也会响；铃声只存在本机。",
            style = PwType.tiny,
            color = p.faint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun extensionForMime(mime: String): String = when (mime.lowercase()) {
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
    "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
    "audio/ogg", "audio/opus" -> "ogg"
    "audio/flac", "audio/x-flac" -> "flac"
    else -> ""
}

/**
 * 我的页的小卡片默认收起：只留标题和一行摘要，点一下才展开详情。
 * 主题卡不在这里——它一直展开。
 */
@Composable
private fun CollapseCard(
    title: String,
    summary: String,
    modifier: Modifier = Modifier.padding(top = 12.dp),
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPwPalette.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    TicketCard(modifier, padding = 16.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PwLabel(title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = summary, style = PwType.tiny, color = p.faint)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = PwType.label,
                    color = p.amber,
                )
            }
        }
        if (expanded) {
            DashedDivider(Modifier.padding(vertical = 12.dp))
            content()
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%d KB".format(bytes / 1024L)
    else -> "$bytes B"
}

/** M5：把导出的 CSV 交给系统分享（FileProvider 只读授权）。 */
private fun shareCsv(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出凭物 CSV"))
    }.onFailure { error ->
        Toast.makeText(context, "导出失败：${error.message}", Toast.LENGTH_LONG).show()
    }
}

