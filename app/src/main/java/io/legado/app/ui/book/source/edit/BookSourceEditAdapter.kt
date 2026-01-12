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

    // 滑动中点击的时间记录
    private var scrollClickDownTime = 0L
    private val quickClickThreshold = 200L // 200ms内算快速点击
    private val layoutStableDelay = 100L   // 等待布局稳定的延迟

    // Activity调用此方法更新滚动状态
    fun setScrolling(scrolling: Boolean) {
        isRecyclerViewScrolling = scrolling
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
        private var clickDownTime = 0L // 每个ViewHolder自己的点击时间记录

        fun bind(editEntity: EditEntity) = binding.run {
            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            cleanup()

            // 设置触摸监听器
            editText.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 如果正在滚动，阻止焦点获取
                        if (isRecyclerViewScrolling) {
                            // 记录滑动中点击的时间（用于滑动停止后的处理）
                            scrollClickDownTime = System.currentTimeMillis()
                            // 消费事件，阻止EditText获取焦点
                            return@setOnTouchListener true
                        }
                        false
                    }

                    MotionEvent.ACTION_UP -> {
                        // 如果正在滚动，不处理抬起事件
                        if (isRecyclerViewScrolling) {
                            // 记录滑动中点击的时间（用于滑动停止后的处理）
                            scrollClickDownTime = System.currentTimeMillis()
                            // 消费事件，阻止EditText获取焦点
                            return@setOnTouchListener true
                        }

                        if (System.currentTimeMillis() - scrollClickDownTime < quickClickThreshold) {
                            // 非滚动状态：计算点击时长，如果是快速点击（<200ms），延迟处理等待布局稳定
                            v.postDelayed({
                                handleClickWithAccurateCursor(v as EditText, event)
                                showKeyboard(v as EditText)
                            }, layoutStableDelay)
                        } else {
                            // 正常点击，立即处理
                            handleClickWithAccurateCursor(v as EditText, event)
                            showKeyboard(v as EditText)
                        }
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
            }
        }

        private fun showKeyboard(editText: EditText) {
            editText.postDelayed({
                val imm = editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 150)
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