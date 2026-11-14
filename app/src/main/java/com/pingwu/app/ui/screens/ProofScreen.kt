package com.pingwu.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pingwu.app.ui.Proof
import com.pingwu.app.ui.components.PhotoThumb
import com.pingwu.app.ui.components.PwLabel
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType

/**
 * 凭据页：一张邮戳收据式的票面（B 方向）。
 *
 * 从「凭物」台账点开的是已经收下的存根；从拍照成功跳过来的是刚打出来、还没收下的一张。
 * 收据的骨架是「凭物回执 + 五条点线行 + 照片存根 + 右下那枚手盖的圆章」。
 */
@Composable
fun ProofScreen(
    proof: Proof,
    justCaptured: Boolean,
    onClose: () -> Unit,
) {
    val p = LocalPwPalette.current
    val accent = if (proof.verified) p.mint else p.amber
    val mono = FontFamily.Monospace

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (p.isDark) 0.86f else 0.68f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .verticalScroll(rememberScrollState())
                .clickable { },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 收据本体 ──
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(p.surface)
                        .diagonalTexture(p.ink.copy(alpha = if (p.isDark) 0.045f else 0.05f))
                        .border(1.5.dp, p.line, RoundedCornerShape(6.dp))
                        .padding(6.dp)
                        .border(1.dp, p.line, RoundedCornerShape(3.dp))
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                ) {
                    ReceiptHead(serial = proof.serial, accent = accent)

                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(p.line),
                    )
                    Spacer(Modifier.height(10.dp))

                    ReceiptRow("日期", "${proof.dateLabel} ${proof.weekday}")
                    ReceiptRow("时间", proof.timeLabel, valueColor = accent)
                    if (proof.lines.size > 1) {
                        // 一次拍好几件：逐件列一行，别合成「事项 + 总数」让人对不上。
                        ReceiptRow("事项", "共 ${proof.lines.size} 件")
                        proof.lines.forEach { line ->
                            ReceiptRow("· ${line.name}", "× ${line.count}")
                        }
                    } else {
                        ReceiptRow("事项", proof.itemName)
                        ReceiptRow("数量", "× ${proof.count}")
                    }
                    ReceiptRow(
                        label = "判定",
                        value = when {
                            !proof.verified -> "未拍"
                            proof.online -> "在线判定"
                            else -> "本机判定"
                        },
                        valueColor = if (proof.verified) accent else p.faint,
                    )

                    proof.pendingNote?.let { note ->
                        Text(
                            text = "还差：$note（继续待拍）",
                            style = PwType.tiny,
                            color = p.amber,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    if (proof.isLate) {
                        Text(
                            text = "这一张拍得晚了些，也算数。",
                            style = PwType.tiny,
                            color = p.amber,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    // 照片存根
                    Spacer(Modifier.height(14.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (proof.photos.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .background(p.surfaceAlt)
                                    .border(1.dp, p.lineSoft, RoundedCornerShape(2.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "这一张没留下照片", style = PwType.tiny, color = p.faint)
                            }
                        } else {
                            PhotoThumb(
                                photoKey = proof.photos.first(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(172.dp),
                                radius = 2.dp,
                            )
                        }
                        Text(
                            text = "照 片 存 根",
                            style = PwType.label.copy(letterSpacing = 1.5.sp),
                            color = p.ink.copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .background(p.surface.copy(alpha = 0.92f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "拍下来，才算真的做过",
                        style = PwType.small.copy(letterSpacing = 1.sp),
                        color = p.ink.copy(alpha = 0.72f),
                    )
                }

                // 手盖的圆章：压在照片右下、悬出票面一点
                Stamp(
                    verified = proof.verified,
                    dateText = proof.dateLabel.replace('.', '.'),
                    color = accent,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 20.dp)
                        .rotate(-13f),
                )
            }

            // ── 票面之外的操作 ──
            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.amber)
                    .clickable(onClick = onClose)
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (justCaptured) "收 下" else "关 闭",
                    style = PwType.body.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp),
                    color = p.onAmber,
                )
            }
            Text(
                text = if (justCaptured) "已存进今天的凭物 · 点票面以外也能关" else "点票面以外的地方也能关",
                style = PwType.tiny,
                color = p.ink.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
            )
        }
    }
}

/** 票据抬头：左边「凭物回执」，右边流水号。 */
@Composable
private fun ReceiptHead(serial: String, accent: Color) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "凭 物 回 执",
            style = PwType.title.copy(letterSpacing = 5.sp, fontWeight = FontWeight.Normal),
            color = p.ink,
        )
        Text(
            text = serial,
            style = PwType.label.copy(fontFamily = FontFamily.Monospace),
            color = accent,
        )
    }
}

/** 收据的一行：标签 · 点线 · 值。 */
@Composable
private fun ReceiptRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = PwType.small, color = p.faint)
        DottedLeader(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .height(12.dp),
            color = p.line,
        )
        Text(
            text = value,
            style = PwType.small.copy(fontFamily = FontFamily.Monospace),
            color = if (valueColor == Color.Unspecified) p.ink else valueColor,
        )
    }
}

/** 一排小点，把标签和值连起来，像收据上的引导点。 */
@Composable
private fun DottedLeader(modifier: Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val y = size.height / 2f
        val step = 5.dp.toPx()
        val r = 0.9.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawCircle(color = color, radius = r, center = Offset(x, y))
            x += step
        }
    }
}

/** 斜纹底：很淡，只在光下看得出纸的织理。 */
private fun Modifier.diagonalTexture(color: Color): Modifier = drawBehind {
    val step = 11.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x + size.height, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        x += step
    }
}

/** 手盖的圆章：双圈 + 日期，故意歪一点。 */
@Composable
private fun Stamp(
    verified: Boolean,
    dateText: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(96.dp)
            .border(2.5.dp, color.copy(alpha = 0.80f), CircleShape)
            .padding(6.dp)
            .border(1.dp, color.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (verified) "已验证" else "待 拍",
                style = PwType.body.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                ),
                color = color.copy(alpha = 0.88f),
            )
            Text(
                text = dateText,
                style = PwType.label.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 0.6.sp,
                ),
                color = color.copy(alpha = 0.80f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** 小票根预览：台账行里展开的那一块，点了才进完整凭据页。 */
@Composable
fun ProofStub(proof: Proof, onClick: () -> Unit) {
    val p = LocalPwPalette.current
    val accent = if (proof.verified) p.mint else p.amber
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(p.surfaceAlt)
            .diagonalTexture(p.ink.copy(alpha = if (p.isDark) 0.04f else 0.045f))
            .border(1.5.dp, p.line, RoundedCornerShape(6.dp))
            .padding(6.dp)
            .border(1.dp, p.line, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PwLabel("凭 物 回 执", color = accent)
            Text(
                text = proof.serial,
                style = PwType.label.copy(fontFamily = FontFamily.Monospace),
                color = p.faint,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = proof.displayName, style = PwType.small, color = p.ink)
                Text(
                    text = "${proof.dateLabel} · ${proof.timeLabel} · ×${proof.displayCount}",
                    style = PwType.tiny.copy(fontFamily = FontFamily.Monospace),
                    color = p.faint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(text = "点开看整张 ›", style = PwType.tiny, color = p.amber)
        }
        // 照片就放在票根里，展开就能看见。
        if (proof.photos.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                proof.photos.take(3).forEach { key ->
                    PhotoThumb(
                        photoKey = key,
                        modifier = Modifier.weight(1f).height(72.dp),
                        radius = 2.dp,
                    )
                }
                repeat(3 - proof.photos.size.coerceAtMost(3)) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}



