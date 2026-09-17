package com.util.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.util.sync.worker.SyncCoordinatorWorker
import com.util.sync.worker.SyncSuccessUpdaterWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.reflect.KClass

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SyncSchedulingRegressionTest {
    class State {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val successfulRuns = AtomicInteger()
    }

    class BlockingWorker(context: Context, params: WorkerParameters, private val state: State) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            state.started.complete(Unit)
            try { state.release.await(); return Result.success() }
            finally { state.stopped.complete(Unit) }
        }
    }

    class SuccessfulWorker(context: Context, params: WorkerParameters, private val state: State) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            state.successfulRuns.incrementAndGet()
            return Result.success()
        }
    }

    class Task(override val workerClass: KClass<out ListenableWorker>, override var syncOptionValue: Int = 1) : SyncTaskDefinition {
        override val title = workerClass.simpleName!!
        override val subTasks = emptyList<SyncSubTask>()
    }

    class Settings : AbstractSyncConfigProvider() {
        var tasks = emptyList<SyncTaskDefinition>()
        var failSave = false
        val saves = AtomicInteger()
        override fun getAllTask() = tasks
        override fun doSaveSuccessfulSyncTime(time: String) {
            if (failSave) error("forced persistence failure")
            saves.incrementAndGet()
        }
    }

    private lateinit var context: Context
    private lateinit var manager: WorkManager
    private lateinit var state: State
    private lateinit var settings: Settings
    private lateinit var workerExecutor: ExecutorService
    private lateinit var taskExecutor: ExecutorService

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        state = State()
        settings = Settings().apply { username = "test"; syncDataTime = "2026-01-01 00:00:00" }
        workerExecutor = Executors.newFixedThreadPool(2)
        taskExecutor = Executors.newSingleThreadExecutor()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                when (workerClassName) {
                    BlockingWorker::class.java.name -> BlockingWorker(appContext, workerParameters, state)
                    SuccessfulWorker::class.java.name -> SuccessfulWorker(appContext, workerParameters, state)
                    SyncCoordinatorWorker::class.java.name -> SyncCoordinatorWorker(appContext, workerParameters, settings)
                    SyncSuccessUpdaterWorker::class.java.name -> SyncSuccessUpdaterWorker(appContext, workerParameters, settings)
                    else -> null
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            // 不用内联 SynchronousExecutor：并发 Room Flow 会产生锁顺序反转。
            .setExecutor(workerExecutor).setTaskExecutor(taskExecutor)
            .setWorkerFactory(factory).setMinimumLoggingLevel(android.util.Log.ERROR).build())
        manager = WorkManager.getInstance(context)
    }

    @After fun tearDown() = runBlocking(Dispatchers.IO) {
        manager.cancelAllWork().await()
        state.release.complete(Unit)
        WorkManagerTestInitHelper.closeWorkDatabase()
        workerExecutor.shutdown()
        taskExecutor.shutdown()
    }

    private suspend fun terminal(id: UUID): WorkInfo = requireNotNull(
        manager.getWorkInfoByIdFlow(id).first { it != null && it.state.isFinished }
    )

    @Test fun `KEEP repeat observes retained request through completion`() = runBlocking {
        withTimeout(15_000) {
            val scheduler = SyncWorkManager(context)
            val first = scheduler.enqueueAndObserveUniqueRequest<BlockingWorker>("", 1)
            state.started.await()
            val firstId = first.first { it.isNotEmpty() }.single().id
            val repeated = scheduler.enqueueAndObserveUniqueRequest<BlockingWorker>("", 2)
            assertEquals(firstId, repeated.first { it.isNotEmpty() }.single().id)
            state.release.complete(Unit)
            val completed = repeated.first { it.any { work -> work.state.isFinished } }.single()
            assertEquals(firstId, completed.id)
            assertEquals(WorkInfo.State.SUCCEEDED, completed.state)
        }
    }

    @Test fun `cancel all stops untagged coordinator and does not run remaining tasks`() = runBlocking {
        withTimeout(15_000) {
            settings.tasks = listOf(Task(BlockingWorker::class), Task(SuccessfulWorker::class))
            // 模拟宿主自行构造、不加全局 tag 的旧入口。
            val request = OneTimeWorkRequestBuilder<SyncCoordinatorWorker>().build()
            manager.enqueue(request).await()
            state.started.await()
            SyncWorkManager(context).cancelAllSync()
            assertEquals(WorkInfo.State.CANCELLED, terminal(request.id).state)
            state.stopped.await()
            assertEquals(0, state.successfulRuns.get())
            assertEquals(0, settings.saves.get())
        }
    }

    @Test fun `failed timestamp persistence fails entire coordinator`() = runBlocking {
        withTimeout(15_000) {
            settings.tasks = listOf(Task(SuccessfulWorker::class))
            settings.failSave = true
            val request = OneTimeWorkRequestBuilder<SyncCoordinatorWorker>().build()
            manager.enqueue(request).await()
            assertEquals(WorkInfo.State.FAILED, terminal(request.id).state)
            assertEquals("2026-01-01 00:00:00", settings.syncDataTime)
            assertEquals(1, state.successfulRuns.get())
        }
    }

    @Test fun `cancelling a child alone aborts coordinator without retry`() = runBlocking {
        withTimeout(15_000) {
            settings.tasks = listOf(Task(BlockingWorker::class), Task(SuccessfulWorker::class))
            val request = OneTimeWorkRequestBuilder<SyncCoordinatorWorker>().build()
            manager.enqueue(request).await()
            state.started.await()
            val child = manager.getWorkInfosByTagFlow(BlockingWorker::class.java.name)
                .first { it.any { work -> work.state == WorkInfo.State.RUNNING } }.single()
            manager.cancelWorkById(child.id).await()
            assertEquals(WorkInfo.State.FAILED, terminal(request.id).state)
            assertEquals(0, state.successfulRuns.get())
            assertEquals(0, settings.saves.get())
        }
    }

    @Test fun `timestamp updater rechecks snapshot after enqueue`() = runBlocking {
        withTimeout(15_000) {
            val task = Task(SuccessfulWorker::class)
            settings.tasks = listOf(task)
            val expected = SyncCursorGuard.signatureOf(settings.tasks)
            task.syncOptionValue = 3
            val request = OneTimeWorkRequestBuilder<SyncSuccessUpdaterWorker>().setInputData(workDataOf(
                KEY_SYNC_START_TIME to "2026-09-17 12:00:00",
                KEY_SYNC_OPTIONS to expected,
                KEY_SYNC_USERNAME to settings.username,
                KEY_SYNC_DEVICE to settings.deviceNumber,
            )).build()
            manager.enqueue(request).await()
            val result = terminal(request.id)
            assertEquals(WorkInfo.State.SUCCEEDED, result.state)
            assertTrue(result.outputData.getBoolean(KEY_SYNC_SKIPPED, false))
            assertEquals(0, settings.saves.get())
        }
    }

    @Test fun `disabled task freezes global cursor but does not block enabled work`() = runBlocking {
        withTimeout(15_000) {
            settings.tasks = listOf(Task(SuccessfulWorker::class, syncOptionValue = 3))
            val request = OneTimeWorkRequestBuilder<SyncCoordinatorWorker>().build()
            manager.enqueue(request).await()
            assertEquals(WorkInfo.State.SUCCEEDED, terminal(request.id).state)
            assertEquals("2026-01-01 00:00:00", settings.syncDataTime)
            assertEquals(0, settings.saves.get())
        }
    }

    @Test fun `configuration changed while child runs holds original cursor`() = runBlocking {
        withTimeout(15_000) {
            val task = Task(BlockingWorker::class)
            settings.tasks = listOf(task)
            val request = OneTimeWorkRequestBuilder<SyncCoordinatorWorker>().build()
            manager.enqueue(request).await()
            state.started.await()
            task.syncOptionValue = 3
            state.release.complete(Unit)
            assertEquals(WorkInfo.State.SUCCEEDED, terminal(request.id).state)
            assertEquals(0, settings.saves.get())
        }
    }

    @Test fun `all enabled successful work advances original timestamp protocol`() = runBlocking {
        withTimeout(15_000) {
            settings.tasks = listOf(Task(SuccessfulWorker::class))
            val request = OneTimeWorkRequestBuilder<SyncCoordinatorWorker>().build()
            manager.enqueue(request).await()
            assertEquals(WorkInfo.State.SUCCEEDED, terminal(request.id).state)
            assertEquals(1, settings.saves.get())
            assertTrue(settings.syncDataTime > "2026-01-01 00:00:00")
        }
    }
}

class SyncExecutionGateTest {
    @Test fun `data gate serializes workers and releases lock on cancellation`() = runBlocking {
        withTimeout(5_000) {
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = launch {
                SyncExecutionGate.withDataLock { firstEntered.complete(Unit); awaitCancellation() }
            }
            firstEntered.await()
            val second = launch { SyncExecutionGate.withDataLock { secondEntered.complete(Unit) } }
            yield()
            assertFalse(secondEntered.isCompleted)
            first.cancelAndJoin()
            secondEntered.await()
            second.join()
        }
    }
}
