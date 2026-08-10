package com.util.sync

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 同步时间解析与格式化工具。
 * 从 BaseCompareWork 中提取的纯逻辑，便于单元测试。
 */
object SyncTimeUtils {
    /** 时钟偏差容忍阈值（毫秒） */
    const val TIME_SKEW_THRESHOLD_MS = 3000L

    /**
     * 带毫秒的解析格式（仅用于解析兼容旧数据，不用于生成新时间戳）。
     * 生成时间戳请使用 [STANDARD_FORMATTER]。
     */
    val PARSE_FORMATTER_WITH_MS: DateTimeFormatter by lazy {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    /** 标准格式（不带毫秒），用于生成时间戳和解析。 */
    val STANDARD_FORMATTER: DateTimeFormatter by lazy {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    val UTC_ZONE: ZoneOffset = ZoneOffset.UTC

    /**
     * 将 epoch 毫秒格式化为本地时间字符串（不带毫秒），用于日志输出和时间戳生成。
     */
    fun formatTimestamp(timeMs: Long): String =
        java.time.Instant.ofEpochMilli(timeMs)
            .atZone(ZoneId.systemDefault())
            .format(STANDARD_FORMATTER)

    /**
     * 将 updateTime 字符串解析为 epoch 毫秒数，用于可靠的数值比较。
     *
     * 统一按设备本地时区 [ZoneId.systemDefault] 解析，兼容两种格式：带毫秒和不带毫秒。
     *
     * 时区约定：客户端与服务器必须使用同一时区（参见后端接口对接文档）。
     * 客户端生成时间串时也使用本地时区，因此这里按本地时区解析可保证
     * 「本地串」与「服务器串」在同一基准下比较。
     *
     * @return 解析得到的 epoch 毫秒；格式不合法时返回 null
     */
    fun parseUpdateTime(time: String): Long? {
        // 先尝试带毫秒格式，再尝试不带毫秒格式，均按设备本地时区解析
        return try {
            java.time.LocalDateTime.parse(time, PARSE_FORMATTER_WITH_MS)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(time, STANDARD_FORMATTER)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 比较两个 updateTime 字符串，决定时间先后。
     *
     * @return TimeComparisonResult 表示时间比较结果
     */
    fun compareTimestamps(remoteTimeStr: String, localTimeStr: String): TimeComparisonResult {
        val remoteTime = parseUpdateTime(remoteTimeStr)
        val localTime = parseUpdateTime(localTimeStr)

        if (remoteTime == null || localTime == null) {
            return TimeComparisonResult.ParseError(remoteTimeStr, localTimeStr)
        }

        val diff = kotlin.math.abs(remoteTime - localTime)
        return when {
            diff <= TIME_SKEW_THRESHOLD_MS -> TimeComparisonResult.WithinThreshold
            remoteTime > localTime -> TimeComparisonResult.RemoteNewer
            else -> TimeComparisonResult.LocalNewer
        }
    }
}

/**
 * 时间比较结果（仅比较时间戳的新旧，不涉及同步方向）
 */
sealed class TimeComparisonResult {
    /** 远程更新时间较新 */
    data object RemoteNewer : TimeComparisonResult()
    /** 本地更新时间较新 */
    data object LocalNewer : TimeComparisonResult()
    /** 时间差在容忍阈值内 */
    data object WithinThreshold : TimeComparisonResult()
    /** 时间解析失败 */
    data class ParseError(val remoteTime: String, val localTime: String) : TimeComparisonResult()
}
