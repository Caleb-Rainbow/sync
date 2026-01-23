package com.util.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.util.ktor.data.heart.HeartRepository
import com.util.sync.SyncConfigProvider
import com.util.sync.createFailData
import com.util.sync.createSuccessData
import com.util.sync.log.libLogD
import com.util.sync.log.libLogE
import com.util.sync.log.libLogI
import com.util.sync.log.libLogW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 心跳任务工作器
 * 负责定期向服务器发送心跳以保持设备在线状态
 * 
 * @author 杨帅林
 * @create 2025/6/24 10:27
 */
class HeartWork(
    appContext: Context,
    workerParams: WorkerParameters,
    private val heartRepository: HeartRepository,
    private val syncConfigProvider: SyncConfigProvider,
) : CoroutineWorker(appContext, workerParams) {
    
    companion object {
        private const val TIMEOUT_THRESHOLD_MS = 5 * 60 * 1000L // 5分钟超时阈值
    }
    
    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }
    
    private fun formatTimestamp(timeMs: Long): String = dateFormat.format(Date(timeMs))
    
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val workerId = id.toString().takeLast(8)
        
        libLogI("════════════════════════════════════════")
        libLogI("🫀 心跳任务开始")
        libLogI("  工作ID: $workerId")
        libLogI("  开始时间: ${formatTimestamp(startTime)}")
        libLogI("════════════════════════════════════════")
        
        // 检查心跳开关
        if (!syncConfigProvider.isHeartbeat) {
            libLogI("⏭️ 心跳功能未开启，任务跳过")
            libLogD("  配置 isHeartbeat = false")
            return@withContext Result.success(createSuccessData("未开启心跳"))
        }
        
        // 检查用户校验状态
        if (syncConfigProvider.username.isEmpty()) {
            libLogW("⚠️ 用户未校验，心跳任务终止")
            libLogD("  username 为空，需要先完成用户登录")
            return@withContext Result.failure(createFailData("未校验"))
        }
        
        // 记录心跳请求参数
        val deviceNumber = syncConfigProvider.deviceNumber
        val heartbeatPeriod = syncConfigProvider.heartbeatPeriod
        
        libLogI("📤 发送心跳请求...")
        libLogD("  设备号: $deviceNumber")
        libLogD("  心跳周期: ${heartbeatPeriod}s")
        libLogD("  用户: ${syncConfigProvider.username}")
        
        try {
            val result = heartRepository.heartbeat(
                deviceNumber = deviceNumber,
                second = heartbeatPeriod
            )
            
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            
            // 检查是否超时
            if (duration > TIMEOUT_THRESHOLD_MS) {
                libLogW("⏱️ 警告: 心跳任务耗时超过5分钟!")
                libLogW("  实际耗时: ${duration}ms (${duration / 1000}s)")
            }
            
            if (result.isSuccess()) {
                libLogI("✅ 心跳成功")
                libLogI("  响应码: ${result.code}")
                libLogI("  耗时: ${duration}ms")
                libLogI("  结束时间: ${formatTimestamp(endTime)}")
                libLogI("────────────────────────────────────────")
                return@withContext Result.success()
            } else {
                libLogE("❌ 心跳失败")
                libLogE("  错误码: ${result.code}")
                libLogE("  错误信息: ${result.message}")
                libLogE("  耗时: ${duration}ms")
                libLogE("  结束时间: ${formatTimestamp(endTime)}")
                libLogI("────────────────────────────────────────")
                return@withContext Result.failure(
                    createFailData("心跳失败,错误码->${result.code} ${result.message}")
                )
            }
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            
            libLogE("💥 心跳任务发生异常", e)
            libLogE("  异常类型: ${e.javaClass.simpleName}")
            libLogE("  异常信息: ${e.message}")
            libLogE("  耗时: ${duration}ms")
            libLogE("  堆栈信息:\n${e.stackTraceToString()}")
            libLogI("────────────────────────────────────────")
            
            return@withContext Result.failure(
                createFailData("心跳异常: ${e.message}")
            )
        }
    }
}