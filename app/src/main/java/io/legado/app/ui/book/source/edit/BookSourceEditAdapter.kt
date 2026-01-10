package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemSourceEditBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.text.EditEntity

class BookSourceEditAdapter(
    private val focusStateManager: FocusStateManager,
    private val scrollStateManager: ScrollStateManager,
    private val recyclerView: RecyclerView
) : RecyclerView.Adapter<BookSourceEditAdapter.MyViewHolder>() {

    val editEntityMaxLine = AppConfig.sourceEditMaxLine

    var editEntities: ArrayList<EditEntity> = ArrayList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val handler = Handler(Looper.getMainLooper())

    // 使用对象引用作为标签
    private object TouchListenerTag
    private object FocusListenerTag
    private object TextWatcherTag

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val binding = ItemSourceEditBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.editText.addLegadoPattern()
        binding.editText.addJsonPattern()
        binding.editText.addJsPattern()
        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.bind(editEntities[position], position)
    }

    override fun getItemCount(): Int = editEntities.size

    inner class MyViewHolder(private val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var currentKey: String? = null
        private var currentPosition: Int = -1

        fun bind(editEntity: EditEntity, position: Int) = binding.run {
            currentKey = editEntity.key
            currentPosition = position

            // 使用资源ID设置标签
            editText.setTag(R.id.edit_entity_key, editEntity.key)
            editText.maxLines = editEntityMaxLine

            // 始终可聚焦
            editText.isFocusable = true
            editText.isFocusableInTouchMode = true
            editText.clearFocus()
            editText.isCursorVisible = false

            // 设置触摸监听（防滑动误触的核心）
            if (editText.getTag(R.id.tag_touch_listener) == null) {
                editText.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        // 关键：如果正在滑动，停止滑动并消费事件
                        if (scrollStateManager.isScrolling()) {
                            // 立即停止滑动
                            scrollStateManager.stopScrollImmediately()
                            return@setOnTouchListener true // 消费事件，阻止焦点
                        } else {
                            // 页面稳定时才标记为用户点击
                            currentKey?.let { key ->
                                focusStateManager.setUserTouched(key)
                                focusStateManager.setCurrentPosition(position)
                            }
                        }
                    }
                    false // 不消费，让 EditText 正常处理后续事件
                }
                editText.setTag(R.id.tag_touch_listener, TouchListenerTag)
            }

            // 焦点变化监听
            if (editText.getTag(R.id.tag_focus_listener) == null) {
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        onGainFocus()
                    } else {
                        onLoseFocus()
                    }
                }
                editText.setTag(R.id.tag_focus_listener, FocusListenerTag)
            }

            // 文本和初始状态设置
            setupTextAndState(editEntity)
            textInputLayout.hint = editEntity.hint

            // 恢复焦点状态 - 只在用户主动点击过的情况下恢复焦点
            currentKey?.let { key ->
                if (focusStateManager.isUserTouched(key)) {
                    handler.post {
                        // 确保不会意外触发多次焦点请求
                        if (!editText.hasFocus()) {
                            editText.requestFocus()
                        }
                    }
                }
            }
        }

        private fun onGainFocus() {
            binding.editText.isCursorVisible = true

            currentKey?.let { key ->
                if (focusStateManager.isUserTouched(key)) {
                    // 用户点击：使用方案三的流畅体验
                    handler.post {
                        // 获取保存的位置
                        val savedPosition = focusStateManager.getCurrentPosition()
                        // 同时进行滚动和焦点请求
                        recyclerView.smoothScrollToPosition(savedPosition)
                        binding.editText.requestFocus()

                        // 短暂延迟后确保键盘弹出
                        handler.postDelayed({
                            showSoftInput(binding.editText)
                        }, 50)
                    }
                } else {
                    // 程序恢复：只恢复光标位置，不弹键盘
                    val (start, end) = focusStateManager.getLastSelection(key)
                    val safeStart = start.coerceAtMost(binding.editText.text.length)
                    val safeEnd = end.coerceAtMost(binding.editText.text.length)
                    binding.editText.setSelection(safeStart, safeEnd)

                    // 确保不会意外弹出键盘
                    hideSoftInputIfNeeded()
                }
            }
        }

        private fun onLoseFocus() {
            binding.editText.isCursorVisible = false
            currentKey?.let { key ->
                focusStateManager.saveSelection(
                    key,
                    binding.editText.selectionStart,
                    binding.editText.selectionEnd
                )
            }
            // 不清除 userTouched 状态，保持状态直到下次绑定
        }

        private fun setupTextAndState(editEntity: EditEntity) {
            // 移除旧的 TextWatcher
            binding.editText.getTag(R.id.tag_text_watcher)?.let {
                if (it is TextWatcher) {
                    binding.editText.removeTextChangedListener(it)
                }
            }

            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    // 使用资源ID进行比较
                    if (currentKey == binding.editText.getTag(R.id.edit_entity_key)) {
                        editEntity.value = s?.toString()
                    }
                }
            }

            binding.editText.addTextChangedListener(textWatcher)
            binding.editText.setTag(R.id.tag_text_watcher, textWatcher)

            // 设置文本
            val currentText = binding.editText.text?.toString().orEmpty()
            val newText = editEntity.value.orEmpty()
            if (currentText != newText) {
                binding.editText.setText(newText)
            }
        }

        private fun showSoftInput(editText: android.widget.EditText) {
            handler.post {
                try {
                    val imm = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        private fun hideSoftInputIfNeeded() {
            handler.post {
                try {
                    val imm = binding.editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    if (imm?.isActive(binding.editText) == true) {
                        imm.hideSoftInputFromWindow(binding.editText.windowToken, 0)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}