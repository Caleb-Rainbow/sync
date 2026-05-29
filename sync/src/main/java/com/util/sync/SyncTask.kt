package com.util.sync

import androidx.work.ListenableWorker
import kotlin.reflect.KClass

/**
 * @description
 * @author 杨帅林
 * @create 2025/9/2 15:56
 **/
/**
 * 定义一个同步子任务的模型。 (可留在库中)
 */
data class SyncSubTask(val tag: String, val description: String)

/**
 * 一个同步任务的通用定义接口。
 * 库中的其他通用功能可以依赖这个接口，而不是具体的实现。
 */
interface SyncTaskDefinition {
    val title: String
    val workerClass: KClass<out ListenableWorker>
    val subTasks: List<SyncSubTask>

    /**
     * 当前任务的同步选项值（0=设备上传, 1=服务器下发, 2=双向同步, 3=关闭）。
     * 由各应用实现从 SettingsRepository 读取。
     */
    val syncOptionValue: Int

    /**
     * 对应的 Room 表名，用于查询本地数据量。
     * 默认从 workerClass 名称推导（CompareXxxWork → Xxx）。
     */
    val roomTableName: String
        get() {
            val name = workerClass.simpleName ?: ""
            if (name.startsWith("Compare") && name.endsWith("Work")) {
                return name.removePrefix("Compare").removeSuffix("Work")
            }
            return name
        }
}