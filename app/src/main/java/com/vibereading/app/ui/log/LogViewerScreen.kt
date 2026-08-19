package com.vibereading.app.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.log.AppLog
import com.vibereading.app.log.CrashLogFiles
import com.vibereading.app.log.LogUtils
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogTab(val label: String) {
    RUN("运行日志"),
    CRASH("崩溃日志")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val stableInsets = LocalStableSystemBarInsets.current

    var tab by remember { mutableStateOf(LogTab.RUN) }
    // 运行日志快照 + 手动刷新计数（AppLog 是普通 List，不会触发重组）
    var refreshKey by remember { mutableStateOf(0) }
    val runLogs by remember(refreshKey) { derivedStateOf { AppLog.logs } }
    // 崩溃日志列表
    var crashFiles by remember { mutableStateOf(CrashLogFiles.list(context)) }
    var selectedCrash by remember { mutableStateOf<File?>(null) }

    Scaffold(
        contentWindowInsets = stableInsets,
        topBar = {
            TopAppBar(
                windowInsets = stableInsets,
                title = { Text("日志", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        when (tab) {
                            LogTab.RUN -> {
                                AppLog.clear()
                                refreshKey++
                                Toast.makeText(context, "已清除运行日志", Toast.LENGTH_SHORT).show()
                            }

                            LogTab.CRASH -> {
                                CrashLogFiles.clear(context)
                                crashFiles = emptyList()
                                Toast.makeText(context, "已清除崩溃日志", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Delete, "清除")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab.ordinal) {
                LogTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(t.label) }
                    )
                }
            }
            when (tab) {
                LogTab.RUN -> RunLogList(
                    logs = runLogs,
                    onRefresh = { refreshKey++ }
                )

                LogTab.CRASH -> CrashLogList(
                    files = crashFiles,
                    onOpen = { selectedCrash = it }
                )
            }
        }
    }

    selectedCrash?.let { file ->
        CrashDetailDialog(
            file = file,
            onDismiss = { selectedCrash = null },
            onCopy = { copyCrashToClipboard(context, file) }
        )
    }
}

@Composable
private fun RunLogList(
    logs: List<Triple<Long, String, Throwable?>>,
    onRefresh: () -> Unit
) {
    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无运行日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    // 离开页面回来时刷新一次；这里用 LaunchedEffect 监听可见性变化不可靠，
    // 简单做法：列表可滚动 + 顶部提供一个轻量刷新提示
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "共 ${logs.size} 条（上限 100）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onRefresh) { Text("刷新", fontSize = 12.sp) }
            }
        }
        items(logs, key = { it.first.toString() + it.second.hashCode() }) { (time, msg, throwable) ->
            RunLogItem(time, msg, throwable != null)
        }
    }
}

@Composable
private fun RunLogItem(time: Long, message: String, hasThrowable: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            LogUtils.logTimeFormat.format(Date(time)),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(2.dp))
        Text(
            message,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (hasThrowable) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CrashLogList(files: List<File>, onOpen: (File) -> Unit) {
    if (files.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无崩溃日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(files, key = { it.name }) { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(file) }
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        formatFileSize(file.length()),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashDetailDialog(
    file: File,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    val content = remember(file) { CrashLogFiles.read(file) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(file.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    content,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(); onDismiss() }) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun copyCrashToClipboard(context: Context, file: File) {
    val content = CrashLogFiles.read(file)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(file.name, content))
    Toast.makeText(context, "已复制 ${file.name}", Toast.LENGTH_SHORT).show()
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        bytes < 1024 -> "${bytes} B"
        kb < 1024 -> String.format(Locale.US, "%.1f KB", kb)
        else -> String.format(Locale.US, "%.1f MB", kb / 1024)
    }
}
