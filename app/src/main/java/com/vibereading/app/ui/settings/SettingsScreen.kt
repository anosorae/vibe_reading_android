package com.vibereading.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.ui.components.LlmProfileEditor
import com.vibereading.app.ui.components.LlmProfileList
import com.vibereading.app.ui.components.LlmTranslationParams
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets
import com.vibereading.app.ui.theme.VibeColors
import com.vibereading.app.ui.theme.WereadColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit = {}
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    // 伴读是前台服务，靠常驻通知展示含 Token 的地址、也靠它把服务锁在前台。
    // Android 13+ 必须先拿到通知权限，否则服务照跑但通知栏里什么都看不到。
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.toggleWebCompanion(true)
        if (!granted) {
            Toast.makeText(
                context,
                "未授予通知权限：伴读会照常运行，但通知栏不会显示常驻地址",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    val editApiKey by vm.editApiKey.collectAsState()
    val editApiBase by vm.editApiBase.collectAsState()
    val editModel by vm.editModel.collectAsState()
    val editName by vm.editName.collectAsState()

    val accentColor = if (state.theme.accent == AppAccent.WEREAD) WereadColors.Accent else VibeColors.Sienna

    // 折叠区：翻译设置
    var llmExpanded by remember { mutableStateOf(true) }

    val clipboard = LocalClipboardManager.current

    // 稳定系统栏 insets：沉浸式切换时不归零，防止布局跳动
    val stableInsets = LocalStableSystemBarInsets.current

    Scaffold(
        contentWindowInsets = stableInsets,
        topBar = {
            TopAppBar(
                windowInsets = stableInsets,
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── 主题设置 ──
            SectionHeader("主题设置")
            SectionCard {
                // 主题模式
                Text(
                    "主题模式",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val modes = listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    modes.forEach { (mode, label) ->
                        val selected = state.theme.themeMode == mode
                        OutlinedButton(
                            onClick = { vm.updateThemeMode(mode) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) accentColor.copy(alpha = 0.1f) else Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 配色预览：原木 / 青简
                Text(
                    "配色",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccentPreview(
                        label = "原木",
                        primary = VibeColors.Sienna,
                        bg = VibeColors.Cream,
                        selected = state.theme.accent == AppAccent.VIBE,
                        accentColor = accentColor,
                        onClick = { vm.updateAccent(AppAccent.VIBE) }
                    )
                    AccentPreview(
                        label = "青简",
                        primary = WereadColors.Accent,
                        bg = WereadColors.Cream,
                        selected = state.theme.accent == AppAccent.WEREAD,
                        accentColor = accentColor,
                        onClick = { vm.updateAccent(AppAccent.WEREAD) }
                    )
                }
            }

            // ── 翻译设置 ──
            SectionHeader("翻译设置")
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { llmExpanded = !llmExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LLM 配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    val rotation by animateFloatAsState(
                        targetValue = if (llmExpanded) 90f else 0f,
                        label = "chevron"
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .rotate(rotation)
                            .size(20.dp)
                    )
                }

                if (llmExpanded) {
                    Spacer(Modifier.height(12.dp))

                    if (state.editingProfile != null) {
                        // ── 编辑配置二级页面 ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.cancelEdit() }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "返回配置列表",
                                style = MaterialTheme.typography.bodyMedium,
                                color = accentColor,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        LlmProfileEditor(
                            editName = editName,
                            editApiKey = editApiKey,
                            editApiBase = editApiBase,
                            editModel = editModel,
                            showApiKey = state.showApiKey,
                            accentColor = accentColor,
                            testResult = state.testResult,
                            testSuccess = state.testSuccess,
                            onUpdateName = vm::updateEditName,
                            onUpdateApiKey = vm::updateEditApiKey,
                            onUpdateApiBase = vm::updateEditApiBase,
                            onUpdateModel = vm::updateEditModel,
                            onToggleShowApiKey = vm::toggleShowApiKey,
                            onSave = vm::saveProfile,
                            onTest = vm::testConnection,
                            isSaving = state.isSaving,
                            isTesting = state.isTesting,
                            editorTitle = if (state.isNewProfile) "新建配置" else "编辑配置",
                            onCancel = vm::cancelEdit
                        )
                    } else {
                        // ── 配置列表 ──
                        LlmProfileList(
                            profiles = state.profiles,
                            activeProfileId = state.activeProfileId,
                            accentColor = accentColor,
                            onSelect = vm::selectProfile,
                            onEdit = vm::editProfile,
                            onDelete = vm::deleteProfile,
                            onAdd = vm::addProfile
                        )
                    }
                }
            }

            // ── 翻译参数 ──
            SectionHeader("翻译参数")
            SectionCard {
                LlmTranslationParams(
                    llmSettings = state.llmSettings,
                    accentColor = accentColor,
                    onUpdateChapterMaxChars = vm::updateChapterMaxChars,
                    onUpdateMaxOutputTokens = vm::updateMaxOutputTokens,
                    onToggleThinking = vm::updateThinking,
                    onToggleExplainThinking = vm::updateExplainThinking,
                    onUpdateTemperature = vm::updateTemperature,
                    onUpdateTopP = vm::updateTopP,
                    dividerBetweenGroups = true
                )
            }

            // ── Web 伴读服务 ──
            SectionHeader("Web 伴读服务")
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("局域网网页阅读", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "同一 WiFi 下的电脑浏览器可阅读本书库",
                            fontSize = 12.sp,
                            color = VibeColors.WarmGray
                        )
                    }
                    Switch(
                        checked = state.webCompanionRunning,
                        onCheckedChange = { on ->
                            val needNotifPermission = on &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            // 先要权限再启服务：授权回调里无论通过与否都会启动，避免开关
                            // 打开却没反应；拒绝时给出提示而不是静默失败。
                            if (needNotifPermission) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                vm.toggleWebCompanion(on)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                    )
                }
                if (state.webCompanionRunning && state.webCompanionUrl != null) {
                    Spacer(Modifier.height(8.dp))
                    val url = state.webCompanionUrl
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (url != null) clipboard.setText(AnnotatedString(url))
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "在电脑浏览器打开（点击复制）",
                                fontSize = 12.sp,
                                color = VibeColors.WarmGray
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                url ?: "",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "此地址已常驻通知栏，锁屏后也能查看",
                                fontSize = 12.sp,
                                color = VibeColors.WarmGray
                            )
                        }
                    }
                }
            }

            // ── 调试 ──
            SectionHeader("调试")
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLogs() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("日志", style = MaterialTheme.typography.bodyMedium)
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── 关于 ──
            SectionHeader("关于")
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("版本", style = MaterialTheme.typography.bodyMedium)
                    Text(com.vibereading.app.BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "译读 —— 双语阅读器：导入 TXT 小说，逐章调用 LLM 翻译为英文，中英双模式阅读。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** 配色预览块：主色圆点 + 底色卡片 + 名称。 */
@Composable
private fun AccentPreview(
    label: String,
    primary: Color,
    bg: Color,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) accentColor.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .then(
                if (selected) Modifier.border(2.dp, accentColor, RoundedCornerShape(10.dp))
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            )
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(1.dp, primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(primary)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
