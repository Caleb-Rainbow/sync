package com.util.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.github.yitter.idgen.YitIdHelper
import com.util.sync.log.libLogD
import com.util.sync.log.libLogE
import com.util.sync.log.libLogI
import com.util.sync.log.libLogW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据同步基类
 * 负责设备与服务器之间的双向数据同步，支持多种同步模式
 * 
 * @param T 同步实体类型，必须实现 SyncableEntity 接口
 * @param R 仓库类型，必须实现 SyncRepository<T> 接口
 */
const val KEY_LAST_SYNC_TIME = "KEY_LAST_SYNC_TIME"
const val KEY_SYNC_START_TIME = "KEY_SYNC_START_TIME"
const val KEY_SYNC_SESSION_ID = "KEY_SYNC_SESSION_ID"

abstract class BaseCompareWork<T : SyncableEntity, R : SyncRepository<T>>(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    companion object {
        private const val TIMEOUT_THRESHOLD_MS = 5 * 60 * 1000L // 5分钟超时阈值
    }

    // --- 由子类提供的抽象属性 ---
    abstract val workName: String
    abstract val workChineseName: String
    abstract val syncOptionName: String
    abstract val repository: R
    abstract val syncOptionInt: Int
    abstract val syncConfig: SyncConfigProvider

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    private fun formatTimestamp(timeMs: Long): String = dateFormat.format(Date(timeMs))

    // --- 用于特殊处理的钩子方法，子类可以重写 ---

    /**
     * 在本地数据上传到服务器之前对其进行处理的钩子。
     * 默认实现不执行任何操作。
     * 子类可以重写此方法来处理文件上传等任务。
     * 
     * @param data 待处理的本地数据
     * @param failureMessages 失败消息列表，用于记录处理失败信息
     * @param onLocalUpdate 本地更新回调
     * @param onRemoteUpdate 远程更新回调
     * @return 处理后的实体，可能包含了远程文件的URL；返回 null 表示跳过此数据
     */
    open suspend fun handleLocalDataForUpload(
        data: T,
        failureMessages: MutableList<String>,
        onLocalUpdate: (T) -> Unit = {},
        onRemoteUpdate: (T) -> Unit = {},
    ): T? {
        return data // 默认：原样返回
    }

    /**
     * 在从服务器下载数据后对其进行处理的钩子。
     * 默认实现不执行任何操作。
     * 子类可以重写此方法来处理人脸特征提取等任务。
     * 
     * @param data 待处理的远程数据
     * @param failureMessages 失败消息列表，用于记录处理失败信息
     * @param onLocalUpdate 本地更新回调
     * @param onRemoteUpdate 远程更新回调
     * @return 处理后的实体，可能包含了新的本地数据；返回 null 表示跳过此数据
     */
    open suspend fun handleRemoteDataForDownload(
        data: T,
        failureMessages: MutableList<String>,
        onLocalUpdate: (T) -> Unit = {},
        onRemoteUpdate: (T) -> Unit = {},
    ): T? {
        return data // 默认：原样返回
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val workerId = id.toString().takeLast(8)
            val sessionId = inputData.getString(KEY_SYNC_SESSION_ID) ?: "自动同步--${YitIdHelper.nextId()}"
            val lastSyncTime = inputData.getString(KEY_LAST_SYNC_TIME)
            val syncOption = SyncOption.fromInt(syncOptionInt)

            // ═══════════════════════════════════════════════════════════
            // 任务开始日志
            // ═══════════════════════════════════════════════════════════
            libLogI("════════════════════════════════════════════════════════")
            libLogI("🔄 同步任务开始: $workChineseName")
            libLogI("════════════════════════════════════════════════════════")
            libLogI("📋 任务信息:")
            libLogI("  工作ID: $workerId")
            libLogI("  会话ID: $sessionId")
            libLogI("  任务名称: $workName")
            libLogI("  同步模式: ${syncOption.description}")
            libLogI("  开始时间: ${formatTimestamp(startTime)}")
            libLogI("  上次同步时间: ${lastSyncTime ?: "无 (首次同步)"}")
            libLogI("────────────────────────────────────────────────────────")

            // 参数校验
            if (lastSyncTime == null) {
                libLogE("❌ 严重错误: 未能获取到上次同步时间")
                libLogE("  任务中止，请检查 SyncCoordinatorWorker 是否正确传递参数")
                return@withContext Result.failure(createFailData("严重错误：未能获取到上次同步时间，任务中止。"))
            }

            // 同步开关检查
            if (syncOption == SyncOption.SYNC_OFF) {
                libLogW("⏭️ 同步开关已关闭，任务跳过")
                libLogI("  同步模式设置为 SYNC_OFF，不执行任何操作")
                libLogI("────────────────────────────────────────────────────────")
                return@withContext Result.success(createSuccessData("同步已关闭，未执行任何操作。"))
            }

            val failureMessages = mutableListOf<String>()
            // 创建一个 Map 来追踪上传成功后需要删除的本地文件
            val filesToDeleteAfterSuccess = mutableMapOf<Long, String>()
            // 用于统计操作摘要
            val summaryStats = mutableMapOf(
                "downloaded" to 0,
                "uploaded" to 0,
                "skipped" to 0,
                "failed_fetch" to 0
            )

            // 定义一个数据类来封装每个 ID 的获取结果
            data class FetchedData(val id: Long, val local: T?, val remote: T?, val error: String? = null)

            // 处理上传成功后需要删除的本地文件
            fun handleFilesToDelete(processed: SyncableEntity, data: SyncableEntity) {
                if (!processed.getPhotoPath().isNullOrEmpty() && processed.getPhotoPath() != data.getPhotoPath()) {
                    data.getPhotoPath()?.let { p ->
                        filesToDeleteAfterSuccess[data.id] = p
                    }
                }
            }

            try {
                var remoteIds: List<Long> = emptyList()
                var localIds: List<Long> = emptyList()

                // ═══════════════════════════════════════════════════════════
                // 步骤 1: 获取需要同步的 ID 列表
                // ═══════════════════════════════════════════════════════════
                libLogI("📥 步骤 1: 获取待同步 ID 列表")

                if (syncOption == SyncOption.SERVER_DOWNLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                    libLogI("  ⬇️ 正在获取服务端更新列表...")
                    val fetchStartTime = System.currentTimeMillis()
                    
                    val remoteIdsResult = repository.remoteGetAfterUpdateTime(lastSyncTime)
                    val fetchDuration = System.currentTimeMillis() - fetchStartTime
                    
                    if (remoteIdsResult.isError()) {
                        libLogE("  ❌ 获取服务端 ID 列表失败")
                        libLogE("    错误码: ${remoteIdsResult.code}")
                        libLogE("    错误信息: ${remoteIdsResult.message}")
                        libLogE("    请求耗时: ${fetchDuration}ms")
                        failureMessages.add("获取服务端 $syncOptionName ID列表失败: 错误码->${remoteIdsResult.code} ${remoteIdsResult.message}")
                    } else {
                        remoteIds = remoteIdsResult.data ?: emptyList()
                        libLogI("  ✅ 服务端更新列表获取成功")
                        libLogI("    数量: ${remoteIds.size} 个")
                        libLogI("    请求耗时: ${fetchDuration}ms")
                        if (remoteIds.isNotEmpty()) {
                            libLogD("    ID 列表: ${remoteIds.toLogString()}")
                        }
                    }
                }

                if (syncOption == SyncOption.DEVICE_UPLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                    libLogI("  ⬆️ 正在获取本地更新列表...")
                    val fetchStartTime = System.currentTimeMillis()
                    
                    localIds = repository.localGetAfterUpdateTime(lastSyncTime)
                    val fetchDuration = System.currentTimeMillis() - fetchStartTime
                    
                    libLogI("  ✅ 本地更新列表获取成功")
                    libLogI("    数量: ${localIds.size} 个")
                    libLogI("    查询耗时: ${fetchDuration}ms")
                    if (localIds.isNotEmpty()) {
                        libLogD("    ID 列表: ${localIds.toLogString()}")
                    }
                }

                val allIds = (remoteIds + localIds).distinct()
                libLogI("  � 汇总: 共 ${allIds.size} 个待处理项目 (去重后)")
                if (allIds.isNotEmpty()) {
                    libLogD("    完整 ID 列表: ${allIds.toLogString(20)}")
                }
                libLogI("────────────────────────────────────────────────────────")

                // 无待同步项目，提前结束
                if (allIds.isEmpty()) {
                    val endTime = System.currentTimeMillis()
                    val duration = endTime - startTime

                    if (failureMessages.isNotEmpty()) {
                        libLogE("⚠️ 任务完成但存在错误")
                        libLogE("  错误数量: ${failureMessages.size}")
                        failureMessages.forEachIndexed { index, msg ->
                            libLogE("  [${index + 1}] $msg")
                        }
                        libLogI("  总耗时: ${duration}ms")
                        libLogI("────────────────────────────────────────────────────────")
                        return@withContext Result.failure(createFailData(failureMessages.joinToString("\n")))
                    }

                    libLogI("✅ 没有需要同步的项目，任务提前完成")
                    libLogI("  总耗时: ${duration}ms")
                    libLogI("────────────────────────────────────────────────────────")
                    return@withContext Result.success(createSuccessData("没有需要同步的$syncOptionName"))
                }

                // ═══════════════════════════════════════════════════════════
                // 步骤 2: 分批并发获取所有项目的详细数据
                // ═══════════════════════════════════════════════════════════
                val batchSize = syncConfig.batchSize
                val totalBatches = (allIds.size + batchSize - 1) / batchSize
                
                libLogI("📦 步骤 2: 分批获取项目详情")
                libLogI("  批量大小: $batchSize")
                libLogI("  总批次数: $totalBatches")
                
                val fetchDetailStartTime = System.currentTimeMillis()
                
                val allFetchedData = allIds.chunked(batchSize).flatMapIndexed { batchIndex, batchIds ->
                    libLogD("  正在处理批次 ${batchIndex + 1}/$totalBatches (${batchIds.size} 项)...")
                    
                    // 对每个批次，并发获取数据
                    batchIds.map { itemId ->
                        async { // 为每个 ID 启动一个 async 协程
                            try {
                                val remoteDataResult = if (syncOption == SyncOption.SERVER_DOWNLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                                    repository.remoteGetById(itemId)
                                } else null

                                // 如果获取远程数据失败，记录下来但不要中断整个流程
                                if (remoteDataResult?.isError() == true) {
                                    libLogE("    ❌ 获取远程数据失败 (ID: $itemId)")
                                    libLogE("      错误: ${remoteDataResult.message}")
                                    failureMessages.add("获取服务端 $syncOptionName (ID: $itemId) 的详细信息失败: ${remoteDataResult.message}")
                                    return@async FetchedData(id = itemId, local = null, remote = null, error = remoteDataResult.message)
                                }

                                val localData = if (syncOption == SyncOption.DEVICE_UPLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                                    repository.localGetById(itemId)
                                } else null

                                FetchedData(id = itemId, local = localData, remote = remoteDataResult?.data)
                            } catch (e: Exception) {
                                libLogE("    💥 获取数据异常 (ID: $itemId)")
                                libLogE("      异常: ${e.message}")
                                failureMessages.add("获取 ID $itemId 数据时发生意外异常: ${e.message}")
                                FetchedData(id = itemId, local = null, remote = null, error = e.message)
                            }
                        }
                    }.awaitAll() // 等待当前批次的所有任务完成
                }
                
                val fetchDetailDuration = System.currentTimeMillis() - fetchDetailStartTime
                libLogI("  ✅ 数据获取完成，耗时: ${fetchDetailDuration}ms")
                libLogI("────────────────────────────────────────────────────────")

                // ═══════════════════════════════════════════════════════════
                // 步骤 3: 集中处理所有已获取的数据
                // ═══════════════════════════════════════════════════════════
                libLogI("⚙️ 步骤 3: 数据比对与处理")
                
                val updatedLocalData = mutableListOf<T>()
                val updatedRemoteData = mutableListOf<T>()

                for (fetched in allFetchedData) {
                    // 跳过在获取阶段就失败的项目
                    if (fetched.error != null) {
                        summaryStats["failed_fetch"] = summaryStats.getOrDefault("failed_fetch", 0) + 1
                        continue
                    }

                    val localData = fetched.local
                    val remoteData = fetched.remote
                    val itemId = fetched.id

                    // 详细记录决策逻辑
                    when (syncOption) {
                        SyncOption.DEVICE_UPLOAD -> localData?.let {
                            libLogD("  📤 [ID: $itemId] 模式: 仅上传")
                            val processed = handleLocalDataForUpload(it, failureMessages)
                            processed?.let { element ->
                                updatedRemoteData.add(element)
                                if (element != it) {
                                    updatedLocalData.add(element)
                                    handleFilesToDelete(element, it)
                                }
                            }
                            summaryStats["uploaded"] = summaryStats.getOrDefault("uploaded", 0) + 1
                        }

                        SyncOption.SERVER_DOWNLOAD -> remoteData?.let {
                            libLogD("  📥 [ID: $itemId] 模式: 仅下载")
                            val processed = handleRemoteDataForDownload(it, failureMessages)
                            processed?.let { element ->
                                updatedLocalData.add(element)
                            }
                            summaryStats["downloaded"] = summaryStats.getOrDefault("downloaded", 0) + 1
                        }

                        SyncOption.TWO_WAY_SYNC -> when {
                            remoteData == null && localData != null -> {
                                libLogD("  📤 [ID: $itemId] 双向同步: 服务端无此数据，执行上传")
                                val processed = handleLocalDataForUpload(localData, failureMessages)
                                processed?.let {
                                    updatedRemoteData.add(it)
                                    if (it != localData) {
                                        updatedLocalData.add(it)
                                        handleFilesToDelete(it, localData)
                                    }
                                }
                                summaryStats["uploaded"] = summaryStats.getOrDefault("uploaded", 0) + 1
                            }

                            remoteData != null && localData == null -> {
                                libLogD("  📥 [ID: $itemId] 双向同步: 本地无此数据，执行下载")
                                val processed = handleRemoteDataForDownload(remoteData, failureMessages)
                                processed?.let {
                                    updatedLocalData.add(it)
                                    if (it != remoteData) {
                                        updatedRemoteData.add(it)
                                        handleFilesToDelete(it, remoteData)
                                    }
                                }
                                summaryStats["downloaded"] = summaryStats.getOrDefault("downloaded", 0) + 1
                            }

                            remoteData != null && localData != null -> {
                                libLogD("  🔀 [ID: $itemId] 双向同步: 冲突解决")
                                libLogD("    服务端时间: ${remoteData.updateTime}")
                                libLogD("    本地时间: ${localData.updateTime}")
                                
                                when {
                                    remoteData.updateTime > localData.updateTime -> {
                                        libLogD("    决策: 服务端较新 → 下载")
                                        val processed = handleRemoteDataForDownload(remoteData, failureMessages)
                                        processed?.let {
                                            updatedLocalData.add(it)
                                            if (processed != remoteData) updatedRemoteData.add(it)
                                        }
                                        summaryStats["downloaded"] = summaryStats.getOrDefault("downloaded", 0) + 1
                                    }

                                    localData.updateTime > remoteData.updateTime -> {
                                        libLogD("    决策: 本地较新 → 上传")
                                        val processed = handleLocalDataForUpload(localData, failureMessages)
                                        processed?.let {
                                            updatedRemoteData.add(it)
                                            if (it != localData) {
                                                updatedLocalData.add(it)
                                                handleFilesToDelete(it, localData)
                                            }
                                        }
                                        summaryStats["uploaded"] = summaryStats.getOrDefault("uploaded", 0) + 1
                                    }

                                    else -> {
                                        libLogD("    决策: 时间相同 → 跳过")
                                        summaryStats["skipped"] = summaryStats.getOrDefault("skipped", 0) + 1
                                    }
                                }
                            }
                        }
                    }
                }
                
                libLogI("  ✅ 数据处理完成")
                libLogI("  待上传: ${updatedRemoteData.size} 项")
                libLogI("  待更新本地: ${updatedLocalData.size} 项")
                libLogI("────────────────────────────────────────────────────────")

                // ═══════════════════════════════════════════════════════════
                // 步骤 4: 批量更新
                // ═══════════════════════════════════════════════════════════
                libLogI("💾 步骤 4: 批量数据更新")
                
                // 上传到服务器
                if (updatedRemoteData.isNotEmpty()) {
                    val idsToUpdate = updatedRemoteData.map { it.id }.toLogString()
                    libLogI("  ☁️ 正在上传 ${updatedRemoteData.size} 个项目到服务器...")
                    libLogD("    ID 列表: $idsToUpdate")
                    
                    val uploadStartTime = System.currentTimeMillis()
                    val remotePutResult = repository.remoteBatchUpsert(updatedRemoteData)
                    val uploadDuration = System.currentTimeMillis() - uploadStartTime
                    
                    if (remotePutResult.isError()) {
                        libLogE("  ❌ 批量上传失败!")
                        libLogE("    错误码: ${remotePutResult.code}")
                        libLogE("    错误信息: ${remotePutResult.message}")
                        libLogE("    请求耗时: ${uploadDuration}ms")
                        failureMessages.add("批量上传到服务器失败: 错误码->${remotePutResult.code} ${remotePutResult.message}")
                    } else {
                        libLogI("  ✅ 批量上传成功")
                        libLogI("    上传数量: ${updatedRemoteData.size}")
                        libLogI("    请求耗时: ${uploadDuration}ms")
                        
                        // 仅在批量上传成功后，才执行文件删除操作
                        if (syncConfig.isDeleteLocalFile && filesToDeleteAfterSuccess.isNotEmpty()) {
                            libLogI("  🗑️ 正在清理已上传的本地文件...")
                            var deletedCount = 0
                            var failedCount = 0
                            
                            updatedRemoteData.forEach { updatedItem ->
                                filesToDeleteAfterSuccess[updatedItem.id]?.let { localPath ->
                                    try {
                                        val fileToDelete = File(localPath)
                                        if (fileToDelete.exists()) {
                                            if (fileToDelete.delete()) {
                                                libLogD("    ✓ 已删除: $localPath")
                                                deletedCount++
                                            } else {
                                                libLogW("    ✗ 删除失败: $localPath")
                                                failedCount++
                                            }
                                        }
                                    } catch (e: SecurityException) {
                                        libLogE("    💥 删除异常: $localPath")
                                        libLogE("      错误: ${e.message}")
                                        failedCount++
                                    }
                                }
                            }
                            libLogI("    文件清理完成: 成功 $deletedCount, 失败 $failedCount")
                        }
                    }
                }

                // 更新本地数据库
                if (updatedLocalData.isNotEmpty()) {
                    val idsToUpdate = updatedLocalData.map { it.id }.toLogString()
                    libLogI("  🗄️ 正在更新本地数据库 ${updatedLocalData.size} 个项目...")
                    libLogD("    ID 列表: $idsToUpdate")
                    
                    val localUpdateStartTime = System.currentTimeMillis()
                    repository.localBatchUpsert(updatedLocalData)
                    val localUpdateDuration = System.currentTimeMillis() - localUpdateStartTime
                    
                    libLogI("  ✅ 本地更新成功")
                    libLogI("    更新数量: ${updatedLocalData.size}")
                    libLogI("    操作耗时: ${localUpdateDuration}ms")
                }
                
                libLogI("────────────────────────────────────────────────────────")

                // ═══════════════════════════════════════════════════════════
                // 步骤 5: 任务完成总结
                // ═══════════════════════════════════════════════════════════
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime

                // 超时警告
                if (duration > TIMEOUT_THRESHOLD_MS) {
                    libLogW("⏱️ 警告: 任务耗时超过5分钟!")
                    libLogW("  实际耗时: ${duration}ms (${duration / 1000}s)")
                    libLogW("  请检查网络状况或数据量是否过大")
                }

                libLogI("════════════════════════════════════════════════════════")
                libLogI("📊 同步任务完成: $workChineseName")
                libLogI("════════════════════════════════════════════════════════")
                libLogI("📈 操作统计:")
                libLogI("  下载: ${summaryStats["downloaded"]} 项")
                libLogI("  上传: ${summaryStats["uploaded"]} 项")
                libLogI("  跳过: ${summaryStats["skipped"]} 项")
                if (summaryStats["failed_fetch"]!! > 0) {
                    libLogW("  获取失败: ${summaryStats["failed_fetch"]} 项")
                }
                libLogI("⏱️ 时间统计:")
                libLogI("  总耗时: ${duration}ms")
                libLogI("  结束时间: ${formatTimestamp(endTime)}")

                if (failureMessages.isEmpty()) {
                    libLogI("✅ 任务状态: 成功")
                    libLogI("════════════════════════════════════════════════════════")
                    Result.success(createSuccessData("${syncOptionName}增量更新成功，耗时${duration}ms"))
                } else {
                    libLogE("❌ 任务状态: 部分失败")
                    libLogE("  错误数量: ${failureMessages.size}")
                    failureMessages.forEachIndexed { index, msg ->
                        libLogE("  [${index + 1}] $msg")
                    }
                    libLogI("════════════════════════════════════════════════════════")
                    Result.failure(createFailData(failureMessages.joinToString("\n")))
                }
            } catch (e: Exception) {
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime
                
                libLogE("════════════════════════════════════════════════════════")
                libLogE("💥 同步任务发生未捕获异常: $workChineseName")
                libLogE("════════════════════════════════════════════════════════")
                libLogE("异常类型: ${e.javaClass.simpleName}")
                libLogE("异常信息: ${e.message}")
                libLogE("任务耗时: ${duration}ms")
                libLogE("堆栈信息:")
                libLogE(e.stackTraceToString())
                libLogE("════════════════════════════════════════════════════════")
                
                failureMessages.add("发生意外错误: ${e.message}")
                return@withContext Result.failure(createFailData(failureMessages.joinToString("\n")))
            }
        }
    }

    /**
     * 将列表转换为日志友好的字符串格式
     * 超过限制数量时会截断并添加省略号
     */
    fun <T> List<T>.toLogString(limit: Int = 10): String {
        if (this.isEmpty()) return "[]"
        val truncated = this.take(limit)
        val suffix = if (this.size > limit) "..." else ""
        return truncated.joinToString(prefix = "[", postfix = "$suffix]")
    }
}

fun createSuccessData(message: String) = Data.Builder().putString("successMessage", message).build()
fun createFailData(message: String) = Data.Builder().putString("failMessage", message).build()