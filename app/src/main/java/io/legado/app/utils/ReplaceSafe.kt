package io.legado.app.utils

import android.os.Build
import android.text.Editable
import java.lang.CharSequence
import kotlin.math.max
import kotlin.math.min

inline fun Editable.replace(
    start: Int,
    end: Int,
    text: CharSequence
): Editable {
    val isAndroid8 = Build.VERSION.SDK_INT in 26..27
    val step = 500
    val min = min(start, end)
    val max = max(start, end)
    if (!isAndroid8 || (max - min + text.length <= step)) {
        @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
        replace(start, end, text)
        return this
    }
    var pos = min
    var i = 0
    while (i < text.length) {
        val size = min(step, text.length - i)
        val chunk = text.subSequence(i, i + size)
        replace(pos, pos + size, chunk)
        pos += size
        i += size
    }
    if (max > post) {
        deleteSafe(pos, max)
    }
    return this
}

fun Editable.deleteSafe(start: Int, end: Int) {
    val isAndroid8 = Build.VERSION.SDK_INT in 26..27
    val step = 500
    val min = min(start, end)
    val max = max(start, end)
    if (!isAndroid8 || max - min <= step) {
        delete(min, max)
        return
    }
    var cur = max
    while (cur > min) {
        val left = max(min, cur - step)
        if (left == cur) break
        delete(left, cur)
        cur = left
    }
}
