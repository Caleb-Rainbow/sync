package com.util.sync

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 同步配置提供者接口。
 *
 * 实现类必须保证线程安全，因为多个 Worker 可能并发读取配置。
 * 推荐使用 [AbstractSyncConfigProvider] 作为基类，它通过原子类型提供了线程安全保障。
 *
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
    var syncMode: Int

    /**
     * 保存最近一次同步成功的时间戳。
     * 实现必须保证线程安全（synchronized 或原子操作）。
     */
    fun saveSuccessfulSyncTime(time: String)

    fun getAllTask(): List<SyncTaskDefinition>
}

/**
 * 线程安全的 [SyncConfigProvider] 抽象基类。
 * 使用 [AtomicReference] 和 [AtomicBoolean]/[AtomicInteger] 保护可变状态。
 * 子类只需实现 [doSaveSuccessfulSyncTime] 和 [getAllTask]。
 */
abstract class AbstractSyncConfigProvider : SyncConfigProvider {
    private val _username = AtomicReference("")
    private val _syncDataTime = AtomicReference("")
    private val _isDeleteLocalFile = AtomicBoolean(false)
    private val _isHeartbeat = AtomicBoolean(false)
    private val _heartbeatPeriod = AtomicInteger(0)
    private val _deviceNumber = AtomicReference("")
    private val _batchSize = AtomicInteger(100)
    private val _syncMode = AtomicInteger(1)

    override var username: String
        get() = _username.get()
        set(value) { _username.set(value) }

    override var syncDataTime: String
        get() = _syncDataTime.get()
        set(value) { _syncDataTime.set(value) }

    override var isDeleteLocalFile: Boolean
        get() = _isDeleteLocalFile.get()
        set(value) { _isDeleteLocalFile.set(value) }

    override var isHeartbeat: Boolean
        get() = _isHeartbeat.get()
        set(value) { _isHeartbeat.set(value) }

    override var heartbeatPeriod: Int
        get() = _heartbeatPeriod.get()
        set(value) { _heartbeatPeriod.set(value) }

    override var deviceNumber: String
        get() = _deviceNumber.get()
        set(value) { _deviceNumber.set(value) }

    override var batchSize: Int
        get() = _batchSize.get()
        set(value) { _batchSize.set(value) }

    override var syncMode: Int
        get() = _syncMode.get()
        set(value) { _syncMode.set(value) }

    /**
     * 使用 CAS 保证时间戳只向前更新，避免并发写入导致回退。
     * 仅在值实际变更时调用持久化。
     */
    final override fun saveSuccessfulSyncTime(time: String) {
        var updated = false
        _syncDataTime.updateAndGet { current ->
            if (current.isEmpty() || time > current) {
                updated = true
                time
            } else {
                current
            }
        }
        if (updated) {
            doSaveSuccessfulSyncTime(time)
        }
    }

    /**
     * 子类实现具体的持久化逻辑（如写入 SharedPreferences）。
     * 已由 [saveSuccessfulSyncTime] 保证内存值的线程安全。
     */
    protected abstract fun doSaveSuccessfulSyncTime(time: String)
}