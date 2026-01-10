package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
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

    // 全局焦点管理 - 分离key和position避免复用冲突
    private var focusedKey: String? = null
    private val selectionStates = Collections.synchronizedMap(WeakHashMap<String, Pair<Int, Int>>())

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

            // 初始化监听器（只做一次）
            if (editText.getTag(R.id.tag1) == null) {
                // 焦点状态管理
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        focusedKey = currentKey
                        // 延迟恢复选择状态，避免干扰输入法
                        mainHandler.postDelayed({
                            if (editText.hasFocus() && focusedKey == currentKey) {
                                selectionStates[currentKey]?.let { (start, end) ->
                                    if (start >= 0 && end >= 0 && start <= editText.text.length && end <= editText.text.length) {
                                        editText.setSelection(start, end)
                                    }
                                }
                            }
                        }, 100)
                    } else {
                        // 失去焦点时保存选择状态
                        if (focusedKey == currentKey) {
                            selectionStates[currentKey] = editText.selectionStart to editText.selectionEnd
                            focusedKey = null
                        }
                    }
                }

                // 点击处理 - 直接请求焦点，不主动设置选择
                editText.setOnClickListener {
                    if (currentKey == editText.getTag(R.id.tag)) {
                        editText.requestFocus()
                        // 显示输入法
                        (context.getSystemService(InputMethodManager::class.java) as? InputMethodManager)?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                    }
                }

                // 选择变化监听 - 实时保存选择状态
                editText.setOnSelectionChangeListener { _, selStart, selEnd ->
                    if (editText.hasFocus() && focusedKey == currentKey) {
                        selectionStates[currentKey] = selStart to selEnd
                    }
                }

                editText.setTag(R.id.tag1, true)
            }

            // 文本绑定 - 避免干扰选择状态
            val currentText = editText.text?.toString().orEmpty()
            val newText = editEntity.value.orEmpty()
            if (currentText != newText) {
                // 保存当前选择状态（如果有焦点）
                val isFocused = editText.hasFocus() && focusedKey == currentKey
                val selStart = if (isFocused) editText.selectionStart else -1
                val selEnd = if (isFocused) editText.selectionEnd else -1

                // 更新文本
                editText.setText(newText)

                // 只有在当前项有焦点且选择范围有效时才恢复选择
                if (isFocused && selStart >= 0 && selEnd >= 0 &&
                    selStart <= newText.length && selEnd <= newText.length
                ) {
                    mainHandler.post {
                        if (editText.hasFocus() && focusedKey == currentKey) {
                            editText.setSelection(selStart, selEnd)
                        }
                    }
                }
            }

            // 文本变化监听 - 安全更新数据
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

            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)

            textInputLayout.hint = editEntity.hint

            // 恢复焦点状态（只在当前项是焦点项时请求）
            if (focusedKey == currentKey) {
                mainHandler.post {
                    if (focusedKey == currentKey) {
                        editText.requestFocus()
                    }
                }
            }
        }
    }
}