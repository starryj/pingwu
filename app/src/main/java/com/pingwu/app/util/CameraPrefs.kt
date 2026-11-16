package com.pingwu.app.util

import android.content.Context

/**
 * 拍照时开不开闪光灯。
 *
 * 用的是手电筒（torch）常亮，不是拍照瞬间那一下闪：晚上拍凭物时，用户需要
 * 在取景框里看见东西、把东西摆正，闪光灯跟着取景一起亮才有用。
 * 选择记在本机，下次进来沿用。
 */
object CameraPrefs {
    private const val PREFS = "pingwu_camera"
    private const val KEY_TORCH = "torch_on"

    fun torchOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TORCH, false)

    fun saveTorch(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TORCH, on)
            .apply()
    }
}
