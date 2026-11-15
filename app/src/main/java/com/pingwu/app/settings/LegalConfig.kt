package com.pingwu.app.settings

/**
 * 合规相关的对外信息集中在这里，改一处就够。
 *
 * - [PRIVACY_URL]：隐私政策全文的公网地址（托管在 Cloudflare Pages）。
 * - [APP_BEIAN]：APP 备案号，备案通过后填写；留空时界面显示「备案办理中」。
 */
object LegalConfig {

    /** 隐私政策全文地址（Cloudflare Pages，2026-11-15 上线）。 */
    const val PRIVACY_URL: String = "https://example.com/privacy"

    /** APP 备案号，形如「苏ICP备2026000000号-2A」。未办下来前留空。 */
    const val APP_BEIAN: String = ""

    /** 界面上展示的备案号文案。 */
    fun beianText(): String = APP_BEIAN.ifBlank { "备案办理中" }
}
