package com.pingwu.app.util

import android.content.Context
import android.content.res.Configuration
import com.pingwu.app.settings.SettingsStore
import java.util.Locale

/** 应用内语言切换：用 configuration 覆写 base context，不依赖 GMS 或系统设置。 */
object LocaleHelper {

    fun wrap(base: Context, language: String): Context {
        val locale = when (language) {
            SettingsStore.LANG_ZH -> Locale.SIMPLIFIED_CHINESE
            SettingsStore.LANG_EN -> Locale.ENGLISH
            else -> return base
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
