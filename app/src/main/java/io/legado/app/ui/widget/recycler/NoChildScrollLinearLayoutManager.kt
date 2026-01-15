package io.legado.app.ui.widget.recycler

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.constant.AppLog

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
        AppLog.put("键盘高度=$keyboardHeight")
        if (!pendingScrollToCursor || keyboardHeight <= 0) return 
        pendingScrollToCursor = false 
 
        val rv = hostRecyclerView ?: return 
        val edit = rv.findFocus() as? EditText ?: return
        AppLog.put("编辑框post")
        edit.post {
            val rect = Rect()
            edit.getFocusedRect(rect)
            edit.requestRectangleOnScreen(rect, false)
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
        /** 拦截初次焦点触发的自动滚动，手动处理光标遮挡
         * 其它光标移动、键盘操作等场景不拦截，focusedChildVisible = false
         */
        if (!allowFocusScroll && focusedChildVisible) {
            return false
        }

        /** 如果子View已经在可见区域内
         * 也尝试手动滚一下光标（光标可能在矩形内但仍被键盘挡）
         */
        if (isChildVisible(parent, child, rect)) {
            AppLog.put("没自动滚动")
            return false
        }
        AppLog.put("自动滚动")

        /** 否则调用父类方法进行滚动 */
        return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
    }
}