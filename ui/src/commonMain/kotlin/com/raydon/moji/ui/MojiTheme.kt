package com.raydon.moji.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MojiPalette {
    // Kept byte-for-byte with Shared/PlanColors.swift so both clients render
    // the same paper, graphite and vermilion under an sRGB display profile.
    val Paper = Color(0xFFF7F4ED)
    val PaperDark = Color(0xFF11100E)
    val Card = Color(0xFFFFFEFB)
    val CardDark = Color(0xFF221F1D)
    val Ink = Color(0xFF242321)
    val InkLight = Color(0xFFEDE8DB)
    val SecondaryInk = Color(0xFF6D6962)
    val SecondaryInkDark = Color(0xFFABA394)
    val Vermilion = Color(0xFF8F332B)
    val VermilionDark = Color(0xFFB86154)
}

@Composable
fun MojiTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(
            primary = MojiPalette.Vermilion,
            secondary = MojiPalette.SecondaryInkDark,
            secondaryContainer = Color(0xFF55302B),
            onSecondaryContainer = MojiPalette.InkLight,
            background = MojiPalette.PaperDark,
            surface = MojiPalette.CardDark,
            surfaceVariant = MojiPalette.PaperDark,
            onBackground = MojiPalette.InkLight,
            onSurface = MojiPalette.InkLight,
            outline = MojiPalette.InkLight.copy(alpha = 0.12f),
        )
    } else {
        lightColorScheme(
            primary = MojiPalette.Vermilion,
            secondary = MojiPalette.SecondaryInk,
            secondaryContainer = Color(0xFFE9E6E0),
            onSecondaryContainer = MojiPalette.Ink,
            background = MojiPalette.Paper,
            surface = MojiPalette.Card,
            surfaceVariant = MojiPalette.Paper,
            onBackground = MojiPalette.Ink,
            onSurface = MojiPalette.Ink,
            outline = MojiPalette.Ink.copy(alpha = 0.12f),
        )
    }
    val typography = Typography(
        displayMedium = Typography().displayMedium.copy(fontSize = 50.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light),
        headlineLarge = Typography().headlineLarge.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
        headlineMedium = Typography().headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
        titleLarge = Typography().titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        titleMedium = Typography().titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = Typography().bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = Typography().bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = Typography().bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = Typography().labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = Typography().labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = Typography().labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
            .border(BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)), RoundedCornerShape(14.dp))
            .padding(padding)
    ) { content() }
}
