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
import kotlin.math.max
import kotlin.math.min

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
    var allowFocusScroll: Boolean = true

    private var recyclerView: RecyclerView? = null
    private val mContext: Context = context
    // 键盘高度
    private var keyboardHeight: Int = 0
    // 键盘上方留白
    private val keyboardMargin = 8.dp

    private val Int.dp: Int
        get() = (this * mContext.resources.displayMetrics.density + 0.5f).toInt()

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val rv = recyclerView ?: return@OnGlobalLayoutListener
        val rect = Rect()
        rv.getWindowVisibleDisplayFrame(rect)

        // 计算键盘高度
        val screenHeight = rv.rootView.height
        val visibleHeight = rect.bottom - rect.top
        val newKeyboardHeight = screenHeight - visibleHeight

        // 只有当键盘弹出时才处理
        if (newKeyboardHeight > 100 && newKeyboardHeight != keyboardHeight) {
            keyboardHeight = newKeyboardHeight
            scrollCursorToVisible()
        } else if (newKeyboardHeight < 100 && keyboardHeight > 0) {
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
        recyclerView = null
        view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    /**
     * 禁止自动滚动时，光标被键盘遮挡，需手动滚到可视区
     * 先滚动EditText内部，让光标尽可能在EditText可视区域内
     * 如果内部滚动后光标仍然被键盘遮挡，再滚动RecyclerView
     */
    private fun scrollCursorToVisible() {
        val rv = recyclerView ?: return
        val edit = rv.findFocus() as? EditText ?: return
        val layout = edit.layout ?: return
        val selection = edit.selectionStart.takeIf { it >= 0 } ?: return

        // 获取窗口可视区域
        val windowRect = Rect()
        rv.getWindowVisibleDisplayFrame(windowRect)

        // 计算键盘顶部在窗口的位置（考虑工具栏和留白）
        val keyboardTop = windowRect.bottom - KeyboardToolPop.toolbarHeight - keyboardMargin
        if (keyboardTop <= 0) return

        // 计算光标底部在EditText中的位置
        val line = layout.getLineForOffset(selection)
        val lineBottom = layout.getLineBottom(line)

        // 计算EditText顶部在窗口中的位置
        val editLoc = IntArray(2)
        edit.getLocationInWindow(editLoc)
        val editTopInWindow = editLoc[1]

        // 计算光标底部在窗口中的位置（考虑EditText的滚动偏移）
        val cursorBottomInWindow = editTopInWindow + lineBottom - edit.scrollY

        // 光标没有被遮挡，无需滚动
        if (cursorBottomInWindow <= keyboardTop) return

        // 计算EditText还能向下滚动的距离
        val originalScrollY = edit.scrollY
        edit.scrollTo(0, Int.MAX_VALUE)
        val maxScrollY = edit.scrollY
        edit.scrollTo(0, originalScrollY)
        val remainingScrollY = maxScrollY - edit.scrollY

        // 计算EditText需要滚动的距离
        val neededScrollInside = cursorBottomInWindow - keyboardTop
        val scrollY = min(neededScrollInside, remainingScrollY)

        // 还有内部滚动空间，先滚动EditText
        if (scrollY > 0) {
            edit.scrollBy(0, scrollY)
        }

        // 滚动完EditText后，再看是否需要滚动RecyclerView
        if (neededScrollInside > remainingScrollY) {
            // 计算RecyclerView需要滚动的距离
            val neededScrollRv = neededScrollInside - remainingScrollY
            rv.stopScroll()
            rv.scrollBy(0, neededScrollRv)
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