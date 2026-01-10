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
        private var isUserTouch = false
        private var lastSelectionStart = 0
        private var lastSelectionEnd = 0

        fun bind(editEntity: EditEntity) = binding.run {
            currentKey = editEntity.key

            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            // 重置状态
            editText.clearFocus()
            editText.isCursorVisible = false
            isUserTouch = false

            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        editText.isFocusable = true
                        editText.isFocusableInTouchMode = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        // 保存当前选择位置
                        if (editText.hasFocus()) {
                            lastSelectionStart = editText.selectionStart
                            lastSelectionEnd = editText.selectionEnd
                        }
                        // 清理焦点状态
                        focusManager.clearPendingTouch(currentKey)
                        editText.clearFocus()
                        isUserTouch = false
                    }
                }
                editText.addOnAttachStateChangeListener(listener)
                editText.setTag(R.id.tag1, listener)

                // 触摸监听 - 记录用户触摸
                editText.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            // 标记为用户触摸
                            isUserTouch = true

                            // 检查是否在滑动中
                            val isScrolling = recyclerView?.scrollState != RecyclerView.SCROLL_STATE_IDLE

                            if (isScrolling) {
                                // 如果在滑动中，停止滑动并延迟处理点击
                                recyclerView?.stopScroll()
                                mainHandler.postDelayed({
                                    handleTouchEvent(event)
                                }, 150) // 等待滑动完全停止
                            } else {
                                // 直接处理点击
                                handleTouchEvent(event)
                            }
                            return@setOnTouchListener true // 消费事件
                        }
                        MotionEvent.ACTION_UP -> {
                            isUserTouch = false
                        }
                    }
                    false
                }

                // 焦点监听
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        if (isUserTouch) {
                            // 用户触摸：显示光标、弹键盘、设置触摸位置
                            editText.isCursorVisible = true
                            showSoftInput(editText)

                            // 延迟设置触摸位置的光标
                            mainHandler.postDelayed({
                                if (editText.hasFocus()) {
                                    focusManager.applyPendingCursorPosition(editText, currentKey)
                                }
                            }, 50)
                        } else {
                            // 程序设置焦点：恢复之前的光标位置，但不弹键盘
                            editText.isCursorVisible = true
                            // 恢复之前的选择位置
                            mainHandler.post {
                                if (editText.hasFocus()) {
                                    val safeStart = lastSelectionStart.coerceAtMost(editText.text.length)
                                    val safeEnd = lastSelectionEnd.coerceAtMost(editText.text.length)
                                    editText.setSelection(safeStart, safeEnd)
                                }
                            }
                        }
                    } else {
                        // 失去焦点时保存选择位置
                        lastSelectionStart = editText.selectionStart
                        lastSelectionEnd = editText.selectionEnd
                        editText.isCursorVisible = false
                        focusManager.clearPendingTouch(currentKey)
                        isUserTouch = false
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
                val hadFocus = editText.hasFocus()
                val selStart = editText.selectionStart
                val selEnd = editText.selectionEnd

                editText.setText(newText)

                // 如果之前有焦点且选择有效，恢复选择
                if (hadFocus && selStart >= 0 && selEnd >= 0 &&
                    selStart <= newText.length && selEnd <= newText.length
                ) {
                    mainHandler.post {
                        if (editText.hasFocus()) {
                            editText.setSelection(selStart, selEnd)
                        }
                    }
                } else if (hadFocus) {
                    // 如果选择无效，保存为最后位置用于恢复
                    lastSelectionStart = selStart.coerceAtMost(newText.length)
                    lastSelectionEnd = selEnd.coerceAtMost(newText.length)
                }
            }

            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)
            textInputLayout.hint = editEntity.hint

            // 如果是当前焦点项，恢复焦点但不弹键盘
            if (focusManager.isCurrentFocus(currentKey)) {
                mainHandler.post {
                    if (focusManager.isCurrentFocus(currentKey) && !editText.hasFocus()) {
                        // 程序设置焦点，不标记为用户触摸
                        isUserTouch = false
                        editText.requestFocus()
                        // 光标会在焦点监听器中显示并恢复位置
                    }
                }
            }
        }

        /**
         * 处理触摸事件
         */
        private fun handleTouchEvent(event: MotionEvent) {
            // 记录触摸位置
            focusManager.setPendingFocus(currentKey, event.x, event.y)

            // 直接请求焦点
            binding.editText.requestFocus()

            // 如果焦点请求失败，重试一次
            mainHandler.postDelayed({
                if (!binding.editText.hasFocus()) {
                    binding.editText.requestFocus()
                }
            }, 50)
        }

        /**
         * 显示软键盘
         */
        private fun showSoftInput(editText: android.widget.EditText) {
            mainHandler.post {
                try {
                    val inputMethodManager = editText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    if (inputMethodManager != null && editText.hasFocus()) {
                        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                    }
                } catch (e: Exception) {
                    // 忽略异常
                }
            }
        }
    }

    /**
     * 简化焦点管理器
     */
    private class FocusManager {
        // 当前获得焦点的key
        private var currentFocusKey: String? = null

        // 待定焦点信息，key -> 触摸坐标
        private val pendingFocusMap = mutableMapOf<String, TouchInfo>()

        private data class TouchInfo(val touchX: Float, val touchY: Float)

        fun setPendingFocus(key: String?, x: Float, y: Float) {
            if (key != null) {
                pendingFocusMap[key] = TouchInfo(x, y)
                currentFocusKey = key // 直接设置为当前焦点
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

        fun isCurrentFocus(key: String?): Boolean {
            return currentFocusKey == key
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
                        // 如果有触摸位置但计算失败，不设置任何位置
                    } catch (e: Exception) {
                        // 忽略异常，不设置任何位置
                    }
                    // 清理待定信息
                    pendingFocusMap.remove(key)
                }
                // 如果没有触摸信息，不设置任何位置
            }
        }
    }
}