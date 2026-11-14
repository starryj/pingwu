package com.pingwu.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pingwu.app.cv.PhotoStore
import com.pingwu.app.data.core.EvidenceStats
import com.pingwu.app.data.core.ItemDraft
import com.pingwu.app.data.core.ItemSubject
import com.pingwu.app.data.core.NextUp
import com.pingwu.app.data.core.PendingTask
import com.pingwu.app.data.core.PingWuDatabase
import com.pingwu.app.data.core.PingWuRepository
import com.pingwu.app.data.core.ReminderRule
import com.pingwu.app.data.core.VerifyResult
import com.pingwu.app.data.core.drawableFor
import com.pingwu.app.data.mock.MockEvidenceDay
import com.pingwu.app.data.mock.MockItem
import com.pingwu.app.data.mock.MockPhoto
import com.pingwu.app.network.AiConfig
import com.pingwu.app.network.AiSettings
import com.pingwu.app.network.JudgeTarget
import com.pingwu.app.network.OnlineVisionClient
import com.pingwu.app.reminder.ReminderLaunch
import com.pingwu.app.reminder.ReminderNotifier
import com.pingwu.app.reminder.ReminderScheduler
import com.pingwu.app.ui.theme.ThemePrefs
import com.pingwu.app.util.EvidenceCleaner
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次「按快门」的结果。 */
sealed interface CaptureResult {
    /** 通过：已写进凭物。judgedOnline 表示这次是模型判定还是本机模拟。 */
    data class Verified(val result: VerifyResult, val judgedOnline: Boolean) : CaptureResult

    /** 在线模型判定没通过：不记账，显示原因。 */
    data class Rejected(val reason: String) : CaptureResult

    /** 今天没有待拍。 */
    data object NoPending : CaptureResult

    /** 在线调用或读图失败。 */
    data class Failed(val message: String) : CaptureResult
}

/**
 * 一次快门拍好几件时的结果。
 *
 * 一件物品登记了几块「认得的样子」，就得全部认到才算完成；
 * 认到一部分会记下进度、继续等，所以这里是「这次记上账的」+「还差什么」。
 */
sealed interface MultiCaptureResult {
    /** 记上账的物品（可能为空：这次只认到一部分）；pendingNotes 是还差什么。 */
    data class Verified(
        val results: List<VerifyResult>,
        val pendingNotes: List<String>,
        val photoKey: String,
    ) : MultiCaptureResult

    /** 一件都没认出来：不记账，显示原因。 */
    data class Rejected(val reason: String) : MultiCaptureResult

    /** 今天没有待拍。 */
    data object NoPending : MultiCaptureResult

    /** 在线调用或读图失败。 */
    data class Failed(val message: String) : MultiCaptureResult
}

/** 多件判定时，一次请求里的一个目标（对应某件物品的某一块样子）。 */
private data class PlannedTarget(
    val task: PendingTask,
    val subject: ItemSubject?,
    val displayName: String,
)

data class UiState(
    val items: List<MockItem> = emptyList(),
    val days: List<MockEvidenceDay> = emptyList(),
    val photos: List<MockPhoto> = emptyList(),
    val stats: EvidenceStats = EvidenceStats(),
    val windows: Map<Int, Int> = emptyMap(),
    val nextUp: NextUp? = null,
    val loading: Boolean = true,
)

/**
 * M2 的界面状态：Room → 展示模型；同时托管「在线识别」的配置与调用。
 * M1 的 MockData 已被真实数据替换，MockData.kt 只留作类型定义与设计口径参考。
 */
class PingWuViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PingWuRepository(PingWuDatabase.get(app))
    private val aiSettings = AiSettings(app)
    private val visionClient = OnlineVisionClient()

    val state: StateFlow<UiState> =
        combine(
            repo.allItems,
            repo.subjects,
            repo.reminders,
            repo.occurrences,
            repo.evidence,
        ) { items, subjects, reminders, occurrences, evidence ->
            // 删掉的物品只留在「凭物 → 已删除」，不再算进今天的安排与统计。
            val activeIds = items.filter { !it.archived }.map { it.id }.toSet()
            UiState(
                items = repo.buildUiItems(
                    items = items.filter { !it.archived },
                    reminders = reminders,
                    subjectCounts = subjects.groupingBy { it.itemId }.eachCount(),
                ),
                days = repo.buildEvidenceDays(occurrences, evidence, items),
                photos = repo.buildPhotos(evidence, items.filter { !it.archived }),
                stats = repo.buildStats(occurrences.filter { it.itemId in activeIds }, evidence),
                windows = repo.buildWindows(reminders),
                nextUp = repo.buildNextUp(items.filter { !it.archived }, reminders),
                loading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** 在线大模型配置（Compose 可观察）。 */
    var aiConfig by mutableStateOf(aiSettings.load())
        private set

    /** 头像那个字（默认「溪」）。 */
    var avatar by mutableStateOf(ThemePrefs.loadAvatar())
        private set

    /** 用户自己选的照片头像（本机绝对路径）；空串表示还用那个字。 */
    var avatarPhoto by mutableStateOf(ThemePrefs.loadAvatarPhoto())
        private set

    /** 用户在「我的」里改的称呼。 */
    var displayName by mutableStateOf(ThemePrefs.loadName())
        private set

    fun pickName(text: String) {
        val clean = text.trim().take(12).ifEmpty { ThemePrefs.DEFAULT_NAME }
        ThemePrefs.saveName(clean)
        displayName = clean
    }

    /**
     * 从相册选一张做头像：缩到 512 方图存进 App 私有目录，回调存好的路径。
     * 照片只在本机，不上传。
     */
    fun saveAvatarPhotoFromUri(uri: android.net.Uri, onDone: (String?) -> Unit = {}) {
        val app = getApplication<android.app.Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longest / sample > 1024) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                app.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrNull()
            val key = bitmap?.let { persistAvatar(app, it) }
            withContext(Dispatchers.Main) {
                if (key != null) {
                    ThemePrefs.saveAvatarPhoto(key)
                    avatarPhoto = key
                }
                onDone(key)
            }
        }
    }

    /** 裁成方图、缩到 512、JPEG 88，存 files/avatar/avatar.jpg。 */
    private fun persistAvatar(app: android.app.Application, source: android.graphics.Bitmap): String? =
        runCatching {
            val dir = java.io.File(app.filesDir, "avatar")
            if (!dir.exists()) dir.mkdirs()
            val target = java.io.File(dir, "avatar.jpg")
            val side = 512
            val short = minOf(source.width, source.height)
            val square = android.graphics.Bitmap.createBitmap(
                source,
                (source.width - short) / 2,
                (source.height - short) / 2,
                short,
                short,
            )
            val scaled = android.graphics.Bitmap.createScaledBitmap(square, side, side, true)
            target.outputStream().use { out ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (square !== source) square.recycle()
            if (scaled !== square) scaled.recycle()
            target.absolutePath
        }.getOrNull()

    fun clearAvatarPhoto() {
        ThemePrefs.saveAvatarPhoto("")
        avatarPhoto = ""
    }

    /** 专注模式：到点把提醒页固定在屏幕上（系统「屏幕固定」）。 */
    var focusLock by mutableStateOf(ThemePrefs.loadFocusLock())
        private set

    fun applyFocusLock(enabled: Boolean) {
        focusLock = enabled
        ThemePrefs.saveFocusLock(enabled)
    }

    /** 正在看的凭据；非空时叠一层票根页。 */
    var proof by mutableStateOf<Proof?>(null)
        private set

    /** 这一张是不是刚拍出来、还没收下的。 */
    var proofFresh by mutableStateOf(false)
        private set

    /** 物品详情「拍下第一张」时记下物品，拍完回来登记封面。 */
    var templateCaptureItemId by mutableStateOf<Int?>(null)
        private set

    /** 正在编辑的物品 id；null 表示「添加」而不是「编辑」。 */
    var editingItemId by mutableStateOf<Int?>(null)
        private set

    /**
     * 前台到点的那一条（应用内全屏到点页用）。
     * 系统在解锁状态不让 App 抢全屏，只好由应用自己在前台弹。
     */
    var dueTask by mutableStateOf<PendingTask?>(null)
        private set

    /** 刚刚推迟过的那一条，避免 15 秒轮询又立刻把它弹回来。 */
    private var snoozedOccurrenceId: Long = -1L
    private var snoozeUntil: Long = 0L

    fun refreshDue() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (snoozedOccurrenceId > 0 && now < snoozeUntil) {
                dueTask = null
                return@launch
            }
            dueTask = runCatching { repo.duePendingList(now).firstOrNull() }.getOrNull()
        }
    }

    fun clearDue() {
        dueTask = null
    }

    /**
     * 推迟：把这一条的到点时间往后挪（写进数据库，重启 App 也还算数），
     * 并且重排一次闹钟，才是「10 分钟后再提醒」。
     */
    fun snoozeDue(minutes: Int = 10) {
        val task = dueTask
        dueTask = null
        val until = System.currentTimeMillis() + minutes * 60_000L
        if (task != null) {
            snoozedOccurrenceId = task.occurrenceId
            snoozeUntil = until
            viewModelScope.launch { runCatching { repo.snoozeOccurrence(task.occurrenceId, until) } }
        }
        ReminderScheduler.snooze(getApplication(), minutes)
    }

    fun beginEdit(itemId: Int) {
        editingItemId = itemId
    }

    fun endEdit() {
        editingItemId = null
    }

    fun openProof(proof: Proof, fresh: Boolean = false) {
        this.proof = proof
        this.proofFresh = fresh
    }

    fun closeProof() {
        proof = null
        proofFresh = false
    }

    fun pickAvatar(text: String) {
        // 自己写的字最多取两个（圆圈里放两个字已经是极限了）。
        val clean = text.trim().take(2).ifEmpty { ThemePrefs.DEFAULT_AVATAR }
        ThemePrefs.saveAvatar(clean)
        avatar = clean
    }

    fun beginTemplateCapture(itemId: Int) {
        templateCaptureItemId = itemId
    }

    fun endTemplateCapture() {
        templateCaptureItemId = null
    }

    /** 登记照：这件物品的第一张照片存成封面，并标记模板已登记。 */
    fun registerItemPhoto(itemId: Int, photoKey: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repo.registerItemPhoto(itemId.toLong(), photoKey) != null
            onDone(ok)
        }
    }

    init {
        viewModelScope.launch {
            repo.ensureSeeded()
            val app = getApplication<Application>()
            ReminderNotifier.ensureChannel(app)
            ReminderScheduler.scheduleNext(app)
        }
    }

    fun saveAiConfig(config: AiConfig) {
        val clean = config.copy(
            enabled = config.enabled,
            baseUrl = config.baseUrl.trim(),
            model = config.model.trim(),
            apiKey = config.apiKey.trim(),
        )
        aiSettings.save(clean)
        aiConfig = clean
    }

    suspend fun testAi(config: AiConfig): Result<String> = visionClient.testConnection(config)

    suspend fun describePhoto(jpeg: ByteArray): Result<String> = visionClient.describePhoto(aiConfig, jpeg)

    /** 开关与配置都齐了，才走在线判定。 */
    val aiActive: Boolean
        get() = aiConfig.enabled && aiConfig.isReady

    /**
     * 按快门（在线路径）：
     * - 在线识别开启 → 先让模型判定；通过才写凭物，没通过只返回原因。
     * - 在线识别关闭 → 交由拍照页做本机判定，判定通过后再调 [completeCapture]。
     */
    fun verifyCapture(
        photoKey: String,
        jpeg: ByteArray? = null,
        task: PendingTask? = null,
        onResult: (CaptureResult) -> Unit,
    ) {
        viewModelScope.launch {
            // 传了 task 就判那一条（「一起拍」只勾了一件时用），否则取今天最早待拍。
            val pending = task ?: repo.peekNextPending()
            if (pending == null) {
                onResult(CaptureResult.NoPending)
                return@launch
            }
            val config = aiConfig
            if (!(config.enabled && config.isReady)) {
                onResult(CaptureResult.Failed("在线识别没有开启，走本机判定。"))
                return@launch
            }
            val raw = jpeg ?: readJpeg(photoKey)
            if (raw == null) {
                onResult(CaptureResult.Failed("读不到用来判定的照片。"))
                return@launch
            }
            // 手机原图往往 2MB 以上，上传前压到长边 1280、质量 82，省流量也避免服务端 413。
            val bytes = withContext(Dispatchers.Default) { shrinkJpeg(raw) }
            // 有登记照就一起发给模型：让它比较「现场这张」和「登记时的样子」是不是同一件东西。
            val coverBytes = pending.coverPhotoKey
                .takeIf { it.isNotBlank() }
                ?.let { withContext(Dispatchers.Default) { readJpegSmall(it) } }
            Log.d("PwAi", "judge item=${pending.itemName} photo=${bytes.size}B cover=${coverBytes?.size ?: 0}B")
            visionClient.judgePhoto(config, bytes, pending.itemName, pending.countPerDose, coverBytes)
                .onSuccess { verdict ->
                    if (!verdict.pass) {
                        onResult(CaptureResult.Rejected(verdict.reason))
                        return@launch
                    }
                    val result = repo.completeOccurrence(pending.occurrenceId, photoKey)
                    onResult(
                        if (result == null) CaptureResult.NoPending
                        else CaptureResult.Verified(result, judgedOnline = true),
                    )
                }
                .onFailure { error ->
                    val why = error.message?.take(120) ?: "未知错误"
                    onResult(
                        CaptureResult.Failed(
                            "在线判定没成功：$why。可以换一个能看图的模型，或先关掉在线识别用本机判定。",
                        ),
                    )
                }
        }
    }

    /** 今天所有还没拍的（「一起拍」勾选列表用）。 */
    suspend fun pendingToday(): List<PendingTask> = repo.pendingTodayList()

    /**
     * 一次快门拍好几件（在线路径）。
     *
     * 每件勾中的物品按它登记的「认得的样子」逐块展开：模型逐块回 pass，
     * 认到几块记几块进度；某件全部认到才算完成、写凭物。
     * 剩下的留着下次接着拍——作者定的「可以分批拍，全部认到才算完成」。
     */
    fun verifyCaptureMany(
        tasks: List<PendingTask>,
        photoKey: String,
        jpeg: ByteArray? = null,
        onResult: (MultiCaptureResult) -> Unit,
    ) {
        viewModelScope.launch {
            if (tasks.isEmpty()) {
                onResult(MultiCaptureResult.NoPending)
                return@launch
            }
            val config = aiConfig
            if (!(config.enabled && config.isReady)) {
                onResult(MultiCaptureResult.Failed("在线识别没有开启，走本机判定。"))
                return@launch
            }
            val raw = jpeg ?: readJpeg(photoKey)
            if (raw == null) {
                onResult(MultiCaptureResult.Failed("读不到用来判定的照片。"))
                return@launch
            }
            val bytes = withContext(Dispatchers.Default) { shrinkJpeg(raw) }
            val plan = mutableListOf<PlannedTarget>()
            tasks.forEach { task ->
                val remaining = task.remainingSubjects
                if (remaining.isEmpty()) {
                    plan += PlannedTarget(task, null, task.itemName)
                } else {
                    remaining.forEach { subject ->
                        val no = task.subjects.indexOf(subject) + 1
                        plan += PlannedTarget(task, subject, "${task.itemName} 第 $no 件")
                    }
                }
            }
            val targets = plan.map { planned ->
                JudgeTarget(
                    name = planned.displayName,
                    count = if (planned.subject == null) planned.task.countPerDose else 1,
                    coverJpeg = planned.subject?.coverPhotoKey
                        ?.takeIf { it.isNotBlank() }
                        ?.let { withContext(Dispatchers.Default) { readJpegSmall(it) } },
                )
            }
            Log.d(
                "PwAi",
                "judgeMany items=${tasks.size} targets=${targets.size} " +
                    "photo=${bytes.size}B covers=${targets.count { it.coverJpeg != null }}",
            )
            visionClient.judgeMany(config, bytes, targets)
                .onSuccess { verdicts ->
                    val passedSubjects = mutableMapOf<Long, MutableSet<Long>>()
                    val passedPlain = mutableSetOf<Long>()
                    var passedCount = 0
                    verdicts.forEach { verdict ->
                        if (!verdict.pass) return@forEach
                        val planned = plan.getOrNull(verdict.index - 1) ?: return@forEach
                        passedCount++
                        val subject = planned.subject
                        if (subject != null) {
                            passedSubjects
                                .getOrPut(planned.task.occurrenceId) { mutableSetOf() }
                                .add(subject.id)
                        } else {
                            passedPlain += planned.task.occurrenceId
                        }
                    }
                    if (passedCount == 0) {
                        val why = verdicts.firstOrNull { !it.pass }?.reason
                            ?.takeIf { it.isNotBlank() }
                            ?: "这次一件都没认出来。"
                        onResult(MultiCaptureResult.Rejected(why))
                        return@onSuccess
                    }
                    val completed = mutableListOf<VerifyResult>()
                    val pendingNotes = mutableListOf<String>()
                    tasks.forEach { task ->
                        val newly = passedSubjects[task.occurrenceId].orEmpty()
                        val doneAfter = task.doneSubjectIds + newly
                        val remainingAfter = task.subjects.filter { it.id !in doneAfter }
                        val finished = if (task.subjects.isEmpty()) {
                            task.occurrenceId in passedPlain
                        } else {
                            remainingAfter.isEmpty()
                        }
                        if (finished) {
                            repo.completeOccurrence(task.occurrenceId, photoKey)
                                ?.let { completed += it }
                        } else {
                            if (newly.isNotEmpty()) {
                                repo.markSubjectsDone(task.occurrenceId, doneAfter)
                            }
                            val stillLabels = remainingAfter.map { task.subjects.indexOf(it) + 1 }
                            pendingNotes += if (stillLabels.isEmpty()) {
                                task.itemName
                            } else {
                                // 只差几块时别写成「还差 X」，那读起来像整件都没认到；
                                // 写成「X 第 3 块」，和上面「已认 2 / 3 块」对得上。
                                "${task.itemName} 第 ${stillLabels.joinToString("、")} 块"
                            }
                        }
                    }
                    onResult(MultiCaptureResult.Verified(completed, pendingNotes, photoKey))
                }
                .onFailure { error ->
                    val why = error.message?.take(120) ?: "未知错误"
                    onResult(
                        MultiCaptureResult.Failed(
                            "在线判定没成功：$why。可以关掉在线识别用本机判定。",
                        ),
                    )
                }
        }
    }

    /**
     * 本机判定通过后记账（可能只认到一部分）。
     *
     * 每件物品按它登记的「认得的样子」逐块认，认到几块记几块；
     * 全部认到才算完成、写凭物，剩下的继续等——与在线路径同一套收账规则。
     */
    fun submitLocalSubjects(
        tasks: List<PendingTask>,
        matched: Map<Long, Set<Long>>,
        plainPassed: Set<Long>,
        photoKey: String,
        onResult: (MultiCaptureResult) -> Unit,
    ) {
        viewModelScope.launch {
            val completed = mutableListOf<VerifyResult>()
            val pendingNotes = mutableListOf<String>()
            tasks.forEach { task ->
                val newly = matched[task.occurrenceId].orEmpty()
                val doneAfter = task.doneSubjectIds + newly
                val remainingAfter = task.subjects.filter { it.id !in doneAfter }
                val finished = if (task.subjects.isEmpty()) {
                    task.occurrenceId in plainPassed
                } else {
                    remainingAfter.isEmpty()
                }
                if (finished) {
                    repo.completeOccurrence(task.occurrenceId, photoKey)?.let { completed += it }
                } else {
                    if (newly.isNotEmpty()) {
                        repo.markSubjectsDone(task.occurrenceId, doneAfter)
                    }
                    val stillLabels = remainingAfter.map { task.subjects.indexOf(it) + 1 }
                    pendingNotes += if (stillLabels.isEmpty()) {
                        task.itemName
                    } else {
                        "${task.itemName}（还差第 ${stillLabels.joinToString("、")} 件）"
                    }
                }
            }
            if (completed.isEmpty() && pendingNotes.isEmpty()) {
                onResult(MultiCaptureResult.Rejected("这次一件都没认出来。"))
                return@launch
            }
            onResult(MultiCaptureResult.Verified(completed, pendingNotes, photoKey))
        }
    }

    /**
     * 给一件物品登记它的「认得的样子」：可能是一块，也可能一次点了几块。
     * 每块单独落盘（item-<id>-subject-<n>.jpg），然后整组替换到 pw_subject。
     */
    fun registerSubjects(
        itemId: Int,
        crops: List<Pair<String, Bitmap>>,
        onDone: (Int) -> Unit = {},
    ) {
        if (crops.isEmpty()) {
            onDone(0)
            return
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val saved = withContext(Dispatchers.Default) {
                crops.mapIndexed { index, crop ->
                    val file = PhotoStore.itemSubjectFile(app, itemId.toLong(), index)
                    PhotoStore.saveJpeg(crop.second, file, quality = 92)
                    crop.first.trim() to file.absolutePath
                }
            }
            repo.saveSubjects(itemId.toLong(), saved)
            onDone(saved.size)
        }
    }

    /** 物品详情页要显示的「认得的样子」。 */
    suspend fun subjectsOf(itemId: Int): List<ItemSubject> = repo.subjectsOf(itemId.toLong())

    /** 本机判定通过后记账（拍照页用）。 */
    fun completeCapture(occurrenceId: Long, photoKey: String, onDone: (VerifyResult?) -> Unit) {        viewModelScope.launch { onDone(repo.completeOccurrence(occurrenceId, photoKey)) }
    }

    /** 当前待拍的那一件事（拍照页用来知道要拍几片）。 */
    suspend fun nextPending(): PendingTask? = repo.peekNextPending()

    /** 登记照模式要知道是哪件物品。 */
    suspend fun itemName(itemId: Int): String? = repo.itemById(itemId.toLong())?.name

    /** 这件物品的分类（MEDICINE / KEY / DOC / OTHER），决定数词与判定方式。 */
    suspend fun itemCategory(itemId: Int): String? = repo.itemById(itemId.toLong())?.category

    private fun readJpeg(photoKey: String): ByteArray? = runCatching {
        if (photoKey.startsWith("/")) {
            return@runCatching java.io.File(photoKey).readBytes()
        }
        val bitmap = BitmapFactory.decodeResource(getApplication<Application>().resources, drawableFor(photoKey))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()

    /**
     * 登记照转成给模型看的缩略 JPEG（长边 ≤ 1024，别把几 MB 的原图塞进请求）。
     * 读不到就返回 null，在线判定会退回「只有现场照」。
     */
    private fun readJpegSmall(photoKey: String, maxEdge: Int = 1024): ByteArray? = runCatching {
        val bitmap = if (photoKey.startsWith("/")) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photoKey, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            BitmapFactory.decodeFile(photoKey, BitmapFactory.Options().apply { inSampleSize = sample })
        } else {
            BitmapFactory.decodeResource(getApplication<Application>().resources, drawableFor(photoKey))
        } ?: return@runCatching null
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > maxEdge) {
            val ratio = maxEdge.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()

    fun verifyNext(photoKey: String, onDone: (VerifyResult?) -> Unit) {
        viewModelScope.launch { onDone(repo.verifyNextPending(photoKey)) }
    }

    /**
     * 把要上传的现场照压到长边 ≤ 1280、质量 82。
     * 已经是小图就原样返回；解码失败也原样返回，别把好不容易拍到的照片弄丢。
     */
    private fun shrinkJpeg(bytes: ByteArray, maxEdge: Int = 1280, quality: Int = 82): ByteArray = runCatching {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching bytes
        val longest = maxOf(src.width, src.height)
        val scaled = if (longest > maxEdge) {
            val ratio = maxEdge.toFloat() / longest
            Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).toInt().coerceAtLeast(1),
                (src.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== src) scaled.recycle()
        src.recycle()
        out.toByteArray()
    }.getOrDefault(bytes)

    /** 保存物品：editingItemId 为空是新增，否则是改写。 */
    fun saveDraft(draft: ItemDraft, onDone: (Long) -> Unit = {}) {
        val editing = editingItemId?.toLong()
        viewModelScope.launch {
            val id = if (editing == null) {
                repo.addItem(draft)
            } else {
                repo.updateItem(editing, draft)
                editing
            }
            editingItemId = null
            ReminderScheduler.scheduleNext(getApplication())
            onDone(id)
        }
    }

    /** 归档物品（不删凭物）。 */
    fun archiveItem(itemId: Int) {
        viewModelScope.launch {
            repo.archiveItem(itemId.toLong())
            ReminderScheduler.scheduleNext(getApplication())
        }
    }

    /** 改某件物品的验证窗口（分钟）。 */
    fun updateWindow(itemId: Int, minutes: Int) {
        viewModelScope.launch { repo.updateWindow(itemId.toLong(), minutes) }
    }

    /** 调试用：照着闹钟那条路走一遍——发通知 + 直接拉起到点页。 */
    fun debugFireDue(onDone: (String) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val task = repo.duePendingList().firstOrNull()
            if (task == null) {
                withContext(Dispatchers.Main) { onDone("没有到点的待拍，先造一条") }
                return@launch
            }
            ReminderNotifier.notify(getApplication(), task.itemName, task.countPerDose, task.timeLabel)
            if (!ReminderLaunch.shouldSkip(getApplication(), task.occurrenceId)) {
                ReminderLaunch.launch(getApplication(), task, "notify")
            }
            withContext(Dispatchers.Main) { onDone("已模拟：${'$'}{task.itemName}") }
        }
    }

    /** 调试用：造一条到点待拍，方便验证全屏/解锁补弹。 */
    fun debugMakeDueNow(onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = runCatching { repo.debugMakeDueNow() }.getOrNull()
            onDone(if (id == null) "没有可用的物品" else "已造一条到点待拍 #$id")
        }
    }

    fun clearEvidence() {
        viewModelScope.launch {
            repo.clearEvidence()
            // 记录全清了，证据目录里的照片就都成了孤儿，一起扫掉。
            withContext(Dispatchers.IO) {
                EvidenceCleaner.purgeOrphans(getApplication(), emptyList())
            }
        }
    }

    /**
     * 彻底删掉选中的「记录」：待办 + 凭物行 + 本机照片文件。
     * 删完重排一次闹钟——被删的可能正是还没拍的那几条。
     *
     * @param occurrenceIds 记录对应的待办 id（[MockEvidenceDay.occurrenceId]）
     */
    fun deleteRecords(occurrenceIds: List<Long>, onDone: (Int) -> Unit = {}) {
        if (occurrenceIds.isEmpty()) {
            onDone(0)
            return
        }
        viewModelScope.launch {
            val keys = repo.photoKeysForRecords(occurrenceIds)
            android.util.Log.d("PwClean", "deleteRecords ids=$occurrenceIds keys=$keys")
            val files = withContext(Dispatchers.IO) {
                EvidenceCleaner.deleteFiles(getApplication(), keys)
            }
            val removed = repo.deleteRecords(occurrenceIds)
            // 顺手对账：把以前删记录时漏下的孤儿照片一起清掉，
            // 否则「本机照片 N 张」会一直比记录多。
            val orphans = withContext(Dispatchers.IO) {
                EvidenceCleaner.purgeOrphans(getApplication(), repo.allEvidencePhotoKeys())
            }
            android.util.Log.d("PwClean", "deleteRecords removed=$removed files=$files orphans=$orphans")
            ReminderScheduler.scheduleNext(getApplication())
            onDone(removed)
        }
    }

    /** M5：导出全量凭物 CSV 到 cacheDir/exports，返回文件；失败返回 null。 */
    suspend fun exportCsvFile(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, com.pingwu.app.data.core.EvidenceCsv.fileName())
            file.writeText(repo.exportCsvText(), Charsets.UTF_8)
            file
        }.getOrNull()
    }

    /**
     * M5：本机凭物照片数量与占用。
     *
     * 读之前先做一次孤儿对账：老版本删记录不删文件，磁盘上会攒下没人引用的照片，
     * 不清掉的话这里显示的张数/占用永远比「凭物」里的记录多。
     */
    suspend fun photoStats(): EvidenceCleaner.PhotoStats = withContext(Dispatchers.IO) {
        runCatching { repo.allEvidencePhotoKeys() }.onSuccess { keep ->
            EvidenceCleaner.purgeOrphans(getApplication(), keep)
        }
        EvidenceCleaner.stats(getApplication())
    }

    /**
     * 清理照片：已删物品留下的照片 + 180 天前的旧照片 + 7 天前的导出缓存，回调删除数。
     * 凭物记录不删，删了照片的记录在凭物页显示「照片已清理」。
     */
    fun cleanupPhotos(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val archived = repo.archivedPhotoKeys()
            val removed = withContext(Dispatchers.IO) {
                var count = EvidenceCleaner.cleanup(getApplication(), extraFiles = archived)
                // 「清理旧照片」也顺手对一次账，孤儿照片一并收掉。
                count += EvidenceCleaner.purgeOrphans(getApplication(), repo.allEvidencePhotoKeys())
                count
            }
            onDone(removed)
        }
    }
}
