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

    /**
     * 保存最近一次同步成功的时间戳。
     */
    fun saveSuccessfulSyncTime(time: String)

    fun getAllTask(): List<SyncTaskDefinition>
}