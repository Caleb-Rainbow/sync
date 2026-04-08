package com.util.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.yitter.idgen.YitIdHelper
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import kotlin.jvm.java
import kotlin.to

/**
 * 全局同步工作名称，用于互斥。
 * 手动同步和自动同步都使用同一个 unique work name，
 * 通过 [ExistingWorkPolicy.APPEND_OR_REPLACE] 保证不会并发执行。
 */
const val GLOBAL_SYNC_WORK_NAME = "global_sync_work"

/**
 * @description 同步工作管理器，负责调度同步 Worker
 * @author 杨帅林
 * @create 2025/8/29 15:47
 **/
class SyncWorkManager(val context: Context) {

    /**
     * 启动一个带唯一动态标签的 Worker，并返回一个只观察该 Worker 的 Flow。
     * 使用全局唯一工作名 [GLOBAL_SYNC_WORK_NAME] 保证手动同步之间互斥。
     */
    inline fun <reified W : ListenableWorker> enqueueAndObserveUniqueRequest(
        lastSyncTime: String,
        sessionId: Long
    ): Flow<List<WorkInfo>> {
        val workManager = WorkManager.getInstance(context)

        // 1. 为本次请求创建唯一的动态 Tag
        val uniqueTag = "${W::class.java.name}-${YitIdHelper.nextId()}"

        val workRequest = OneTimeWorkRequestBuilder<W>()
            .addTag(uniqueTag)
            .setInputData(
                workDataOf(
                    KEY_LAST_SYNC_TIME to lastSyncTime,
                    KEY_SYNC_SESSION_ID to "手动同步--$sessionId"
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        // 2. 使用全局唯一工作名入队，防止手动同步之间并发重复执行
        // APPEND_OR_REPLACE: 如果有已完成的同名工作则替换，如果有正在执行的同名工作则排队等待
        workManager.enqueueUniqueWork(
            GLOBAL_SYNC_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )

        // 3. 返回一个只观察这个唯一 Tag 的 Flow
        return workManager.getWorkInfosByTagFlow(uniqueTag)
    }

    /**
     * 检查当前是否有同步任务正在执行。
     */
    fun isSyncRunning(): Boolean {
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork(GLOBAL_SYNC_WORK_NAME).get()
        return workInfos.any { it.state == WorkInfo.State.RUNNING }
    }

    /**
     * 取消所有正在进行的同步任务。
     */
    fun cancelAllSync() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(GLOBAL_SYNC_WORK_NAME)
    }
}
