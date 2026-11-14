package com.pingwu.app.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pingwu.app.data.core.ItemDraft
import com.pingwu.app.data.core.ReminderRule
import com.pingwu.app.data.mock.ItemCategory
import com.pingwu.app.data.mock.MockItem
import com.pingwu.app.ui.components.BackBar
import com.pingwu.app.ui.components.DashedDivider
import com.pingwu.app.ui.components.ItemGlyph
import com.pingwu.app.ui.components.PwLabel
import com.pingwu.app.ui.components.TicketCard
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType
import java.time.LocalDate
import java.util.Locale

/**
 * M9 通用物品：名字、备注、每次几件、分类，加上「每天 / 每周 / 每月 / 一次」四种提醒。
 * 新建与编辑共用一屏（editing == null 表示新建）。
 */
@Composable
fun AddItemScreen(
    editing: MockItem?,
    onBack: () -> Unit,
    onSave: (ItemDraft) -> Unit,
) {
    val p = LocalPwPalette.current
    val context = LocalContext.current

    var name by remember(editing) { mutableStateOf(editing?.name.orEmpty()) }
    var note by remember(editing) { mutableStateOf(editing?.note.orEmpty()) }
    var count by remember(editing) { mutableStateOf(editing?.count?.coerceAtLeast(1) ?: 1) }
    var category by remember(editing) { mutableStateOf(editing?.category ?: ItemCategory.OTHER) }
    var rule by remember(editing) { mutableStateOf(editing?.rule ?: ReminderRule.DAILY) }
    var weekDays by remember(editing) {
        mutableStateOf(
            editing?.daysCsv.orEmpty().split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..7 }
                .toSet(),
        )
    }
    var monthDay by remember(editing) {
        mutableStateOf(
            editing?.daysCsv.orEmpty().split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .firstOrNull { it in 1..31 } ?: 1,
        )
    }
    var times by remember(editing) {
        mutableStateOf(editing?.times?.takeIf { it.isNotEmpty() } ?: listOf("08:00"))
    }
    var onceDate by remember(editing) { mutableStateOf(editing?.onceDate.orEmpty()) }
    var windowMinutes by remember(editing) { mutableStateOf(editing?.windowMinutes ?: 30) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pickTime(initial: String, onPicked: (String) -> Unit) {
        val hour = initial.substringBefore(':').toIntOrNull()?.coerceIn(0, 23) ?: 8
        val minute = initial.substringAfter(':', "00").toIntOrNull()?.coerceIn(0, 59) ?: 0
        TimePickerDialog(context, { _, h, m -> onPicked(formatTime(h, m)) }, hour, minute, true).show()
    }

    fun pickDate() {
        val base = runCatching { LocalDate.parse(onceDate) }.getOrElse { LocalDate.now() }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                onceDate = LocalDate.of(year, month + 1, day).toString()
            },
            base.year,
            base.monthValue - 1,
            base.dayOfMonth,
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        BackBar(title = if (editing == null) "添加物品" else "编辑物品", onBack = onBack)

        Text(text = "它是哪一件东西？", style = PwType.title, color = p.ink)
        Text(
            text = "钥匙、钱包、护照、药……只要是看得见、拍得到的东西都行。",
            style = PwType.small,
            color = p.faint,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        )

        // ---- 基本信息 ----
        TicketCard(padding = 16.dp) {
            PwLabel("物品")
            Field("名称", name, "如 家门钥匙 / 盐酸二甲双胍片") { name = it }
            Field("备注（可空）", note, "如 0.25g / 放在玄关抽屉") { note = it }

            Text(text = "每次几件", style = PwType.tiny, color = p.faint, modifier = Modifier.padding(top = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stepper(
                    value = "$count",
                    onMinus = { count = (count - 1).coerceAtLeast(1) },
                    onPlus = { count = (count + 1).coerceAtMost(99) },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "拍照时要看到 $count 件",
                    style = PwType.small,
                    color = p.faint,
                )
            }

            Text(text = "分类", style = PwType.tiny, color = p.faint, modifier = Modifier.padding(top = 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ItemCategory.entries.forEach { item ->
                    CategoryChip(
                        category = item,
                        selected = category == item,
                        modifier = Modifier.weight(1f),
                        onClick = { category = item },
                    )
                }
            }
        }

        // ---- 提醒 ----
        TicketCard(Modifier.padding(top = 12.dp), padding = 16.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PwLabel("什么时候提醒")
                Text(
                    text = ReminderRule.label(rule),
                    style = PwType.label,
                    color = p.amber,
                )
            }

            // 四个重复规则
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReminderRule.ALL.forEach { r ->
                    RuleChip(
                        text = ReminderRule.label(r),
                        selected = rule == r,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            rule = r
                            if (r == ReminderRule.ONCE && onceDate.isBlank()) {
                                onceDate = LocalDate.now().plusDays(1).toString()
                            }
                        },
                    )
                }
            }

            when (rule) {
                ReminderRule.WEEKLY -> {
                    Text(
                        text = "哪几天",
                        style = PwType.tiny,
                        color = p.faint,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (1..7).forEach { day ->
                            DayChip(
                                text = ReminderRule.weekdayName(day).removePrefix("周"),
                                selected = day in weekDays,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    weekDays = if (day in weekDays) weekDays - day else weekDays + day
                                },
                            )
                        }
                    }
                }
                ReminderRule.MONTHLY -> {
                    Text(
                        text = "每月哪一天",
                        style = PwType.tiny,
                        color = p.faint,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Stepper(
                            value = "$monthDay",
                            onMinus = { monthDay = (monthDay - 1).coerceAtLeast(1) },
                            onPlus = { monthDay = (monthDay + 1).coerceAtMost(31) },
                        )
                        Spacer(Modifier.weight(1f))
                        Text(text = "每月 $monthDay 日", style = PwType.small, color = p.faint)
                    }
                }
                ReminderRule.ONCE -> {
                    Text(
                        text = "哪一天",
                        style = PwType.tiny,
                        color = p.faint,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, p.line, RoundedCornerShape(12.dp))
                            .clickable { pickDate() }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (onceDate.isBlank()) "选一天" else ReminderRule.formatOnceDate(onceDate),
                            style = PwType.body,
                            color = if (onceDate.isBlank()) p.faint else p.ink,
                        )
                        Text(text = "改", style = PwType.small, color = p.amber)
                    }
                }
            }

            DashedDivider(Modifier.padding(vertical = 14.dp))

            // 时间点
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "时间", style = PwType.tiny, color = p.faint)
                Text(text = "点一下就能改", style = PwType.label, color = p.faint)
            }
            times.forEachIndexed { index, time ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, p.line, RoundedCornerShape(12.dp))
                        .clickable { pickTime(time) { picked -> times = times.toMutableList().also { it[index] = picked } } }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = time, style = PwType.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = p.ink)
                    if (times.size > 1) {
                        Text(
                            text = "删掉",
                            style = PwType.small,
                            color = p.faint,
                            modifier = Modifier.clickable {
                                times = times.toMutableList().also { it.removeAt(index) }
                            },
                        )
                    } else {
                        Text(text = "改", style = PwType.small, color = p.amber)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (times.size < 4) {
                            pickTime("21:00") { picked -> times = (times + picked).distinct().sorted() }
                        }
                    }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(text = "＋ 再加一个时间", style = PwType.small, color = p.amber)
            }

            DashedDivider(Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "验证窗口", style = PwType.tiny, color = p.faint)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        WindowChip(
                            label = "$minutes 分",
                            selected = windowMinutes == minutes,
                            onClick = { windowMinutes = minutes },
                        )
                    }
                }
            }
        }

        if (error != null) {
            Text(
                text = error.orEmpty(),
                style = PwType.small,
                color = p.red,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.amber)
                .clickable {
                    val cleanName = name.trim()
                    val cleanTimes = times.map { it.trim() }.filter { TIME_REGEX.matches(it) }.distinct().sorted()
                    when {
                        cleanName.isEmpty() -> error = "先给它起个名字"
                        cleanTimes.isEmpty() -> error = "至少留一个提醒时间"
                        rule == ReminderRule.WEEKLY && weekDays.isEmpty() -> error = "选上哪几天要拍"
                        rule == ReminderRule.ONCE && onceDate.isBlank() -> error = "选一个日期"
                        else -> {
                            error = null
                            onSave(
                                ItemDraft(
                                    name = cleanName,
                                    note = note.trim(),
                                    count = count,
                                    category = category.raw,
                                    rule = rule,
                                    daysCsv = when (rule) {
                                        ReminderRule.WEEKLY -> weekDays.sorted().joinToString(",")
                                        ReminderRule.MONTHLY -> monthDay.toString()
                                        else -> ""
                                    },
                                    times = cleanTimes,
                                    onceDate = if (rule == ReminderRule.ONCE) onceDate else "",
                                    windowMinutes = windowMinutes,
                                ),
                            )
                        }
                    }
                }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (editing == null) "存 下 来" else "保 存 修 改",
                style = PwType.body.copy(letterSpacing = 3.sp),
                color = p.onAmber,
            )
        }

        Text(
            text = if (editing == null) {
                "存下之后，今天还没到点的提醒会立刻排上；模板照片可以以后再补。"
            } else {
                "改完保存，今天还没拍的待办会按新规则重排。"
            },
            style = PwType.tiny,
            color = p.faint,
            modifier = Modifier.padding(top = 14.dp, bottom = 28.dp),
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val p = LocalPwPalette.current
    Text(text = label, style = PwType.tiny, color = p.faint, modifier = Modifier.padding(top = 10.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        placeholder = { Text(placeholder, style = PwType.small, color = p.faint) },
        textStyle = PwType.body,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = fieldColors(),
    )
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val p = LocalPwPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StepperButton("−", onMinus)
        Text(
            text = value,
            style = PwType.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = p.ink,
            modifier = Modifier.width(46.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepperButton("＋", onPlus)
    }
}

@Composable
private fun StepperButton(text: String, onClick: () -> Unit) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, p.line, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = PwType.body, color = p.amber)
    }
}

@Composable
private fun CategoryChip(
    category: ItemCategory,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPwPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) p.amberSoft else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) p.amber else p.line, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ItemGlyph(category, size = 26.dp)
        Text(
            text = category.label,
            style = PwType.label.copy(fontSize = 10.sp),
            color = if (selected) p.amber else p.faint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RuleChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPwPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) p.amberSoft else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) p.amber else p.line, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = PwType.small, color = if (selected) p.amber else p.faint)
    }
}

@Composable
private fun DayChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPwPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) p.amber else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) p.amber else p.line, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PwType.small,
            color = if (selected) p.onAmber else p.faint,
        )
    }
}

@Composable
private fun WindowChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) p.amberSoft else androidx.compose.ui.graphics.Color.Transparent)
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

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalPwPalette.current.amber,
    unfocusedBorderColor = LocalPwPalette.current.line,
    focusedTextColor = LocalPwPalette.current.ink,
    unfocusedTextColor = LocalPwPalette.current.ink,
    cursorColor = LocalPwPalette.current.amber,
    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
)

private fun formatTime(hour: Int, minute: Int): String = String.format(Locale.US, "%02d:%02d", hour, minute)

private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
