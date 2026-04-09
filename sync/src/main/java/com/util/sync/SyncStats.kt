package com.util.sync

/**
 * 同步统计数据类（可变计数器）
 * 用于在同步过程中统计各项操作的计数。
 * 虽然使用 var 字段，但仅在单一线程的顺序处理流程中使用，不涉及并发修改。
 */
class SyncStats {
    var downloaded: Int = 0; private set
    var uploaded: Int = 0; private set
    var skipped: Int = 0; private set
    var failedFetch: Int = 0; private set

    fun recordDownload() { downloaded++ }
    fun recordUpload() { uploaded++ }
    fun recordSkip() { skipped++ }
    fun recordFailedFetch() { failedFetch++ }

    override fun toString(): String =
        "SyncStats(downloaded=$downloaded, uploaded=$uploaded, skipped=$skipped, failedFetch=$failedFetch)"
}
