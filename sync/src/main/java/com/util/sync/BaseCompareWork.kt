package com.util.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.github.yitter.idgen.YitIdHelper
import com.util.sync.data.SyncLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.let

/**
 * @description
 * @author 杨帅林
 * @create 2025/8/29 14:22
 **/
const val KEY_LAST_SYNC_TIME = "KEY_LAST_SYNC_TIME"
const val KEY_SYNC_START_TIME = "KEY_SYNC_START_TIME"
const val KEY_SYNC_SESSION_ID = "KEY_SYNC_SESSION_ID"

abstract class BaseCompareWork<T : SyncableEntity, R : SyncRepository<T>>(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    // --- 由子类提供的抽象属性 ---
    abstract val workName: String
    abstract val workChineseName: String
    abstract val syncOptionName: String
    abstract val repository: R
    abstract val syncOptionInt: Int
    abstract val logger:SyncLogger
    abstract val syncConfig: SyncConfigProvider

    // --- 用于特殊处理的钩子方法，子类可以重写 ---

    /**
     * 在本地数据上传到服务器之前对其进行处理的钩子。
     * 默认实现不执行任何操作。
     * 子类可以重写此方法来处理文件上传等任务。
     * @return 处理后的实体，可能包含了远程文件的URL。
     */
    open suspend fun handleLocalDataForUpload(
        data: T,
        logger: SyncLogger,
        failureMessages: MutableList<String>,
        onLocalUpdate: (T) -> Unit = {},
        onRemoteUpdate: (T) -> Unit = {},
    ): T {
        return data // 默认：原样返回
    }

    /**
     * 在从服务器下载数据后对其进行处理的钩子。
     * 默认实现不执行任何操作。
     * 子类可以重写此方法来处理人脸特征提取等任务。
     * @return 处理后的实体，可能包含了新的本地数据。
     */
    open suspend fun handleRemoteDataForDownload(
        data: T,
        logger: SyncLogger,
        failureMessages: MutableList<String>,
        onLocalUpdate: (T) -> Unit = {},
        onRemoteUpdate: (T) -> Unit = {},
    ): T {
        return data // 默认：原样返回
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val sessionId = inputData.getString(KEY_SYNC_SESSION_ID) ?: "自动同步--${YitIdHelper.nextId()}"
            logger.setSessionId(sessionId)
            val lastSyncTime = inputData.getString("KEY_LAST_SYNC_TIME")
            val syncOption = SyncOption.fromInt(syncOptionInt)

            // 新增日志: 记录初始状态和参数，提供完整上下文。
            logger.info("✅ 开始执行同步任务: $workChineseName")
            logger.info("   - 会话 ID (Session ID): $sessionId")
            logger.info("   - 同步模式: ${syncOption.description}")
            logger.info("   - 上次同步时间: $lastSyncTime")

            if (lastSyncTime == null) {
                val errorMsg = "严重错误：未能获取到上次同步时间，任务中止。"
                logger.error(errorMsg)
                return@withContext Result.failure(createFailData(errorMsg))
            }

            if (syncOption == SyncOption.SYNC_OFF) {
                logger.warn("同步开关已关闭，任务未执行任何操作。") // 新增日志: 使用 WARN 级别记录跳过的工作。
                return@withContext Result.success(createSuccessData("同步已关闭，未执行任何操作。"))
            }

            val failureMessages = mutableListOf<String>()
            //创建一个Map来追踪上传成功后需要删除的本地文件
            // 键是实体ID，值是本地文件路径
            val filesToDeleteAfterSuccess = mutableMapOf<Long, String>()
            // 新增日志: 用于统计操作摘要
            val summaryStats = mutableMapOf("downloaded" to 0, "uploaded" to 0, "skipped" to 0)
            //处理上传成功后需要删除的本地文件
            fun handleFilesToDelete(processed: SyncableEntity,data: SyncableEntity){
                if (processed.getPhotoPath().isNullOrEmpty().not() && processed.getPhotoPath() != data.getPhotoPath()) {
                    data.getPhotoPath()?.let {p->
                        filesToDeleteAfterSuccess[data.id] = p
                    }
                }
            }

            try {
                val startTime = System.currentTimeMillis()
                var remoteIds: List<Long> = emptyList()
                var localIds: List<Long> = emptyList()

                // --- 步骤 1: 获取需要同步的ID列表 ---
                if (syncOption == SyncOption.SERVER_DOWNLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                    logger.info("⬇️ 开始获取服务端更新列表")
                    val remoteIdsResult = repository.remoteGetAfterUpdateTime(lastSyncTime)
                    if (remoteIdsResult.isError()) {
                        val errorMsg = "获取服务端 $syncOptionName ID列表失败: 错误码->${remoteIdsResult.code} ${remoteIdsResult.message}"
                        logger.error(errorMsg)
                        failureMessages.add(errorMsg)
                    } else {
                        remoteIds = remoteIdsResult.data ?: emptyList()
                        // 新增日志: 记录获取到的具体ID。
                        logger.info("   - 服务端发现 ${remoteIds.size} 个待更新项, ID: ${remoteIds.toLogString()}")
                    }
                }

                if (syncOption == SyncOption.DEVICE_UPLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                    logger.info("⬆️ 开始获取本地更新列表")
                    localIds = repository.localGetAfterUpdateTime(lastSyncTime)
                    // 新增日志: 记录获取到的具体ID。
                    logger.info("   - 本地发现 ${localIds.size} 个待更新项, ID: ${localIds.toLogString()}")
                }

                val allIds = (remoteIds + localIds).distinct()
                logger.info("🔄 本次需要处理的总项目数 (去重后): ${allIds.size} 个, ID: ${allIds.toLogString()}")

                if (allIds.isEmpty()) {
                    logger.info("没有需要同步的项目，任务提前完成。")
                    return@withContext Result.success(createSuccessData("没有需要同步的$syncOptionName"))
                }

                val updatedLocalData = mutableListOf<T>()
                val updatedRemoteData = mutableListOf<T>()

                // --- 步骤 2: 逐个处理项目 ---
                for (itemId in allIds) {
                    logger.info("--- 正在处理项目 ID: $itemId ---")

                    val remoteDataResult = if (syncOption == SyncOption.SERVER_DOWNLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                        repository.remoteGetById(itemId)
                    } else null

                    if (remoteDataResult?.isError() == true) {
                        val errorMsg = "获取服务端 $syncOptionName (ID: $itemId) 的详细信息失败: ${remoteDataResult.message}"
                        logger.error(errorMsg)
                        failureMessages.add(errorMsg)
                        continue // 跳过此项目
                    }
                    val remoteData = remoteDataResult?.data

                    val localData = if (syncOption == SyncOption.DEVICE_UPLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                        repository.localGetById(itemId)
                    } else null

                    // 新增日志: 详细记录决策逻辑
                    when (syncOption) {
                        SyncOption.DEVICE_UPLOAD -> localData?.let {
                            logger.info("   - 模式: [仅上传]. 准备上传本地数据。")
                            val processed = handleLocalDataForUpload(it, logger, failureMessages)
                            updatedRemoteData.add(processed)
                            if (processed != it) {
                                updatedLocalData.add(processed)
                                handleFilesToDelete(processed,it)
                            }
                            summaryStats["uploaded"] = summaryStats.getOrDefault("uploaded", 0) + 1
                        }

                        SyncOption.SERVER_DOWNLOAD -> remoteData?.let {
                            logger.info("   - 模式: [仅下载]. 准备下载服务端数据。")
                            val processed = handleRemoteDataForDownload(it, logger, failureMessages)
                            updatedLocalData.add(processed)
                            summaryStats["downloaded"] = summaryStats.getOrDefault("downloaded", 0) + 1
                        }

                        SyncOption.TWO_WAY_SYNC -> when {
                            remoteData == null && localData != null -> {
                                logger.info("   - 模式: [双向同步]. 服务端无此数据，本地存在。作为新数据上传。")
                                val processed = handleLocalDataForUpload(localData, logger, failureMessages)
                                updatedRemoteData.add(processed)
                                if (processed != localData) {
                                    updatedLocalData.add(processed)
                                    handleFilesToDelete(processed,localData)
                                }
                                summaryStats["uploaded"] = summaryStats.getOrDefault("uploaded", 0) + 1
                            }

                            remoteData != null && localData == null -> {
                                logger.info("   - 模式: [双向同步]. 本地无此数据，服务端存在。作为新数据下载。")
                                val processed = handleRemoteDataForDownload(remoteData, logger, failureMessages)
                                updatedLocalData.add(processed)
                                if (processed != remoteData) {
                                    updatedRemoteData.add(processed)
                                    handleFilesToDelete(processed,remoteData)
                                }
                                summaryStats["downloaded"] = summaryStats.getOrDefault("downloaded", 0) + 1
                            }

                            remoteData != null && localData != null -> {
                                logger.info("   - 模式: [双向同步]，需要进行冲突解决。")
                                logger.info("     - 时间戳对比: 服务端=${remoteData.updateTime} vs 本地=${localData.updateTime}")
                                when {
                                    remoteData.updateTime > localData.updateTime -> {
                                        logger.info("     - 决策: 服务端数据较新，执行下载操作。")
                                        val processed = handleRemoteDataForDownload(remoteData, logger, failureMessages)
                                        updatedLocalData.add(processed)
                                        if (processed != remoteData) updatedRemoteData.add(processed)
                                        summaryStats["downloaded"] = summaryStats.getOrDefault("downloaded", 0) + 1
                                    }

                                    localData.updateTime > remoteData.updateTime -> {
                                        logger.info("     - 决策: 本地数据较新，执行上传操作。")
                                        val processed = handleLocalDataForUpload(localData, logger, failureMessages)
                                        updatedRemoteData.add(processed)
                                        if (processed != localData) {
                                            updatedLocalData.add(processed)
                                            handleFilesToDelete(processed,localData)
                                        }
                                        summaryStats["uploaded"] = summaryStats.getOrDefault("uploaded", 0) + 1
                                    }

                                    else -> {
                                        logger.info("     - 决策: 时间戳相同，无需更新，跳过此项。")
                                        summaryStats["skipped"] = summaryStats.getOrDefault("skipped", 0) + 1
                                    }
                                }
                            }
                        }

                        else -> {}
                    }
                }

                // --- 步骤 3: 批量更新 ---
                if (updatedRemoteData.isNotEmpty()) {
                    val idsToUpdate = updatedRemoteData.map { it.id }.toLogString() // 新增日志: 获取待更新的ID列表
                    logger.info("☁️ 开始批量上传 ${updatedRemoteData.size} 个项目到服务器。ID: $idsToUpdate")
                    val remotePutResult = repository.remoteBatchUpsert(updatedRemoteData)
                    if (remotePutResult.isError()) {
                        val errorMsg = "批量上传到服务器失败: 错误码->${remotePutResult.code} ${remotePutResult.message}"
                        logger.error(errorMsg)
                        failureMessages.add(errorMsg)
                    } else {
                        // 变化4：仅在批量上传成功后，才执行文件删除操作
                        if (syncConfig.isDeleteLocalFile && filesToDeleteAfterSuccess.isNotEmpty()) {
                            logger.info("   - 开始删除已成功上传的本地文件...")
                            updatedRemoteData.forEach { updatedItem -> // 遍历已成功上传到服务器的项
                                filesToDeleteAfterSuccess[updatedItem.id]?.let { localPath ->
                                    try {
                                        val fileToDelete = File(localPath)
                                        if (fileToDelete.exists()) {
                                            if (fileToDelete.delete()) {
                                                logger.info("     - 已删除文件: $localPath")
                                            } else {
                                                logger.warn("     - 文件删除失败: $localPath")
                                            }
                                        }
                                    } catch (e: SecurityException) {
                                        logger.error("     - 删除文件时发生安全异常: $localPath, ${e.message}")
                                    }
                                }
                            }
                        }
                        logger.info("   - 批量上传成功。")
                    }
                }

                if (updatedLocalData.isNotEmpty()) {
                    val idsToUpdate = updatedLocalData.map { it.id }.toLogString() // 新增日志: 获取待更新的ID列表
                    logger.info("🗄️ 开始批量更新本地数据库 ${updatedLocalData.size} 个项目。ID: $idsToUpdate")
                    repository.localBatchUpsert(updatedLocalData)
                    logger.info("   - 本地批量更新成功。")
                }

                // --- 步骤 4: 最终总结 ---
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime
                logger.info("🏁 同步任务在 ${duration}ms 内执行完毕。")
                // 新增日志: 详细的操作总结
                logger.info("   - 操作总结: ${summaryStats["downloaded"]} 个下载, ${summaryStats["uploaded"]} 个上传, ${summaryStats["skipped"]} 个跳过。")

                if (failureMessages.isEmpty()) {
                    logger.info("✅ 同步任务成功完成。")
                    Result.success(createSuccessData("${syncOptionName}增量更新成功，耗时${duration}ms"))
                } else {
                    logger.error("❌ 同步任务完成，但出现 ${failureMessages.size} 个错误。")
                    Result.failure(createFailData(failureMessages.joinToString("\n")))
                }
            } catch (e: Exception) {
                logger.error("💥 任务 ($workName) 发生未捕获的异常: ${e.message}") // 新增日志: 记录完整的异常堆栈
                failureMessages.add("发生意外错误: ${e.message}")
                return@withContext Result.failure(createFailData(failureMessages.joinToString("\n")))
            }
        }
    }


    fun <T> List<T>.toLogString(limit: Int = 10): String {
        if (this.isEmpty()) return "[]"
        val truncated = this.take(limit)
        val suffix = if (this.size > limit) "..." else ""
        return truncated.joinToString(prefix = "[", postfix = "$suffix]")
    }

}

fun createSuccessData(message: String) = Data.Builder().putString("successMessage", message).build()
fun createFailData(message: String) = Data.Builder().putString("failMessage", message).build()