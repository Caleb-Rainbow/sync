package com.util.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.util.sync.KEY_SYNC_START_TIME
import com.util.sync.SyncConfigProvider
import com.util.sync.log.libLogD
import com.util.sync.log.libLogE
import com.util.sync.log.libLogI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 同步成功更新器工作器
 * 在所有同步任务成功完成后执行，负责更新最后同步时间戳
 * 
 * @author 杨帅林
 * @create 2025/6/22 16:02
 */
class SyncSuccessUpdaterWorker(
    context: Context,
    params: WorkerParameters,
    private val configProvider: SyncConfigProvider
) : CoroutineWorker(context, params) {

    private val logDateFormatter by lazy {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    private val utcZone = java.time.ZoneOffset.UTC

    private fun formatTimestamp(timeMs: Long): String =
        java.time.Instant.ofEpochMilli(timeMs)
            .atZone(utcZone)
            .format(logDateFormatter)

    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val workerId = id.toString().takeLast(8)

        libLogI("════════════════════════════════════════")
        libLogI("🏆 同步成功更新器开始")
        libLogI("  工作ID: $workerId")
        libLogI("  开始时间: ${formatTimestamp(startTime)}")
        libLogI("════════════════════════════════════════")

        // 从输入数据中获取本次同步开始的时间戳
        val syncStartTime = inputData.getString(KEY_SYNC_START_TIME)

        libLogD("  输入参数 KEY_SYNC_START_TIME: $syncStartTime")

        if (syncStartTime.isNullOrEmpty()) {
            libLogE("❌ 严重错误: 同步开始时间未提供!")
            libLogE("  无法更新同步时间戳，同步链可能未正确配置")
            libLogE("  请检查 SyncCoordinatorWorker 是否正确传递了 KEY_SYNC_START_TIME")
            libLogI("────────────────────────────────────────")
            return@withContext Result.failure()
        }

        try {
            // 获取更新前的时间戳（用于日志对比）
            val previousSyncTime = configProvider.syncDataTime

            // 这是整个流程中唯一更新时间戳的地方！
            configProvider.saveSuccessfulSyncTime(syncStartTime)

            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime

            libLogI("✅ 同步链全部完成!")
            libLogI("  同步时间戳已更新:")
            libLogI("    旧值: ${previousSyncTime.ifEmpty { "无 (首次同步)" }}")
            libLogI("    新值: $syncStartTime")
            libLogI("  更新器耗时: ${duration}ms")
            libLogI("  结束时间: ${formatTimestamp(endTime)}")
            libLogI("════════════════════════════════════════")
            libLogI("🎉 数据同步流程成功结束")
            libLogI("════════════════════════════════════════")

            Result.success()
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime

            libLogE("💥 更新同步时间戳时发生异常", e)
            libLogE("  异常类型: ${e.javaClass.simpleName}")
            libLogE("  异常信息: ${e.message}")
            libLogE("  耗时: ${duration}ms")
            libLogE("  堆栈信息:\n${e.stackTraceToString()}")
            libLogI("────────────────────────────────────────")

            Result.failure()
        }
    }
}