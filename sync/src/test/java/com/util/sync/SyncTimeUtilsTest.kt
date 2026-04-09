package com.util.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId

/**
 * SyncTimeUtils 时间解析与比较的全面边界测试。
 */
class SyncTimeUtilsTest {

    // ═══════════════════════════════════════════════════════════
    // parseUpdateTime - 标准格式解析
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parseUpdateTime with milliseconds UTC`() {
        val time = "2026-01-15 10:30:45.123"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)

        val expected = LocalDateTime.parse(time, SyncTimeUtils.PARSE_FORMATTER_WITH_MS)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, result)
    }

    @Test
    fun `parseUpdateTime without milliseconds UTC`() {
        val time = "2026-01-15 10:30:45"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)

        val expected = LocalDateTime.parse(time, SyncTimeUtils.STANDARD_FORMATTER)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, result)
    }

    // ═══════════════════════════════════════════════════════════
    // parseUpdateTime - 边界时间值
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parseUpdateTime epoch zero`() {
        val time = "1970-01-01 00:00:00.000"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)
        assertEquals(0L, result)
    }

    @Test
    fun `parseUpdateTime one second before epoch`() {
        // 1969 不在 UTC LocalDateTime 范围内，但格式合法
        val time = "1969-12-31 23:59:59.000"
        val result = SyncTimeUtils.parseUpdateTime(time)
        // 这个时间在 UTC 下是负值，parseUpdateTime 应该能解析
        assertNotNull(result)
        assertEquals(-1000L, result)
    }

    @Test
    fun `parseUpdateTime far future date`() {
        val time = "2099-12-31 23:59:59.999"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)
        assertTrue(result!! > 0)
    }

    @Test
    fun `parseUpdateTime midnight boundary`() {
        val time = "2026-06-15 00:00:00.000"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)
    }

    @Test
    fun `parseUpdateTime last millisecond of day`() {
        val time = "2026-06-15 23:59:59.999"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)
    }

    // ═══════════════════════════════════════════════════════════
    // parseUpdateTime - 毫秒边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parseUpdateTime milliseconds all zeros`() {
        val time = "2026-01-15 10:30:45.000"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)
    }

    @Test
    fun `parseUpdateTime milliseconds 999`() {
        val time = "2026-01-15 10:30:45.999"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)
    }

    @Test
    fun `parseUpdateTime same time with and without milliseconds are equivalent`() {
        val withMs = "2026-01-15 10:30:45.000"
        val withoutMs = "2026-01-15 10:30:45"
        val resultWith = SyncTimeUtils.parseUpdateTime(withMs)
        val resultWithout = SyncTimeUtils.parseUpdateTime(withoutMs)
        assertNotNull(resultWith)
        assertNotNull(resultWithout)
        assertEquals(resultWith, resultWithout)
    }

    // ═══════════════════════════════════════════════════════════
    // parseUpdateTime - 无效输入
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parseUpdateTime empty string returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime(""))
    }

    @Test
    fun `parseUpdateTime random text returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("not-a-date"))
    }

    @Test
    fun `parseUpdateTime partial date returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("2026-01-15"))
    }

    @Test
    fun `parseUpdateTime date only no time returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("2026-01-15 "))
    }

    @Test
    fun `parseUpdateTime with wrong separator returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("2026/01/15 10:30:45"))
    }

    @Test
    fun `parseUpdateTime with timezone suffix returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("2026-01-15T10:30:45Z"))
    }

    @Test
    fun `parseUpdateTime with extra whitespace returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime(" 2026-01-15 10:30:45 "))
    }

    @Test
    fun `parseUpdateTime with only time returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("10:30:45"))
    }

    @Test
    fun `parseUpdateTime month 13 returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("2026-13-15 10:30:45"))
    }

    @Test
    fun `parseUpdateTime day 32 returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("2026-01-32 10:30:45"))
    }

    @Test
    fun `parseUpdateTime hour 25 returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("2026-01-15 25:30:45"))
    }

    @Test
    fun `parseUpdateTime special characters returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("null"))
        assertNull(SyncTimeUtils.parseUpdateTime("undefined"))
        assertNull(SyncTimeUtils.parseUpdateTime("---"))
    }

    // ═══════════════════════════════════════════════════════════
    // parseUpdateTime - 数值精度
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parseUpdateTime preserves millisecond precision`() {
        val time1 = "2026-01-15 10:30:45.001"
        val time2 = "2026-01-15 10:30:45.002"
        val result1 = SyncTimeUtils.parseUpdateTime(time1)
        val result2 = SyncTimeUtils.parseUpdateTime(time2)
        assertNotNull(result1)
        assertNotNull(result2)
        assertEquals(1L, result2!! - result1!!)
    }

    @Test
    fun `parseUpdateTime round trip`() {
        val originalMs = System.currentTimeMillis()
        val formatted = SyncTimeUtils.formatTimestamp(originalMs)
        val parsed = SyncTimeUtils.parseUpdateTime(formatted)
        assertNotNull(parsed)
        // formatTimestamp 不带毫秒，所以毫秒部分被截断
        assertTrue("Round-trip drift should be < 1000ms, actual: ${originalMs - parsed!!}",
            kotlin.math.abs(originalMs - parsed) < 1000)
    }

    // ═══════════════════════════════════════════════════════════
    // compareTimestamps - 基本方向判断
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareTimestamps remote newer`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:50.000",
            "2026-01-15 10:30:40.000"
        )
        assertTrue(result is TimeComparisonResult.RemoteNewer)
    }

    @Test
    fun `compareTimestamps local newer`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:40.000",
            "2026-01-15 10:30:50.000"
        )
        assertTrue(result is TimeComparisonResult.LocalNewer)
    }

    // ═══════════════════════════════════════════════════════════
    // compareTimestamps - 时钟偏差阈值边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareTimestamps exactly equal times - skip`() {
        val time = "2026-01-15 10:30:45.000"
        val result = SyncTimeUtils.compareTimestamps(time, time)
        assertTrue("Expected WithinThreshold for equal times, got $result", result is TimeComparisonResult.WithinThreshold)
    }

    @Test
    fun `compareTimestamps within threshold 1ms - skip`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:45.000",
            "2026-01-15 10:30:45.001"
        )
        assertTrue("Expected WithinThreshold for 1ms diff, got $result", result is TimeComparisonResult.WithinThreshold)
    }

    @Test
    fun `compareTimestamps within threshold 2999ms - skip`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:45.000",
            "2026-01-15 10:30:47.999"
        )
        assertTrue("Expected WithinThreshold for 2999ms diff, got $result", result is TimeComparisonResult.WithinThreshold)
    }

    @Test
    fun `compareTimestamps exactly at threshold 3000ms - skip`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:45.000",
            "2026-01-15 10:30:48.000"
        )
        assertTrue("Expected WithinThreshold for exactly 3000ms diff, got $result", result is TimeComparisonResult.WithinThreshold)
    }

    @Test
    fun `compareTimestamps just above threshold 3001ms - remote newer`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:48.001",
            "2026-01-15 10:30:45.000"
        )
        assertTrue("Expected RemoteNewer for 3001ms diff, got $result", result is TimeComparisonResult.RemoteNewer)
    }

    @Test
    fun `compareTimestamps just above threshold 3001ms - local newer`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:45.000",
            "2026-01-15 10:30:48.001"
        )
        assertTrue("Expected LocalNewer for 3001ms diff, got $result", result is TimeComparisonResult.LocalNewer)
    }

    // ═══════════════════════════════════════════════════════════
    // compareTimestamps - 解析失败
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareTimestamps remote parse failure returns ParseError`() {
        val result = SyncTimeUtils.compareTimestamps(
            "invalid",
            "2026-01-15 10:30:45.000"
        )
        assertTrue(result is TimeComparisonResult.ParseError)
    }

    @Test
    fun `compareTimestamps local parse failure returns ParseError`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:45.000",
            "bad-time"
        )
        assertTrue(result is TimeComparisonResult.ParseError)
    }

    @Test
    fun `compareTimestamps both parse failure returns ParseError`() {
        val result = SyncTimeUtils.compareTimestamps(
            "not-a-time",
            "also-not-a-time"
        )
        assertTrue(result is TimeComparisonResult.ParseError)
    }

    // ═══════════════════════════════════════════════════════════
    // compareTimestamps - 极端时间差
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareTimestamps large time difference - years apart`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:45.000",
            "2020-01-15 10:30:45.000"
        )
        assertTrue(result is TimeComparisonResult.RemoteNewer)
    }

    @Test
    fun `compareTimestamps one second difference within threshold`() {
        val result = SyncTimeUtils.compareTimestamps(
            "2026-01-15 10:30:46.000",
            "2026-01-15 10:30:45.000"
        )
        assertTrue(result is TimeComparisonResult.WithinThreshold)
    }

    // ═══════════════════════════════════════════════════════════
    // formatTimestamp - 格式化验证
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `formatTimestamp epoch zero`() {
        val result = SyncTimeUtils.formatTimestamp(0L)
        assertEquals("1970-01-01 00:00:00", result)
    }

    @Test
    fun `formatTimestamp negative timestamp`() {
        val result = SyncTimeUtils.formatTimestamp(-1000L)
        assertEquals("1969-12-31 23:59:59", result)
    }

    @Test
    fun `formatTimestamp round trip consistency`() {
        val now = System.currentTimeMillis()
        val formatted = SyncTimeUtils.formatTimestamp(now)
        // formatTimestamp 不带毫秒，parseUpdateTime 可解析不带毫秒的格式
        val parsed = SyncTimeUtils.parseUpdateTime(formatted)
        assertNotNull(parsed)
        // 毫秒被截断，差异不超过 999ms
        assertTrue("Round-trip drift should be < 1000ms, actual: ${now - parsed!!}",
            kotlin.math.abs(now - parsed) < 1000)
    }

    // ═══════════════════════════════════════════════════════════
    // parseUpdateTime - 跨日/月/年边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parseUpdateTime year boundary`() {
        val time = "2025-12-31 23:59:59.999"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)

        val nextSecond = SyncTimeUtils.parseUpdateTime("2026-01-01 00:00:00.000")
        assertNotNull(nextSecond)
        assertEquals(1L, nextSecond!! - result!!)
    }

    @Test
    fun `parseUpdateTime february 29 leap year`() {
        val time = "2024-02-29 12:00:00.000"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)
    }

    @Test
    fun `parseUpdateTime february 28 non leap year`() {
        val time = "2025-02-28 12:00:00.000"
        val result = SyncTimeUtils.parseUpdateTime(time)
        assertNotNull(result)
    }

    // ═══════════════════════════════════════════════════════════
    // parseUpdateTime - 不同毫秒位数
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parseUpdateTime with single digit millisecond`() {
        // "2026-01-15 10:30:45.1" - only 1 digit, not 3
        assertNull(SyncTimeUtils.parseUpdateTime("2026-01-15 10:30:45.1"))
    }

    @Test
    fun `parseUpdateTime with two digit milliseconds`() {
        assertNull(SyncTimeUtils.parseUpdateTime("2026-01-15 10:30:45.12"))
    }

    @Test
    fun `parseUpdateTime with four digit milliseconds`() {
        assertNull(SyncTimeUtils.parseUpdateTime("2026-01-15 10:30:45.1234"))
    }
}
