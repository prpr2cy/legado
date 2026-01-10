package io.legado.app.ui.book.source.edit

import android.app.Application
import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.RuleComplete
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.storage.ImportOldData
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers


class BookSourceEditViewModel(application: Application) : BaseViewModel(application) {
    var autoComplete = false
    var bookSource: BookSource? = null

    // 添加焦点和滑动状态管理器
    val focusStateManager = FocusStateManager()
    val scrollStateManager = ScrollStateManager()

    fun initData(intent: Intent, onFinally: () -> Unit) {
        execute {
            val sourceUrl = intent.getStringExtra("sourceUrl")
            var source: BookSource? = null
            if (sourceUrl != null) {
                source = appDb.bookSourceDao.getBookSource(sourceUrl)
            }
            source?.let {
                bookSource = it
            }
        }.onFinally {
            onFinally()
        }
    }

    fun save(source: BookSource, success: ((BookSource) -> Unit)? = null) {
        execute {
            if (source.bookSourceUrl.isBlank() || source.bookSourceName.isBlank()) {
                throw NoStackTraceException(context.getString(R.string.non_null_name_url))
            }
            if (source.equal(bookSource ?: BookSource())) {
                return@execute source
            }
            bookSource?.let {
                appDb.bookSourceDao.delete(it)
                SourceConfig.removeSource(it.bookSourceUrl)
            }
            source.lastUpdateTime = System.currentTimeMillis()
            appDb.bookSourceDao.insert(source)
            bookSource = source
            source
        }.onSuccess {
            success?.invoke(it)
        }.onError {
            context.toastOnUi(it.localizedMessage)
            it.printOnDebug()
        }
    }

    fun pasteSource(onSuccess: (source: BookSource) -> Unit) {
        execute(context = Dispatchers.Main) {
            val text = context.getClipText()
            if (text.isNullOrBlank()) {
                throw NoStackTraceException("剪贴板为空")
            } else {
                importSource(text, onSuccess)
            }
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "Error")
            it.printOnDebug()
        }
    }

    fun importSource(text: String, finally: (source: BookSource) -> Unit) {
        execute {
            importSource(text)
        }.onSuccess {
            finally.invoke(it)
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "Error")
            it.printOnDebug()
        }
    }

    suspend fun importSource(text: String): BookSource {
        return when {
            text.isAbsUrl() -> {
                val text1 = okHttpClient.newCallStrResponse { url(text) }.body
                importSource(text1!!)
            }

            text.isJsonArray() -> {
                if (text.contains("ruleSearchUrl") || text.contains("ruleFindUrl")) {
                    val items: List<Map<String, Any>> = jsonPath.parse(text).read("$")
                    val jsonItem = jsonPath.parse(items[0])
                    ImportOldData.fromOldBookSource(jsonItem)
                } else {
                    GSON.fromJsonArray<BookSource>(text).getOrThrow()[0]
                }
            }

            text.isJsonObject() -> {
                if (text.contains("ruleSearchUrl") || text.contains("ruleFindUrl")) {
                    val jsonItem = jsonPath.parse(text)
                    ImportOldData.fromOldBookSource(jsonItem)
                } else {
                    GSON.fromJsonObject<BookSource>(text).getOrThrow()
                }
            }

            else -> throw NoStackTraceException("格式不对")
        }
    }

    fun clearCookie(url: String) {
        execute {
            CookieStore.removeCookie(url)
        }
    }

    fun ruleComplete(rule: String?, preRule: String? = null, type: Int = 1): String? {
        if (autoComplete) {
            return RuleComplete.autoComplete(rule, preRule, type)
        }
        return rule
    }
}

// 在同一个文件中定义状态管理器
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