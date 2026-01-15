package io.legado.app.utils

import android.graphics.Rect
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView

fun RecyclerView.scrollCursorIntoViewIfNeeded() {
    val edit = findFocus() as? EditText ?: return
    val layout = edit.layout ?: return
    val sel = edit.selectionStart.takeIf { it >= 0 } ?: return
    val line = layout.getLineForOffset(sel)

    // 光标相对 EditText 的矩形
    val local = Rect(
        layout.getPrimaryHorizontal(sel).toInt() - 2,
        layout.getLineTop(line),
        layout.getPrimaryHorizontal(sel).toInt() + 2,
        layout.getLineBottom(line)
    )

    // 转成屏幕坐标
    val loc = IntArray(2)
    edit.getLocationOnScreen(loc)
    local.offset(loc[0], loc[1])

    // 当前可视区域（已刨掉键盘）
    val visible = Rect()
    getWindowVisibleDisplayFrame(visible)

    // 需要滚动的距离
    val overflow = local.bottom - visible.bottom
    if (overflow > 0) {
        post { scrollBy(0, overflow + 8.dp) }
    }
}

private val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density + 0.5f).toInt()