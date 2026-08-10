package com.util.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * SyncTimeUtils 回归测试 —— 针对 BUG-5 修复（移除不可达的 UTC 回退分支，
 * 统一按设备本地时区解析）后的行为保障。
 *
 * 核心契约：客户端与服务器使用同一（本地）时区，parseUpdateTime 对本地时间串
 * 与服务器时间串一视同仁，均按 ZoneId.systemDefault() 解析为 epoch 毫秒。
 * 现有 SyncTimeUtilsTest 覆盖格式边界；本类聚焦时区一致性回归。
 */
class SyncTimeUtilsRegressionTest {

    // ═══════════════════════════════════════════════════════════
    // 时区一致性：本地生成串与解析串在同一基准下 round-trip
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `formatTimestamp then parseUpdateTime round trips under system zone`() {
        val now = System.currentTimeMillis()
        val formatted = SyncTimeUtils.formatTimestamp(now)
        val parsed = SyncTimeUtils.parseUpdateTime(formatted)

        assertNotNull(parsed)
        // formatTimestamp 不带毫秒，漂移 < 1000ms
        assertTrue("round-trip drift < 1000ms, actual=${now - parsed!!}", kotlin.math.abs(now - parsed) < 1000)
    }

    @Test
    fun `local and server identical timestamps parse to same epoch`() {
        // 模拟客户端与服务器使用同一时区生成的时间串
        val ts = "2026-08-10 14:30:45"
        val fromLocal = SyncTimeUtils.parseUpdateTime(ts)
        val fromRemote = SyncTimeUtils.parseUpdateTime(ts)
        assertNotNull(fromLocal)
        assertNotNull(fromRemote)
        assertEquals("同一时区下相同时间串应解析为相同 epoch", fromLocal, fromRemote)
    }

    // ═══════════════════════════════════════════════════════════
    // 带毫秒 / 不带毫秒 等价（兼容性回归）
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `same instant with and without ms parses equivalently`() {
        val withMs = "2026-08-10 14:30:45.000"
        val withoutMs = "2026-08-10 14:30:45"
        assertEquals(
            SyncTimeUtils.parseUpdateTime(withMs),
            SyncTimeUtils.parseUpdateTime(withoutMs),
        )
    }

    // ═══════════════════════════════════════════════════════════
    // 已知本地时刻 → 已知 epoch（时区正确性锚点）
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `known local instant maps to expected epoch`() {
        // 2026-01-01 00:00:00 在系统时区下的 epoch，由 java.time 独立计算作为参照
        val ref = java.time.LocalDateTime.of(2026, 1, 1, 0, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val parsed = SyncTimeUtils.parseUpdateTime("2026-01-01 00:00:00")
        assertNotNull(parsed)
        assertEquals(ref, parsed)
    }

    // ═══════════════════════════════════════════════════════════
    // 解析失败仍返回 null
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `malformed input returns null`() {
        assertNull(SyncTimeUtils.parseUpdateTime("not-a-time"))
        assertNull(SyncTimeUtils.parseUpdateTime(""))
        assertNull(SyncTimeUtils.parseUpdateTime("2026/01/01 00:00:00"))
    }
}
