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
        private var scrollRunnable: Runnable? = null
        private val ensureCursorRunnable = Runnable {
            if (binding.editText.hasFocus()) {
                ensureCursorVisible()
            }
        }

        fun bind(editEntity: EditEntity) = binding.run {
            // 重置焦点状态，防止快速滑动时焦点错乱
            editText.clearFocus()

            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            // 移除旧的文本监听器
            editText.getTag(R.id.tag2)?.let {
                if (it is TextWatcher) {
                    editText.removeTextChangedListener(it)
                }
            }

            // 智能文本更新 - 避免不必要的文本设置导致光标跳动
            val oldText = editText.text?.toString().orEmpty()
            val newText = editEntity.value.orEmpty()
            if (oldText != newText) {
                val hasFocus = editText.hasFocus()
                val selStart = editText.selectionStart.coerceIn(0, oldText.length)
                val selEnd = editText.selectionEnd.coerceIn(0, oldText.length)

                editText.setText(newText)

                // 只有在当前有焦点且选择范围有效时才恢复选择
                if (hasFocus && selStart <= newText.length && selEnd <= newText.length) {
                    mainHandler.post {
                        if (editText.hasFocus()) {
                            editText.setSelection(selStart, selEnd)
                        }
                    }
                }
            }

            textInputLayout.hint = editEntity.hint

            // 新的文本变化监听器
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable?) {
                    editEntity.value = s?.toString().orEmpty()

                    // 使用防抖机制，避免频繁滚动
                    mainHandler.removeCallbacks(ensureCursorRunnable)
                    mainHandler.postDelayed(ensureCursorRunnable, 100)
                }
            }
            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)

            // 点击处理 - 确保点击正确的项目
            editText.setOnClickListener {
                editText.requestFocus()
            }

            // 焦点变化监听 - 更好的管理光标显示
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // 延迟设置光标可见，避免与输入法冲突
                    mainHandler.post {
                        editText.isCursorVisible = true
                    }
                } else {
                    editText.isCursorVisible = false
                    // 失去焦点时清理防抖任务
                    mainHandler.removeCallbacks(ensureCursorRunnable)
                }
            }

            // 只在第一次绑定时添加附加监听器
            if (editText.getTag(R.id.tag1) == null) {
                val attachListener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        // 简化设置，避免与输入法冲突
                        editText.isFocusable = true
                        editText.isFocusableInTouchMode = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        // 清理工作
                        editText.clearFocus()
                        cancelScroll()
                        mainHandler.removeCallbacks(ensureCursorRunnable)
                    }
                }
                editText.addOnAttachStateChangeListener(attachListener)
                editText.setTag(R.id.tag1, attachListener)
            }
        }

        /**
         * 确保光标在可见范围内
         */
        private fun ensureCursorVisible() {
            cancelScroll()
            scrollRunnable = Runnable {
                (binding.root.parent as? RecyclerView)?.let { recyclerView ->
                    // 使用当前的位置，确保滚动到正确的位置
                    val currentPosition = adapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        recyclerView.smoothScrollToPosition(currentPosition)
                    }
                }
            }
            mainHandler.post(scrollRunnable!!)
        }

        /**
         * 取消待处理的滚动
         */
        private fun cancelScroll() {
            scrollRunnable?.let {
                mainHandler.removeCallbacks(it)
                scrollRunnable = null
            }
        }
    }
}