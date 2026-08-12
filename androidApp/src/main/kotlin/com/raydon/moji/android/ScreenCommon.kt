package com.raydon.moji.android

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.raydon.moji.ui.MojiPalette
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text("墨", color = MojiPalette.Vermilion, fontSize = 22.sp, fontFamily = FontFamily(Font(R.font.jfzskseal)))
        }
        Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun rememberDatePicker(onDate: (LocalDate) -> Unit): (LocalDate) -> Unit {
    val context = LocalContext.current
    return { initial ->
        DatePickerDialog(
            context,
            { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }
}

@Composable
fun rememberTimePicker(onTime: (LocalTime) -> Unit): (LocalTime) -> Unit {
    val context = LocalContext.current
    return { initial ->
        TimePickerDialog(
            context,
            { _, hour, minute -> onTime(LocalTime.of(hour, minute)) },
            initial.hour,
            initial.minute,
            true,
        ).show()
    }
}

fun categoryName(raw: String): String = when (raw) {
    "study" -> "学习"
    "work" -> "工作"
    else -> raw.removePrefix("custom:").ifBlank { "其他" }
}

fun phaseName(raw: String): String = when (raw) {
    "shortBreak" -> "短休息"
    "longBreak" -> "长休息"
    else -> "专注"
}
