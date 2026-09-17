package com.util.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** WorkManager 的默认单进程内，所有库内 Compare Worker 共用写入通道。 */
internal object SyncExecutionGate {
    private val dataMutex = Mutex()
    private val coordinatorMutex = Mutex()

    suspend fun <T> withDataLock(block: suspend () -> T): T = dataMutex.withLock { block() }
    suspend fun <T> withCoordinatorLock(block: suspend () -> T): T = coordinatorMutex.withLock { block() }
}
