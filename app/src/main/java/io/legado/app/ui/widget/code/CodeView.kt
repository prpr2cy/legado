package io.legado.app.ui.widget.code

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.*
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.widget.TextView
import androidx.annotation.ColorInt
import io.legado.app.ui.widget.text.ScrollMultiAutoCompleteTextView
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Suppress("unused")
class CodeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ScrollMultiAutoCompleteTextView(context, attrs) {

    private var tabWidth = 0
    private var tabWidthInCharacters = 0
    private var mUpdateDelayTime = 500
    private var modified = true
    private var highlightWhileTextChanging = true
    private var hasErrors = false
    private var mRemoveErrorsWhenTextChanged = true
    private val mUpdateHandler = Handler(Looper.getMainLooper())
    private var mAutoCompleteTokenizer: Tokenizer? = null
    private val displayDensity = resources.displayMetrics.density
    private val mErrorHashSet: SortedMap<Int, Int> = TreeMap()
    private val mSyntaxPatternMap: MutableMap<Pattern, Int> = HashMap()
    private var mIndentCharacterList = mutableListOf('{', '+', '-', '*', '/', '=')

    // ========== Android 8.0-8.1 安全操作 ==========
    private val isAndroid8Bug = Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.O_MR1
    private val maxDeleteChunkSize = 150  // 必须小于200，安全起见用150

    // 安全操作队列
    private val safeOperationQueue = SafeReplace8(this)

    // TextWatcher中拦截大删除的标记
    private var pendingSafeDelete = false
    private var pendingStart = 0
    private var pendingDelete = 0

    // ========== 统一的复制到剪贴板方法 ==========
    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CodeView", text))
    }

    private fun getSelectedText(): String? {
        val text = text ?: return null
        val start = selectionStart
        val end = selectionEnd

        if (start < 0 || end < 0 || start == end) return null

        val min = min(start, end)
        val max = max(start, end)
        return text.subSequence(min, max).toString()
    }

    // ========== 上下文菜单操作 ==========
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (isAndroid8Bug) {
            return when (id) {
                android.R.id.copy -> handleCopy()
                android.R.id.cut -> handleCut()
                android.R.id.paste -> handlePaste()
                else -> super.onTextContextMenuItem(id)
            }
        }
        return super.onTextContextMenuItem(id)
    }

    // ========== 修复键盘删除键 ==========
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isAndroid8Bug) {
            if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                if (selectionStart != selectionEnd) {
                    val start = selectionStart
                    val end = selectionEnd
                    val min = min(start, end)
                    val max = max(start, end)

                    safeDelete(min, max - min)
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun handleCopy(): Boolean {
        val selectedText = getSelectedText() ?: return false
        copyToClipboard(selectedText)
        setSelection(selectionEnd)
        return true
    }

    private fun handleCut(): Boolean {
        val start = selectionStart
        val end = selectionEnd

        if (start < 0 || end < 0 || start == end) return false

        val mi = min(start, end)
        val mx = max(start, end)
        val txt = text?.subSequence(mi, mx)?.toString() ?: return false

        // 复制到剪贴板
        copyToClipboard(txt)

        // 安全删除选中文本
        safeDelete(mi, mx - mi)
        return true
    }

    private fun handlePaste(): Boolean {
        val clipboard = context.getSystemService(Context.CLIPboardManager::class.java) as ClipboardManager
        val clip = clipboard.primaryClip ?: return false
        val paste = clip.getItemAt(0).text?.toString() ?: return false

        val start = selectionStart
        val end = selectionEnd
        val mi = min(start, end)
        val mx = max(start, end)

        // 安全替换（删除选中文本并插入粘贴内容）
        safeReplace(mi, mx - mi, paste)
        return true
    }

    // ========== 安全操作方法 ==========

    /**
     * 8.0 安全删除：外部统一调它即可
     */
    private fun safeDelete(start: Int, length: Int) {
        if (!isAndroid8Bug || length <= maxDeleteChunkSize) {
            editableText.delete(start, start + length)
            setSelection(start)
        } else {
            safeOperationQueue.add(start, length, "")
        }
    }

    /**
     * 8.0 安全替换：删除+插入合二为一
     */
    private fun safeReplace(start: Int, deleteLen: Int, newText: String) {
        if (!isAndroid8Bug || (deleteLen <= maxDeleteChunkSize && newText.length <= maxDeleteChunkSize)) {
            editableText.replace(start, start + deleteLen, newText)
            setSelection(start + newText.length)
        } else {
            safeOperationQueue.add(start, deleteLen, newText)
        }
    }

    /**
     * 8.0 专用：把一次 >150 字符的 delete/insert 拆成多帧
     */
    private class SafeReplace8(private val codeView: CodeView) {

        private data class Task(
            val start: Int,
            val deleteLen: Int,
            val newText: String
        )

        private val tasks = ArrayDeque<Task>()
        private var running = false
        private val handler = Handler(Looper.getMainLooper())
        private val maxChunkSize = 150

        /**
         * 唯一入口：外部直接调用，线程安全
         */
        fun add(start: Int, deleteLen: Int, newText: String) {
            tasks += Task(start, deleteLen, newText)
            if (!running) next()
        }

        private fun next() {
            val task = tasks.pollFirst() ?: return
            running = true

            val editable = codeView.editableText
            var delDone = 0
            var addDone = 0

            val job = object : Runnable {
                override fun run() {
                    /* 1. 先分段删 */
                    if (delDone < task.deleteLen) {
                        val del = min(maxChunkSize, task.deleteLen - delDone)
                        editable.replace(task.start, task.start + del, "")
                        delDone += del
                        handler.post(this)
                        return
                    }

                    /* 2. 再分段插 */
                    if (addDone < task.newText.length) {
                        val piece = task.newText.substring(
                            addDone,
                            min(addDone + maxChunkSize, task.newText.length)
                        )
                        editable.insert(task.start, piece)
                        addDone += piece.length
                        handler.post(this)
                        return
                    }

                    /* 3. 本任务完成 */
                    running = false
                    codeView.setSelection(task.start + task.newText.length)
                    next()   // 继续下一个排队任务
                }
            }
            handler.post(job)
        }
    }

    // ========== 原有代码 ==========
    private val mUpdateRunnable = Runnable {
        val source = text
        highlightWithoutChange(source)
    }

    private val mEditorTextWatcher: TextWatcher = object : TextWatcher {

        private var changeStart = 0
        private var changeCount = 0

        override fun beforeTextChanged(
            source: CharSequence,
            start: Int,
            deleteCount: Int,
            addCount: Int
        ) {
            changeStart = start
            changeCount = addCount

            // 拦截大删除操作（8.0且一次性净删 >150）
            if (isAndroid8Bug && deleteCount > maxDeleteChunkSize && addCount < deleteCount) {
                pendingSafeDelete = true
                pendingStart = start
                pendingDelete = deleteCount
            }
        }

        override fun onTextChanged(
            source: CharSequence,
            start: Int,
            deleteCount: Int,
            addCount: Int
        ) {
            if (pendingSafeDelete) {
                pendingSafeDelete = false
                // 把系统删掉的先恢复，再自己慢慢删
                val deletedText = source.subSequence(start, start + deleteCount)
                editableText.insert(pendingStart, deletedText)
                safeDelete(pendingStart, pendingDelete)
                return
            }

            if (!modified) return

            if (highlightWhileTextChanging && mSyntaxPatternMap.isNotEmpty()) {
                convertTabs(editableText, start, addCount)
                mUpdateHandler.postDelayed(mUpdateRunnable, mUpdateDelayTime.toLong())
            }
            if (mRemoveErrorsWhenTextChanged) removeAllErrorLines()
        }

        override fun afterTextChanged(editable: Editable) {
            if (!highlightWhileTextChanging) {
                if (!modified) return
                cancelHighlighterRender()
                if (mSyntaxPatternMap.isNotEmpty()) {
                    convertTabs(editableText, changeStart, changeCount)
                    mUpdateHandler.postDelayed(mUpdateRunnable, mUpdateDelayTime.toLong())
                }
            }
        }
    }

    init {
        if (mAutoCompleteTokenizer == null) {
            mAutoCompleteTokenizer = KeywordTokenizer()
        }
        setTokenizer(mAutoCompleteTokenizer)
        filters = arrayOf(
            InputFilter { source, start, end, dest, dStart, dEnd ->
                if (modified && end - start == 1 && start < source.length && dStart < dest.length) {
                    val c = source[start]
                    if (c == '\n') {
                        return@InputFilter autoIndent(source, dest, dStart, dEnd)
                    }
                }
                source
            }
        )
        addTextChangedListener(mEditorTextWatcher)
    }

    override fun showDropDown() {
        val screenPoint = IntArray(2)
        getLocationOnScreen(screenPoint)
        val displayFrame = Rect()
        getWindowVisibleDisplayFrame(displayFrame)
        val position = selectionStart
        val layout = layout
        val line = layout.getLineForOffset(position)
        val verticalDistanceInDp = (750 + 140 * line) / displayDensity
        dropDownVerticalOffset = verticalDistanceInDp.toInt()
        val horizontalDistanceInDp = layout.getPrimaryHorizontal(position) / displayDensity
        dropDownHorizontalOffset = horizontalDistanceInDp.toInt()
        super.showDropDown()
    }

    private fun autoIndent(
        source: CharSequence,
        dest: Spanned,
        dStart: Int,
        dEnd: Int
    ): CharSequence {
        if (source.isNotEmpty() && source[0] != '\n') {
            return source
        }

        var lineStart = dStart - 1
        while (lineStart >= 0 && dest[lineStart] != '\n') {
            lineStart--
        }
        lineStart++

        var totalSpaces = 0
        var i = lineStart
        while (i < dStart) {
            when (dest[i]) {
                ' ' -> totalSpaces++
                '\t' -> totalSpaces += 2
                else -> break
            }
            i++
        }

        if (totalSpaces % 2 != 0) {
            totalSpaces = (totalSpaces / 2) * 2
        }

        return buildString {
            append(source)
            append(" ".repeat(totalSpaces))
        }
    }

    private fun highlightSyntax(editable: Editable) {
        if (mSyntaxPatternMap.isEmpty()) return
        for (pattern in mSyntaxPatternMap.keys) {
            val color = mSyntaxPatternMap[pattern]!!
            val m = pattern.matcher(editable)
            while (m.find()) {
                createForegroundColorSpan(editable, m, color)
            }
        }
    }

    private fun highlightErrorLines(editable: Editable) {
        if (mErrorHashSet.isEmpty()) return
        val maxErrorLineValue = mErrorHashSet.lastKey()
        var lineNumber = 0
        val matcher = PATTERN_LINE.matcher(editable)
        while (matcher.find()) {
            if (mErrorHashSet.containsKey(lineNumber)) {
                val color = mErrorHashSet[lineNumber]!!
                createBackgroundColorSpan(editable, matcher, color)
            }
            lineNumber += 1
            if (lineNumber > maxErrorLineValue) break
        }
    }

    private fun createForegroundColorSpan(
        editable: Editable,
        matcher: Matcher,
        @ColorInt color: Int
    ) {
        editable.setSpan(
            ForegroundColorSpan(color),
            matcher.start(), matcher.end(),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun createBackgroundColorSpan(
        editable: Editable,
        matcher: Matcher,
        @ColorInt color: Int
    ) {
        editable.setSpan(
            BackgroundColorSpan(color),
            matcher.start(), matcher.end(),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun highlight(editable: Editable): Editable {
        if (editable.length !in 1..1024) {
            return editable
        }
        try {
            clearSpans(editable)
            highlightErrorLines(editable)
            highlightSyntax(editable)
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
        return editable
    }

    private fun highlightWithoutChange(editable: Editable) {
        modified = false
        highlight(editable)
        modified = true
    }

    fun setTextHighlighted(text: CharSequence?) {
        if (text.isNullOrEmpty()) return
        cancelHighlighterRender()
        removeAllErrorLines()
        modified = false
        setText(highlight(SpannableStringBuilder(text)))
        modified = true
    }

    fun setTabWidth(characters: Int) {
        if (tabWidthInCharacters == characters) return
        tabWidthInCharacters = characters
        tabWidth = (paint.measureText("m") * characters).roundToInt()
    }

    private fun clearSpans(editable: Editable) {
        val length = editable.length
        val foregroundSpans = editable.getSpans(
            0, length, ForegroundColorSpan::class.java
        )
        run {
            var i = foregroundSpans.size
            while (i-- > 0) {
                editable.removeSpan(foregroundSpans[i])
            }
        }
        val backgroundSpans = editable.getSpans(
            0, length, BackgroundColorSpan::class.java
        )
        var i = backgroundSpans.size
        while (i-- > 0) {
            editable.removeSpan(backgroundSpans[i])
        }
    }

    fun cancelHighlighterRender() {
        mUpdateHandler.removeCallbacks(mUpdateRunnable)
    }

    private fun convertTabs(editable: Editable, start: Int, count: Int) {
        var startIndex = start
        if (tabWidth < 1) return
        val s = editable.toString()
        val stop = startIndex + count
        while (s.indexOf("\t", startIndex).also { startIndex = it } > -1 && startIndex < stop) {
            editable.setSpan(
                TabWidthSpan(),
                startIndex,
                startIndex + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ++startIndex
        }
    }

    fun setSyntaxPatternsMap(syntaxPatterns: Map<Pattern, Int>?) {
        if (mSyntaxPatternMap.isNotEmpty()) mSyntaxPatternMap.clear()
        mSyntaxPatternMap.putAll(syntaxPatterns!!)
    }

    fun addSyntaxPattern(pattern: Pattern, @ColorInt Color: Int) {
        mSyntaxPatternMap[pattern] = Color
    }

    fun removeSyntaxPattern(pattern: Pattern) {
        mSyntaxPatternMap.remove(pattern)
    }

    fun getSyntaxPatternsSize(): Int {
        return mSyntaxPatternMap.size
    }

    fun resetSyntaxPatternList() {
        mSyntaxPatternMap.clear()
    }

    fun setAutoIndentCharacterList(characterList: MutableList<Char>) {
        mIndentCharacterList = characterList
    }

    fun clearAutoIndentCharacterList() {
        mIndentCharacterList.clear()
    }

    fun getAutoIndentCharacterList(): List<Char> {
        return mIndentCharacterList
    }

    fun addErrorLine(lineNum: Int, color: Int) {
        mErrorHashSet[lineNum] = color
        hasErrors = true
    }

    fun removeErrorLine(lineNum: Int) {
        mErrorHashSet.remove(lineNum)
        hasErrors = mErrorHashSet.size > 0
    }

    fun removeAllErrorLines() {
        mErrorHashSet.clear()
        hasErrors = false
    }

    fun getErrorsSize(): Int {
        return mErrorHashSet.size
    }

    fun getTextWithoutTrailingSpace(): String {
        return PATTERN_TRAILING_WHITE_SPACE
            .matcher(text)
            .replaceAll("")
    }

    fun setAutoCompleteTokenizer(tokenizer: Tokenizer?) {
        mAutoCompleteTokenizer = tokenizer
    }

    fun setRemoveErrorsWhenTextChanged(removeErrors: Boolean) {
        mRemoveErrorsWhenTextChanged = removeErrors
    }

    fun reHighlightSyntax() {
        highlightSyntax(editableText)
    }

    fun reHighlightErrors() {
        highlightErrorLines(editableText)
    }

    fun isHasError(): Boolean {
        return hasErrors
    }

    fun setUpdateDelayTime(time: Int) {
        mUpdateDelayTime = time
    }

    fun getUpdateDelayTime(): Int {
        return mUpdateDelayTime
    }

    fun setHighlightWhileTextChanging(updateWhileTextChanging: Boolean) {
        highlightWhileTextChanging = updateWhileTextChanging
    }

    private inner class TabWidthSpan : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: FontMetricsInt?
        ): Int {
            return tabWidth
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
        }
    }

    companion object {
        private val PATTERN_LINE = Pattern.compile("(^.+$)+", Pattern.MULTILINE)
        private val PATTERN_TRAILING_WHITE_SPACE = Pattern.compile("[\\t ]+$", Pattern.MULTILINE)
    }
}