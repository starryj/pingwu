package com.pingwu.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pingwu.app.cv.ImageQuality
import com.pingwu.app.cv.ItemMatcher
import com.pingwu.app.cv.LocalVerdictJudge
import com.pingwu.app.cv.PhotoStore
import com.pingwu.app.cv.PillDetection
import com.pingwu.app.cv.QualityReport
import com.pingwu.app.cv.label
import com.pingwu.app.data.core.PendingTask
import com.pingwu.app.data.core.VerifyResult
import com.pingwu.app.data.mock.ItemCategory
import com.pingwu.app.data.mock.categoryOf
import com.pingwu.app.data.mock.unitOf
import com.pingwu.app.reminder.ReminderScheduler
import com.pingwu.app.ui.CaptureResult
import com.pingwu.app.ui.MultiCaptureResult
import com.pingwu.app.ui.PingWuViewModel
import com.pingwu.app.ui.toMultiProof
import com.pingwu.app.ui.toProof
import com.pingwu.app.ui.components.BackBar
import com.pingwu.app.ui.components.PhotoImage
import com.pingwu.app.ui.components.StatusChip
import com.pingwu.app.ui.components.TicketCard
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType
import com.pingwu.app.util.CameraPrefs
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M4 拍照页：CameraX 真预览 + 拍照，落盘后跑本机检测。
 * - 在线识别开启：交给模型判定（本机结果只做展示）。
 * - 在线识别关闭：本机判定——数量对得上且照片质量 OK 才算通过。
 */
@Composable
fun CaptureScreen(onBack: () -> Unit, vm: PingWuViewModel) {
    val p = LocalPwPalette.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    var pending by remember { mutableStateOf<PendingTask?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var rejectedReason by remember { mutableStateOf<String?>(null) }
    var detectionText by remember { mutableStateOf<String?>(null) }
    var lastPhoto by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    // 闪光灯：用手电筒常亮，取景时就亮，晚上才看得见东西摆在哪。
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var flashOn by remember { mutableStateOf(CameraPrefs.torchOn(context)) }

    // 登记样子模式：从物品详情「给这件东西拍个样子」进来，拍完不记凭物，
    // 只在照片上点出这件东西的几块样子，存成它的模板。
    val templateItemId = vm.templateCaptureItemId
    var templateName by remember { mutableStateOf<String?>(null) }
    var registeredPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var registeredHint by remember { mutableStateOf<String?>(null) }
    // 拍好但还没点选的照片；非空时先走点选这一屏（可以放大，也能一次点好几块）。
    var regPhoto by remember { mutableStateOf<Bitmap?>(null) }

    // 验证：今天还没拍的，用户勾哪几件，这次就一起拍。
    // 一件物品可能登记了好几块样子，一起拍时按块计数。
    var pendingToday by remember { mutableStateOf<List<PendingTask>>(emptyList()) }
    var pickedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    /** 这次勾中的物品一共还要认几块。 */
    fun targetCount(tasks: List<PendingTask>): Int =
        tasks.sumOf { if (it.remainingSubjects.isEmpty()) 1 else it.remainingSubjects.size }

    // 到点验证页不能随手退出：返回键先问一句，只有「推迟」能离开。
    val blocking = pending != null && templateItemId == null
    var askLeave by remember { mutableStateOf(false) }
    BackHandler(enabled = blocking) { askLeave = true }

    LaunchedEffect(Unit) {
        pending = if (templateItemId == null) vm.nextPending() else null
        templateName = templateItemId?.let { vm.itemName(it) }
        // 「一起拍」：勾选今天还没拍的，默认全勾。
        // 本机模式也允许：登记了多块样子的物品要靠这里逐块认，才能「全部认到才算完成」。
        if (templateItemId == null) {
            val list = vm.pendingToday()
            pendingToday = list
            pickedIds = list.map { it.occurrenceId }.toSet()
        }
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 不管从哪条路离开拍照页，都要把「登记模式」收掉，免得下次拍照走错分支。
    DisposableEffect(Unit) {
        onDispose { vm.endTemplateCapture() }
    }

    /** 登记样子：把用户点中的几块裁出来，存成这件物品的「认得的样子」。 */
    fun saveSubjects(crops: List<Pair<String, Bitmap>>) {
        val registerId = templateItemId ?: return
        if (crops.isEmpty()) {
            regPhoto = null
            busy = false
            return
        }
        busy = true
        regPhoto = null
        scope.launch {
            vm.registerSubjects(registerId, crops) { count ->
                busy = false
                if (count <= 0) {
                    notice = "登记没成，再试一次。"
                    return@registerSubjects
                }
                detectionText = if (count == 1) "记住这一件了" else "记住这 $count 件了"
                scope.launch {
                    val saved = vm.subjectsOf(registerId)
                    registeredPhotos = saved.map { it.coverPhotoKey }
                    registeredHint = null
                    val plain = withContext(Dispatchers.Default) {
                        saved.count { sub ->
                            ItemMatcher.featureCount(sub.coverPhotoKey) in 1 until ItemMatcher.MIN_KEYPOINTS
                        }
                    }
                    registeredHint = when {
                        plain <= 0 -> null
                        plain == saved.size -> "这些块细节都偏少，想认得更准就靠近一点重拍。"
                        else -> "有 $plain 块细节偏少，想认得更准就靠近一点重拍。"
                    }
                }
            }
        }
    }

    /** 拍完就走：把这张存根交给上层，由界面叠出凭据页。 */
    fun showProofAndLeave(result: VerifyResult, photoKey: String?, online: Boolean) {
        vm.openProof(result.toProof(photoKey, online), fresh = true)
        onBack()
    }

    /** 「一起拍」的结果：认到的先记账，没认到的留在原地等下次。 */
    fun handleMultiResult(result: MultiCaptureResult, online: Boolean = true) {
        busy = false
        when (result) {
            is MultiCaptureResult.Verified -> {
                if (result.pendingNotes.isNotEmpty()) {
                    // 只认到一部分：不跳票面，留在本页把「认到哪件、还差哪几块」说清楚，
                    // 并把认到的块在「一起拍」上勾出来，用户才知道剩什么没拍。
                    val doneNames = result.results.map { it.itemName }.distinct()
                    notice = buildString {
                        if (doneNames.isNotEmpty()) {
                            append("认到 ")
                            append(doneNames.joinToString("、"))
                            append("，已经记上了；")
                        }
                        append("还差 ")
                        append(result.pendingNotes.joinToString("；"))
                        append("。剩下的接着拍。")
                    }
                    scope.launch {
                        val list = vm.pendingToday()
                        pendingToday = list
                        val alive = list.map { it.occurrenceId }.toSet()
                        pickedIds = pickedIds.intersect(alive)
                    }
                } else {
                    val proof = result.results.toMultiProof(
                        photoKey = result.photoKey,
                        online = online,
                        pendingNames = emptyList(),
                    )
                    if (proof != null) {
                        vm.openProof(proof, fresh = true)
                        onBack()
                    } else {
                        notice = "这次没有记上账的。"
                    }
                }
            }
            is MultiCaptureResult.Rejected -> rejectedReason = result.reason
            is MultiCaptureResult.NoPending -> notice = "今天没有待拍的了。明天再来。"
            is MultiCaptureResult.Failed -> notice = result.message
        }
    }

    /**
     * 本机判定：
     * - 多件：数件数（原有逻辑）。
     * - 其它物品：拿它的登记照「认物」（ORB 特征点，旋转不变），认出来 + 照片质量过关就过；
     *   没登记过照片才退回数件数。
     */
    suspend fun localJudge(task: PendingTask, bitmap: Bitmap, filePath: String, report: QualityReport) {
        val category = categoryOf(task.category)
        val unit = unitOf(task.category)
        val canMatch = category != ItemCategory.MEDICINE && task.coverPhotoKey.isNotBlank()
        var pass = false
        var reject: String? = null
        var matchedByCover = false
        var coverTooPlain = false
        if (canMatch) {
            val cover = withContext(Dispatchers.Default) { ItemMatcher.load(task.coverPhotoKey) }
            if (cover != null) {
                val match = withContext(Dispatchers.Default) { ItemMatcher.compare(cover, bitmap) }
                if (match.coverKeyPoints < ItemMatcher.MIN_KEYPOINTS) {
                    // 登记照本身太素（比如白墙上一颗蒜），ORB 提不出细节；硬判只会把人挡在外面，
                    // 所以退回数量判定，并在界面上一句话说清楚。
                    coverTooPlain = true
                } else {
                    matchedByCover = true
                    pass = match.matched && report.ok
                    detectionText = if (match.matched) {
                        "认出来了 · ${match.goodMatches} 个特征点对上"
                    } else {
                        "还没认出它"
                    }
                    reject = when {
                        !report.ok -> "照片不够清楚（${report.issue?.label ?: "再拍一张"}），认出来也不敢算。"
                        !match.matched -> match.reason ?: "画面里没认出这件东西，再拍一张。"
                        else -> null
                    }
                }
            }
        }
        if (!matchedByCover) {
            val detect = withContext(Dispatchers.Default) { PillDetection.detect(context, bitmap) }
            val verdict = LocalVerdictJudge.judge(
                count = detect.cleanCount,
                expected = task.countPerDose,
                qualityOk = report.ok,
                qualityIssue = report.issue?.label,
                unit = unit,
            )
            pass = verdict.pass
            reject = verdict.reason
            detectionText = if (coverTooPlain) {
                "登记照太素，本机只按数量算 · 看到 ${detect.cleanCount}$unit"
            } else {
                "本机看到 ${detect.cleanCount}$unit，应拍 ${task.countPerDose}$unit"
            }
        }
        if (pass) {
            vm.completeCapture(task.occurrenceId, filePath) { result ->
                busy = false
                if (result == null) {
                    notice = "今天没有待拍的了。明天再来。"
                } else {
                    showProofAndLeave(result, filePath, false)
                }
            }
        } else {
            busy = false
            rejectedReason = reject ?: "这次没认出来，再拍一张试试。"
        }
    }

    /**
     * 本机判定（一次拍几件、或一件登记了好几块）：
     * 逐块拿登记照做 ORB 认物，认到几块记几块；没登记样子的物品退回数件数。
     */
    suspend fun localJudgeMany(
        tasks: List<PendingTask>,
        bitmap: Bitmap,
        filePath: String,
        report: QualityReport,
    ) {
        if (!report.ok) {
            busy = false
            rejectedReason = "照片不够清楚（${report.issue?.label ?: "再拍一张"}），认出来也不敢算。"
            return
        }
        val matched = mutableMapOf<Long, MutableSet<Long>>()
        val plainPassed = mutableSetOf<Long>()
        var recognized = 0
        var plainDetail = ""
        tasks.forEach { task ->
            val remaining = task.remainingSubjects
            if (remaining.isEmpty()) {
                val detect = withContext(Dispatchers.Default) { PillDetection.detect(context, bitmap) }
                val verdict = LocalVerdictJudge.judge(
                    count = detect.cleanCount,
                    expected = task.countPerDose,
                    qualityOk = report.ok,
                    qualityIssue = report.issue?.label,
                    unit = unitOf(task.category),
                )
                if (verdict.pass) {
                    plainPassed += task.occurrenceId
                    recognized++
                } else if (plainDetail.isBlank()) {
                    plainDetail = verdict.reason.orEmpty()
                }
            } else {
                remaining.forEach { subject ->
                    val ok = withContext(Dispatchers.Default) {
                        ItemMatcher.load(subject.coverPhotoKey)?.let { cover ->
                            val result = ItemMatcher.compare(cover, bitmap)
                            result.coverKeyPoints >= ItemMatcher.MIN_KEYPOINTS && result.matched
                        } == true
                    }
                    if (ok) {
                        matched.getOrPut(task.occurrenceId) { mutableSetOf() } += subject.id
                        recognized++
                    }
                }
            }
        }
        detectionText = "本机认到 $recognized 处"
        if (recognized == 0) {
            busy = false
            rejectedReason = plainDetail.ifBlank { "画面里没认出这件东西，再拍一张。" }
            return
        }
        vm.submitLocalSubjects(
            tasks = tasks,
            matched = matched,
            plainPassed = plainPassed,
            photoKey = filePath,
        ) { result -> handleMultiResult(result, online = false) }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    DisposableEffect(lifecycleOwner, hasPermission) {
        val future = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        future.addListener(
            {
                runCatching {
                    val provider = future.get()
                    if (hasPermission) {
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        provider.unbindAll()
                        val bound = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                        camera = bound
                        hasFlash = bound.cameraInfo.hasFlashUnit()
                        cameraError = null
                    }
                }.onFailure { cameraError = it.message ?: "相机打不开。" }
            },
            executor,
        )
        onDispose {
            // 离开这一页就把手电筒关掉，别在别人手机上留一盏灯。
            runCatching { camera?.cameraControl?.enableTorch(false) }
            runCatching { future.get().unbindAll() }
        }
    }

    // 开关一变就同步到相机；没闪光灯的机型这里直接不做事。
    LaunchedEffect(flashOn, hasFlash, camera) {
        val c = camera ?: return@LaunchedEffect
        if (!hasFlash) {
            if (flashOn) flashOn = false
            return@LaunchedEffect
        }
        runCatching { c.cameraControl.enableTorch(flashOn) }
    }

    fun shoot() {
        if (busy) return
        busy = true
        rejectedReason = null
        notice = null
        detectionText = null
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    image.close()
                    val bitmap = rotate(raw, rotation)
                    scope.launch {
                        val registerId = templateItemId
                        if (registerId != null) {
                            // 先别急着存：让用户点出这件东西的样子（能放大，也能点好几块）。
                            busy = false
                            regPhoto = bitmap
                            return@launch
                        }
                        var selected = pendingToday.filter { it.occurrenceId in pickedIds }
                        if (selected.isEmpty()) {
                            vm.nextPending()?.let { selected = listOf(it) }
                        }
                        val targets = targetCount(selected)
                        // 登记了多块样子的物品，就算只拍这一件也要按块判定：
                        // 单件路径只看封面那一块，会把没认到的块也一起算成完成。
                        val many = selected.size > 1 || targets > 1 ||
                            selected.any { it.subjects.size > 1 }
                        val task = if (many) null else selected.firstOrNull()
                        val target = task ?: selected.firstOrNull()
                        if (target == null) {
                            busy = false
                            notice = "今天没有待拍的了。明天再来。"
                            return@launch
                        }
                        val file = PhotoStore.saveJpeg(
                            bitmap,
                            PhotoStore.newEvidenceFile(context, target.occurrenceId),
                        )
                        lastPhoto = file.absolutePath
                        val report = withContext(Dispatchers.Default) { ImageQuality.evaluate(bitmap) }
                        when {
                            many && vm.aiActive -> vm.verifyCaptureMany(
                                tasks = selected,
                                photoKey = file.absolutePath,
                                jpeg = compressJpeg(bitmap),
                            ) { result -> handleMultiResult(result) }

                            many -> localJudgeMany(selected, bitmap, file.absolutePath, report)

                            vm.aiActive -> vm.verifyCapture(
                                photoKey = file.absolutePath,
                                jpeg = compressJpeg(bitmap),
                                task = task,
                            ) { result ->
                                busy = false
                                when (result) {
                                    is CaptureResult.Verified -> showProofAndLeave(
                                        result.result,
                                        file.absolutePath,
                                        result.judgedOnline,
                                    )
                                    is CaptureResult.Rejected -> rejectedReason = result.reason
                                    is CaptureResult.NoPending -> notice = "今天没有待拍的了。明天再来。"
                                    is CaptureResult.Failed -> notice = result.message
                                }
                            }

                            task != null -> localJudge(task, bitmap, file.absolutePath, report)
                            else -> {
                                busy = false
                                notice = "今天没有待拍的了。明天再来。"
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    notice = "没拍成：${exception.message}"
                }
            },
        )
    }

    fun runExamplePhoto() {
        if (busy) return
        busy = true
        rejectedReason = null
        notice = null
        scope.launch {
            val registerId = templateItemId
            // 登记样子不需要今天的待拍：它就是在建模板，不是来验证的。
            val task = if (registerId == null) vm.nextPending() else null
            if (registerId == null && task == null) {
                busy = false
                notice = "今天没有待拍的了。明天再来。"
                return@launch
            }
            val currentTask = task
            // 调试用：非多件物品若登记过样子，就把登记照转 90°，模拟「换个方向」再判一次。
            val simulated = currentTask?.let { t ->
                if (categoryOf(t.category) != ItemCategory.MEDICINE && t.coverPhotoKey.isNotBlank()) {
                    ItemMatcher.load(t.coverPhotoKey)?.let { rotate(it, 90) }
                } else {
                    null
                }
            }
            val bitmap = simulated ?: android.graphics.BitmapFactory.decodeResource(
                context.resources,
                com.pingwu.app.R.drawable.pw_photo_2,
            )?.let { upscaleForDebug(it) } ?: run {
                busy = false
                notice = "示例照片读不到。"
                return@launch
            }
            if (simulated != null) notice = "调试：用它的登记照转了 90° 当这次拍的照片"
            if (registerId != null) {
                busy = false
                regPhoto = bitmap
                return@launch
            }
            val current = currentTask ?: return@launch
            val file = PhotoStore.saveJpeg(bitmap, PhotoStore.newEvidenceFile(context, current.occurrenceId))
            lastPhoto = file.absolutePath
            val report = withContext(Dispatchers.Default) { ImageQuality.evaluate(bitmap) }
            var selected = pendingToday.filter { it.occurrenceId in pickedIds }
            if (selected.isEmpty()) selected = listOf(current)
            val targets = targetCount(selected)
            val many = selected.size > 1 || targets > 1 ||
                selected.any { it.subjects.size > 1 }
            if (many && vm.aiActive) {
                vm.verifyCaptureMany(
                    tasks = selected,
                    photoKey = file.absolutePath,
                    jpeg = compressJpeg(bitmap),
                ) { result -> handleMultiResult(result) }
            } else if (many) {
                localJudgeMany(selected, bitmap, file.absolutePath, report)
            } else if (vm.aiActive) {
                vm.verifyCapture(file.absolutePath, compressJpeg(bitmap), current) { result ->
                    busy = false
                    when (result) {
                        is CaptureResult.Verified -> showProofAndLeave(
                            result.result,
                            file.absolutePath,
                            result.judgedOnline,
                        )
                        is CaptureResult.Rejected -> rejectedReason = result.reason
                        is CaptureResult.NoPending -> notice = "今天没有待拍的了。明天再来。"
                        is CaptureResult.Failed -> notice = result.message
                    }
                }
            } else {
                localJudge(current, bitmap, file.absolutePath, report)
            }
        }
    }

    val selectedTasks = pendingToday.filter { it.occurrenceId in pickedIds }
    val selectedTargets = targetCount(selectedTasks)
    val picking = regPhoto
    if (picking != null) {
        PickSubjectsScreen(
            bitmap = picking,
            onCancel = {
                regPhoto = null
                busy = false
            },
            onConfirm = { crops -> saveSubjects(crops) },
        )
        return
    }

    if (registeredPhotos.isNotEmpty()) {
        RegisteredCard(
            photoKeys = registeredPhotos,
            itemName = templateName ?: "这件物品",
            hint = registeredHint,
            onDone = {
                vm.endTemplateCapture()
                onBack()
            },
            onAgain = {
                registeredPhotos = emptyList()
                registeredHint = null
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        BackBar(
            title = when {
                templateItemId != null -> "登记样子"
                else -> "拍照验证"
            },
            onBack = { if (blocking) askLeave = true else onBack() },
        )

        if (askLeave) {
            AlertDialog(
                onDismissRequest = { askLeave = false },
                containerColor = p.surface,
                title = { Text(text = "还没拍完", style = PwType.title, color = p.ink) },
                text = {
                    Text(
                        text = "这一条还没验证。先走的话会记一次推迟，10 分钟后再提醒你。",
                        style = PwType.small,
                        color = p.faint,
                    )
                },
                confirmButton = {
                    Text(
                        text = "推迟",
                        style = PwType.body,
                        color = p.amber,
                        modifier = Modifier
                            .clickable {
                                askLeave = false
                                ReminderScheduler.snooze(context, 10)
                                onBack()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                },
                dismissButton = {
                    Text(
                        text = "继续拍",
                        style = PwType.body,
                        color = p.faint,
                        modifier = Modifier
                            .clickable { askLeave = false }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                },
            )
        }

        Text(
            text = when {
                templateItemId != null -> "给「${templateName ?: "这件东西"}」拍一张样子"
                selectedTargets > 1 -> "这次要认 $selectedTargets 处"
                pending != null && categoryOf(pending!!.category) == ItemCategory.MEDICINE ->
                    "把${pending!!.itemName} ×${pending!!.countPerDose} 拍进框里"
                pending != null -> "把「${pending!!.itemName}」拍进框里"
                else -> "看一看今天要拍什么"
            },
            style = PwType.title,
            color = p.ink,
        )
        Text(
            text = when {
                templateItemId != null -> "只把这一件放满框；想认得更准，可以点出它的几块样子"
                selectedTargets > 1 ->
                    "一张照片认全部 · 认到的先算过，剩下的继续等"
                pending != null && categoryOf(pending!!.category) != ItemCategory.MEDICINE &&
                    pending!!.coverPhotoKey.isNotBlank() -> "只拍这一件，拍满一点 · 换个方向也认得出"
                else -> "摆好、看清楚、全部在画面里"
            },
            style = PwType.small,
            color = p.faint,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )

        // 「一起拍」：今天还没拍的列出来，用户自己勾这次要一起认哪几处。
        // 只要有多件物品、或某件登记了好几块样子，就把进度条列出来——
        // 认到一部分后剩一块时也还要看得见「已认 X / Y 块」，否则用户不知道还差什么。
        val showTogether = pendingToday.isNotEmpty() && (
            pendingToday.size > 1 ||
                pendingToday.any { it.subjects.size > 1 } ||
                pendingToday.any { it.doneSubjectIds.isNotEmpty() }
            )
        if (showTogether) {
            TogetherPickCard(
                tasks = pendingToday,
                picked = pickedIds,
                onToggle = { id ->
                    pickedIds = if (id in pickedIds) pickedIds - id else pickedIds + id
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(p.surface)
                .border(1.dp, p.line, RoundedCornerShape(22.dp)),
        ) {
            if (!hasPermission) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "需要相机权限才能拍凭物", style = PwType.title, color = p.ink)
                    Text(
                        text = "照片只存在本机；权限只用来拍照。",
                        style = PwType.small,
                        color = p.faint,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                    PrimaryButton("去允许") { permissionLauncher.launch(Manifest.permission.CAMERA) }
                }
            } else if (cameraError != null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "相机没打开", style = PwType.title, color = p.ink)
                    Text(
                        text = cameraError ?: "",
                        style = PwType.small,
                        color = p.faint,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                ViewfinderOverlay()

                // 取景框上下暗角：保证叠字在亮背景下也能看清
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(104.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)
                            )
                        ),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(88.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                            )
                        ),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip("在线判定" + if (vm.aiActive) "已开启" else "未开启", if (vm.aiActive) p.amber else p.faint)
                StatusChip("本机检测", p.mint)
            }

            // 闪光灯开关：只有带闪光灯的机型才显示。开着的时候取景就亮，
            // 晚上能先看清东西摆在哪，不至于拍完才发现框里是黑的。
            if (hasFlash) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 52.dp, end = 12.dp)
                        .clip(CircleShape)
                        .background(if (flashOn) p.amber else Color.Black.copy(alpha = 0.45f))
                        .clickable(enabled = !busy) {
                            val next = !flashOn
                            flashOn = next
                            CameraPrefs.saveTorch(context, next)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (flashOn) "闪光灯 · 开" else "闪光灯 · 关",
                        style = PwType.label.copy(fontSize = 11.sp),
                        color = if (flashOn) p.ink else Color.White.copy(alpha = 0.92f),
                    )
                }
            }

            Text(
                text = detectionText ?: "把东西放进框里 · 本机检测",
                style = PwType.label.copy(fontSize = 10.sp),
                color = p.faint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
            )
        }

        rejectedReason?.let { reason ->
            Text(
                text = "这次没算通过：$reason",
                style = PwType.small,
                color = p.red,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        notice?.let { message ->
            Text(
                text = message,
                style = PwType.small,
                color = p.amber,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (com.pingwu.app.BuildConfig.DEBUG) {
            Text(
                text = "调试：用示例照片走一遍",
                style = PwType.label,
                color = p.faint,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { runExamplePhoto() }
                    .padding(vertical = 6.dp),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(p.amberSoft)
                    .border(3.dp, p.amber, CircleShape)
                    .clickable(enabled = !busy && hasPermission) { shoot() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (busy) p.faint else p.amber),
                )
            }
            Text(
                text = when {
                    busy -> "正在看这张照片…"
                    templateItemId != null -> "按一下 · 拍下这一件的样子"
                    selectedTargets > 1 -> "按一下 · 一起认 $selectedTargets 处"
                    pending == null -> "今天没有待拍的"
                    else -> "按一下 · 拍下凭物"
                },
                style = PwType.label,
                color = p.faint,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ViewfinderOverlay() {
    val p = LocalPwPalette.current
    Canvas(Modifier.fillMaxSize().padding(24.dp)) {
        val arm = size.minDimension * 0.12f
        val stroke = 3f
        val c = p.amber.copy(alpha = 0.85f)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(c, Offset(x, y), Offset(x + dx, y), strokeWidth = stroke)
            drawLine(c, Offset(x, y), Offset(x, y + dy), strokeWidth = stroke)
        }
        corner(0f, 0f, arm, arm)
        corner(size.width, 0f, -arm, arm)
        corner(0f, size.height, arm, -arm)
        corner(size.width, size.height, -arm, -arm)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(p.amber)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = PwType.title, color = p.onAmber)
    }
}

/**
 * 登记时点选「要认的那一件」：在拍好的照片上点一下，把那块裁出来当模板。
 * 为什么要有这一步：登记照里同时有好几样东西时，模型和本机认物都只能猜（实测换把勺子也能过）。
 * 裁成单件之后，认物才有干净的依据，用户起的名字也就无关紧要了。
 */
/** 照片上点出的一块：rect 是它在原图里的像素位置，crop 是裁出来的方块。 */
private data class PickedSubject(val rect: Rect, val crop: Bitmap)

/**
 * 在一张照片里点出这件东西的几块「认得的样子」。
 *
 * 双指可以放大、拖动，小东西也能点准；每点一下追加一块，最多 6 块。
 * 一块也行——就是以前那种单块模板。
 */
@Composable
private fun PickSubjectsScreen(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (List<Pair<String, Bitmap>>) -> Unit,
) {
    val p = LocalPwPalette.current
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val picks = remember { mutableStateListOf<PickedSubject>() }
    var boxWidth by remember { mutableStateOf(0f) }
    var boxHeight by remember { mutableStateOf(0f) }

    fun clampPan(next: Offset, zoom: Float): Offset {
        if (boxWidth <= 0f || boxHeight <= 0f) return Offset.Zero
        val maxX = boxWidth * (zoom - 1f) / 2f
        val maxY = boxHeight * (zoom - 1f) / 2f
        return Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
    }

    fun addPick(tap: Offset) {
        if (picks.size >= 6 || boxWidth <= 0f || boxHeight <= 0f) return
        val cx = boxWidth / 2f
        val cy = boxHeight / 2f
        // 把「屏幕上点到的位置」反解回原图：先去掉平移，再除以缩放。
        val localX = (tap.x - cx - pan.x) / scale + cx
        val localY = (tap.y - cy - pan.y) / scale + cy
        if (localX < 0f || localY < 0f || localX > boxWidth || localY > boxHeight) return
        val side = (minOf(bitmap.width, bitmap.height) * 0.52f / scale).toInt().coerceAtLeast(48)
        val px = (localX / boxWidth * bitmap.width).toInt()
        val py = (localY / boxHeight * bitmap.height).toInt()
        val left = (px - side / 2).coerceIn(0, (bitmap.width - side).coerceAtLeast(0))
        val top = (py - side / 2).coerceIn(0, (bitmap.height - side).coerceAtLeast(0))
        picks += PickedSubject(
            rect = Rect(left.toFloat(), top.toFloat(), (left + side).toFloat(), (top + side).toFloat()),
            crop = Bitmap.createBitmap(bitmap, left, top, side, side),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        BackBar(title = "登记样子", onBack = onCancel)
        Text(
            text = "点出这件东西的样子",
            style = PwType.display,
            color = p.ink,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "双指放大好点准。每点一下裁一块，可以点好几块（正面、侧面都行）；以后到点认的就是这些块。",
            style = PwType.small,
            color = p.faint,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(16.dp))
                .background(p.surface)
                .onSizeChanged {
                    boxWidth = it.width.toFloat()
                    boxHeight = it.height.toFloat()
                }
                .pointerInput(bitmap) {
                    detectTapGestures { offset -> addPick(offset) }
                }
                .pointerInput(bitmap) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        val nextPan = clampPan(pan + panChange, nextScale)
                        scale = nextScale
                        pan = nextPan
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y
                    },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "登记的照片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    val sx = size.width / bitmap.width
                    val sy = size.height / bitmap.height
                    picks.forEach { pick ->
                        val r = pick.rect
                        drawRoundRect(
                            color = p.amber,
                            topLeft = Offset(r.left * sx, r.top * sy),
                            size = Size(r.width * sx, r.height * sy),
                            cornerRadius = CornerRadius(10f, 10f),
                            style = Stroke(width = 3f),
                        )
                    }
                }
            }
            // 不习惯双指也能按着放大。
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZoomStep("−") {
                    val next = (scale - 0.5f).coerceAtLeast(1f)
                    scale = next
                    pan = clampPan(pan, next)
                }
                Text(
                    text = "${(scale * 100).toInt()}%",
                    style = PwType.label,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                ZoomStep("＋") {
                    val next = (scale + 0.5f).coerceAtMost(5f)
                    scale = next
                    pan = clampPan(pan, next)
                }
            }
        }
        if (picks.isEmpty()) {
            Text(
                text = "在照片里点它一下。",
                style = PwType.small,
                color = p.amber,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = picks.toList()
                shown.forEachIndexed { index, pick ->
                    Box {
                        Image(
                            bitmap = pick.crop.asImageBitmap(),
                            contentDescription = "第 ${index + 1} 块",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Text(
                            text = "✕",
                            style = PwType.label,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { picks.remove(pick) }
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (picks.isNotEmpty()) {
            PrimaryButton(
                text = if (picks.size == 1) "就认这一块" else "就认这 ${picks.size} 块",
                onClick = { onConfirm(picks.map { "" to it.crop }) },
            )
        }
        Text(
            text = if (picks.isEmpty()) "整张都算这一件" else "改成整张都算这一件",
            style = PwType.small,
            color = p.amber,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onConfirm(listOf("" to bitmap)) }
                .padding(vertical = 14.dp),
        )
        Text(
            text = "裁出来的这几块只存在本机，不用重新拍。",
            style = PwType.label.copy(fontSize = 10.sp),
            color = p.faint,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun ZoomStep(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = PwType.body,
        color = Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
private fun RegisteredCard(
    photoKeys: List<String>,
    itemName: String,
    hint: String? = null,
    onDone: () -> Unit,
    onAgain: () -> Unit,
) {
    val p = LocalPwPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackBar(title = "登记好了", onBack = onDone)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (photoKeys.size > 1) "记住这 ${photoKeys.size} 块了" else "记住它了",
            style = PwType.display,
            color = p.ink,
        )
        Text(
            text = if (photoKeys.size > 1) {
                "以后提醒到「$itemName」，画面里要有这几块样子；可以一次拍全，也可以分几次凑齐。"
            } else {
                "以后提醒到「$itemName」，你就拍这一件东西。"
            },
            style = PwType.small,
            color = p.faint,
            modifier = Modifier.padding(top = 8.dp, bottom = if (hint == null) 18.dp else 6.dp),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = PwType.small,
                color = p.amber,
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
        if (photoKeys.size == 1) {
            PhotoImage(
                photoKey = photoKeys.first(),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, p.line, RoundedCornerShape(18.dp)),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                photoKeys.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { key ->
                            PhotoImage(
                                photoKey = key,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, p.line, RoundedCornerShape(14.dp)),
                            )
                        }
                        repeat(3 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        TicketCard(modifier = Modifier.padding(top = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = itemName,
                    style = PwType.small.copy(fontWeight = FontWeight.Medium),
                    color = p.ink,
                )
                Text(
                    text = if (photoKeys.size > 1) "${photoKeys.size} 块已登记" else "模板已登记",
                    style = PwType.mono,
                    color = p.mint,
                )
            }
            Text(
                text = "以后认的就是这几块；它们不会记进今天的凭物。",
                style = PwType.label,
                color = p.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton(text = "好，就这样", onClick = onDone)
        Text(
            text = "重新拍一张",
            style = PwType.small,
            color = p.amber,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAgain() }
                .padding(vertical = 14.dp),
        )
        Text(
            text = "照片只存在本机，保存在 App 私有目录。",
            style = PwType.label.copy(fontSize = 10.sp),
            color = p.faint,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

private fun compressJpeg(bitmap: Bitmap, quality: Int = 88): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** 仅调试用：内置照片尺寸偏小，放大到相机照片的尺寸，好把通过路径走通。 */
private fun upscaleForDebug(bitmap: Bitmap): Bitmap {
    val longEdge = maxOf(bitmap.width, bitmap.height)
    if (longEdge >= 1280) return bitmap
    val scale = 1600f / longEdge
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt(),
        (bitmap.height * scale).toInt(),
        true,
    )
}

private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * 「一起拍」的勾选条：今天还没拍的列出来，用户自己勾这次要拍哪几件。
 *
 * 为什么要让用户勾，而不是自动全拍：一次快门只发一次模型请求，
 * 画面里塞太多东西会互相干扰；谁在画面里，只有举着手机的人知道。
 */
@Composable
private fun TogetherPickCard(
    tasks: List<PendingTask>,
    picked: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    val p = LocalPwPalette.current
    TicketCard(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), padding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "一起拍",
                style = PwType.small.copy(fontWeight = FontWeight.Medium),
                color = p.ink,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "勾了哪几件，这张就认哪几件",
                style = PwType.label,
                color = p.faint,
            )
        }
        tasks.forEach { task ->
            val on = task.occurrenceId in picked
            val subjects = task.subjects
            val doneIds = task.doneSubjectIds
            val remaining = task.remainingSubjects
            val multi = subjects.size > 1
            val allDone = subjects.isNotEmpty() && remaining.isEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(task.occurrenceId) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (on) p.amber else Color.Transparent)
                        .border(1.5.dp, if (on) p.amber else p.line, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Text(text = "✓", style = PwType.label, color = p.onAmber)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.itemName,
                        style = PwType.small,
                        color = p.ink,
                        maxLines = 1,
                    )
                    if (multi) {
                        // 一物品多块：认到的块画实心绿块，没认到的描边，一眼看出还差哪几块。
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            subjects.forEach { subject ->
                                val done = subject.id in doneIds
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (done) p.mint else Color.Transparent)
                                        .border(
                                            1.dp,
                                            if (done) p.mint else if (allDone) p.mint else p.amber.copy(alpha = 0.7f),
                                            RoundedCornerShape(2.dp),
                                        ),
                                )
                            }
                            Text(
                                text = when {
                                    allDone -> "全部认到了"
                                    doneIds.isEmpty() -> "还差 ${remaining.size} 块"
                                    else -> "已认 ${doneIds.size} / ${subjects.size} 块 · 还差 ${remaining.size} 块"
                                },
                                style = PwType.label.copy(fontSize = 9.sp),
                                color = if (allDone) p.mint else if (doneIds.isEmpty()) p.faint else p.amber,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                    } else if (subjects.size == 1 && doneIds.size == 1) {
                        Text(
                            text = "这块认到了",
                            style = PwType.label.copy(fontSize = 9.sp),
                            color = p.mint,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "×${task.countPerDose}",
                    style = PwType.label.copy(fontFamily = FontFamily.Monospace),
                    color = p.faint,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = task.timeLabel, style = PwType.label, color = p.faint)
            }
        }
    }
}

@Composable
private fun pwFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalPwPalette.current.amber,
    unfocusedBorderColor = LocalPwPalette.current.line,
    cursorColor = LocalPwPalette.current.amber,
    focusedTextColor = LocalPwPalette.current.ink,
    unfocusedTextColor = LocalPwPalette.current.ink,
    focusedLabelColor = LocalPwPalette.current.faint,
    unfocusedLabelColor = LocalPwPalette.current.faint,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)
