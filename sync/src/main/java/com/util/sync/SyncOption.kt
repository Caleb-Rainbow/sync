package com.util.sync

import kotlin.collections.first

/**
 * @description
 * @author 杨帅林
 * @create 2025/9/2 16:03
 **/
enum class SyncOption(val description: String) {
    /**
     * 设备将数据上传到服务器，服务器不向设备下发数据。
     */
    DEVICE_UPLOAD("设备单向上传"),

    /**
     * 服务器将数据下发到设备，设备不向服务器上传数据。
     */
    SERVER_DOWNLOAD("服务器单向下发"),

    /**
     * 设备与服务器之间双向同步数据。
     */
    TWO_WAY_SYNC("双向同步"),

    /**
     * 关闭当前同步项，不进行任何数据同步。
     */
    SYNC_OFF("关闭同步");

    companion object {
        fun fromInt(value: Int) = entries.first { it.ordinal == value }
    }
}