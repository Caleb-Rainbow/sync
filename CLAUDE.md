# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SyncMoudle is a reusable Android data synchronization library published to JitPack (`com.github.Caleb-Rainbow:sync`). It provides bidirectional data sync between Android devices and a server, built on WorkManager. Written in Kotlin, targets Java 21, compileSdk 36, minSdk 26.

## Build & Test Commands

```bash
# Compile the sync library (requires JAVA_HOME set to JDK 21)
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew :sync:compileDebugKotlin

# Run all unit tests
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew :sync:testDebugUnitTest --no-daemon

# Run a single test class
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew :sync:testDebugUnitTest --tests "com.util.sync.SyncComparatorTest" --no-daemon

# Run a single test method
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew :sync:testDebugUnitTest --tests "com.util.sync.SyncComparatorTest.`test description`" --no-daemon

# Compile without the composite build dependency (faster for non-network changes)
JAVA_HOME="D:/AndroidStudio/jbr" ./gradlew :sync:compileDebugKotlin -x :NetworkMoudle:ktor:compileDebugKotlin
```

Test results are in `sync/build/test-results/`.

## Architecture

### Module Layout

- **`:sync`** — The core library. All source code lives here under `com.util.sync`.
- **`:app`** — Minimal shell app for development testing. Not published.
- **Composite build** — `settings.gradle.kts` 的 `NetworkMoudle` `includeBuild` 当前已注释掉，库通过 JitPack 解析 `com.github.Caleb-Rainbow:Ktor-Network`（不替换为本地 `:ktor`）。如需本地联调网络库，取消注释 `settings.gradle.kts` 中的 `includeBuild("...\\NetworkMoudle")` 块即可。

### Core Classes (all in `sync/src/main/java/com/util/sync/`)

**Sync flow** — `SyncWorkManager` enqueues a `SyncCoordinatorWorker`, which runs all registered `SyncSubTask`s sequentially. Each task uses a subclass of `BaseCompareWork` to perform the actual sync. On full success, `SyncSuccessUpdaterWorker` persists the sync timestamp.

| Class | Role |
|-------|------|
| `BaseCompareWork` | Abstract `CoroutineWorker`. Template Method pattern — subclasses provide repository, config, and entity type; this class handles the full sync lifecycle. |
| `SyncCoordinatorWorker` | Orchestrator. Runs tasks sequentially, retries failed ones (up to 3x with exponential backoff), triggers timestamp update only on full success. |
| `SyncComparator` | Pure comparison engine. Compares entity pairs by timestamp and returns `SyncDecision`. |
| `SyncRepository<T>` | Interface consuming apps implement to bridge local DB (Room) and remote API. |
| `SyncConfigProvider` | Config interface + thread-safe `AbstractSyncConfigProvider` using atomic types with CAS-based monotonic timestamp updates. |
| `SyncWorkManager` | Facade for scheduling WorkManager `OneTimeWorkRequest`s with exponential backoff. |
| `HeartWork` | Periodic heartbeat worker using `HeartRepository` from the network module. |
| `SyncTimeUtils` | Pure utility for time parsing (`yyyy-MM-dd HH:mm:ss[.SSS]`, UTC-first) and comparison with configurable clock skew. |

### Key Patterns

- **Template Method** — `BaseCompareWork` defines the sync algorithm; subclasses override hooks like `handleLocalDataForUpload` and `handleRemoteDataForDownload`.
- **Strategy** — `SyncOption` enum (DEVICE_UPLOAD, SERVER_DOWNLOAD, TWO_WAY_SYNC, SYNC_OFF) drives different execution paths.
- **Sealed classes** — `SyncDecision` and `TimeComparisonResult` model closed outcome sets for exhaustive `when` handling.
- **Dependency-injected logging** — `ILibLogger` interface with no-op default; consuming apps inject via `LibLogManager.init()`.
- **Two sync strategies**: Batch mode (syncMode=1, recommended) fetches complete data in bulk; ID query mode (syncMode=0, deprecated) fetches IDs then individual entities.

### Package Structure

```
com.util.sync/       — Core classes
com.util.sync.log/   — ILibLogger, LibLogManager, extension functions
com.util.sync.worker/ — HeartWork, SyncCoordinatorWorker, SyncSuccessUpdaterWorker
```

## Conventions

- **Language**: All documentation and README are in Chinese (中文).
- **Commit format**: `<type>(<scope>): <subject>` in Chinese — types: feat, fix, refactor, docs, test, chore, perf, ci.
- **Test naming**: Backtick-quoted descriptive names (`` `test description` ``).
- **Test framework**: JUnit 4 with `org.junit.Assert`. MockK, Robolectric, and coroutines-test are declared as dependencies but not yet used in existing tests.
- **Versions**: Date-based versioning (e.g., `2026.04.15.01`) for Maven publishing.
- **Dependencies are `api`** not `implementation` — consuming apps need transitive access to WorkManager, Yitter IDGenerator, and Ktor-Network types.

## Publishing

The sync module publishes to Maven with group `com.github.Caleb-Rainbow`, artifact `sync`. Version is set in `sync/build.gradle.kts` publication block. Released via JitPack.
