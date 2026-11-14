package com.pingwu.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pingwu.app.data.mock.ItemCategory
import com.pingwu.app.ui.components.ItemGlyph
import com.pingwu.app.ui.components.PhotoImage
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType

/**
 * 到点页：锁屏全屏（ReminderActivity）与前台应用内（PingWuRoot 叠加）共用。
 *
 * - 图片用这件东西自己的模板照；没拍过就显示占位，不拿内置示例图冒充。
 * - 返回键不算退出：先问一句，只有「推迟」能走（记一次推迟，10 分钟后再提醒）。
 */
@Composable
fun AlarmScreen(
    itemName: String,
    count: Int,
    timeLabel: String,
    coverPhotoKey: String,
    onCapture: () -> Unit,
    onLater: () -> Unit,
) {
    val p = LocalPwPalette.current
    var askLeave by remember { mutableStateOf(false) }
    BackHandler { askLeave = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.bg, p.bgGlow, p.bg)))
            .padding(horizontal = 22.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(96.dp))
            Text(text = timeLabel, style = PwType.label, color = p.amber)
            Text(
                text = "到点该拍了",
                style = PwType.title.copy(fontWeight = FontWeight.SemiBold),
                color = p.ink,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "拍一张 $itemName ×$count，才算这次完成。",
                style = PwType.small,
                color = p.faint,
                modifier = Modifier.padding(top = 8.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, p.line, RoundedCornerShape(18.dp)),
            ) {
                if (coverPhotoKey.isNotBlank()) {
                    PhotoImage(
                        photoKey = coverPhotoKey,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.15f),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.15f)
                            .background(p.surfaceAlt),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ItemGlyph(category = ItemCategory.OTHER)
                        Text(
                            text = "还没给它拍样子",
                            style = PwType.small,
                            color = p.faint,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        Text(
                            text = "去「物品」里补一张，下次提醒就显示它",
                            style = PwType.tiny,
                            color = p.faint,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Text(
                    text = "$itemName ×$count",
                    style = PwType.label,
                    color = p.ink,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.bg.copy(alpha = 0.75f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Text(
                text = "拍照只是在场证明：证明东西在，不证明已经做了。",
                style = PwType.tiny,
                color = p.faint,
                modifier = Modifier.padding(top = 14.dp),
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, p.line, RoundedCornerShape(14.dp))
                        .clickable { onLater() }
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "稍后 10 分钟", style = PwType.small, color = p.faint)
                }
                Box(
                    modifier = Modifier
                        .weight(1.6f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.amber)
                        .clickable { onCapture() }
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "去拍照",
                        style = PwType.small.copy(fontWeight = FontWeight.Medium),
                        color = p.onAmber,
                    )
                }
            }
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            containerColor = p.surface,
            title = { Text(text = "还没拍呢", style = PwType.title, color = p.ink) },
            text = {
                Text(
                    text = "这一条还没验证。现在走的话，会记一次推迟，10 分钟后再提醒你。",
                    style = PwType.small,
                    color = p.faint,
                )
            },
            confirmButton = {
                Text(
                    text = "推迟",
                    style = PwType.body,
                    color = p.amber,
                    modifier = Modifier
                        .clickable {
                            askLeave = false
                            onLater()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "继续拍",
                    style = PwType.body,
                    color = p.faint,
                    modifier = Modifier
                        .clickable { askLeave = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
        )
    }
}
