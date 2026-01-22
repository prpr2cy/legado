package io.legado.app.utils

import android.os.Build
import android.text.Editable
import kotlin.math.max
import kotlin.math.min

private val IS_ANDROID_8 = Build.VERSION.SDK_INT in 26..27
private const val STEP = 500
private const val DELAY_MS = 1L

/**
 * 覆盖Editable.replace方法，在Android 8.0上自动进行安全操作
 */
inline fun Editable.replace(
    start: Int,
    end: Int,
    text: CharSequence
): Editable {
    val actualStart = min(start, end)
    val actualEnd = max(start, end)
    val deleteLength = actualEnd - actualStart
    val insertLength = text.length

    // 如果不是Android 8或者操作长度小，直接执行原方法
    if (!IS_ANDROID_8 || (deleteLength + insertLength) <= STEP) {
        // 调用原始的replace方法
        this.replace(actualStart, actualEnd, text)
        return this
    }

    // 大操作：先插入后删除
    if (insertLength > 0) {
        // 在删除开始位置插入新文本
        var pos = actualStart
        var i = 0
        while (i < insertLength) {
            val chunkSize = min(STEP, insertLength - i)
            val chunk = text.subSequence(i, i + chunkSize)
            this.insert(pos, chunk)
            pos += chunkSize
            i += chunkSize

            // 小延迟避免ANR
            if (chunkSize >= STEP) {
                Thread.sleep(DELAY_MS)
            }
        }
    }

    if (deleteLength > 0) {
        // 删除旧文本（注意：插入后旧文本的位置向后移动了insertLength）
        val deleteStart = actualStart + insertLength
        val deleteEnd = deleteStart + deleteLength
        var cur = deleteEnd

        while (cur > deleteStart) {
            val left = max(deleteStart, cur - STEP)
            if (left >= cur) break

            this.delete(left, cur)
            cur = left

            if ((cur - left) >= STEP) {
                Thread.sleep(DELAY_MS)
            }
        }
    }

    return this
}

/**
 * 安全删除（如果需要单独调用）
 */
fun Editable.deleteSafe(start: Int, end: Int): Editable {
    val actualStart = min(start, end)
    val actualEnd = max(start, end)

    if (!IS_ANDROID_8 || (actualEnd - actualStart) <= STEP) {
        delete(actualStart, actualEnd)
        return this
    }

    var cur = actualEnd
    while (cur > actualStart) {
        val left = max(actualStart, cur - STEP)
        if (left >= cur) break

        delete(left, cur)
        cur = left

        if ((cur - left) >= STEP) {
            Thread.sleep(DELAY_MS)
        }
    }

    return this
}

/**
 * 安全插入（如果需要单独调用）
 */
fun Editable.insertSafe(start: Int, text: CharSequence): Editable {
    if (!IS_ANDROID_8 || text.length <= STEP) {
        insert(start, text)
        return this
    }

    var pos = start
    var i = 0
    while (i < text.length) {
        val chunkSize = min(STEP, text.length - i)
        val chunk = text.subSequence(i, i + chunkSize)
        insert(pos, chunk)
        pos += chunkSize
        i += chunkSize

        if (chunkSize >= STEP) {
            Thread.sleep(DELAY_MS)
        }
    }

    return this
}