package com.util.sync

/** 沿用全局时间戳；停用或配置变化时不越过尚未同步的数据。 */
internal class SyncCursorGuard(tasks: List<SyncTaskDefinition>) {
    val signature: Array<String> = signatureOf(tasks)
    private val allEnabled = tasks.isNotEmpty() && tasks.all {
        SyncOption.fromInt(it.syncOptionValue) != SyncOption.SYNC_OFF
    }
    var skipped: Boolean = false

    fun canAdvance(currentTasks: List<SyncTaskDefinition>): Boolean =
        allEnabled && !skipped && signature.contentEquals(signatureOf(currentTasks))

    companion object {
        fun signatureOf(tasks: List<SyncTaskDefinition>): Array<String> = tasks.map {
            "${it.workerClass.java.name}:${it.syncOptionValue}"
        }.sorted().toTypedArray()
    }
}
