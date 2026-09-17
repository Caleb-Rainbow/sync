package com.util.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.util.ktor.model.ResultModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BaseCompareWork 集成测试。
 *
 * 通过手写的 FakeSyncRepository（记录调用、可注入返回值）与 TestSyncConfigProvider，
 * 验证三种同步模式、覆盖本地模式、上传一致性保护等核心行为。
 *
 * 约定与 SyncComparatorTest/AbstractSyncConfigProviderTest 一致：
 * JUnit4 + org.junit.Assert + 反引号命名 + 内联私有 TestEntity + 手写 fake。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BaseCompareWorkTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // BaseCompareWork.doWork() 内部调用 libLogI/libLogE 等扩展函数；
        // 未 init 时 LibLogManager 使用 no-op 默认实现，测试可安全调用。
    }

    // ═══════════════════════════════════════════════════════════
    // 测试夹具
    // ═══════════════════════════════════════════════════════════

    /** 测试用的同步实体（默认参数，与 SyncComparatorTest 风格一致）。 */
    private data class TestEntity(
        override val id: Long,
        override val officeId: Int? = null,
        override val canteenId: Int? = null,
        override val deviceNumber: String = "DEV-001",
        override val createTime: String = "2026-01-01 00:00:00",
        override val updateTime: String,
        override val isDelete: Boolean = false,
        private val photoPath: String? = null,
    ) : SyncableEntity {
        override fun getPhotoPath(): String? = photoPath
    }

    /** 手写 FakeSyncRepository：记录所有调用，返回值可注入。 */
    private class FakeSyncRepository : SyncRepository<TestEntity> {
        // 可注入的返回值
        var remoteBatchResult: List<TestEntity> = emptyList()
        var localBatchResult: List<TestEntity> = emptyList()
        var remoteUpsertSuccess: Boolean = true
        var remoteUpsertMessage: String = "ok"

        // 调用记录（多线程 BaseCompareWork 内顺序处理，但用并发安全集合以防万一）
        val localUpsertCalls = ConcurrentLinkedQueue<List<TestEntity>>()
        val remoteUpsertCalls = ConcurrentLinkedQueue<List<TestEntity>>()
        var deleteAllCalled = false
        val deleteAllExceptCalls = ConcurrentLinkedQueue<Set<Long>>()

        // —— 批量模式（被测主路径）——
        // 返回注入的 remoteBatchResult；若需模拟远程失败，设 remoteBatchError=true。
        var remoteBatchError: Boolean = false
        override suspend fun remoteGetAfterUpdateTimeBatch(lastSyncTime: String): ResultModel<List<TestEntity>> =
            if (remoteBatchError) ResultModel.error("forced fetch error")
            else ResultModel.success(remoteBatchResult)

        override suspend fun localGetAfterUpdateTimeBatch(lastSyncTime: String): List<TestEntity> = localBatchResult

        override suspend fun localBatchUpsert(data: List<TestEntity>) {
            localUpsertCalls.add(data)
        }

        override suspend fun remoteBatchUpsert(data: List<TestEntity>): ResultModel<String> {
            remoteUpsertCalls.add(data)
            return if (remoteUpsertSuccess) ResultModel.success(remoteUpsertMessage) else ResultModel.error("upload failed")
        }

        override suspend fun localDeleteAll() {
            deleteAllCalled = true
        }

        override suspend fun localDeleteAllExcept(retainedIds: Set<Long>) {
            deleteAllExceptCalls.add(retainedIds)
        }

        // —— ID 单查模式（@Deprecated，本测试不覆盖该模式，返回空）——
        @Deprecated("dep")
        override suspend fun remoteGetAfterUpdateTime(lastSyncTime: String): ResultModel<List<Long>> =
            ResultModel.success(emptyList())

        @Deprecated("dep")
        override suspend fun localGetAfterUpdateTime(lastSyncTime: String): List<Long> = emptyList()

        @Deprecated("dep")
        override suspend fun remoteGetById(id: Long): ResultModel<TestEntity> = ResultModel.error("not used")

        @Deprecated("dep")
        override suspend fun localGetById(id: Long): TestEntity? = null
    }

    /** 测试用配置：syncMode 默认批量(1)，可调 batchSize/uploadBatchSize。 */
    private class TestConfig : AbstractSyncConfigProvider() {
        override fun doSaveSuccessfulSyncTime(time: String) { /* 记录到内存字段即可 */
            savedTime = time
        }

        override fun getAllTask(): List<SyncTaskDefinition> = emptyList()
        var savedTime: String = ""
    }

    /** 构造一个可配置的测试 Worker。 */
    private class TestCompareWork(
        context: Context,
        params: WorkerParameters,
        val repo: FakeSyncRepository,
        val config: TestConfig,
        val optionInt: Int,
        val downloadMode: Int = 0,
    ) : BaseCompareWork<TestEntity, FakeSyncRepository>(context, params) {
        override val workName = "TestCompareWork"
        override val workChineseName = "测试同步"
        override val syncOptionName = "测试数据"
        override val repository: FakeSyncRepository = repo
        override val syncOptionInt: Int get() = optionInt
        override val serverDownloadModeInt: Int = downloadMode
        override val syncConfig: TestConfig = config
    }

    /** 运行 Worker.doWork() 并返回结果（批量模式默认 syncMode=1）。 */
    private fun runWork(
        repo: FakeSyncRepository,
        config: TestConfig,
        optionInt: Int,
        downloadMode: Int = 0,
        lastSyncTime: String = "2026-01-01 00:00:00",
    ): androidx.work.ListenableWorker.Result {
        val factory = object : androidx.work.WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker =
                TestCompareWork(appContext, workerParameters, repo, config, optionInt, downloadMode)
        }
        val worker = TestListenableWorkerBuilder<TestCompareWork>(context)
            .setWorkerFactory(factory)
            .setInputData(
                androidx.work.workDataOf(
                    KEY_LAST_SYNC_TIME to lastSyncTime,
                    KEY_SYNC_SESSION_ID to "test-session",
                )
            )
            .build()
        return runBlocking { worker.doWork() }
    }

    // ═══════════════════════════════════════════════════════════
    // 用例 1: SYNC_OFF 直接成功，不触发任何数据操作
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `SYNC_OFF returns success without data operations`() {
        val repo = FakeSyncRepository()
        val config = TestConfig()
        val result = runWork(repo, config, optionInt = 3)

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertTrue("不应有任何上传", repo.remoteUpsertCalls.isEmpty())
        assertTrue("不应有任何本地写入", repo.localUpsertCalls.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 用例 2: DEVICE_UPLOAD 仅上传本地变更，不下载
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `DEVICE_UPLOAD uploads local changes and skips download`() {
        val local = TestEntity(id = 1L, updateTime = "2026-01-15 10:30:45")
        val repo = FakeSyncRepository().apply { localBatchResult = listOf(local) }
        val config = TestConfig()

        val result = runWork(repo, config, optionInt = 0)

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(1, repo.remoteUpsertCalls.size)
        assertEquals(listOf(1L), repo.remoteUpsertCalls.first().map { it.id })
    }

    // ═══════════════════════════════════════════════════════════
    // 用例 3: SERVER_DOWNLOAD 增量模式仅下载服务端变更
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `SERVER_DOWNLOAD incremental downloads remote changes`() {
        val remote = TestEntity(id = 2L, updateTime = "2026-01-15 11:00:00")
        val repo = FakeSyncRepository().apply { remoteBatchResult = listOf(remote) }
        val config = TestConfig()

        val result = runWork(repo, config, optionInt = 1, downloadMode = 0)

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(1, repo.localUpsertCalls.size)
        assertEquals(listOf(2L), repo.localUpsertCalls.first().map { it.id })
        assertTrue("增量模式不应清表", !repo.deleteAllCalled)
        assertTrue("增量模式不应触发 deleteAllExcept", repo.deleteAllExceptCalls.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 用例 4: 覆盖本地模式（SERVER_DOWNLOAD + serverDownloadModeInt=1）
    //         全量获取 → 先 upsert → 再 deleteAllExcept(retainedIds)
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `overwrite mode writes all then deletes except retained ids`() {
        val remote = listOf(
            TestEntity(id = 10L, updateTime = "2026-01-15 09:00:00"),
            TestEntity(id = 20L, updateTime = "2026-01-15 09:05:00"),
        )
        val repo = FakeSyncRepository().apply { remoteBatchResult = remote }
        val config = TestConfig()

        val result = runWork(repo, config, optionInt = 1, downloadMode = 1)

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        // 先写入全量
        assertEquals(1, repo.localUpsertCalls.size)
        assertEquals(setOf(10L, 20L), repo.localUpsertCalls.first().map { it.id }.toSet())
        // 再删除不在新数据集中的旧记录
        assertEquals(1, repo.deleteAllExceptCalls.size)
        assertEquals(setOf(10L, 20L), repo.deleteAllExceptCalls.first())
    }

    // ═══════════════════════════════════════════════════════════
    // 用例 5: TWO_WAY 时间戳决胜 —— remote 新 → 下载；local 新 → 上传；≤3s → skip
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `TWO_WAY remote newer downloads and does not upload`() {
        val local = TestEntity(id = 1L, updateTime = "2026-01-15 10:00:00")
        val remote = TestEntity(id = 1L, updateTime = "2026-01-15 11:00:00")
        val repo = FakeSyncRepository().apply {
            localBatchResult = listOf(local)
            remoteBatchResult = listOf(remote)
        }
        val config = TestConfig()

        val result = runWork(repo, config, optionInt = 2)

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertTrue("remote 较新应下载到本地", repo.localUpsertCalls.any { it.any { e -> e.id == 1L } })
        assertTrue("不应上传", repo.remoteUpsertCalls.isEmpty())
    }

    @Test
    fun `TWO_WAY within 3s skew downloads newer remote data`() {
        val local = TestEntity(id = 1L, updateTime = "2026-01-15 10:30:45.000")
        val remote = TestEntity(id = 1L, updateTime = "2026-01-15 10:30:47.000") // 差 2s
        val repo = FakeSyncRepository().apply {
            localBatchResult = listOf(local)
            remoteBatchResult = listOf(remote)
        }
        val config = TestConfig()

        val result = runWork(repo, config, optionInt = 2)

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(listOf(remote), repo.localUpsertCalls.single())
        assertTrue("远端较新，不上传", repo.remoteUpsertCalls.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 用例 6: 远程上传失败时，不写入 fromUpload 本地数据（一致性保护）
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `DEVICE_UPLOAD when remote upsert fails skips local upload-state write`() {
        val local = TestEntity(id = 1L, updateTime = "2026-01-15 10:00:00")
        val repo = FakeSyncRepository().apply {
            localBatchResult = listOf(local)
            remoteUpsertSuccess = false
        }
        val config = TestConfig()

        val result = runWork(repo, config, optionInt = 0)

        // upload 失败 → 整体 Result.failure
        assertTrue(result is androidx.work.ListenableWorker.Result.Failure)
        // 应尝试过上传
        assertEquals(1, repo.remoteUpsertCalls.size)
        // 但不应有任何本地 upsert（因为没有成功上传项需要回写）
        assertTrue("上传失败时不应写本地", repo.localUpsertCalls.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 用例 7: 无待同步数据时直接成功
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `empty datasets returns success`() {
        val repo = FakeSyncRepository() // 两边都空
        val config = TestConfig()

        val result = runWork(repo, config, optionInt = 2)

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertTrue(repo.localUpsertCalls.isEmpty())
        assertTrue(repo.remoteUpsertCalls.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 用例 8: lastSyncTime 缺失时任务失败（参数校验）
    // ═══════════════════════════════════════════════════════════
    @Test
    fun `missing lastSyncTime fails the task`() {
        val repo = FakeSyncRepository()
        val config = TestConfig()
        val factory = object : androidx.work.WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker =
                TestCompareWork(appContext, workerParameters, repo, config, optionInt = 0)
        }
        val worker = TestListenableWorkerBuilder<TestCompareWork>(context)
            .setWorkerFactory(factory)
            // 故意不传 KEY_LAST_SYNC_TIME
            .build()

        val result = runBlocking { worker.doWork() }

        assertTrue(result is androidx.work.ListenableWorker.Result.Failure)
    }
}
