package com.raydon.moji.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.raydon.moji.ui.MojiPalette

val mojiDestructive: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFF453A) else Color(0xFFFF3B30)

// UIKit's dynamic system colours, so grouped forms read exactly like the iOS Form.
private val isDarkScheme: Boolean @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f
val iosSecondaryLabel: Color @Composable get() = if (isDarkScheme) Color(0x99EBEBF5) else Color(0x993C3C43)
val iosTertiaryLabel: Color @Composable get() = if (isDarkScheme) Color(0x4DEBEBF5) else Color(0x4D3C3C43)
val iosSeparator: Color @Composable get() = if (isDarkScheme) Color(0x99545458) else Color(0x4A3C3C43)
/** tertiarySystemFill: the capsule behind compact date pickers and steppers. */
val iosFill: Color @Composable get() = if (isDarkScheme) Color(0x3D767680) else Color(0x1F767680)

/** One device pixel; Dp.Hairline strokes on a pixel boundary and smears into two lighter rows. */
val devicePixel: androidx.compose.ui.unit.Dp @Composable get() = (1f / androidx.compose.ui.platform.LocalDensity.current.density).dp

/** A 1px rule laid over the row edge instead of taking height, like UITableView separators. */
fun Modifier.overlayHairline(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
}

/** The trailing chevron of an iOS NavigationLink row (tertiary label, semibold). */
@Composable
fun FormChevron(modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color? = null) {
    val color = tint ?: iosTertiaryLabel
    androidx.compose.foundation.Canvas(modifier.size(8.dp, 13.dp)) {
        val d = density
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(1.2f * d, 1.2f * d); lineTo(6.6f * d, 6.5f * d); lineTo(1.2f * d, 11.8f * d) }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(1.9f * d, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
fun FormSection(
    title: String? = null,
    footer: String? = null,
    titleLineHeight: Int = 22,
    topPadding: Int = 0,
    /** iOS sets a Form's first header 27.7pt (glyph top to card) above its card, 8pt more than later headers. */
    firstHeader: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    // SwiftUI's inset-grouped Form is a distinct surface from an ink card.
    val formSurface = if (dark) Color(0xFF2C2C2E) else Color.White
    Column(Modifier.fillMaxWidth().padding(top = topPadding.dp)) {
        if (title != null) {
            Text(
                title,
                modifier = Modifier.padding(start = 40.dp, bottom = if (firstHeader) 13.7.dp else 5.5.dp),
                color = iosSecondaryLabel,
                fontSize = 13.sp,
                lineHeight = if (firstHeader) 17.sp else titleLineHeight.sp,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            color = formSurface,
            shape = RoundedCornerShape(13.dp),
        ) {
            MaterialTheme(colorScheme = scheme.copy(surface = formSurface, onSurface = if (dark) Color.White else Color.Black)) {
                Column(content = content)
            }
        }
        if (footer != null) {
            Text(
                footer,
                modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 5.5.dp),
                color = iosSecondaryLabel,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
fun FormSeparator(start: androidx.compose.ui.unit.Dp = 20.dp) {
    // Exactly one device pixel on the row's last pixel row; a zero-width hairline at a row
    // boundary is anti-aliased across two rows.
    Box(Modifier.overlayHairline().padding(start = start).fillMaxWidth().height(devicePixel).background(iosSeparator))
}

@Composable
fun FormRow(
    title: String,
    value: String? = null,
    icon: MojiIcon? = null,
    onClick: (() -> Unit)? = null,
    trailingChevron: Boolean = false,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // SwiftUI Label: tinted glyph at regular weight in a 28pt slot.
            MojiLineIcon(icon, if (icon.ordinal >= MojiIcon.TabChecklist.ordinal) 26.dp else 23.dp, MaterialTheme.colorScheme.primary, strokeWidth = 1.5f)
            Spacer(Modifier.width(12.dp))
        }
        Text(title, fontSize = 17.sp, color = titleColor, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, modifier = Modifier.widthIn(max = 180.dp).padding(start = 12.dp, top = 10.dp, bottom = 10.dp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = iosSecondaryLabel, fontSize = 17.sp)
        }
        if (trailingChevron) {
            Spacer(Modifier.width(12.dp))
            FormChevron()
        }
    }
}

@Composable
fun FormLabelledNumberField(title: String, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, modifier = Modifier.weight(1f))
        androidx.compose.foundation.text.BasicTextField(value, onChange, Modifier.width(100.dp), singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.End),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurface),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterEnd) { if (value.isEmpty()) Text("可选", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)); inner() } })
    }
}

@Composable
fun FormToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    minHeight: Int = 44,
    horizontalPadding: Int = 20,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight.dp).padding(horizontal = horizontalPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
        FormSwitch(checked, onCheckedChange, enabled)
    }
}

/** The iOS switch on its own, for rows that are not a plain FormToggle. */
@Composable
fun FormSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Box(Modifier.size(51.dp, 31.dp).background(if (checked) MaterialTheme.colorScheme.primary else if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF39393D) else Color(0xFFE9E9EB), CircleShape)
        .alpha(if (enabled) 1f else 0.4f)
        .toggleable(checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange)
        .padding(2.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(27.dp).background(Color.White, CircleShape))
    }
}

@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    minHeight: Int = 44,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    androidx.compose.foundation.text.BasicTextField(
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurface),
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).padding(start = 20.dp, end = 20.dp, top = if (singleLine) 10.dp else 10.5.dp, bottom = if (singleLine) 10.dp else 13.5.dp),
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        // A vertical SwiftUI TextField advances 22pt per line at 17pt.
        textStyle = if (singleLine) textStyle else textStyle.copy(lineHeight = 22.sp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isBlank()) Text(placeholder, style = if (singleLine) textStyle else textStyle.copy(lineHeight = 22.sp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f))
                inner()
            }
        },
    )
}

@Composable
fun FormSegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Int = 20,
    /** Options shown dimmed that cannot be picked. */
    disabled: Set<String> = emptySet(),
    onSelected: (String) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding.dp, vertical = 6.dp),
        color = if (dark) Color(0xFF3D3D42) else Color(0xFFEEEEF0),
        shape = RoundedCornerShape(9.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(2.dp)) {
            options.forEachIndexed { index, (raw, label) ->
                val active = raw == selected
                if (index > 0) {
                    val besideSelected = active || options[index - 1].first == selected
                    Box(Modifier.width(1.dp).height(16.dp).align(Alignment.CenterVertically).background(if (besideSelected) Color.Transparent else if (dark) Color(0x33EBEBF5) else Color(0x293C3C43)))
                }
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable(enabled = raw !in disabled) { onSelected(raw) },
                    color = if (active) if (dark) Color(0xFF727278) else Color.White else Color.Transparent,
                    shape = RoundedCornerShape(7.dp),
                    border = if (active) BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)) else null,
                    shadowElevation = if (active) 1.dp else 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            modifier = if (raw in disabled) Modifier.alpha(0.35f) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
fun FormStepper(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    enabled: Boolean = true,
    valueStyle: TextStyle? = null,
    minHeight: Int = 44,
    horizontalPadding: Int = 20,
) {
    Row(Modifier.fillMaxWidth().height(minHeight.dp).alpha(if (enabled) 1f else 0.38f).padding(horizontal = horizontalPadding.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(value, style = valueStyle ?: MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)), modifier = Modifier.padding(end = 12.dp))
        Surface(color = iosFill, shape = RoundedCornerShape(9.dp)) {
            Row(Modifier.height(32.dp)) {
                Box(Modifier.size(47.dp, 32.dp).clickable(enabled = enabled, onClick = onMinus), contentAlignment = Alignment.Center) { StepperGlyph(plus = false) }
                Box(Modifier.width(0.6.dp).height(20.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)).align(Alignment.CenterVertically))
                Box(Modifier.size(47.dp, 32.dp).clickable(enabled = enabled, onClick = onPlus), contentAlignment = Alignment.Center) { StepperGlyph(plus = true) }
            }
        }
    }
}

/** One date/time row, matching the iOS form while retaining native Android pickers. */
@Composable
fun FormDateRow(title: String, date: LocalDate, minHeight: Int = 44, horizontalPadding: Int = 20, onDate: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).padding(horizontal = horizontalPadding.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), fontSize = 17.sp, lineHeight = 22.sp,
            modifier = Modifier.background(iosFill, RoundedCornerShape(8.dp))
                .clickable(onClick = onDate).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
fun FormDateTimeRow(title: String, date: LocalDate, time: LocalTime, onDate: () -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 59.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 17.sp, modifier = Modifier.weight(1f))
        listOf(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) to onDate, time.format(DateTimeFormatter.ofPattern("HH:mm")) to onTime).forEach { (value, action) ->
            Text(value, fontSize = 17.sp, lineHeight = 22.sp, modifier = Modifier.background(iosFill, RoundedCornerShape(8.dp)).clickable(onClick = action).padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

@Composable
fun DurationPills(values: List<Int> = listOf(15, 25, 45, 60, 90), verticalPadding: Int = 5, unit: String = "分", onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = verticalPadding.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { minutes ->
            Surface(
                modifier = Modifier.clickable { onSelect(minutes) },
                // A bordered SwiftUI capsule tinted with planPrimary.
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkScheme) 0.25f else 0.18f),
                shape = CircleShape,
            ) {
                Text("$minutes $unit", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 17.sp, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
fun FormChoiceRow(
    choices: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (raw, label) ->
            val active = raw == selected
            Surface(
                modifier = Modifier.weight(1f).height(38.dp).clickable { onSelected(raw) },
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f),
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 0f else 0.10f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, textAlign = TextAlign.Center, color = if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                }
            }
        }
    }
}

/** UIStepper's minus/plus: a 13.7pt glyph with a 2pt stroke. */
@Composable
private fun StepperGlyph(plus: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(ink, androidx.compose.ui.geometry.Offset(0.3f * stroke, size.height / 2), androidx.compose.ui.geometry.Offset(size.width - 0.3f * stroke, size.height / 2), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        if (plus) drawLine(ink, androidx.compose.ui.geometry.Offset(size.width / 2, 0.3f * stroke), androidx.compose.ui.geometry.Offset(size.width / 2, size.height - 0.3f * stroke), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
