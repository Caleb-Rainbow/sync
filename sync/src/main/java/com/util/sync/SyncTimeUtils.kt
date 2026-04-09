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
     * 将 epoch 毫秒格式化为 UTC 时间字符串（不带毫秒），用于日志输出和时间戳生成。
     */
    fun formatTimestamp(timeMs: Long): String =
        java.time.Instant.ofEpochMilli(timeMs)
            .atZone(UTC_ZONE)
            .format(STANDARD_FORMATTER)

    /**
     * 将 updateTime 字符串解析为 epoch 毫秒数，用于可靠的数值比较。
     * 兼容两种格式：带毫秒和不带毫秒。
     * 优先按 UTC 解析；若失败，回退到系统时区（兼容旧数据）。
     * 解析失败返回 null。
     */
    fun parseUpdateTime(time: String): Long? {
        return try {
            java.time.LocalDateTime.parse(time, PARSE_FORMATTER_WITH_MS)
                .toInstant(UTC_ZONE)
                .toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(time, STANDARD_FORMATTER)
                    .toInstant(UTC_ZONE)
                    .toEpochMilli()
            } catch (_: Exception) {
                try {
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
