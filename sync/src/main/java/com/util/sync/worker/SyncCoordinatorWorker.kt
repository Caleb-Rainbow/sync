package com.util.sync.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * 同步协调器工作器
 * 负责调度和编排所有同步任务，顺序执行每个任务
 * 
 * 设计原则：
 * 1. 任务顺序执行，避免并发带来的性能问题
 * 2. 单个任务失败不影响后续任务的执行
 * 3. 只有所有任务都成功时，才触发 SyncSuccessUpdaterWorker 更新同步时间戳
 */
class SyncCoordinatorWorker(
    private val context: Context,
    params: WorkerParameters,
    private val syncConfigProvider: SyncConfigProvider,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TIMEOUT_THRESHOLD_MS = 5 * 60 * 1000L // 5分钟超时阈值
        private const val MAX_RETRY_COUNT = 3 // 最大重试次数
        private val UTC_ZONE = java.time.ZoneOffset.UTC
        private const val KEY_SUCCEEDED_WORKERS = "KEY_SUCCEEDED_WORKERS"
        private const val SEPARATOR = ","
    }

    private val logDateFormatter by lazy {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    private val fallbackDateFormatter by lazy {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    /**
     * 将 epoch 毫秒格式化为 UTC 时间字符串，用于日志输出。
     */
    private fun formatTimestamp(timeMs: Long): String =
        java.time.Instant.ofEpochMilli(timeMs)
            .atZone(UTC_ZONE)
            .format(logDateFormatter)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val workerId = id.toString().takeLast(8)

        libLogI("════════════════════════════════════════")
        libLogI("🎯 同步协调器开始")
        libLogI("  工作ID: $workerId")
        libLogI("  开始时间: ${formatTimestamp(startTime)}")
        libLogI("  重试次数: $runAttemptCount/$MAX_RETRY_COUNT")
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
                val lastTime = parseLastSyncTime(lastSyncTime)
                if (lastTime > 0L) {
                    val timeSinceLastSync = startTime - lastTime
                    if (timeSinceLastSync > 15 * 60 * 1000) {
                        libLogW("⚠️ 距离上次同步已超过15分钟!")
                        libLogW("  间隔时间: ${timeSinceLastSync / 1000 / 60} 分钟")
                        libLogW("  可能存在定期同步未执行的问题")
                    }
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

        // 检查是否有任务需要执行
        if (allTasks.isEmpty()) {
            libLogI("📭 没有需要执行的同步任务")
            libLogI("  协调器正常结束")
            val duration = System.currentTimeMillis() - startTime
            libLogI("  耗时: ${duration}ms")
            libLogI("────────────────────────────────────────")
            return@withContext Result.success()
        }

        // 从 inputData 中恢复上一次已成功的 Worker 类名列表（重试时跳过）
        val previousSucceededClasses = parseSucceededWorkersFromInput()
        if (previousSucceededClasses.isNotEmpty()) {
            libLogI("🔄 重试模式: 跳过已成功的 ${previousSucceededClasses.size} 个任务")
            previousSucceededClasses.forEach { libLogD("  ✓ 已跳过: $it") }
        }

        // 筛选出需要执行的任务（排除已成功的）
        val pendingTasks = allTasks.filter { task ->
            task.workerClass.java.name !in previousSucceededClasses
        }

        if (pendingTasks.isEmpty()) {
            libLogI("📋 所有任务已在前次执行中成功，无需重新执行")
        } else {
            libLogI("🔧 开始顺序执行任务:")
            libLogI("  待执行: ${pendingTasks.size}，跳过(已成功): ${previousSucceededClasses.size}")
            libLogD("  执行策略: 顺序执行，单个失败不阻塞后续任务")
            libLogI("────────────────────────────────────────")
        }

        // 记录每个任务的执行结果
        data class TaskResult(
            val taskName: String,
            val workerClassName: String,
            val success: Boolean,
            val durationMs: Long,
            val errorMessage: String? = null
        )

        val taskResults = mutableListOf<TaskResult>()
        var allTasksSucceeded = true

        // 顺序执行每个任务
        for ((index, task) in pendingTasks.withIndex()) {
            val taskStartTime = System.currentTimeMillis()
            val taskName = task.title
            val workerClassName = task.workerClass.simpleName ?: "Unknown"

            libLogI("📌 [${index + 1}/${pendingTasks.size}] 开始执行: $taskName")
            libLogD("  Worker: $workerClassName")

            try {
                // 创建并入队单个任务
                val workRequest = createOneTimeWork(task.workerClass, lastSyncTime, sessionId)

                // 入队任务（不需要等待入队完成，直接监听任务状态）
                workManager.enqueue(workRequest)

                // 使用 Flow 等待任务完成（阻塞直到任务进入终态）
                val workInfo = workManager.getWorkInfoByIdFlow(workRequest.id)
                    .first { it != null && it.state.isFinished }
                val taskDuration = System.currentTimeMillis() - taskStartTime

                when (workInfo?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        libLogI("  ✅ 任务成功: $taskName, 耗时: ${taskDuration}ms")
                        taskResults.add(TaskResult(taskName, workerClassName, true, taskDuration))
                    }
                    WorkInfo.State.FAILED -> {
                        val errorMsg = workInfo.outputData.getString("failMessage") ?: "未知错误"
                        libLogE("  ❌ 任务失败: $taskName, 耗时: ${taskDuration}ms")
                        libLogE("    错误信息: $errorMsg")
                        taskResults.add(TaskResult(taskName, workerClassName, false, taskDuration, errorMsg))
                        allTasksSucceeded = false
                    }
                    WorkInfo.State.CANCELLED -> {
                        libLogW("  ⚠️ 任务被取消: $taskName, 耗时: ${taskDuration}ms")
                        taskResults.add(TaskResult(taskName, workerClassName, false, taskDuration, "任务被取消"))
                        allTasksSucceeded = false
                    }
                    else -> {
                        libLogW("  ⚠️ 任务状态异常: $taskName, 状态: ${workInfo?.state}, 耗时: ${taskDuration}ms")
                        taskResults.add(TaskResult(taskName, workerClassName, false, taskDuration, "状态异常: ${workInfo?.state}"))
                        allTasksSucceeded = false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val taskDuration = System.currentTimeMillis() - taskStartTime
                libLogE("  💥 任务执行异常: $taskName", e)
                libLogE("    异常类型: ${e.javaClass.simpleName}")
                libLogE("    异常信息: ${e.message}")
                taskResults.add(TaskResult(taskName, workerClassName, false, taskDuration, e.message))
                allTasksSucceeded = false
            }

            libLogD("────────────────────────────────────────")
        }

        // 输出任务执行汇总
        libLogI("════════════════════════════════════════")
        libLogI("📊 任务执行汇总:")
        val successCount = taskResults.count { it.success }
        val failCount = taskResults.count { !it.success }
        libLogI("  成功: $successCount, 失败: $failCount, 总计: ${taskResults.size}")

        taskResults.forEachIndexed { index, result ->
            val statusIcon = if (result.success) "✅" else "❌"
            libLogI("  [$statusIcon] ${result.taskName}: ${result.durationMs}ms")
            if (!result.success && result.errorMessage != null) {
                libLogD("      错误: ${result.errorMessage}")
            }
        }
        libLogI("────────────────────────────────────────")

        // 根据任务执行结果决定是否触发 SyncSuccessUpdaterWorker
        if (allTasksSucceeded) {
            libLogI("🏆 所有任务执行成功，准备更新同步时间戳")

            try {
                // 创建并执行成功更新器
                val successUpdaterWork = OneTimeWorkRequestBuilder<SyncSuccessUpdaterWorker>()
                    .setInputData(workDataOf(KEY_SYNC_START_TIME to syncStartTime))
                    .build()

                workManager.enqueue(successUpdaterWork)
                val updaterInfo = workManager.getWorkInfoByIdFlow(successUpdaterWork.id)
                    .first { it != null && it.state.isFinished }

                if (updaterInfo?.state == WorkInfo.State.SUCCEEDED) {
                    libLogI("  ✅ SyncSuccessUpdaterWorker 执行成功")
                } else {
                    libLogW("  ⚠️ SyncSuccessUpdaterWorker 执行状态: ${updaterInfo?.state}")
                }
            } catch (e: Exception) {
                libLogE("  💥 SyncSuccessUpdaterWorker 执行异常", e)
            }
        } else {
            libLogW("⚠️ 存在失败的任务，跳过更新同步时间戳")
            libLogW("  失败任务将在下次同步时重试")
        }

        val endTime = System.currentTimeMillis()
        val totalDuration = endTime - startTime

        // 检查协调器自身是否超时
        if (totalDuration > TIMEOUT_THRESHOLD_MS) {
            libLogW("⏱️ 警告: 协调器任务耗时超过5分钟!")
            libLogW("  实际耗时: ${totalDuration}ms (${totalDuration / 1000}s)")
        }

        libLogI("════════════════════════════════════════")
        libLogI("🎯 同步协调器结束")
        libLogI("  总耗时: ${totalDuration}ms")
        libLogI("  结束时间: ${formatTimestamp(endTime)}")
        libLogI("  最终状态: ${if (allTasksSucceeded) "全部成功" else "部分失败"}")
        libLogI("════════════════════════════════════════")

        // 协调器根据任务结果决定返回值
        // 存在失败任务时返回 retry，让 WorkManager 按退避策略自动重试
        // 超过最大重试次数后返回 failure，避免无限重试
        // 将已成功的 Worker 类名列表附加到 outputData，供重试时跳过
        return@withContext if (allTasksSucceeded) {
            Result.success()
        } else if (runAttemptCount < MAX_RETRY_COUNT) {
            // 收集本次已成功的 Worker 类名 + 之前已成功的
            val succeededWorkers = (previousSucceededClasses +
                    taskResults.filter { it.success }.map { it.workerClassName })
                .distinct()
                .joinToString(SEPARATOR)

            libLogI("  ⚠️ 存在失败任务 (重试 ${runAttemptCount + 1}/$MAX_RETRY_COUNT)，协调器返回 retry")
            Result.retry()
        } else {
            libLogW("  ❌ 已达到最大重试次数 ($MAX_RETRY_COUNT)，协调器返回 failure")
            Result.failure()
        }
    }

    /**
     * 从 inputData 中解析上一次已成功的 Worker 类名列表。
     * 注意：WorkManager retry 时 inputData 保持不变，无法通过 inputData 传递重试状态。
     * 因此重试时会重新执行所有任务（依赖子任务的幂等性保证安全性）。
     */
    private fun parseSucceededWorkersFromInput(): Set<String> {
        val inputStr = inputData.getString(KEY_SUCCEEDED_WORKERS) ?: ""
        return inputStr.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    fun getCurrentTime(): String =
        java.time.LocalDateTime.now(UTC_ZONE).format(logDateFormatter)

    /**
     * 解析上次同步时间字符串为 epoch 毫秒。
     * 优先按 UTC 解析，失败后回退到系统时区（兼容旧数据）。
     */
    private fun parseLastSyncTime(time: String): Long {
        // 优先 UTC
        try {
            return java.time.LocalDateTime.parse(time, logDateFormatter)
                .toInstant(UTC_ZONE)
                .toEpochMilli()
        } catch (_: Exception) { }
        try {
            return java.time.LocalDateTime.parse(time, fallbackDateFormatter)
                .toInstant(UTC_ZONE)
                .toEpochMilli()
        } catch (_: Exception) { }
        // 回退系统时区
        try {
            return java.time.LocalDateTime.parse(time, logDateFormatter)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) { }
        try {
            return java.time.LocalDateTime.parse(time, fallbackDateFormatter)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) { }
        return 0L
    }

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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 创建协调器自身的 WorkRequest，带退避策略。
     * 供外部调用方使用。
     */
    fun createCoordinatorWork(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncCoordinatorWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
}