package io.legado.app.ui.widget.recycler

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class NoChildScrollLinearLayoutManager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayoutManager(context, attrs, defStyleAttr, defStyleRes) {

    /**
     * 是否允许自动滚动
     */
    var allowAutoScroll: Boolean = true

    /**
     * 检查子View是否在可见区域内
     */
    private fun isChildVisible(parent: RecyclerView, child: View): Boolean {
        val childRect = Rect()
        child.getDrawingRect(childRect)
        parent.offsetDescendantRectToMyCoords(child, childRect)

        val paddingLeft = parent.paddingLeft
        val paddingTop = parent.paddingTop
        val paddingRight = parent.paddingRight
        val paddingBottom = parent.paddingBottom

        val parentRect = Rect(
            paddingLeft,
            paddingTop,
            parent.width - paddingRight,
            parent.height - paddingBottom
        )
        parentRect.offset(-parent.scrollX, -parent.scrollY)

        return parentRect.contains(childRect) || Rect.intersects(parentRect, childRect)
    }

    /**
     * 检查子View的指定矩形区域是否在可见区域内
     */
    private fun isChildVisible(parent: RecyclerView, child: View, rect: Rect): Boolean {
        val childRect = Rect()
        child.getDrawingRect(childRect)
        parent.offsetDescendantRectToMyCoords(child, childRect)

        val targetRect = Rect(rect)
        targetRect.offset(childRect.left, childRect.top)

        val globalRect = Rect()
        parent.getGlobalVisibleRect(globalRect)

        val paddingLeft = parent.paddingLeft
        val paddingTop = parent.paddingTop
        val paddingRight = parent.paddingRight
        val paddingBottom = parent.paddingBottom

        val parentRect = Rect(
            paddingLeft,
            paddingTop,
            parent.width - paddingRight,
            parent.height - paddingBottom
        )
        parentRect.offset(-parent.scrollX, -parent.scrollY)

        return parentRect.contains(targetRect) || Rect.intersects(parentRect, targetRect)
    }

    /**
     * 重写方法，控制滚动行为
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
     * 重写方法，控制滚动行为
     */
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        // 如果不允许自动滚动，直接返回 false
        if (!allowAutoScroll) {
            return false
        }

        // 如果子View已经在可见区域内，不需要滚动
        if (isChildVisible(parent, child, rect)) {
            return false
        }

        // 否则调用父类方法进行滚动
        return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
    }
}