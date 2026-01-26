package com.util.sync

import com.util.ktor.model.ResultModel


/**
 * @description
 * @author 杨帅林
 * @create 2025/8/29 14:30
 **/
interface SyncRepository<T : SyncableEntity> {
    // 远程获取上次同步时间之后更新过的ID列表
    @Deprecated("将在后续移除")
    suspend fun remoteGetAfterUpdateTime(lastSyncTime: String): ResultModel<List<Long>>
    // 本地获取上次同步时间之后更新过的ID列表
    @Deprecated("将在后续移除")
    suspend fun localGetAfterUpdateTime(lastSyncTime: String): List<Long>

    // 远程获取上次同步时间之后更新过的完整信息列表
    suspend fun remoteGetAfterUpdateTimeBatch(lastSyncTime: String): ResultModel<List<T>>
    // 本地获取上次同步时间之后更新过的完整信息列表
    suspend fun localGetAfterUpdateTimeBatch(lastSyncTime: String): List<T>

    // 远程根据ID获取单个实体
    @Deprecated("将在后续移除")
    suspend fun remoteGetById(id: Long): ResultModel<T>
    // 本地根据ID获取单个实体
    @Deprecated("将在后续移除")
    suspend fun localGetById(id: Long): T?

    // 远程批量更新或插入
    suspend fun remoteBatchUpsert(data: List<T>): ResultModel<String> // 使用 Unit 表示只关心成功或失败
    // 本地批量更新或插入
    suspend fun localBatchUpsert(data: List<T>)
}