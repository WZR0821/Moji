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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object MojiPalette {
    val Paper = Color(0xFFF4F0E6)
    val PaperDark = Color(0xFF171715)
    val Ink = Color(0xFF24211D)
    val InkLight = Color(0xFFE9E4D7)
    val SecondaryInk = Color(0xFF716B60)
    val Vermilion = Color(0xFFB43A2F)
}

@Composable
fun MojiTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(
            primary = MojiPalette.Vermilion,
            secondary = MojiPalette.Vermilion,
            secondaryContainer = Color(0xFF55302B),
            onSecondaryContainer = MojiPalette.InkLight,
            background = MojiPalette.PaperDark,
            surface = Color(0xFF211F1B),
            surfaceVariant = Color(0xFF292620),
            onBackground = MojiPalette.InkLight,
            onSurface = MojiPalette.InkLight,
        )
    } else {
        lightColorScheme(
            primary = MojiPalette.Vermilion,
            secondary = MojiPalette.Vermilion,
            secondaryContainer = Color(0xFFF0D8D0),
            onSecondaryContainer = MojiPalette.Ink,
            background = MojiPalette.Paper,
            surface = Color(0xFFF9F6EE),
            surfaceVariant = MojiPalette.Paper,
            onBackground = MojiPalette.Ink,
            onSurface = MojiPalette.Ink,
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
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
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp), RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)), RoundedCornerShape(3.dp))
            .padding(padding)
    ) { content() }
}
