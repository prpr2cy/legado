package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

        fun bind(editEntity: EditEntity) = binding.run {
            currentKey = editEntity.key

            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            // 重置状态
            editText.clearFocus()
            editText.isCursorVisible = false

            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        editText.isFocusable = true
                        editText.isFocusableInTouchMode = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        // 清理焦点状态
                        if (focusManager.isCurrentFocus(currentKey)) {
                            focusManager.clearPendingTouch(currentKey)
                        }
                        editText.clearFocus()
                    }
                }
                editText.addOnAttachStateChangeListener(listener)
                editText.setTag(R.id.tag1, listener)

                // 触摸监听 - 立即记录触摸信息
                editText.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            // 立即记录触摸信息并设置待定焦点
                            focusManager.setPendingFocus(currentKey, event.x, event.y)
                            editText.requestFocus()
                            return@setOnTouchListener true // 消费事件
                        }
                    }
                    false
                }

                // 焦点监听
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        // 验证焦点合法性 - 只检查key
                        if (focusManager.isPendingFocus(currentKey)) {
                            focusManager.confirmFocus(currentKey)
                            mainHandler.post {
                                if (editText.hasFocus() && focusManager.isCurrentFocus(currentKey)) {
                                    editText.isCursorVisible = true
                                    // 延迟设置光标位置
                                    mainHandler.postDelayed({
                                        if (editText.hasFocus() && focusManager.isCurrentFocus(currentKey)) {
                                            focusManager.applyPendingCursorPosition(editText, currentKey)
                                        }
                                    }, 50)
                                }
                            }
                        } else {
                            // 非法焦点，清除（可能是复用导致的错乱）
                            editText.clearFocus()
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

            // 如果是当前焦点项，恢复焦点
            if (focusManager.isCurrentFocus(currentKey)) {
                mainHandler.post {
                    if (focusManager.isCurrentFocus(currentKey)) {
                        editText.requestFocus()
                    }
                }
            }
        }
    }

    /**
     * 焦点管理器 - 基于key的焦点管理，避免position复用冲突
     */
    private inner class FocusManager {
        // 当前获得焦点的key
        private var currentFocusKey: String? = null

        // 待定焦点信息，key -> 触摸坐标
        private val pendingFocusMap = mutableMapOf<String, TouchInfo>()

        private data class TouchInfo(val touchX: Float, val touchY: Float)

        fun setPendingFocus(key: String?, x: Float, y: Float) {
            if (key != null) {
                pendingFocusMap[key] = TouchInfo(x, y)
            }
        }

        fun confirmFocus(key: String?) {
            if (key != null && pendingFocusMap.containsKey(key)) {
                currentFocusKey = key
                // 不移除pending信息，等光标设置完成后再清理
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