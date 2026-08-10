package com.util.sync

/**
 * 同步模块常量定义。
 *
 * @author 杨帅林
 */

/** WorkManager Data 键：上次同步时间 */
const val KEY_LAST_SYNC_TIME = "KEY_LAST_SYNC_TIME"

/** WorkManager Data 键：本次同步开始时间 */
const val KEY_SYNC_START_TIME = "KEY_SYNC_START_TIME"

/** WorkManager Data 键：同步会话 ID */
const val KEY_SYNC_SESSION_ID = "KEY_SYNC_SESSION_ID"

/**
 * 全局同步工作标签（tag），用于跨手动/自动同步的互斥与状态查询。
 *
 * 使用方式（注意各路径策略不同）：
 * - 手动同步：每个 Compare Worker 以 [ExistingWorkPolicy.REPLACE] 入队，并打上此标签；
 *   不同 Worker 类型各自用独立 unique work name，避免互相清理 WorkSpec。
 * - 自动同步：`SyncCoordinatorWorker` 入队子任务时打上此标签，启动时检查是否存在
 *   RUNNING 状态的同标签任务，有则 `Result.retry()` 退让。
 * - 查询/取消：`SyncWorkManager.isSyncRunning()` 与 `cancelAllSync()` 均基于此标签。
 */
const val GLOBAL_SYNC_WORK_NAME = "global_sync_work"
