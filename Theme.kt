package com.footballanalyzer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgColor = Color(0xFF0A0E1A)
val BgCard = Color(0xFF121929)
val BgCardDark = Color(0xFF0D1320)
val AccentGreen = Color(0xFF10B981)
val AccentDim = Color(0xFF0D2B22)
val BorderColor = Color(0xFF1E2D45)
val TextPrimary = Color(0xFFE2E8F0)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)
val HomeColor = Color(0xFF3B82F6)
val BlueDim = Color(0xFF0D1E3D)
val AwayColor = Color(0xFFF59E0B)
val PurpleLight = Color(0xFFA78BFA)
val PurpleDim = Color(0xFF2D1B4E)
val DrawColor = Color(0xFF94A3B8)
val LiveColor = Color(0xFFEF4444)
val LiveDim = Color(0xFF3B0F0F)

private val DarkColorScheme = darkColorScheme(
    primary = AccentGreen,
    background = BgColor,
    surface = BgCard,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun FootballAnalyzerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
