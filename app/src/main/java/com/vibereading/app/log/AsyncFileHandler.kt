package com.vibereading.app.log

import java.util.logging.FileHandler
import java.util.logging.LogRecord

/**
 * 把磁盘写入转移到 [logExecutor] 后台线程，日志调用线程不阻塞在 I/O。
 * 参照 legado `AsyncFileHandler`。
 */
class AsyncFileHandler(pattern: String) : FileHandler(pattern) {

    override fun publish(record: LogRecord?) {
        if (!isLoggable(record)) return
        logExecutor.execute { super.publish(record) }
    }
}
