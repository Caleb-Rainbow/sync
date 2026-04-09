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
import com.util.sync.SyncTimeUtils
import com.util.sync.SyncTaskDefinition
import com.util.sync.log.libLogD
import com.util.sync.log.libLogE
import com.util.sync.log.libLogI
import com.util.sync.log.libLogW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * 3. 失败任务在协调器内部重试（仅重试失败的任务，跳过已成功的）
 * 4. 只有所有任务都成功时，才触发 SyncSuccessUpdaterWorker 更新同步时间戳
 */
class SyncCoordinatorWorker(
    private val context: Context,
    params: WorkerParameters,
    private val syncConfigProvider: SyncConfigProvider,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TIMEOUT_THRESHOLD_MS = 5 * 60 * 1000L // 5分钟超时阈值
        private const val MAX_RETRY_COUNT = 3 // 最大重试次数
        private const val RETRY_BASE_DELAY_MS = 30_000L // 重试基础延迟（毫秒）
    }

    /**
     * 将 epoch 毫秒格式化为 UTC 时间字符串，用于日志输出。
     */
    private fun formatTimestamp(timeMs: Long): String =
        SyncTimeUtils.formatTimestamp(timeMs)

    /**
     * 解析上次同步时间字符串为 epoch 毫秒。
     * 委托给 SyncTimeUtils 统一处理。
     */
    private fun parseLastSyncTime(time: String): Long =
        SyncTimeUtils.parseUpdateTime(time) ?: 0L

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

        val syncStartTime = SyncTimeUtils.run {
            java.time.LocalDateTime.now(UTC_ZONE).format(STANDARD_FORMATTER)
        }
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

        libLogI("🔧 开始执行任务:")
        libLogI("  执行策略: 顺序执行，内部重试仅针对失败任务")
        libLogI("────────────────────────────────────────")

        // 记录所有任务的执行结果
        data class TaskResult(
            val taskName: String,
            val workerClassName: String,
            val task: SyncTaskDefinition,
            val success: Boolean,
            val durationMs: Long,
            val errorMessage: String? = null
        )

        val allTaskResults = mutableListOf<TaskResult>()
        var remainingTasks = allTasks.toList()
        var allTasksSucceeded = false

        // 内部重试循环：仅重试失败的任务，跳过已成功的
        for (attempt in 0..MAX_RETRY_COUNT) {
            if (attempt > 0) {
                val delayMs = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                libLogI("🔄 第 $attempt 次重试，等待 ${delayMs / 1000}s 后重试 ${remainingTasks.size} 个失败任务...")
                delay(delayMs)
            }

            libLogI("📌 ${if (attempt == 0) "首次执行" else "第 $attempt 次重试"}: ${remainingTasks.size} 个任务")

            val attemptResults = mutableListOf<TaskResult>()

            for ((index, task) in remainingTasks.withIndex()) {
                val taskStartTime = System.currentTimeMillis()
                val taskName = task.title
                val workerClassName = task.workerClass.simpleName ?: "Unknown"

                libLogI("  [${index + 1}/${remainingTasks.size}] 开始执行: $taskName")

                try {
                    // 创建并入队单个任务
                    val workRequest = createOneTimeWork(task.workerClass, lastSyncTime, sessionId)
                    workManager.enqueue(workRequest)

                    // 使用 Flow 等待任务完成（阻塞直到任务进入终态）
                    val workInfo = workManager.getWorkInfoByIdFlow(workRequest.id)
                        .first { it != null && it.state.isFinished }
                    val taskDuration = System.currentTimeMillis() - taskStartTime

                    when (workInfo?.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            libLogI("    ✅ 任务成功: $taskName, 耗时: ${taskDuration}ms")
                            attemptResults.add(TaskResult(taskName, workerClassName, task, true, taskDuration))
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMsg = workInfo.outputData.getString("failMessage") ?: "未知错误"
                            libLogE("    ❌ 任务失败: $taskName, 耗时: ${taskDuration}ms")
                            libLogE("      错误信息: $errorMsg")
                            attemptResults.add(TaskResult(taskName, workerClassName, task, false, taskDuration, errorMsg))
                        }
                        WorkInfo.State.CANCELLED -> {
                            libLogW("    ⚠️ 任务被取消: $taskName, 耗时: ${taskDuration}ms")
                            attemptResults.add(TaskResult(taskName, workerClassName, task, false, taskDuration, "任务被取消"))
                        }
                        else -> {
                            libLogW("    ⚠️ 任务状态异常: $taskName, 状态: ${workInfo?.state}, 耗时: ${taskDuration}ms")
                            attemptResults.add(TaskResult(taskName, workerClassName, task, false, taskDuration, "状态异常: ${workInfo?.state}"))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val taskDuration = System.currentTimeMillis() - taskStartTime
                    libLogE("    💥 任务执行异常: $taskName", e)
                    libLogE("      异常类型: ${e.javaClass.simpleName}")
                    libLogE("      异常信息: ${e.message}")
                    attemptResults.add(TaskResult(taskName, workerClassName, task, false, taskDuration, e.message))
                }
            }

            allTaskResults.addAll(attemptResults)

            val failedResults = attemptResults.filter { !it.success }
            if (failedResults.isEmpty()) {
                allTasksSucceeded = true
                break
            }

            if (attempt < MAX_RETRY_COUNT) {
                val failedNames = failedResults.map { it.taskName }.toSet()
                remainingTasks = remainingTasks.filter { it.title in failedNames }
                libLogW("  ⚠️ ${failedResults.size} 个任务失败，将在重试时跳过已成功的任务")
            } else {
                libLogW("  ❌ 已达到最大重试次数 ($MAX_RETRY_COUNT)，以下任务仍然失败:")
                failedResults.forEach { libLogW("    - ${it.taskName}: ${it.errorMessage}") }
            }

            libLogD("────────────────────────────────────────")
        }

        // 输出任务执行汇总
        libLogI("════════════════════════════════════════")
        libLogI("📊 任务执行汇总:")
        val successCount = allTaskResults.count { it.success }
        val failCount = allTaskResults.count { !it.success }
        libLogI("  成功: $successCount, 失败: $failCount, 总计: ${allTaskResults.size}")

        allTaskResults.forEachIndexed { index, result ->
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

        return@withContext if (allTasksSucceeded) Result.success() else Result.failure()
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
