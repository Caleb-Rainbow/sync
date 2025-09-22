package com.util.sync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.util.sync.SyncConfigProvider
import com.util.sync.data.LogLevel
import com.util.sync.data.SessionLogGroup
import com.util.sync.data.SyncLog

/**
 * @description
 * @author 杨帅林
 * @create 2025/6/27 11:34
 **/
/**
 * 日志可视化主屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogScreen(
    modifier: Modifier,
    goBack: (() -> Unit)? = null,
    viewModel: SyncLogViewModel,
    syncConfigProvider: SyncConfigProvider,
    workerNameResolver: (String) -> String,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "同步记录")
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                navigationIcon = {
                    goBack?.let {back->
                        Icon(
                            modifier = Modifier
                                .size(40.dp)
                                .clickable { back() },
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null
                        )
                    }
                }
            )
        }
        , containerColor = Color.Transparent
    ) { paddingValues ->
        val sessionGroup = uiState.sessionLogs?.collectAsLazyPagingItems()
        sessionGroup?.let { list ->
            if (list.itemCount == 0) {
                Column(
                    modifier = modifier.padding(paddingValues),
                ) {
                    Text(
                        text = "最后一次成功同步时间：${syncConfigProvider.syncDataTime}"
                    )
                    Text("暂无日志记录", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    modifier = modifier
                        .padding(paddingValues)
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "最后一次成功同步时间：${syncConfigProvider.syncDataTime}"
                        )
                    }
                    items(list.itemCount) { index ->
                        val session = list[index] ?: return@items
                        val isExpanded = session.sessionId in uiState.expandedSessionIds
                        SessionItem(
                            session = session,
                            isExpanded = isExpanded,
                            onToggle = { viewModel.toggleSessionExpanded(session.sessionId) },
                            viewModel = viewModel,
                            workerNameResolver = workerNameResolver
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个会话的 Composable，包含摘要和可展开的详情
 */
@Composable
fun SessionItem(
    session: SessionLogGroup,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    viewModel: SyncLogViewModel,
    workerNameResolver: (String) -> String,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column {
            SessionSummaryRow(session, isExpanded, onToggle)
            var logsByWorker: Map<String, List<SyncLog>>? by rememberSaveable {
                mutableStateOf(null)
            }
            AnimatedVisibility(visible = isExpanded) {
                // LaunchedEffect 保持不变，但回调接收的数据结构变了
                LaunchedEffect(Unit) {
                    if (logsByWorker == null) {
                        viewModel.getLogForSession(sessionId = session.sessionId) {
                            logsByWorker = it
                        }
                    }
                }

                logsByWorker?.let {
                    // 将分组后的日志和UI状态传递给 SessionDetail
                    SessionDetail(
                        sessionId = session.sessionId,
                        logsByWorker = it,
                        expandedWorkerGroups = uiState.expandedWorkerGroups,
                        onToggleWorkerGroup = { workerName ->
                            viewModel.toggleWorkerGroupExpanded(session.sessionId, workerName)
                        },
                        workerNameResolver = workerNameResolver
                    )
                }
            }
        }
    }
}

/**
 * 会话摘要行，可点击
 */
@Composable
private fun SessionSummaryRow(session: SessionLogGroup, isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusIndicator(status = session.overallStatusStr)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${session.startTime}-----${session.endTime}",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "日志: ${session.logCount}条 | session: ${session.sessionId}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "展开/折叠",
            modifier = Modifier.rotate(if (isExpanded) 180f else 0f)
        )
    }
}

/**
 * 展开后显示的详细日志列表
 */
@Composable
private fun SessionDetail(
    sessionId: String,
    logsByWorker: Map<String, List<SyncLog>>,
    expandedWorkerGroups: Set<String>,
    onToggleWorkerGroup: (String) -> Unit,
    workerNameResolver: (String) -> String,

    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 遍历分组后的Map，为每个Worker创建一个可展开项
        logsByWorker.entries.forEachIndexed { index, (workerName, logs) ->
            WorkerLogGroupItem(
                sessionId = sessionId,
                workerName = workerName,
                logs = logs,
                isExpanded = "$sessionId-$workerName" in expandedWorkerGroups,
                onToggle = { onToggleWorkerGroup(workerName) },
                workerNameResolver = workerNameResolver
            )
            if (index < logsByWorker.size - 1) {
                HorizontalDivider(Modifier, DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            }
        }
    }
}

/**
 * 新增 Composable：用于显示单个 Worker 的日志分组
 */
@Composable
private fun WorkerLogGroupItem(
    sessionId: String,
    workerName: String,
    logs: List<SyncLog>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    workerNameResolver: (String) -> String,
) {
    // 【核心改动】
    // 不再使用硬编码的 `when` 语句，而是通过 SyncTaskHelper 动态获取显示名称。
    // 这样一来，UI 和您的业务逻辑完全解耦。
    val workerDisplayName = remember(workerName) {
        workerNameResolver(workerName)
    }

    // 计算当前Worker的总体状态
    val workerStatus = remember(logs) {
        when {
            logs.any { it.logLevel == "ERROR" } -> "ERROR"
            logs.any { it.logLevel == "WARN" } -> "WARN"
            else -> "INFO"
        }
    }


    Column {
        // Worker分组的摘要行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIndicator(status = workerStatus)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workerDisplayName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "日志: ${logs.size}条",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "展开/折叠",
                modifier = Modifier.rotate(if (isExpanded) 180f else 0f)
            )
        }

        // 展开后显示该Worker的日志列表
        AnimatedVisibility(visible = isExpanded) {
            LazyColumn (
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .padding(start = 16.dp, bottom = 8.dp) // 缩进以表示层级
            ) {
                items(logs){ log ->
                    LogEntryItem(log)
                    if (log != logs.last()) HorizontalDivider(
                        Modifier,
                        DividerDefaults.Thickness,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

/**
 * 单条日志条目
 */
@Composable
private fun LogEntryItem(log: SyncLog) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        val levelColor = when (LogLevel.fromString(log.logLevel)) {
            LogLevel.INFO -> Color(0xFF388E3C) // Green
            LogLevel.WARN -> Color(0xFFF57C00) // Orange
            LogLevel.ERROR -> Color(0xFFD32F2F) // Red
        }
        Text(
            text = log.logLevel,
            color = levelColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(50.dp)
        )
        Column {
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${log.timestamp} - ${log.workerName}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}

/**
 * 会话状态指示器（彩色圆点）
 */
@Composable
private fun StatusIndicator(status: String) {
    val color = when (status) {
        LogLevel.INFO.level -> Color(0xFF4CAF50)
        LogLevel.WARN.level -> Color(0xFFFF9800)
        LogLevel.ERROR.level -> Color(0xFFF44336)
        else -> Color.Gray
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}