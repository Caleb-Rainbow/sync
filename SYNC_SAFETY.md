# 同步完整性修复与宿主接入

本次继续使用现有 `updateTime`、`syncDataTime` 和接口时间串，不引入数据版本号或新的服务端协议。原有同步选项数值保持不变。

## 停用与恢复：保留全局时间戳

本轮存在停用任务、Worker 返回 `KEY_SYNC_SKIPPED`、任务集合/同步选项/账号/设备号在本轮发生变化时，协调器不推进 `syncDataTime`，已启用的任务仍正常执行。成功更新器在提交前再次核对配置。

恢复同步后从保留的时间继续查询，成功完成全部已启用任务后才推进时间。代价是停用期间，其他任务会重复扫描和处理保留时间之后的数据；Repository 的 upsert 与附件钩子需保持幂等。

`getAllTask()` 必须返回全部已注册任务，包含停用项；不能先过滤掉关闭项，否则协调器无法知道哪些数据尚未同步。自定义 Worker 若没有实际执行，应返回 `KEY_SYNC_SKIPPED=true`。

这项保护不能追回升级前已经被旧游标越过的数据，也不能获知模块未运行时发生的历史配置变更。已有遗漏需要按宿主原有方式执行一次全量回补。新增任务或改变同步方向时，同样应先回补对应数据。

## 时间冲突规则

生产 Worker 统一调用 `SyncComparator`。两端时间不同时按新时间决定方向，不再把 3 秒内的实际更新跳过。时间完全一致时：删除标记冲突采用删除优先；其他内容不同采用服务端优先；实体值相同时跳过。

`timeSkewThresholdMs` 构造参数为源码兼容保留，但不再用来抑制变更。`SyncTimeUtils.compareTimestamps` 仍可用于诊断时间差，不能用它的容差结果代替同步决策。客户端和服务端仍须遵守原有时区及时间精度约定。

## 两个可覆盖的仓库入口

现有方法仍保留，新增方法提供默认实现，已有仓库重新编译无需新增抽象成员。实体应为不可变、具有值相等语义的数据对象，例如 Kotlin data class。

### localUpsertAfterUpload

上传钩子修改实体或发出本地更新回调后，先用读取时的原始实体复核数据库现值。检测到期间有业务修改时，保留新数据、返回失败、保留附件，等待下一轮重试。

默认实现是“查询 → 比较 → upsert”，**无法原子阻止业务线程在比较后再次写入**。有并发业务写入的宿主必须覆盖为同一个数据库事务，示意代码如下（DAO 名称按宿主项目调整）：

```kotlin
override suspend fun localUpsertAfterUpload(
    original: MyEntity,
    updated: MyEntity,
): Boolean = database.withTransaction {
    require(original.id == updated.id)
    if (dao.getById(original.id) != original) {
        false
    } else {
        dao.upsert(updated)
        true
    }
}
```

默认实现调用已有 `localGetById`。若宿主此前只实现了批量模式、将此旧方法留作占位，需要直接覆盖新入口，或补齐真实本地读取。不要返回固定 true 跳过比较。

### localReplaceAll

覆盖模式只在整个服务端快照和所有预处理都成功后进入此方法。缺失 data、任一详情失败、任一钩子跳过都会失败并保留原本地数据。只有明确成功的空列表允许清空表。清理失败不再被当作成功。

默认实现为先 `localBatchUpsert` 后 `localDeleteAllExcept`，空列表则 `localDeleteAll`。它兼容原有仓库，但**不等于数据库事务**；需要读者看不到半完成状态的宿主应覆盖：

```kotlin
override suspend fun localReplaceAll(data: List<MyEntity>) {
    database.withTransaction {
        if (data.isEmpty()) {
            dao.deleteAll()
        } else {
            dao.upsertAll(data)
            dao.deleteAllExcept(data.map { it.id })
        }
    }
}
```

大量保留 ID 需遵守宿主 SQLite 参数数量限制，必要时使用暂存表。事务失败必须抛出异常。

## 调度、回调与取消

- `BaseCompareWork` 在同一进程内使用共享 Mutex 串行执行；协调器使用独立 Mutex，避免持有数据锁等待子 Worker。任意绕过 BaseCompareWork 的自定义 Worker、多进程任务仍需宿主自行保障互斥。
- KEEP 重复入队后，Flow 观察唯一工作名对应的实际任务，并传播入队异常，不再观察不存在的新 tag。
- `cancelAllSync()` 同时取消协调器类名 tag、提交器类名 tag 和全局 tag，兼容宿主旧的无全局 tag 协调器请求。子任务被取消时整轮停止，不能当成普通失败重试；协调器停止时取消正在等待的请求。
- 协调器成功包含时间戳持久化成功。`AbstractSyncConfigProvider` 串行提交持久化与内存值；`doSaveSuccessfulSyncTime` 必须同步完成并在失败时抛错。
- 上传/下载钩子的更新回调现在会进入写入队列。同 ID 以最后一个回调结果为准；钩子返回 null 表示整条跳过，回调不会单独提交。上传产生的本地回写仍依赖远程上传成功及原始快照复核。覆盖模式本地回调必须保持当前记录 ID。
- 只有远程成功、本地回写成功且最终本地实体不再引用旧路径时，才允许删除对应附件。

## 验证与边界

测试覆盖部分获取/预处理失败、null 响应、删除失败、上传期间业务修改、附件保留与清理、钩子回调、同秒冲突、游标停用恢复、持久化失败重试、emoji/NUL 大消息，以及真实测试 WorkManager 的 KEEP、取消、配置变化、提交失败流程。JDK 25 的 Robolectric 导出参数已加入测试任务配置。

库内测试和 AAR 构建不能代替宿主 DAO 事务、真实服务端、真机断网、进程被杀和长期运行验证。当前协调器仍受普通 WorkManager 运行时限约束；取消清理不等于持久化断点续跑。
