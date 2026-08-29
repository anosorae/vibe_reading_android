package com.vibereading.app.ui.reader.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
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
    padH: Int,
    padV: Int,
    headerContentGap: Int = 20,
    footerContentGap: Int = 20,
    statusBarPx: Int,
    navBarPx: Int,
    cutoutLeftPx: Int = 0,
    cutoutRightPx: Int = 0
) {
    val context = LocalContext.current
    val density = LocalDensity.current

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
    // 当前页/本章总页（章内相对页，ADR-001 无全局页索引）。
    // 本章整章排版未完成（打开书籍前缀排版续排中）时总页数未定：只显示当前页，
    // 排版完成由 refreshWindow 重组带出完整「当前页/总页」，避免总页数渐变跳变。
    val chapterLayoutComplete = activeChapterId != null && window.isChapterLayoutComplete(activeChapterId)
    val totalPages = activeChapterId?.let { window.pageCountInChapter(it) } ?: 0
    val currentPage = window.pageInChapterOfPage(pagerState.currentPage)
    val pageTip = when {
        totalPages <= 0 -> ""
        chapterLayoutComplete -> "${currentPage + 1}/$totalPages"
        else -> "${currentPage + 1}"
    }
    val tipColor = palette.bodyText.copy(alpha = 0.7f)

    // 系统栏/挖孔用缓存值（不随沉浸式切换变化），与排版几何一致
    val statusBarDp = with(density) { statusBarPx.toDp() }
    val navBarDp = with(density) { navBarPx.toDp() }
    val cutoutLeftDp = with(density) { cutoutLeftPx.toDp() }
    val cutoutRightDp = with(density) { cutoutRightPx.toDp() }
    // 正文内容区边界（与排版几何一致：contentTop = statusBar + padV, contentBottom = navBar + padV）
    val contentTopDp = statusBarDp + padV.dp
    val contentBottomDp = navBarDp + padV.dp
    // 12sp 单行文字高度（用于推算页眉/页脚离正文区间距）
    val overlayTextHeightDp = 16.dp
    // 锚点退让量：手势导航设备（如小米 HyperOS）底部 inset 可能不足 16dp，
    // 推算结果为负会让 Compose padding 直接抛 IllegalArgumentException，必须钳到 0
    // （负数语义本就是「贴边」，与上文允许叠入系统栏区域的取舍一致）
    val headerOffsetDp = (contentTopDp - headerContentGap.dp - overlayTextHeightDp).coerceAtLeast(0.dp)
    val footerOffsetDp = (contentBottomDp - footerContentGap.dp - overlayTextHeightDp).coerceAtLeast(0.dp)

    Box(modifier = Modifier.fillMaxSize()) {
        // ── 页眉（左上）：章节号 · 章节名 ──
        // 以正文区顶部为锚点向上退让：文字底边 = contentTop - gap，文字顶边 = contentTop - gap - textHeight
        // 若 margin 不够，文字可少量叠入系统栏区域（边到边模式下状态栏半透明，可接受）
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
                .padding(
                    top = headerOffsetDp,
                    start = maxOf(padH.dp, cutoutLeftDp)
                )
        )

        // ── 页脚（左下）：当前页 / 本章总页 ──
        // 以正文区底部为锚点向下退让：文字顶边 = contentBottom - gap，文字底边 = contentBottom - gap - textHeight
        Text(
            text = pageTip,
            fontSize = 12.sp,
            color = tipColor,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    bottom = footerOffsetDp,
                    start = maxOf(padH.dp, cutoutLeftDp)
                )
        )

        // ── 页脚（右下）：时间 + 电量 ──
        Text(
            text = "${timeFormat.format(Date(now))} $battery%",
            fontSize = 12.sp,
            color = tipColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    bottom = footerOffsetDp,
                    end = maxOf(padH.dp, cutoutRightDp)
                )
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
