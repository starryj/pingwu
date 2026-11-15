package com.pingwu.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType

/**
 * 首启合规：未同意隐私政策之前，App 不读数据、不申请权限、不排提醒，
 * 只显示这一页说明。
 *
 * 记在本机 SharedPreferences 里，同意过一次就不再出现。
 */
object ConsentPrefs {

    private const val FILE = "legal"
    private const val KEY_ACCEPTED = "privacy_accepted_v1"
    private const val KEY_ACCEPTED_AT = "privacy_accepted_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isAccepted(context: Context): Boolean = prefs(context).getBoolean(KEY_ACCEPTED, false)

    fun accept(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACCEPTED, true)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .apply()
    }
}

/**
 * 首启的隐私说明页。整屏铺开，盖住主界面——同意之前不组合任何业务界面，
 * 也就不会碰到数据库和照片。
 */
@Composable
fun ConsentScreen(
    onReadPolicy: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier.fillMaxSize().background(p.bg).padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(p.surface)
                .border(1.dp, p.line, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Text(text = "隐私与数据", style = PwType.display, color = p.ink)
            Spacer(Modifier.height(6.dp))
            Text(text = "先说清楚，再开始用。", style = PwType.small, color = p.faint)

            Spacer(Modifier.height(16.dp))
            ConsentBullet("凭物不需要账号，也没有服务器：物品、记录、照片、设置都只存在这台手机里。")
            ConsentBullet("用到的权限只有三个：相机（拍凭物照片）、通知与精确闹钟（到点提醒）、开机广播（重启后补排提醒）。它们只为这些功能服务，你可以随时在系统设置里收回。")
            ConsentBullet("没有广告和统计 SDK，不做用户画像，照片和记录一律不上传。")
            ConsentBullet("导出 CSV 由你发起，文件只写到你选的位置。")
            ConsentBullet("同意之后，才会申请通知等系统权限。")

            Spacer(Modifier.height(14.dp))
            Text(
                text = "查看隐私政策全文 ›",
                style = PwType.small,
                color = p.amber,
                modifier = Modifier.clickable { onReadPolicy() },
            )

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConsentButton("不同意，退出", primary = false, Modifier.weight(1f), onDecline)
                ConsentButton("同意并继续", primary = true, Modifier.weight(1f), onAccept)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "不同意也能随时卸载，本机数据随卸载一并删除。",
                style = PwType.tiny,
                color = p.faint,
            )
        }
    }
}

@Composable
private fun ConsentBullet(text: String) {
    val p = LocalPwPalette.current
    Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(5.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(p.amber),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = PwType.small, color = p.inkSoft)
    }
}

@Composable
private fun ConsentButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPwPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) p.amber else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (primary) androidx.compose.ui.graphics.Color.Transparent else p.line, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PwType.small,
            color = if (primary) p.onAmber else p.ink,
        )
    }
}
