package com.util.sync

import androidx.work.Data

/**
 * WorkManager Result Data 构建工具。
 */

fun createSuccessData(message: String): Data = createMessageData("successMessage", message)
fun createFailData(message: String): Data = createMessageData("failMessage", message)

/**
 * Data 使用 modified UTF-8（writeUTF），计入 NUL 和 UTF-16 代理项的实际开销。
 * 保留额外空间给键、类型标记和截断提示；超长单行同样保留有用前缀。
 */
private fun createMessageData(key: String, message: String): Data {
    val budget = 10_000
    val suffix = "…（信息已截断，详见日志）"
    fun cost(c: Char) = when (c.code) {
        in 1..127 -> 1
        in 0..2047 -> 2
        else -> 3
    }
    var bytes = 0
    var end = 0
    while (end < message.length && bytes + cost(message[end]) <= budget) {
        bytes += cost(message[end++])
    }
    val text = if (end == message.length) message else {
        if (end > 0 && message[end - 1].isHighSurrogate()) end--
        message.substring(0, end) + suffix
    }
    return Data.Builder().putString(key, text).build()
}
