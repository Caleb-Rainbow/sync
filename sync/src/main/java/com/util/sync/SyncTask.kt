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
}