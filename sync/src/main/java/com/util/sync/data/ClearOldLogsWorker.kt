package com.util.sync.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.jvm.java

/**
 * @description
 * @author 杨帅林
 * @create 2025/9/4 9:16
 **/
class ClearOldLogsWorker(appContext: Context, workerParams: WorkerParameters, private val syncLogDao: SyncLogDao) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // 例如，删除30天前的日志
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -5)
            val cutoffDate = formatTimestamp(calendar.time)

            syncLogDao.deleteLogsOlderThan(cutoffDate)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private val format by lazy {
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        )
    }
    fun formatTimestamp(date: Date) = format.format(date)
}