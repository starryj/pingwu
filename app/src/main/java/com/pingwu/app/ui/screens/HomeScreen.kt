package com.pingwu.app.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pingwu.app.data.mock.DayStatus
import com.pingwu.app.data.mock.MockItem
import com.pingwu.app.ui.UiState
import com.pingwu.app.ui.components.Avatar
import com.pingwu.app.ui.components.DashedDivider
import com.pingwu.app.ui.components.ItemGlyph
import com.pingwu.app.ui.components.MiniBar
import com.pingwu.app.ui.components.PhotoThumb
import com.pingwu.app.ui.components.PwLabel
import com.pingwu.app.ui.components.SectionHead
import com.pingwu.app.ui.components.StatusChip
import com.pingwu.app.ui.components.TicketCard
import com.pingwu.app.ui.components.TopBar
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val HEADER_DATE = DateTimeFormatter.ofPattern("MM.dd")

@Composable
fun HomeScreen(
    state: UiState,
    avatar: String,
    avatarPhoto: String = "",
    userName: String = "朋友",
    onAvatarClick: () -> Unit,
    onOpenItem: (Int) -> Unit,
    onCapture: () -> Unit,
    onSeeEvidence: () -> Unit,
    onAdd: () -> Unit,
) {
    val p = LocalPwPalette.current
    val stats = state.stats
    val remaining = (stats.todayTotal - stats.todayDone).coerceAtLeast(0)
    val activeIds = state.items.map { it.id }.toSet()
    val next = state.days.firstOrNull { it.status == DayStatus.PENDING && it.itemId in activeIds }
    val nextItem = next?.let { day -> state.items.firstOrNull { it.id == day.itemId } }
    val nextUp = state.nextUp
    val nextTime = next?.timeLabel?.substringBefore(" ·")?.trim()?.takeIf { it.isNotBlank() }
        ?: nextUp?.timeLabel
        ?: "—"
    val nextName = nextItem?.let { "${it.name} · ${it.countLabel}" }
        ?: nextUp?.let { "${it.itemName} · ×${it.count}" }
        ?: "今天没有更多安排了"
    val nextDayHint = if (next == null) nextUp?.dayLabel else null
    val todayPhoto = state.photos.firstOrNull()
    val today = LocalDate.now()
    val todayText = "${today.format(HEADER_DATE)} ${weekdayOf(today)}"
    val greeting = when (java.time.LocalTime.now().hour) {
        in 5..10 -> "早上好，$userName"
        in 11..12 -> "中午好，$userName"
        in 13..17 -> "下午好，$userName"
        in 18..22 -> "晚上好，$userName"
        else -> "夜深了，$userName"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(right = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = todayText, style = PwType.small, color = p.faint)
                Spacer(Modifier.width(10.dp))
                Avatar(avatar, photoKey = avatarPhoto, onClick = onAvatarClick)
            }
        })

        Text(text = greeting, style = PwType.title.copy(fontSize = 24.sp), color = p.ink)
        Text(
            text = when {
                state.items.isEmpty() -> "还没有物品，先添一件吧"
                remaining > 0 -> "今天还有 $remaining 次待拍"
                else -> "今天都拍完了"
            },
            style = PwType.small,
            color = p.faint,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        )

        if (state.items.isEmpty()) {
            EmptyHomeCard(onAdd = onAdd)
        } else {
        // 下一次提醒
        TicketCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PwLabel("下一次")
                StatusChip(
                    text = when {
                        remaining > 0 -> "待拍"
                        nextUp != null -> "排着"
                        else -> "已完成"
                    },
                    tone = if (remaining > 0) p.amber else p.mint,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = nextTime,
                    style = PwType.display,
                    color = p.ink,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (nextDayHint != null) {
                    Text(
                        text = nextDayHint,
                        style = PwType.label,
                        color = p.faint,
                        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                    )
                }
            }
            Text(
                text = nextName,
                style = PwType.small,
                color = p.faint,
                modifier = Modifier.padding(top = 4.dp),
            )
            DashedDivider(Modifier.padding(vertical = 14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "验证窗口 ${windowHint(state)}", style = PwType.small, color = p.faint)
                Text(text = "拍下来才算数", style = PwType.small, color = p.amber)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.amber)
                    .clickable { onCapture() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "拍 下 来",
                    style = PwType.body.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp),
                    color = if (p.isDark) Color(0xFF0A1014) else Color(0xFFFBF6EC),
                )
            }
        }

        // 今日进度
        SectionHead("今日进度", action = "全部凭物", onAction = onSeeEvidence)
        if (stats.todayTotal == 0) {
            Text(
                text = "今天没有安排",
                style = PwType.small,
                color = p.faint,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "${stats.todayDone}", style = PwType.display.copy(fontSize = 34.sp), color = p.ink)
                Text(
                    text = " / ${stats.todayTotal} 次",
                    style = PwType.small,
                    color = p.faint,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                // 一天安排得多时（比如 9 次）原来的 46dp 宽块会把整行挤出屏幕，
                // 这里按格数收窄，最多画 9 格。
                val shownBars = stats.todayTotal.coerceAtMost(9)
                val barWidth = when {
                    shownBars <= 3 -> 44.dp
                    shownBars <= 5 -> 30.dp
                    shownBars <= 7 -> 23.dp
                    else -> 18.dp
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(shownBars) { i ->
                        MiniBar(active = i < stats.todayDone, modifier = Modifier.width(barWidth))
                    }
                }
            }
        }

        // 今日凭物
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (todayPhoto != null) {
                PhotoThumb(todayPhoto.photoKey, Modifier.weight(1f).height(104.dp), radius = 12.dp)
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(104.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, p.line, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "还没有", style = PwType.label, color = p.faint)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(104.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, p.line, RoundedCornerShape(12.dp))
                    .clickable { onCapture() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (stats.todayTotal == 0) {
                        Text(text = "今天没有安排", style = PwType.label, color = p.faint)
                    } else {
                        Text(text = nextTime, style = PwType.small, color = p.amber)
                        Text(
                            text = if (remaining > 0) "待拍" else "已完成",
                            style = PwType.label,
                            color = p.faint,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        // 周条
        TicketCard(Modifier.padding(top = 18.dp), padding = 16.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (stats.scheduledCount == 0) {
                        "最近 7 天 · 还没有记录"
                    } else {
                        "已连续 ${stats.streakDays} 天按时"
                    },
                    style = PwType.small,
                    color = p.ink,
                )
                if (stats.decidedCount > 0) {
                    Text(text = "${stats.onTimePercent}%", style = PwType.small, color = p.amber)
                } else if (stats.scheduledCount > 0) {
                    Text(text = "—", style = PwType.small, color = p.faint)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                stats.weekFlags.forEach { flag ->
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(
                                    when (flag) {
                                        1 -> 38.dp
                                        2 -> 22.dp
                                        0 -> 16.dp
                                        else -> 8.dp
                                    },
                                )
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when (flag) {
                                        1 -> p.amber
                                        2 -> p.amber.copy(alpha = 0.38f)
                                        0 -> p.red.copy(alpha = 0.55f)
                                        else -> p.line
                                    },
                                ),
                        )
                        Text(
                            text = when (flag) {
                                1 -> "·"
                                2 -> "待"
                                0 -> "漏"
                                else -> ""
                            },
                            style = PwType.label.copy(fontSize = 8.sp),
                            color = p.faint,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            }
        }

        }

        // 我的物品（有东西才显示；空态由上面的卡片接住）
        if (state.items.isNotEmpty()) {
            SectionHead("我的物品", action = "管理")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.items.forEach { item ->
                    ItemRow(item = item, onClick = { onOpenItem(item.id) })
                }
            }
        }

        Text(
            text = "照片只存在本机 · 拍下来才算真的做过",
            style = PwType.label,
            color = p.faint,
            modifier = Modifier.padding(top = 22.dp, bottom = 28.dp),
        )
    }
}

@Composable
private fun EmptyHomeCard(onAdd: () -> Unit) {
    val p = LocalPwPalette.current
    TicketCard(padding = 18.dp) {
        PwLabel("第一次用")
        Text(
            text = "先添加一件你怕忘的东西",
            style = PwType.title,
            color = p.ink,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = "钥匙、钱包、护照、工牌，什么都行。到点它会提醒你，拍一张照，这一件才算做过。",
            style = PwType.small,
            color = p.faint,
            modifier = Modifier.padding(top = 6.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.amber)
                .clickable { onAdd() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "添加第一件物品",
                style = PwType.body.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
                color = if (p.isDark) Color(0xFF0A1014) else Color(0xFFFBF6EC),
            )
        }
    }
}

private fun weekdayOf(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}

private fun windowHint(state: UiState): String {
    // 窗口长度目前取第一条提醒的配置；M3 设置页可改
    return "30 分钟"
}

@Composable
fun ItemRow(item: MockItem, onClick: () -> Unit) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.surface)
            .border(1.dp, p.lineSoft, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemGlyph(item.category, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.name, style = PwType.body, color = p.ink)
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = listOf(item.note, item.countLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    style = PwType.label,
                    color = p.faint,
                )
                Text(text = "·", style = PwType.label, color = p.line)
                Text(text = item.schedule, style = PwType.label, color = p.faint)
            }
        }
        Text(text = "›", style = PwType.title, color = p.faint)
    }
}
