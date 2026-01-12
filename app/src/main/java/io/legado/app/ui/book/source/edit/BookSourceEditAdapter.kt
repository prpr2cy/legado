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

    // 关键：暴露给Activity的滚动状态
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

            cleanup()

            // 关键修改：移除原来的OnAttachStateChangeListener
            // 不再使用 isCursorVisible = false/true 的trick
            // 而是完全控制焦点获取时机

            // 设置触摸监听器
            editText.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 关键逻辑：滚动时完全阻止焦点获取
                        if (isRecyclerViewScrolling) {
                            // 消费事件，阻止EditText获取焦点
                            return@setOnTouchListener true
                        }
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        // 手指抬起时，如果之前被阻止了焦点，现在处理
                        if (isRecyclerViewScrolling) {
                            return@setOnTouchListener true
                        }

                        // 非滚动状态：手动处理光标准确定位
                        handleClickWithAccurateCursor(v as android.widget.EditText, event)
                        false
                    }
                    else -> false
                }
            }

            editText.setText(editEntity.value)
            textInputLayout.hint = editEntity.hint

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

        @SuppressLint("ClickableViewAccessibility")
        private fun handleClickWithAccurateCursor(editText: EditText, event: MotionEvent) {
            try {
                val layout = editText.layout ?: return

                // 计算相对于EditText内容的坐标
                val x = event.x - editText.paddingLeft
                val y = event.y - editText.paddingTop

                // 获取点击位置对应的字符偏移
                val line = layout.getLineForVertical(y.toInt())
                val offset = layout.getOffsetForHorizontal(line, x)
                    .coerceIn(0, editText.text?.length ?: 0)

                // 设置焦点和光标位置
                editText.requestFocus()
                editText.setSelection(offset)

            } catch (e: Exception) {
                // 如果计算失败，使用默认行为
                editText.requestFocus()
                editText.setSelection(editText.text?.length ?: 0)
            }
        }

        fun cleanup() {
            textWatcher?.let {
                binding.editText.removeTextChangedListener(it)
                textWatcher = null
            }
            binding.editText.setOnTouchListener(null)
        }
    }
}