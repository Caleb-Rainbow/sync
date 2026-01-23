package com.util.sync.log

/**
 * 1. 定义日志接口 (API)
 * 库只负责调用这些方法，具体实现由 App 注入
 */
interface ILibLogger {
    fun v(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, tr: Throwable?)

    // 操作日志接口：只传递原始数据，不负责拼接字符串，格式化逻辑交给实现层
    fun logOperation(tag: String, type: String, result: String, params: Map<String, Any?>)
}

/**
 * 2. 静态代理单例 (Manager)
 * 用于保存 App 注入的实现类
 */
object LibLogManager {
    // 默认为空实现，防止未注入时调用崩溃
    var logger: ILibLogger = object : ILibLogger {
        override fun v(tag: String, msg: String) {}
        override fun d(tag: String, msg: String) {}
        override fun i(tag: String, msg: String) {}
        override fun w(tag: String, msg: String) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
        override fun logOperation(tag: String, type: String, result: String, params: Map<String, Any?>) {}
    }

    fun init(impl: ILibLogger) {
        this.logger = impl
    }
}

// ===========================================
// 3. 库内部使用的扩展方法 (为了让你写代码和在 App 里一样爽)
// ===========================================

/**
 * 复制了一份 TAG 获取逻辑到库里，这样库代码也能自动获取类名
 */
private val Any.LIB_TAG: String
    get() {
        val tag = this.javaClass.simpleName
        return if (tag.isNullOrEmpty()) "Anonymous" else tag
    }

// 基础日志扩展
fun Any.libLogV(msg: String) = LibLogManager.logger.v(this.LIB_TAG, msg)
fun Any.libLogD(msg: String) = LibLogManager.logger.d(this.LIB_TAG, msg)
fun Any.libLogI(msg: String) = LibLogManager.logger.i(this.LIB_TAG, msg)
fun Any.libLogW(msg: String) = LibLogManager.logger.w(this.LIB_TAG, msg)
fun Any.libLogE(msg: String, tr: Throwable? = null) = LibLogManager.logger.e(this.LIB_TAG, msg, tr)

// 操作日志扩展
fun Any.libLogOp(type: String, result: String, params: Map<String, Any?> = emptyMap()) {
    LibLogManager.logger.logOperation(this.LIB_TAG, type, result, params)
}

fun Any.libLogOpStart(type: String, params: Map<String, Any?> = emptyMap()) {
    LibLogManager.logger.logOperation(this.LIB_TAG, type, "开始", params)
}

fun Any.libLogOpSuccess(type: String, params: Map<String, Any?> = emptyMap()) {
    LibLogManager.logger.logOperation(this.LIB_TAG, type, "成功", params)
}

fun Any.libLogOpFail(type: String, error: String, params: Map<String, Any?> = emptyMap()) {
    // 失败时，我们将错误信息合并到 params 或者 result 中传出去
    LibLogManager.logger.logOperation(this.LIB_TAG, type, "失败 | 错误:$error", params)
}