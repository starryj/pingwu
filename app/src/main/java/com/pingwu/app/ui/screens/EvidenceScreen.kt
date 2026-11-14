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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.pingwu.app.data.mock.DayStatus
import com.pingwu.app.data.mock.MockEvidenceDay
import com.pingwu.app.ui.UiState
import com.pingwu.app.ui.toProof
import com.pingwu.app.ui.components.DashedDivider
import com.pingwu.app.ui.components.PhotoThumb
import com.pingwu.app.ui.components.PwLabel
import com.pingwu.app.ui.components.StatusChip
import com.pingwu.app.ui.components.TicketCard
import com.pingwu.app.ui.components.TopBar
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields

/** 凭物页的三段：已记住 / 待验证 / 已删除。 */
private enum class ProofSection { REMEMBERED, PENDING, DELETED }

private data class WeekGroup(val key: String, val label: String, val days: List<MockEvidenceDay>)

private data class MonthGroup(val key: String, val label: String, val weeks: List<WeekGroup>)

@Composable
fun EvidenceScreen(
    state: UiState,
    onOpenProof: (MockEvidenceDay) -> Unit,
    onDeleteRecords: (List<Long>) -> Unit = {},
) {
    val p = LocalPwPalette.current
    var section by remember { mutableStateOf(ProofSection.REMEMBERED) }
    var wallMode by remember { mutableStateOf(false) }
    var expandedMonths by remember { mutableStateOf(emptySet<String>()) }
    var expandedWeeks by remember { mutableStateOf(emptySet<String>()) }
    var expandedDayId by remember { mutableStateOf<Int?>(null) }
    // 已删除段的多选彻底删除
    var selectMode by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    var showDeleteRecords by remember { mutableStateOf(false) }

    val remembered = state.days.filter { it.status == DayStatus.VERIFIED && !it.itemArchived }
    val pending = state.days.filter { it.status != DayStatus.VERIFIED && !it.itemArchived }
    val deleted = state.days.filter { it.itemArchived }
    val months = remember(remembered) { groupMonths(remembered) }

    // 默认展开最近一个月、最近一周：进来就能看到最新的那几张。
    LaunchedEffect(months.firstOrNull()?.key, months.firstOrNull()?.weeks?.firstOrNull()?.key) {
        months.firstOrNull()?.let { month ->
            if (expandedMonths.isEmpty()) expandedMonths = setOf(month.key)
            month.weeks.firstOrNull()?.let { week ->
                if (expandedWeeks.isEmpty()) expandedWeeks = setOf(week.key)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(right = { StatusChip("本周", p.amber) })

        Text(text = "凭物", style = PwType.title.copy(fontSize = 24.sp), color = p.ink)
        Text(
            text = "拍下来的，都留在这里",
            style = PwType.small,
            color = p.faint,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        )

        // 统计
        TicketCard(padding = 16.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("${state.stats.evidenceCount}", "张")
                Stat("${state.stats.onTimePercent}%", "按时")
                Stat("${state.stats.streakDays}", "连续天")
                Stat("${state.stats.missedCount}", "次漏")
            }
            DashedDivider(Modifier.padding(top = 14.dp))
            Text(
                text = "本周 ${state.stats.evidenceCount} 张 · ${state.stats.onTimePercent}% 按时 · 漏 ${state.stats.missedCount} 次",
                style = PwType.label,
                color = p.faint,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        // 三段
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionChip("已记住", remembered.size, section == ProofSection.REMEMBERED) {
                section = ProofSection.REMEMBERED
            }
            SectionChip("待验证", pending.size, section == ProofSection.PENDING) {
                section = ProofSection.PENDING
            }
            SectionChip("已删除", deleted.size, section == ProofSection.DELETED) {
                section = ProofSection.DELETED
            }
        }
        // 离开「已删除」就退出多选，免得状态挂着。
        LaunchedEffect(section) {
            if (section != ProofSection.DELETED) {
                selectMode = false
                picked = emptySet()
            }
        }

        when (section) {
            ProofSection.REMEMBERED -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeChip("台账", selected = !wallMode) { wallMode = false }
                    ModeChip("照片墙", selected = wallMode) { wallMode = true }
                }
                if (wallMode) {
                    PhotoWall(state)
                } else if (remembered.isEmpty()) {
                    EmptyNote("还没有记住的凭物。到点拍一张，这里就攒起来了。")
                } else {
                    months.forEach { month ->
                        val monthOpen = month.key in expandedMonths
                        MonthCard(
                            month = month,
                            open = monthOpen,
                            onToggleMonth = {
                                expandedMonths = if (monthOpen) {
                                    expandedMonths - month.key
                                } else {
                                    expandedMonths + month.key
                                }
                            },
                            expandedWeeks = expandedWeeks,
                            onToggleWeek = { key ->
                                expandedWeeks = if (key in expandedWeeks) {
                                    expandedWeeks - key
                                } else {
                                    expandedWeeks + key
                                }
                            },
                            expandedDayId = expandedDayId,
                            onToggleDay = { id -> expandedDayId = if (expandedDayId == id) null else id },
                            onOpenProof = onOpenProof,
                        )
                    }
                }
            }

            ProofSection.PENDING -> {
                if (pending.isEmpty()) {
                    EmptyNote("今天没有待拍的，都记下了。")
                } else {
                    Text(
                        text = "还没拍的、漏掉的，都在这",
                        style = PwType.label,
                        color = p.faint,
                        modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pending.forEach { day ->
                            DayRow(
                                day = day,
                                expanded = expandedDayId == day.id,
                                onToggle = {
                                    expandedDayId = if (expandedDayId == day.id) null else day.id
                                },
                                onOpenProof = { onOpenProof(day) },
                            )
                        }
                    }
                }
            }

            ProofSection.DELETED -> {
                if (deleted.isEmpty()) {
                    EmptyNote("还没有删掉的物品。删掉的东西会留在这里，照片也可以清掉。")
                } else {
                    if (selectMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (picked.isEmpty()) "点记录条勾选" else "已选 ${picked.size} 条",
                                style = PwType.small,
                                color = if (picked.isEmpty()) p.faint else p.amber,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                val all = deleted.map { it.occurrenceId }.filter { it > 0 }.toSet()
                                picked = if (picked.size == all.size) emptySet() else all
                            }) {
                                Text(
                                    text = if (picked.isNotEmpty() && picked.size == deleted.count { it.occurrenceId > 0 }) "全不选" else "全选",
                                    style = PwType.small,
                                    color = p.inkSoft,
                                )
                            }
                            TextButton(onClick = {
                                selectMode = false
                                picked = emptySet()
                            }) {
                                Text(text = "取消", style = PwType.small, color = p.faint)
                            }
                        }
                    } else {
                        Text(
                            text = "物品删了，记录还在；长按一条记录可以彻底删掉",
                            style = PwType.label,
                            color = p.faint,
                            modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        deleted.forEach { day ->
                            DayRow(
                                day = day,
                                expanded = expandedDayId == day.id && !selectMode,
                                onToggle = {
                                    if (selectMode) {
                                        picked = if (day.occurrenceId in picked) {
                                            picked - day.occurrenceId
                                        } else {
                                            picked + day.occurrenceId
                                        }
                                    } else {
                                        expandedDayId = if (expandedDayId == day.id) null else day.id
                                    }
                                },
                                onOpenProof = { onOpenProof(day) },
                                selectable = selectMode,
                                selected = day.occurrenceId in picked,
                                onLongPress = {
                                    selectMode = true
                                    picked = picked + day.occurrenceId
                                },
                            )
                        }
                    }
                    if (selectMode) {
                        DeletePickedBar(
                            count = picked.size,
                            onDelete = { showDeleteRecords = true },
                        )
                    }
                }
            }
        }

        Text(
            text = "照片只存在本机 · 可在「我的」里清理",
            style = PwType.label,
            color = p.faint,
            modifier = Modifier.padding(top = 22.dp, bottom = 28.dp),
        )
    }

    if (showDeleteRecords) {
        AlertDialog(
            onDismissRequest = { showDeleteRecords = false },
            title = {
                Text("彻底删除这 ${picked.size} 条记录？", style = PwType.title, color = p.ink)
            },
            text = {
                Text(
                    "记录和它名下的照片会一起删掉，删了就找不回来。" +
                        "统计里的张数、按时率也会跟着变。",
                    style = PwType.small,
                    color = p.inkSoft,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = picked.toList()
                    showDeleteRecords = false
                    selectMode = false
                    picked = emptySet()
                    onDeleteRecords(ids)
                }) { Text(text = "彻底删除", color = p.red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteRecords = false }) {
                    Text(text = "算了", color = p.faint)
                }
            },
            containerColor = p.surface,
        )
    }
}

/** 已删除段底部的「删除选中」条。 */
@Composable
private fun DeletePickedBar(count: Int, onDelete: () -> Unit) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 0) "还没选记录" else "已选 $count 条",
            style = PwType.small,
            color = if (count == 0) p.faint else p.ink,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDelete, enabled = count > 0) {
            Text(
                text = if (count == 0) "彻底删除" else "彻底删除 $count 条",
                style = PwType.small.copy(fontWeight = FontWeight.Medium),
                color = if (count == 0) p.faint else p.red,
            )
        }
    }
}

/** 把已记住的按「月 → 周」两级分好（天在周里）。 */
private fun groupMonths(days: List<MockEvidenceDay>): List<MonthGroup> =
    days.filter { it.isoDate.isNotBlank() }
        .groupBy { it.isoDate.substring(0, 7) }
        .toSortedMap(compareByDescending { it })
        .map { (monthKey, monthDays) ->
            val year = monthKey.substring(0, 4)
            val month = monthKey.substring(5, 7).trimStart('0')
            MonthGroup(
                key = monthKey,
                label = "$year 年 $month 月",
                weeks = monthDays.groupBy { isoWeekKey(it.isoDate) }
                    .toSortedMap(compareByDescending { it })
                    .map { (weekKey, weekDays) ->
                        WeekGroup(
                            key = weekKey,
                            label = weekLabel(weekDays.first().isoDate),
                            days = weekDays.sortedByDescending { it.isoDate },
                        )
                    },
            )
        }

private fun isoWeekKey(iso: String): String {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    val year = date.get(IsoFields.WEEK_BASED_YEAR)
    return "$year-W" + week.toString().padStart(2, '0')
}

private fun weekLabel(iso: String): String {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val monday = date.with(DayOfWeek.MONDAY)
    val sunday = monday.plusDays(6)
    return "${monday.monthValue}.${monday.dayOfMonth} – ${sunday.monthValue}.${sunday.dayOfMonth}"
}

@Composable
private fun MonthCard(
    month: MonthGroup,
    open: Boolean,
    onToggleMonth: () -> Unit,
    expandedWeeks: Set<String>,
    onToggleWeek: (String) -> Unit,
    expandedDayId: Int?,
    onToggleDay: (Int) -> Unit,
    onOpenProof: (MockEvidenceDay) -> Unit,
) {
    val p = LocalPwPalette.current
    val count = month.weeks.sumOf { it.days.size }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(p.surfaceAlt)
            .border(1.dp, p.lineSoft, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleMonth() }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = month.label, style = PwType.body, color = p.ink, modifier = Modifier.weight(1f))
            Text(text = "$count 张", style = PwType.label, color = p.faint, modifier = Modifier.padding(end = 8.dp))
            Text(text = if (open) "▾" else "›", style = PwType.small, color = p.faint)
        }
        if (open) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                month.weeks.forEach { week ->
                    val weekOpen = week.key in expandedWeeks
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.surface)
                            .border(1.dp, p.lineSoft, RoundedCornerShape(12.dp)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleWeek(week.key) }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "第 ${week.key.substringAfter("-W").trimStart('0')} 周",
                                style = PwType.small,
                                color = p.inkSoft,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = week.label,
                                style = PwType.label.copy(fontSize = 10.sp),
                                color = p.faint,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(text = "${week.days.size} 张", style = PwType.label, color = p.faint, modifier = Modifier.padding(end = 8.dp))
                            Text(text = if (weekOpen) "▾" else "›", style = PwType.small, color = p.faint)
                        }
                        if (weekOpen) {
                            Column(
                                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                week.days.forEach { day ->
                                    DayRow(
                                        day = day,
                                        expanded = expandedDayId == day.id,
                                        onToggle = { onToggleDay(day.id) },
                                        onOpenProof = { onOpenProof(day) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    val p = LocalPwPalette.current
    Text(
        text = text,
        style = PwType.small,
        color = p.faint,
        modifier = Modifier.padding(top = 24.dp),
    )
}

/** M5：三列照片墙，按时间倒序显示全部凭物照片。 */
@Composable
private fun PhotoWall(state: UiState) {
    val p = LocalPwPalette.current
    if (state.photos.isEmpty()) {
        Text(
            text = "还没有凭物照片。拍下第一张，就会出现在这里。",
            style = PwType.small,
            color = p.faint,
            modifier = Modifier.padding(top = 24.dp),
        )
        return
    }
    Text(
        text = "全部 ${state.photos.size} 张 · 按日期倒序",
        style = PwType.label,
        color = p.faint,
        modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
    )
    state.photos.chunked(3).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { photo ->
                Column(modifier = Modifier.weight(1f)) {
                    PhotoThumb(photo.photoKey, Modifier.fillMaxWidth().height(104.dp))
                    Text(
                        text = photo.dateLabel,
                        style = PwType.label.copy(fontSize = 9.sp),
                        color = p.faint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            repeat(3 - row.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 三段的大标签，带条数。 */
@Composable
private fun SectionChip(text: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) p.amber else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else p.line, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = if (count > 0) "$text $count" else text,
            style = PwType.label,
            color = if (selected) (if (p.isDark) Color(0xFF0A1014) else Color(0xFFFBF6EC)) else p.inkSoft,
        )
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) p.amber else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else p.line, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = PwType.label,
            color = if (selected) (if (p.isDark) Color(0xFF0A1014) else Color(0xFFFBF6EC)) else p.inkSoft,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayRow(
    day: MockEvidenceDay,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenProof: () -> Unit,
    selectable: Boolean = false,
    selected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    val p = LocalPwPalette.current
    val tone = when (day.status) {
        DayStatus.PENDING -> p.amber
        DayStatus.VERIFIED -> p.mint
        DayStatus.MISSED -> p.red
    }
    val statusText = when {
        day.itemArchived -> "已删除"
        day.status == DayStatus.PENDING -> "待拍"
        day.status == DayStatus.VERIFIED -> "已验证"
        else -> "漏拍"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(p.surface)
            .border(1.dp, if (expanded) p.line else p.lineSoft, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectable) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = p.red,
                        uncheckedColor = p.line,
                        checkmarkColor = p.surface,
                    ),
                )
                Spacer(Modifier.width(4.dp))
            }
            Column(modifier = Modifier.width(64.dp)) {
                Text(text = day.dateLabel, style = PwType.mono.copy(fontWeight = FontWeight.Medium), color = p.ink)
                Text(text = day.weekday, style = PwType.label.copy(fontSize = 9.sp), color = p.faint, modifier = Modifier.padding(top = 2.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = day.itemName, style = PwType.small, color = p.inkSoft)
                Text(text = day.timeLabel, style = PwType.label.copy(fontSize = 9.sp), color = p.faint, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                text = when {
                    day.photoKeys.isEmpty() && day.itemArchived -> "照片已清理"
                    day.photoKeys.isEmpty() -> "—"
                    else -> "${day.photoKeys.size} 张"
                },
                style = PwType.label.copy(fontSize = 9.sp),
                color = p.faint,
                modifier = Modifier.padding(end = 8.dp),
            )
            StatusChip(statusText, tone)
            Text(
                text = when {
                    selectable -> ""
                    expanded -> "▾"
                    else -> "›"
                },
                style = PwType.small,
                color = p.faint,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (expanded) {
            Column {
                DashedDivider(Modifier.padding(horizontal = 14.dp))
                if (day.photoKeys.isEmpty()) {
                    Text(
                        text = "这张照片已经清理掉了，记录还留着。",
                        style = PwType.label,
                        color = p.faint,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp),
                    )
                }
                Box(modifier = Modifier.padding(14.dp)) {
                    ProofStub(proof = day.toProof(), onClick = onOpenProof)
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val p = LocalPwPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = PwType.num.copy(fontSize = 24.sp), color = p.ink)
        Text(text = label, style = PwType.label.copy(fontSize = 9.sp), color = p.faint, modifier = Modifier.padding(top = 3.dp))
    }
}
