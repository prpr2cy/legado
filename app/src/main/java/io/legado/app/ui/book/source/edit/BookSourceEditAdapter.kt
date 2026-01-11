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

    // 存储每个输入框的焦点和选择状态
    private val focusStates = mutableMapOf<String, FocusState>()

    data class FocusState(
        val hasFocus: Boolean = false,
        val selectionStart: Int = 0,
        val selectionEnd: Int = 0
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
        holder.saveFocusState()
        holder.cleanup()
        super.onViewRecycled(holder)
    }

    inner class MyViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var currentTextWatcher: TextWatcher? = null
        private var currentEntityKey: String? = null
        private var selectionWatcher: TextWatcher? = null

        init {
            setupFocusListeners()
        }

        private fun setupFocusListeners() {
            binding.editText.setOnFocusChangeListener { _, hasFocus ->
                currentEntityKey?.let { key ->
                    if (hasFocus) {
                        // 保存焦点状态和选择位置
                        val selectionStart = binding.editText.selectionStart
                        val selectionEnd = binding.editText.selectionEnd
                        focusStates[key] = FocusState(
                            hasFocus = true,
                            selectionStart = selectionStart,
                            selectionEnd = selectionEnd
                        )
                    } else {
                        focusStates[key] = FocusState(
                            hasFocus = false,
                            selectionStart = binding.editText.selectionStart,
                            selectionEnd = binding.editText.selectionEnd
                        )
                    }
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
            selectionWatcher?.let { editText.removeTextChangedListener(it) }

            // 设置选择状态监听器
            selectionWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // 实时保存选择状态
                    currentEntityKey?.let { key ->
                        if (editText.hasFocus()) {
                            focusStates[key] = FocusState(
                                hasFocus = true,
                                selectionStart = editText.selectionStart,
                                selectionEnd = editText.selectionEnd
                            )
                        }
                    }
                }
            }
            editText.addTextChangedListener(selectionWatcher)

            // 设置文本
            editText.setText(editEntity.value)
            textInputLayout.hint = editEntity.hint

            // 恢复焦点状态和选择位置
            val state = focusStates[editEntity.key]
            editText.post {
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
                }
            }

            // 光标修复
            editText.apply {
                // 移除旧的attach监听器
                editText.getTag(R.id.tag1)?.let {
                    if (it is View.OnAttachStateChangeListener) {
                        editText.removeOnAttachStateChangeListener(it)
                    }
                }

                val attachListener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        post {
                            isCursorVisible = false
                            post {
                                isCursorVisible = true
                            }
                        }
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        // 保存状态
                        saveFocusState()
                    }
                }
                editText.addOnAttachStateChangeListener(attachListener)
                editText.setTag(R.id.tag1, attachListener)
            }

            // 创建新的TextWatcher
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    editEntity.value = (s?.toString())
                }
            }

            editText.addTextChangedListener(textWatcher)
            currentTextWatcher = textWatcher
        }

        fun saveFocusState() {
            currentEntityKey?.let { key ->
                val hasFocus = binding.editText.hasFocus()
                val selectionStart = binding.editText.selectionStart
                val selectionEnd = binding.editText.selectionEnd

                focusStates[key] = FocusState(
                    hasFocus = hasFocus,
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd
                )
            }
        }

        fun cleanup() {
            saveFocusState()
            currentTextWatcher?.let {
                binding.editText.removeTextChangedListener(it)
                currentTextWatcher = null
            }
            selectionWatcher?.let {
                binding.editText.removeTextChangedListener(it)
                selectionWatcher = null
            }
            currentEntityKey = null
        }
    }
}