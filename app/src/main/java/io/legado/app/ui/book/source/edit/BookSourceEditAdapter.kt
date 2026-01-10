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
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        fun bind(editEntity: EditEntity) = binding.run {
            currentKey = editEntity.key

            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        editText.isFocusable = true
                        editText.isFocusableInTouchMode = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        editText.clearFocus()
                    }
                }
                editText.addOnAttachStateChangeListener(listener)
                editText.setTag(R.id.tag1, listener)

                // 触摸监听 - 记录点击位置
                editText.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            // 记录触摸位置
                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                        MotionEvent.ACTION_UP -> {
                            // 只在点击释放时处理，避免干扰滚动
                            if (currentKey == editText.getTag(R.id.tag)) {
                                mainHandler.post {
                                    if (editText.hasFocus()) {
                                        // 根据触摸位置设置光标
                                        setCursorAtTouchPosition()
                                    }
                                }
                            }
                        }
                    }
                    false // 不消费事件，让点击处理正常进行
                }

                // 焦点监听
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && currentKey == editText.getTag(R.id.tag)) {
                        mainHandler.post {
                            if (editText.hasFocus() && currentKey == editText.getTag(R.id.tag)) {
                                editText.isCursorVisible = true
                                // 焦点获得时，如果有记录的触摸位置，设置光标
                                if (lastTouchX != 0f || lastTouchY != 0f) {
                                    setCursorAtTouchPosition()
                                }
                            }
                        }
                    } else {
                        editText.isCursorVisible = false
                        // 重置触摸位置
                        lastTouchX = 0f
                        lastTouchY = 0f
                    }
                }

                // 点击处理
                editText.setOnClickListener {
                    if (currentKey == editText.getTag(R.id.tag)) {
                        editText.requestFocus()
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
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) = Unit

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
                        if (editText.hasFocus() && currentKey == editText.getTag(R.id.tag)) {
                            editText.setSelection(selStart, selEnd)
                        }
                    }
                }
            }

            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)
            textInputLayout.hint = editEntity.hint
        }

        /**
         * 根据记录的触摸位置设置光标
         */
        private fun setCursorAtTouchPosition() {
            if (lastTouchX == 0f && lastTouchY == 0f) return

            try {
                val offset = binding.editText.getOffsetForPosition(lastTouchX, lastTouchY)
                if (offset != -1) {
                    binding.editText.setSelection(offset)
                }
            } catch (e: Exception) {
                // 忽略异常，使用默认行为
            }

            // 重置触摸位置
            lastTouchX = 0f
            lastTouchY = 0f
        }
    }
}