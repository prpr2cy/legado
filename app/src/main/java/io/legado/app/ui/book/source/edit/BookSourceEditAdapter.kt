package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
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
import java.util.Collections
import java.util.WeakHashMap

class BookSourceEditAdapter : RecyclerView.Adapter<BookSourceEditAdapter.MyViewHolder>() {

    val editEntityMaxLine = AppConfig.sourceEditMaxLine

    var editEntities: ArrayList<EditEntity> = ArrayList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // 全局焦点管理
    private var focusedPosition = -1
    private var focusedKey: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val binding = ItemSourceEditBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)
        binding.editText.addLegadoPattern()
        binding.editText.addJsonPattern()
        binding.editText.addJsPattern()
        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.bind(editEntities[position], position)
    }

    override fun getItemCount(): Int {
        return editEntities.size
    }

    inner class MyViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val mainHandler = Handler(Looper.getMainLooper())
        private var currentKey: String? = null
        private var currentPosition = -1

        fun bind(editEntity: EditEntity, position: Int) = binding.run {
            currentKey = editEntity.key
            currentPosition = position

            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        editText.isFocusable = true
                        editText.isFocusableInTouchMode = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        // 清理焦点状态
                        if (focusedKey == currentKey) {
                            focusedKey = null
                            focusedPosition = -1
                        }
                        editText.clearFocus()
                    }
                }
                editText.addOnAttachStateChangeListener(listener)
                editText.setTag(R.id.tag1, listener)

                // 焦点监听 - 简化逻辑
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        // 更新全局焦点状态
                        focusedKey = currentKey
                        focusedPosition = currentPosition
                        // 延迟设置光标可见
                        mainHandler.post {
                            if (editText.hasFocus() && focusedKey == currentKey) {
                                editText.isCursorVisible = true
                            }
                        }
                    } else {
                        editText.isCursorVisible = false
                        // 如果失去焦点的是当前焦点项，清除状态
                        if (focusedKey == currentKey) {
                            focusedKey = null
                            focusedPosition = -1
                        }
                    }
                }

                // 点击处理 - 直接请求焦点，不设置选择
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

            // 安全绑定 - 简化逻辑，避免干扰输入法
            SafeBind.bind(editText, editEntity, textWatcher, mainHandler, focusedKey == currentKey)

            editText.setTag(R.id.tag2, textWatcher)
            textInputLayout.hint = editEntity.hint

            // 如果是之前获得焦点的位置，恢复焦点但不干扰选择状态
            if (focusedPosition == position && focusedKey == currentKey) {
                mainHandler.post {
                    if (focusedPosition == currentPosition && focusedKey == currentKey) {
                        editText.requestFocus()
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* 零入侵绑定工具：仅做增加，不改动外部任何代码                         */
/* ------------------------------------------------------------------ */
private object SafeBind {

    /**
     * 使用 WeakHashMap 避免内存泄漏
     */
    private val key2txt = Collections.synchronizedMap(
        WeakHashMap<String, String>()
    )

    /**
     * 安全绑定 EditText：
     * 简化逻辑，避免干扰输入法的选择状态
     */
    fun bind(
        et: android.widget.EditText,
        entity: EditEntity,
        watcher: TextWatcher,
        handler: Handler,
        isFocusedItem: Boolean
    ) {
        val key = entity.key
        val newText = entity.value.orEmpty()
        val currentText = et.text?.toString().orEmpty()

        // 只在文本确实变化时更新，避免不必要的 setText
        if (currentText != newText) {
            key2txt[key] = newText

            // 保存当前选择状态（如果有焦点）
            val hadSelection = isFocusedItem && et.hasFocus()
            val selStart = et.selectionStart
            val selEnd = et.selectionEnd

            et.setText(newText)

            // 只有在当前项有焦点且选择范围有效时才恢复选择
            if (hadSelection && selStart >= 0 && selEnd >= 0 &&
                selStart <= newText.length && selEnd <= newText.length
            ) {
                handler.post {
                    // 再次验证状态
                    if (et.hasFocus() && isFocusedItem) {
                        et.setSelection(selStart, selEnd)
                    }
                }
            }
        }

        // 管理监听器
        et.removeTextChangedListener(watcher)
        et.addTextChangedListener(watcher)

        // 简化光标处理：不主动操作光标状态，让系统管理
        // 只在失去焦点时确保光标隐藏
        if (!et.hasFocus()) {
            et.isCursorVisible = false
        }
    }
}