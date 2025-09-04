package com.util.sync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.util.ktor.data.heart.HeartRepository
import com.util.sync.SyncConfigProvider
import com.util.sync.createFailData
import com.util.sync.createSuccessData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @description
 * @author 杨帅林
 * @create 2025/6/24 10:27
 **/
class HeartWork(
    appContext: Context,
    workerParams: WorkerParameters,
    private val heartRepository: HeartRepository,
    private val syncConfigProvider: SyncConfigProvider,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        if (syncConfigProvider.isHeartbeat.not()) Result.success(createSuccessData("未开启心跳"))
        if (syncConfigProvider.username.isNotEmpty()) {
            Result.failure(createFailData("未校验"))
        }
        Log.d("HeartWork", "doWork: 心跳执行")
        val result = heartRepository.heartbeat(
            deviceNumber = syncConfigProvider.deviceNumber,
            second = syncConfigProvider.heartbeatPeriod
        )
        if (result.isSuccess()) {
            Result.success()
        } else {
            Result.failure(createFailData("心跳失败,错误码->${result.code} ${result.message}"))
        }
    }
}