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

class BookSourceEditAdapter : RecyclerView.Adapter<BookSourceEditAdapter.MyViewHolder>() {

    val editEntityMaxLine = AppConfig.sourceEditMaxLine

    var editEntities: ArrayList<EditEntity> = ArrayList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // 全局焦点管理器
    private val focusManager = FocusManager()

    // 关联RecyclerView来处理滑动状态
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
        focusManager.clearAllFocus()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val binding = ItemSourceEditBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)
        binding.editText.addLegadoPattern()
        binding.editText.addJsonPattern()
        binding.editText.addJsPattern()
        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.bind(editEntities[position])
    }

    override fun getItemCount(): Int {
        return editEntities.size
    }

    inner class MyViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val mainHandler = Handler(Looper.getMainLooper())
        private var currentKey: String? = null
        private var isHandlingTouch = false
        private var lastScrollState = RecyclerView.SCROLL_STATE_IDLE

        fun bind(editEntity: EditEntity) = binding.run {
            currentKey = editEntity.key

            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            // 重置状态
            editText.clearFocus()
            editText.isCursorVisible = false
            isHandlingTouch = false

            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        editText.isFocusable = true
                        editText.isFocusableInTouchMode = true

                        // 如果这是焦点项，只恢复焦点状态，不弹出键盘
                        if (focusManager.isCurrentFocus(currentKey)) {
                            mainHandler.post {
                                if (focusManager.isCurrentFocus(currentKey) && !editText.hasFocus()) {
                                    editText.requestFocus()
                                    editText.isCursorVisible = true
                                    // 不自动弹出键盘，等用户点击时再弹出
                                }
                            }
                        }
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        // 清理焦点状态
                        if (focusManager.isCurrentFocus(currentKey)) {
                            focusManager.clearPendingTouch(currentKey)
                        }
                        editText.clearFocus()
                        isHandlingTouch = false
                    }
                }
                editText.addOnAttachStateChangeListener(listener)
                editText.setTag(R.id.tag1, listener)

                // 监听RecyclerView滚动状态
                recyclerView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        lastScrollState = newState
                        // 如果从滚动变为空闲，检查是否有待处理的焦点
                        if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                            focusManager.hasPendingFocusWithoutTouch()) {
                            focusManager.clearAllFocus()
                        }
                    }
                })

                // 触摸监听 - 只在页面稳定时处理点击
                editText.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            // 检查是否在滑动中
                            val isScrolling = lastScrollState != RecyclerView.SCROLL_STATE_IDLE

                            if (isScrolling) {
                                // 如果在滑动中，渐进停止滑动，但不设置焦点和光标
                                recyclerView?.stopScroll()
                                // 不处理焦点，让用户等页面稳定后再点击
                                return@setOnTouchListener true
                            } else {
                                // 页面稳定，正常处理点击
                                handleTouchEvent(event.x, event.y)
                                return@setOnTouchListener true // 消费事件
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            // 标记触摸处理完成
                            isHandlingTouch = false
                        }
                    }
                    false
                }

                // 焦点监听
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        // 验证焦点合法性
                        if (focusManager.isPendingFocus(currentKey) || focusManager.isCurrentFocus(currentKey)) {
                            if (focusManager.isPendingFocus(currentKey)) {
                                focusManager.confirmFocus(currentKey)
                            }
                            mainHandler.post {
                                if (editText.hasFocus() && focusManager.isCurrentFocus(currentKey)) {
                                    editText.isCursorVisible = true
                                    // 延迟设置光标位置，确保视图稳定
                                    mainHandler.postDelayed({
                                        if (editText.hasFocus() && focusManager.isCurrentFocus(currentKey)) {
                                            focusManager.applyPendingCursorPosition(editText, currentKey)
                                        }
                                    }, 50)
                                    // 只在用户主动点击时弹出键盘
                                    if (focusManager.isUserInitiatedFocus(currentKey)) {
                                        showSoftInput(editText)
                                    }
                                }
                            }
                        } else {
                            // 非法焦点，清除
                            mainHandler.post {
                                editText.clearFocus()
                            }
                        }
                    } else {
                        editText.isCursorVisible = false
                        // 失去焦点时清理待定状态
                        focusManager.clearPendingTouch(currentKey)
                    }
                }
            }

            // 移除旧监听器
            editText.getTag(R.id.tag2)?.let {
                if (it is TextWatcher) {
                    editText.removeTextChangedListener(it)
                }
            }

            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (currentKey == editText.getTag(R.id.tag)) {
                        editEntity.value = s?.toString()
                    }
                }
            }

            // 智能文本更新
            val currentText = editText.text?.toString().orEmpty()
            val newText = editEntity.value.orEmpty()

            if (currentText != newText) {
                // 保存当前选择状态
                val hadFocus = editText.hasFocus() && focusManager.isCurrentFocus(currentKey)
                val selStart = editText.selectionStart
                val selEnd = editText.selectionEnd

                editText.setText(newText)

                // 如果之前有焦点且选择有效，恢复选择
                if (hadFocus && selStart >= 0 && selEnd >= 0 &&
                    selStart <= newText.length && selEnd <= newText.length
                ) {
                    mainHandler.post {
                        if (editText.hasFocus() && focusManager.isCurrentFocus(currentKey)) {
                            editText.setSelection(selStart, selEnd)
                        }
                    }
                }
            }

            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)
            textInputLayout.hint = editEntity.hint

            // 如果是当前焦点项，只恢复焦点状态，不弹出键盘
            if (focusManager.isCurrentFocus(currentKey)) {
                mainHandler.post {
                    if (focusManager.isCurrentFocus(currentKey) && !editText.hasFocus()) {
                        editText.requestFocus()
                        editText.isCursorVisible = true
                        // 不自动弹出键盘
                    }
                }
            }
        }

        /**
         * 处理触摸事件
         */
        private fun handleTouchEvent(x: Float, y: Float) {
            if (isHandlingTouch) return // 防止重复处理

            isHandlingTouch = true
            focusManager.setPendingFocus(currentKey, x, y, true) // 标记为用户发起的焦点
            binding.editText.requestFocus()
        }

        /**
         * 显示软键盘
         */
        private fun showSoftInput(editText: android.widget.EditText) {
            mainHandler.post {
                val inputMethodManager = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                inputMethodManager?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /**
     * 焦点管理器 - 基于key的焦点管理
     */
    private class FocusManager {
        // 当前获得焦点的key
        private var currentFocusKey: String? = null

        // 待定焦点信息，key -> 触摸坐标和用户发起标志
        private val pendingFocusMap = mutableMapOf<String, TouchInfo>()

        private data class TouchInfo(
            val touchX: Float,
            val touchY: Float,
            val isUserInitiated: Boolean = false // 标记是否是用户主动点击发起的焦点
        )

        fun setPendingFocus(key: String?, x: Float, y: Float, isUserInitiated: Boolean = false) {
            if (key != null) {
                pendingFocusMap[key] = TouchInfo(x, y, isUserInitiated)
            }
        }

        fun confirmFocus(key: String?) {
            if (key != null && pendingFocusMap.containsKey(key)) {
                currentFocusKey = key
            }
        }

        fun clearPendingTouch(key: String?) {
            if (key != null) {
                pendingFocusMap.remove(key)
            }
        }

        fun clearAllFocus() {
            currentFocusKey = null
            pendingFocusMap.clear()
        }

        fun isPendingFocus(key: String?): Boolean {
            return key != null && pendingFocusMap.containsKey(key)
        }

        fun isCurrentFocus(key: String?): Boolean {
            return currentFocusKey == key
        }

        /**
         * 检查焦点是否是用户主动点击发起的
         */
        fun isUserInitiatedFocus(key: String?): Boolean {
            return key != null && pendingFocusMap[key]?.isUserInitiated == true
        }

        /**
         * 检查是否有待处理的焦点但没有触摸信息（用于滑动中点击的情况）
         */
        fun hasPendingFocusWithoutTouch(): Boolean {
            return pendingFocusMap.isNotEmpty() && currentFocusKey == null
        }

        fun applyPendingCursorPosition(editText: android.widget.EditText, key: String?) {
            if (key != null) {
                val touchInfo = pendingFocusMap[key]
                if (touchInfo != null) {
                    try {
                        val offset = editText.getOffsetForPosition(touchInfo.touchX, touchInfo.touchY)
                        if (offset != -1) {
                            editText.setSelection(offset)
                        }
                    } catch (e: Exception) {
                        // 忽略异常
                    }
                    // 光标设置完成后清理待定信息
                    pendingFocusMap.remove(key)
                }
            }
        }
    }
}