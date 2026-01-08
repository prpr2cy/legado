package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.DialogPageChoiceBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch

class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>(),
    ExploreShowAdapter.CallBack,
    GroupSelectDialog.CallBack {

    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private val adapter by lazy { ExploreShowAdapter(this, this) }
    private val loadMoreView by lazy { LoadMoreView(this) }
    private val loadMoreViewTop by lazy { LoadMoreView(this) }
    private val waitDialog by lazy { WaitDialog(this) }

    private var oldPage = -1
    private var isClearAll = false

    companion object {
        private const val MENU_PAGE_ID = 10001
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        initRecyclerView()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(this) {
            loadMoreViewTop.error(it)
        }
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf(it to null))
        }
        viewModel.pageLiveData.observe(this) {
            updatePageMenuTitle(it)
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter

        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.startLoad()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                } else if (!recyclerView.canScrollVertically(-1) && dy < 0) {
                    scrollToTop()
                }
            }
        })
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((loadMoreView.hasMore && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreView.hasMore()
            viewModel.explore()
        }
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if (oldPage <= 1) return

        if ((oldPage > 1 && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreViewTop.hasMore()
            oldPage--
            viewModel.explore(oldPage)
        }
    }

    private fun upData(books: List<SearchBook>) {
        loadMoreView.stopLoad()
        if (books.isEmpty() && adapter.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (books.isEmpty() || adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            if (isClearAll) {
                adapter.setItems(books)
                val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
                layoutManager.scrollToPositionWithOffset(1, 0)
                isClearAll = false
            } else {
                adapter.addItems(books)
            }
        }
    }

    private fun upDataTop(books: List<SearchBook>) {
        loadMoreViewTop.stopLoad()
        adapter.addItems(0, books)

        val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
        if (layoutManager.findFirstVisibleItemPosition() <= 1) {
            layoutManager.scrollToPositionWithOffset(books.size, 0)
        }

        if (oldPage <= 1) {
            val layoutParams = loadMoreViewTop.layoutParams
            if (layoutParams != null) {
                layoutParams.height = 0
                loadMoreViewTop.layoutParams = layoutParams
            }
        }
    }

    override fun isInBookshelf(name: String, author: String): Boolean {
        return if (author.isNotBlank()) {
            viewModel.bookshelf.contains("$name-$author")
        } else {
            viewModel.bookshelf.any { it.startsWith("$name-") }
        }
    }

    override fun showBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        val pageMenuItem = menu.add(Menu.NONE, MENU_PAGE_ID, 0, getString(R.string.menu_page, 1))
        pageMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menuInflater.inflate(R.menu.explore_show, menu)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_PAGE_ID -> {
                val page = viewModel.pageLiveData.value ?: 1
                NumberPickerDialog(this@ExploreShowActivity)
                    .setTitle(getString(R.string.change_page))
                    .setMaxValue(999)
                    .setMinValue(1)
                    .setValue(page)
                    .show { selectedPage ->
                        if (page != selectedPage) {
                            handlePageJump(selectedPage)
                        }
                    }
            }
            R.id.menu_add_all_to_bookshelf -> {
                addAllToBookshelf()
            }
            else -> {}
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun handlePageJump(selectedPage: Int) {
        if (oldPage == -1 && selectedPage != 1) {
            adapter.addHeaderView { ViewLoadMoreBinding.bind(loadMoreViewTop) }
        } else if (selectedPage != 1) {
            val layoutParams = loadMoreViewTop.layoutParams
            if (layoutParams?.height == 0) {
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                loadMoreViewTop.layoutParams = layoutParams
            }
        }
        oldPage = selectedPage
        viewModel.skipPage(selectedPage)
        isClearAll = true
        adapter.clearItems()
        if (!loadMoreView.hasMore) {
            scrollToBottom(true)
        }
    }

    private fun updatePageMenuTitle(currentPage: Int) {
        runOnUiThread {
            val menu = binding.titleBar.menu
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item.itemId == MENU_PAGE_ID) {
                    item.title = getString(R.string.menu_page, currentPage)
                    break
                }
            }
        }
    }

    private fun addAllToBookshelf() {
        showDialogFragment(GroupSelectDialog(0))
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        alert("选择页数范围") {
            val alertBinding = DialogPageChoiceBinding.inflate(layoutInflater).apply {
                root.setBackgroundColor(root.context.backgroundColor)
            }
            customView { alertBinding.root }
            yesButton {
                alertBinding.run {
                    val start = editStart.text.toString().toIntOrNull() ?: 1
                    val end = editEnd.text.toString().toIntOrNull() ?: 9
                    addAllToBookshelf(start, end, groupId)
                }
            }
            noButton()
        }
    }

    private fun addAllToBookshelf(start: Int, end: Int, groupId: Long) {
        val job = Coroutine.async {
            launch(Main) {
                waitDialog.setText("加载列表中...")
                waitDialog.show()
            }
            val searchBooks = viewModel.loadExploreBooks(start, end)
            val books = searchBooks.map {
                it.toBook()
            }
            launch(Main) {
                waitDialog.setText("添加书架中...")
            }
            books.forEach {
                appDb.bookDao.getBook(it.bookUrl)?.let { book ->
                    book.group = book.group or groupId
                    it.order = appDb.bookDao.minOrder - 1
                    book.save()
                    return@forEach
                }
                if (it.tocUrl.isEmpty()) {
                    val source = appDb.bookSourceDao.getBookSource(it.origin)!!
                    WebBook.getBookInfoAwait(source, it)
                }
                it.order = appDb.bookDao.minOrder - 1
                it.group = groupId
                it.save()
            }
        }.onError {
            AppLog.put("添加书架出错\n${it.localizedMessage}", it)
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            job.cancel()
        }
    }
}