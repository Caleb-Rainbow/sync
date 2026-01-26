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

// ===========================================
// 4. 惰性求值日志扩展 (避免不必要的字符串拼接开销)
// ===========================================

/**
 * 惰性日志扩展函数 - 使用缓存的 TAG
 * 适用于高频调用场景，避免每次都通过反射获取类名
 * 
 * @param tag 预先缓存的 TAG 字符串
 * @param lazyMsg 延迟构建的日志消息
 */
inline fun libLogDLazy(tag: String, lazyMsg: () -> String) {
    LibLogManager.logger.d(tag, lazyMsg())
}

inline fun libLogILazy(tag: String, lazyMsg: () -> String) {
    LibLogManager.logger.i(tag, lazyMsg())
}

inline fun libLogWLazy(tag: String, lazyMsg: () -> String) {
    LibLogManager.logger.w(tag, lazyMsg())
}

inline fun libLogELazy(tag: String, lazyMsg: () -> String) {
    LibLogManager.logger.e(tag, lazyMsg(), null)
}

inline fun libLogVLazy(tag: String, lazyMsg: () -> String) {
    LibLogManager.logger.v(tag, lazyMsg())
}

/**
 * 获取类名的扩展属性（供外部缓存使用）
 */
val Any.libLogTag: String
    get() = this.LIB_TAG