package com.util.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * SyncableEntity 接口实现的边界测试。
 */
class SyncableEntityTest {

    private data class TestEntity(
        override val id: Long,
        override val officeId: Int?,
        override val canteenId: Int?,
        override val deviceNumber: String,
        override val createTime: String,
        override val updateTime: String,
        override val isDelete: Boolean,
        private val photoPath: String? = null
    ) : SyncableEntity {
        override fun getPhotoPath(): String? = photoPath
    }

    // ═══════════════════════════════════════════════════════════
    // 基本 getter
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `all fields accessible`() {
        val entity = TestEntity(
            id = 1,
            officeId = 100,
            canteenId = 200,
            deviceNumber = "DEV-001",
            createTime = "2026-01-01 00:00:00.000",
            updateTime = "2026-01-15 10:30:45.000",
            isDelete = false,
            photoPath = "/path/to/photo.jpg"
        )
        assertEquals(1L, entity.id)
        assertEquals(100, entity.officeId)
        assertEquals(200, entity.canteenId)
        assertEquals("DEV-001", entity.deviceNumber)
        assertEquals("2026-01-01 00:00:00.000", entity.createTime)
        assertEquals("2026-01-15 10:30:45.000", entity.updateTime)
        assertFalse(entity.isDelete)
        assertEquals("/path/to/photo.jpg", entity.getPhotoPath())
    }

    // ═══════════════════════════════════════════════════════════
    // 可空字段边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `officeId can be null`() {
        val entity = TestEntity(
            id = 1, officeId = null, canteenId = null,
            deviceNumber = "", createTime = "", updateTime = "", isDelete = false
        )
        assertNull(entity.officeId)
        assertNull(entity.canteenId)
    }

    @Test
    fun `photoPath default is null`() {
        val entity = TestEntity(
            id = 1, officeId = null, canteenId = null,
            deviceNumber = "", createTime = "", updateTime = "", isDelete = false
        )
        assertNull(entity.getPhotoPath())
    }

    // ═══════════════════════════════════════════════════════════
    // ID 边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `id can be Long MAX_VALUE`() {
        val entity = TestEntity(
            id = Long.MAX_VALUE, officeId = null, canteenId = null,
            deviceNumber = "", createTime = "", updateTime = "", isDelete = false
        )
        assertEquals(Long.MAX_VALUE, entity.id)
    }

    @Test
    fun `id can be negative`() {
        val entity = TestEntity(
            id = -1L, officeId = null, canteenId = null,
            deviceNumber = "", createTime = "", updateTime = "", isDelete = false
        )
        assertEquals(-1L, entity.id)
    }

    @Test
    fun `id can be zero`() {
        val entity = TestEntity(
            id = 0L, officeId = null, canteenId = null,
            deviceNumber = "", createTime = "", updateTime = "", isDelete = false
        )
        assertEquals(0L, entity.id)
    }

    // ═══════════════════════════════════════════════════════════
    // 空字符串字段
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `empty string fields are valid`() {
        val entity = TestEntity(
            id = 1, officeId = null, canteenId = null,
            deviceNumber = "", createTime = "", updateTime = "", isDelete = false
        )
        assertEquals("", entity.deviceNumber)
        assertEquals("", entity.createTime)
        assertEquals("", entity.updateTime)
    }

    // ═══════════════════════════════════════════════════════════
    // isDelete 标志
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `isDelete true`() {
        val entity = TestEntity(
            id = 1, officeId = null, canteenId = null,
            deviceNumber = "", createTime = "", updateTime = "", isDelete = true
        )
        assertTrue(entity.isDelete)
    }

    @Test
    fun `isDelete false`() {
        val entity = TestEntity(
            id = 1, officeId = null, canteenId = null,
            deviceNumber = "", createTime = "", updateTime = "", isDelete = false
        )
        assertFalse(entity.isDelete)
    }

    // ═══════════════════════════════════════════════════════════
    // data class 相等性（对 sync 去重和比较很重要）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `data class equality for same values`() {
        val e1 = TestEntity(1, null, null, "D", "2026-01-01", "2026-01-15", false)
        val e2 = TestEntity(1, null, null, "D", "2026-01-01", "2026-01-15", false)
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `data class inequality for different updateTime`() {
        val e1 = TestEntity(1, null, null, "D", "2026-01-01", "2026-01-15", false)
        val e2 = TestEntity(1, null, null, "D", "2026-01-01", "2026-01-16", false)
        assertNotEquals(e1, e2)
    }

    @Test
    fun `data class copy works`() {
        val original = TestEntity(1, null, null, "D", "2026-01-01", "2026-01-15", false)
        val modified = original.copy(updateTime = "2026-01-16 10:00:00.000")
        assertEquals("2026-01-16 10:00:00.000", modified.updateTime)
        assertEquals("2026-01-15", original.updateTime) // 原始不变
    }

    // ═══════════════════════════════════════════════════════════
    // associateBy 去重（batch mode 使用）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `associateBy keeps last entity for duplicate IDs`() {
        val entities = listOf(
            TestEntity(1, null, null, "D1", "2026-01-01", "2026-01-15", false),
            TestEntity(1, null, null, "D2", "2026-01-01", "2026-01-16", false),
        )
        val map = entities.associateBy { it.id }
        assertEquals(1, map.size)
        assertEquals("D2", map[1]!!.deviceNumber) // last one wins
    }
}
