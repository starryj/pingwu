package com.pingwu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pingwu.app.data.mock.DayStatus
import com.pingwu.app.data.mock.MockEvidenceDay
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType

/**
 * 「我的 → 凭物数据 → 自定义删除」：按物品把所有记录列出来，勾选后一起清空。
 *
 * 这里删的是「记录」本身（待办 + 凭物 + 照片），不是物品，删完物品还在、还能接着拍。
 */
@Composable
fun RecordPickerDialog(
    days: List<MockEvidenceDay>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    val p = LocalPwPalette.current
    var selected by remember { mutableStateOf(emptySet<Long>()) }

    // 按物品分组：用户想清掉的往往是「这件东西的历史」。
    val groups = remember(days) {
        days.filter { it.occurrenceId > 0 }
            .groupBy { it.itemId }
            .values
            .sortedByDescending { group -> group.maxOf { it.isoDate } }
            .map { group -> group.sortedByDescending { it.isoDate } }
    }
    val allIds = remember(days) { days.map { it.occurrenceId }.filter { it > 0 }.toSet() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 28.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(p.surface)
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Text(text = "自定义删除", style = PwType.title, color = p.ink)
            Text(
                text = "勾选要清掉的记录。记录和照片会一起删掉，物品本身不动，之后还能接着拍。",
                style = PwType.tiny,
                color = p.faint,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selected.isEmpty()) "还没选" else "已选 ${selected.size} 条",
                    style = PwType.small,
                    color = if (selected.isEmpty()) p.faint else p.amber,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selected = if (selected.size == allIds.size) emptySet() else allIds
                }) {
                    Text(
                        text = if (selected.size == allIds.size && allIds.isNotEmpty()) "全不选" else "全选",
                        style = PwType.small,
                        color = p.inkSoft,
                    )
                }
            }

            if (groups.isEmpty()) {
                Text(
                    text = "还没有任何记录。到点拍一张，这里就有东西可挑了。",
                    style = PwType.small,
                    color = p.faint,
                    modifier = Modifier.padding(top = 18.dp, bottom = 18.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    groups.forEach { group ->
                        val groupIds = group.map { it.occurrenceId }.toSet()
                        val allPicked = groupIds.isNotEmpty() && selected.containsAll(groupIds)
                        item(key = "g" + group.first().itemId) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 4.dp)
                                    .clickable {
                                        selected = if (allPicked) {
                                            selected - groupIds
                                        } else {
                                            selected + groupIds
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = group.first().itemName,
                                    style = PwType.small.copy(fontWeight = FontWeight.Medium),
                                    color = p.ink,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = if (group.first().itemArchived) {
                                        "已删除物品 · ${group.size} 条"
                                    } else {
                                        "${group.size} 条"
                                    },
                                    style = PwType.label.copy(fontSize = 10.sp),
                                    color = p.faint,
                                )
                            }
                        }
                        items(group, key = { "r" + it.occurrenceId }) { day ->
                            RecordPickRow(
                                day = day,
                                checked = day.occurrenceId in selected,
                                onToggle = {
                                    selected = if (day.occurrenceId in selected) {
                                        selected - day.occurrenceId
                                    } else {
                                        selected + day.occurrenceId
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "算了", style = PwType.small, color = p.faint)
                }
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = { onConfirm(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                ) {
                    Text(
                        text = if (selected.isEmpty()) "删除" else "删除 ${selected.size} 条",
                        style = PwType.small.copy(fontWeight = FontWeight.Medium),
                        color = if (selected.isEmpty()) p.faint else p.red,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordPickRow(day: MockEvidenceDay, checked: Boolean, onToggle: () -> Unit) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) p.surfaceAlt else p.surface)
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(34.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = p.red,
                uncheckedColor = p.line,
                checkmarkColor = p.surface,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${day.dateLabel} ${day.weekday} · ${day.timeLabel}",
                style = PwType.small,
                color = p.inkSoft,
            )
            Text(
                text = when {
                    day.photoKeys.isEmpty() && day.itemArchived -> "照片已清理"
                    day.photoKeys.isEmpty() -> "没有照片"
                    else -> "${day.photoKeys.size} 张照片"
                },
                style = PwType.label.copy(fontSize = 10.sp),
                color = p.faint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = when {
                day.itemArchived -> "已删除"
                day.status == DayStatus.PENDING -> "待拍"
                day.status == DayStatus.VERIFIED -> "已验证"
                else -> "漏拍"
            },
            style = PwType.label.copy(fontSize = 10.sp),
            color = when {
                day.itemArchived -> p.faint
                day.status == DayStatus.PENDING -> p.amber
                day.status == DayStatus.VERIFIED -> p.mint
                else -> p.red
            },
        )
    }
}
