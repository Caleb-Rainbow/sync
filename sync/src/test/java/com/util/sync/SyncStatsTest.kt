package com.util.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * SyncStats 计数器边界测试。
 */
class SyncStatsTest {

    @Test
    fun `initial values are all zero`() {
        val stats = SyncStats()
        assertEquals(0, stats.downloaded)
        assertEquals(0, stats.uploaded)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.failedFetch)
    }

    @Test
    fun `recordDownload increments downloaded`() {
        val stats = SyncStats()
        stats.recordDownload()
        assertEquals(1, stats.downloaded)
        assertEquals(0, stats.uploaded)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.failedFetch)
    }

    @Test
    fun `recordUpload increments uploaded`() {
        val stats = SyncStats()
        stats.recordUpload()
        assertEquals(0, stats.downloaded)
        assertEquals(1, stats.uploaded)
        assertEquals(0, stats.skipped)
        assertEquals(0, stats.failedFetch)
    }

    @Test
    fun `recordSkip increments skipped`() {
        val stats = SyncStats()
        stats.recordSkip()
        assertEquals(0, stats.downloaded)
        assertEquals(0, stats.uploaded)
        assertEquals(1, stats.skipped)
        assertEquals(0, stats.failedFetch)
    }

    @Test
    fun `recordFailedFetch increments failedFetch`() {
        val stats = SyncStats()
        stats.recordFailedFetch()
        assertEquals(0, stats.downloaded)
        assertEquals(0, stats.uploaded)
        assertEquals(0, stats.skipped)
        assertEquals(1, stats.failedFetch)
    }

    @Test
    fun `multiple operations accumulate correctly`() {
        val stats = SyncStats()
        repeat(3) { stats.recordDownload() }
        repeat(5) { stats.recordUpload() }
        repeat(2) { stats.recordSkip() }
        repeat(1) { stats.recordFailedFetch() }

        assertEquals(3, stats.downloaded)
        assertEquals(5, stats.uploaded)
        assertEquals(2, stats.skipped)
        assertEquals(1, stats.failedFetch)
    }

    @Test
    fun `large number of records`() {
        val stats = SyncStats()
        repeat(10000) { stats.recordDownload() }
        assertEquals(10000, stats.downloaded)
    }

    @Test
    fun `toString contains all fields`() {
        val stats = SyncStats()
        stats.recordDownload()
        stats.recordUpload()
        val str = stats.toString()
        assertTrue(str.contains("downloaded=1"))
        assertTrue(str.contains("uploaded=1"))
        assertTrue(str.contains("skipped=0"))
        assertTrue(str.contains("failedFetch=0"))
    }

    @Test
    fun `each counter is independent`() {
        val stats = SyncStats()
        stats.recordDownload()
        stats.recordDownload()
        stats.recordUpload()
        assertEquals(2, stats.downloaded)
        assertEquals(1, stats.uploaded)
    }
}
