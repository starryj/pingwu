package com.pingwu.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.PowerManager
import android.provider.Settings

/**
 * 国产 ROM 的后台/自启动设置入口（M7 适配）：
 * 各家没有标准 API，只能尽力跳到厂商页面；跳不过去就退回应用详情页，由用户按提示手动找。
 * 电池优化有标准 API，能判断状态；自启动与后台弹出界面判断不了，只做引导。
 */
object RomSettings {

    /** 是否已加入电池优化白名单（标准 API，可判断）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 自启动管理页。 */
    fun openAutostart(context: Context) {
        openFirst(
            context,
            listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    )
                ),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                appDetails(context),
            ),
        )
    }

    /** 电池优化 / 省电策略页。 */
    fun openBatteryOptimization(context: Context) {
        openFirst(
            context,
            listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetails(context),
            ),
        )
    }

    /** 后台弹出界面 / 后台运行权限页。 */
    fun openBackgroundPopup(context: Context) {
        openFirst(
            context,
            listOf(
                Intent("miui.intent.action.OP_BACKGROUND_START").addCategory(Intent.CATEGORY_DEFAULT),
                Intent()
                    .setComponent(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.permissions.PermissionsEditorActivity",
                        )
                    )
                    .putExtra("extra_pkgname", context.packageName),
                appDetails(context),
            ),
        )
    }

    private fun appDetails(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        ("package:" + context.packageName).toUri(),
    )

    /** 依次尝试候选页面，第一个能打开的就用；全失败时静默（最后一项是应用详情页，基本都能开）。 */
    private fun openFirst(context: Context, candidates: List<Intent>) {
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
                // 厂商页面不存在或不允许跳转：试下一个
            }
        }
    }
}
