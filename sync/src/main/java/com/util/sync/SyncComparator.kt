package com.util.sync

/**
 * 同步比较决策结果（无泛型的简单标记）
 */
sealed class SyncDecision {
    /** 跳过（时钟偏差范围内） */
    data object Skip : SyncDecision()
    /** 解析错误 */
    data class ParseError(
        val itemId: Long,
        val remoteTime: String,
        val localTime: String
    ) : SyncDecision()
    /** 无操作（根据 SyncOption 不需要处理） */
    data object NoOp : SyncDecision()
    /** 需要上传 */
    data object ShouldUpload : SyncDecision()
    /** 需要下载 */
    data object ShouldDownload : SyncDecision()
}

/**
 * 批量比较结果
 */
data class SyncCompareResult<T : SyncableEntity>(
    val toUpload: List<T>,
    val toDownload: List<T>,
    val skipped: Int,
    val errors: List<String>
)

/**
 * 同步比较引擎，封装核心的双向数据比对逻辑。
 * 从 BaseCompareWork 中提取，便于独立测试。
 *
 * @param T 同步实体类型
 */
class SyncComparator<T : SyncableEntity>(
    private val timeSkewThresholdMs: Long = SyncTimeUtils.TIME_SKEW_THRESHOLD_MS
) {
    /**
     * 对一对本地/远程数据进行比较，返回同步决策。
     */
    fun compare(
        localData: T?,
        remoteData: T?,
        syncOption: SyncOption,
    ): SyncDecision {
        return when (syncOption) {
            SyncOption.DEVICE_UPLOAD -> {
                if (localData != null) SyncDecision.ShouldUpload
                else SyncDecision.NoOp
            }

            SyncOption.SERVER_DOWNLOAD -> {
                if (remoteData != null) SyncDecision.ShouldDownload
                else SyncDecision.NoOp
            }

            SyncOption.TWO_WAY_SYNC -> when {
                remoteData == null && localData != null -> SyncDecision.ShouldUpload
                remoteData != null && localData == null -> SyncDecision.ShouldDownload

                remoteData != null && localData != null -> {
                    val remoteTime = SyncTimeUtils.parseUpdateTime(remoteData.updateTime)
                    val localTime = SyncTimeUtils.parseUpdateTime(localData.updateTime)

                    if (remoteTime == null || localTime == null) {
                        SyncDecision.ParseError(localData.id, remoteData.updateTime, localData.updateTime)
                    } else {
                        val diff = kotlin.math.abs(remoteTime - localTime)
                        when {
                            diff <= timeSkewThresholdMs -> SyncDecision.Skip
                            remoteTime > localTime -> SyncDecision.ShouldDownload
                            else -> SyncDecision.ShouldUpload
                        }
                    }
                }

                else -> SyncDecision.NoOp
            }

            SyncOption.SYNC_OFF -> SyncDecision.NoOp
        }
    }

    /**
     * 批量比较所有 ID 对应的本地/远程数据。
     */
    fun compareBatch(
        localDataMap: Map<Long, T>,
        remoteDataMap: Map<Long, T>,
        syncOption: SyncOption
    ): SyncCompareResult<T> {
        val allIds = (remoteDataMap.keys + localDataMap.keys).distinct()

        val toUpload = mutableListOf<T>()
        val toDownload = mutableListOf<T>()
        val errors = mutableListOf<String>()
        var skipped = 0

        for (itemId in allIds) {
            val local = localDataMap[itemId]
            val remote = remoteDataMap[itemId]

            when (val decision = compare(local, remote, syncOption)) {
                SyncDecision.ShouldUpload -> {
                    local?.let {
                        toUpload.add(it)
                    }
                }
                SyncDecision.ShouldDownload -> {
                    remote?.let { toDownload.add(it) }
                }
                SyncDecision.Skip -> skipped++
                is SyncDecision.ParseError -> {
                    errors.add("ID: ${decision.itemId} updateTime 解析失败, remote=${decision.remoteTime}, local=${decision.localTime}")
                }
                SyncDecision.NoOp -> { /* skip */ }
            }
        }

        return SyncCompareResult(toUpload, toDownload, skipped, errors)
    }
}
