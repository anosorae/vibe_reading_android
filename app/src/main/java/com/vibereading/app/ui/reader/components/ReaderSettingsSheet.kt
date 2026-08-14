package com.vibereading.app.ui.reader.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.theme.ReaderBgPresets
import com.vibereading.app.ui.theme.VibeColors

/**
 * 阅读设置面板（对齐 Legado ReadStyleDialog 的信息密度）：
 * 背景色一行 / 翻页类型一行 / 字体（系统字体 + 自定义导入）/ 常用滑块 / 排版滑块与开关，
 * 所有改动立即生效并经 onUpdate 持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReadingSettings,
    accentColor: Color,
    onUpdate: (ReadingSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val bgPresets = listOf(
        ReaderBgPresets.WarmCream,
        ReaderBgPresets.DarkCream,
        ReaderBgPresets.GreenTint,
        ReaderBgPresets.GrayCream,
        ReaderBgPresets.DarkNight
    )
    val flipModes = listOf(
        ReadingSettings.FLIP_SCROLL to "上下",
        ReadingSettings.FLIP_PAGER to "平移",
        ReadingSettings.FLIP_COVER to "覆盖",
        ReadingSettings.FLIP_NO_ANIM to "无动画",
        ReadingSettings.FLIP_SIMULATION to "仿真"
    )
    // 自定义字体：SAF 选择 TTF/OTF，持久化 content:// URI（跨重启恢复）
    val context = LocalContext.current
    val fontLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // 选择器未授予持久读权限：本次会话仍可用，但不持久化
        }
        onUpdate(settings.copy(customFontUri = uri.toString()))
    }
    fun pickCustomFont() {
        fontLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "font/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "font/ttf", "font/otf",
                    "application/x-font-ttf", "application/x-font-opentype",
                    "application/octet-stream"
                ))
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        scrimColor = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // ── 背景色（一行） ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("背景", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                bgPresets.forEachIndexed { index, color ->
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (settings.bgColorIndex == index) {
                                    Modifier.border(2.dp, accentColor, CircleShape)
                                } else {
                                    Modifier.border(1.dp, VibeColors.Sand, CircleShape)
                                }
                            )
                            .clickable { onUpdate(settings.copy(bgColorIndex = index)) }
                    )
                }
            }

            // ── 翻页类型（一行 5 项） ──
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                flipModes.forEach { (key, label) ->
                    val selected = settings.pageFlipMode == key
                    OutlinedButton(
                        onClick = { onUpdate(settings.copy(pageFlipMode = key)) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) accentColor.copy(alpha = 0.1f) else Color.Transparent
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(label, fontSize = 12.sp, color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── 字体（系统字体 + 自定义导入） ──
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val sysSelected = settings.customFontUri == null
                OutlinedButton(
                    onClick = { onUpdate(settings.copy(customFontUri = null)) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (sysSelected) accentColor.copy(alpha = 0.1f) else Color.Transparent
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("系统字体", fontSize = 12.sp, color = if (sysSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (settings.customFontUri != null) {
                    OutlinedButton(
                        onClick = ::pickCustomFont,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Text("更换自定义字体", fontSize = 12.sp, color = accentColor, maxLines = 1)
                    }
                    TextButton(onClick = { onUpdate(settings.copy(customFontUri = null)) }) {
                        Text("清除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    OutlinedButton(
                        onClick = ::pickCustomFont,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Text("导入字体…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── 常用滑块（字号 / 行间距 / 段落间距） ──
            SettingSliderRow(
                title = "字号",
                value = settings.fontSize.toFloat(),
                display = "${settings.fontSize}sp",
                range = 14f..24f,
                accentColor = accentColor
            ) { onUpdate(settings.copy(fontSize = it.toInt())) }

            SettingSliderRow(
                title = "行间距",
                value = settings.lineSpacing.toFloat(),
                display = "${settings.lineSpacing}",
                range = 0f..24f,
                accentColor = accentColor
            ) { onUpdate(settings.copy(lineSpacing = it.toInt())) }

            SettingSliderRow(
                title = "段落间距",
                value = settings.paragraphSpacing.toFloat(),
                display = "${settings.paragraphSpacing}",
                range = 4f..32f,
                accentColor = accentColor
            ) { onUpdate(settings.copy(paragraphSpacing = it.toInt())) }

            // ── 排版滑块（左右边距 / 上下边距 / 字间距 / 首行缩进） ──
            SettingSliderRow(
                title = "左右边距",
                value = settings.paddingH.toFloat(),
                display = "${settings.paddingH}dp",
                range = 4f..100f,
                accentColor = accentColor
            ) { onUpdate(settings.copy(paddingH = it.toInt())) }

            SettingSliderRow(
                title = "上下边距",
                value = settings.paddingV.toFloat(),
                display = "${settings.paddingV}dp",
                range = 4f..100f,
                accentColor = accentColor
            ) { onUpdate(settings.copy(paddingV = it.toInt())) }

            SettingSliderRow(
                title = "字间距",
                value = settings.letterSpacing,
                display = String.format("%.2f", settings.letterSpacing),
                range = -0.5f..0.5f,
                accentColor = accentColor,
                step = 0.01f
            ) { onUpdate(settings.copy(letterSpacing = it)) }

            SettingSliderRow(
                title = "首行缩进",
                value = settings.indentEm,
                display = String.format("%.1f em", settings.indentEm),
                range = 0f..4f,
                accentColor = accentColor,
                step = 0.5f
            ) { onUpdate(settings.copy(indentEm = it)) }

            // ── 两端对齐 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("两端对齐", style = MaterialTheme.typography.bodyMedium)
                    Text("英文排版时调整词间距", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.justify,
                    onCheckedChange = { onUpdate(settings.copy(justify = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                )
            }

            // ── 单手模式（分页模式生效） ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("单手模式", style = MaterialTheme.typography.bodyMedium)
                    Text("分页模式下点击左右两侧翻下一页", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.oneHandMode,
                    onCheckedChange = { onUpdate(settings.copy(oneHandMode = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                )
            }
        }
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    value: Float,
    display: String,
    range: ClosedFloatingPointRange<Float>,
    accentColor: Color,
    step: Float = 0f,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(display, fontWeight = FontWeight.SemiBold, color = accentColor, fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = if (step > 0f) ((range.endInclusive - range.start) / step).toInt() - 1 else 0,
            colors = SliderDefaults.colors(activeTrackColor = accentColor),
            modifier = Modifier.height(28.dp)
        )
    }
}
