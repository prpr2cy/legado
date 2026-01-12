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

    // 滚动状态
    private var isRecyclerViewScrolling = false

    // 双击检测
    private var isDoubleClickDetection = false
    private var firstClickTime = 0L
    private val doubleClickThreshold = 400L

    // 待处理的点击坐标（用于双击的第二次点击）
    private data class PendingClick(
        val editText: EditText,
        val x: Float,
        val y: Float
    )
    private var pendingClick: PendingClick? = null

    // Activity调用此方法更新滚动状态
    fun setScrolling(scrolling: Boolean) {
        isRecyclerViewScrolling = scrolling

        // 从滚动状态变为停止状态时，处理待处理的点击
        if (!scrolling) {
            processPendingClick()
        }
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

    // 处理待处理的点击（双击的第二次点击）
    private fun processPendingClick() {
        val click = pendingClick ?: return
        pendingClick = null

        // 延迟一段时间，确保布局完全稳定
        click.editText.postDelayed({
            setCursorAtCoordinate(click.editText, click.x, click.y)
        }, 100)
    }

    inner class MyViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var textWatcher: TextWatcher? = null

        fun bind(editEntity: EditEntity) = binding.run {
            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            cleanup()

            // 设置触摸监听器
            editText.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        handleTouchDown(v as EditText, event.x, event.y)
                        return@setOnTouchListener true
                    }

                    MotionEvent.ACTION_UP -> {
                        // 如果是双击检测中的第一次点击，且没有第二次点击，取消双击检测
                        if (isDoubleClickDetection && System.currentTimeMillis() - firstClickTime > doubleClickThreshold) {
                            isDoubleClickDetection = false
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        isDoubleClickDetection = false
                    }
                }
                false
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

        private fun handleTouchDown(editText: EditText, x: Float, y: Float) {
            val currentTime = System.currentTimeMillis()

            if (isRecyclerViewScrolling) {
                // 滑动状态：处理双击逻辑
                if (!isDoubleClickDetection) {
                    // 第一次点击：停止滚动，开启双击检测
                    isDoubleClickDetection = true
                    firstClickTime = currentTime

                    // 停止滚动
                    notifyScrollStopRequested()

                    // 设置一个超时，如果400ms内没有第二次点击，则取消双击检测
                    editText.postDelayed({
                        if (isDoubleClickDetection) {
                            isDoubleClickDetection = false
                            // 滑动中的单次点击不处理，因为可能是误触
                        }
                    }, doubleClickThreshold)
                } else if (currentTime - firstClickTime < doubleClickThreshold) {
                    // 第二次快速点击：记录坐标，等待处理
                    pendingClick = PendingClick(editText, x, y)
                    isDoubleClickDetection = false

                    // 停止滚动
                    notifyScrollStopRequested()
                } else {
                    // 超过时间阈值，重新开始
                    isDoubleClickDetection = true
                    firstClickTime = currentTime
                    notifyScrollStopRequested()
                }
            } else {
                // 非滑动状态：立即处理
                setCursorAtCoordinate(editText, x, y)
            }
        }

        private fun setCursorAtCoordinate(editText: EditText, x: Float, y: Float) {
            try {
                // 先请求焦点
                editText.requestFocus()

                // 延迟一小段时间确保焦点已获取
                editText.postDelayed({
                    try {
                        val layout = editText.layout ?: return@postDelayed

                        // 计算相对于EditText内容的坐标
                        val contentX = x - editText.paddingLeft
                        val contentY = y - editText.paddingTop

                        // 获取点击位置对应的字符偏移
                        val line = layout.getLineForVertical(contentY.toInt())
                        val offset = layout.getOffsetForHorizontal(line, contentX)
                            .coerceIn(0, editText.text?.length ?: 0)

                        // 设置光标位置
                        editText.setSelection(offset)

                        // 通知Activity检查键盘遮挡
                        notifyCheckKeyboardCoverage(editText, x, y)

                        // 显示键盘
                        showKeyboard(editText)

                    } catch (e: Exception) {
                        // 如果计算失败，使用默认行为
                        editText.setSelection(editText.text?.length ?: 0)
                        notifyCheckKeyboardCoverage(editText, x, y)
                    }
                }, 50)

            } catch (e: Exception) {
                editText.requestFocus()
            }
        }

        private fun showKeyboard(editText: EditText) {
            editText.postDelayed({
                val imm = editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        }

        fun cleanup() {
            textWatcher?.let {
                binding.editText.removeTextChangedListener(it)
                textWatcher = null
            }
            binding.editText.setOnTouchListener(null)
        }
    }

    // 通知Activity停止滚动
    private var scrollStopListener: (() -> Unit)? = null

    // 通知Activity检查键盘遮挡
    private var keyboardCoverageListener: ((EditText, Float, Float) -> Unit)? = null

    fun setScrollStopListener(listener: () -> Unit) {
        scrollStopListener = listener
    }

    fun setKeyboardCoverageListener(listener: (EditText, Float, Float) -> Unit) {
        keyboardCoverageListener = listener
    }

    private fun notifyScrollStopRequested() {
        scrollStopListener?.invoke()
    }

    private fun notifyCheckKeyboardCoverage(editText: EditText, x: Float, y: Float) {
        keyboardCoverageListener?.invoke(editText, x, y)
    }
}