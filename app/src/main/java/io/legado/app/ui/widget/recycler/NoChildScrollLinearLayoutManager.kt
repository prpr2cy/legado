package io.legado.app.ui.widget.recycler

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.utils.imeHeight
import androidx.core.view.WindowInsetsCompat

/**
 * 禁止子项自动滚动的 LinearLayoutManager
 * 主要用于解决 RecyclerView 中 EditText 获取焦点时自动滚动的问题
 * allowFocusScroll = false 关闭原生滚动，光标被遮挡时手动处理
 */
class NoChildScrollLinearLayoutManager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayoutManager(context, attrs, defStyleAttr, defStyleRes) {

    /** 是否允许因焦点变化产生的自动滚动 */
    var allowFocusScroll: Boolean = true

    private val mContext = context

    /** 屏幕高度（实时获取，兼容折叠屏） */
    private val screenHeight: Int
        get() = mContext.resources.displayMetrics.heightPixels

    companion object {
        /** 光标额外留白（dp） */
        private const val EXTRA_SLACK_DP = 4f
    }

    private val extraSlackPx by lazy {
        (EXTRA_SLACK_DP * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    /** 标记：下次 layout 时是否需要把光标滚出来 */
    private var pendingScrollToCursor = false 

    /** 键盘高度（由外部通过 WindowInsets 赋值） */
    var keyboardHeight: Int = 0 
        set(value) {
            if (field == value) return 
            field = value 
            pendingScrollToCursor = true 
            requestLayout()
        }

    /* 在真正布局阶段处理光标滚动 */
    private var hostRecyclerView: RecyclerView? = null 

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        hostRecyclerView = view 
    }
 
    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        hostRecyclerView = null 
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        super.onLayoutChildren(recycler, state)
        if (!pendingScrollToCursor || keyboardHeight <= 0) return 
        pendingScrollToCursor = false 
 
        val rv = hostRecyclerView ?: return 
        val focus = rv.findFocus() as? EditText ?: return 
        val cursorY = getCursorScreenY(focus)
        if (cursorY == -1 || !isCursorObscuredByKeyboard(cursorY)) return 
 
        val dy = calculateRemainScroll(cursorY)
        if (dy > 0) {
            // 使用 LayoutManager 自己的滚动接口，避开 adjustResize 冲突 
            scrollVerticallyBy(dy, recycler, state)
        }
    }

    /** 判断光标是否被键盘遮挡 */
    private fun isCursorObscuredByKeyboard(cursorScreenY: Int): Boolean =
        keyboardHeight > 0 && cursorScreenY > screenHeight - keyboardHeight

    /** 获取 EditText 光标在屏幕上的 Y 坐标（包含 descent 留白） */
    private fun getCursorScreenY(editText: EditText): Int {
        if (!ViewCompat.isAttachedToWindow(editText)) return -1
        val layout = editText.layout ?: return -1
        val selection = editText.selectionStart.takeIf { it >= 0 } ?: return -1
        val line = layout.getLineForOffset(selection)
        val baseline = layout.getLineBaseline(line)
        val descent = layout.getLineDescent(line)
        val cursorHeight = descent + extraSlackPx
        val cursorY = baseline + cursorHeight - editText.scrollY
        val loc = IntArray(2)
        editText.getLocationOnScreen(loc)
        return loc[1] + cursorY
    }

    /** 计算“尚未滚动的距离”——正值表示需要再上滚多少像素 */
    private fun calculateRemainScroll(cursorScreenY: Int): Int {
        if (keyboardHeight <= 0) return 0
        val visibleBottom = screenHeight - keyboardHeight
        return (cursorScreenY - visibleBottom).coerceAtLeast(0)
    }

    /** 滚动光标到可见区；返回 true 表示确实滚动了 */
    private fun scrollToCursorVisible(parent: RecyclerView, child: View): Boolean {
        // OPT：真正持焦点的可能是任意后代
        val focus = child.findFocus() as? EditText ?: return false
        val cursorY = getCursorScreenY(focus)
        if (cursorY == -1 || !isCursorObscuredByKeyboard(cursorY)) return false
        val dy = calculateRemainScroll(cursorY)
        if (dy > 0) parent.scrollBy(0, dy)
        return dy > 0
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
        /** 拦截初次焦点触发的自动滚动，手动处理光标遮挡
         * 其它光标移动、键盘操作等场景不拦截，focusedChildVisible = false
         */
        if (!allowFocusScroll && focusedChildVisible) {
            getCursorScreenY(child)
            return false
        }

        /** 如果子View已经在可见区域内
         * 也尝试手动滚一下光标（光标可能在矩形内但仍被键盘挡）
         */
        if (isChildVisible(parent, child, rect)) {
            getCursorScreenY(child)
            return false
        }

        /** 否则调用父类方法进行滚动 */
        return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
    }
}