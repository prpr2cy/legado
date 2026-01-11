package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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

    // 存储每个输入框的焦点和选择状态
    private val selectionStates = mutableMapOf<String, SelectionState>()

    data class SelectionState(
        val hasFocus: Boolean = false,
        val selectionStart: Int = 0,
        val selectionEnd: Int = 0,
        val text: String? = null
    )

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

    // 保存状态并清理资源
    override fun onViewRecycled(holder: MyViewHolder) {
        holder.saveSelectionState()
        holder.cleanup()
        super.onViewRecycled(holder)
    }

    inner class MyViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var currentTextWatcher: TextWatcher? = null
        private var currentEntityKey: String? = null

        init {
            setupFocusAndSelectionListeners()
        }

        private fun setupFocusAndSelectionListeners() {
            binding.editText.setOnFocusChangeListener { _, hasFocus ->
                currentEntityKey?.let { key ->
                    if (hasFocus) {
                        // 保存焦点状态和选择位置
                        val selectionStart = binding.editText.selectionStart
                        val selectionEnd = binding.editText.selectionEnd
                        selectionStates[key] = SelectionState(
                            hasFocus = true,
                            selectionStart = selectionStart,
                            selectionEnd = selectionEnd,
                            text = binding.editText.text?.toString()
                        )
                    } else {
                        selectionStates[key] = SelectionState(
                            hasFocus = false,
                            selectionStart = binding.editText.selectionStart,
                            selectionEnd = binding.editText.selectionEnd,
                            text = binding.editText.text?.toString()
                        )
                    }
                }
            }

            // 监听选择变化
            binding.editText.setOnSelectionChangedListener { start, end ->
                currentEntityKey?.let { key ->
                    selectionStates[key] = selectionStates[key]?.copy(
                        selectionStart = start,
                        selectionEnd = end
                    ) ?: SelectionState(
                        selectionStart = start,
                        selectionEnd = end,
                        text = binding.editText.text?.toString()
                    )
                }
            }
        }

        fun bind(editEntity: EditEntity) = binding.run {
            // 保存当前实体key
            currentEntityKey = editEntity.key

            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            // 移除旧的TextWatcher
            currentTextWatcher?.let { editText.removeTextChangedListener(it) }

            // 先设置文本，再恢复选择状态
            val currentState = selectionStates[editEntity.key]
            val currentText = editText.text?.toString()

            // 只有当文本真正改变时才设置，避免干扰选择状态
            if (currentText != editEntity.value) {
                editText.setText(editEntity.value)
            }

            textInputLayout.hint = editEntity.hint

            // 【关键修复】延迟恢复选择状态，确保视图已经完全布局
            editText.post {
                val state = selectionStates[editEntity.key]
                if (state != null && state.hasFocus) {
                    // 恢复选择状态
                    if (state.selectionStart >= 0 && state.selectionEnd >= 0 &&
                        state.selectionStart <= editText.length() &&
                        state.selectionEnd <= editText.length()) {
                        try {
                            editText.setSelection(state.selectionStart, state.selectionEnd)
                        } catch (e: Exception) {
                            // 防止选择范围越界
                            editText.setSelection(state.selectionStart.coerceAtMost(editText.length()))
                        }
                    }

                    // 请求焦点
                    editText.requestFocus()
                } else {
                    // 确保没有焦点的视图不会保持选择状态
                    editText.clearFocus()
                }
            }

            // 光标修复
            editText.apply {
                viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                        if (hasFocus()) {
                            isCursorVisible = false
                            post {
                                isCursorVisible = true
                            }
                        }
                    }
                })

                isFocusable = true
                isFocusableInTouchMode = true
            }

            // 创建新的TextWatcher
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    editEntity.value = s?.toString()
                    // 实时更新文本状态
                    currentEntityKey?.let { key ->
                        selectionStates[key] = selectionStates[key]?.copy(
                            text = s?.toString()
                        ) ?: SelectionState(text = s?.toString())
                    }
                }
            }

            editText.addTextChangedListener(textWatcher)
            currentTextWatcher = textWatcher
        }

        fun saveSelectionState() {
            currentEntityKey?.let { key ->
                val hasFocus = binding.editText.hasFocus()
                val selectionStart = binding.editText.selectionStart
                val selectionEnd = binding.editText.selectionEnd
                val text = binding.editText.text?.toString()

                selectionStates[key] = SelectionState(
                    hasFocus = hasFocus,
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd,
                    text = text
                )
            }
        }

        fun cleanup() {
            saveSelectionState()
            currentTextWatcher?.let {
                binding.editText.removeTextChangedListener(it)
                currentTextWatcher = null
            }
            currentEntityKey = null
        }
    }

    // 扩展函数：为EditText添加选择变化监听
    private fun androidx.appcompat.widget.AppCompatEditText.setOnSelectionChangedListener(
        listener: (Int, Int) -> Unit
    ) {
        setOnTouchListener { v, event ->
            // 让触摸事件正常处理
            v.performClick()
            false
        }

        // 通过TextWatcher和焦点变化来检测选择变化
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                listener(selectionStart, selectionEnd)
            }
        })
    }
}