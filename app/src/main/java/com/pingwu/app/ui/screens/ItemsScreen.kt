package com.pingwu.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pingwu.app.data.mock.MockItem
import com.pingwu.app.ui.UiState
import com.pingwu.app.ui.components.DashedDivider
import com.pingwu.app.ui.components.ItemGlyph
import com.pingwu.app.ui.components.PhotoThumb
import com.pingwu.app.ui.components.PwLabel
import com.pingwu.app.ui.components.SectionHead
import com.pingwu.app.ui.components.TicketCard
import com.pingwu.app.ui.components.TopBar
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType

@Composable
fun ItemsScreen(
    state: UiState,
    onOpenItem: (Int) -> Unit,
    onAdd: () -> Unit,
    onSeeEvidence: () -> Unit,
    onDeleteItem: (Int) -> Unit = {},
) {
    val p = LocalPwPalette.current
    val recentPhotos = state.photos.take(3)
    // 长按物品卡 → 先问一句再删（删的是「收起来」，凭物记录还在「凭物 → 已删除」）。
    var pendingDelete by remember { mutableStateOf<MockItem?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(right = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, p.line, RoundedCornerShape(10.dp))
                    .clickable { onAdd() },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "＋", style = PwType.body, color = p.amber)
            }
        })

        Text(text = "我的物品", style = PwType.title.copy(fontSize = 24.sp), color = p.ink)
        Text(
            text = "每件怕忘的事，绑在一件看得见的东西上",
            style = PwType.small,
            color = p.faint,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.items.forEach { item ->
                ItemCard(
                    item = item,
                    onClick = { onOpenItem(item.id) },
                    onLongClick = { pendingDelete = item },
                )
            }
            if (state.items.isEmpty() && !state.loading) {
                TicketCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                    Text(text = "还没有物品", style = PwType.body, color = p.ink)
                    Text(
                        text = "先添加一件你怕忘的东西：钥匙、钱包、护照、药，都行。",
                        style = PwType.small,
                        color = p.faint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.amberSoft)
                            .border(1.dp, p.amber, RoundedCornerShape(12.dp))
                            .clickable { onAdd() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "添加第一件物品", style = PwType.small, color = p.amber)
                    }
                }
            }
        }

        // 添加物品
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, p.line, RoundedCornerShape(16.dp))
                .clickable { onAdd() }
                .padding(vertical = 15.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "＋", style = PwType.body, color = p.amber)
            Spacer(Modifier.width(8.dp))
            Text(text = "添加物品", style = PwType.body, color = p.amber)
        }

        // 模板说明
        TicketCard(Modifier.padding(top = 18.dp), padding = 16.dp) {
            PwLabel("模板")
            Text(
                text = "模板就是它的样子。第一次拍清楚，之后每次都认这一件；一件东西也能登记好几块样子。",
                style = PwType.small,
                color = p.inkSoft,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // 提醒卡（取第一件有提醒的物品）
        val firstWithTimes = state.items.firstOrNull { it.times.isNotEmpty() }
        TicketCard(Modifier.padding(top = 12.dp), padding = 16.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PwLabel("提醒")
                Text(
                    text = firstWithTimes?.schedule ?: "尚未设置",
                    style = PwType.label,
                    color = p.amber,
                )
            }
            DashedDivider(Modifier.padding(vertical = 12.dp))
            if (firstWithTimes == null) {
                Text(text = "添加物品后就能设置提醒", style = PwType.small, color = p.faint)
            } else {
                firstWithTimes.times.forEach { time ->
                    RemindRow(time, "${firstWithTimes.name} ${firstWithTimes.countLabel}")
                }
            }
        }

        // 最近凭物
        SectionHead("最近凭物", action = "全部", onAction = onSeeEvidence)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(3) { index ->
                val photo = recentPhotos.getOrNull(index)
                if (photo != null) {
                    PhotoThumb(photo.photoKey, Modifier.weight(1f).height(96.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, p.line, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "—", style = PwType.label, color = p.faint)
                    }
                }
            }
        }

        Text(
            text = "长按物品可以删掉 · 照片只存在本机",
            style = PwType.label,
            color = p.faint,
            modifier = Modifier.padding(top = 22.dp, bottom = 28.dp),
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = p.surface,
            title = { Text(text = "删掉「${target.name}」？", style = PwType.title, color = p.ink) },
            text = {
                Text(
                    text = "删掉后不再提醒，日常里也看不到它。它的凭物记录会留在「凭物 → 已删除」里，" +
                        "照片可以在「我的 → 清理旧照片」里清掉。",
                    style = PwType.small,
                    color = p.inkSoft,
                )
            },
            confirmButton = {
                Text(
                    text = "删除",
                    style = PwType.body,
                    color = p.red,
                    modifier = Modifier
                        .clickable {
                            onDeleteItem(target.id)
                            pendingDelete = null
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "算了",
                    style = PwType.body,
                    color = p.faint,
                    modifier = Modifier
                        .clickable { pendingDelete = null }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(item: MockItem, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val p = LocalPwPalette.current
    TicketCard(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ItemGlyph(item.category, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.name, style = PwType.body.copy(fontWeight = FontWeight.Medium), color = p.ink)
                Text(
                    text = listOf(item.note, item.countLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    style = PwType.small,
                    color = p.faint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(text = "›", style = PwType.title, color = p.faint)
        }
        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tag(if (item.templateReady) "模板已登记" else "模板待登记", if (item.templateReady) p.mint else p.amber)
            if (item.subjectCount > 1) {
                Tag("${item.subjectCount} 块样子", p.amber)
            }
            Tag(item.schedule, p.faint)
        }
    }
}

@Composable
private fun Tag(text: String, tone: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, tone.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = PwType.label.copy(letterSpacing = 0.4.sp), color = tone)
    }
}

@Composable
private fun RemindRow(time: String, item: String) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = time, style = PwType.mono, color = p.ink)
        Text(text = item, style = PwType.small, color = p.faint)
    }
}
