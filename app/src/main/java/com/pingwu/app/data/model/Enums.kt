package com.pingwu.app.data.model

/** 参考方式：免卡 / 参照物 / 参考卡，见《02》§6.6 */
enum class ReferenceMode { NONE, OBJECT, CARD }

/** 剂量状态机，见《02》§3.2 / 《04》§2 */
enum class DoseState { PENDING, REMINDING, SNOOZED, VERIFIED, MISSED, SKIPPED }

/** 药品来源 */
enum class DrugSource { MANUAL, BARCODE, OCR }

/** 预设药库形状（8 种），只用于简笔画图标，不参与视觉判定 */
enum class PresetShape { ROUND, OVAL, CAPSULE, CAPSULE_TWO_TONE, RECT, TRIANGLE, DIAMOND, IRREGULAR }

/** 预设药库颜色（10 种） */
enum class PresetColor { WHITE, YELLOW, PINK, ORANGE, BLUE, GREEN, RED, BROWN, GRAY, PURPLE }

/** 证据判定结论 */
enum class Verdict { PASSED, FAILED }
