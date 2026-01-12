package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

    // 滚动状态 - 由Activity实时更新
    private var isRecyclerViewScrolling = false

    // Activity调用此方法更新滚动状态
    fun setScrolling(scrolling: Boolean) {
        isRecyclerViewScrolling = scrolling
        // 不需要notifyDataSetChanged，因为ViewHolder会实时读取这个状态
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

    override fun onViewRecycled(holder: MyViewHolder) {
        super.onViewRecycled(holder)
        holder.cleanup()
    }

    inner class MyViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var textWatcher: TextWatcher? = null

        fun bind(editEntity: EditEntity) = binding.run {
            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            // 移除旧的文本监听器
            cleanup()

            // 设置触摸监听器
            editText.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 如果正在滚动，消费事件，阻止焦点获取
                        if (isRecyclerViewScrolling) {
                            return@setOnTouchListener true
                        }
                        false
                    }

                    MotionEvent.ACTION_UP -> {
                        // 如果正在滚动，不处理抬起事件
                        if (isRecyclerViewScrolling) {
                            return@setOnTouchListener true
                        }
                        false
                    }
                    else -> false
                }
            }

            editText.setText(editEntity.value)
            textInputLayout.hint = editEntity.hint

            // 创建新的文本监听器
            textWatcher = object : TextWatcher {
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
                    editEntity.value = (s?.toString())
                }
            }
            editText.addTextChangedListener(textWatcher)
        }

        fun cleanup() {
            // 清理文本监听器
            textWatcher?.let {
                binding.editText.removeTextChangedListener(it)
                textWatcher = null
            }
        }
    }
}