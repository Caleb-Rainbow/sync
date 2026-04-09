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
 * 全局同步工作名称，用于互斥。
 * 手动同步和自动同步都使用同一个 unique work name，
 * 通过 [androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE] 保证不会并发执行。
 */
const val GLOBAL_SYNC_WORK_NAME = "global_sync_work"
