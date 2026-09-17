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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import androidx.work.await
import com.util.sync.worker.SyncCoordinatorWorker
import com.util.sync.worker.SyncSuccessUpdaterWorker
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
     * 启动唯一 Worker，返回其唯一工作名的状态流；重复请求观察 KEEP 保留的任务。
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
        // KEEP 保留活动任务。真正的数据执行互斥由 BaseCompareWork 的共享通道保证。
        val uniqueWorkName = "${GLOBAL_SYNC_WORK_NAME}_${W::class.java.name}"
        val operation = workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        // KEEP 可能保留旧请求；观察唯一工作而非未入队的新 tag，并传播入队错误。
        return flow {
            operation.await()
            emitAll(workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName))
        }
    }

    /**
     * 检查当前是否有同步任务正在执行。
     * 通过公共标签 [GLOBAL_SYNC_WORK_NAME] 查询所有同步任务。
     */
    suspend fun isSyncRunning(): Boolean {
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosByTagFlow(GLOBAL_SYNC_WORK_NAME).first()
        return workInfos.any { it.state == WorkInfo.State.RUNNING } ||
            workManager.getWorkInfosByTagFlow(SyncCoordinatorWorker::class.java.name)
                .first().any { it.state == WorkInfo.State.RUNNING }
    }

    /**
     * 是否存在未结束的同步，包括运行、排队、阻塞及协调器退避期间。
     * 供 UI 展示状态使用，不能用先查询后入队替代执行互斥。
     */
    suspend fun isSyncActive(): Boolean {
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosByTagFlow(GLOBAL_SYNC_WORK_NAME).first()
        return workInfos.any { !it.state.isFinished } ||
            workManager.getWorkInfosByTagFlow(SyncCoordinatorWorker::class.java.name)
                .first().any { !it.state.isFinished }
    }

    /**
     * 取消所有正在进行的同步任务。
     */
    fun cancelAllSync() {
        val workManager = WorkManager.getInstance(context)
        // 类名是 WorkManager 自动添加的 tag，兼容宿主自行创建的协调器请求。
        workManager.cancelAllWorkByTag(SyncCoordinatorWorker::class.java.name)
        workManager.cancelAllWorkByTag(SyncSuccessUpdaterWorker::class.java.name)
        workManager.cancelAllWorkByTag(GLOBAL_SYNC_WORK_NAME)
    }
}
