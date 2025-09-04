package com.util.sync.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * @description
 * @author 杨帅林
 * @create 2025/6/27 10:02
 **/
@Dao
interface SyncLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: SyncLog)

    @Query("SELECT * FROM synclog ORDER BY id DESC")
    suspend fun getAllLogs(): List<SyncLog>

    @Query("SELECT * FROM synclog ORDER BY id DESC LIMIT :limit")
    suspend fun getLogs(limit: Int): List<SyncLog>

    @Query("DELETE FROM synclog")
    suspend fun clearAll()

    @Query("""
        SELECT
            sessionId,
            COUNT(*) AS logCount,
            MIN(timestamp) AS startTime,
            MAX(timestamp) AS endTime,
            -- 使用条件聚合来确定整体状态，效率极高
            CASE MAX(
                CASE logLevel
                    WHEN 'ERROR' THEN 2
                    WHEN 'WARN' THEN 1
                    ELSE 0
                END
            )
                WHEN 2 THEN 'ERROR'
                WHEN 1 THEN 'WARN'
                ELSE 'INFO'
            END AS overallStatusStr
        FROM SyncLog
        GROUP BY sessionId
        ORDER BY startTime DESC
    """)
    fun pagingGroupList(): PagingSource<Int, SessionLogGroup>

    @Query("SELECT * FROM SyncLog WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getLogsForSession(sessionId: String): List<SyncLog>

    @Query("DELETE FROM SyncLog WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteLogsOlderThan(cutoffTimestamp: String)
}