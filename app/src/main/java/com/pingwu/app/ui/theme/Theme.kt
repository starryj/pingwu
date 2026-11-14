package com.pingwu.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** C「值守」两套皮肤：深墨（默认）/ 纸白。 */
enum class ThemeMode { DARK, PAPER }

@Immutable
data class PwPalette(
    val bg: Color,
    val bgGlow: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val inkSoft: Color,
    val faint: Color,
    val line: Color,
    val lineSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val mint: Color,
    val red: Color,
    val onAmber: Color,
    val isDark: Boolean,
)

// 深墨这套是按「晚上不开灯也看得清」调的：底压住、字提亮、分隔线可见。
// 早先那版在低亮度屏幕上要人把亮度拉到最大才认得出字，太费眼睛。
val DarkPalette = PwPalette(
    bg = Color(0xFF0B1216),
    bgGlow = Color(0xFF17262F),
    surface = Color(0xFF16222A),
    surfaceAlt = Color(0xFF101B22),
    ink = Color(0xFFF5F2E8),
    inkSoft = Color(0xFFD2CDBE),
    faint = Color(0xFFA6A192),
    line = Color(0xFF33454F),
    lineSoft = Color(0xFF26343C),
    amber = Color(0xFFF5BE6B),
    amberSoft = Color(0x3DF5BE6B),
    mint = Color(0xFF6FE3B6),
    red = Color(0xFFF4786A),
    onAmber = Color(0xFF1A1206),
    isDark = true,
)

val PaperPalette = PwPalette(
    bg = Color(0xFFE8E4D7),
    bgGlow = Color(0xFFF1ECDE),
    surface = Color(0xFFF5F0E3),
    surfaceAlt = Color(0xFFEDE7D8),
    ink = Color(0xFF17150F),
    inkSoft = Color(0xFF4B473C),
    faint = Color(0xFF8B8678),
    line = Color(0xFFCFC8B5),
    lineSoft = Color(0xFFDDD7C7),
    amber = Color(0xFF8A5A14),
    amberSoft = Color(0x268A5A14),
    mint = Color(0xFF2F6F57),
    red = Color(0xFFB23A2E),
    onAmber = Color(0xFFFFF6E4),
    isDark = false,
)

val LocalPwPalette = staticCompositionLocalOf { DarkPalette }

object PwType {
    val display = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val title = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val small = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp)
    val tiny = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp)
    val mono = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.4.sp)
    val num = TextStyle(fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
}

@Composable
fun PingWuTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val p = if (mode == ThemeMode.PAPER) PaperPalette else DarkPalette
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.amber,
            onPrimary = p.onAmber,
            background = p.bg,
            onBackground = p.ink,
            surface = p.surface,
            onSurface = p.ink,
        )
    } else {
        lightColorScheme(
            primary = p.amber,
            onPrimary = p.onAmber,
            background = p.bg,
            onBackground = p.ink,
            surface = p.surface,
            onSurface = p.ink,
        )
    }
    val typography = Typography(
        bodyLarge = PwType.body,
        bodyMedium = PwType.small,
        titleLarge = PwType.title,
    )
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalPwPalette provides p, content = content)
    }
}
