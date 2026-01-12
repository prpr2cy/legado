package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
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

    /* 当前是否正在滚动，供 TouchListener 实时查询 */
    var recyclerViewIsScrolling = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            // 如果后续需要立即通知所有 Holder 刷新状态，可在这里 notify
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val binding = ItemSourceEditBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        binding.editText.addLegadoPattern()
        binding.editText.addJsonPattern()
        binding.editText.addJsPattern()
        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.bind(editEntities[position])
    }

    override fun getItemCount(): Int = editEntities.size

    override fun onViewRecycled(holder: MyViewHolder) {
        super.onViewRecycled(holder)
        holder.cleanup()
    }

    inner class MyViewHolder(private val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var textWatcher: TextWatcher? = null

        /* TouchListener 只构造一次 */
        private val touchListener by lazy {
            OnEditTouchListener { recyclerViewIsScrolling }
        }

        fun bind(editEntity: EditEntity) = binding.run {
            /* 1. 基本绑定 */
            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine

            /* 2. 清理旧监听器 */
            cleanup()

            /* 3. TouchListener 只设一次 */
            if (editText.getTag(R.id.tag_touch) == null) {
                editText.setOnTouchListener(touchListener)
                editText.setTag(R.id.tag_touch, touchListener)
            }

            /* 4. 数据与 hint */
            editText.setText(editEntity.value)
            textInputLayout.hint = editEntity.hint

            /* 5. 新 TextWatcher */
            textWatcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence,
                    start: Int,
                    before: Int,
                    count: Int
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    editEntity.value = s?.toString() ?: ""
                }
            }
            editText.addTextChangedListener(textWatcher!!)
        }

        fun cleanup() {
            // 清理文本监听器
            textWatcher?.let {
                binding.editText.removeTextChangedListener(it)
                textWatcher = null
            }
        }
    }

    /* 真正的 Touch 处理类，内部实时读取最新的 scrolling 状态 */
    private inner class OnEditTouchListener(
        private val scrollingProvider: () -> Boolean
    ) : View.OnTouchListener {

        override fun onTouch(v: View?, event: MotionEvent?): Boolean {
            if (event?.actionMasked != MotionEvent.ACTION_DOWN) return false
            if (scrollingProvider()) {
                (v?.parent?.parent as? RecyclerView)?.stopScroll()
                return true
            }

            val editText = v as? io.legado.app.ui.widget.text.AdaptiveTextInputEditText ?: return false
            val x = event.x.toInt() - editText.totalPaddingLeft
            val y = event.y.toInt() - editText.totalPaddingTop
            val layout = editText.layout ?: return false
            val line = layout.getLineForVertical(y)
            val offset = layout.getOffsetForHorizontal(line, x.toFloat())
                .coerceIn(0, editText.text?.length ?: 0)

            editText.post {
                editText.requestFocus()
                editText.setSelection(offset)
            }
            return false
        }
    }
}