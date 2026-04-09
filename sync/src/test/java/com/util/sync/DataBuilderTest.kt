package com.util.sync

import androidx.work.Data
import org.junit.Assert.*
import org.junit.Test

/**
 * 同步模块公共工具和 Data 构建的边界测试。
 */
class DataBuilderTest {

    // ═══════════════════════════════════════════════════════════
    // createSuccessData
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `createSuccessData stores message`() {
        val data = createSuccessData("同步成功")
        assertEquals("同步成功", data.getString("successMessage"))
    }

    @Test
    fun `createSuccessData empty message`() {
        val data = createSuccessData("")
        assertEquals("", data.getString("successMessage"))
    }

    @Test
    fun `createSuccessData long message`() {
        val longMsg = "a".repeat(10000)
        val data = createSuccessData(longMsg)
        assertEquals(longMsg, data.getString("successMessage"))
    }

    @Test
    fun `createSuccessData with special characters`() {
        val msg = "成功!\n第二行\ttab<>&\"'"
        val data = createSuccessData(msg)
        assertEquals(msg, data.getString("successMessage"))
    }

    // ═══════════════════════════════════════════════════════════
    // createFailData
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `createFailData stores message`() {
        val data = createFailData("同步失败")
        assertEquals("同步失败", data.getString("failMessage"))
    }

    @Test
    fun `createFailData empty message`() {
        val data = createFailData("")
        assertEquals("", data.getString("failMessage"))
    }

    @Test
    fun `createFailData multiline errors`() {
        val msg = "错误1\n错误2\n错误3"
        val data = createFailData(msg)
        assertEquals(msg, data.getString("failMessage"))
    }

    // ═══════════════════════════════════════════════════════════
    // Data key constants
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `KEY constants are stable`() {
        assertEquals("KEY_LAST_SYNC_TIME", KEY_LAST_SYNC_TIME)
        assertEquals("KEY_SYNC_START_TIME", KEY_SYNC_START_TIME)
        assertEquals("KEY_SYNC_SESSION_ID", KEY_SYNC_SESSION_ID)
    }

    @Test
    fun `GLOBAL_SYNC_WORK_NAME is stable`() {
        assertEquals("global_sync_work", GLOBAL_SYNC_WORK_NAME)
    }

    // ═══════════════════════════════════════════════════════════
    // Data 构建边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Data with all sync keys`() {
        val data = Data.Builder()
            .putString(KEY_LAST_SYNC_TIME, "2026-01-15 10:30:45.000")
            .putString(KEY_SYNC_START_TIME, "2026-01-15 10:35:00.000")
            .putString(KEY_SYNC_SESSION_ID, "session-123")
            .build()

        assertEquals("2026-01-15 10:30:45.000", data.getString(KEY_LAST_SYNC_TIME))
        assertEquals("2026-01-15 10:35:00.000", data.getString(KEY_SYNC_START_TIME))
        assertEquals("session-123", data.getString(KEY_SYNC_SESSION_ID))
    }

    @Test
    fun `Data with null values returns null`() {
        val data = Data.Builder()
            .putString(KEY_LAST_SYNC_TIME, null)
            .build()

        assertNull(data.getString(KEY_LAST_SYNC_TIME))
    }

    @Test
    fun `Data with missing key returns null`() {
        val data = Data.Builder().build()
        assertNull(data.getString(KEY_LAST_SYNC_TIME))
    }
}
