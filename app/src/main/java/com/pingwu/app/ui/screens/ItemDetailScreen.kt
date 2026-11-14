package com.pingwu.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pingwu.app.data.core.EvidenceStats
import com.pingwu.app.data.mock.MockItem
import com.pingwu.app.data.mock.MockPhoto
import com.pingwu.app.ui.components.BackBar
import com.pingwu.app.ui.components.DashedDivider
import com.pingwu.app.ui.components.ItemGlyph
import com.pingwu.app.ui.components.PhotoThumb
import com.pingwu.app.ui.components.PwLabel
import com.pingwu.app.ui.components.TicketCard
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType

@Composable
fun ItemDetailScreen(
    itemId: Int,
    items: List<MockItem>,
    photos: List<MockPhoto>,
    stats: EvidenceStats,
    windowMinutes: Int,
    onWindowChange: (Int) -> Unit,
    onRegisterPhoto: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    subjectPhotoKeys: List<String> = emptyList(),
) {
    val p = LocalPwPalette.current
    val item = items.firstOrNull { it.id == itemId }
    val itemPhotos = photos.filter { it.itemId == itemId }
    var showDelete by remember { mutableStateOf(false) }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = p.surface,
            title = { Text(text = "删掉「${item?.name.orEmpty()}」？", style = PwType.body, color = p.ink) },
            text = {
                Text(
                    text = "删除后不再提醒它，也不再出现在物品列表里。已经拍下的凭物会留在「凭物」里。",
                    style = PwType.small,
                    color = p.faint,
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.red.copy(alpha = 0.14f))
                        .border(1.dp, p.red.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable {
                            showDelete = false
                            onDelete()
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(text = "删掉", style = PwType.small, color = p.red)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showDelete = false }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(text = "先留着", style = PwType.small, color = p.faint)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        BackBar(title = "物品详情", onBack = onBack)

        if (item == null) {
            Text(text = "找不到这件物品", style = PwType.body, color = p.faint)
            return@Column
        }

        TicketCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemGlyph(item.category, size = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.name, style = PwType.title, color = p.ink)
                    Text(
                        text = listOf(item.note, item.countLabel, item.category.label)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = PwType.small,
                        color = p.faint,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Text(
                        text = item.schedule,
                        style = PwType.small,
                        color = p.amber,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, p.line, RoundedCornerShape(10.dp))
                        .clickable { onEdit() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(text = "编辑", style = PwType.small, color = p.amber)
                }
            }
            DashedDivider(Modifier.padding(vertical = 14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("${itemPhotos.size}", "张凭物")
                Stat(
                    value = if (stats.scheduledCount == 0) "—" else "${stats.onTimePercent}%",
                    label = "按时",
                )
                Stat("${stats.streakDays} 天", "连续")
                Stat("${stats.missedCount} 次", "漏拍")
            }
            DashedDivider(Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "验证窗口", style = PwType.small, color = p.faint)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        WindowChip(
                            label = "$minutes 分钟",
                            selected = windowMinutes == minutes,
                            onClick = { if (windowMinutes != minutes) onWindowChange(minutes) },
                        )
                    }
                }
            }
            DashedDivider(Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showDelete = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "删除这件物品", style = PwType.small, color = p.red)
                Text(text = "已有的凭物会保留", style = PwType.label, color = p.faint)
            }
        }

        // 模板：这件东西「认得的样子」。可以一块，也可以好几块。
        TicketCard(Modifier.padding(top = 12.dp), padding = 16.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PwLabel("认得的样子")
                Text(
                    text = if (subjectPhotoKeys.isEmpty()) "还没登记" else "${subjectPhotoKeys.size} 块",
                    style = PwType.label,
                    color = if (subjectPhotoKeys.isEmpty()) p.amber else p.mint,
                )
            }
            if (subjectPhotoKeys.isEmpty()) {
                Text(
                    text = "给它拍个样子，以后到点就认它。可以一次拍全，也能分几次补。",
                    style = PwType.small,
                    color = p.faint,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                val shown = subjectPhotoKeys.take(4)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shown.forEach { key ->
                        PhotoThumb(
                            photoKey = key,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                            radius = 10.dp,
                        )
                    }
                    repeat(4 - shown.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Text(
                    text = "以后到点，画面里要有这些块；一次认不全可以分几次凑齐。",
                    style = PwType.label,
                    color = p.faint,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.amberSoft)
                    .border(1.dp, p.amber, RoundedCornerShape(12.dp))
                    .clickable { onRegisterPhoto() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (subjectPhotoKeys.isEmpty()) "拍下它的样子" else "重新拍，或再补一块",
                    style = PwType.small,
                    color = p.amber,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    PwLabel("这件物品的全部凭物")
                    Text(
                        text = if (itemPhotos.isEmpty()) "还没有凭物照片" else "每一张，都是它真的在过的证据",
                        style = PwType.small,
                        color = p.faint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            items(itemPhotos, key = { "${it.photoKey}-${it.dateLabel}" }) { photo ->
                PhotoCell(photo)
            }
            if (itemPhotos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "还没有凭物照片",
                            style = PwType.small,
                            color = p.inkSoft,
                        )
                        Text(
                            text = "到点拍一张，这里就攒起来了。",
                            style = PwType.tiny,
                            color = p.faint,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoCell(photo: MockPhoto) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        PhotoThumb(photo.photoKey, Modifier.fillMaxSize(), radius = 12.dp)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(p.bg.copy(alpha = 0.72f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(text = photo.dateLabel, style = PwType.label.copy(fontSize = 9.sp), color = p.ink)
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val p = LocalPwPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = PwType.mono.copy(fontWeight = FontWeight.Medium), color = p.ink)
        Text(text = label, style = PwType.label.copy(fontSize = 9.sp), color = p.faint, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun WindowChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) p.amberSoft else Color.Transparent)
            .border(1.dp, if (selected) p.amber else p.line, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = PwType.label.copy(fontSize = 10.sp, letterSpacing = 0.2.sp),
            color = if (selected) p.amber else p.faint,
        )
    }
}
