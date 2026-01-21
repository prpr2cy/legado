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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.util.AttributeSet
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

    /* ---------- Android 8.0-8.1 安全文本编辑 ---------- */
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

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (!isAndroid8) return super.onTextContextMenuItem(id)
        when (id) {
            android.R.id.copy -> {
                val s = getSelectedText() ?: return super.onTextContextMenuItem(id)
                copyToClipboard(s)
                setSelection(selectionEnd)
                return true
            }
            android.R.id.cut -> {
                val s = getSelectedText() ?: return super.onTextContextMenuItem(id)
                copyToClipboard(s)
                editableText.replaceSafe(selectionStart, selectionEnd, "")
                setSelection(selectionEnd)
                return true
            }
            android.R.id.paste -> {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip ?: return super.onTextContextMenuItem(id)
                val paste = clip.getItemAt(0).coerceToText(context)
                editableText.replaceSafe(selectionStart, selectionEnd, paste)
                setSelection(selectionEnd)
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        return if (!isAndroid8 || connection == null) connection
        else SafeInputConnection(connection, true)
    }

    private inner class SafeInputConnection(
        target: InputConnection,
        mutable: Boolean
    ) : InputConnectionWrapper(target, mutable) {

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) {
                val start = selectionStart
                val end = selectionEnd
                val min = min(start, end)
                val max = max(start, end)
                if (min >= 0 && max >= 0) {
                    val deleteStart = max(0, min - beforeLength)
                    val deleteEnd = max + afterLength
                    if (deleteEnd - deleteStart > SAFE_CHUNK) {
                        editableText.deleteSafe(deleteStart, deleteEnd)
                        setSelection(deleteStart)
                        return true
                    }
                }
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
    }

    /* ---------- CodeView 原有代码 ---------- */
    private val mUpdateRunnable = Runnable {
        val source = text
        highlightWithoutChange(source)
    }

    private val mEditorTextWatcher: TextWatcher = object : TextWatcher {

        private var changeStart = 0
        private var changeCount = 0
        private var isSafeModified = false

        override fun beforeTextChanged(
            source: CharSequence,
            start: Int,
            deleteCount: Int,
            addCount: Int
        ) {
            changeStart = start
            changeCount = addCount
            if (isSafeModified || !isAndroid8 || deleteCount <= SAFE_CHUNK) return

            isSafeModified = true 
            editableText.replaceSafe(start, start + deleteCount, "")
            setSelection(start)
            throw CancelInputException()
        }

        override fun onTextChanged(
            source: CharSequence,
            start: Int,
            deleteCount: Int,
            addCount: Int
        ) {
            if (isSafeModified) {
                isSafeModified = false
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

/* ---------- Android 8.0-8.1 分段删除/插入 ---------- */
private val isAndroid8 = Build.VERSION.SDK_INT in 26..27
private const val SAFE_CHUNK = 150
private class CancelInputException: Exception()

private inline fun Editable.replaceSafe(start: Int, end: Int, text: CharSequence): Editable {
    val min = min(start, end)
    val max = max(start, end)
    if (!isAndroid8 || max - min + text.length <= SAFE_CHUNK) {
        replace(min, max, text)
        return this
    }
    /* 分段插入 */
    var pos = min
    for (i in text.indices step SAFE_CHUNK) {
        val chunk = text.substring(i, minOf(i + SAFE_CHUNK, text.length))
        replace(pos, pos, chunk)
        pos += chunk.length
    }
    /* 删掉旧尾巴 */
    deleteSafe(pos, pos + (max - min))
    return this
}

private fun Editable.deleteSafe(start: Int, end: Int) {
    val min = min(start, end)
    val max = max(start, end)
    if (!isAndroid8 || max - min <= SAFE_CHUNK) {
        delete(min, max)
        return
    }
    var cur = max
    while (cur > min) {
        val left = maxOf(min, cur - SAFE_CHUNK)
        delete(left, cur)
        cur = left
    }
}