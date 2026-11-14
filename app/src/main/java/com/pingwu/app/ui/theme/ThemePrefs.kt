package com.pingwu.app.ui.theme

import android.content.Context
import android.content.SharedPreferences

/** 主题与头像这类小选择只存在本机 SharedPreferences 里，重启后保留。 */
object ThemePrefs {
    private const val FILE = "pingwu_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_AVATAR = "avatar_char"
    private const val KEY_AVATAR_PHOTO = "avatar_photo"
    private const val KEY_NAME = "display_name"
    private const val KEY_FOCUS_LOCK = "focus_lock"

    /** 可选的单字头像。 */
    val AVATARS = listOf("溪", "十", "安", "宁")

    const val DEFAULT_AVATAR = "溪"

    /** 默认称呼；用户可以在「我的」里改。 */
    const val DEFAULT_NAME = "朋友"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    fun load(): ThemeMode {
        val value = prefs?.getString(KEY_THEME, "dark") ?: "dark"
        return if (value == "paper") ThemeMode.PAPER else ThemeMode.DARK
    }

    fun save(mode: ThemeMode) {
        prefs?.edit()?.putString(KEY_THEME, if (mode == ThemeMode.PAPER) "paper" else "dark")?.apply()
    }

    fun loadAvatar(): String {
        val value = prefs?.getString(KEY_AVATAR, DEFAULT_AVATAR)?.trim().orEmpty()
        // 用户自己写的字最多两个字，圆圈里放得下。
        return value.ifEmpty { DEFAULT_AVATAR }.take(2)
    }

    fun saveAvatar(text: String) {
        prefs?.edit()?.putString(KEY_AVATAR, text.trim().take(2))?.apply()
    }

    /** 用户自己选的照片头像（本机文件路径）；空串表示还用那个字。 */
    fun loadAvatarPhoto(): String = prefs?.getString(KEY_AVATAR_PHOTO, "").orEmpty()

    fun saveAvatarPhoto(path: String) {
        prefs?.edit()?.putString(KEY_AVATAR_PHOTO, path)?.apply()
    }

    /** 用户自己的称呼。 */
    fun loadName(): String {
        val value = prefs?.getString(KEY_NAME, DEFAULT_NAME)?.trim().orEmpty()
        return value.ifEmpty { DEFAULT_NAME }
    }

    fun saveName(name: String) {
        prefs?.edit()?.putString(KEY_NAME, name.trim().take(12))?.apply()
    }

    /** 专注模式：到点时把提醒页固定在屏幕上（系统「屏幕固定」），默认关。 */
    fun loadFocusLock(): Boolean = prefs?.getBoolean(KEY_FOCUS_LOCK, false) ?: false

    fun saveFocusLock(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_FOCUS_LOCK, enabled)?.apply()
    }
}
