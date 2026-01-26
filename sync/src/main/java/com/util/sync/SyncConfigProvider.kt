package com.util.sync

/**
 * @description
 * @author 杨帅林
 * @create 2025/9/2 16:33
 **/
interface SyncConfigProvider {
    var username: String
    var syncDataTime: String
    /**
     * 是否应在上传成功后删除本地文件。
     */
    var isDeleteLocalFile: Boolean
    var isHeartbeat: Boolean
    var heartbeatPeriod: Int
    var deviceNumber: String
    var batchSize: Int
    /**
     * 当前同步模式，批量还是根据id单查
     * 0-id单查  1-批量
     * */
    var syncMode:Int

    /**
     * 保存最近一次同步成功的时间戳。
     */
    fun saveSuccessfulSyncTime(time: String)

    fun getAllTask(): List<SyncTaskDefinition>
}