package com.worldexplorer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

object Brand {
    val Night = Color(0xFF0B1320)
    val NightSurface = Color(0xFF131D2E)
    val NightSurfaceHigh = Color(0xFF1B2840)
    val Teal = Color(0xFF2EC4B6)
    val TealDark = Color(0xFF13887E)
    val Amber = Color(0xFFFFB703)
    val Red = Color(0xFFE5484D)
    val Mist = Color(0xFFE8EEF5)
}

private val Dark = darkColorScheme(
    primary = Brand.Teal,
    onPrimary = Color(0xFF00201D),
    primaryContainer = Color(0xFF0E4A45),
    onPrimaryContainer = Color(0xFFB4F1EA),
    secondary = Brand.Amber,
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFF1E4A47),
    onSecondaryContainer = Color(0xFFCFF5EF),
    tertiary = Color(0xFF9DB4FF),
    background = Brand.Night,
    onBackground = Brand.Mist,
    surface = Brand.NightSurface,
    onSurface = Brand.Mist,
    surfaceVariant = Brand.NightSurfaceHigh,
    onSurfaceVariant = Color(0xFFB7C3D3),
    surfaceContainer = Brand.NightSurface,
    surfaceContainerHigh = Brand.NightSurfaceHigh,
    surfaceContainerHighest = Color(0xFF23324D),
    outline = Color(0xFF4A5A73),
    outlineVariant = Color(0xFF2C3A52),
    error = Brand.Red,
)

private val Light = lightColorScheme(
    primary = Brand.TealDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F1EA),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFCDEFEA),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF3F5BB8),
    background = Color(0xFFF5F8FB),
    onBackground = Color(0xFF111A27),
    surface = Color.White,
    onSurface = Color(0xFF111A27),
    surfaceVariant = Color(0xFFE6EDF4),
    onSurfaceVariant = Color(0xFF465467),
    surfaceContainer = Color(0xFFF0F4F8),
    surfaceContainerHigh = Color(0xFFE9EFF5),
    surfaceContainerHighest = Color(0xFFE1E8F0),
    error = Color(0xFFC62828),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun WorldExplorerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes, content = content)
}
