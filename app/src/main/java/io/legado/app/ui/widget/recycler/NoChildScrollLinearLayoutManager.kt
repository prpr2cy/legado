package io.legado.app.ui.widget.recycler

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.constant.AppLog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

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

    // 是否允许因焦点变化产生的自动滚动，默认允许
    private var allowFocusScroll: Boolean = true
    // 记录当前的RecyclerView
    private var recyclerView: RecyclerView? = null
    // 记录获得焦点的EditText
    private var editText: EditText? = null
    // 记录键盘的弹起状态
    private var isKeyboardShowing: Boolean = false
    // 工具栏高度
    private val toolbarHeight: Int get() = KeyboardToolPop.toolbarHeight
    // 留白高度
    private val keyboardMargin: Int = (8 * context.resources.displayMetrics.density + 0.5f).toInt()

    fun setFocusedEditText(view: EditText?) {
        editText = view
    }

    private val keyboardListener = ViewTreeObserver.OnPreDrawListener {
        val rv = recyclerView ?: return@OnPreDrawListener true
        val root = rv.rootView ?: return@OnPreDrawListener true
        val visible = Rect().apply {
            root.getWindowVisibleDisplayFrame(this)
        }
        val showing = visible.height() < root.height * 0.8f
        if (showing != isKeyboardShowing) {
            isKeyboardShowing = showing
            if (showing) {
                scrollCursorToVisible()
            }
        }
        true
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recyclerView = view
        view.viewTreeObserver.addOnPreDrawListener(keyboardListener)
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        recyclerView = null
        editText = null
        view.viewTreeObserver.removeOnPreDrawListener(keyboardListener)
    }

    /**
     * 禁止自动滚动时，光标被键盘遮挡，需手动滚到可视区
     * 先滚动EditText内部，让光标尽可能在EditText可视区域内
     * 如果内部滚动后光标仍然被键盘遮挡，再滚动RecyclerView
     */
    private fun scrollCursorToVisible() {
        val rv = recyclerView ?: return
        val root = recyclerView?.rootView ?: return
        val edit = editText ?: return
        val layout = edit.layout ?: return
        val selection = edit.selectionStart.takeIf { it >= 0 } ?: return

        // 计算光标顶部/底部相对EditText的位置
        val line = layout.getLineForOffset(selection)
        val cursorTopInEdit = layout.getLineTop(line) - edit.scrollY
        val cursorBottomInEdit = layout.getLineBottom(line) - edit.scrollY

        // 计算EditText顶部/底部在窗口中的位置
        val editLoc = IntArray(2)
        edit.getLocationInWindow(editLoc)
        val editTopInWindow = editLoc[1]
        val editBottomInWindow = editLoc[1] + edit.height

        // 计算光标顶部/底部在窗口中的位置
        val cursorBottomInWindow = editTopInWindow + cursorBottomInEdit

        // 计算键盘顶部在窗口的位置（考虑工具栏和留白）
        val windowRect = Rect()
        rv.getWindowVisibleDisplayFrame(windowRect)
        val keyboardTopInwindow = windowRect.bottom - toolbarHeight - keyboardMargin

        // 光标没有被遮挡，无需滚动
        if (cursorBottomInWindow <= keyboardTopInwindow) return

        // 计算光标需要的滚动距离
        val neededScrollY = cursorBottomInWindow - keyboardTopInwindow

        // 记录EditText当前的已滚动距离
        val oldScrollY = edit.scrollY

        // 先尝试滚动EditText
        edit.scrollBy(0, neededScrollY)

        // EditText实际的滚动距离
        val actualScrollY = edit.scrollY - oldScrollY

        edit.post {
            // 计算光标剩下的滚动距离
            val remainingScrollY = neededScrollY - actualScrollY

            // 滚动完EditText后还被遮挡，再滚动RecyclerView
            if (remainingScrollY > 0) {
                // 计算RecyclerView可滚动的最大距离，避免滚动过度
                val maxCanScrollY = rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent() - rv.scrollY

                // RecyclerView实际可以滚动的距离
                val actualCanScrollY = min(remainingScrollY, maxCanScrollY)

                rv.stopScroll()
                rv.scrollBy(0, actualCanScrollY)
            }
        }
    }

    /**
     * 计算窗口可视区域（RecyclerView 坐标系）
     */
    private fun computeVisibleRect(parent: RecyclerView): Rect {
        val rvLoc = IntArray(2)
        parent.getLocationOnScreen(rvLoc)

        val windowRect = Rect()
        parent.getWindowVisibleDisplayFrame(windowRect)

        windowRect.offset(-rvLoc[0], -rvLoc[1])
        windowRect.offset(-parent.scrollX, -parent.scrollY)

        return windowRect - (toolbarHeight + keyboardMargin)
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

        /*
        val parentRect = Rect(
            parent.paddingLeft,
            parent.paddingTop,
            parent.width - parent.paddingRight,
            parent.height - parent.paddingBottom
        ).apply{ offset(-parent.scrollX, -parent.scrollY) }
        */
        val visibleRect = computeVisibleRect(parent)

        return visibleRect.contains(childRect) || Rect.intersects(visibleRect, childRect)
    }

    /**
     * 判断子项的指定矩形区域是否可见
     * @param parent RecyclerView父容器
     * @param child 要判断的子项View
     * @param rect 要判断的矩形区域（相对于子项的坐标）
     * @return true表示矩形区域可见，false表示不可见
     */
    private fun isChildVisible(parent: RecyclerView, child: View, rect: Rect): Boolean {
        /*
        val childRect = Rect()
        child.getDrawingRect(childRect)
        parent.offsetDescendantRectToMyCoords(child, childRect)

        val targetRect = Rect(rect).apply{ offset(childRect.left, childRect.top) }
        */

        val targetRect = Rect(rect)
        parent.offsetDescendantRectToMyCoords(child, targetRect)

        /*
        val parentRect = Rect(
            parent.paddingLeft,
            parent.paddingTop,
            parent.width - parent.paddingRight,
            parent.height - parent.paddingBottom
        ).apply { offset(-parent.scrollX, -parent.scrollY) }
        */
        val visibleRect = computeVisibleRect(parent)

        return visibleRect.contains(targetRect) || Rect.intersects(visibleRect, targetRect)
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
        if (focusedChildVisible) {
            allowFocusScroll = false
            parent?.postDelayed({ allowFocusScroll = true }, 200)
        }
        /**
         * 拦截初次点击产生焦点时触发的自动滚动
         * 后续移动光标、键盘操作等场景不会拦截，因为 focusedChildVisible = false
         * 如果子View已经在可见区域内，无需滚动
         * 否则调用父方法进行滚动
         */
        return when {
            !allowFocusScroll -> false
            isChildVisible(parent, child, rect) -> false
            else -> super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
        }
    }
}