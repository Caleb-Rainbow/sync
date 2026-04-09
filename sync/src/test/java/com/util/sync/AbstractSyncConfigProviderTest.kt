package com.util.sync

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

/**
 * AbstractSyncConfigProvider 线程安全与 CAS 边界测试。
 */
class AbstractSyncConfigProviderTest {

    /**
     * 测试用的具体实现
     */
    private class TestSyncConfigProvider : AbstractSyncConfigProvider() {
        val savedTimes = ConcurrentLinkedQueue<String>()
        var doSaveCallCount = AtomicInteger(0)

        override fun doSaveSuccessfulSyncTime(time: String) {
            savedTimes.add(time)
            doSaveCallCount.incrementAndGet()
        }

        override fun getAllTask(): List<SyncTaskDefinition> = emptyList()
    }

    // ═══════════════════════════════════════════════════════════
    // 基本属性默认值
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `default username is empty`() {
        val provider = TestSyncConfigProvider()
        assertEquals("", provider.username)
    }

    @Test
    fun `default syncDataTime is empty`() {
        val provider = TestSyncConfigProvider()
        assertEquals("", provider.syncDataTime)
    }

    @Test
    fun `default isDeleteLocalFile is false`() {
        val provider = TestSyncConfigProvider()
        assertFalse(provider.isDeleteLocalFile)
    }

    @Test
    fun `default isHeartbeat is false`() {
        val provider = TestSyncConfigProvider()
        assertFalse(provider.isHeartbeat)
    }

    @Test
    fun `default heartbeatPeriod is 0`() {
        val provider = TestSyncConfigProvider()
        assertEquals(0, provider.heartbeatPeriod)
    }

    @Test
    fun `default deviceNumber is empty`() {
        val provider = TestSyncConfigProvider()
        assertEquals("", provider.deviceNumber)
    }

    @Test
    fun `default batchSize is 100`() {
        val provider = TestSyncConfigProvider()
        assertEquals(100, provider.batchSize)
    }

    @Test
    fun `default syncMode is 1`() {
        val provider = TestSyncConfigProvider()
        assertEquals(1, provider.syncMode)
    }

    // ═══════════════════════════════════════════════════════════
    // 属性读写
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `set and get username`() {
        val provider = TestSyncConfigProvider()
        provider.username = "testUser"
        assertEquals("testUser", provider.username)
    }

    @Test
    fun `set and get syncDataTime`() {
        val provider = TestSyncConfigProvider()
        provider.syncDataTime = "2026-01-15 10:30:45.000"
        assertEquals("2026-01-15 10:30:45.000", provider.syncDataTime)
    }

    @Test
    fun `set and get batchSize`() {
        val provider = TestSyncConfigProvider()
        provider.batchSize = 50
        assertEquals(50, provider.batchSize)
    }

    @Test
    fun `set and get syncMode`() {
        val provider = TestSyncConfigProvider()
        provider.syncMode = 0
        assertEquals(0, provider.syncMode)
    }

    @Test
    fun `set and get boolean properties`() {
        val provider = TestSyncConfigProvider()
        provider.isDeleteLocalFile = true
        provider.isHeartbeat = true
        assertTrue(provider.isDeleteLocalFile)
        assertTrue(provider.isHeartbeat)
    }

    // ═══════════════════════════════════════════════════════════
    // saveSuccessfulSyncTime - CAS 单调递增
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `saveSuccessfulSyncTime sets time when empty`() {
        val provider = TestSyncConfigProvider()
        provider.saveSuccessfulSyncTime("2026-01-15 10:30:45.000")
        assertEquals("2026-01-15 10:30:45.000", provider.syncDataTime)
        assertEquals(1, provider.doSaveCallCount.get())
    }

    @Test
    fun `saveSuccessfulSyncTime updates when new time is greater`() {
        val provider = TestSyncConfigProvider()
        provider.saveSuccessfulSyncTime("2026-01-15 10:30:45.000")
        provider.saveSuccessfulSyncTime("2026-01-16 10:30:45.000")
        assertEquals("2026-01-16 10:30:45.000", provider.syncDataTime)
        assertEquals(2, provider.doSaveCallCount.get())
    }

    @Test
    fun `saveSuccessfulSyncTime ignores when new time is older`() {
        val provider = TestSyncConfigProvider()
        provider.saveSuccessfulSyncTime("2026-01-16 10:30:45.000")
        provider.saveSuccessfulSyncTime("2026-01-15 10:30:45.000")
        assertEquals("2026-01-16 10:30:45.000", provider.syncDataTime)
        assertEquals(1, provider.doSaveCallCount.get())
    }

    @Test
    fun `saveSuccessfulSyncTime ignores when new time is equal`() {
        val provider = TestSyncConfigProvider()
        provider.saveSuccessfulSyncTime("2026-01-15 10:30:45.000")
        provider.saveSuccessfulSyncTime("2026-01-15 10:30:45.000")
        assertEquals("2026-01-15 10:30:45.000", provider.syncDataTime)
        // 只更新了一次 - 第二次因为值相等而不触发持久化
        assertEquals(1, provider.doSaveCallCount.get())
    }

    @Test
    fun `saveSuccessfulSyncTime string comparison - lexicographic`() {
        // CAS 使用字符串比较 ">" — 对于日期格式而言，字典序与时间序一致
        val provider = TestSyncConfigProvider()
        provider.saveSuccessfulSyncTime("2026-01-15 10:30:45.000")
        // 毫秒数不同但同一秒内
        provider.saveSuccessfulSyncTime("2026-01-15 10:30:45.999")
        assertEquals("2026-01-15 10:30:45.999", provider.syncDataTime)
    }

    @Test
    fun `saveSuccessfulSyncTime empty string does not overwrite existing`() {
        val provider = TestSyncConfigProvider()
        provider.saveSuccessfulSyncTime("2026-01-15 10:30:45.000")
        provider.saveSuccessfulSyncTime("")
        assertEquals("2026-01-15 10:30:45.000", provider.syncDataTime)
        assertEquals(1, provider.doSaveCallCount.get())
    }

    @Test
    fun `saveSuccessfulSyncTime empty string can be set on empty provider`() {
        val provider = TestSyncConfigProvider()
        // current is empty, new time is empty - isEmpty check prevents update
        provider.saveSuccessfulSyncTime("")
        // "current.isEmpty() || time > current" - 当 current 为空且 time 为空时，
        // time > current 为 false ("" > "" == false)，但 current.isEmpty() 为 true
        // 所以会设置为 ""
        assertEquals("", provider.syncDataTime)
    }

    // ═══════════════════════════════════════════════════════════
    // saveSuccessfulSyncTime - 并发安全性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `concurrent saveSuccessfulSyncTime only keeps latest time`() {
        val provider = TestSyncConfigProvider()
        val threadCount = 50
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)

        val times = (0 until threadCount).map { i ->
            "2026-01-15 10:30:${String.format("%02d", i)}.000"
        }

        threads(threadCount) { index ->
            barrier.await()
            provider.saveSuccessfulSyncTime(times[index])
            latch.countDown()
        }

        latch.await()

        // 最终值必须是最大的时间
        val maxTime = times.max()
        assertEquals(maxTime, provider.syncDataTime)
    }

    @Test
    fun `concurrent reads during writes do not crash`() {
        val provider = TestSyncConfigProvider()
        provider.syncDataTime = "2026-01-15 10:00:00.000"

        val threadCount = 20
        val barrier = CyclicBarrier(threadCount * 2)
        val latch = CountDownLatch(threadCount * 2)

        // 一半线程写
        threads(threadCount) { index ->
            barrier.await()
            provider.saveSuccessfulSyncTime("2026-01-15 10:${index}:00.000")
            latch.countDown()
        }

        // 一半线程读
        threads(threadCount) { _ ->
            barrier.await()
            provider.syncDataTime // 不崩溃即可
            latch.countDown()
        }

        latch.await()
        // 只要不崩溃就通过
    }

    @Test
    fun `concurrent property modifications do not crash`() {
        val provider = TestSyncConfigProvider()
        val threadCount = 10
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)

        threads(threadCount) { index ->
            barrier.await()
            // 每个线程随机修改不同属性
            when (index % 5) {
                0 -> provider.username = "user$index"
                1 -> provider.batchSize = index
                2 -> provider.syncMode = index % 2
                3 -> provider.isDeleteLocalFile = index % 2 == 0
                4 -> provider.deviceNumber = "device$index"
            }
            latch.countDown()
        }

        latch.await()
        // 不崩溃即通过
    }

    // ═══════════════════════════════════════════════════════════
    // 边界值
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `batchSize zero and negative`() {
        val provider = TestSyncConfigProvider()
        provider.batchSize = 0
        assertEquals(0, provider.batchSize)
        provider.batchSize = -1
        assertEquals(-1, provider.batchSize)
    }

    @Test
    fun `heartbeatPeriod negative`() {
        val provider = TestSyncConfigProvider()
        provider.heartbeatPeriod = -10
        assertEquals(-10, provider.heartbeatPeriod)
    }

    @Test
    fun `deviceNumber with special characters`() {
        val provider = TestSyncConfigProvider()
        provider.deviceNumber = "设备-001_测试"
        assertEquals("设备-001_测试", provider.deviceNumber)
    }

    @Test
    fun `username with unicode`() {
        val provider = TestSyncConfigProvider()
        provider.username = "用户🎉"
        assertEquals("用户🎉", provider.username)
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    private fun threads(count: Int, block: (Int) -> Unit) {
        (0 until count).map { i ->
            Thread { block(i) }
        }.forEach { it.start() }
    }
}
