package com.util.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.util.ktor.model.ResultModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// 同步完整性回归测试：断言修复后的安全行为。
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SyncIntegrityRegressionTest {
    data class E(
        override val id: Long,
        override val updateTime: String = "2026-09-17 10:00:00",
        override val isDelete: Boolean = false,
        val value: String = "old",
        val photo: String? = null,
    ) : SyncableEntity {
        override val officeId: Int? = null
        override val canteenId: Int? = null
        override val deviceNumber = "test"
        override val createTime = "2026-01-01 00:00:00"
        override fun getPhotoPath() = photo
    }

    class C : AbstractSyncConfigProvider() {
        override fun getAllTask() = emptyList<SyncTaskDefinition>()
        override fun doSaveSuccessfulSyncTime(time: String) {}
    }

    class R : SyncRepository<E> {
        var remote: List<E> = emptyList()
        val local = linkedMapOf<Long, E>()
        val uploads = mutableListOf<E>()
        var nullBatch = false
        var failedId: Long? = null
        var failCleanup = false
        var beforeUploadReturns: () -> Unit = {}
        override suspend fun remoteGetAfterUpdateTimeBatch(lastSyncTime: String): ResultModel<List<E>> =
            if (nullBatch) ResultModel(code = 200, message = "ok", data = null)
            else ResultModel.success(remote.filter { it.updateTime > lastSyncTime })
        override suspend fun localGetAfterUpdateTimeBatch(lastSyncTime: String) =
            local.values.filter { it.updateTime > lastSyncTime }
        @Deprecated("probe")
        override suspend fun remoteGetAfterUpdateTime(lastSyncTime: String) =
            ResultModel.success(remote.filter { it.updateTime > lastSyncTime }.map { it.id })
        @Deprecated("probe")
        override suspend fun localGetAfterUpdateTime(lastSyncTime: String) =
            local.values.filter { it.updateTime > lastSyncTime }.map { it.id }
        @Deprecated("probe")
        override suspend fun remoteGetById(id: Long): ResultModel<E> =
            if (id == failedId) ResultModel.error("detail failed")
            else ResultModel.success(remote.first { it.id == id })
        @Deprecated("probe")
        override suspend fun localGetById(id: Long) = local[id]
        override suspend fun remoteBatchUpsert(data: List<E>): ResultModel<String> {
            uploads.addAll(data)
            beforeUploadReturns()
            return ResultModel.success("ok")
        }
        override suspend fun localBatchUpsert(data: List<E>) { data.forEach { local[it.id] = it } }
        override suspend fun localDeleteAll() { local.clear() }
        override suspend fun localDeleteAllExcept(retainedIds: Set<Long>) {
            if (failCleanup) error("database busy")
            local.keys.retainAll(retainedIds)
        }
    }

    class W(
        context: Context, params: WorkerParameters,
        override val repository: R, override val syncConfig: C,
        override val syncOptionInt: Int, override val serverDownloadModeInt: Int,
        val skipId: Long?, val transformUpload: Boolean, val callbackMode: Boolean,
    ) : BaseCompareWork<E, R>(context, params) {
        override val workName = "probe"
        override val workChineseName = "probe"
        override val syncOptionName = "probe"
        override suspend fun handleRemoteDataForDownload(
            data: E, failureMessages: MutableList<String>,
            onLocalUpdate: (E) -> Unit, onRemoteUpdate: (E) -> Unit,
        ): E? {
            if (data.id == skipId) {
                failureMessages.add("image processing failed")
                return null
            }
            if (callbackMode) {
                onLocalUpdate(data.copy(value = "local callback"))
                onRemoteUpdate(data.copy(value = "remote callback"))
            }
            return data
        }
        override suspend fun handleLocalDataForUpload(
            data: E, failureMessages: MutableList<String>,
            onLocalUpdate: (E) -> Unit, onRemoteUpdate: (E) -> Unit,
        ): E {
            if (callbackMode) {
                onLocalUpdate(data.copy(value = "local callback"))
                onRemoteUpdate(data.copy(value = "remote callback"))
            }
            return if (transformUpload) data.copy(photo = "https://example.test/file.jpg") else data
        }
    }

    private fun run(
        repo: R, option: Int = 1, overwrite: Int = 1, mode: Int = 1,
        skipId: Long? = null, transformUpload: Boolean = false,
        since: String = "2026-09-17 09:00:00", callbackMode: Boolean = false, deleteFiles: Boolean = false,
    ): ListenableWorker.Result {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                W(appContext, workerParameters, repo, C().apply { syncMode = mode; isDeleteLocalFile = deleteFiles }, option, overwrite, skipId, transformUpload, callbackMode)
        }
        val worker = TestListenableWorkerBuilder<W>(context)
            .setWorkerFactory(factory)
            .setInputData(workDataOf(KEY_LAST_SYNC_TIME to since, KEY_SYNC_SESSION_ID to "review"))
            .build()
        return runBlocking { worker.doWork() }
    }

    @Test fun `partial ID fetch retains all local records`() {
        val repo = R().apply {
            remote = listOf(E(1), E(2)); local.putAll(remote.associateBy { it.id }); failedId = 2
        }
        assertTrue(run(repo, mode = 0) is ListenableWorker.Result.Failure)
        assertEquals(setOf(1L, 2L), repo.local.keys)
    }

    @Test fun `failed preprocessing retains previously valid records`() {
        val repo = R().apply { remote = listOf(E(1), E(2)); local.putAll(remote.associateBy { it.id }) }
        assertTrue(run(repo, skipId = 2) is ListenableWorker.Result.Failure)
        assertEquals(setOf(1L, 2L), repo.local.keys)
    }

    @Test fun `null success payload fails without deleting local data`() {
        val repo = R().apply { nullBatch = true; local[1] = E(1) }
        assertTrue(run(repo) is ListenableWorker.Result.Failure)
        assertEquals(setOf(1L), repo.local.keys)
    }

    @Test fun `cleanup failure is reported as failure`() {
        val repo = R().apply { remote = listOf(E(1)); local[2] = E(2); failCleanup = true }
        assertTrue(run(repo) is ListenableWorker.Result.Failure)
        assertTrue(repo.local.containsKey(2))
    }

    @Test fun `two second newer tombstone is downloaded before cursor advances`() {
        val repo = R().apply {
            local[1] = E(1)
            remote = listOf(E(1, updateTime = "2026-09-17 10:00:02", isDelete = true))
        }
        assertTrue(run(repo, option = 2, overwrite = 0) is ListenableWorker.Result.Success)
        assertTrue(repo.local.getValue(1).isDelete)
        assertTrue(run(repo, option = 2, overwrite = 0, since = "2026-09-17 10:01:00") is ListenableWorker.Result.Success)
        assertTrue(repo.local.getValue(1).isDelete)
    }

    @Test fun `upload metadata write preserves local edit made during request`() {
        val original = E(1, photo = "/tmp/old.jpg")
        val repo = R().apply {
            local[1] = original
            beforeUploadReturns = { local[1] = original.copy(value = "new edit", updateTime = "2026-09-17 10:02:00") }
        }
        assertTrue(run(repo, option = 0, overwrite = 0, transformUpload = true) is ListenableWorker.Result.Failure)
        assertEquals("new edit", repo.local.getValue(1).value)
        assertEquals("2026-09-17 10:02:00", repo.local.getValue(1).updateTime)
    }

    @Test fun `disabled worker succeeds as noop and marks skipped for host ui`() {
        val repo = R().apply { remote = listOf(E(1)) }
        val result = run(repo, option = 3, overwrite = 0)
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(result.outputData.getBoolean(KEY_SYNC_SKIPPED, false))
        assertTrue(repo.local.isEmpty())
        // 重新启用后从当前游标正常同步
        assertTrue(run(repo, option = 1, overwrite = 0) is ListenableWorker.Result.Success)
        assertEquals(setOf(1L), repo.local.keys)
    }

    @Test fun `upload and download hooks apply both callbacks`() {
        for ((option, overwrite) in listOf(0 to 0, 1 to 0, 1 to 1)) {
            val repo = R().apply {
                if (option == 0) local[1] = E(1) else remote = listOf(E(1))
            }
            assertTrue(run(repo, option = option, overwrite = overwrite, callbackMode = true) is ListenableWorker.Result.Success)
            assertEquals("local callback", repo.local.getValue(1).value)
            assertEquals("remote callback", repo.uploads.single().value)
        }
    }

    @Test fun `equal timestamp content conflict uses remote data`() {
        val repo = R().apply { local[1] = E(1); remote = listOf(E(1, value = "remote changed")) }
        assertTrue(run(repo, option = 2, overwrite = 0) is ListenableWorker.Result.Success)
        assertEquals("remote changed", repo.local.getValue(1).value)
    }

    @Test fun `attachment survives local edit conflict and is deleted only after successful writeback`() {
        for (conflict in listOf(false, true)) {
            val file = java.io.File.createTempFile("sync-regression-", ".jpg")
            try {
                file.writeText("test image")
                val original = E(1, photo = file.absolutePath)
                val repo = R().apply {
                    local[1] = original
                    if (conflict) beforeUploadReturns = { local[1] = original.copy(value = "new edit") }
                }
                val result = run(repo, option = 0, overwrite = 0, transformUpload = true, deleteFiles = true)
                if (conflict) {
                    assertTrue(result is ListenableWorker.Result.Failure)
                    assertTrue(file.exists())
                    assertEquals("new edit", repo.local.getValue(1).value)
                } else {
                    assertTrue(result is ListenableWorker.Result.Success)
                    assertFalse(file.exists())
                    assertEquals("https://example.test/file.jpg", repo.local.getValue(1).photo)
                }
            } finally { file.delete() }
        }
    }

}

class SyncPersistenceRegressionTest {
    @Test fun `persistence and memory both remain monotonic`() {
        val olderEntered = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        val persisted = AtomicReference("")
        val older = "2026-09-17 10:00:00"
        val newer = "2026-09-17 11:00:00"
        val config = object : AbstractSyncConfigProvider() {
            override fun getAllTask() = emptyList<SyncTaskDefinition>()
            override fun doSaveSuccessfulSyncTime(time: String) {
                if (time == older) { olderEntered.countDown(); check(releaseOlder.await(5, TimeUnit.SECONDS)) }
                persisted.set(time)
            }
        }
        val thread = Thread { config.saveSuccessfulSyncTime(older) }
        thread.start()
        assertTrue(olderEntered.await(5, TimeUnit.SECONDS))
        val newerThread = Thread { config.saveSuccessfulSyncTime(newer) }
        newerThread.start()
        releaseOlder.countDown()
        thread.join(5000)
        newerThread.join(5000)
        assertFalse(thread.isAlive)
        assertFalse(newerThread.isAlive)
        assertEquals(newer, config.syncDataTime)
        assertEquals(newer, persisted.get())
    }

    @Test fun `failed persistence can retry the same timestamp`() {
        var calls = 0
        val config = object : AbstractSyncConfigProvider() {
            override fun getAllTask() = emptyList<SyncTaskDefinition>()
            override fun doSaveSuccessfulSyncTime(time: String) { calls++; if (calls == 1) error("disk failed") }
        }
        val time = "2026-09-17 10:00:00"
        try { config.saveSuccessfulSyncTime(time); fail() } catch (_: IllegalStateException) {}
        assertEquals("", config.syncDataTime)
        config.saveSuccessfulSyncTime(time)
        assertEquals(2, calls)
        assertEquals(time, config.syncDataTime)
    }
}

class SyncDataSizeRegressionTest {
    @Test fun `emoji NUL Chinese and long single line messages stay serializable`() {
        for (message in listOf("😀".repeat(4000), "\u0000".repeat(12000), "错误".repeat(8000), "a".repeat(30000))) {
            for ((key, data) in listOf("failMessage" to createFailData(message), "successMessage" to createSuccessData(message))) {
                val text = requireNotNull(data.getString(key))
                assertTrue(text.isNotEmpty())
                assertTrue(text.contains("已截断"))
                assertTrue(data.toByteArray().size <= Data.MAX_DATA_BYTES)
                assertTrue(message.startsWith(text.substringBefore("…")))
            }
        }
    }
}
