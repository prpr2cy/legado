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

                // 焦点监听
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && currentKey == editText.getTag(R.id.tag)) {
                        mainHandler.post {
                            if (editText.hasFocus()) {
                                editText.isCursorVisible = true
                            }
                        }
                    } else {
                        editText.isCursorVisible = false
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

            // 安全绑定
            SafeBind.bind(editText, editEntity, textWatcher, mainHandler)

            editText.setTag(R.id.tag2, textWatcher)
            textInputLayout.hint = editEntity.hint
        }
    }
}

/* ------------------------------------------------------------------ */
/* 零入侵绑定工具：仅做增加，不改动外部任何代码                         */
/* ------------------------------------------------------------------ */
private object SafeBind {

    /**
     * 使用 WeakHashMap 避免内存泄漏
     * 键为 EditEntity.key，值为最后一次设置的文本
     */
    private val key2txt = Collections.synchronizedMap(
        WeakHashMap<String, String>()
    )

    /**
     * 安全绑定 EditText：
     * 1. 文本无变化时不 setText，保护光标 & 输入法 span
     * 2. 先清旧监听再挂新监听，防止 Holder 复用串扰
     * 3. 智能恢复选择状态，避免光标跳动
     */
    fun bind(
        et: android.widget.EditText,
        entity: EditEntity,
        watcher: TextWatcher,
        handler: Handler
    ) {
        val key = entity.key
        val newText = entity.value.orEmpty()

        // 保存当前状态
        val hadFocus = et.hasFocus()
        val selStart = et.selectionStart
        val selEnd = et.selectionEnd
        val currentText = et.text?.toString().orEmpty()

        // 只在文本确实变化时更新，避免不必要的 setText
        if (currentText != newText) {
            key2txt[key] = newText
            et.setText(newText)

            // 如果之前有焦点且选择范围有效，恢复选择状态
            if (hadFocus && selStart >= 0 && selEnd >= 0 &&
                selStart <= newText.length && selEnd <= newText.length
            ) {
                handler.post {
                    if (et.hasFocus()) {
                        et.setSelection(selStart, selEnd)
                    }
                }
            }
        }

        // 防止复用 Holder 时旧监听仍在写其他实体
        et.removeTextChangedListener(watcher)
        et.addTextChangedListener(watcher)

        // 安全的光标处理：只在有焦点时显示光标
        if (et.hasFocus()) {
            handler.post {
                if (et.hasFocus()) {
                    et.isCursorVisible = true
                }
            }
        } else {
            et.isCursorVisible = false
        }
    }
}