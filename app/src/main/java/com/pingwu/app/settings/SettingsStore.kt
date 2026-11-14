package com.pingwu.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.core.content.edit
import kotlinx.coroutines.flow.StateFlow

/**
 * 轻量设置存储（SharedPreferences）。M1 只放语言，后续加提示音 URI、默认参考方式等。
 * 语言取值：system / zh / en。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(prefs.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM)
    val language: StateFlow<String> = _language

    fun setLanguage(value: String) {
        prefs.edit { putString(KEY_LANGUAGE, value) }
        _language.value = value
    }

    /** 自定义提醒音 URI；null = 系统默认通知音 */
    fun ringtoneUri(): String? = prefs.getString(KEY_RINGTONE, null)

    /** 首次启动隐私政策是否已同意（上架要求：首启弹窗同意） */
    fun privacyAccepted(): Boolean = prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)

    fun setPrivacyAccepted(value: Boolean) {
        prefs.edit { putBoolean(KEY_PRIVACY_ACCEPTED, value) }
    }

    /**
     * 调试构建专用：临时覆盖放行所需的最低置信度（默认 0.65）。
     * null = 用默认值。仅 debug 构建读取，release 构建里没有入口。
     */
    fun debugMinConfidence(): Double? =
        prefs.getFloat(KEY_DEBUG_MIN_CONFIDENCE, -1f).takeIf { it > 0f }?.toDouble()

    fun setDebugMinConfidence(value: Double?) {
        prefs.edit {
            if (value == null) remove(KEY_DEBUG_MIN_CONFIDENCE) else putFloat(KEY_DEBUG_MIN_CONFIDENCE, value.toFloat())
        }
    }

    fun setRingtone(uri: String?) {
        prefs.edit {
            if (uri == null) remove(KEY_RINGTONE) else putString(KEY_RINGTONE, uri)
        }
    }

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
        const val KEY_RINGTONE = "ringtone_uri"
        const val KEY_DEBUG_MIN_CONFIDENCE = "debug_min_confidence"
        const val LANG_SYSTEM = "system"
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"
    }
}
