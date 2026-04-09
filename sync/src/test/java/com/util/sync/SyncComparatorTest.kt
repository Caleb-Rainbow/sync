package com.util.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * SyncComparator 双向数据同步比较引擎的全面边界测试。
 * 涵盖：冲突解决、时钟偏差阈值、空数据、大数据量、各种 SyncOption 组合。
 */
class SyncComparatorTest {

    // ═══════════════════════════════════════════════════════════
    // 测试用实体
    // ═══════════════════════════════════════════════════════════

    private data class TestEntity(
        override val id: Long,
        override val officeId: Int? = null,
        override val canteenId: Int? = null,
        override val deviceNumber: String = "device-001",
        override val createTime: String = "2026-01-01 00:00:00.000",
        override val updateTime: String,
        override val isDelete: Boolean = false,
        private val photoPath: String? = null
    ) : SyncableEntity {
        override fun getPhotoPath(): String? = photoPath
    }

    private val comparator = SyncComparator<TestEntity>()

    // ═══════════════════════════════════════════════════════════
    // DEVICE_UPLOAD 模式
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `DEVICE_UPLOAD with local data returns ShouldUpload`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compare(local, null, SyncOption.DEVICE_UPLOAD)
        assertEquals(SyncDecision.ShouldUpload, result)
    }

    @Test
    fun `DEVICE_UPLOAD with no local data returns NoOp`() {
        val result = comparator.compare(null, null, SyncOption.DEVICE_UPLOAD)
        assertEquals(SyncDecision.NoOp, result)
    }

    @Test
    fun `DEVICE_UPLOAD ignores remote data`() {
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compare(null, remote, SyncOption.DEVICE_UPLOAD)
        assertEquals(SyncDecision.NoOp, result)
    }

    @Test
    fun `DEVICE_UPLOAD with both sides only uploads local`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:50.000")
        val result = comparator.compare(local, remote, SyncOption.DEVICE_UPLOAD)
        assertEquals(SyncDecision.ShouldUpload, result)
    }

    // ═══════════════════════════════════════════════════════════
    // SERVER_DOWNLOAD 模式
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `SERVER_DOWNLOAD with remote data returns ShouldDownload`() {
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compare(null, remote, SyncOption.SERVER_DOWNLOAD)
        assertEquals(SyncDecision.ShouldDownload, result)
    }

    @Test
    fun `SERVER_DOWNLOAD with no remote data returns NoOp`() {
        val result = comparator.compare(null, null, SyncOption.SERVER_DOWNLOAD)
        assertEquals(SyncDecision.NoOp, result)
    }

    @Test
    fun `SERVER_DOWNLOAD ignores local data`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compare(local, null, SyncOption.SERVER_DOWNLOAD)
        assertEquals(SyncDecision.NoOp, result)
    }

    @Test
    fun `SERVER_DOWNLOAD with both sides only downloads remote`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:50.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compare(local, remote, SyncOption.SERVER_DOWNLOAD)
        assertEquals(SyncDecision.ShouldDownload, result)
    }

    // ═══════════════════════════════════════════════════════════
    // TWO_WAY_SYNC - 远程为空，本地有数据 → 上传
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TWO_WAY_SYNC remote null local exists - upload`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compare(local, null, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldUpload, result)
    }

    // ═══════════════════════════════════════════════════════════
    // TWO_WAY_SYNC - 本地为空，远程有数据 → 下载
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TWO_WAY_SYNC local null remote exists - download`() {
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compare(null, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldDownload, result)
    }

    // ═══════════════════════════════════════════════════════════
    // TWO_WAY_SYNC - 双方都为空 → 无操作
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TWO_WAY_SYNC both null - noOp`() {
        val result = comparator.compare(null, null, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.NoOp, result)
    }

    // ═══════════════════════════════════════════════════════════
    // TWO_WAY_SYNC - 双方都有数据，时间比较
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TWO_WAY_SYNC remote newer - download`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:50.000")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldDownload, result)
    }

    @Test
    fun `TWO_WAY_SYNC local newer - upload`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:50.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40.000")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldUpload, result)
    }

    @Test
    fun `TWO_WAY_SYNC equal times - skip`() {
        val time = "2026-01-15 10:30:45.000"
        val local = TestEntity(id = 1, updateTime = time)
        val remote = TestEntity(id = 1, updateTime = time)
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.Skip, result)
    }

    // ═══════════════════════════════════════════════════════════
    // TWO_WAY_SYNC - 时钟偏差阈值精确边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TWO_WAY_SYNC within threshold 1ms - skip`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.001")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.Skip, result)
    }

    @Test
    fun `TWO_WAY_SYNC within threshold 2999ms - skip`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:47.999")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.Skip, result)
    }

    @Test
    fun `TWO_WAY_SYNC exactly at threshold 3000ms - skip`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:48.000")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.Skip, result)
    }

    @Test
    fun `TWO_WAY_SYNC just above threshold 3001ms - remote newer`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:48.001")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldDownload, result)
    }

    @Test
    fun `TWO_WAY_SYNC just above threshold 3001ms - local newer`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:48.001")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldUpload, result)
    }

    @Test
    fun `TWO_WAY_SYNC threshold boundary with non-zero base`() {
        val local = TestEntity(id = 1, updateTime = "2099-12-31 23:59:58.000")
        val remote = TestEntity(id = 1, updateTime = "2099-12-31 23:59:59.999")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.Skip, result)
    }

    // ═══════════════════════════════════════════════════════════
    // TWO_WAY_SYNC - 解析失败
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TWO_WAY_SYNC remote parse failure returns ParseError`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1, updateTime = "invalid-time")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertTrue(result is SyncDecision.ParseError)
    }

    @Test
    fun `TWO_WAY_SYNC local parse failure returns ParseError`() {
        val local = TestEntity(id = 1, updateTime = "bad")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertTrue(result is SyncDecision.ParseError)
    }

    @Test
    fun `TWO_WAY_SYNC both parse failure returns ParseError`() {
        val local = TestEntity(id = 1, updateTime = "aaa")
        val remote = TestEntity(id = 1, updateTime = "bbb")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertTrue(result is SyncDecision.ParseError)
    }

    @Test
    fun `ParseError contains correct metadata`() {
        val local = TestEntity(id = 42, updateTime = "bad-local")
        val remote = TestEntity(id = 42, updateTime = "bad-remote")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertTrue(result is SyncDecision.ParseError)
        val error = result as SyncDecision.ParseError
        assertEquals(42L, error.itemId)
        assertEquals("bad-remote", error.remoteTime)
        assertEquals("bad-local", error.localTime)
    }

    // ═══════════════════════════════════════════════════════════
    // SYNC_OFF 模式
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `SYNC_OFF always returns NoOp regardless of data`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:50.000")

        assertEquals(SyncDecision.NoOp, comparator.compare(local, remote, SyncOption.SYNC_OFF))
        assertEquals(SyncDecision.NoOp, comparator.compare(local, null, SyncOption.SYNC_OFF))
        assertEquals(SyncDecision.NoOp, comparator.compare(null, remote, SyncOption.SYNC_OFF))
        assertEquals(SyncDecision.NoOp, comparator.compare(null, null, SyncOption.SYNC_OFF))
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - 空数据集
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch empty maps returns empty result`() {
        val result = comparator.compareBatch(emptyMap(), emptyMap(), SyncOption.TWO_WAY_SYNC)
        assertTrue(result.toUpload.isEmpty())
        assertTrue(result.toDownload.isEmpty())
        assertEquals(0, result.skipped)
        assertTrue(result.errors.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - 仅远程数据
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch only remote data - TWO_WAY_SYNC downloads`() {
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compareBatch(
            emptyMap(),
            mapOf(1L to remote),
            SyncOption.TWO_WAY_SYNC
        )
        assertEquals(0, result.toUpload.size)
        assertEquals(1, result.toDownload.size)
        assertEquals(remote, result.toDownload[0])
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - 仅本地数据
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch only local data - TWO_WAY_SYNC uploads`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compareBatch(
            mapOf(1L to local),
            emptyMap(),
            SyncOption.TWO_WAY_SYNC
        )
        assertEquals(1, result.toUpload.size)
        assertEquals(0, result.toDownload.size)
        assertEquals(local, result.toUpload[0])
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - 混合场景
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch mixed scenario with all decision types`() {
        val local1 = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40.000") // local exists, no remote → upload
        val remote2 = TestEntity(id = 2, updateTime = "2026-01-15 10:30:40.000") // remote exists, no local → download
        val local3 = TestEntity(id = 3, updateTime = "2026-01-15 10:30:40.000") // remote newer → download
        val remote3 = TestEntity(id = 3, updateTime = "2026-01-15 10:30:50.000")
        val local4 = TestEntity(id = 4, updateTime = "2026-01-15 10:30:50.000") // local newer → upload
        val remote4 = TestEntity(id = 4, updateTime = "2026-01-15 10:30:40.000")
        val local5 = TestEntity(id = 5, updateTime = "2026-01-15 10:30:45.000") // equal → skip
        val remote5 = TestEntity(id = 5, updateTime = "2026-01-15 10:30:45.000")
        val local6 = TestEntity(id = 6, updateTime = "invalid") // parse error
        val remote6 = TestEntity(id = 6, updateTime = "2026-01-15 10:30:45.000")

        val result = comparator.compareBatch(
            mapOf(1L to local1, 3L to local3, 4L to local4, 5L to local5, 6L to local6),
            mapOf(2L to remote2, 3L to remote3, 4L to remote4, 5L to remote5, 6L to remote6),
            SyncOption.TWO_WAY_SYNC
        )

        assertEquals(2, result.toUpload.size) // id=1, id=4
        assertEquals(2, result.toDownload.size) // id=2, id=3
        assertEquals(1, result.skipped) // id=5
        assertEquals(1, result.errors.size) // id=6
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - ID 不重叠场景
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch non-overlapping IDs - all upload or download`() {
        val localOnly = (1L..5L).map { it to TestEntity(id = it, updateTime = "2026-01-15 10:30:45.000") }.toMap()
        val remoteOnly = (6L..10L).map { it to TestEntity(id = it, updateTime = "2026-01-15 10:30:45.000") }.toMap()

        val result = comparator.compareBatch(localOnly, remoteOnly, SyncOption.TWO_WAY_SYNC)
        assertEquals(5, result.toUpload.size)
        assertEquals(5, result.toDownload.size)
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - 大数据量
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch large dataset 1000 items`() {
        val localMap = (1L..1000L).associateWith { id ->
            TestEntity(id = id, updateTime = "2026-01-15 10:30:${String.format("%02d", (id % 60).toInt())}.${String.format("%03d", (id % 1000).toInt())}")
        }
        val remoteMap = (1L..1000L).associateWith { id ->
            TestEntity(id = id, updateTime = "2026-01-15 10:35:${String.format("%02d", (id % 60).toInt())}.${String.format("%03d", (id % 1000).toInt())}")
        }

        val result = comparator.compareBatch(localMap, remoteMap, SyncOption.TWO_WAY_SYNC)
        assertEquals(1000, result.toDownload.size)
        assertEquals(0, result.toUpload.size)
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - 全部跳过（时钟偏差内）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch all items within skew threshold - all skipped`() {
        val localMap = (1L..100L).associateWith { id ->
            TestEntity(id = id, updateTime = "2026-01-15 10:30:45.000")
        }
        val remoteMap = (1L..100L).associateWith { id ->
            TestEntity(id = id, updateTime = "2026-01-15 10:30:45.001")
        }

        val result = comparator.compareBatch(localMap, remoteMap, SyncOption.TWO_WAY_SYNC)
        assertEquals(100, result.skipped)
        assertEquals(0, result.toUpload.size)
        assertEquals(0, result.toDownload.size)
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - 全部解析失败
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch all parse errors`() {
        val localMap = (1L..10L).associateWith { id ->
            TestEntity(id = id, updateTime = "bad-local-$id")
        }
        val remoteMap = (1L..10L).associateWith { id ->
            TestEntity(id = id, updateTime = "bad-remote-$id")
        }

        val result = comparator.compareBatch(localMap, remoteMap, SyncOption.TWO_WAY_SYNC)
        assertEquals(10, result.errors.size)
        assertEquals(0, result.toUpload.size)
        assertEquals(0, result.toDownload.size)
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - SYNC_OFF 忽略所有数据
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch SYNC_OFF ignores all data`() {
        val localMap = mapOf(1L to TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000"))
        val remoteMap = mapOf(1L to TestEntity(id = 1, updateTime = "2026-01-15 10:30:50.000"))

        val result = comparator.compareBatch(localMap, remoteMap, SyncOption.SYNC_OFF)
        assertTrue(result.toUpload.isEmpty())
        assertTrue(result.toDownload.isEmpty())
        assertEquals(0, result.skipped)
        assertTrue(result.errors.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - DEVICE_UPLOAD 忽略远程数据
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch DEVICE_UPLOAD only uploads local data ignoring remote`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:50.000")

        val result = comparator.compareBatch(
            mapOf(1L to local),
            mapOf(1L to remote),
            SyncOption.DEVICE_UPLOAD
        )
        assertEquals(1, result.toUpload.size)
        assertEquals(local, result.toUpload[0])
        assertEquals(0, result.toDownload.size)
    }

    // ═══════════════════════════════════════════════════════════
    // compareBatch - SERVER_DOWNLOAD 忽略本地数据
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch SERVER_DOWNLOAD only downloads remote data ignoring local`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:50.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40.000")

        val result = comparator.compareBatch(
            mapOf(1L to local),
            mapOf(1L to remote),
            SyncOption.SERVER_DOWNLOAD
        )
        assertEquals(1, result.toDownload.size)
        assertEquals(remote, result.toDownload[0])
        assertEquals(0, result.toUpload.size)
    }

    // ═══════════════════════════════════════════════════════════
    // 自定义阈值
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `custom threshold zero - no tolerance`() {
        val customComparator = SyncComparator<TestEntity>(timeSkewThresholdMs = 0)
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.001")

        val result = customComparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldDownload, result)
    }

    @Test
    fun `custom threshold very large - everything is skip`() {
        val customComparator = SyncComparator<TestEntity>(timeSkewThresholdMs = Long.MAX_VALUE)
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40.000")
        val remote = TestEntity(id = 1, updateTime = "2099-12-31 23:59:59.999")

        val result = customComparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.Skip, result)
    }

    // ═══════════════════════════════════════════════════════════
    // 无毫秒格式的时间比较
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TWO_WAY_SYNC with non-millisecond format works`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:50")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldDownload, result)
    }

    @Test
    fun `TWO_WAY_SYNC mixed formats - with and without milliseconds`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40.000")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.Skip, result)
    }

    // ═══════════════════════════════════════════════════════════
    // isDelete 标志不影响同步决策
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TWO_WAY_SYNC deleted entity is still synced`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:50.000", isDelete = true)
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:40.000", isDelete = false)

        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldUpload, result)
    }

    // ═══════════════════════════════════════════════════════════
    // ID 一致性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `compareBatch result entities have correct IDs`() {
        val local = (1L..5L).associateWith { TestEntity(id = it, updateTime = "2026-01-15 10:30:45.000") }
        val result = comparator.compareBatch(local, emptyMap(), SyncOption.TWO_WAY_SYNC)

        val uploadIds = result.toUpload.map { it.id }.sorted()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), uploadIds)
    }

    @Test
    fun `compareBatch with Long MAX_VALUE IDs`() {
        val local = TestEntity(id = Long.MAX_VALUE, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compareBatch(
            mapOf(Long.MAX_VALUE to local),
            emptyMap(),
            SyncOption.TWO_WAY_SYNC
        )
        assertEquals(1, result.toUpload.size)
        assertEquals(Long.MAX_VALUE, result.toUpload[0].id)
    }

    @Test
    fun `compareBatch with negative IDs`() {
        val local = TestEntity(id = -1, updateTime = "2026-01-15 10:30:45.000")
        val result = comparator.compareBatch(
            mapOf(-1L to local),
            emptyMap(),
            SyncOption.TWO_WAY_SYNC
        )
        assertEquals(1, result.toUpload.size)
        assertEquals(-1L, result.toUpload[0].id)
    }

    // ═══════════════════════════════════════════════════════════
    // 同一 ID 但本地/远程都存在且时间差在阈值外
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TWO_WAY_SYNC exact second difference beyond threshold`() {
        val local = TestEntity(id = 1, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1, updateTime = "2026-01-15 10:30:49.000")
        val result = comparator.compare(local, remote, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldDownload, result)
    }

    // ═══════════════════════════════════════════════════════════
    // 可空字段边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `entity with null optional fields syncs correctly`() {
        val local = TestEntity(
            id = 1,
            officeId = null,
            canteenId = null,
            deviceNumber = "",
            createTime = "",
            updateTime = "2026-01-15 10:30:45.000"
        )
        val result = comparator.compare(local, null, SyncOption.TWO_WAY_SYNC)
        assertEquals(SyncDecision.ShouldUpload, result)
    }

    // ═══════════════════════════════════════════════════════════
    // CompareResult 数据完整性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `SyncCompareResult preserves entity data`() {
        val entity = TestEntity(
            id = 42,
            officeId = 100,
            canteenId = 200,
            deviceNumber = "DEV-001",
            createTime = "2026-01-01 00:00:00.000",
            updateTime = "2026-01-15 10:30:45.000",
            isDelete = false
        )
        val result = comparator.compareBatch(
            mapOf(42L to entity),
            emptyMap(),
            SyncOption.TWO_WAY_SYNC
        )
        val uploaded = result.toUpload[0]
        assertEquals(42L, uploaded.id)
        assertEquals(100, uploaded.officeId)
        assertEquals(200, uploaded.canteenId)
        assertEquals("DEV-001", uploaded.deviceNumber)
        assertFalse(uploaded.isDelete)
    }
}
