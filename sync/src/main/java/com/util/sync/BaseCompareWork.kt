package com.util.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.yitter.idgen.YitIdHelper
import com.util.sync.log.libLogD
import com.util.sync.log.libLogDLazy
import com.util.sync.log.libLogE
import com.util.sync.log.libLogI
import com.util.sync.log.libLogTag
import com.util.sync.log.libLogW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 数据同步基类
 * 负责设备与服务器之间的双向数据同步，支持多种同步模式
 *
 * @param T 同步实体类型，必须实现 SyncableEntity 接口
 * @param R 仓库类型，必须实现 SyncRepository<T> 接口
 */
abstract class BaseCompareWork<T : SyncableEntity, R : SyncRepository<T>>(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    companion object {
        private const val TIMEOUT_THRESHOLD_MS = 5 * 60 * 1000L // 5分钟超时阈值
        private const val TIME_SKEW_THRESHOLD_MS = 3000L // 3秒时钟偏差容忍阈值
        private const val MAX_ID_QUERY_CONCURRENCY = 8 // ID 单查模式最大并发请求数
    }

    // --- 由子类提供的抽象属性 ---
    abstract val workName: String
    abstract val workChineseName: String
    abstract val syncOptionName: String
    abstract val repository: R
    abstract val syncOptionInt: Int
    abstract val syncConfig: SyncConfigProvider

    // 缓存 TAG，避免每次日志调用时通过反射获取类名
    private val cachedTag: String by lazy { this.libLogTag }

    /**
     * 将 epoch 毫秒格式化为 UTC 时间字符串（带毫秒），用于日志输出。
     * 使用 UTC 避免设备时区变更导致的时间错乱。
     */
    private fun formatTimestamp(timeMs: Long): String = SyncTimeUtils.formatTimestamp(timeMs)

    /**
     * 将 updateTime 字符串解析为 epoch 毫秒数，用于可靠的数值比较。
     * 委托给 [SyncTimeUtils.parseUpdateTime]。
     */
    private fun parseUpdateTime(time: String): Long? = SyncTimeUtils.parseUpdateTime(time)

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

            // 根据 syncMode 选择同步策略
            val syncMode = syncConfig.syncMode
            libLogI("📍 同步策略: ${if (syncMode == 1) "批量模式" else "ID单查模式"}")
            libLogI("────────────────────────────────────────────────────────")

            return@withContext if (syncMode == 1) {
                executeBatchMode(startTime, sessionId, lastSyncTime, syncOption)
            } else {
                executeIdQueryMode(startTime, lastSyncTime, syncOption)
            }
        }
    }

    /**
     * 旧模式：ID 单查模式
     * 先获取 ID 列表，然后逐个获取详情，最后批量更新
     */
    @Suppress("DEPRECATION")
    private suspend fun executeIdQueryMode(
        startTime: Long,
        lastSyncTime: String,
        syncOption: SyncOption
    ): Result = withContext(Dispatchers.IO) {
        val failureMessages = mutableListOf<String>()
        val filesToDeleteAfterSuccess = mutableMapOf<Long, String>()
        val stats = SyncStats()

        data class FetchedData(val id: Long, val local: T?, val remote: T?, val error: String? = null)

        try {
            var remoteIds: List<Long> = emptyList()
            var localIds: List<Long> = emptyList()

            libLogI("📥 步骤 1: 获取待同步 ID 列表")

            if (syncOption == SyncOption.SERVER_DOWNLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                libLogI("  ⬇️ 正在获取服务端更新列表...")
                val fetchStartTime = System.currentTimeMillis()
                val remoteIdsResult = repository.remoteGetAfterUpdateTime(lastSyncTime)
                val fetchDuration = System.currentTimeMillis() - fetchStartTime

                if (remoteIdsResult.isError()) {
                    libLogE("  ❌ 获取服务端 ID 列表失败: ${remoteIdsResult.message}")
                    return@withContext Result.failure(createFailData("获取服务端 ID列表失败: ${remoteIdsResult.message}"))
                } else {
                    remoteIds = remoteIdsResult.data ?: emptyList()
                    libLogI("  ✅ 服务端: ${remoteIds.size} 个，耗时: ${fetchDuration}ms")
                }
            }

            if (syncOption == SyncOption.DEVICE_UPLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                libLogI("  ⬆️ 正在获取本地更新列表...")
                val fetchStartTime = System.currentTimeMillis()
                localIds = repository.localGetAfterUpdateTime(lastSyncTime)
                val fetchDuration = System.currentTimeMillis() - fetchStartTime
                libLogI("  ✅ 本地: ${localIds.size} 个，耗时: ${fetchDuration}ms")
            }

            val allIds = (remoteIds + localIds).distinct()
            libLogI("  📋 汇总: 共 ${allIds.size} 个待处理项目")

            if (allIds.isEmpty()) {
                return@withContext if (failureMessages.isEmpty()) {
                    Result.success(createSuccessData("没有需要同步的$syncOptionName"))
                } else {
                    Result.failure(createFailData(failureMessages.joinToString("\n")))
                }
            }

            libLogI("📦 步骤 2: 分批获取项目详情")
            val batchSize = syncConfig.batchSize
            val fetchDetailStartTime = System.currentTimeMillis()

            // 使用 Semaphore 限制并发数，避免大量并发网络请求
            val maxConcurrency = minOf(batchSize.coerceAtLeast(1), MAX_ID_QUERY_CONCURRENCY)
            val semaphore = Semaphore(maxConcurrency)
            libLogD("  并发控制: 最大并发数 $maxConcurrency")

            val allFetchedData = allIds.chunked(batchSize).flatMapIndexed { batchIndex, batchIds ->
                batchIds.map { itemId ->
                    async {
                        semaphore.withPermit {
                            try {
                                val remoteDataResult = if (syncOption == SyncOption.SERVER_DOWNLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                                    repository.remoteGetById(itemId)
                                } else null

                                if (remoteDataResult?.isError() == true) {
                                    // 不在 async 中直接操作 failureMessages，通过 error 字段传递
                                    return@withPermit FetchedData(id = itemId, local = null, remote = null, error = "获取远程数据失败 (ID: $itemId): ${remoteDataResult.message}")
                                }

                                val localData = if (syncOption == SyncOption.DEVICE_UPLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                                    repository.localGetById(itemId)
                                } else null

                                FetchedData(id = itemId, local = localData, remote = remoteDataResult?.data)
                            } catch (e: Exception) {
                                FetchedData(id = itemId, local = null, remote = null, error = "获取数据异常 (ID: $itemId): ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }

            libLogI("  ✅ 数据获取完成，耗时: ${System.currentTimeMillis() - fetchDetailStartTime}ms")

            libLogI("⚙️ 步骤 3: 数据比对与处理")
            val updatedLocalDataFromRemote = mutableListOf<T>()
            val updatedLocalDataFromUpload = mutableListOf<T>()
            val updatedRemoteData = mutableListOf<T>()

            for (fetched in allFetchedData) {
                if (fetched.error != null) {
                    // 顺序收集错误消息，避免并发修改
                    failureMessages.add(fetched.error)
                    stats.recordFailedFetch()
                    continue
                }
                processDataComparison(
                    fetched.local, fetched.remote, fetched.id, syncOption,
                    failureMessages, stats, updatedLocalDataFromRemote, updatedLocalDataFromUpload,
                    updatedRemoteData, filesToDeleteAfterSuccess
                )
            }

            libLogI("  ✅ 待上传: ${updatedRemoteData.size} 项，待更新本地(远程): ${updatedLocalDataFromRemote.size} 项，待更新本地(上传): ${updatedLocalDataFromUpload.size} 项")

            performBatchUpdates(updatedRemoteData, updatedLocalDataFromRemote, updatedLocalDataFromUpload, failureMessages, filesToDeleteAfterSuccess)

            finalizeSyncResult(startTime, stats, failureMessages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleSyncException(e, startTime, failureMessages)
        }
    }

    /**
     * 新模式：批量模式
     * 直接获取全部完整信息，在内存中比较差异，最后批量更新
     * 性能更优，避免大量网络请求
     */
    private suspend fun executeBatchMode(
        startTime: Long,
        sessionId: String,
        lastSyncTime: String,
        syncOption: SyncOption
    ): Result = withContext(Dispatchers.IO) {
        val failureMessages = mutableListOf<String>()
        val filesToDeleteAfterSuccess = mutableMapOf<Long, String>()
        val stats = SyncStats()

        try {
            // ═══════════════════════════════════════════════════════════
            // 步骤 1: 批量获取所有更新的完整信息
            // ═══════════════════════════════════════════════════════════
            libLogI("📥 步骤 1: 批量获取完整信息")

            var remoteDataList: List<T> = emptyList()
            var localDataList: List<T> = emptyList()

            // 获取服务端更新数据
            if (syncOption == SyncOption.SERVER_DOWNLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                libLogI("  ⬇️ 正在批量获取服务端更新数据...")
                val fetchStartTime = System.currentTimeMillis()
                val remoteResult = repository.remoteGetAfterUpdateTimeBatch(lastSyncTime)
                val fetchDuration = System.currentTimeMillis() - fetchStartTime

                if (remoteResult.isError()) {
                    libLogE("  ❌ 批量获取服务端数据失败: ${remoteResult.message}")
                    return@withContext Result.failure(createFailData("批量获取服务端数据失败: ${remoteResult.message}"))
                }
                remoteDataList = remoteResult.data ?: emptyList()
                libLogI("  ✅ 服务端数据获取成功")
                libLogI("    数量: ${remoteDataList.size} 个")
                libLogI("    请求耗时: ${fetchDuration}ms")
            }

            // 获取本地更新数据
            if (syncOption == SyncOption.DEVICE_UPLOAD || syncOption == SyncOption.TWO_WAY_SYNC) {
                libLogI("  ⬆️ 正在批量获取本地更新数据...")
                val fetchStartTime = System.currentTimeMillis()
                localDataList = repository.localGetAfterUpdateTimeBatch(lastSyncTime)
                val fetchDuration = System.currentTimeMillis() - fetchStartTime

                libLogI("  ✅ 本地数据获取成功")
                libLogI("    数量: ${localDataList.size} 个")
                libLogI("    查询耗时: ${fetchDuration}ms")
            }

            // 构建 ID -> 数据 的映射，便于快速查找
            val remoteDataMap = remoteDataList.associateBy { it.id }
            val localDataMap = localDataList.associateBy { it.id }
            val allIds = (remoteDataMap.keys + localDataMap.keys).distinct()

            libLogI("  📋 汇总: 共 ${allIds.size} 个待处理项目")
            libLogI("    服务端: ${remoteDataMap.size} 个, 本地: ${localDataMap.size} 个")
            libLogI("────────────────────────────────────────────────────────")

            // 无待同步项目，提前结束
            if (allIds.isEmpty()) {
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime

                return@withContext if (failureMessages.isEmpty()) {
                    libLogI("✅ 没有需要同步的项目，任务完成，耗时: ${duration}ms")
                    Result.success(createSuccessData("没有需要同步的$syncOptionName"))
                } else {
                    libLogE("⚠️ 任务完成但存在错误: ${failureMessages.size} 个")
                    Result.failure(createFailData(failureMessages.joinToString("\n")))
                }
            }

            // ═══════════════════════════════════════════════════════════
            // 步骤 2: 在内存中比较差异
            // ═══════════════════════════════════════════════════════════
            libLogI("⚙️ 步骤 2: 内存中数据比对")
            val compareStartTime = System.currentTimeMillis()

            // 区分两个本地更新来源：
            // - fromRemote: 来自服务端数据，始终写入本地
            // - fromUpload: 上传处理后需更新本地，仅在远程上传成功时写入
            val updatedLocalDataFromRemote = mutableListOf<T>()
            val updatedLocalDataFromUpload = mutableListOf<T>()
            val updatedRemoteData = mutableListOf<T>()

            for (itemId in allIds) {
                val localData = localDataMap[itemId]
                val remoteData = remoteDataMap[itemId]

                processDataComparison(
                    localData, remoteData, itemId, syncOption,
                    failureMessages, stats, updatedLocalDataFromRemote, updatedLocalDataFromUpload,
                    updatedRemoteData, filesToDeleteAfterSuccess
                )
            }

            val compareDuration = System.currentTimeMillis() - compareStartTime
            libLogI("  ✅ 数据比对完成，耗时: ${compareDuration}ms")
            libLogI("  待上传: ${updatedRemoteData.size} 项")
            libLogI("  待更新本地(来自服务端): ${updatedLocalDataFromRemote.size} 项")
            libLogI("  待更新本地(来自上传): ${updatedLocalDataFromUpload.size} 项")
            libLogI("────────────────────────────────────────────────────────")

            // ═══════════════════════════════════════════════════════════
            // 步骤 3: 批量更新
            // ═══════════════════════════════════════════════════════════
            performBatchUpdates(updatedRemoteData, updatedLocalDataFromRemote, updatedLocalDataFromUpload, failureMessages, filesToDeleteAfterSuccess)

            finalizeSyncResult(startTime, stats, failureMessages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleSyncException(e, startTime, failureMessages)
        }
    }

    /**
     * 通用的数据比对处理逻辑
     *
     * updatedLocalDataFromRemote: 来自服务端、需要写入本地的数据（无论远程上传是否成功都应写入）
     * updatedLocalDataFromUpload: 来自本地上传处理、需要同步更新本地的数据（仅在远程上传成功时才写入）
     * updatedRemoteData: 需要上传到服务器的数据
     */
    private suspend fun processDataComparison(
        localData: T?,
        remoteData: T?,
        itemId: Long,
        syncOption: SyncOption,
        failureMessages: MutableList<String>,
        stats: SyncStats,
        updatedLocalDataFromRemote: MutableList<T>,
        updatedLocalDataFromUpload: MutableList<T>,
        updatedRemoteData: MutableList<T>,
        filesToDeleteAfterSuccess: MutableMap<Long, String>
    ) {
        // 内联文件删除逻辑，避免高阶函数调用开销
        fun markFileForDeletion(processed: SyncableEntity, original: SyncableEntity) {
            val processedPath = processed.getPhotoPath()
            val originalPath = original.getPhotoPath()
            if (!processedPath.isNullOrEmpty() && processedPath != originalPath) {
                originalPath?.let { filesToDeleteAfterSuccess[original.id] = it }
            }
        }

        when (syncOption) {
            SyncOption.DEVICE_UPLOAD -> localData?.let {
                val processed = handleLocalDataForUpload(it, failureMessages)
                processed?.let { element ->
                    updatedRemoteData.add(element)
                    if (element != it) {
                        updatedLocalDataFromUpload.add(element)
                        markFileForDeletion(element, it)
                    }
                    stats.recordUpload()
                }
            }

            SyncOption.SERVER_DOWNLOAD -> remoteData?.let {
                val processed = handleRemoteDataForDownload(it, failureMessages)
                processed?.let { element ->
                    updatedLocalDataFromRemote.add(element)
                    stats.recordDownload()
                }
            }

            SyncOption.TWO_WAY_SYNC -> when {
                remoteData == null && localData != null -> {
                    val processed = handleLocalDataForUpload(localData, failureMessages)
                    processed?.let {
                        updatedRemoteData.add(it)
                        if (it != localData) {
                            updatedLocalDataFromUpload.add(it)
                            markFileForDeletion(it, localData)
                        }
                        stats.recordUpload()
                    }
                }

                remoteData != null && localData == null -> {
                    val processed = handleRemoteDataForDownload(remoteData, failureMessages)
                    processed?.let {
                        updatedLocalDataFromRemote.add(it)
                        stats.recordDownload()
                    }
                }

                remoteData != null && localData != null -> {
                    val remoteTime = parseUpdateTime(remoteData.updateTime)
                    val localTime = parseUpdateTime(localData.updateTime)
                    if (remoteTime == null || localTime == null) {
                        failureMessages.add("ID: $itemId updateTime 解析失败, remote=${remoteData.updateTime}, local=${localData.updateTime}")
                        stats.recordFailedFetch()
                    } else {
                        val diff = kotlin.math.abs(remoteTime - localTime)
                        when {
                            // 时间差在容忍阈值内，视为同时更新，跳过
                            diff <= TIME_SKEW_THRESHOLD_MS -> {
                                stats.recordSkip()
                            }
                            remoteTime > localTime -> {
                                val processed = handleRemoteDataForDownload(remoteData, failureMessages)
                                processed?.let {
                                    updatedLocalDataFromRemote.add(it)
                                    stats.recordDownload()
                                }
                            }

                            localTime > remoteTime -> {
                                val processed = handleLocalDataForUpload(localData, failureMessages)
                                processed?.let {
                                    updatedRemoteData.add(it)
                                    if (it != localData) {
                                        updatedLocalDataFromUpload.add(it)
                                        markFileForDeletion(it, localData)
                                    }
                                    stats.recordUpload()
                                }
                            }
                        }
                    }
                }
            }

            SyncOption.SYNC_OFF -> { /* 不处理 */ }
        }
    }

    /**
     * 执行批量更新操作
     *
     * @param updatedRemoteData 需要上传到服务器的数据
     * @param updatedLocalDataFromRemote 来自服务端、需要写入本地的数据（始终写入）
     * @param updatedLocalDataFromUpload 上传处理后需更新本地的数据（仅远程上传成功时才写入，保证一致性）
     * @param failureMessages 失败消息列表
     * @param filesToDeleteAfterSuccess 待删除的本地文件映射
     */
    private suspend fun performBatchUpdates(
        updatedRemoteData: List<T>,
        updatedLocalDataFromRemote: List<T>,
        updatedLocalDataFromUpload: List<T>,
        failureMessages: MutableList<String>,
        filesToDeleteAfterSuccess: Map<Long, String>
    ) {
        libLogI("💾 步骤: 批量数据更新")

        // 上传到服务器
        var remoteUploadSucceeded = false
        if (updatedRemoteData.isNotEmpty()) {
            libLogI("  ☁️ 正在上传 ${updatedRemoteData.size} 个项目到服务器...")
            val uploadStartTime = System.currentTimeMillis()
            val remotePutResult = repository.remoteBatchUpsert(updatedRemoteData)
            val uploadDuration = System.currentTimeMillis() - uploadStartTime

            if (remotePutResult.isError()) {
                libLogE("  ❌ 批量上传失败: ${remotePutResult.message}")
                failureMessages.add("批量上传失败: ${remotePutResult.message}")
            } else {
                remoteUploadSucceeded = true
                libLogI("  ✅ 批量上传成功，数量: ${updatedRemoteData.size}，耗时: ${uploadDuration}ms")
            }
        } else {
            // 没有需要上传的数据，视为成功
            remoteUploadSucceeded = true
        }

        // 步骤 1: 始终写入来自服务端的数据到本地
        var localRemoteUpdateSucceeded = true
        if (updatedLocalDataFromRemote.isNotEmpty()) {
            libLogI("  🗄️ 正在写入服务端数据到本地 ${updatedLocalDataFromRemote.size} 个项目...")
            val localUpdateStartTime = System.currentTimeMillis()
            try {
                repository.localBatchUpsert(updatedLocalDataFromRemote)
                val localUpdateDuration = System.currentTimeMillis() - localUpdateStartTime
                libLogI("  ✅ 服务端数据写入本地成功，数量: ${updatedLocalDataFromRemote.size}，耗时: ${localUpdateDuration}ms")
            } catch (e: Exception) {
                localRemoteUpdateSucceeded = false
                val localUpdateDuration = System.currentTimeMillis() - localUpdateStartTime
                libLogE("  ❌ 服务端数据写入本地失败: ${e.message}，耗时: ${localUpdateDuration}ms")
                failureMessages.add("本地数据库更新失败(服务端数据): ${e.message}")
            }
        }

        // 步骤 2: 仅在远程上传成功时，写入上传处理后的本地更新数据
        var localUploadUpdateSucceeded = true
        if (remoteUploadSucceeded && updatedLocalDataFromUpload.isNotEmpty()) {
            libLogI("  🗄️ 正在同步本地上传状态 ${updatedLocalDataFromUpload.size} 个项目...")
            val uploadUpdateStartTime = System.currentTimeMillis()
            try {
                repository.localBatchUpsert(updatedLocalDataFromUpload)
                val uploadUpdateDuration = System.currentTimeMillis() - uploadUpdateStartTime
                libLogI("  ✅ 本地上传状态更新成功，数量: ${updatedLocalDataFromUpload.size}，耗时: ${uploadUpdateDuration}ms")
            } catch (e: Exception) {
                localUploadUpdateSucceeded = false
                libLogE("  ❌ 本地上传状态更新失败: ${e.message}")
                failureMessages.add("本地数据库更新失败(上传状态): ${e.message}")
            }
        } else if (!remoteUploadSucceeded && updatedLocalDataFromUpload.isNotEmpty()) {
            localUploadUpdateSucceeded = false
            libLogW("  ⚠️ 远程上传失败，跳过 ${updatedLocalDataFromUpload.size} 个本地上传状态的更新，避免数据不一致")
        }

        // 清理本地文件（分阶段清理，避免部分失败导致文件泄漏）
        if (syncConfig.isDeleteLocalFile && filesToDeleteAfterSuccess.isNotEmpty()) {
            // 只有当远程上传成功 + 上传状态本地更新成功时，才清理对应的上传文件
            val canCleanupUploadFiles = remoteUploadSucceeded && localUploadUpdateSucceeded
            if (canCleanupUploadFiles) {
                cleanupLocalFiles(updatedRemoteData, filesToDeleteAfterSuccess)
            } else {
                libLogW("  ⚠️ 跳过文件清理: 远程上传=${remoteUploadSucceeded}, 本地上传状态更新=${localUploadUpdateSucceeded}")
                libLogW("  ⚠️ ${filesToDeleteAfterSuccess.size} 个本地文件暂不删除，将在下次成功同步后重试")
            }
        }

        libLogI("────────────────────────────────────────────────────────")
    }

    /**
     * 清理已上传的本地文件
     */
    private fun cleanupLocalFiles(
        updatedRemoteData: List<T>,
        filesToDeleteAfterSuccess: Map<Long, String>
    ) {
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
                    libLogE("    💥 删除异常: $localPath - ${e.message}")
                    failedCount++
                }
            }
        }
        libLogI("    文件清理完成: 成功 $deletedCount, 失败 $failedCount")
    }

    /**
     * 完成同步结果统计和日志
     */
    private fun finalizeSyncResult(
        startTime: Long,
        stats: SyncStats,
        failureMessages: List<String>
    ): Result {
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        if (duration > TIMEOUT_THRESHOLD_MS) {
            libLogW("⏱️ 警告: 任务耗时超过5分钟! 实际: ${duration}ms")
        }

        libLogI("════════════════════════════════════════════════════════")
        libLogI("📊 同步任务完成: $workChineseName")
        libLogI("  下载: ${stats.downloaded} 项")
        libLogI("  上传: ${stats.uploaded} 项")
        libLogI("  跳过: ${stats.skipped} 项")
        libLogI("  总耗时: ${duration}ms")
        libLogI("════════════════════════════════════════════════════════")

        return if (failureMessages.isEmpty()) {
            libLogI("✅ 任务状态: 成功")
            Result.success(createSuccessData("${syncOptionName}增量更新成功，耗时${duration}ms"))
        } else {
            libLogE("❌ 任务状态: 部分失败，错误: ${failureMessages.size} 个")
            Result.failure(createFailData(failureMessages.joinToString("\n")))
        }
    }

    /**
     * 处理同步异常
     */
    private fun handleSyncException(
        e: Exception,
        startTime: Long,
        failureMessages: MutableList<String>
    ): Result {
        val duration = System.currentTimeMillis() - startTime
        libLogE("════════════════════════════════════════════════════════")
        libLogE("💥 同步任务发生未捕获异常: $workChineseName")
        libLogE("异常类型: ${e.javaClass.simpleName}")
        libLogE("异常信息: ${e.message}")
        libLogE("任务耗时: ${duration}ms")
        libLogE("堆栈信息:")
        libLogE(e.stackTraceToString())
        libLogE("════════════════════════════════════════════════════════")

        failureMessages.add("发生意外错误: ${e.message}")
        return Result.failure(createFailData(failureMessages.joinToString("\n")))
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