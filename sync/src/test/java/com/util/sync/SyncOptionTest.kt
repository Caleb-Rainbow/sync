package com.util.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * SyncOption 枚举边界测试。
 */
class SyncOptionTest {

    // ═══════════════════════════════════════════════════════════
    // fromInt - 有效值
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `fromInt 0 returns DEVICE_UPLOAD`() {
        assertEquals(SyncOption.DEVICE_UPLOAD, SyncOption.fromInt(0))
    }

    @Test
    fun `fromInt 1 returns SERVER_DOWNLOAD`() {
        assertEquals(SyncOption.SERVER_DOWNLOAD, SyncOption.fromInt(1))
    }

    @Test
    fun `fromInt 2 returns TWO_WAY_SYNC`() {
        assertEquals(SyncOption.TWO_WAY_SYNC, SyncOption.fromInt(2))
    }

    @Test
    fun `fromInt 3 returns SYNC_OFF`() {
        assertEquals(SyncOption.SYNC_OFF, SyncOption.fromInt(3))
    }

    // ═══════════════════════════════════════════════════════════
    // fromInt - 无效值回退
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `fromInt negative returns SYNC_OFF`() {
        assertEquals(SyncOption.SYNC_OFF, SyncOption.fromInt(-1))
    }

    @Test
    fun `fromInt negative large returns SYNC_OFF`() {
        assertEquals(SyncOption.SYNC_OFF, SyncOption.fromInt(-100))
    }

    @Test
    fun `fromInt beyond range returns SYNC_OFF`() {
        assertEquals(SyncOption.SYNC_OFF, SyncOption.fromInt(4))
    }

    @Test
    fun `fromInt large positive returns SYNC_OFF`() {
        assertEquals(SyncOption.SYNC_OFF, SyncOption.fromInt(999))
    }

    @Test
    fun `fromInt Int MAX_VALUE returns SYNC_OFF`() {
        assertEquals(SyncOption.SYNC_OFF, SyncOption.fromInt(Int.MAX_VALUE))
    }

    @Test
    fun `fromInt Int MIN_VALUE returns SYNC_OFF`() {
        assertEquals(SyncOption.SYNC_OFF, SyncOption.fromInt(Int.MIN_VALUE))
    }

    // ═══════════════════════════════════════════════════════════
    // ordinal 验证
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `ordinal values are stable`() {
        assertEquals(0, SyncOption.DEVICE_UPLOAD.ordinal)
        assertEquals(1, SyncOption.SERVER_DOWNLOAD.ordinal)
        assertEquals(2, SyncOption.TWO_WAY_SYNC.ordinal)
        assertEquals(3, SyncOption.SYNC_OFF.ordinal)
    }

    @Test
    fun `entries count is 4`() {
        assertEquals(4, SyncOption.entries.size)
    }

    // ═══════════════════════════════════════════════════════════
    // description 验证
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `all options have non-empty descriptions`() {
        SyncOption.entries.forEach { option ->
            assertTrue("Description for $option should not be empty", option.description.isNotEmpty())
        }
    }

    // ═══════════════════════════════════════════════════════════
    // round-trip: fromInt(ordinal) == self
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `round trip ordinal to fromInt is identity`() {
        SyncOption.entries.forEach { option ->
            assertEquals(option, SyncOption.fromInt(option.ordinal))
        }
    }
}
