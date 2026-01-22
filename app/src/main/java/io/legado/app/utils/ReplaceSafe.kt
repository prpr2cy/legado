package io.legado.app.utils

import android.os.Build
import android.text.Editable
import kotlin.math.max
import kotlin.math.min

inline fun Editable.replace(
    start: Int,
    end: Int,
    text: CharSequence
): Editable {
    val isAndroid8 = Build.VERSION.SDK_INT in 26..27
    val step = 500
    val actualStart = min(start, end)
    val actualEnd = max(start, end)
    val deleteLength = actualEnd - actualStart
    val insertLength = text.length

    if (!isAndroid8 || deleteLength + insertLength <= step) {
        @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
        this.replace(actualStart, actualEnd, text)
        return this
    }

    if (deleteLength > 0) {
        this.deleteSafe(actualStart, actualEnd)
    }
    if (insertLength > 0) {
        this.insertSafe(actualStart, text)
    }
    return this
}

fun Editable.deleteSafe(start: Int, end: Int): Editable {
    val isAndroid8 = Build.VERSION.SDK_INT in 26..27
    val step = 500
    val actualStart = min(start, end)
    val actualEnd = max(start, end)
    val deleteLength = actualEnd - actualStart

    if (!isAndroid8 || deleteLength <= step) {
        this.delete(actualStart, actualEnd)
        return this
    }

    var cur = actualEnd
    while (cur > actualStart) {
        val left = max(actualStart, cur - step)
        if (left >= cur) break
        this.delete(left, cur)
        cur = left
    }
    return this
}

fun Editable.insertSafe(start: Int, text: CharSequence): Editable {
    val isAndroid8 = Build.VERSION.SDK_INT in 26..27
    val step = 500
    val insertLength = text.length

    if (!isAndroid8 || insertLength <= step) {
        this.insert(start, text)
        return this
    }

    var pos = start
    var i = 0
    while (i < insertLength) {
        val chunkSize = min(step, insertLength - i)
        val chunk = text.subSequence(i, i + chunkSize)
        this.insert(pos, chunk)
        pos += chunkSize
        i += chunkSize
    }
    return this
}