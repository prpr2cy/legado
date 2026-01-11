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

    // 提供外部调用的焦点请求方法
    fun requestFocusAt(position: Int) {
        // 使用post确保在布局完成后执行
        (getRecyclerView()?.post {
            val viewHolder = getRecyclerView()?.findViewHolderForAdapterPosition(position)
            (viewHolder as? MyViewHolder)?.binding?.editText?.requestFocus()
        })
    }

    private fun getRecyclerView(): RecyclerView? {
        return null // 实际使用时需要获取RecyclerView实例
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
        holder.bind(editEntities[position], position)
    }

    override fun getItemCount(): Int {
        return editEntities.size
    }

    inner class MyViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(editEntity: EditEntity, position: Int) = binding.run {
            // 核心1: 设置位置标签
            editText.tag = position

            // 设置文本和提示
            textInputLayout.hint = editEntity.hint
            editText.maxLines = editEntityMaxLine

            // 移除旧的TextWatcher
            editText.getTag(R.id.tag2)?.let {
                if (it is TextWatcher) {
                    editText.removeTextChangedListener(it)
                }
            }

            // 核心2: 设置文本并恢复光标位置
            val currentText = editText.text?.toString().orEmpty()
            val newText = editEntity.value.orEmpty()
            if (currentText != newText) {
                editText.setText(newText)
            }

            // 恢复光标位置
            val safeCursor = editEntity.cursor.coerceAtMost(newText.length)
            editText.setSelection(safeCursor)

            // 核心3: 文本变化时实时保存数据和光标位置
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable?) {
                    s?.let {
                        editEntity.value = it.toString()
                        editEntity.cursor = editText.selectionEnd
                    }
                }
            }
            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)

            // 简化焦点处理 - 只在用户点击时请求焦点
            editText.setOnClickListener {
                editText.requestFocus()
            }

            // 简化附件状态监听
            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        // 保持简单，不需要特殊处理
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        // 保存最终的光标位置
                        editEntity.cursor = editText.selectionEnd
                    }
                }
                editText.addOnAttachStateChangeListener(listener)
                editText.setTag(R.id.tag1, listener)
            }
        }
    }
}