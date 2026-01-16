package io.legado.app.ui.widget.recycler

import android.graphics.Rect
import android.content.Context
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
     * 禁止自动滚动时，光标被键盘遮挡，需手动滚到可视区
     */
    private var mContext = context
    private var recyclerView: RecyclerView? = null
    private var keyboardHeight: Int = 0

    /*
    private val resetTapRunnable = Runnable { fromTap = false }
    private var fromTap: Boolean = false
        set(value) {
            field = value
            if (value) recyclerView?.removeCallbacks(resetTapRunnable)
            else recyclerView?.postDelayed(resetTapRunnable, 100)
        }

    private val scrollRunnable = Runnable { scrollCursorToVisible() }
    */

    private val Int.dp: Int
        get() = (this * mContext.resources.displayMetrics.density + 0.5f).toInt()

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val rv = recyclerView ?: return@OnGlobalLayoutListener
        val rect = Rect()
        rv.getWindowVisibleDisplayFrame(rect)
        val newH = rv.rootView.height - rect.bottom
        if (newH > keyboardHeight) {
            keyboardHeight = newH
            scrollCursorToVisible()
        } else if (newH < keyboardHeight) {
            keyboardHeight = 0
        }
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recyclerView = view
        view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        recyclerView?.removeCallbacks(resetTapRunnable)
        recyclerView?.removeCallbacks(scrollRunnable)
        recyclerView = null
        view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    private fun scrollCursorToVisible() {
        val rv = recyclerView ?: return
        val edit = rv.findFocus() as? EditText ?: return
        val layout = edit.layout ?: return
        val selection = edit.selectionStart.takeIf { it >= 0 } ?: return

        // 获取可视区域矩形
        val parentRect = Rect()
        rv.getWindowVisibleDisplayFrame(parentRect)

        // 键盘/工具栏顶边的窗口坐标
        val keyboardTop = parentRect.bottom - KeyboardToolPop.toolbarHeight

        // 计算光标行底边的窗口坐标
        val line = layout.getLineForOffset(selection)
        val lineBottom = layout.getLineBottom(line)
        val editLoc = IntArray(2).also { edit.getLocationInWindow(it) }
        val lineBottomInWindow = editLoc[1] + lineBottom

        // 先让EditText内部滚动，把文字露出来
        val needInside = lineBottomInWindow - edit.scrollY - keyboardTop
        if (needInside > 0) {
            edit.scrollBy(0, needInside + 8.dp)
        }

        // 仍被盖住，并且内部滚动已到低，再让RecyclerView滚动
        rv.post {
            val canScrollInside = edit.canScrollVertically(1)
            val itemBottomInWindow = editLoc[1] + edit.height
            val needRv = itemBottomInWindow - keyboardTop

            if (!canScrollInside && needRv > 0) {
                rv.stopScroll()
                rv.scrollBy(0, needRv + 8.dp)
            }
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
        ).apply { offset(-parent.scrollX, -parent.scrollY) }

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
         * 拦截初次点击产生焦点时触发的自动滚动
         * 后续移动光标、键盘操作等场景不会拦截，因为 focusedChildVisible = false
         * 如果子View已经在可见区域内，无需滚动
         * 否则调用父方法进行滚动
         */
        return when {
            !allowFocusScroll && focusedChildVisible -> false
            isChildVisible(parent, child, rect) -> false
            else -> super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
        }
    }
}