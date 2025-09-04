package com.util.sync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.util.sync.SyncConfigProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @description
 * @author 杨帅林
 * @create 2025/6/22 16:02
 **/
class SyncSuccessUpdaterWorker(context: Context, params: WorkerParameters, private val configProvider: SyncConfigProvider) : CoroutineWorker(context, params) {
    override suspend fun doWork()= withContext(Dispatchers.IO) {
        // 从输入数据中获取本次同步开始的时间戳
        val syncStartTime = inputData.getString("KEY_SYNC_START_TIME")
        if (syncStartTime.isNullOrEmpty()) {
            Log.e("UpdaterWorker", "Sync start time not provided!")
            Result.failure()
        }

        // 这是整个流程中唯一更新时间戳的地方！
        syncStartTime?.let { configProvider.saveSuccessfulSyncTime(it) }
        Log.i("UpdaterWorker", "SYNC CHAIN COMPLETED SUCCESSFULLY. Sync time updated to: $syncStartTime")
        Result.success()
    }
}