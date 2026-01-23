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
import com.util.sync.log.libLogD
import com.util.sync.log.libLogE
import com.util.sync.log.libLogI
import com.util.sync.log.libLogW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KClass

/**
 * 同步协调器工作器
 * 负责调度和编排所有同步任务，构建任务执行链
 */
class SyncCoordinatorWorker(
    private val context: Context,
    params: WorkerParameters,
    private val syncConfigProvider: SyncConfigProvider,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TIMEOUT_THRESHOLD_MS = 5 * 60 * 1000L // 5分钟超时阈值
    }

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    private fun formatTimestamp(timeMs: Long): String = dateFormat.format(Date(timeMs))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val workerId = id.toString().takeLast(8)

        libLogI("════════════════════════════════════════")
        libLogI("🎯 同步协调器开始")
        libLogI("  工作ID: $workerId")
        libLogI("  开始时间: ${formatTimestamp(startTime)}")
        libLogI("════════════════════════════════════════")

        // 检查用户登录状态
        if (syncConfigProvider.username.isEmpty()) {
            libLogW("⚠️ 用户未登录，协调任务终止")
            libLogD("  username 为空，无法执行同步")
            return@withContext Result.failure()
        }

        libLogI("👤 当前用户: ${syncConfigProvider.username}")

        val syncStartTime = getCurrentTime()
        val lastSyncTime = syncConfigProvider.syncDataTime

        libLogI("📅 同步时间信息:")
        libLogI("  上次同步时间: ${lastSyncTime.ifEmpty { "首次同步" }}")
        libLogI("  本次同步开始: $syncStartTime")

        // 检查距离上次同步是否超过15分钟（定期同步监控）
        if (lastSyncTime.isNotEmpty()) {
            try {
                val lastTime = dateFormat.parse(lastSyncTime)?.time ?: 0
                val timeSinceLastSync = startTime - lastTime
                if (timeSinceLastSync > 15 * 60 * 1000) {
                    libLogW("⚠️ 距离上次同步已超过15分钟!")
                    libLogW("  间隔时间: ${timeSinceLastSync / 1000 / 60} 分钟")
                    libLogW("  可能存在定期同步未执行的问题")
                }
            } catch (e: Exception) {
                libLogD("  无法解析上次同步时间: ${e.message}")
            }
        }

        val workManager = WorkManager.getInstance(context)
        val sessionId = "自动同步-${YitIdHelper.nextId()}"

        libLogI("🔑 会话ID: $sessionId")

        // 获取所有需要执行的同步任务
        val allTasks = syncConfigProvider.getAllTask()
        libLogI("📋 任务列表: 共 ${allTasks.size} 个任务")

        allTasks.forEachIndexed { index, task ->
            libLogD("  [${index + 1}] ${task.title}")
            libLogD("      Worker: ${task.workerClass.simpleName}")
        }

        // 创建同步任务工作请求
        val parallelSyncTasks = allTasks.map { task ->
            createOneTimeWork(task.workerClass, lastSyncTime, sessionId)
        }

        // 检查是否有任务需要执行
        if (parallelSyncTasks.isEmpty()) {
            libLogI("📭 没有需要执行的同步任务")
            libLogI("  协调器正常结束")
            val duration = System.currentTimeMillis() - startTime
            libLogI("  耗时: ${duration}ms")
            libLogI("────────────────────────────────────────")
            return@withContext Result.success()
        }

        libLogI("🔧 构建任务执行链:")
        libLogI("  并行任务数: ${parallelSyncTasks.size}")
        libLogD("  后续任务: SyncSuccessUpdaterWorker (更新同步时间戳)")

        // 创建成功更新器工作
        val successUpdaterWork = OneTimeWorkRequestBuilder<SyncSuccessUpdaterWorker>()
            .setInputData(workDataOf(KEY_SYNC_START_TIME to syncStartTime))
            .build()

        try {
            // 构建并入队任务链
            var chain = workManager.beginWith(parallelSyncTasks[0])
            for (i in 1 until parallelSyncTasks.size) {
                chain = chain.then(parallelSyncTasks[i])
            }
            chain.then(successUpdaterWork).enqueue()

            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime

            // 检查协调器自身是否超时
            if (duration > TIMEOUT_THRESHOLD_MS) {
                libLogW("⏱️ 警告: 协调器任务耗时超过5分钟!")
                libLogW("  实际耗时: ${duration}ms (${duration / 1000}s)")
            }

            libLogI("✅ 任务链已成功入队")
            libLogI("  入队任务数: ${parallelSyncTasks.size + 1}")
            libLogI("  耗时: ${duration}ms")
            libLogI("  结束时间: ${formatTimestamp(endTime)}")
            libLogI("────────────────────────────────────────")

            return@withContext Result.success()
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime

            libLogE("💥 任务链入队失败", e)
            libLogE("  异常类型: ${e.javaClass.simpleName}")
            libLogE("  异常信息: ${e.message}")
            libLogE("  耗时: ${duration}ms")
            libLogE("  堆栈信息:\n${e.stackTraceToString()}")
            libLogI("────────────────────────────────────────")

            return@withContext Result.failure()
        }
    }

    private val format1: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    fun getCurrentTime(): String = format1.format(Date())

    /**
     * 创建一个带输入数据的一次性工作请求
     */
    private fun createOneTimeWork(
        workerClass: KClass<out ListenableWorker>,
        lastSyncTime: String,
        sessionId: String,
    ): OneTimeWorkRequest {
        val inputData = workDataOf(
            KEY_LAST_SYNC_TIME to lastSyncTime,
            KEY_SYNC_SESSION_ID to sessionId
        )

        libLogD("  创建工作请求: ${workerClass.simpleName}")
        libLogD("    lastSyncTime: $lastSyncTime")
        libLogD("    sessionId: $sessionId")

        return OneTimeWorkRequest.Builder(workerClass.java)
            .setInputData(inputData)
            .build()
    }
}