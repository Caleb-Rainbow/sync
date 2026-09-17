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

    // 远程批量更新或插入（返回 ResultModel<String>，调用方只关心成功/失败，data 通常为 "ok"）
    suspend fun remoteBatchUpsert(data: List<T>): ResultModel<String>
    // 本地批量更新或插入
    suspend fun localBatchUpsert(data: List<T>)

    /**
     * 上传后的条件回写，不得覆盖读取快照后发生的业务修改。
     * 默认实现兼容已有仓库，在回写前复核整个实体（实体应有值相等语义）。
     * 若业务线程也会写表，宿主必须重写为同一数据库事务中的读取、比较和写入，
     * 才能封闭复核与 upsert 之间的竞态；多进程写入也必须由数据库保证。
     * 返回 false 时本轮同步失败且不会删除该记录的附件。
     */
    @Suppress("DEPRECATION")
    suspend fun localUpsertAfterUpload(original: T, updated: T): Boolean {
        require(original.id == updated.id) { "上传回写不能改变实体 ID" }
        val current = localGetById(original.id)
        if (current != original) return false
        localBatchUpsert(listOf(updated))
        return true
    }

    /**
     * 替换已验证完整的服务端快照。默认先写后删以兼容旧仓库；
     * 宿主可重写并在同一个 Room 事务内完成，避免读者看到中间状态。
     * 写入或清理失败必须抛异常，不能静默忽略。
     */
    suspend fun localReplaceAll(data: List<T>) {
        if (data.isEmpty()) {
            localDeleteAll()
        } else {
            localBatchUpsert(data)
            localDeleteAllExcept(data.mapTo(mutableSetOf()) { it.id })
        }
    }

    /** 清空本地表所有数据，仅用于已确认的服务端空快照。 */
    suspend fun localDeleteAll()

    /** 删除不在指定 ID 集合中的本地记录，用于"覆盖本地"模式下安全替换 */
    suspend fun localDeleteAllExcept(retainedIds: Set<Long>)
}
