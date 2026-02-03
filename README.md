# SyncMoudle

> 一个基于 WorkManager 的 Android 数据同步库，用于设备与服务器间的双向数据同步

## 目录

- [项目概述](#项目概述)
- [核心特性](#核心特性)
- [技术栈](#技术栈)
- [项目架构](#项目架构)
- [同步模式](#同步模式)
- [快速开始](#快速开始)
- [核心API](#核心api)
- [配置说明](#配置说明)
- [使用示例](#使用示例)
- [注意事项](#注意事项)
- [常见问题](#常见问题)
- [版本信息](#版本信息)
- [维护说明](#维护说明)

---

## 项目概述

SyncMoudle 是一个功能强大且灵活的 Android 数据同步库，旨在简化设备与服务器之间的数据同步操作。该库基于 Android WorkManager 构建，提供可靠的异步任务调度机制，支持多种同步模式，并内置完善的日志系统，帮助开发者轻松实现数据同步功能。

### 主要功能

- **四种同步模式**：支持设备单向上传、服务器单向下发、双向同步和关闭同步
- **智能冲突解决**：基于时间戳的自动冲突检测与解决机制
- **批量处理**：支持分批并发获取和处理数据，提高同步效率
- **灵活的日志系统**：支持依赖注入的日志接口，便于应用层自定义日志实现
- **灵活的配置**：支持心跳机制、文件删除、批量大小等配置选项
- **可扩展架构**：基于接口设计，易于扩展和自定义

---

## 核心特性

### 1. 基于 WorkManager 的可靠调度

利用 Android WorkManager 的强大功能，确保同步任务在各种条件下都能可靠执行：

- 支持约束条件（网络、电量、设备空闲等）
- 自动重试机制
- 任务链式执行
- 独立于应用生命周期

### 2. 多种同步模式

提供四种灵活的同步模式，满足不同业务场景需求：

- **设备单向上传**：设备将数据上传到服务器
- **服务器单向下发**：服务器将数据下发到设备
- **双向同步**：设备与服务器之间双向同步数据
- **关闭同步**：不进行任何数据同步

### 3. 智能冲突解决

在双向同步模式下，自动检测并解决数据冲突：

- 基于时间戳比较数据新旧
- 自动选择较新的数据进行同步
- 支持自定义冲突解决策略

### 4. 批量并发处理

- 支持分批获取数据，避免内存溢出
- 并发处理多个数据项，提高同步效率
- 可配置批量大小

### 5. 灵活的日志系统

- 支持依赖注入的日志接口，由应用层实现具体日志逻辑
- 提供丰富的日志扩展函数（info、warn、error等）
- 支持操作日志记录，便于追踪关键操作
- 支持惰性日志，避免不必要的字符串拼接开销

### 6. 灵活的配置选项

- 心跳机制配置
- 本地文件删除选项
- 批量大小配置
- 设备编号配置

---

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Kotlin | 2.3.0 | 开发语言 |
| Android Gradle Plugin | 8.13.2 | 构建工具 |
| WorkManager | 2.11.0 | 后台任务调度 |
| Room | 2.8.4 | 本地数据库 |
| Paging | 3.3.6 | 分页加载 |
| Jetpack Compose | 2026.01.00 | UI 框架 |
| Yitter IDGenerator | 1.0.6 | 分布式 ID 生成 |
| Ktor-Network | 1.0.2 | 网络请求库 |

### 最低系统要求

- **minSdk**: 26 (Android 8.0)
- **compileSdk**: 36
- **JVM Target**: 17

---

## 项目架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         Application Layer                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │  LibLogMgr   │  │   ViewModel  │  │   Repository │           │
│  │  (日志注入)   │  │              │  │              │           │
│  └──────────────┘  └──────────────┘  └──────────────┘           │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Sync Library Layer                        │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    SyncWorkManager                       │   │
│  │              (Work 任务管理与调度)                         │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                │                                 │
│          ┌─────────────────────┼─────────────────────┐          │
│          ▼                     ▼                     ▼          │
│  ┌───────────────┐   ┌──────────────────┐   ┌─────────────┐   │
│  │ SyncCoordinator│  │  BaseCompareWork │  │  HeartWork  │   │
│  │     Worker    │   │   (抽象同步类)    │  │  (心跳任务)  │   │
│  └───────────────┘   └──────────────────┘   └─────────────┘   │
│          │                     │                     │          │
│          └─────────────────────┼─────────────────────┘          │
│                                ▼                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    SyncRepository                         │   │
│  │              (数据访问接口定义)                             │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Logging Layer                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │ ILibLogger   │  │ LibLogManager│  │  扩展函数    │           │
│  │ (日志接口)    │  │  (日志单例)    │  │ (libLogX)   │           │
│  └──────────────┘  └──────────────┘  └──────────────┘           │
└─────────────────────────────────────────────────────────────────┘
```

### 核心组件说明

#### 1. SyncWorkManager
负责 Work 任务的创建、调度和状态观察。

```kotlin
class SyncWorkManager(val context: Context) {
    inline fun <reified W : ListenableWorker> enqueueAndObserveUniqueRequest(
        lastSyncTime: String,
        sessionId: Long
    ): Flow<List<WorkInfo>>
}
```

#### 2. BaseCompareWork<T, R>
抽象同步工作类，定义了同步的核心流程。子类需要实现以下抽象属性：

- `workName`: 工作名称
- `workChineseName`: 工作中文名称
- `syncOptionName`: 同步选项名称
- `repository`: 数据仓库
- `syncOptionInt`: 同步选项整数值
- `syncConfig`: 同步配置

**内部统计数据类 SyncStats**：
```kotlin
data class SyncStats(
    var downloaded: Int = 0,   // 下载的项目数量
    var uploaded: Int = 0,     // 上传的项目数量
    var skipped: Int = 0,      // 跳过的项目数量
    var failedFetch: Int = 0   // 获取失败的项目数量
)
```

**支持的同步模式**：
- **ID 单查模式** (syncMode = 0)：先获取 ID 列表，再逐个获取详情
- **批量模式** (syncMode = 1)：直接批量获取完整数据，性能更优

#### 3. SyncRepository<T>
数据访问接口，定义了本地和远程数据的 CRUD 操作。

```kotlin
interface SyncRepository<T : SyncableEntity> {
    // ID 单查模式 (已标记为 @Deprecated)
    suspend fun remoteGetAfterUpdateTime(lastSyncTime: String): ResultModel<List<Long>>
    suspend fun localGetAfterUpdateTime(lastSyncTime: String): List<Long>
    suspend fun remoteGetById(id: Long): ResultModel<T>
    suspend fun localGetById(id: Long): T?

    // 批量查询模式 (推荐使用)
    suspend fun remoteGetAfterUpdateTimeBatch(lastSyncTime: String): ResultModel<List<T>>
    suspend fun localGetAfterUpdateTimeBatch(lastSyncTime: String): List<T>

    // 批量更新
    suspend fun remoteBatchUpsert(data: List<T>): ResultModel<String>
    suspend fun localBatchUpsert(data: List<T>)
}
```

#### 4. SyncableEntity
可同步实体接口，定义了同步实体必须具备的基本属性。

```kotlin
interface SyncableEntity {
    val id: Long
    val officeId: Int?
    val canteenId: Int?
    val deviceNumber: String
    val createTime: String
    val updateTime: String
    val isDelete: Boolean
    fun getPhotoPath(): String?
}
```

#### 5. SyncConfigProvider
同步配置提供者接口，定义了同步所需的配置项。

```kotlin
interface SyncConfigProvider {
    var username: String
    var syncDataTime: String
    var isDeleteLocalFile: Boolean
    var isHeartbeat: Boolean
    var heartbeatPeriod: Int
    var deviceNumber: String
    var batchSize: Int
    fun saveSuccessfulSyncTime(time: String)
    fun getAllTask(): List<SyncTaskDefinition>
}
```

#### 6. ILibLogger

日志接口，用于记录同步过程中的详细信息。采用依赖注入的方式，由应用层实现。

```kotlin
interface ILibLogger {
    fun v(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, tr: Throwable?)
    fun logOperation(tag: String, type: String, result: String, params: Map<String, Any?>)
}
```

**使用方式**：

1. 实现 `ILibLogger` 接口
2. 通过 `LibLogManager.init()` 注入
3. 在库中使用扩展函数 `libLogD()`, `libLogI()`, `libLogE()` 等

**示例**：
```kotlin
LibLogManager.init(object : ILibLogger {
    override fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }
    override fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
    }
    // ... 其他方法
})

// 在代码中使用
libLogI("同步开始")
libLogE("同步失败", exception)

### 同步流程图

```
开始
  │
  ▼
┌─────────────────┐
│ 检查同步模式     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 获取上次同步时间 │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 获取待同步ID列表 │
│ (本地 + 远程)    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 分批获取数据详情 │
│ (并发处理)       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 数据对比与处理  │
│ (冲突解决)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 批量更新本地     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 批量上传远程     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 删除本地文件     │
│ (如需)          │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 更新同步时间戳   │
└────────┬────────┘
         │
         ▼
       结束
```

---

## 同步模式

本库提供四种同步模式，通过 `SyncOption` 枚举类定义：

### 1. DEVICE_UPLOAD - 设备单向上传

**描述**：设备将数据上传到服务器，服务器不向设备下发数据。

**适用场景**：
- 设备端产生的数据需要备份到服务器
- 只需要单向数据流的应用场景
- 离线数据收集后上传

**实现细节**：
```kotlin
DEVICE_UPLOAD("设备单向上传")
```

**工作流程**：
1. 获取本地在 `lastSyncTime` 之后更新的数据 ID 列表
2. 批量获取本地数据详情
3. 调用 `handleLocalDataForUpload` 钩子方法处理数据（如文件上传）
4. 批量上传到服务器
5. 如配置了 `isDeleteLocalFile`，删除已成功上传的本地文件

### 2. SERVER_DOWNLOAD - 服务器单向下发

**描述**：服务器将数据下发到设备，设备不向服务器上传数据。

**适用场景**：
- 服务器端配置下发
- 数据只读场景
- 从服务器同步基础数据

**实现细节**：
```kotlin
SERVER_DOWNLOAD("服务器单向下发")
```

**工作流程**：
1. 获取服务器在 `lastSyncTime` 之后更新的数据 ID 列表
2. 批量获取服务器数据详情
3. 调用 `handleRemoteDataForDownload` 钩子方法处理数据（如人脸特征提取）
4. 批量更新本地数据库

### 3. TWO_WAY_SYNC - 双向同步

**描述**：设备与服务器之间双向同步数据。

**适用场景**：
- 多端数据同步
- 需要保持数据一致性的场景
- 支持离线操作的应用

**实现细节**：
```kotlin
TWO_WAY_SYNC("双向同步")
```

**工作流程**：
1. 同时获取本地和服务器在 `lastSyncTime` 之后更新的数据 ID 列表
2. 合并并去重所有待同步 ID
3. 批量获取本地和服务器数据详情
4. 根据以下规则进行冲突解决：
   - **仅本地有数据**：上传到服务器
   - **仅服务器有数据**：下载到本地
   - **两边都有数据**：比较 `updateTime` 时间戳
     - 服务器时间较新：下载到本地
     - 本地时间较新：上传到服务器
     - 时间相同：跳过
5. 批量更新本地数据库和服务器
6. 如配置了 `isDeleteLocalFile`，删除已成功上传的本地文件

### 4. SYNC_OFF - 关闭同步

**描述**：关闭当前同步项，不进行任何数据同步。

**适用场景**：
- 临时禁用某个同步任务
- 调试和测试
- 用户手动关闭同步

**实现细节**：
```kotlin
SYNC_OFF("关闭同步")
```

**工作流程**：
- 直接返回成功，不执行任何同步操作

### 同步模式切换

```kotlin
// 通过整数值获取同步模式
val syncOption = SyncOption.fromInt(0) // DEVICE_UPLOAD

// 获取描述信息
val description = syncOption.description // "设备单向上传"
```

---

## 快速开始

### 1. 添加依赖

在项目的 `build.gradle.kts` 文件中添加依赖：

```kotlin
dependencies {
    implementation("com.github.Caleb-Rainbow:sync:2026.02.03.01")
}
```

### 2. 初始化配置

实现 `SyncConfigProvider` 接口：

```kotlin
class MySyncConfigProvider : SyncConfigProvider {
    override var username: String = ""
    override var syncDataTime: String = ""
    override var isDeleteLocalFile: Boolean = false
    override var isHeartbeat: Boolean = true
    override var heartbeatPeriod: Int = 60
    override var deviceNumber: String = ""
    override var batchSize: Int = 50

    override fun saveSuccessfulSyncTime(time: String) {
        // 保存同步时间到本地存储
        syncDataTime = time
    }

    override fun getAllTask(): List<SyncTaskDefinition> {
        // 返回所有需要同步的任务列表
        return listOf(
            SyncTaskDefinition(
                title = "用户数据同步",
                workerClass = UserDataSyncWorker::class,
                subTasks = listOf(
                    SyncSubTask("upload", "上传用户数据"),
                    SyncSubTask("download", "下载用户数据")
                )
            )
        )
    }
}
```

### 3. 实现数据仓库

实现 `SyncRepository<T>` 接口：

```kotlin
class UserDataRepository(
    private val localDataSource: UserDao,
    private val remoteDataSource: UserApi
) : SyncRepository<User> {

    override suspend fun remoteGetAfterUpdateTime(lastSyncTime: String): ResultModel<List<Long>> {
        return remoteDataSource.getUpdatedIds(lastSyncTime)
    }

    override suspend fun localGetAfterUpdateTime(lastSyncTime: String): List<Long> {
        return localDataSource.getUpdatedIds(lastSyncTime)
    }

    override suspend fun remoteGetById(id: Long): ResultModel<User> {
        return remoteDataSource.getUserById(id)
    }

    override suspend fun localGetById(id: Long): User? {
        return localDataSource.getUserById(id)
    }

    override suspend fun remoteBatchUpsert(data: List<User>): ResultModel<String> {
        return remoteDataSource.batchUpsert(data)
    }

    override suspend fun localBatchUpsert(data: List<User>) {
        localDataSource.batchUpsert(data)
    }
}
```

### 4. 创建同步 Worker

继承 `BaseCompareWork<T, R>` 并实现抽象属性：

```kotlin
class UserDataSyncWorker(
    context: Context,
    workerParameters: WorkerParameters,
    private val repository: UserDataRepository,
    private val syncConfig: SyncConfigProvider
) : BaseCompareWork<User, UserDataRepository>(context, workerParameters) {

    override val workName: String = "UserDataSync"
    override val workChineseName: String = "用户数据同步"
    override val syncOptionName: String = "用户数据"
    override val repository: UserDataRepository = this.repository
    override val syncOptionInt: Int = 2 // TWO_WAY_SYNC
    override val syncConfig: SyncConfigProvider = this.syncConfig

    override suspend fun handleLocalDataForUpload(
        data: User,
        failureMessages: MutableList<String>,
        onLocalUpdate: (User) -> Unit,
        onRemoteUpdate: (User) -> Unit
    ): User? {
        // 处理本地数据上传前的逻辑，例如上传头像文件
        data.photoPath?.let { path ->
            val remoteUrl = uploadFileToServer(path)
            return data.copy(photoUrl = remoteUrl)
        }
        return data
    }

    override suspend fun handleRemoteDataForDownload(
        data: User,
        failureMessages: MutableList<String>,
        onLocalUpdate: (User) -> Unit,
        onRemoteUpdate: (User) -> Unit
    ): User? {
        // 处理远程数据下载后的逻辑，例如下载头像文件
        data.photoUrl?.let { url ->
            val localPath = downloadFileFromServer(url)
            return data.copy(photoPath = localPath)
        }
        return data
    }
}
```

### 5. 启动同步

使用 `SyncWorkManager` 启动同步任务：

```kotlin
val syncWorkManager = SyncWorkManager(context)
val lastSyncTime = syncConfigProvider.syncDataTime
val sessionId = YitIdHelper.nextId()

syncWorkManager.enqueueAndObserveUniqueRequest<UserDataSyncWorker>(
    lastSyncTime = lastSyncTime,
    sessionId = sessionId
).collect { workInfos ->
    workInfos.forEach { workInfo ->
        when (workInfo.state) {
            WorkInfo.State.ENQUEUED -> {
                Log.d("Sync", "任务已入队")
            }
            WorkInfo.State.RUNNING -> {
                Log.d("Sync", "任务正在运行")
            }
            WorkInfo.State.SUCCEEDED -> {
                Log.d("Sync", "任务成功完成")
            }
            WorkInfo.State.FAILED -> {
                Log.d("Sync", "任务失败")
            }
            else -> {}
        }
    }
}
```

---

## 核心API

### SyncWorkManager

工作管理器，负责创建和调度 Work 任务。

#### 方法

##### `enqueueAndObserveUniqueRequest`

启动一个带唯一动态标签的 Worker，并返回一个只观察该 Worker 的 Flow。

```kotlin
inline fun <reified W : ListenableWorker> enqueueAndObserveUniqueRequest(
    lastSyncTime: String,
    sessionId: Long
): Flow<List<WorkInfo>>
```

**参数**：
- `lastSyncTime`: 上次同步时间
- `sessionId`: 会话 ID

**返回值**：
- `Flow<List<WorkInfo>>`: Work 状态的 Flow

**示例**：
```kotlin
val flow = syncWorkManager.enqueueAndObserveUniqueRequest<MyWorker>(
    lastSyncTime = "2025-01-01 00:00:00",
    sessionId = 123456789L
)
flow.collect { workInfos ->
    // 处理 Work 状态变化
}
```

### BaseCompareWork<T, R>

抽象同步工作类，定义了同步的核心流程。

#### 抽象属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `workName` | String | 工作名称（英文） |
| `workChineseName` | String | 工作名称（中文） |
| `syncOptionName` | String | 同步选项名称 |
| `repository` | R | 数据仓库实例 |
| `syncOptionInt` | Int | 同步选项整数值 |
| `syncConfig` | SyncConfigProvider | 同步配置 |

#### 钩子方法

##### `handleLocalDataForUpload`

在本地数据上传到服务器之前对其进行处理的钩子。

```kotlin
open suspend fun handleLocalDataForUpload(
    data: T,
    failureMessages: MutableList<String>,
    onLocalUpdate: (T) -> Unit = {},
    onRemoteUpdate: (T) -> Unit = {},
): T?
```

**参数**：
- `data`: 待上传的本地数据
- `failureMessages`: 失败消息列表
- `onLocalUpdate`: 本地更新回调
- `onRemoteUpdate`: 远程更新回调

**返回值**：
- `T?`: 处理后的数据，返回 null 表示跳过此数据

**示例**：
```kotlin
override suspend fun handleLocalDataForUpload(
    data: User,
    failureMessages: MutableList<String>,
    onLocalUpdate: (User) -> Unit,
    onRemoteUpdate: (User) -> Unit
): User? {
    // 上传头像文件
    data.photoPath?.let { path ->
        try {
            val remoteUrl = uploadFileToServer(path)
            libLogI("头像上传成功: $remoteUrl")
            return data.copy(photoUrl = remoteUrl)
        } catch (e: Exception) {
            libLogE("头像上传失败: ${e.message}", e)
            failureMessages.add("头像上传失败")
            return null
        }
    }
    return data
}
```

##### `handleRemoteDataForDownload`

在从服务器下载数据后对其进行处理的钩子。

```kotlin
open suspend fun handleRemoteDataForDownload(
    data: T,
    failureMessages: MutableList<String>,
    onLocalUpdate: (T) -> Unit = {},
    onRemoteUpdate: (T) -> Unit = {},
): T?
```

**参数**：
- `data`: 待处理的远程数据
- `failureMessages`: 失败消息列表
- `onLocalUpdate`: 本地更新回调
- `onRemoteUpdate`: 远程更新回调

**返回值**：
- `T?`: 处理后的数据，返回 null 表示跳过此数据

**示例**：
```kotlin
override suspend fun handleRemoteDataForDownload(
    data: User,
    failureMessages: MutableList<String>,
    onLocalUpdate: (User) -> Unit,
    onRemoteUpdate: (User) -> Unit
): User? {
    // 下载头像文件
    data.photoUrl?.let { url ->
        try {
            val localPath = downloadFileFromServer(url)
            libLogI("头像下载成功: $localPath")
            return data.copy(photoPath = localPath)
        } catch (e: Exception) {
            libLogE("头像下载失败: ${e.message}", e)
            failureMessages.add("头像下载失败")
            return null
        }
    }
    return data
}
```

### SyncRepository<T>

数据访问接口，定义了本地和远程数据的 CRUD 操作。

#### 方法

##### `remoteGetAfterUpdateTimeBatch`

远程获取上次同步时间之后更新过的完整数据列表（批量模式，推荐使用）。

```kotlin
suspend fun remoteGetAfterUpdateTimeBatch(lastSyncTime: String): ResultModel<List<T>>
```

##### `localGetAfterUpdateTimeBatch`

本地获取上次同步时间之后更新过的完整数据列表（批量模式，推荐使用）。

```kotlin
suspend fun localGetAfterUpdateTimeBatch(lastSyncTime: String): List<T>
```

##### `remoteGetAfterUpdateTime`

远程获取上次同步时间之后更新过的 ID 列表（已标记为 Deprecated）。

```kotlin
suspend fun remoteGetAfterUpdateTime(lastSyncTime: String): ResultModel<List<Long>>
```

##### `localGetAfterUpdateTime`

本地获取上次同步时间之后更新过的 ID 列表（已标记为 Deprecated）。

```kotlin
suspend fun localGetAfterUpdateTime(lastSyncTime: String): List<Long>
```

##### `remoteGetById`

远程根据 ID 获取单个实体（已标记为 Deprecated）。

```kotlin
suspend fun remoteGetById(id: Long): ResultModel<T>
```

##### `localGetById`

本地根据 ID 获取单个实体（已标记为 Deprecated）。

```kotlin
suspend fun localGetById(id: Long): T?
```

##### `remoteBatchUpsert`

远程批量更新或插入。

```kotlin
suspend fun remoteBatchUpsert(data: List<T>): ResultModel<String>
```

##### `localBatchUpsert`

本地批量更新或插入。

```kotlin
suspend fun localBatchUpsert(data: List<T>)
```

### SyncConfigProvider

同步配置提供者接口。

#### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `username` | String | 用户名 |
| `syncDataTime` | String | 上次同步时间 |
| `isDeleteLocalFile` | Boolean | 是否在上传成功后删除本地文件 |
| `isHeartbeat` | Boolean | 是否开启心跳 |
| `heartbeatPeriod` | Int | 心跳周期（秒） |
| `deviceNumber` | String | 设备编号 |
| `batchSize` | Int | 批量大小 |
| `syncMode` | Int | 同步模式（0-ID单查，1-批量） |

#### 方法

##### `saveSuccessfulSyncTime`

保存最近一次同步成功的时间戳。

```kotlin
fun saveSuccessfulSyncTime(time: String)
```

##### `getAllTask`

获取所有同步任务。

```kotlin
fun getAllTask(): List<SyncTaskDefinition>
```

### ILibLogger

日志接口，用于记录同步过程中的详细信息。

#### 方法

##### `v` / `d` / `i` / `w` / `e`

记录不同级别的日志。

```kotlin
fun v(tag: String, msg: String)
fun d(tag: String, msg: String)
fun i(tag: String, msg: String)
fun w(tag: String, msg: String)
fun e(tag: String, msg: String, tr: Throwable?)
```

##### `logOperation`

记录操作日志。

```kotlin
fun logOperation(tag: String, type: String, result: String, params: Map<String, Any?>)
```

#### 扩展函数

库提供了便捷的扩展函数，使用时无需手动传递 tag（TAG 自动从类名获取）：

```kotlin
// 基础日志
libLogD("调试信息")
libLogI("普通信息")
libLogW("警告信息")
libLogE("错误信息", exception)

// 操作日志
libLogOpStart("数据同步", params = mapOf("id" to 123))
libLogOpSuccess("数据同步", params = mapOf("count" to 10))
libLogOpFail("数据同步", error = "网络错误")
```

**扩展属性**：
```kotlin
// 获取当前类的 TAG（已缓存，避免反射开销）
val tag = this.libLogTag
```

**惰性日志扩展**（用于高频调用场景，避免不必要的字符串拼接开销）：
```kotlin
// 需要预先缓存 TAG 以提高性能
private val cachedTag = this.libLogTag

// 使用惰性日志，只有当日志会被输出时才执行 lambda
libLogDLazy(cachedTag) { "耗时: ${calculateExpensiveValue()}ms" }
libLogILazy(cachedTag) { "处理数据: ${expensiveOperation()}" }
libLogWLazy(cachedTag) { "警告: ${buildWarningMessage()}" }
libLogELazy(cachedTag) { "错误: ${buildErrorMessage()}" }
libLogVLazy(cachedTag) { "详细: ${buildVerboseMessage()}" }
```

---

## 配置说明

### 同步配置

通过实现 `SyncConfigProvider` 接口来配置同步参数：

```kotlin
class MySyncConfigProvider : SyncConfigProvider {
    // 用户名，用于身份验证
    override var username: String = ""

    // 上次同步时间，格式：yyyy-MM-dd HH:mm:ss
    override var syncDataTime: String = "2025-01-01 00:00:00"

    // 是否在上传成功后删除本地文件
    override var isDeleteLocalFile: Boolean = false

    // 是否开启心跳
    override var isHeartbeat: Boolean = true

    // 心跳周期（秒）
    override var heartbeatPeriod: Int = 60

    // 设备编号
    override var deviceNumber: String = ""

    // 批量大小，用于分批获取数据
    override var batchSize: Int = 50

    // 同步模式：0-ID单查，1-批量（推荐）
    override var syncMode: Int = 1

    override fun saveSuccessfulSyncTime(time: String) {
        syncDataTime = time
    }

    override fun getAllTask(): List<SyncTaskDefinition> {
        return listOf(
            SyncTaskDefinition(
                title = "用户数据同步",
                workerClass = UserDataSyncWorker::class,
                subTasks = listOf(
                    SyncSubTask("upload", "上传用户数据"),
                    SyncSubTask("download", "下载用户数据")
                )
            )
        )
    }
}
```

### 日志配置

初始化日志系统（推荐在 Application 的 onCreate 中初始化）：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化日志系统
        LibLogManager.init(object : ILibLogger {
            override fun v(tag: String, msg: String) {
                Log.v(tag, msg)
            }

            override fun d(tag: String, msg: String) {
                Log.d(tag, msg)
            }

            override fun i(tag: String, msg: String) {
                Log.i(tag, msg)
            }

            override fun w(tag: String, msg: String) {
                Log.w(tag, msg)
            }

            override fun e(tag: String, msg: String, tr: Throwable?) {
                Log.e(tag, msg, tr)
            }

            override fun logOperation(tag: String, type: String, result: String, params: Map<String, Any?>) {
                Log.i(tag, "$type: $result, params: $params")
            }
        })
    }
}
```

### WorkManager 配置

在 `Application` 类中初始化 WorkManager：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化 WorkManager
        WorkManager.initialize(
            this,
            Configuration.Builder()
                .setExecutor(Executors.newFixedThreadPool(4))
                .build()
        )
    }
}
```

---

## 使用示例

### 示例 1：用户数据同步

完整的用户数据同步实现示例：

```kotlin
// 1. 定义用户实体
@Entity(tableName = "users")
data class User(
    @PrimaryKey
    override val id: Long,
    override val officeId: Int? = null,
    override val canteenId: Int? = null,
    override val deviceNumber: String,
    override val createTime: String,
    override val updateTime: String,
    override val isDelete: Boolean = false,
    val name: String,
    val email: String,
    val photoPath: String? = null,
    val photoUrl: String? = null
) : SyncableEntity {
    override fun getPhotoPath(): String? = photoPath
}

// 2. 定义用户数据仓库
class UserRepository(
    private val userDao: UserDao,
    private val userApi: UserApi
) : SyncRepository<User> {

    override suspend fun remoteGetAfterUpdateTime(lastSyncTime: String): ResultModel<List<Long>> {
        return userApi.getUpdatedIds(lastSyncTime)
    }

    override suspend fun localGetAfterUpdateTime(lastSyncTime: String): List<Long> {
        return userDao.getUpdatedIds(lastSyncTime)
    }

    override suspend fun remoteGetById(id: Long): ResultModel<User> {
        return userApi.getUserById(id)
    }

    override suspend fun localGetById(id: Long): User? {
        return userDao.getUserById(id)
    }

    override suspend fun remoteBatchUpsert(data: List<User>): ResultModel<String> {
        return userApi.batchUpsert(data)
    }

    override suspend fun localBatchUpsert(data: List<User>) {
        userDao.batchUpsert(data)
    }
}

// 3. 创建用户同步 Worker
class UserSyncWorker(
    context: Context,
    workerParameters: WorkerParameters,
    private val repository: UserRepository,
    private val syncConfig: SyncConfigProvider,
    private val fileService: FileService
) : BaseCompareWork<User, UserRepository>(context, workerParameters) {

    override val workName: String = "UserSync"
    override val workChineseName: String = "用户数据同步"
    override val syncOptionName: String = "用户数据"
    override val repository: UserRepository = this.repository
    override val syncOptionInt: Int = 2 // TWO_WAY_SYNC
    override val syncConfig: SyncConfigProvider = this.syncConfig

    override suspend fun handleLocalDataForUpload(
        data: User,
        failureMessages: MutableList<String>,
        onLocalUpdate: (User) -> Unit,
        onRemoteUpdate: (User) -> Unit
    ): User? {
        // 上传头像文件
        data.photoPath?.let { path ->
            try {
                val remoteUrl = fileService.uploadFile(path)
                libLogI("头像上传成功: $remoteUrl")
                return data.copy(photoUrl = remoteUrl)
            } catch (e: Exception) {
                libLogE("头像上传失败: ${e.message}", e)
                failureMessages.add("头像上传失败")
                return null
            }
        }
        return data
    }

    override suspend fun handleRemoteDataForDownload(
        data: User,
        failureMessages: MutableList<String>,
        onLocalUpdate: (User) -> Unit,
        onRemoteUpdate: (User) -> Unit
    ): User? {
        // 下载头像文件
        data.photoUrl?.let { url ->
            try {
                val localPath = fileService.downloadFile(url)
                libLogI("头像下载成功: $localPath")
                return data.copy(photoPath = localPath)
            } catch (e: Exception) {
                libLogE("头像下载失败: ${e.message}", e)
                failureMessages.add("头像下载失败")
                return null
            }
        }
        return data
    }
}

// 4. 启动同步
fun startUserSync(context: Context, syncConfig: SyncConfigProvider) {
    val syncWorkManager = SyncWorkManager(context)
    val lastSyncTime = syncConfig.syncDataTime
    val sessionId = YitIdHelper.nextId()

    syncWorkManager.enqueueAndObserveUniqueRequest<UserSyncWorker>(
        lastSyncTime = lastSyncTime,
        sessionId = sessionId
    ).collect { workInfos ->
        workInfos.forEach { workInfo ->
            when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val message = workInfo.outputData.getString("successMessage")
                    Log.d("UserSync", "同步成功: $message")
                }
                WorkInfo.State.FAILED -> {
                    val message = workInfo.outputData.getString("failMessage")
                    Log.e("UserSync", "同步失败: $message")
                }
                else -> {}
            }
        }
    }
}
```

### 示例 2：订单数据同步（仅上传）

```kotlin
class OrderSyncWorker(
    context: Context,
    workerParameters: WorkerParameters,
    private val repository: OrderRepository,
    private val syncConfig: SyncConfigProvider
) : BaseCompareWork<Order, OrderRepository>(context, workerParameters) {

    override val workName: String = "OrderSync"
    override val workChineseName: String = "订单数据同步"
    override val syncOptionName: String = "订单数据"
    override val repository: OrderRepository = this.repository
    override val syncOptionInt: Int = 0 // DEVICE_UPLOAD
    override val syncConfig: SyncConfigProvider = this.syncConfig
}
```

### 示例 3：配置数据同步（仅下载）

```kotlin
class ConfigSyncWorker(
    context: Context,
    workerParameters: WorkerParameters,
    private val repository: ConfigRepository,
    private val syncConfig: SyncConfigProvider
) : BaseCompareWork<Config, ConfigRepository>(context, workerParameters) {

    override val workName: String = "ConfigSync"
    override val workChineseName: String = "配置数据同步"
    override val syncOptionName: String = "配置数据"
    override val repository: ConfigRepository = this.repository
    override val syncOptionInt: Int = 1 // SERVER_DOWNLOAD
    override val syncConfig: SyncConfigProvider = this.syncConfig
}
```

### 示例 4：使用自定义日志实现

实现 `ILibLogger` 接口，将日志输出到文件或网络：

```kotlin
class FileLogger(
    private val context: Context
) : ILibLogger {
    private val logFile = File(context.filesDir, "sync_logs.txt")

    override fun i(tag: String, msg: String) {
        writeToLog("INFO", tag, msg)
    }

    override fun e(tag: String, msg: String, tr: Throwable?) {
        val errorMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        writeToLog("ERROR", tag, errorMsg)
    }

    // ... 其他方法

    private fun writeToLog(level: String, tag: String, msg: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            .format(Date())
        val logLine = "[$timestamp] [$level] [$tag] $msg\n"
        logFile.appendText(logLine)
    }
}

// 初始化
LibLogManager.init(FileLogger(applicationContext))
```

---

## 注意事项

### 1. 数据模型要求

实现 `SyncableEntity` 接口时，确保以下字段正确设置：

- `id`: 必须是唯一标识符
- `updateTime`: 时间戳格式必须为 `yyyy-MM-dd HH:mm:ss`
- `deviceNumber`: 设备编号用于数据归属

### 2. 时间戳格式

所有时间戳必须使用以下格式：

```kotlin
val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
val timestamp = format.format(Date())
```

### 3. 批量大小配置

根据数据量和设备性能调整 `batchSize`：

- 数据量大、设备性能好：可以设置较大的值（如 100）
- 数据量小、设备性能一般：建议设置较小的值（如 20-50）

### 4. 网络请求处理

在 `SyncRepository` 实现中，确保正确处理网络请求错误：

```kotlin
override suspend fun remoteGetById(id: Long): ResultModel<T> {
    return try {
        val response = apiService.getById(id)
        if (response.isSuccessful) {
            ResultModel.success(response.data)
        } else {
            ResultModel.error(response.code, response.message)
        }
    } catch (e: Exception) {
        ResultModel.error(-1, e.message ?: "Unknown error")
    }
}
```

### 5. 文件处理

在 `handleLocalDataForUpload` 和 `handleRemoteDataForDownload` 中处理文件时：

- 确保文件路径正确
- 处理文件上传/下载失败的情况
- 考虑文件大小和网络状况

### 6. 同步模式选择

根据实际场景选择合适的同步模式：

- **批量模式** (syncMode = 1)：推荐使用，性能更好，减少网络请求次数
  - 实现批量查询接口：`remoteGetAfterUpdateTimeBatch` 和 `localGetAfterUpdateTimeBatch`
  - 适用于数据量较大的场景
  
- **ID 单查模式** (syncMode = 0)：传统模式，先获取 ID 列表，再逐个查询详情
  - 已标记为 Deprecated，将在后续版本移除
  - 适用于数据量较小的场景

### 7. 心跳机制

心跳机制用于保持设备与服务器的连接：

- 确保 `heartbeatPeriod` 设置合理（建议 60-300 秒）
- 在 `HeartWork` 中实现心跳逻辑

### 8. 冲突解决

双向同步模式下的冲突解决基于时间戳：

- 确保服务器和客户端时间同步
- 考虑使用服务器时间作为权威时间
- 对于重要数据，可以实现自定义冲突解决策略

### 9. 内存管理

处理大量数据时注意内存使用：

- 使用分批处理
- 及时释放不再使用的对象
- 避免在内存中保存大量数据

### 10. 错误处理

在同步过程中，错误处理非常重要：

- 记录详细的错误日志
- 提供用户友好的错误提示
- 考虑实现重试机制

---

## 常见问题

### Q1: 同步任务没有执行怎么办？

**A:** 检查以下几点：

1. 确认 `SyncConfigProvider.username` 已设置
2. 检查 WorkManager 是否正确初始化
3. 查看日志中是否有错误信息
4. 确认同步模式不是 `SYNC_OFF`

```kotlin
// 检查配置
if (syncConfigProvider.username.isEmpty()) {
    Log.e("Sync", "用户名未设置")
}
```

### Q2: 如何查看同步日志？

**A:** 库采用依赖注入的日志系统，日志输出由应用层实现 `ILibLogger` 接口控制。常见方式包括：

1. **输出到 Logcat**：
   ```kotlin
   LibLogManager.init(object : ILibLogger {
       override fun i(tag: String, msg: String) {
           Log.i(tag, msg)
       }
       // ... 其他方法
   })
   ```

2. **输出到文件**：
   ```kotlin
   class FileLogger : ILibLogger {
       private val logFile = File(context.filesDir, "sync_logs.txt")
       
       override fun i(tag: String, msg: String) {
           logFile.appendText("[INFO] $tag: $msg\n")
       }
       // ... 其他方法
   }
   ```

3. **输出到网络服务器**：
   ```kotlin
   class NetworkLogger : ILibLogger {
       override fun e(tag: String, msg: String, tr: Throwable?) {
           sendToServer(tag, msg, tr)
       }
       // ... 其他方法
   }
   ```

### Q3: 双向同步时如何解决冲突？

**A:** 默认基于时间戳解决冲突：

- 服务器时间较新：下载到本地
- 本地时间较新：上传到服务器
- 时间相同：跳过

如需自定义冲突解决策略，重写 `BaseCompareWork` 中的相关逻辑。

### Q4: 如何实现文件上传/下载？

**A:** 在钩子方法中实现：

```kotlin
override suspend fun handleLocalDataForUpload(
    data: T,
    failureMessages: MutableList<String>,
    onLocalUpdate: (T) -> Unit,
    onRemoteUpdate: (T) -> Unit
): T? {
    data.getPhotoPath()?.let { path ->
        val remoteUrl = uploadFileToServer(path)
        return data.copy(photoUrl = remoteUrl)
    }
    return data
}
```

### Q5: 如何优化同步性能？

**A:** 以下是一些优化建议：

1. 使用批量同步模式（syncMode = 1），减少网络请求次数
2. 调整 `batchSize` 参数
3. 使用并发处理
4. 减少不必要的数据传输
5. 压缩上传的数据
6. 使用增量同步

### Q6: 同步失败后如何重试？

**A:** WorkManager 会自动重试失败的任务。你也可以手动重试：

```kotlin
val workManager = WorkManager.getInstance(context)
workManager.enqueue(workRequest)
```

### Q7: 如何取消正在进行的同步任务？

**A:** 使用 WorkManager 取消任务：

```kotlin
val workManager = WorkManager.getInstance(context)
workManager.cancelAllWorkByTag(tag)
```

### Q8: 如何处理网络异常？

**A:** 在 `SyncRepository` 实现中捕获异常：

```kotlin
override suspend fun remoteGetById(id: Long): ResultModel<T> {
    return try {
        val response = apiService.getById(id)
        ResultModel.success(response.data)
    } catch (e: IOException) {
        ResultModel.error(-1, "网络错误: ${e.message}")
    } catch (e: Exception) {
        ResultModel.error(-1, "未知错误: ${e.message}")
    }
}
```

### Q9: 如何同步大量数据？

**A:** 使用分批处理：

```kotlin
override var batchSize: Int = 100 // 设置合适的批量大小

// BaseCompareWork 会自动分批处理数据
```

### Q10: 如何测试同步功能？

**A:** 可以使用以下方法测试：

1. 使用单元测试测试 `SyncRepository`
2. 使用集成测试测试完整的同步流程
3. 使用模拟服务器测试网络请求
4. 使用日志查看同步过程

---

## 版本信息

### 当前版本：2026.02.03.01

### 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 2026.02.03.01 | 2026-02-03 | 新增 SyncStats 统计数据类；优化日志性能（TAG 缓存、惰性求值）；改进异常处理和日志输出 |
| 1.0.8 | 2025-01-26 | 重构日志系统，采用依赖注入方式；新增批量同步模式；优化同步性能 |
| 1.0.7 | 2025-01-21 | 初始版本发布 |

### 依赖版本

| 依赖 | 版本 |
|------|------|
| Kotlin | 2.3.0 |
| Android Gradle Plugin | 8.13.2 |
| WorkManager | 2.11.0 |
| Room | 2.8.4 |
| Paging | 3.3.6 |
| Jetpack Compose | 2026.01.00 |
| Yitter IDGenerator | 1.0.6 |
| Ktor-Network | 1.0.2 |

### 系统要求

- **minSdk**: 26 (Android 8.0)
- **compileSdk**: 36
- **JVM Target**: 17

---

## 维护说明

### 代码规范

- 遵循 Kotlin 编码规范
- 使用有意义的变量和函数命名
- 添加必要的注释
- 保持代码简洁和可读性

### 提交规范

提交信息格式：

```
<type>(<scope>): <subject>

<body>

<footer>
```

类型（type）：
- `feat`: 新功能
- `fix`: 修复 bug
- `docs`: 文档更新
- `style`: 代码格式调整
- `refactor`: 重构
- `test`: 测试相关
- `chore`: 构建/工具相关

### 测试

- 为新功能编写单元测试
- 确保所有测试通过
- 测试覆盖率达到 80% 以上

### 文档

- 更新 README.md
- 添加必要的代码注释
- 更新 API 文档

### 发布流程

1. 更新版本号
2. 更新 CHANGELOG.md
3. 运行所有测试
4. 构建发布包
5. 发布到 Maven 仓库

### 问题反馈

如有问题或建议，请通过以下方式联系：

- 提交 Issue
- 发送邮件
- 加入讨论组

---

## 许可证

本项目采用 MIT 许可证。

---

## 致谢

感谢所有为本项目做出贡献的开发者。

---

**联系方式**

- 作者：杨帅林
- 项目地址：[GitHub](https://github.com/Caleb-Rainbow/SyncMoudle)
- 问题反馈：[Issues](https://github.com/Caleb-Rainbow/SyncMoudle/issues)

---

**最后更新时间：2026-02-03**
