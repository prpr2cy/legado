package io.legado.app.ui.book.source.edit

import androidx.recyclerview.widget.RecyclerView

class FocusStateManager {
    private var userTouchedKey: String? = null
    private val selectionMap = mutableMapOf<String, Pair<Int, Int>>()
    private var currentPosition: Int = -1

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

    fun setCurrentPosition(position: Int) {
        currentPosition = position
    }

    fun getCurrentPosition(): Int = currentPosition

    // 新增：清除所有状态
    fun clearAll() {
        userTouchedKey = null
        selectionMap.clear()
        currentPosition = -1
    }
}

class ScrollStateManager {
    private var isScrolling = false
    private val scrollListeners = mutableListOf<(Boolean) -> Unit>()
    private var recyclerView: RecyclerView? = null
    private var lastScrollTime: Long = 0

    fun attachRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    fun setScrolling(scrolling: Boolean) {
        if (isScrolling != scrolling) {
            isScrolling = scrolling
            if (scrolling) {
                lastScrollTime = System.currentTimeMillis()
            }
            scrollListeners.forEach { it(scrolling) }
        }
    }

    fun isScrolling(): Boolean = isScrolling

    // 新增：检查是否刚刚停止滚动
    fun isJustStoppedScrolling(): Boolean {
        return !isScrolling && System.currentTimeMillis() - lastScrollTime < 200
    }

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