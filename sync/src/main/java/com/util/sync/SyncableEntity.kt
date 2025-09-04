package com.util.sync

/**
 * @description
 * @author 杨帅林
 * @create 2025/8/29 14:26
 **/
interface SyncableEntity {
    val id: Long
    val officeId: Int?
    val canteenId: Int?
    val deviceNumber: String
    val createTime: String
    val updateTime: String
    val isDelete: Boolean
    fun getPhotoPath(): String? = null
}