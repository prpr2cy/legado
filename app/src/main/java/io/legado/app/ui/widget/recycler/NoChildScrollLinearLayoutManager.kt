package io.legado.app.ui.widget.recycler

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 禁止子项自动滚动的LinearLayoutManager
 * 主要用于解决RecyclerView中EditText获取焦点时自动滚动的问题
 */
class NoChildScrollLinearLayoutManager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayoutManager(context, attrs, defStyleAttr, defStyleRes) {

    /**
     * 是否允许因焦点变化产生的自动滚动
     * 默认值为true，保持与原生LinearLayoutManager一致的行为
     */
    var allowFocusScroll: Boolean = true

    /**
     * 禁止自动滚动，光标被键盘遮挡时，需手动滚到可视区
     */
    private var mContext = context
    private var recyclerView: RecyclerView? = null
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        handleCursorIfNeeded()
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recyclerView = view
        view.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        view.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        recyclerView = null
    }

    private fun handleCursorIfNeeded() {
        val rv = recyclerView ?: return
        val edit = rv.findFocus() as? EditText ?: return
        val layout = edit.layout ?: return
        val sel = edit.selectionStart.takeIf { it >= 0 } ?: return
        val line = layout.getLineForOffset(sel)

        /* 1. 光标矩形（相对 EditText） */
        val cursorRect = Rect(
            0, layout.getLineTop(line),
            0, layout.getLineBottom(line)
        )

        /* 2. 转成 RV 坐标系（含当前滚动量） */
        rv.offsetDescendantRectToMyCoords(edit, cursorRect)

        /* 3. 需要 RV 顶部距离光标底部 + 8 dp 留白 */
        val targetScrollY = cursorRect.bottom + 8.dp - rv.height

        /* 4. 只滚一次，不叠加 */
        if (targetScrollY > rv.scrollY) {
            rv.stopScroll()
            rv.post { rv.scrollTo(0, targetScrollY) }
        }
    }

    /**
     * 请求将子项的指定矩形区域滚动到屏幕可见区域
     * @param parent RecyclerView父容器
     * @param child 要滚动的子项View
     * @param rect 要滚动到可见区域的矩形区域（相对于子项的坐标）
     * @param immediate 是否立即滚动，不使用平滑滚动动画
     * @param focusedChildVisible 是否只在子项获取焦点时才滚动
     * @return true表示滚动已执行，false表示滚动未执行
     */
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        /**
         * 拦截初次焦点触发的自动滚动
         * 后续光标移动、键盘操作等场景不会拦截，因为 focusedChildVisible = false
         */
        if (!allowFocusScroll && focusedChildVisible) {
            return false
        }

        /* 如果子View已经在可见区域内，不需要滚动 */
        if (isChildVisible(parent, child, rect)) {
            return false
        }

        /* 否则调用父类方法进行滚动 */
        return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
    }
}