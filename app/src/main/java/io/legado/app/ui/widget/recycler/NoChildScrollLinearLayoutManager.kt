package io.legado.app.ui.widget.recycler

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.ui.widget.keyboard.KeyboardToolPop

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
    private val handler = Handler(Looper.getMainLooper())
    private var pendingScroll: Runnable? = null
    private val Int.dp: Int
        get() = (this * mContext.resources.displayMetrics.density + 0.5f).toInt()

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        // 延迟 100ms 确保键盘完全弹出，且只执行一次
        pendingScroll?.let { handler.removeCallbacks(it) }
        pendingScroll = Runnable { scrollCursor() }
        handler.postDelayed(pendingScroll!!, 100)
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recyclerView = view
        view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        pendingScroll?.let { handler.removeCallbacks(it) }
        view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        recyclerView = null
    }

    private fun scrollCursor() {
        val rv = recyclerView ?: return
        val edit = rv.findFocus() as? EditText ?: return
        val layout = edit.layout ?: return
        val sel = edit.selectionStart.takeIf { it >= 0 } ?: return
        val line = layout.getLineForOffset(sel)

        /* 1. 光标矩形（相对 EditText） */
        val cursorRect = Rect()
        edit.getFocusedRect(cursorRect)

        /* 2. 转成窗口绝对坐标 */
        val editLoc = IntArray(2)
        edit.getLocationInWindow(editLoc)
        cursorRect.offset(editLoc[0], editLoc[1])

        /**
         * 3. 用可见帧底部当键盘顶边
         * 键盘顶边 = 窗口高度 - 键盘高度
         */
        val visible = Rect()
        rv.getWindowVisibleDisplayFrame(visible)
        val keyboardTop = visible.bottom + toolbarH

        /* 4. 要滚动的距离 */
        val overflow = cursorRect.bottom - keyboardTop.toolbarHeight
        if (overflow > 0) {
            /* 5. 增量滚动：当前滚动量 + 缺口 */
            val target = rv.scrollY + overflow + 8.dp
            rv.stopScroll()
            rv.scrollBy(0, overflow + 8.dp)
        }
    }

    /**
     * 判断子项是否可见
     * @param parent RecyclerView父容器
     * @param child 要判断的子项View
     * @return true表示子项可见，false表示不可见
     */
    private fun isChildVisible(parent: RecyclerView, child: View): Boolean {
        val childRect = Rect()
        child.getDrawingRect(childRect)
        parent.offsetDescendantRectToMyCoords(child, childRect)

        val parentRect = Rect(
            parent.paddingLeft,
            parent.paddingTop,
            parent.width - parent.paddingRight,
            parent.height - parent.paddingBottom
        )
        parentRect.offset(-parent.scrollX, -parent.scrollY)

        return parentRect.contains(childRect) || Rect.intersects(parentRect, childRect)
    }

    /**
     * 判断子项的指定矩形区域是否可见
     * @param parent RecyclerView父容器
     * @param child 要判断的子项View
     * @param rect 要判断的矩形区域（相对于子项的坐标）
     * @return true表示矩形区域可见，false表示不可见
     */
    private fun isChildVisible(parent: RecyclerView, child: View, rect: Rect): Boolean {
        val childRect = Rect()
        child.getDrawingRect(childRect)
        parent.offsetDescendantRectToMyCoords(child, childRect)

        val targetRect = Rect(rect)
        targetRect.offset(childRect.left, childRect.top)

        val parentRect = Rect(
            parent.paddingLeft,
            parent.paddingTop,
            parent.width - parent.paddingRight,
            parent.height - parent.paddingBottom
        )
        parentRect.offset(-parent.scrollX, -parent.scrollY)

        return parentRect.contains(targetRect) || Rect.intersects(parentRect, targetRect)
    }

    /**
     * 请求将子项的指定矩形区域滚动到屏幕可见区域
     * @param parent RecyclerView父容器
     * @param child 要滚动的子项View
     * @param rect 要滚动到可见区域的矩形区域（相对于子项的坐标）
     * @param immediate 是否立即滚动，不使用平滑滚动动画
     * @return true表示滚动已执行，false表示滚动未执行
     */
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean
    ): Boolean {
        return requestChildRectangleOnScreen(parent, child, rect, immediate, false)
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