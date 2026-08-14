package com.vibereading.app.ui.reader.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.reader.ReaderPalette
import com.vibereading.app.ui.reader.chapterLabel
import com.vibereading.app.ui.reader.pagination.BookWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阅读页页眉/页脚（视觉覆盖层，对齐 Legado view_book_page 的 ll_header / ll_footer）：
 * - 页眉（左上）：章节号 · 章节名
 * - 页脚（左下）：当前页 / 本章总页（对齐 Legado page 提示：index+1/pageSize）
 * - 页脚（右下）：时间 + 电量（对齐 Legado timeBatteryPercentage：HH:mm 电量%）
 *
 * 只叠在分页 pager 之上、不参与排版测量、不缩排版内容区（与原文气泡/弹窗一致），
 * 显示在用户边距条带内；卷页位图不渲染页眉/页脚（对齐 Legado 位图只有正文）。
 * 时间/电量用系统受保护广播 TIME_TICK + BATTERY_CHANGED 动态注册（无需导出标记），
 * 对齐 Legado TimeBatteryReceiver。
 */
@Composable
fun PageInfoOverlays(
    window: BookWindow,
    chapters: List<Chapter>,
    activeChapterId: Long?,
    pagerState: PagerState,
    palette: ReaderPalette,
    padH: Int
) {
    val context = LocalContext.current

    // 时间 / 电量（对齐 Legado TimeBatteryReceiver）
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableIntStateOf(initialBattery(context)) }

    DisposableEffect(context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK -> now = System.currentTimeMillis()
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        if (level >= 0) battery = level
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val chapterIndex = chapters.indexOfFirst { it.id == activeChapterId }
    val inBook = chapterIndex in chapters.indices
    // 当前页/本章总页（章内相对页，ADR-001 无全局页索引）
    val totalPages = activeChapterId?.let { window.pageCountInChapter(it) } ?: 0
    val currentPage = window.pageInChapterOfPage(pagerState.currentPage)
    val tipColor = palette.bodyText.copy(alpha = 0.7f)

    Box(modifier = Modifier.fillMaxSize()) {
        // ── 页眉（左上）：章节号 · 章节名 ──
        // 标题本身已含章号前缀（如「第6章 终于死了」）时不重复拼章号；序章/楔子同理
        Text(
            text = if (inBook) {
                val title = chapters[chapterIndex].title
                val label = chapterLabel(chapters, chapterIndex)
                if (title == label || title.startsWith(label)) title else "$label · $title"
            } else "",
            fontSize = 12.sp,
            color = tipColor,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = padH.dp, top = 2.dp)
        )

        // ── 页脚（左下）：当前页 / 本章总页 ──
        Text(
            text = if (totalPages > 0) "${currentPage + 1}/$totalPages" else "",
            fontSize = 12.sp,
            color = tipColor,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = padH.dp, bottom = 2.dp)
        )

        // ── 页脚（右下）：时间 + 电量 ──
        Text(
            text = "${timeFormat.format(Date(now))} $battery%",
            fontSize = 12.sp,
            color = tipColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = padH.dp, bottom = 2.dp)
        )
    }
}

/** 时间格式（对齐 Legado AppConst.timeFormat："HH:mm"）。 */
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** 初始电量：BatteryManager 属性查询（对齐 Legado TimeBatteryReceiver 的默认 100 兜底）。 */
private fun initialBattery(context: Context): Int =
    try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
    } catch (_: Exception) {
        100
    }
