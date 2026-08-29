package com.vibereading.app.data.repository

import com.vibereading.app.domain.model.ReadingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读设置持久化器：UI 状态是唯一事实源，这里只负责「最新值合并写」。
 *
 * - 滑杆拖动期每个 tick 调用 [submit]，内部只保留最新待存值；串行写循环在每笔写完后
 *   取走最新值，把 N 次高频改动合并为实际执行的少数几次 DataStore 全量写。
 * - 写入包裹在 [NonCancellable]：阅读器退出（scope 取消）时已排队未落盘的最后一笔
 *   仍会写完，不丢用户设置。
 * - 仅限主线程调用 [submit]（pending/job 无锁，依赖 viewModelScope 主线程串行）。
 * 不在此回流 UI：DataStore 写库回声会以旧值覆盖较新的 UI 状态（滑杆回跳），
 * 持久化值的载入由 ReaderViewModel 一次性完成。
 */
class ReadingSettingsSaver(
    private val scope: CoroutineScope,
    private val save: suspend (ReadingSettings) -> Unit
) {
    private var pending: ReadingSettings? = null
    private var job: Job? = null

    fun submit(settings: ReadingSettings) {
        pending = settings
        if (job?.isActive != true) {
            job = scope.launch {
                while (true) {
                    val toSave = pending ?: break
                    pending = null
                    withContext(NonCancellable) { save(toSave) }
                }
            }
        }
    }
}
