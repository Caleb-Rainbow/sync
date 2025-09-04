package com.util.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.yitter.idgen.YitIdHelper
import kotlinx.coroutines.flow.Flow
import kotlin.jvm.java
import kotlin.to

/**
 * @description
 * @author 杨帅林
 * @create 2025/8/29 15:47
 **/
class SyncWorkManager(val context: Context) {

    /**
     * 启动一个带唯一动态标签的 Worker，并返回一个只观察该 Worker 的 Flow。
     * 这是最终的正确版本。
     */
    inline fun <reified W : ListenableWorker> enqueueAndObserveUniqueRequest(
        lastSyncTime: String,
        sessionId: Long
    ): Flow<List<WorkInfo>> {
        val workManager = WorkManager.getInstance(context)

        // 1. 为本次请求创建唯一的动态 Tag
        val uniqueTag = "${W::class.java.name}-${YitIdHelper.nextId()}"

        val workRequest = OneTimeWorkRequestBuilder<W>()
            .addTag(uniqueTag) // <-- 使用唯一的动态 Tag
            .setInputData(
                workDataOf(
                    KEY_LAST_SYNC_TIME to lastSyncTime,
                    KEY_SYNC_SESSION_ID to "手动同步--$sessionId"
                )
            )
            .build()

        // 2. 使用简单的 enqueue
        workManager.enqueue(workRequest)

        // 3. 返回一个只观察这个唯一 Tag 的 Flow
        return workManager.getWorkInfosByTagFlow(uniqueTag)
    }
}