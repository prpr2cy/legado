package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
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

        // 设置文本选择监听
        binding.editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // 确保文本选择可用
                binding.editText.isCursorVisible = true
                binding.editText.isFocusable = true
                binding.editText.isFocusableInTouchMode = true
            }
        }

        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.bind(editEntities[position])
    }

    override fun getItemCount(): Int {
        return editEntities.size
    }

    override fun onViewRecycled(holder: MyViewHolder) {
        super.onViewRecycled(holder)
        holder.cleanup()
    }

    inner class MyViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var currentEditEntity: EditEntity? = null
        private var textWatcher: TextWatcher? = null

        fun bind(editEntity: EditEntity) = binding.run {
            // 移除旧的文本监听器
            cleanup()

            // 保存当前编辑实体引用
            currentEditEntity = editEntity

            // 设置标签和属性
            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            // 设置文本内容
            val currentText = editText.text?.toString()
            if (currentText != editEntity.value) {
                editText.setText(editEntity.value)
            }

            textInputLayout.hint = editEntity.hint

            // 创建新的文本监听器
            textWatcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                    // 空实现
                }

                override fun onTextChanged(
                    s: CharSequence,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    // 空实现
                }

                override fun afterTextChanged(s: Editable?) {
                    currentEditEntity?.value = s?.toString()
                }
            }

            // 添加文本监听器
            textWatcher?.let { editText.addTextChangedListener(it) }

            // 设置焦点变化监听
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // 确保光标可见和文本选择可用
                    editText.isCursorVisible = true
                    editText.isFocusable = true
                    editText.isFocusableInTouchMode = true

                    // 延迟设置选择位置，避免与系统选择冲突
                    editText.post {
                        if (editText.hasFocus()) {
                            val textLength = editText.text?.length ?: 0
                            if (textLength > 0) {
                                // 确保选择范围有效
                                val selectionStart = editText.selectionStart.coerceAtLeast(0)
                                val selectionEnd = editText.selectionEnd.coerceAtMost(textLength)
                                if (selectionStart != selectionEnd) {
                                    editText.setSelection(selectionStart, selectionEnd)
                                }
                            }
                        }
                    }
                }
            }
        }

        fun cleanup() {
            // 清理资源
            textWatcher?.let {
                binding.editText.removeTextChangedListener(it)
                textWatcher = null
            }
            currentEditEntity = null
        }
    }
}