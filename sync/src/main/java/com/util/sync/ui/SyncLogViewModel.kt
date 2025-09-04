package com.util.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.util.sync.data.SessionLogGroup
import com.util.sync.data.SyncLog
import com.util.sync.data.SyncLogDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.collections.groupBy
import kotlin.collections.toMutableSet

data class SyncLogUiState(
    // 分组后的会话日志
    val sessionLogs: Flow<PagingData<SessionLogGroup>>? = null,
    // 记录哪些会话是展开状态的
    val expandedSessionIds: Set<String> = emptySet(),
    // 新增：记录哪些Worker分组是展开的。Key设计为 "${sessionId}-${workerName}" 保证唯一性
    val expandedWorkerGroups: Set<String> = emptySet()
)

/**
 * SyncLog 的 ViewModel
 *
 * @param syncLogDao 通过 Koin 注入的 DAO 实例
 */
class SyncLogViewModel(
    private val syncLogDao: SyncLogDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncLogUiState())
    val uiState: StateFlow<SyncLogUiState> = _uiState.asStateFlow()

    init {
        loadLogs()
    }

    /**
     * 加载并处理日志数据
     */
    private fun loadLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { currentState ->
                currentState.copy(sessionLogs = Pager(PagingConfig(pageSize = 20)) {
                    syncLogDao.pagingGroupList()
                }.flow)
            }
        }
    }

    fun getLogForSession(sessionId: String,onResult: (Map<String, List<SyncLog>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val logs = syncLogDao.getLogsForSession(sessionId)
            // 核心改动：使用 groupBy 对日志列表进行分组
            val groupedLogs = logs.groupBy { it.workerName }
            onResult(groupedLogs)
        }
    }

    /**
     * 切换指定 sessionId 的展开/折叠状态
     */
    fun toggleSessionExpanded(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { currentState ->
                val updatedIds = currentState.expandedSessionIds.toMutableSet()
                if (sessionId in updatedIds) {
                    updatedIds.remove(sessionId)
                } else {
                    updatedIds.add(sessionId)
                }
                currentState.copy(expandedSessionIds = updatedIds)
            }
        }
    }

    /**
     * 新增：切换指定 Worker 分组的展开/折叠状态
     */
    fun toggleWorkerGroupExpanded(sessionId: String, workerName: String) {
        viewModelScope.launch {
            _uiState.update { currentState ->
                val groupKey = "$sessionId-$workerName"
                val updatedIds = currentState.expandedWorkerGroups.toMutableSet()
                if (groupKey in updatedIds) {
                    updatedIds.remove(groupKey)
                } else {
                    updatedIds.add(groupKey)
                }
                currentState.copy(expandedWorkerGroups = updatedIds)
            }
        }
    }
}