package com.vibereading.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.pow

/** 「单章字符上限」的合法区间；输入越界在失焦/确认时夹取。 */
val CHAPTER_MAX_CHARS_RANGE: IntRange = 1000..200000

/** 「最大输出Token」的合法区间；输入越界在失焦/确认时夹取。 */
val MAX_OUTPUT_TOKENS_RANGE: IntRange = 256..65536

/** 翻译参数共用区间与步长（首页设置与阅读页翻译设置两处界面共用）。 */
val TEMPERATURE_RANGE: ClosedFloatingPointRange<Float> = 0f..2f
val TOP_P_RANGE: ClosedFloatingPointRange<Float> = 0f..1f
const val DECIMAL_PARAM_STEP: Float = 0.1f

/**
 * 步进式数值输入胶囊：「− / 数值 / ＋」，± 按步长增减并夹取到区间，
 * 中间数值可直接键入，区间内的值即时生效；
 * 失焦或 IME 确认时归一化：空串/非法回退为当前值，越界值夹取到边界。
 */
@Composable
fun StepperValueInput(
    value: Int,
    range: IntRange,
    step: Int,
    accentColor: Color,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fieldWidth: Dp = 68.dp
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val focusManager = LocalFocusManager.current

    StepperPill(
        modifier = modifier,
        text = text,
        onTextChange = { raw ->
            val digits = raw.filter(Char::isDigit)
                .take(range.endInclusive.toString().length)
            text = if (digits.isEmpty()) "" else digits.dropWhile { it == '0' }.ifEmpty { "0" }
            digits.toIntOrNull()?.let { if (it in range) onValueChange(it) }
        },
        fieldWidth = fieldWidth,
        accentColor = accentColor,
        canDecrement = value > range.first,
        canIncrement = value < range.last,
        onDecrement = { onValueChange((value - step).coerceIn(range)) },
        onIncrement = { onValueChange((value + step).coerceIn(range)) },
        onFocusLost = {
            val clamped = (text.toIntOrNull() ?: value).coerceIn(range)
            if (clamped != value) onValueChange(clamped)
            text = clamped.toString()
        },
        onDone = {
            val clamped = (text.toIntOrNull() ?: value).coerceIn(range)
            if (clamped != value) onValueChange(clamped)
            text = clamped.toString()
            focusManager.clearFocus()
        }
    )
}

/**
 * 小数版步进胶囊（采样温度 / Top P）：内部以字符串编辑，
 * 区间内即时生效，显示与归一化按 [decimals] 位小数格式化。
 */
@Composable
fun StepperValueInput(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    accentColor: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Int = 1,
    fieldWidth: Dp = 52.dp
) {
    var text by remember(value) { mutableStateOf(formatDecimal(value, decimals)) }
    val focusManager = LocalFocusManager.current

    fun commit(candidate: Float) {
        val clamped = candidate.coerceIn(range).roundTo(decimals)
        if (clamped != value) onValueChange(clamped)
        text = formatDecimal(clamped, decimals)
    }

    StepperPill(
        modifier = modifier,
        text = text,
        onTextChange = { raw ->
            val filtered = sanitizeDecimal(raw, decimals, formatDecimal(range.endInclusive, decimals).length)
            text = filtered
            filtered.toFloatOrNull()?.let { if (it in range) onValueChange(it.roundTo(decimals)) }
        },
        fieldWidth = fieldWidth,
        accentColor = accentColor,
        canDecrement = value > range.start + 1e-4f,
        canIncrement = value < range.endInclusive - 1e-4f,
        onDecrement = { commit(value - step) },
        onIncrement = { commit(value + step) },
        onFocusLost = {
            commit(text.toFloatOrNull() ?: value)
        },
        onDone = {
            commit(text.toFloatOrNull() ?: value)
            focusManager.clearFocus()
        }
    )
}

/** 胶囊容器：浅底全圆角，内含减号 / 数值 / 加号，紧凑高度约 28dp。 */
@Composable
private fun StepperPill(
    text: String,
    onTextChange: (String) -> Unit,
    fieldWidth: Dp,
    accentColor: Color,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onFocusLost: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton(Icons.Filled.Remove, "减少", canDecrement, onDecrement)
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .width(fieldWidth)
                .onFocusChanged { if (!it.isFocused) onFocusLost() },
            textStyle = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            cursorBrush = SolidColor(accentColor),
            singleLine = true
        )
        StepperButton(Icons.Filled.Add, "增加", canIncrement, onIncrement)
    }
}

/** 胶囊两端的步进按钮：到达边界时置灰。 */
@Composable
private fun StepperButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(26.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.35f)
        )
    }
}

private fun formatDecimal(value: Float, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private fun Float.roundTo(decimals: Int): Float {
    val factor = 10.0.pow(decimals)
    return (Math.round(this * factor) / factor).toFloat()
}

/** 仅保留数字与一个小数点，限制总长与小数位数。 */
private fun sanitizeDecimal(raw: String, decimals: Int, maxLen: Int): String {
    val builder = StringBuilder()
    var dotSeen = false
    for (c in raw) {
        when {
            c.isDigit() -> builder.append(c)
            c == '.' && !dotSeen && decimals > 0 -> { dotSeen = true; builder.append(c) }
        }
    }
    var s = if (builder.length > maxLen) builder.substring(0, maxLen) else builder.toString()
    val dotIndex = s.indexOf('.')
    if (dotIndex >= 0 && s.length - dotIndex - 1 > decimals) {
        s = s.substring(0, dotIndex + 1 + decimals)
    }
    return s
}
