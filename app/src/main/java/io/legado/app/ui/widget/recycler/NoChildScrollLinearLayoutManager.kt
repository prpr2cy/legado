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
        if (android.os.Build.VERSION.SDK_INT >= 999) {
            // 延迟 100ms 确保键盘完全弹出，且只执行一次
            pendingScroll?.let { handler.removeCallbacks(it) }
            pendingScroll = Runnable { scrollCursor() }
            handler.postDelayed(pendingScroll!!, 100)
        }
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
        val rv  = recyclerView ?: return
        val edit = rv.findFocus() as? EditText ?: return
        val layout = edit.layout ?: return
        val sel = edit.selectionStart.takeIf { it >= 0 } ?: return

        /* 1. 光标行底边（相对于文字容器） */
        val line = layout.getLineForOffset(sel)
        val lineBottom = layout.getLineBottom(line)

        /* 2. 键盘/工具栏顶边（窗口绝对坐标） */
        val parentRect = Rect()
        rv.getWindowVisibleDisplayFrame(parentRect)
        val keyboardTop = parentRect.bottom - KeyboardToolPop.toolbarHeight

        /* 3. 行底边 → 窗口坐标 */
        val editLoc = IntArray(2).also { edit.getLocationInWindow(it) }
        val lineBottomInWindow = editLoc[1] + lineBottom - edit.scrollY

        /* 4. 先让 EditText 内部滚动，把文字露出来 */
        val needInside = lineBottomInWindow - keyboardTop
        if (needInside > 0) {
            edit.scrollBy(0, needInside + 8.dp)
        }

        /* 5. 真正需要 RecyclerView 滚动的条件：
         *    item 下沿仍被盖住，并且内部滚动已到底（scrollY 无法再变大）
         */
        rv.post {
            val canScrollInside = edit.canScrollVertically(1)   // 是否还能往下滑
            val itemBottomInWindow = editLoc[1] + edit.height
            val needRv = itemBottomInWindow - keyboardTop

            if (!canScrollInside && needRv > 0) {
                // 只把 item 下沿刚好顶到键盘上沿，绝不多滚
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
     * 计算子项的指定矩形区域与父容器可见区域的重叠/偏移
     * @param parent RecyclerView父容器
     * @param child  要判断的子项View
     * @param rect  要判断的矩形区域（相对于子项的坐标）
     * @return dy > 0 往下滚，dy < 0 往上滚，dy = 0 无需滚动
     */
    private fun calcScrollDy(parent: RecyclerView, child: View, rect: Rect): Int {
        // 目标矩形 → 父容器坐标
        val targetRect = Rect(rect)
        child.getDrawingRect(targetRect)
        parent.offsetDescendantRectToMyCoords(child, targetRect)

        // 父容器可见区域（已考虑 scroll 及 padding）
        val parentRect = Rect(
            parent.paddingLeft,
            parent.paddingTop,
            parent.width - parent.paddingRight,
            parent.height - parent.paddingBottom
        ).apply { offset(-parent.scrollX, -parent.scrollY) }

        // 完全包含即认为“可见”
        if (parentRect.contains(targetRect)) 0

        // 计算最少的滚动距离”
        val dy = when {
            targetRect.bottom > parentRect.bottom -> {
                targetRect.bottom - parentRect.bottom // 往下滚
            }
            targetRect.top < parentRect.top -> {
                targetRect.top - parentRect.top // 往上滚
            }
            else -> 0
        }
        return dy
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
        // 拦截初次焦点触发的自动滚动
        // 后续光标移动、键盘操作等场景不会拦截，因为 focusedChildVisible = false
        if (!allowFocusScroll && focusedChildVisible) {
            return false
        }

        val dy = calcScrollDy(parent, child, rect)

        // 如果子View已经完全在可见区域内，无需滚动
        if (dy == 0) return false

        // 部分可见或完全不可见，滚动到可见区域
        // parent.scrollBy(0, dy)
        return true
    }
}