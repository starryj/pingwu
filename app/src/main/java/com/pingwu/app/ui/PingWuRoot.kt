package com.pingwu.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pingwu.app.ui.screens.AddItemScreen
import com.pingwu.app.reminder.AlarmPlayer
import com.pingwu.app.ui.screens.AlarmScreen
import com.pingwu.app.ui.screens.CaptureScreen
import com.pingwu.app.ui.screens.EvidenceScreen
import com.pingwu.app.ui.screens.HomeScreen
import com.pingwu.app.ui.screens.ItemDetailScreen
import com.pingwu.app.ui.screens.ItemsScreen
import com.pingwu.app.ui.screens.ProfileScreen
import com.pingwu.app.ui.screens.ProofScreen
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType
import com.pingwu.app.ui.theme.ThemeMode
import com.pingwu.app.ui.theme.ThemePrefs
import kotlinx.coroutines.flow.collect

object Routes {
    const val HOME = "home"
    const val ITEMS = "items"
    const val EVIDENCE = "evidence"
    const val PROFILE = "profile"
    const val ITEM_DETAIL = "item/{itemId}"
    const val CAPTURE = "capture"
    const val ADD = "add"

    fun itemDetail(id: Int) = "item/$id"
}

private data class TabSpec(val route: String, val label: String, val glyph: Glyph)

private enum class Glyph { TODAY, ITEM, PROOF, ME }

private val tabs = listOf(
    TabSpec(Routes.HOME, "今日", Glyph.TODAY),
    TabSpec(Routes.ITEMS, "物品", Glyph.ITEM),
    TabSpec(Routes.EVIDENCE, "凭物", Glyph.PROOF),
    TabSpec(Routes.PROFILE, "我的", Glyph.ME),
)

@Composable
fun PingWuRoot(
    mode: ThemeMode,
    onToggleTheme: () -> Unit,
    initialRoute: String? = null,
) {
    val p = LocalPwPalette.current
    val vm: PingWuViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // 物品详情也算「有底栏」的页面：这样从物品列表点进详情时，底栏不会中途消失、
    // 列表也不会因为在滑出时被抽掉底栏而产生重排（底部那一下「跳版」就是这么来的）。
    val isTabRoute = route == Routes.HOME || route == Routes.ITEMS ||
        route == Routes.EVIDENCE || route == Routes.PROFILE || route == Routes.ITEM_DETAIL

    // 底栏要等推进动画走完再收起来。
    // 如果一换页就把底栏摘掉，正在滑出的那一页会立刻重排（内容往下撑），
    // 底部就出现一下「跳版」；等新页滑满屏再收，就完全看不出来。
    var showBar by remember { mutableStateOf(isTabRoute) }
    LaunchedEffect(route) {
        if (isTabRoute) {
            showBar = true
        } else {
            kotlinx.coroutines.delay(PUSH_MS.toLong())
            showBar = false
        }
    }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showNameEditor by remember { mutableStateOf(false) }

    // 从相册选头像：系统相册选择器，不需要存储权限；只在本机裁成 512 方图。
    val ctx = LocalContext.current
    val avatarPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            vm.saveAvatarPhotoFromUri(uri) { key ->
                if (key == null) {
                    Toast.makeText(ctx, "这张读不出来，换一张试试", Toast.LENGTH_SHORT).show()
                }
            }
            showAvatarPicker = false
        }
    }

    LaunchedEffect(route) {
        android.util.Log.d("PwNav", "route=$route")
    }

    // 从通知点进来时，直接落在拍照验证页。
    // 这里踩过一次真机崩溃（0.17.2，22:25）：这个 effect 比 NavHost 先声明，冷启动那一帧
    // NavHost 可能还没把导航图装好，直接 navigate 会抛
    // 「Cannot navigate to capture. Navigation graph has not been set」，整个进程被带走。
    // 现在先等图就绪再跳，而且无论成败都兜住，绝不因为一次跳转把 App 崩掉。
    LaunchedEffect(initialRoute) {
        if (initialRoute != Routes.CAPTURE) return@LaunchedEffect
        if (nav.currentDestination?.route == Routes.CAPTURE) return@LaunchedEffect
        var waited = 0
        while (nav.currentDestination == null && waited < 2000) {
            kotlinx.coroutines.delay(50)
            waited += 50
        }
        if (nav.currentDestination?.route == Routes.CAPTURE) return@LaunchedEffect
        runCatching {
            nav.navigate(Routes.CAPTURE) { launchSingleTop = true }
        }.onFailure {
            android.util.Log.w("PwNav", "navigate to capture failed: ${it.message}")
        }
    }

    // 前台时到点自己弹全屏页：解锁状态下系统不给 App 用全屏 Intent，只能应用内补。
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshDue()
            kotlinx.coroutines.delay(15_000)
        }
    }

    // 从桌面或别的 App 回到前台时立刻补一次：
    // HOME 手势是系统行为拦不住，但一回到 App 就还是这一步。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshDue()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 专注模式：到点时把提醒页钉在屏幕上（系统「屏幕固定」），要长按组合键才能解。
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(vm.dueTask != null, vm.focusLock) {
        if (activity == null) return@LaunchedEffect
        val shouldLock = vm.dueTask != null && vm.focusLock
        runCatching {
            if (shouldLock) activity.startLockTask() else activity.stopLockTask()
        }
    }

    // 到点就响：应用内弹到点页时同样响铃振动（手机可能被放在一边没人看）。
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(vm.dueTask != null) {
        if (vm.dueTask != null) AlarmPlayer.start(appContext, reason = "inapp") else AlarmPlayer.stop()
    }

    // 外层 Box：让凭据页能盖住底栏与整屏。
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                PwBottomBar(
                    currentRoute = route,
                    onSelect = { target ->
                        android.util.Log.d("PwNav", "tap target=$target from=$route")
                        if (target != route) {
                            // 不用 saveState/restoreState：当 target 恰好等于 popUpTo 目的地（今日）时，
                            // Navigation 会把刚保存的返回栈立刻恢复回来，页面就停在原处不动。
                            // 先尝试「回退到已在栈里的 tab」，回退不了再前进并清掉中间页。
                            val popped = nav.popBackStack(target, inclusive = false)
                            if (!popped) {
                                nav.navigate(target) {
                                    popUpTo(Routes.HOME) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                        android.util.Log.d("PwNav", "after navigate current=${nav.currentDestination?.route}")
                    },
                )
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(p.bg, p.bgGlow, p.bg)))
                .padding(inner),
        ) {
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                // 四个 tab 之间直接切：不做交叉淡入，避免两屏叠在一起。
                // 详情 / 添加 / 拍照这类「推进去」的页面单独配左右滑动（见 PUSH_* 常量）。
                // 推进用滑动（三个推进页各自覆盖 popExit）；
                // 退回时被盖住的那一页从左边滑回来，所以要给默认 popEnter 配动画，
                // 否则退回会变成「啪」一下直接切，和推进不对称。
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { PUSH_POP_ENTER },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Routes.HOME) {
                    android.util.Log.d("PwNav", "compose HOME")
                    PwPage {
                    HomeScreen(
                        state = state,
                        avatar = vm.avatar,
                        avatarPhoto = vm.avatarPhoto,
                        userName = vm.displayName,
                        onAvatarClick = { showAvatarPicker = true },
                        onOpenItem = { nav.navigate(Routes.itemDetail(it)) },
                        onCapture = { nav.navigate(Routes.CAPTURE) },
                        onSeeEvidence = { nav.navigate(Routes.EVIDENCE) },
                        onAdd = {
                            vm.endEdit()
                            nav.navigate(Routes.ADD)
                        },
                    )
                    }
                }
                composable(Routes.ITEMS) {
                    PwPage {
                    ItemsScreen(
                        state = state,
                        onOpenItem = { nav.navigate(Routes.itemDetail(it)) },
                        onDeleteItem = { vm.archiveItem(it) },
                        onAdd = {
                            vm.endEdit()
                            nav.navigate(Routes.ADD)
                        },
                        onSeeEvidence = { nav.navigate(Routes.EVIDENCE) },
                    )
                    }
                }
                composable(
                    route = Routes.ITEM_DETAIL,
                    enterTransition = { PUSH_ENTER },
                    exitTransition = { PUSH_EXIT },
                    popEnterTransition = { PUSH_POP_ENTER },
                    popExitTransition = { PUSH_POP_EXIT },
                ) { entry ->
                    val id = entry.arguments?.getString("itemId")?.toIntOrNull() ?: 0
                    // 「认得的样子」是本地文件，随块数变化重新读一次（拍完回来能看到新的）。
                    val subjectCount = state.items.firstOrNull { it.id == id }?.subjectCount ?: 0
                    var subjectKeys by remember(id) { mutableStateOf<List<String>>(emptyList()) }
                    LaunchedEffect(id, subjectCount) {
                        subjectKeys = vm.subjectsOf(id).map { it.coverPhotoKey }
                    }
                    PwPage {
                    ItemDetailScreen(
                        itemId = id,
                        items = state.items,
                        photos = state.photos,
                        stats = state.stats,
                        windowMinutes = state.windows[id] ?: 30,
                        onWindowChange = { minutes -> vm.updateWindow(id, minutes) },
                        onRegisterPhoto = {
                            vm.beginTemplateCapture(id)
                            nav.navigate(Routes.CAPTURE)
                        },
                        onEdit = {
                            vm.beginEdit(id)
                            nav.navigate(Routes.ADD)
                        },
                        onDelete = {
                            vm.archiveItem(id)
                            nav.popBackStack()
                        },
                        onBack = { nav.popBackStack() },
                        subjectPhotoKeys = subjectKeys,
                    )
                    }
                }
                composable(Routes.EVIDENCE) {
                    PwPage {
                    EvidenceScreen(
                        state = state,
                        onOpenProof = { day -> vm.openProof(day.toProof()) },
                        onDeleteRecords = { ids -> vm.deleteRecords(ids) },
                    )
                    }
                }
                composable(Routes.PROFILE) {
                    PwPage {
                    ProfileScreen(
                        mode = mode,
                        onToggleTheme = onToggleTheme,
                        vm = vm,
                        onAvatarClick = { showAvatarPicker = true },
                        onNameClick = { showNameEditor = true },
                        days = state.days,
                    )
                    }
                }
                composable(
                    route = Routes.ADD,
                    enterTransition = { PUSH_ENTER },
                    exitTransition = { PUSH_EXIT },
                    popEnterTransition = { PUSH_POP_ENTER },
                    popExitTransition = { PUSH_POP_EXIT },
                ) {
                    PwPage {
                    AddItemScreen(
                        editing = vm.editingItemId?.let { id ->
                            state.items.firstOrNull { it.id == id }
                        },
                        onBack = {
                            vm.endEdit()
                            nav.popBackStack()
                        },
                        onSave = { draft ->
                            vm.saveDraft(draft) { nav.popBackStack() }
                        },
                    )
                    }
                }
                composable(
                    route = Routes.CAPTURE,
                    enterTransition = { PUSH_ENTER },
                    exitTransition = { PUSH_EXIT },
                    popEnterTransition = { PUSH_POP_ENTER },
                    popExitTransition = { PUSH_POP_EXIT },
                ) {
                    PwPage(topScrim = false) {
                        CaptureScreen(
                            onBack = {
                                nav.popBackStack()
                            },
                            vm = vm,
                        )
                    }
                }
            }
        }
    }

    // 前台到点：叠一层和锁屏页一样的到点页；正在拍照时不再叠。
    val due = vm.dueTask
    if (due != null && route != Routes.CAPTURE) {
        AlarmScreen(
            itemName = due.itemName,
            count = due.countPerDose,
            timeLabel = due.timeLabel,
            coverPhotoKey = due.coverPhotoKey,
            onCapture = {
                vm.clearDue()
                nav.navigate(Routes.CAPTURE) { launchSingleTop = true }
            },
            onLater = { vm.snoozeDue() },
        )
    }

    // 凭据页：叠在整棵界面之上，点票面外也能关。
    val proof = vm.proof
    if (proof != null) {
        ProofScreen(
            proof = proof,
            justCaptured = vm.proofFresh,
            onClose = { vm.closeProof() },
        )
    }

    if (showAvatarPicker) {
        AvatarPickerDialog(
            current = vm.avatar,
            hasPhoto = vm.avatarPhoto.isNotBlank(),
            onPick = { picked ->
                vm.pickAvatar(picked)
                vm.clearAvatarPhoto()
                showAvatarPicker = false
            },
            onPickPhoto = { avatarPhotoLauncher.launch("image/*") },
            onClearPhoto = {
                vm.clearAvatarPhoto()
                showAvatarPicker = false
            },
            onDismiss = { showAvatarPicker = false },
        )
    }

    if (showNameEditor) {
        NameEditorDialog(
            current = vm.displayName,
            onConfirm = { name ->
                vm.pickName(name)
                showNameEditor = false
            },
            onDismiss = { showNameEditor = false },
        )
    }
    }
}

/** 改称呼：一句话输入，最多 12 个字。 */
@Composable
private fun NameEditorDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPwPalette.current
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(text = "怎么称呼你", style = PwType.title, color = p.ink) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 12) text = it },
                    singleLine = true,
                    placeholder = { Text(text = "朋友", style = PwType.small, color = p.faint) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "只存在本机，用在首页问候和「我的」里。",
                    style = PwType.tiny,
                    color = p.faint,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            Text(
                text = "好",
                style = PwType.body,
                color = p.amber,
                modifier = Modifier
                    .clickable { onConfirm(text) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
        dismissButton = {
            Text(
                text = "算了",
                style = PwType.body,
                color = p.faint,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
    )
}

/** 推进去 / 退回的横向滑动时长与位移（渐快渐慢，220ms 一屏，不拖沓）。 */
private const val PUSH_MS = 220
private val PUSH_ENTER: EnterTransition =
    slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { it }
private val PUSH_EXIT: ExitTransition =
    slideOutHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { -it / 4 }
private val PUSH_POP_ENTER: EnterTransition =
    slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { -it / 4 }
private val PUSH_POP_EXIT: ExitTransition =
    slideOutHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { it }

/**
 * 每个页面自己铺一层不透明底。
 *
 * 底色本来只画在 NavHost 外面那一层 Box 上，页面自己的背景是透明的；
 * 换页那一刻两个页面会同时存在一帧，透明背景就互相透出来（底部看到上一页的内容），
 * 看起来像「抽帧」。所以每页都自己铺一次同样的渐变，盖住底下的东西。
 */
@Composable
private fun PwPage(
    // 滚动页的内容会滑到状态栏底下，和系统时间叠在一起；铺一条和底色同色的遮罩盖住最上面那截。
    // 拍照页是全屏相机，不要遮罩。
    topScrim: Boolean = true,
    content: @Composable () -> Unit,
) {
    val pagePalette = LocalPwPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(pagePalette.bg, pagePalette.bgGlow, pagePalette.bg))),
    ) {
        content()
        if (topScrim) {
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topInset + 10.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(pagePalette.bg, pagePalette.bg, pagePalette.bg.copy(alpha = 0f)),
                        ),
                    ),
            )
        }
    }
}

/** 换个头像：从相册选一张照片，或者挑一个喜欢的字，都存在本机。 */
@Composable
private fun AvatarPickerDialog(
    current: String,
    hasPhoto: Boolean,
    onPick: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPwPalette.current
    var draft by remember { mutableStateOf(if (hasPhoto) "" else current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(text = "换个头像", style = PwType.title, color = p.ink) },
        text = {
            Column {
                Text(
                    text = "用一张自己的照片，或者写一个字",
                    style = PwType.small,
                    color = p.faint,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(p.amberSoft)
                            .border(1.dp, p.amber, RoundedCornerShape(14.dp))
                            .clickable(onClick = onPickPhoto),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "从相册选", style = PwType.small, color = p.ink)
                    }
                    if (hasPhoto) {
                        Box(
                            modifier = Modifier
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(p.surfaceAlt)
                                .border(1.dp, p.line, RoundedCornerShape(14.dp))
                                .clickable(onClick = onClearPhoto)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "换回字", style = PwType.small, color = p.faint)
                        }
                    }
                }
                Text(
                    text = "自己写：一个字或两个字都行",
                    style = PwType.tiny,
                    color = p.faint,
                    modifier = Modifier.padding(top = 18.dp),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 2) draft = it },
                    singleLine = true,
                    placeholder = { Text(text = "比如 溪", style = PwType.small, color = p.faint) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                Text(
                    text = "或者挑一个现成的：",
                    style = PwType.tiny,
                    color = p.faint,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemePrefs.AVATARS.forEach { char ->
                        val selected = !hasPhoto && char == current
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) p.amberSoft else p.surfaceAlt)
                                .border(1.dp, if (selected) p.amber else p.line, RoundedCornerShape(16.dp))
                                .clickable {
                                    draft = char
                                    onPick(char)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = char, style = PwType.title, color = if (selected) p.amber else p.ink)
                        }
                    }
                }
                Text(
                    text = "照片只在本机，不会上传。",
                    style = PwType.tiny,
                    color = p.faint,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Text(
                text = if (draft.isBlank()) "好" else "用「${draft.trim()}」",
                style = PwType.body,
                color = p.amber,
                modifier = Modifier
                    .clickable {
                        val t = draft.trim()
                        if (t.isBlank()) onDismiss() else onPick(t)
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
        dismissButton = {
            Text(
                text = "算了",
                style = PwType.body,
                color = p.faint,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
    )
}

@Composable
private fun PwBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    val p = LocalPwPalette.current
    // 系统底部手势/导航条的高度：把它也算进每一格的可点区域，
    // 这样手指按得偏下（落在系统条那一带）也能切页，而不是点了个空白。
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.surface.copy(alpha = if (p.isDark) 0.96f else 0.98f)),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(
                color = p.line,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1f,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val active = currentRoute == tab.route
                val tint = if (active) p.amber else p.faint
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                LaunchedEffect(interaction) {
                    interaction.interactions.collect { i ->
                        android.util.Log.d("PwNav", "press ${tab.route} $i")
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        // 整条高度（含系统条那一带）都可点；不要 ripple，按下只做轻微缩放，
                        // 这样即使某次手势被打断，也不会在底栏留下一块「按下去没收起来」的高亮。
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSelect(tab.route) },
                        )
                        .padding(top = 12.dp, bottom = 12.dp + navBarBottom),
                ) {
                    TabGlyph(
                        tab.glyph,
                        tint,
                        Modifier.size(22.dp * if (pressed) 0.9f else 1f),
                    )
                    Text(
                        text = tab.label,
                        style = PwType.label.copy(fontSize = 10.sp, letterSpacing = 1.2.sp),
                        color = tint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabGlyph(kind: Glyph, color: Color, modifier: Modifier = Modifier) {    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f)
        when (kind) {
            Glyph.TODAY -> {
                // 值守灯：圆环 + 中心点
                drawCircle(color, radius = w * 0.34f, center = center, style = stroke)
                drawCircle(color, radius = w * 0.1f, center = center)
            }
            Glyph.ITEM -> {
                // 盒子：圆角矩形 + 中线
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.18f, h * 0.22f),
                    size = Size(w * 0.64f, h * 0.56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
                    style = stroke,
                )
                drawLine(color, Offset(w * 0.18f, h * 0.42f), Offset(w * 0.82f, h * 0.42f), strokeWidth = w * 0.09f)
            }
            Glyph.PROOF -> {
                // 票据：外框 + 两侧打孔
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.26f),
                    size = Size(w * 0.76f, h * 0.48f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = stroke,
                )
                drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.12f, h * 0.5f))
                drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.88f, h * 0.5f))
                drawLine(
                    color,
                    Offset(w * 0.3f, h * 0.5f),
                    Offset(w * 0.7f, h * 0.5f),
                    strokeWidth = w * 0.07f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.08f, w * 0.08f)),
                )
            }
            Glyph.ME -> {
                // 人：头 + 肩
                drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.34f), style = stroke)
                val path = Path().apply {
                    moveTo(w * 0.2f, h * 0.82f)
                    quadraticTo(w * 0.5f, h * 0.52f, w * 0.8f, h * 0.82f)
                }
                drawPath(path, color, style = stroke)
            }
        }
    }
}

/** Compose 的 LocalContext 有时是 ContextWrapper，往上找到真正的 Activity。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

