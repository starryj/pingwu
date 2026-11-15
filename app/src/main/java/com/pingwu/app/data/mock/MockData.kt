package com.pingwu.app.data.mock

/** 物品分类：只影响图标、文案与判定方式（多件数件数，其它认物）。 */
enum class ItemCategory(val raw: String, val label: String, val unit: String) {
    MEDICINE("MEDICINE", "多件", "件"),
    KEY("KEY", "钥匙", "件"),
    DOC("DOC", "证件", "件"),
    OTHER("OTHER", "其他", "件"),
}

fun categoryOf(raw: String): ItemCategory = when (raw) {
    "MEDICINE" -> ItemCategory.MEDICINE
    "KEY" -> ItemCategory.KEY
    "DOC" -> ItemCategory.DOC
    else -> ItemCategory.OTHER
}

/** 这件东西的数词：都是按件。 */
fun unitOf(raw: String): String = categoryOf(raw).unit

enum class DayStatus { PENDING, VERIFIED, MISSED }

data class MockItem(
    val id: Int,
    val name: String,
    /** 备注：数量、放哪儿……可为空 */
    val note: String = "",
    val countLabel: String,
    /** 每次要拍几件 */
    val count: Int = 1,
    /** 一句话提醒说明，如「每天 08:00 · 21:00」「每周三 09:00」 */
    val schedule: String,
    val times: List<String>,
    /** DAILY / WEEKLY / MONTHLY / ONCE */
    val rule: String = "DAILY",
    /** WEEKLY 的星期 / MONTHLY 的几号，逗号分隔 */
    val daysCsv: String = "",
    /** ONCE 的日期 yyyy-MM-dd */
    val onceDate: String = "",
    val windowMinutes: Int = 30,
    val templateReady: Boolean,
    val category: ItemCategory = ItemCategory.OTHER,
    val coverRes: Int,
    /** 封面照片 key：登记照拍好后是本机文件路径，否则是工程内资源名。 */
    val coverPhotoKey: String = "",
    /** 这件物品登记了几块「认得的样子」；0 表示还没登记。 */
    val subjectCount: Int = 1,
)

data class MockEvidenceDay(
    val id: Int,
    val dateLabel: String,
    val weekday: String,
    val itemId: Int,
    val itemName: String,
    val photoKeys: List<String>,
    val status: DayStatus,
    val timeLabel: String,
    /** 这一条对应的待办与凭物 id，凭据页要用。 */
    val occurrenceId: Long = 0,
    val evidenceId: Long = 0,
    val countPerDose: Int = 1,
    val isLate: Boolean = false,
    /** ISO 日期（2026-11-13）：凭物页按月/周/天折叠要用。 */
    val isoDate: String = "",
    /** 这件物品是不是已经被删掉了（归档）。 */
    val itemArchived: Boolean = false,
)

data class MockPhoto(
    val photoKey: String,
    val dateLabel: String,
    val itemId: Int,
)
