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

/** 子任务本轮未执行（如同步开关关闭）。仅供宿主 UI 识别状态，不再影响游标推进。 */
const val KEY_SYNC_SKIPPED = "KEY_SYNC_SKIPPED"
internal const val KEY_SYNC_USERNAME = "KEY_SYNC_USERNAME"
internal const val KEY_SYNC_DEVICE = "KEY_SYNC_DEVICE"

/**
 * 全局同步工作标签，用于状态查询和取消。
 * 手动任务使用每类型唯一名称 + KEEP；BaseCompareWork 在同进程中串行执行。
 * 自动协调器、子任务和成功提交均加此标签；旧入口还可通过 Worker 类名 tag 取消。
 */
const val GLOBAL_SYNC_WORK_NAME = "global_sync_work"
