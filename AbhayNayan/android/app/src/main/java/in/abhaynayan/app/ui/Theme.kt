package `in`.abhaynayan.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Navy = Color(0xFF123642)
    val Teal = Color(0xFF0A6F6B)
    val TealSoft = Color(0xFFE3F2EF)
    val Canvas = Color(0xFFF4F8F8)
    val Surface = Color(0xFFFFFFFF)
    val Body = Color(0xFF3E565C)
    val Muted = Color(0xFF5A6F74)
    val Line = Color(0xFFD5E2E3)
    val AmberSurface = Color(0xFFFFF4DF)
    val AmberText = Color(0xFF6A4B16)
    val AmberLine = Color(0xFFE9D7AE)
    val RedSurface = Color(0xFFFDECEA)
    val RedText = Color(0xFF8A1F17)
    val GreenText = Color(0xFF1C6B3A)
    val Purple = Color(0xFF5B3E8E)
    val PurpleSurface = Color(0xFFF0EAFA)
}

private val Scheme = lightColorScheme(
    primary = Palette.Teal,
    onPrimary = Color.White,
    primaryContainer = Palette.TealSoft,
    onPrimaryContainer = Palette.Navy,
    secondary = Palette.Navy,
    onSecondary = Color.White,
    background = Palette.Canvas,
    onBackground = Palette.Navy,
    surface = Palette.Surface,
    onSurface = Palette.Navy,
    onSurfaceVariant = Palette.Body,
    outline = Palette.Line,
    error = Palette.RedText,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .4.sp),
)

@Composable
fun AbhayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
