package io.legado.app.ui.book.source.edit

import androidx.recyclerview.widget.RecyclerView

class FocusStateManager {
    // 只记录用户主动点击的 key，其他情况不记录
    private var userTouchedKey: String? = null
    private val selectionMap = mutableMapOf<String, Pair<Int, Int>>()

    fun setUserTouched(key: String) {
        userTouchedKey = key
    }

    fun isUserTouched(key: String): Boolean = userTouchedKey == key

    fun saveSelection(key: String, start: Int, end: Int) {
        selectionMap[key] = Pair(start, end)
    }

    fun getLastSelection(key: String): Pair<Int, Int> = selectionMap[key] ?: Pair(0, 0)

    fun clearUserTouched() {
        userTouchedKey = null
    }
}

class ScrollStateManager {
    private var isScrolling = false
    private val scrollListeners = mutableListOf<(Boolean) -> Unit>()
    private var recyclerView: RecyclerView? = null

    fun attachRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    fun setScrolling(scrolling: Boolean) {
        if (isScrolling != scrolling) {
            isScrolling = scrolling
            scrollListeners.forEach { it(scrolling) }
        }
    }

    fun isScrolling(): Boolean = isScrolling

    // 立即停止滑动的方法
    fun stopScrollImmediately() {
        recyclerView?.stopScroll()
        setScrolling(false)
    }

    fun addScrollListener(listener: (Boolean) -> Unit) {
        scrollListeners.add(listener)
    }

    fun removeScrollListener(listener: (Boolean) -> Unit) {
        scrollListeners.remove(listener)
    }
}