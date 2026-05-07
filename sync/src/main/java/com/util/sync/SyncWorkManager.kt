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
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.jvm.java
import kotlin.to

/**
 * @description 同步工作管理器，负责调度同步 Worker
 * @author 杨帅林
 * @create 2025/8/29 15:47
 **/
class SyncWorkManager(val context: Context) {

    /**
     * 启动一个带唯一动态标签的 Worker，并返回一个只观察该 Worker 的 Flow。
     * 每种 Worker 使用独立的唯一工作名，避免不同任务之间互相清理 WorkSpec。
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
            .addTag(GLOBAL_SYNC_WORK_NAME)
            .setInputData(
                workDataOf(
                    KEY_LAST_SYNC_TIME to lastSyncTime,
                    KEY_SYNC_SESSION_ID to "手动同步--$sessionId"
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        // 2. 每个 Worker 类型使用独立的唯一工作名
        // 避免 APPEND_OR_REPLACE 在新任务入队时删除已完成任务的 WorkSpec
        val uniqueWorkName = "${GLOBAL_SYNC_WORK_NAME}_${W::class.java.name}"
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        // 3. 返回一个只观察这个唯一 Tag 的 Flow
        return workManager.getWorkInfosByTagFlow(uniqueTag)
    }

    /**
     * 检查当前是否有同步任务正在执行。
     * 通过公共标签 [GLOBAL_SYNC_WORK_NAME] 查询所有同步任务。
     */
    suspend fun isSyncRunning(): Boolean {
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosByTagFlow(GLOBAL_SYNC_WORK_NAME).first()
        return workInfos.any { it.state == WorkInfo.State.RUNNING }
    }

    /**
     * 取消所有正在进行的同步任务。
     */
    fun cancelAllSync() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(GLOBAL_SYNC_WORK_NAME)
    }
}
