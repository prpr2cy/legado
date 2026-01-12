package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
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

        /* 用来延迟定位光标 */
        private var lastClickXY: FloatArray? = null

        /* 当前 attach 的 RecyclerView，用来停滚/隐藏键盘 */
        private var hostRv: RecyclerView? = null

        init {
            /* 拿到 RecyclerView 引用，后面好调 stopScroll/hideKeyboard */
            binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    hostRv = v.parent as? RecyclerView
                }
                override fun onViewDetachedFromWindow(v: View) {
                    hostRv = null
                }
            })
        }

        fun bind(editEntity: EditEntity) = binding.run {
            cleanup()

            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine
            editText.setText(editEntity.value)
            textInputLayout.hint = editEntity.hint

            /* 1. 让光标回到可见区域 */
            editText.viewTreeObserver.addOnGlobalLayout(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (editText.selectionStart != editText.selectionEnd) return
                    editText.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (editText.hasFocus()) {
                        val imm = context.getSystemService(InputMethodManager::class.java)
                        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            })

            /* 2. 核心触摸分发 */
            editText.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        /* 滚动中：只停滚，消费事件，不产生任何副作用 */
                        if (isRecyclerViewScrolling) {
                            hostRv?.stopScroll()   // 立即停滚
                            return@setOnTouchListener true
                        }

                        /* 非滚动：记录坐标，排队定位 */
                        lastClickXY = floatArrayOf(event.x, event.y)
                        editText.post { placeCursorAccurately(editText) }
                        false   // 放行，系统长按计时继续
                    }
                    else -> false
                }
            }

            /* 3. 文本监听 */
            textWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    editEntity.value = s?.toString() ?: ""
                }
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            }
            editText.addTextChangedListener(textWatcher)
        }

        /* 真正放光标：layout 就绪 + 未进入长按选择 */
        private fun placeCursorAccurately(editText: EditText) {
            val xy = lastClickXY ?: return
            lastClickXY = null
            val ly = editText.layout
            if (ly == null) {          // layout 还没好，再排一次
                editText.post { placeCursorAccurately(editText) }
                return
            }
            val x = xy[0] - editText.totalPaddingLeft
            val y = xy[1] - editText.totalPaddingTop
            var offset = ly.getOffsetForHorizontal(ly.getLineForVertical(y.toInt()), x)
            if (offset < 0) offset = 0
            // 已长按选择 → 不覆盖
            if (editText.selectionStart != editText.selectionEnd) return
            editText.setSelection(offset, offset)
        }

        fun cleanup() {
            textWatcher?.let { binding.editText.removeTextChangedListener(it) }
            textWatcher = null
            lastClickXY = null
            binding.editText.setOnTouchListener(null)
        }
    }
}