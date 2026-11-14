package com.pingwu.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pingwu.app.R
import com.pingwu.app.data.core.drawableFor
import com.pingwu.app.data.mock.ItemCategory
import com.pingwu.app.ui.theme.LocalPwPalette
import com.pingwu.app.ui.theme.PwType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PwLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val p = LocalPwPalette.current
    Text(
        text = text,
        style = PwType.label,
        color = color ?: p.faint,
        modifier = modifier,
    )
}

@Composable
fun TicketCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPwPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(p.surface, p.surfaceAlt)))
            .border(1.dp, p.line, RoundedCornerShape(18.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun DashedDivider(modifier: Modifier = Modifier, color: Color? = null) {
    val p = LocalPwPalette.current
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color ?: p.line,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
        )
    }
}

@Composable
fun StatusChip(text: String, tone: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = 0.14f))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = PwType.label.copy(letterSpacing = 0.6.sp), color = tone)
    }
}

/** 物品图标：药品 / 钥匙 / 证件 / 其他，纯 Canvas，不依赖图片。 */
@Composable
fun ItemGlyph(category: ItemCategory, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val p = LocalPwPalette.current
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(p.surfaceAlt)
            .border(1.dp, p.line, RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.62f)) {
            val w = this.size.width
            val h = this.size.height
            val line = p.inkSoft
            when (category) {
                ItemCategory.MEDICINE -> {
                    drawOval(
                        color = p.ink.copy(alpha = 0.72f),
                        topLeft = Offset(w * 0.07f, h * 0.28f),
                        size = Size(w * 0.86f, h * 0.44f),
                    )
                    drawLine(
                        color = p.surfaceAlt,
                        start = Offset(w * 0.5f, h * 0.33f),
                        end = Offset(w * 0.5f, h * 0.67f),
                        strokeWidth = w * 0.06f,
                    )
                }
                ItemCategory.KEY -> {
                    drawCircle(
                        color = p.amber,
                        radius = w * 0.19f,
                        center = Offset(w * 0.3f, h * 0.5f),
                        style = Stroke(width = w * 0.085f),
                    )
                    drawLine(
                        color = p.amber,
                        start = Offset(w * 0.46f, h * 0.5f),
                        end = Offset(w * 0.9f, h * 0.5f),
                        strokeWidth = w * 0.085f,
                    )
                    drawLine(
                        color = p.amber,
                        start = Offset(w * 0.74f, h * 0.5f),
                        end = Offset(w * 0.74f, h * 0.72f),
                        strokeWidth = w * 0.075f,
                    )
                    drawLine(
                        color = p.amber,
                        start = Offset(w * 0.88f, h * 0.5f),
                        end = Offset(w * 0.88f, h * 0.68f),
                        strokeWidth = w * 0.075f,
                    )
                }
                ItemCategory.DOC -> {
                    drawRoundRect(
                        color = line,
                        topLeft = Offset(w * 0.16f, h * 0.16f),
                        size = Size(w * 0.68f, h * 0.68f),
                        cornerRadius = CornerRadius(w * 0.1f),
                        style = Stroke(width = w * 0.06f),
                    )
                    drawLine(
                        color = line,
                        start = Offset(w * 0.3f, h * 0.38f),
                        end = Offset(w * 0.7f, h * 0.38f),
                        strokeWidth = w * 0.05f,
                    )
                    drawLine(
                        color = line,
                        start = Offset(w * 0.3f, h * 0.58f),
                        end = Offset(w * 0.56f, h * 0.58f),
                        strokeWidth = w * 0.05f,
                    )
                }
                ItemCategory.OTHER -> {
                    drawRoundRect(
                        color = line,
                        topLeft = Offset(w * 0.14f, h * 0.14f),
                        size = Size(w * 0.72f, h * 0.72f),
                        cornerRadius = CornerRadius(w * 0.14f),
                        style = Stroke(width = w * 0.06f),
                    )
                    drawCircle(color = p.amber, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.5f))
                }
            }
        }
    }
}

@Composable
fun PhotoThumb(photoKey: String, modifier: Modifier = Modifier, radius: Dp = 10.dp) {
    val p = LocalPwPalette.current
    PhotoImage(
        photoKey = photoKey,
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .border(1.dp, p.lineSoft, RoundedCornerShape(radius)),
        contentScale = ContentScale.Crop,
        maxEdge = THUMB_EDGE,
    )
}

/**
 * 凭物照片：文件路径读本机文件，`pw_*` 读工程内资源（种子数据兜底）。
 *
 * 相册里的原图动辄 3000×4000，主线程解码会让整页卡住（真机上表现为点了没反应），
 * 所以这里一律异步解码 + 内存 LRU 缓存，先用底色占位，解好再淡入。
 */
@Composable
fun PhotoImage(
    photoKey: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxEdge: Int = FULL_EDGE,
) {
    val p = LocalPwPalette.current
    if (!photoKey.startsWith("/")) {
        Image(
            painter = painterResource(id = drawableFor(photoKey)),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
        return
    }

    val cacheKey = "$photoKey@$maxEdge"
    var bitmap by remember(cacheKey) { mutableStateOf(PhotoCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) { decodeSampled(photoKey, maxEdge) }
            if (loaded != null) {
                PhotoCache.put(cacheKey, loaded)
                bitmap = loaded
            }
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.background(p.surfaceAlt))
    }
}

private const val THUMB_EDGE = 480
private const val FULL_EDGE = 1080

/** 解码后的缩略图缓存；上限取可用堆的 1/6，最多 48MB。 */
private object PhotoCache {
    private val maxBytes: Int =
        (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(48L * 1024 * 1024).toInt()

    private val lru = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = lru.get(key)

    fun put(key: String, bitmap: Bitmap) {
        lru.put(key, bitmap)
    }
}

private fun decodeSampled(path: String, maxEdge: Int = FULL_EDGE): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, opts)
}.getOrNull()

@Composable
fun SectionHead(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = PwType.title, color = p.ink)
        if (action != null) {
            Text(
                text = action,
                style = PwType.small,
                color = p.amber,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = onAction != null) { onAction?.invoke() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun BackBar(title: String, onBack: () -> Unit) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, p.line, RoundedCornerShape(10.dp))
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(16.dp)) {
                val stroke = Stroke(width = size.width * 0.12f)
                drawLine(
                    p.ink,
                    Offset(size.width * 0.7f, size.height * 0.15f),
                    Offset(size.width * 0.3f, size.height * 0.5f),
                    strokeWidth = size.width * 0.12f,
                )
                drawLine(
                    p.ink,
                    Offset(size.width * 0.3f, size.height * 0.5f),
                    Offset(size.width * 0.7f, size.height * 0.85f),
                    strokeWidth = size.width * 0.12f,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text = title, style = PwType.title, color = p.ink)
    }
}

/** 一行「标签 + 值」的票据信息行。 */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = PwType.small, color = p.faint)
        Text(text = value, style = PwType.small.copy(fontWeight = FontWeight.Medium), color = valueColor ?: p.ink)
    }
}

@Composable
fun MiniBar(active: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPwPalette.current
    Box(
        modifier = modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) p.amber else p.lineSoft),
    )
}

/** 顶部标题栏：左边 wordmark，右边日期/头像。 */
@Composable
fun TopBar(right: @Composable () -> Unit = {}) {
    val p = LocalPwPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "凭 物",
            style = PwType.label.copy(fontSize = 12.sp, letterSpacing = 3.sp),
            color = p.amber,
        )
        right()
    }
}

/** 头像：一个字，或者用户自己从相册选的一张照片。点一下去「我的」里换。 */
@Composable
fun Avatar(
    text: String = "溪",
    photoKey: String? = null,
    size: Dp = 32.dp,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalPwPalette.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(999.dp))
            .background(p.amberSoft)
            .border(1.dp, p.amber.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!photoKey.isNullOrBlank()) {
            PhotoImage(photoKey, Modifier.fillMaxSize(), ContentScale.Crop, maxEdge = 512)
        } else {
            // 自己写两个字时缩小一档，不然会顶出圆圈。
            val style = if (text.length >= 2) PwType.tiny else PwType.small
            Text(text = text, style = style, color = p.amber)
        }
    }
}
