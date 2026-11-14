package com.pingwu.app.network

import android.content.Context
import android.content.SharedPreferences

/**
 * 在线大模型 API 配置。
 *
 * 设计原则（与合规结论一致）：
 * - 用用户自己申请的 Key，App 只是工具；
 * - 默认关闭；开启后照片会离开本机、发到用户填的服务商；
 * - Key 只存本机 SharedPreferences，不上传、不写日志。
 */
data class AiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = PRESET_OPENAI.baseUrl,
    val model: String = PRESET_OPENAI.model,
    val apiKey: String = "",
    /** 关掉模型的思考链：识物不需要推理，关掉更快更省。默认开。 */
    val disableThinking: Boolean = true,
) {
    val isReady: Boolean get() = enabled && baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    companion object {
        val PRESET_OPENAI = AiPreset(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            note = "支持看图，按量计费",
        )
        val PRESET_DASHSCOPE = AiPreset(
            name = "阿里百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-vl-max",
            note = "通义千问视觉模型，国内直连",
        )
        val PRESET_ARK = AiPreset(
            name = "火山方舟",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            model = "",
            note = "模型名填「推理接入点 ID」，如 ep-2024…",
        )
        val PRESET_DEEPSEEK = AiPreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            note = "deepseek-chat / deepseek-flash 能看图；v4-pro 不收图",
        )
        val PRESETS = listOf(PRESET_OPENAI, PRESET_DASHSCOPE, PRESET_ARK, PRESET_DEEPSEEK)
    }
}

data class AiPreset(
    val name: String,
    val baseUrl: String,
    val model: String,
    val note: String,
)

class AiSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): AiConfig = AiConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        baseUrl = prefs.getString(KEY_BASE_URL, AiConfig.PRESET_OPENAI.baseUrl) ?: AiConfig.PRESET_OPENAI.baseUrl,
        model = prefs.getString(KEY_MODEL, AiConfig.PRESET_OPENAI.model) ?: AiConfig.PRESET_OPENAI.model,
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        disableThinking = prefs.getBoolean(KEY_DISABLE_THINKING, true),
    )

    fun save(config: AiConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putBoolean(KEY_DISABLE_THINKING, config.disableThinking)
            .apply()
    }

    private companion object {
        const val FILE = "pingwu_ai"
        const val KEY_ENABLED = "enabled"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_DISABLE_THINKING = "disable_thinking"
    }
}
