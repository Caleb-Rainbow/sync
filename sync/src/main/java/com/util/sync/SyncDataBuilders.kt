package com.util.sync

import androidx.work.Data

/**
 * WorkManager Result Data 构建工具。
 */

fun createSuccessData(message: String) = Data.Builder().putString("successMessage", message).build()
fun createFailData(message: String) = Data.Builder().putString("failMessage", message).build()
