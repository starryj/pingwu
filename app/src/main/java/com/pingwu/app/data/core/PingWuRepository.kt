package com.pingwu.app.data.core

import com.pingwu.app.data.mock.DayStatus
import com.pingwu.app.data.mock.MockEvidenceDay
import com.pingwu.app.data.mock.MockItem
import com.pingwu.app.data.mock.MockPhoto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

data class VerifyResult(
    val itemName: String,
    val timeLabel: String,
    val countPerDose: Int,
    val isLate: Boolean,
    /** 这条凭物的编号（EvidenceEntity.id），凭据页印在票根上。 */
    val evidenceId: Long = 0,
    /** 拍下来的时刻（epoch ms）。 */
    val capturedAt: Long = 0,
)

/**
 * 一件物品「认得的样子」：一张照片里裁出来的一块。
 * 一件东西可以登记好几块，到点时全部认到才算完成。
 */
data class ItemSubject(
    val id: Long,
    val name: String,
    val coverPhotoKey: String,
) {
    /** 显示名：没起名就叫「第 N 件」。 */
    fun label(index: Int): String = name.ifBlank { "第 ${index + 1} 件" }
}

/** 今天最早的一条待拍，用于判定前的「问谁、要几件」。 */
data class PendingTask(
    val occurrenceId: Long,
    val itemId: Long,
    val itemName: String,
    val countPerDose: Int,
    /** MEDICINE / KEY / DOC / OTHER；决定数量单位与判定方式。 */
    val category: String = "",
    val timeLabel: String,
    val scheduledAt: Long,
    val windowMinutes: Int,
    /** 这件东西自己的模板照（绝对路径）；没登记过就是空串。 */
    val coverPhotoKey: String = "",
    /** 这件东西登记的「认得的样子」；空 list 表示只按数量认。 */
    val subjects: List<ItemSubject> = emptyList(),
    /** 这一次已经认到的样子 id（分批拍留下的进度）。 */
    val doneSubjectIds: Set<Long> = emptySet(),
) {
    /** 这次还差哪几块。 */
    val remainingSubjects: List<ItemSubject>
        get() = subjects.filter { it.id !in doneSubjectIds }

    /** 全部认到了吗（没登记样子时算通过）。 */
    val allSubjectsDone: Boolean
        get() = subjects.isEmpty() || remainingSubjects.isEmpty()
}

/** 首页「下一次」：今天没安排时，用这个显示真正下一次。 */
data class NextUp(
    val dayLabel: String,
    val timeLabel: String,
    val itemName: String,
    val count: Int,
)

data class EvidenceStats(
    val evidenceCount: Int = 0,
    val onTimePercent: Int = 0,
    val streakDays: Int = 0,
    val missedCount: Int = 0,
    val todayDone: Int = 0,
    val todayTotal: Int = 0,
    /** 近 7 天，从左到右：-1 无安排、0 漏、1 按时、2 今天还排着（待拍） */
    val weekFlags: List<Int> = List(7) { -1 },
    /** 一共有过多少次安排；0 表示刚装好还没有记录 */
    val scheduledCount: Int = 0,
    /** 已经有过结果（拍到了或漏了）的次数；0 表示还没有可算的百分比 */
    val decidedCount: Int = 0,
)

/**
 * 单一数据入口：Room 读写 + 每日待办的补齐。
 * M9 起物品通用化（名称/备注/分类/数量 + 四种提醒规则），不再内置药品种子数据。
 */
class PingWuRepository(private val db: PingWuDatabase) {

    private val zone: ZoneId = ZoneId.systemDefault()

    val items: Flow<List<ItemEntity>> = db.itemDao().observeAll()

    /** 含已删除（归档）物品：凭物页的「已删除」要显示原名。 */
    val allItems: Flow<List<ItemEntity>> = db.itemDao().observeAllIncludingArchived()
    val subjects: Flow<List<SubjectEntity>> = db.subjectDao().observeAll()
    val reminders: Flow<List<ReminderEntity>> = db.reminderDao().observeEnabled()
    val occurrences: Flow<List<OccurrenceEntity>> = db.occurrenceDao().observeAll()
    val evidence: Flow<List<EvidenceEntity>> = db.evidenceDao().observeAll()

    suspend fun ensureSeeded() {
        ensureTodayOccurrences()
    }

    /**
     * 每次启动把「今天该拍的」补齐（按各自规则判断今天要不要拍），
     * 否则第二天打开会是空的一天。
     */
    suspend fun ensureTodayOccurrences(today: LocalDate = LocalDate.now(zone)) {
        val date = today.toString()
        val remindersByItem = db.reminderDao().getAll()
            .filter { it.enabled }
            .groupBy { it.itemId }
        db.itemDao().getAll().filter { !it.archived }.forEach { item ->
            remindersByItem[item.id].orEmpty()
                .filter { ReminderRule.matches(it, today) }
                .forEach { reminder ->
                    ReminderRule.times(reminder).forEach { time ->
                        val exists = runCatching { db.occurrenceDao().find(item.id, date, time) }.getOrNull()
                        if (exists == null) {
                            insertOccurrence(
                                itemId = item.id,
                                date = today,
                                time = time,
                                state = "PENDING",
                                windowMinutes = reminder.windowMinutes,
                            )
                        }
                    }
                }
        }
    }

    /** 新物品 + 提醒，一次写入。返回新物品 id。 */
    suspend fun addItem(draft: ItemDraft): Long {
        val id = db.itemDao().insert(
            ItemEntity(
                name = draft.name.trim(),
                note = draft.note.trim(),
                countPerDose = draft.count.coerceAtLeast(1),
                category = draft.category,
                templateReady = false,
                coverPhotoKey = "",
                sortOrder = db.itemDao().count(),
            ),
        )
        db.reminderDao().insert(draft.toReminderEntity(itemId = id))
        createTodayOccurrences(id, draft)
        return id
    }

    /** 编辑物品：基本信息 + 提醒一起改；今天还没拍的待办按新规则重建。 */
    suspend fun updateItem(itemId: Long, draft: ItemDraft): Boolean {
        val item = db.itemDao().getAll().firstOrNull { it.id == itemId } ?: return false
        db.itemDao().update(
            item.copy(
                name = draft.name.trim(),
                note = draft.note.trim(),
                countPerDose = draft.count.coerceAtLeast(1),
                category = draft.category,
            ),
        )
        db.reminderDao().deleteForItem(itemId)
        db.reminderDao().insert(draft.toReminderEntity(itemId = itemId))

        val today = LocalDate.now(zone)
        val date = today.toString()
        db.occurrenceDao().deletePendingOn(itemId, date)
        createTodayOccurrences(itemId, draft)
        return true
    }

    /** 删除物品：归档（凭物记录与历史仍然保留，只是不再提醒、不进列表）。 */
    suspend fun archiveItem(itemId: Long) {
        db.reminderDao().deleteForItem(itemId)
        db.itemDao().archive(itemId)
    }

    /**
     * 已删物品留下的凭物照片路径。
     *
     * 「清理旧照片」会把这些一起删掉：物品都删了，照片留着只占地方；
     * 凭物记录本身不删，凭物页「已删除」里还能看到那次是什么时候拍的。
     */
    suspend fun archivedPhotoKeys(): List<String> {
        val archivedIds = db.itemDao().getArchived().map { it.id }.toSet()
        if (archivedIds.isEmpty()) return emptyList()
        return db.evidenceDao().getAll()
            .filter { it.itemId in archivedIds }
            .map { it.photoKey }
    }

    /** 只改验证窗口：提醒时间不动，今天的待拍也同步改窗口。 */
    /** 推迟这一次：把到点时间往后挪，下次闹钟响了再提醒。 */
    suspend fun snoozeOccurrence(occurrenceId: Long, untilMs: Long) {
        db.occurrenceDao().reschedule(occurrenceId, untilMs)
    }

    suspend fun updateWindow(itemId: Long, minutes: Int) {
        val safe = minutes.coerceIn(5, 180)
        db.reminderDao().updateWindow(itemId, safe)
        db.occurrenceDao().updateWindowForPending(itemId, LocalDate.now(zone).toString(), safe)
    }

    /**
     * 把今天最近的一次「待拍」标记为已验证，并写入一张凭物。
     */
    suspend fun verifyNextPending(photoKey: String, capturedAt: Long = nowMs()): VerifyResult? {
        val pending = peekNextPending() ?: return null
        return completeOccurrence(pending.occurrenceId, photoKey, capturedAt)
    }

    /** 今天最早的一条待拍（只读）。在线模型判定需要先拿它去问，再决定是否记账。 */
    suspend fun peekNextPending(): PendingTask? {
        val today = LocalDate.now(zone).toString()
        val pending = db.occurrenceDao().getByDate(today)
            .filter { it.state == "PENDING" }
            .minByOrNull { it.scheduledAt }
            ?: return null
        return pendingTask(pending.id)
    }

    /**
     * 今天所有还没拍的，按时间排。
     *
     * 「一起拍」的勾选列表用它：用户自己勾这次要拍哪几件，
     * 一次快门只发一次模型请求。
     */
    suspend fun pendingTodayList(): List<PendingTask> {
        val today = LocalDate.now(zone).toString()
        return db.occurrenceDao().getByDate(today)
            .filter { it.state == "PENDING" }
            .sortedBy { it.scheduledAt }
            .mapNotNull { pendingTask(it.id) }
    }

    /**
     * 把某条待办标记为已完成并写入凭物。判定通过后调用；
     * 没有这条、或已经不是 PENDING，返回 null（避免重复记账）。
     */
    suspend fun completeOccurrence(
        occurrenceId: Long,
        photoKey: String,
        capturedAt: Long = nowMs(),
    ): VerifyResult? {
        val occ = db.occurrenceDao().getById(occurrenceId) ?: return null
        if (occ.state != "PENDING") return null
        val item = db.itemDao().getAll().firstOrNull { it.id == occ.itemId } ?: return null
        val late = capturedAt > occ.scheduledAt + occ.windowMinutes * 60_000L
        db.occurrenceDao().mark(occ.id, "VERIFIED", capturedAt, late)
        val evidenceId = db.evidenceDao().insert(
            EvidenceEntity(
                occurrenceId = occ.id,
                itemId = occ.itemId,
                photoKey = photoKey,
                capturedAt = capturedAt,
            ),
        )
        return VerifyResult(
            itemName = item.name,
            timeLabel = timeLabelOf(capturedAt, zone),
            countPerDose = item.countPerDose,
            isLate = late,
            evidenceId = evidenceId,
            capturedAt = capturedAt,
        )
    }

    /**
     * 一张照片同时算作好几件的凭物（多件一起拍）。
     *
     * 每件各写一条 Evidence，photoKey 都指向同一张照片——只存一次，不浪费空间；
     * 返回的列表就是这次真正记上账的那几件，没在列表里的说明已经被人拍过或不是待拍。
     */
    suspend fun completeOccurrences(
        occurrenceIds: List<Long>,
        photoKey: String,
        capturedAt: Long = nowMs(),
    ): List<VerifyResult> = occurrenceIds.mapNotNull { completeOccurrence(it, photoKey, capturedAt) }

    /**
     * 登记照：某件物品拍下的一张照片，存成它的封面，并标记「模板已登记」。
     * 不写凭物台账（它证明「这件东西长这样」，不是「今天做过」）。
     * 单块登记会把这个物品原来的样子全部换掉——要保留多块请用 [saveSubjects]。
     */
    suspend fun registerItemPhoto(itemId: Long, photoKey: String): ItemEntity? {
        val item = db.itemDao().getAll().firstOrNull { it.id == itemId } ?: return null
        db.subjectDao().deleteForItem(itemId)
        db.subjectDao().insert(
            SubjectEntity(itemId = itemId, coverPhotoKey = photoKey, sortOrder = 0),
        )
        val updated = item.copy(coverPhotoKey = photoKey, templateReady = true)
        db.itemDao().update(updated)
        return updated
    }

    /** 一件物品登记的「认得的样子」。 */
    suspend fun subjectsOf(itemId: Long): List<ItemSubject> =
        db.subjectDao().forItem(itemId).map { ItemSubject(it.id, it.name, it.coverPhotoKey) }

    /** 每件物品登记了几块（物品卡上显示「3 件」）。 */
    suspend fun subjectCounts(): Map<Long, Int> =
        db.subjectDao().getAll().groupingBy { it.itemId }.eachCount()

    /**
     * 保存一件物品的全部样子：先清空再按顺序写入。
     *
     * [crops] 是「这块叫什么 + 照片绝对路径」，照片由调用方先落盘。
     * 返回保存后的样子（带新 id）。
     */
    suspend fun saveSubjects(
        itemId: Long,
        crops: List<Pair<String, String>>,
    ): List<ItemSubject> {
        val item = db.itemDao().getAll().firstOrNull { it.id == itemId } ?: return emptyList()
        db.subjectDao().deleteForItem(itemId)
        val saved = crops.mapIndexed { index, crop ->
            val id = db.subjectDao().insert(
                SubjectEntity(
                    itemId = itemId,
                    name = crop.first,
                    coverPhotoKey = crop.second,
                    sortOrder = index,
                ),
            )
            ItemSubject(id = id, name = crop.first, coverPhotoKey = crop.second)
        }
        db.itemDao().update(
            item.copy(
                templateReady = saved.isNotEmpty(),
                coverPhotoKey = saved.firstOrNull()?.coverPhotoKey ?: item.coverPhotoKey,
            ),
        )
        return saved
    }

    /** 记下这一次已经认到了哪几块（还没认全，所以状态仍是 PENDING）。 */
    suspend fun markSubjectsDone(occurrenceId: Long, ids: Set<Long>) {
        db.occurrenceDao().markSubjectsDone(occurrenceId, ids.joinToString(","))
    }

    suspend fun itemById(id: Long): ItemEntity? = db.itemDao().getAll().firstOrNull { it.id == id }

    suspend fun clearEvidence() {
        db.evidenceDao().deleteAll()
    }

    /** 这些「记录」名下已经拍过的照片路径（彻底删除前先取，好把照片文件一起删掉）。 */
    suspend fun photoKeysForRecords(occurrenceIds: List<Long>): List<String> =
        if (occurrenceIds.isEmpty()) {
            emptyList()
        } else {
            db.evidenceDao().forOccurrences(occurrenceIds).map { it.photoKey }
        }

    /** 当前所有凭物照片路径——「孤儿照片对账」用：不在这个集合里的文件都可以删。 */
    suspend fun allEvidencePhotoKeys(): List<String> =
        db.evidenceDao().getAll().map { it.photoKey }

    /**
     * 彻底删掉这些「记录」：待办删掉，它挂着的凭物行会被外键级联一起删掉。
     * 照片文件不归这里管——调用方先按 [photoKeysForRecords] 删文件。
     */
    suspend fun deleteRecords(occurrenceIds: List<Long>): Int {
        if (occurrenceIds.isEmpty()) return 0
        db.occurrenceDao().deleteByIds(occurrenceIds)
        return occurrenceIds.size
    }

    /** M5：构建全量凭物 CSV 文本（写入文件与分享由 ViewModel 负责）。 */
    suspend fun exportCsvText(): String = EvidenceCsv.build(
        items = db.itemDao().getAll(),
        occurrences = db.occurrenceDao().getAll(),
        evidence = db.evidenceDao().getAll(),
        zone = zone,
    )

    suspend fun countEvidence(): Int = db.evidenceDao().count()

    /** 某条待拍对应的物品信息，用于通知与全屏页。 */
    suspend fun pendingTask(occurrenceId: Long): PendingTask? {
        val occ = db.occurrenceDao().getById(occurrenceId) ?: return null
        val item = db.itemDao().getAll().firstOrNull { it.id == occ.itemId } ?: return null
        // 删掉的物品不再提醒，也不再算进「今天还有几次待拍」；
        // 它的历史凭物仍留在「凭物 → 已删除」里。
        if (item.archived) return null
        val subjects = db.subjectDao().forItem(item.id)
            .map { ItemSubject(it.id, it.name, it.coverPhotoKey) }
        // 只认登记里真实存在的块：重拍样子后旧的 doneSubjectIds 会留下不存在的 id，
        // 不过滤就会算成「已认 1 块」，界面上的进度和「还差几块」会自相矛盾。
        val validIds = subjects.map { it.id }.toSet()
        return PendingTask(
            occurrenceId = occ.id,
            itemId = occ.itemId,
            itemName = item.name,
            countPerDose = item.countPerDose,
            category = item.category,
            timeLabel = occ.timeLabel,
            scheduledAt = occ.scheduledAt,
            windowMinutes = occ.windowMinutes,
            coverPhotoKey = item.coverPhotoKey,
            subjects = subjects,
            doneSubjectIds = occ.doneSubjectIds.subjectIdSet().intersect(validIds),
        )
    }

    /**
     * 到点（或刚过点）的所有待拍；同分钟到点的可能不止一件，通知要逐条发。
     *
     * [leadMs] 是「提前多少算到点」，只有闹钟接收端给一点余量（系统投递可能早几百毫秒），
     * 界面轮询一律传 0——否则页面会提前一分钟弹出来。
     */
    suspend fun duePendingList(now: Long = nowMs(), leadMs: Long = 0L): List<PendingTask> {
        val today = LocalDate.now(zone)
        return db.occurrenceDao().getByDate(today.toString())
            .filter { it.state == "PENDING" && ScheduleCalculator.isDue(it.scheduledAt, now, leadMs) }
            .sortedBy { it.scheduledAt }
            .mapNotNull { pendingTask(it.id) }
    }

    /** 到点（或刚过点）的那一条待拍；给通知用，没有就返回 null。 */
    suspend fun duePending(now: Long = nowMs(), leadMs: Long = 0L): PendingTask? =
        duePendingList(now, leadMs).firstOrNull()

    /** 下一个要响的提醒时刻（毫秒）；按各自规则往前找，没有则返回 null。 */
    suspend fun nextScheduledAfter(now: Long = nowMs()): Long? {
        val activeItems = db.itemDao().getAll().filter { !it.archived }.map { it.id }.toSet()
        return db.reminderDao().getAll()
            .filter { it.enabled && it.itemId in activeItems }
            .mapNotNull { ReminderRule.nextAfter(it, now, zone) }
            .minOrNull()
    }

    /** 把过了验证窗口还没拍的今天待办标记为漏拍。 */
    suspend fun markMissed(now: Long = nowMs()) {
        val today = LocalDate.now(zone).toString()
        db.occurrenceDao().getByDate(today)
            .filter { it.state == "PENDING" && it.scheduledAt + it.windowMinutes * 60_000L < now }
            .forEach { db.occurrenceDao().mark(it.id, "MISSED", null, false) }
    }

    // ---------- 展示映射 ----------

    fun buildUiItems(
        items: List<ItemEntity>,
        reminders: List<ReminderEntity>,
        subjectCounts: Map<Long, Int> = emptyMap(),
    ): List<MockItem> =
        items.filter { !it.archived }
            .map { item ->
                item.toUiItem(
                    reminders = reminders.filter { it.itemId == item.id },
                    subjectCount = subjectCounts[item.id]
                        ?: if (item.templateReady) 1 else 0,
                )
            }

    /** 每件物品的验证窗口（分钟），给物品详情页显示与修改。 */
    /** 下一次真正会响的时间（今天没到点之外，也算上周/每月的下一次）。 */
    fun buildNextUp(
        items: List<ItemEntity>,
        reminders: List<ReminderEntity>,
        now: Long = nowMs(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): NextUp? {
        val active = items.filter { !it.archived }.associateBy { it.id }
        val cand = reminders
            .filter { it.enabled && active.containsKey(it.itemId) }
            .mapNotNull { r ->
                val at = ReminderRule.nextAfter(r, now, zone) ?: return@mapNotNull null
                Triple(r, at, active[r.itemId]!!)
            }
            .minByOrNull { it.second } ?: return null

        val (reminder, at, item) = cand
        val date = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = java.time.LocalDate.now(zone)
        val dayLabel = when (date) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> "${date.monthValue}.${date.dayOfMonth} ${weekdayLabel(date)}"
        }
        val timeText = java.time.Instant.ofEpochMilli(at).atZone(zone)
            .toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return NextUp(
            dayLabel = dayLabel,
            timeLabel = timeText,
            itemName = item.name,
            count = item.countPerDose,
        )
    }

    fun buildWindows(reminders: List<ReminderEntity>): Map<Int, Int> =
        reminders.groupBy { it.itemId.toInt() }.mapValues { (_, list) -> list.first().windowMinutes }

    fun buildEvidenceDays(
        occurrences: List<OccurrenceEntity>,
        evidence: List<EvidenceEntity>,
        items: List<ItemEntity>,
    ): List<MockEvidenceDay> = occurrences.toEvidenceDays(evidence, items)

    fun buildPhotos(evidence: List<EvidenceEntity>, items: List<ItemEntity>): List<MockPhoto> {
        return evidence.mapNotNull { e ->
            val date = Instant.ofEpochMilli(e.capturedAt).atZone(zone).toLocalDate()
            MockPhoto(
                photoKey = e.photoKey,
                dateLabel = localDateLabel(date),
                itemId = e.itemId.toInt(),
            )
        }
    }

    fun buildStats(
        occurrences: List<OccurrenceEntity>,
        evidence: List<EvidenceEntity>,
        today: LocalDate = LocalDate.now(zone),
    ): EvidenceStats = StatsCalculator.compute(occurrences, evidence, today)

    // ---------- 内部 ----------

    private fun ItemDraft.toReminderEntity(itemId: Long) = ReminderEntity(
        itemId = itemId,
        rule = rule,
        daysCsv = daysCsv,
        timesCsv = times.joinToString(","),
        onceDate = onceDate,
        windowMinutes = windowMinutes.coerceIn(5, 180),
    )

    private suspend fun insertOccurrence(
        itemId: Long,
        date: LocalDate,
        time: String,
        state: String,
        verifiedAt: Long? = null,
        windowMinutes: Int = 30,
    ): Long {
        val scheduledAt = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
        return db.occurrenceDao().insert(
            OccurrenceEntity(
                itemId = itemId,
                localDate = date.toString(),
                timeLabel = time,
                scheduledAt = scheduledAt,
                state = state,
                verifiedAt = verifiedAt,
                windowMinutes = windowMinutes,
            ),
        )
    }

    /** 新建/编辑后，把今天还没到点的待办补上（今天不适用这条规则就不建）。 */
    private suspend fun createTodayOccurrences(itemId: Long, draft: ItemDraft) {
        val today = LocalDate.now(zone)
        val probe = ReminderEntity(
            itemId = itemId,
            rule = draft.rule,
            daysCsv = draft.daysCsv,
            timesCsv = draft.times.joinToString(","),
            onceDate = draft.onceDate,
            windowMinutes = draft.windowMinutes,
        )
        if (!ReminderRule.matches(probe, today)) return
        ReminderRule.times(probe).forEach { time ->
            val exists = runCatching {
                db.occurrenceDao().find(itemId, today.toString(), time)
            }.getOrNull()
            if (exists == null) {
                insertOccurrence(
                    itemId = itemId,
                    date = today,
                    time = time,
                    state = "PENDING",
                    windowMinutes = draft.windowMinutes,
                )
            }
        }
    }
}
