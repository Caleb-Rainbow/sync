package com.util.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.yitter.idgen.YitIdHelper
import com.util.sync.KEY_LAST_SYNC_TIME
import com.util.sync.KEY_SYNC_SESSION_ID
import com.util.sync.KEY_SYNC_START_TIME
import com.util.sync.SyncConfigProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KClass

class SyncCoordinatorWorker(
    private val context: Context,
    params: WorkerParameters,
    private val syncConfigProvider: SyncConfigProvider
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (syncConfigProvider.username.isEmpty()) {
            return@withContext Result.failure()
        }
        val syncStartTime = getCurrentTime()
        val lastSyncTime = syncConfigProvider.syncDataTime

        val workManager = WorkManager.Companion.getInstance(context)
        val sessionId = "自动同步-${YitIdHelper.nextId()}"

        // 1. 筛选出所有应该参与定期同步的任务
        val parallelSyncTasks = syncConfigProvider.getAllTask()
            .map { task ->
                // 2. 使用动态的 workerClass 创建 OneTimeWorkRequest
                createOneTimeWork(task.workerClass, lastSyncTime, sessionId)
            }

        // 如果没有需要执行的任务，直接成功返回
        if (parallelSyncTasks.isEmpty()) {
            return@withContext Result.success()
        }

        // --- 后续逻辑保持不变 ---
        val successUpdaterWork = OneTimeWorkRequestBuilder<SyncSuccessUpdaterWorker>()
            .setInputData(workDataOf(KEY_SYNC_START_TIME to syncStartTime))
            .build()

        workManager
            .beginWith(parallelSyncTasks)
            .then(successUpdaterWork)
            .enqueue()

        return@withContext Result.success()
    }
    private val format1: SimpleDateFormat by lazy {
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        )
    }
    fun getCurrentTime(): String = format1.format(Date())

    /**
     * 创建一个带输入数据的一次性工作请求（非泛型版本）
     * 这里的实现需要修改，以接受 KClass 参数
     */
    private fun createOneTimeWork(workerClass: KClass<out ListenableWorker>, lastSyncTime: String, sessionId: String): OneTimeWorkRequest {
        val inputData = workDataOf(KEY_LAST_SYNC_TIME to lastSyncTime, KEY_SYNC_SESSION_ID to sessionId)

        // 使用我们之前讨论过的非泛型 Builder
        return OneTimeWorkRequest.Builder(workerClass.java)
            .setInputData(inputData)
            .build()
    }

}