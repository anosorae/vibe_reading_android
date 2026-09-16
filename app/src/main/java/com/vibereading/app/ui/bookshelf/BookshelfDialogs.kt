package com.vibereading.app.ui.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibereading.app.data.image.BookImageStore
import com.vibereading.app.domain.model.BookShelfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookActionsSheet(
    item: BookShelfItem,
    onDismiss: () -> Unit,
    onOpenBook: () -> Unit,
    onSelectSourceLanguage: () -> Unit,
    onSelectCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDelete: () -> Unit
) {
    // 内嵌封面备份是否存在：决定菜单显示「恢复原封面」还是「移除封面」（与 VM 用同一判据）。
    // 磁盘 stat 放 IO，菜单打开时只查一次
    val canRestoreCover by produceState(
        initialValue = false,
        key1 = item.book.id,
        key2 = item.book.coverPath
    ) {
        value = withContext(Dispatchers.IO) {
            BookImageStore.canRestoreEmbeddedCover(item.book.id, item.book.coverPath)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                item.book.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Text(
                "${item.book.totalChapters}章 · 已译${item.translatedCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(8.dp))
            ListItem(
                headlineContent = { Text("开始阅读") },
                leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenBook)
            )
            ListItem(
                headlineContent = { Text("本书原文语言") },
                supportingContent = { Text(if (item.book.sourceLanguage == "en") "英文原版" else "中文原版") },
                leadingContent = { Icon(Icons.Filled.Translate, contentDescription = null) },
                trailingContent = { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onSelectSourceLanguage)
            )
            ListItem(
                headlineContent = { Text(if (item.book.coverPath == null) "设置封面" else "更换封面") },
                supportingContent = { Text("从相册或文件中选择图片") },
                leadingContent = { Icon(Icons.Filled.Image, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onSelectCover)
            )
            if (item.book.coverPath != null) {
                ListItem(
                    headlineContent = { Text(if (canRestoreCover) "恢复原封面" else "移除封面") },
                    supportingContent = { Text(if (canRestoreCover) "回到 EPUB 内置封面" else "回到默认渐变封面") },
                    leadingContent = {
                        Icon(if (canRestoreCover) Icons.Filled.Restore else Icons.Filled.HideImage, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onRemoveCover)
                )
            }
            ListItem(
                headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(onClick = onDelete)
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun DeleteBookDialog(
    item: BookShelfItem?,
    onDismiss: () -> Unit,
    onConfirm: (BookShelfItem) -> Unit
) {
    item ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除书籍") },
        text = { Text("确定要删除《${item.book.title}》吗？此操作不可恢复。") },
        confirmButton = {
            TextButton(onClick = { onConfirm(item) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun SourceLanguagePickerDialog(
    item: BookShelfItem?,
    onDismiss: () -> Unit,
    onConfirm: (BookShelfItem, String) -> Unit
) {
    item ?: return
    var picked by remember(item.book.id) { mutableStateOf(item.book.sourceLanguage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本书原文语言") },
        text = {
            Column {
                listOf("zh" to "中文原版", "en" to "英文原版").forEach { (lang, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { picked = lang }.padding(vertical = 8.dp)
                    ) {
                        RadioButton(selected = picked == lang, onClick = { picked = lang })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(item, picked) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun ConfirmSourceLanguageDialog(
    selection: Pair<BookShelfItem, String>?,
    onDismiss: () -> Unit,
    onConfirm: (BookShelfItem, String) -> Unit
) {
    selection ?: return
    val (item, targetLang) = selection
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修正原文语言") },
        text = {
            Text(
                "将《${item.book.title}》的原文语言改为「${if (targetLang == "en") "英文原版" else "中文原版"}」？\n\n此操作会清空本书已生成的章节译文，并重置阅读模式为对应原文。"
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(item, targetLang) }) {
                Text("确认", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
