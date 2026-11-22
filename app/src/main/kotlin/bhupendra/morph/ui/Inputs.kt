package bhupendra.morph.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bhupendra.morph.core.surface.Block
import bhupendra.morph.core.surface.InputKind
import bhupendra.morph.core.surface.Surface
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Live values of a question card's inputs, keyed by input id. */
class FormState(val values: SnapshotStateMap<String, JsonElement>) {
    fun toJson() = JsonObject(values.toMap())

    fun missingRequired(inputs: List<Block.Input>): Boolean = inputs.any { i ->
        i.required && when (val v = values[i.id]) {
            null -> true
            is JsonPrimitive -> v.contentOrNull.isNullOrBlank()
            is JsonArray -> v.isEmpty()
            else -> false
        }
    }
}

private fun num(f: Float): JsonPrimitive = if (f == f.roundToInt().toFloat()) JsonPrimitive(f.roundToInt()) else JsonPrimitive(f)

private fun initialValue(i: Block.Input): JsonElement? = when (i.kind) {
    InputKind.Text, InputKind.LongText, InputKind.Choice -> i.initial?.let(::JsonPrimitive)
        ?: if (i.kind == InputKind.Choice) i.options.firstOrNull()?.let(::JsonPrimitive) else JsonPrimitive("")
    InputKind.Slider, InputKind.Stepper -> num(i.initial?.toFloatOrNull() ?: i.min ?: 0f)
    InputKind.Rating -> num(i.initial?.toFloatOrNull() ?: 0f)
    InputKind.Toggle -> JsonPrimitive(i.initial?.toBooleanStrictOrNull() ?: false)
    InputKind.MultiChoice -> JsonArray(i.initial?.split('|')?.filter { it in i.options }?.map(::JsonPrimitive).orEmpty())
    InputKind.Date -> i.initial?.let(::JsonPrimitive)
    InputKind.Time -> i.initial?.let(::JsonPrimitive)
}

@Composable
fun rememberFormState(surface: Surface): FormState = remember(surface.id) {
    val map = mutableStateMapOf<String, JsonElement>()
    surface.blocks.filterIsInstance<Block.Input>().forEach { i -> initialValue(i)?.let { map[i.id] = it } }
    FormState(map)
}

@Composable
fun InputView(i: Block.Input, state: FormState, colors: ToneColors, enabled: Boolean) {
    val haptics = LocalHapticFeedback.current
    val v = state.values[i.id]
    fun set(e: JsonElement) { state.values[i.id] = e }
    val labelStyle = MaterialTheme.typography.titleSmall
    val fieldShape = RoundedCornerShape(20.dp)

    when (i.kind) {
        InputKind.Text, InputKind.LongText -> OutlinedTextField(
            value = (v as? JsonPrimitive)?.contentOrNull.orEmpty(),
            onValueChange = { set(JsonPrimitive(it)) },
            label = { Text(i.label) },
            placeholder = i.placeholder?.let { { Text(it) } },
            singleLine = i.kind == InputKind.Text,
            minLines = if (i.kind == InputKind.LongText) 3 else 1,
            enabled = enabled, shape = fieldShape,
            modifier = Modifier.fillMaxWidth(),
        )

        InputKind.Slider -> Column {
            val min = i.min ?: 0f
            val max = (i.max ?: 100f).coerceAtLeast(min + 1f)
            val value = (v as? JsonPrimitive)?.floatOrNull ?: min
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(i.label, Modifier.weight(1f), style = labelStyle, color = colors.content)
                Text(formatNumber(value) + (i.unit?.let { " $it" } ?: ""), style = MaterialTheme.typography.titleMediumEmphasized, color = colors.accent)
            }
            val steps = i.step?.takeIf { it > 0 }?.let { ((max - min) / it).roundToInt() - 1 }?.coerceIn(0, 200) ?: 0
            Slider(
                value = value.coerceIn(min, max),
                onValueChange = {
                    val snapped = i.step?.takeIf { s -> s > 0 }?.let { s -> min + ((it - min) / s).roundToInt() * s } ?: it
                    if (snapped != value) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    set(num(snapped))
                },
                valueRange = min..max, steps = steps, enabled = enabled,
            )
        }

        InputKind.Toggle -> Row(
            Modifier.fillMaxWidth().clickable(enabled) { set(JsonPrimitive(!((v as? JsonPrimitive)?.booleanOrNull ?: false))) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(i.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.content)
            Switch((v as? JsonPrimitive)?.booleanOrNull ?: false, { set(JsonPrimitive(it)) }, enabled = enabled)
        }

        InputKind.Choice -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(i.label, style = labelStyle, color = colors.content)
            val selected = (v as? JsonPrimitive)?.contentOrNull
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                i.options.forEach { o ->
                    ToggleButton(
                        checked = o == selected,
                        onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); set(JsonPrimitive(o)) },
                        enabled = enabled,
                    ) { Text(o) }
                }
            }
        }

        InputKind.MultiChoice -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(i.label, style = labelStyle, color = colors.content)
            val chosen = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                i.options.forEach { o ->
                    FilterChip(
                        selected = o in chosen, enabled = enabled,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            val next = if (o in chosen) chosen - o else chosen + o
                            set(JsonArray(i.options.filter { it in next }.map(::JsonPrimitive)))
                        },
                        label = { Text(o) },
                    )
                }
            }
        }

        InputKind.Date -> {
            var open by remember { mutableStateOf(false) }
            val current = (v as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            PickerButton(i.label, current?.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) ?: "Pick a date", Icons.Rounded.CalendarMonth, enabled, colors.content) { open = true }
            if (open) {
                val dp = rememberDatePickerState(initialSelectedDateMillis = (current ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                DatePickerDialog(
                    onDismissRequest = { open = false },
                    confirmButton = {
                        TextButton(onClick = {
                            dp.selectedDateMillis?.let { set(JsonPrimitive(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())) }
                            open = false
                        }) { Text("Set") }
                    },
                    dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
                ) { DatePicker(dp) }
            }
        }

        InputKind.Time -> {
            var open by remember { mutableStateOf(false) }
            val current = (v as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            PickerButton(i.label, current?.format(DateTimeFormatter.ofPattern("h:mm a")) ?: "Pick a time", Icons.Rounded.Schedule, enabled, colors.content) { open = true }
            if (open) {
                val now = current ?: LocalTime.now().withMinute(0)
                val tp = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute)
                AlertDialog(
                    onDismissRequest = { open = false },
                    confirmButton = {
                        TextButton(onClick = { set(JsonPrimitive("%02d:%02d".format(tp.hour, tp.minute))); open = false }) { Text("Set") }
                    },
                    dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
                    text = { TimePicker(tp) },
                )
            }
        }

        InputKind.Stepper -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(i.label, Modifier.weight(1f), style = labelStyle, color = colors.content)
            val step = i.step?.takeIf { it > 0 } ?: 1f
            val value = (v as? JsonPrimitive)?.floatOrNull ?: (i.min ?: 0f)
            fun bump(d: Float) {
                val next = (value + d).coerceIn(i.min ?: -Float.MAX_VALUE, i.max ?: Float.MAX_VALUE)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); set(num(next))
            }
            FilledTonalIconButton(onClick = { bump(-step) }, enabled = enabled, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Remove, "Less") }
            Text(formatNumber(value) + (i.unit?.let { " $it" } ?: ""), Modifier.widthIn(min = 56.dp).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleLargeEmphasized, color = colors.content, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            FilledTonalIconButton(onClick = { bump(step) }, enabled = enabled, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "More") }
        }

        InputKind.Rating -> Column {
            Text(i.label, style = labelStyle, color = colors.content)
            val value = ((v as? JsonPrimitive)?.floatOrNull ?: 0f).roundToInt()
            Row {
                (1..5).forEach { n ->
                    Text(if (n <= value) "★" else "☆", fontSize = 32.sp, color = colors.accent,
                        modifier = Modifier.clickable(enabled) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); set(num(n.toFloat())) }.padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun PickerButton(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean,
                         labelColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = labelColor)
        Button(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.filledTonalButtonColors()) {
            Icon(icon, null, Modifier.padding(end = 8.dp)); Text(value)
        }
    }
}

private fun formatNumber(f: Float): String =
    if (f == f.roundToInt().toFloat()) "%,d".format(f.roundToInt()) else "%.1f".format(f)
