package com.util.sync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * @description
 * @author 杨帅林
 * @create 2025/6/27 9:57
 **/
@Entity(
    indices = [
        Index(value = ["sessionId", "logLevel"]), // 关键！用于优化分组查询
        Index(value = ["timestamp"]),             // 用于排序和定期清理
        Index(value = ["sessionId"])              // 用于按session查询详情
    ]
)
data class SyncLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val workerName: String,
    val logLevel: String,
    val message: String,
    val timestamp: String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date())
)
/**
 * 日志记录器帮助类
 * 封装了日志记录的通用逻辑，方便在各个Worker中调用。
 * @param sessionId 当前同步会话的ID。
 * @param workerName 当前Worker的类名。
 */
class SyncLogger(
    private val workerName: String,
    private val syncLogDao: SyncLogDao
) {
    private var sessionId: String = ""
    suspend fun info(message: String) {
        log("INFO", message)
    }

    suspend fun warn(message: String) {
        log("WARN", message)
    }

    suspend fun error(message: String) {
        log("ERROR", message)
    }

    fun setSessionId(sessionId: String) {
        this.sessionId = sessionId
    }

    private suspend fun log(level: String, message: String) {
        val logEntry = SyncLog(
            sessionId = sessionId,
            workerName = workerName,
            logLevel = level,
            message = message
        )
        syncLogDao.insert(logEntry)
    }
}

@Entity
data class SessionLogGroup(
    val sessionId: String,
    val overallStatusStr: String,
    val logCount: Int,
    val startTime: String,
    val endTime: String
)

/**
 * 日志级别枚举，方便处理和显示
 */
enum class LogLevel(val level: String) {
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR");

    companion object {
        fun fromString(level: String): LogLevel {
            return entries.find { it.level.equals(level, ignoreCase = true) } ?: INFO
        }
    }
}