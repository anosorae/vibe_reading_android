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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.components.StepperValueInput
import com.vibereading.app.ui.reader.pagination.ReaderFontInfo
import com.vibereading.app.ui.reader.pagination.ReaderFonts
import com.vibereading.app.ui.theme.ReaderBgPresets
import com.vibereading.app.ui.theme.VibeColors
import kotlinx.coroutines.launch

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
        ReadingSettings.FLIP_PAGER to "平移",
        ReadingSettings.FLIP_SIMULATION to "仿真",
        ReadingSettings.FLIP_COVER to "覆盖",
        ReadingSettings.FLIP_NO_ANIM to "无动画",
        ReadingSettings.FLIP_SCROLL to "上下"
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
        onUpdate(settings.copy(customFontUri = uri.toString(), fontId = null))
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
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

            // ── 字号（胶囊步进） + 字体选择（同一行） ──
            val currentFontName = when {
                settings.customFontUri != null -> "自定义字体"
                settings.fontId != null -> ReaderFonts.byId(settings.fontId)?.zhName ?: "内置字体"
                else -> "系统字体"
            }
            val customFontActive = settings.customFontUri != null || settings.fontId != null
            var fontPickerVisible by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字号", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp))
                StepperValueInput(
                    value = settings.fontSize,
                    range = 14..24,
                    step = 1,
                    accentColor = accentColor,
                    fieldWidth = 44.dp,
                    onValueChange = { onUpdate(settings.copy(fontSize = it)) }
                )
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { fontPickerVisible = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (customFontActive) accentColor.copy(alpha = 0.1f) else Color.Transparent
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        currentFontName,
                        fontSize = 12.sp,
                        color = if (customFontActive) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (fontPickerVisible) {
                FontPickerDialog(
                    settings = settings,
                    accentColor = accentColor,
                    onSelectSystem = {
                        onUpdate(settings.copy(customFontUri = null, fontId = null, fontFamily = "default"))
                    },
                    onSelectBuiltin = { id ->
                        onUpdate(settings.copy(fontId = id, customFontUri = null))
                    },
                    onImportCustom = ::pickCustomFont,
                    onClearCustom = { onUpdate(settings.copy(customFontUri = null)) },
                    onDismiss = { fontPickerVisible = false }
                )
            }

            // ── 常用滑块（行间距 / 段落间距） ──
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
                title = "页眉间距",
                value = settings.headerContentGap.toFloat(),
                display = "${settings.headerContentGap}dp",
                range = 0f..50f,
                accentColor = accentColor
            ) { onUpdate(settings.copy(headerContentGap = it.toInt())) }

            SettingSliderRow(
                title = "页脚间距",
                value = settings.footerContentGap.toFloat(),
                display = "${settings.footerContentGap}dp",
                range = 0f..50f,
                accentColor = accentColor
            ) { onUpdate(settings.copy(footerContentGap = it.toInt())) }

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

            // ── 沉浸式（对齐 Legado hideStatusBar / hideNavigationBar） ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("隐藏状态栏", style = MaterialTheme.typography.bodyMedium)
                    Text("阅读时隐藏顶部状态栏", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.hideStatusBar,
                    onCheckedChange = { onUpdate(settings.copy(hideStatusBar = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("隐藏导航栏", style = MaterialTheme.typography.bodyMedium)
                    Text("阅读时隐藏底部导航栏", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.hideNavigationBar,
                    onCheckedChange = { onUpdate(settings.copy(hideNavigationBar = it)) },
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = if (step > 0f) ((range.endInclusive - range.start) / step).toInt() - 1 else 0,
            colors = SliderDefaults.colors(activeTrackColor = accentColor),
            modifier = Modifier.weight(1f).height(24.dp)
        )
        Text(display, fontWeight = FontWeight.SemiBold, color = accentColor, fontSize = 12.sp,
            modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/**
 * 字体选择器：系统默认 / 内置开源字库（未下载则点击即下载）/ 自定义导入。
 * 内置字体选择后经 onSelectBuiltin 应用到阅读设置；下载状态就地维护。
 */
@Composable
private fun FontPickerDialog(
    settings: ReadingSettings,
    accentColor: Color,
    onSelectSystem: () -> Unit,
    onSelectBuiltin: (String) -> Unit,
    onImportCustom: () -> Unit,
    onClearCustom: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloading = remember { mutableStateMapOf<String, Boolean>() }
    var downloadError by remember { mutableStateOf<String?>(null) }

    fun pickBuiltin(info: ReaderFontInfo) {
        if (ReaderFonts.localFile(context, info) != null) {
            onSelectBuiltin(info.id)
            return
        }
        if (downloading[info.id] == true) return
        downloading[info.id] = true
        downloadError = null
        scope.launch {
            ReaderFonts.downloadFont(context, info)
                .onSuccess { onSelectBuiltin(info.id) }
                .onFailure { downloadError = "「${info.zhName}」下载失败" }
            downloading[info.id] = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    "选择字体",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                // 系统字体
                FontPickerRow(
                    label = "系统字体",
                    sub = "跟随设备系统字体",
                    selected = settings.customFontUri == null && settings.fontId == null,
                    accentColor = accentColor,
                    trailing = null,
                    onClick = { onSelectSystem(); onDismiss() }
                )
                // 内置开源字体
                ReaderFonts.fonts.forEach { info ->
                    val selected = settings.fontId == info.id && settings.customFontUri == null
                    val downloaded = ReaderFonts.localFile(context, info) != null
                    val isDownloading = downloading[info.id] == true
                    FontPickerRow(
                        label = info.zhName,
                        sub = info.enSpec,
                        selected = selected,
                        accentColor = accentColor,
                        trailing = when {
                            isDownloading -> "下载中…"
                            downloaded -> "已下载"
                            else -> "下载"
                        },
                        trailingColor = when {
                            isDownloading -> MaterialTheme.colorScheme.onSurfaceVariant
                            downloaded -> MaterialTheme.colorScheme.primary
                            else -> accentColor
                        },
                        onClick = { pickBuiltin(info) }
                    )
                }
                // 自定义导入
                FontPickerRow(
                    label = "自定义导入",
                    sub = "从本机文件选择 TTF/OTF",
                    selected = settings.customFontUri != null,
                    accentColor = accentColor,
                    trailing = if (settings.customFontUri != null) "清除" else null,
                    trailingColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        if (settings.customFontUri != null) {
                            onClearCustom()
                        } else {
                            onImportCustom()
                            onDismiss()
                        }
                    }
                )
                downloadError?.let { err ->
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FontPickerRow(
    label: String,
    sub: String,
    selected: Boolean,
    accentColor: Color,
    trailing: String?,
    trailingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) accentColor.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (selected) accentColor else MaterialTheme.colorScheme.onSurface)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.let {
            Text(it, fontSize = 12.sp, color = trailingColor)
        }
    }
}
