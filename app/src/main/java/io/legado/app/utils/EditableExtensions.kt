package io.legado.app.utils

import android.os.Build
import android.text.Editable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val IS_ANDROID_8 = Build.VERSION.SDK_INT in 26..27
private const val STEP = 500
private const val DELAY_MS = 10
private val operationQueue = mutableListOf<suspend () -> Unit>()
private var isProcessing = false

inline fun Editable.replace(
    start: Int,
    end: Int,
    text: CharSequence
): Editable {
    val actualStart = min(start, end)
    val actualEnd = max(start, end)
    val deleteLength = actualEnd - actualStart
    val insertLength = text.length

    if (!IS_ANDROID_8 || deleteLength + insertLength <= STEP) {
        @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
        this.replace(actualStart, actualEnd, text)
        return this
    }

    GlobalScope.launch(Dispatchers.Main) {
        enqueueOperation {
            if (deleteLength > 0) {
                deleteSafe(actualStart, actualEnd)
            }
            if (insertLength > 0) {
                insertSafe(actualStart, text)
            }
        }
    }

    return this
}

fun Editable.deleteSafe(
    start: Int,
    end: Int
): Editable {
    val actualStart = min(start, end)
    val actualEnd = max(start, end)
    val deleteLength = actualEnd - actualStart

    if (!IS_ANDROID_8 || deleteLength <= STEP) {
        this.delete(actualStart, actualEnd)
        return this
    }

    GlobalScope.launch(Dispatchers.Main) {
        enqueueOperation {
            var cur = actualEnd
            while (cur > actualStart) {
                val left = max(actualStart, cur - STEP)
                if (left >= cur) break

                withContext(Dispatchers.Main) {
                    this@deleteSafe.delete(left, cur)
                }

                cur = left
                delay(DELAY_MS)
            }
        }
    }

    return this
}

fun Editable.insertSafe(
    start: Int,
    text: CharSequence
): Editable {
    val insertLength = text.length

    if (!IS_ANDROID_8 || insertLength <= STEP) {
        this.insert(start, text)
        return this
    }

    GlobalScope.launch(Dispatchers.Main) {
        enqueueOperation {
            var pos = start
            var i = 0
            while (i < insertLength) {
                val chunkSize = min(STEP, insertLength - i)
                val chunk = text.subSequence(i, i + chunkSize)

                withContext(Dispatchers.Main) {
                    this@insertSafe.insert(pos, chunk)
                }

                pos += chunkSize
                i += chunkSize
                delay(DELAY_MS)
            }
        }
    }

    return this
}

/**
 * 将操作加入全局队列，确保顺序执行
 */
private suspend fun enqueueOperation(operation: suspend () -> Unit) {
    synchronized(operationQueue) {
        operationQueue.add(operation)
    }

    if (!isProcessing) {
        processQueue()
    }
}

/**
 * 处理全局操作队列
 */
private suspend fun processQueue() {
    isProcessing = true
    try {
        while (true) {
            val operation = synchronized(operationQueue) {
                if (operationQueue.isEmpty()) {
                    return@synchronized null
                }
                operationQueue.removeAt(0)
            } ?: break

            operation()
        }
    } finally {
        isProcessing = false
    }
}