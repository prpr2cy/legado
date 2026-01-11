package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.*
import splitties.init.appCtx
import java.util.LinkedList
import java.util.Locale
import kotlin.math.min
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.collections.ArrayList

/**
 * 解析内容生成章节和页面
 */
@Suppress("DEPRECATION")
object ChapterProvider {
    //用于图片字的替换
    const val srcReplaceChar = "▩"

    //用于评论按钮的替换
    const val reviewChar = "▨"

    @JvmStatic
    private var beforeLineIsImage = false

    @JvmStatic
    private var isScroll = ReadBook.pageAnim() == 3

    @JvmStatic
    var viewWidth = 0
        private set

    @JvmStatic
    var viewHeight = 0
        private set

    @JvmStatic
    var paddingLeft = 0
        private set

    @JvmStatic
    var paddingTop = 0
        private set

    @JvmStatic
    var paddingRight = 0
        private set

    @JvmStatic
    var paddingBottom = 0
        private set

    @JvmStatic
    var visibleWidth = 0
        private set

    @JvmStatic
    var visibleHeight = 0
        private set

    @JvmStatic
    var visibleRight = 0
        private set

    @JvmStatic
    var visibleBottom = 0
        private set

    @JvmStatic
    var lineSpacingExtra = 0f
        private set

    @JvmStatic
    var paragraphSpacing = 0
        private set

    @JvmStatic
    var titleTopSpacing = 0
        private set

    @JvmStatic
    var titleBottomSpacing = 0
        private set

    @JvmStatic
    var indentCharWidth = 0f
        private set

    @JvmStatic
    var titlePaintTextHeight = 0f
        private set

    @JvmStatic
    var contentPaintTextHeight = 0f
        private set

    @JvmStatic
    var titlePaintFontMetrics = FontMetrics()

    @JvmStatic
    var contentPaintFontMetrics = FontMetrics()

    @JvmStatic
    var typeface: Typeface? = Typeface.DEFAULT
        private set

    @JvmStatic
    var titlePaint: TextPaint = TextPaint()

    @JvmStatic
    var contentPaint: TextPaint = TextPaint()

    @JvmStatic
    var reviewPaint: TextPaint = TextPaint()

    @JvmStatic
    var doublePage = false
        private set

    init {
        upStyle()
    }

    /**
     * 获取拆分完的章节数据
     */
    suspend fun getTextChapter(
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
        chapterSize: Int,
    ): TextChapter {
        val contents = bookContent.textList
        val textPages = arrayListOf<TextPage>()
        val stringBuilder = StringBuilder()
        var absStartX = paddingLeft
        var durY = 0f
        textPages.add(TextPage())
        if (ReadBookConfig.titleMode != 2 || bookChapter.isVolume) {
            //标题非隐藏
            displayTitle.splitNotBlank("\n").forEach { text ->
                setTypeText(
                    book, absStartX, durY,
                    if (AppConfig.enableReview) text + reviewChar else text,
                    textPages,
                    stringBuilder,
                    titlePaint,
                    titlePaintTextHeight,
                    titlePaintFontMetrics,
                    isTitle = true,
                    emptyContent = contents.isEmpty(),
                    isVolumeTitle = bookChapter.isVolume
                ).let {
                    absStartX = it.first
                    durY = it.second
                }
            }
            durY += titleBottomSpacing
        }
        contents.forEach { content ->
            if (book.getImageStyle().equals(Book.imgStyleText, true)) {
                //图片样式为文字嵌入类型
                var text = content.replace(srcReplaceChar, "▣")
                val srcList = LinkedList<String>()
                val sb = StringBuffer()
                val matcher = AppPattern.imgPattern.matcher(text)
                while (matcher.find()) {
                    matcher.group(1)?.let { src ->
                        srcList.add(src)
                        matcher.appendReplacement(sb, srcReplaceChar)
                    }
                }
                matcher.appendTail(sb)
                text = sb.toString()
                setTypeText(
                    book,
                    absStartX,
                    durY,
                    text,
                    textPages,
                    stringBuilder,
                    contentPaint,
                    contentPaintTextHeight,
                    contentPaintFontMetrics,
                    srcList = srcList
                ).let {
                    absStartX = it.first
                    durY = it.second
                }
            } else {
                val matcher = AppPattern.imgPattern.matcher(content)
                var start = 0
                while (matcher.find()) {
                    val text = content.substring(start, matcher.start())
                    if (text.isNotBlank()) {
                        setTypeText(
                            book,
                            absStartX,
                            durY,
                            text,
                            textPages,
                            stringBuilder,
                            contentPaint,
                            contentPaintTextHeight,
                            contentPaintFontMetrics
                        ).let {
                            absStartX = it.first
                            durY = it.second
                        }
                    }
                    setTypeImage(
                        book, matcher.group(1)!!,
                        absStartX, durY, textPages, stringBuilder, book.getImageStyle()
                    ).let {
                        absStartX = it.first
                        durY = it.second
                    }
                    start = matcher.end()
                }
                if (start < content.length) {
                    val text = content.substring(start, content.length)
                    if (text.isNotBlank()) {
                        setTypeText(
                            book, absStartX, durY,
                            if (AppConfig.enableReview) text + reviewChar else text,
                            textPages,
                            stringBuilder,
                            contentPaint,
                            contentPaintTextHeight,
                            contentPaintFontMetrics
                        ).let {
                            absStartX = it.first
                            durY = it.second
                        }
                    }
                }
            }
        }
        textPages.last().height = durY + 20.dpToPx()
        textPages.last().text = stringBuilder.toString()
        textPages.forEachIndexed { index, item ->
            item.index = index
            item.pageSize = textPages.size
            item.chapterIndex = bookChapter.index
            item.chapterSize = chapterSize
            item.title = displayTitle
            item.upLinesPosition()
        }

        return TextChapter(
            bookChapter,
            bookChapter.index, displayTitle,
            textPages, chapterSize,
            bookContent.sameTitleRemoved,
            bookChapter.isVip,
            bookChapter.isPay,
            bookContent.effectiveReplaceRules
        )
    }

    /**
     * 排版图片
     */
    private suspend fun setTypeImage(
        book: Book,
        src: String,
        x: Int,
        y: Float,
        textPages: ArrayList<TextPage>,
        stringBuilder: StringBuilder,
        imageStyle: String?,
    ): Pair<Int, Float> {
        var absStartX = x
        var durY = y
        if (doublePage && isScroll && !beforeLineIsImage) {
            val textPage = textPages.last()
            if (textPage.leftLineSize == 0) {
                textPage.leftLineSize = textPage.lineSize
            }
            if (textPage.height < durY) {
                textPage.height = durY
            }
            textPages.add(TextPage())
            durY = 0f
        }
        var doubleY = 0f
        var ratio = 1f
        val size = ImageProvider.getImageSize(book, src, ReadBook.bookSource)
        val disPlayWidth = if (isScroll && !appCtx.isPad) {
            if (viewWidth < viewHeight) {
                viewWidth - paddingLeft - paddingRight
            } else viewWidth / 2
        } else visibleWidth

        if (size.width > 0 && size.height > 0) {
            var height = size.height
            var width = size.width
            when (imageStyle?.toUpperCase(Locale.ROOT)) {
                Book.imgStyleFull -> {
                    width = disPlayWidth
                    height = width * size.height / size.width
                    if (height > visibleHeight && height.toFloat() < visibleHeight.toFloat() * 1.2f) {
                        height = visibleHeight
                        width = height * size.width / size.height
                    }
                    if (height > visibleHeight && size.width < disPlayWidth) {
                        // 原图宽度小于visibleWidth时，分页裁剪高度要按比例缩小
                        ratio = size.width.toFloat() / disPlayWidth
                    }
                }

                else -> {
                    if (size.width > disPlayWidth) {
                        width = disPlayWidth
                        height = width * size.height / size.width
                    }
                    if (height > visibleHeight && height.toFloat() < visibleHeight.toFloat() * 1.2f) {
                        height = visibleHeight
                        width = height * size.width / size.height
                    }
                }
            }

            // 计算水平居中位置
            val (start, end) = if (disPlayWidth > width) {
                val adjustWidth = (disPlayWidth.toFloat() - width.toFloat()) / 2f
                Pair(adjustWidth, adjustWidth + width.toFloat())
            } else {
                Pair(0f, width.toFloat())
            }

            val totalPages = ceil(height.toFloat() / visibleHeight).toInt()

            for (page in 0 until totalPages) {
                // 计算当前分段的高度
                val cropStartY = page * visibleHeight
                val cropEndY = min(cropStartY + visibleHeight, height)
                val segmentHeight = cropEndY - cropStartY

                // 检查当前页是否有足够空间
                if (durY + segmentHeight > visibleHeight) {
                    val textPage = textPages.last()

                    if (doublePage && !isScroll && absStartX < viewWidth / 2) {
                        //当前页面左列结束
                        textPage.leftLineSize = textPage.lineSize
                        absStartX = viewWidth / 2 + paddingLeft
                        doubleY = durY
                        durY = 0f
                    } else {
                        //当前页面结束
                        if (textPage.leftLineSize == 0) {
                            textPage.leftLineSize = textPage.lineSize
                        }
                        textPage.text = stringBuilder.toString().ifEmpty { "本页无文字内容" }
                        stringBuilder.clear()
                        textPages.add(TextPage())
                        doubleY += durY
                        if (textPage.height < doubleY) {
                            textPage.height = doubleY
                        }
                        absStartX = paddingLeft
                        doubleY = 0f
                        durY = 0f
                    }
                }

                // 创建图片行
                val textLine = TextLine(isImage = true)
                textLine.lineTop = durY + paddingTop
                durY += segmentHeight
                textLine.lineBottom = durY + paddingTop
                textLine.addColumn(
                    ImageColumn(
                        start = absStartX + start,
                        end = absStartX + end,
                        src = src,
                        totalPages = totalPages,
                        cropStartY = floor(ratio * cropStartY).toInt(),
                        cropEndY = floor(ratio * cropEndY).toInt(),
                        originalWidth = size.width,
                        originalHeight = size.height
                    )
                )
                AppLog.put("src=${src}, page=${page}, durY=${durY}, absStartX=${absStartX}, doublePage${doublePage}, isScroll=${isScroll}, disPlayWidth=${disPlayWidth}")
                calcTextLinePosition(textPages, textLine, stringBuilder.length)
                stringBuilder.append(" ") // 确保翻页时索引计算正确
                textPages.last().addLine(textLine)
            }
        }
        beforeLineIsImage = true
        return absStartX to doubleY + durY
    }

    /**
     * 排版文字
     */
    private suspend fun setTypeText(
        book: Book,
        x: Int,
        y: Float,
        text: String,
        textPages: ArrayList<TextPage>,
        stringBuilder: StringBuilder,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: FontMetrics,
        isTitle: Boolean = false,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
        srcList: LinkedList<String>? = null
    ): Pair<Int, Float> {
        var absStartX = x
        var durY = y
        if (doublePage && isScroll && beforeLineIsImage) {
            val textPage = textPages.last()
            if (textPage.leftLineSize == 0) {
                textPage.leftLineSize = textPage.lineSize
            }
            if (textPage.height < durY) {
                textPage.height = durY
            }
            textPages.add(TextPage())
            durY = 0f
        }
        if (beforeLineIsImage) {
            durY += textHeight * lineSpacingExtra / 2f
            beforeLineIsImage = false
        }
        val widthsArray = FloatArray(text.length)
        val layout = if (ReadBookConfig.useZhLayout) {
            ZhLayout(text, textPaint, visibleWidth, widthsArray)
        } else {
            textPaint.getTextWidths(text, widthsArray)
            StaticLayout(text, textPaint, visibleWidth, Layout.Alignment.ALIGN_NORMAL, 0f, 0f, true)
        }
        val widthsList = widthsArray.asList()
        durY = when {
            //标题y轴居中
            emptyContent && textPages.size == 1 -> {
                val textPage = textPages.last()
                if (textPage.lineSize == 0) {
                    val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                } else {
                    var textLayoutHeight = layout.lineCount * textHeight
                    val fistLine = textPage.getLine(0)
                    if (fistLine.lineTop < textLayoutHeight + titleTopSpacing) {
                        textLayoutHeight = fistLine.lineTop - titleTopSpacing
                    }
                    textPage.lines.forEach {
                        it.lineTop = it.lineTop - textLayoutHeight
                        it.lineBase = it.lineBase - textLayoutHeight
                        it.lineBottom = it.lineBottom - textLayoutHeight
                    }
                    durY - textLayoutHeight
                }
            }

            isTitle && textPages.size == 1 && textPages.last().lines.isEmpty() ->
                durY + titleTopSpacing

            else -> durY
        }

        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(isTitle = isTitle)
            if (durY + textHeight > visibleHeight) {
                val textPage = textPages.last()
                if (doublePage && absStartX < viewWidth / 2) {
                    textPage.leftLineSize = textPage.lineSize
                    absStartX = viewWidth / 2 + paddingLeft
                } else {
                    //当前页面结束,设置各种值
                    if (textPage.leftLineSize == 0) {
                        textPage.leftLineSize = textPage.lineSize
                    }
                    textPage.text = stringBuilder.toString()
                    textPage.height = durY
                    //新建页面
                    textPages.add(TextPage())
                    stringBuilder.clear()
                    absStartX = paddingLeft
                }
                durY = 0f
            }
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val words = text.substring(lineStart, lineEnd)
            val textWidths = widthsList.subList(lineStart, lineEnd)
            val desiredWidth = textWidths.sum()
            when {
                lineIndex == 0 && layout.lineCount > 1 && !isTitle -> {
                    //第一行 非标题
                    textLine.text = words
                    addCharsToLineFirst(
                        book, absStartX, textLine, words,
                        textPaint, desiredWidth, textWidths, srcList
                    )
                }

                lineIndex == layout.lineCount - 1 -> {
                    //最后一行
                    textLine.text = words
                    textLine.isParagraphEnd = true
                    //标题x轴居中
                    val startX = if (
                        isTitle &&
                        (ReadBookConfig.isMiddleTitle || emptyContent || isVolumeTitle)
                    ) {
                        (visibleWidth - desiredWidth) / 2
                    } else {
                        0f
                    }
                    addCharsToLineNatural(
                        book, absStartX, textLine, words, textPaint,
                        startX, !isTitle && lineIndex == 0, textWidths, srcList
                    )
                }

                else -> {
                    if (
                        isTitle &&
                        (ReadBookConfig.isMiddleTitle || emptyContent || isVolumeTitle)
                    ) {
                        //标题居中
                        val startX = (visibleWidth - desiredWidth) / 2
                        addCharsToLineNatural(
                            book, absStartX, textLine, words,
                            textPaint, startX, false, textWidths, srcList
                        )
                    } else {
                        //中间行
                        textLine.text = words
                        addCharsToLineMiddle(
                            book, absStartX, textLine, words,
                            textPaint, desiredWidth, 0f, textWidths, srcList
                        )
                    }
                }
            }
            val sbLength = stringBuilder.length
            stringBuilder.append(words)
            if (textLine.isParagraphEnd) {
                stringBuilder.append("\n")
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            textPages.last().addLine(textLine)
            textLine.upTopBottom(durY, textHeight, fontMetrics)
            durY += textHeight * lineSpacingExtra
            textPages.last().height = durY
        }
        durY += textHeight * paragraphSpacing.toFloat() / 10f
        return Pair(absStartX, durY)
    }

    private fun calcTextLinePosition(
        textPages: ArrayList<TextPage>,
        textLine: TextLine,
        sbLength: Int
    ) {
        val lastLine = textPages.last().lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.getOrNull(textPages.lastIndex - 1)?.lines?.lastOrNull { it.paragraphNum > 0 }
        val paragraphNum = when {
            lastLine == null -> 1
            lastLine.isParagraphEnd -> lastLine.paragraphNum + 1
            else -> lastLine.paragraphNum
        }
        textLine.paragraphNum = paragraphNum
        textLine.chapterPosition =
            (textPages.getOrNull(textPages.lastIndex - 1)?.lines?.lastOrNull()?.run {
                chapterPosition + charSize + if (isParagraphEnd) 1 else 0
            } ?: 0) + sbLength
        textLine.pagePosition = sbLength
    }

    /**
     * 有缩进,两端对齐
     */
    private suspend fun addCharsToLineFirst(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        text: String,
        textPaint: TextPaint,
        /**自然排版长度**/
        desiredWidth: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?
    ) {
        var x = 0f
        if (!ReadBookConfig.textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, text, textPaint,
                x, true, textWidths, srcList
            )
            return
        }
        val bodyIndent = ReadBookConfig.paragraphIndent
        for (char in bodyIndent.toStringArray()) {
            val x1 = x + indentCharWidth
            textLine.addColumn(
                TextColumn(
                    charData = char,
                    start = absStartX + x,
                    end = absStartX + x1
                )
            )
            x = x1
            textLine.indentWidth = x
        }
        if (text.length > bodyIndent.length) {
            val text1 = text.substring(bodyIndent.length, text.length)
            val textWidths1 = textWidths.subList(bodyIndent.length, textWidths.size)
            addCharsToLineMiddle(
                book, absStartX, textLine, text1,
                textPaint, desiredWidth, x, textWidths1, srcList
            )
        }
    }

    /**
     * 无缩进,两端对齐
     */
    private suspend fun addCharsToLineMiddle(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        text: String,
        textPaint: TextPaint,
        /**自然排版长度**/
        desiredWidth: Float,
        /**起始x坐标**/
        startX: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?
    ) {
        if (!ReadBookConfig.textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, text, textPaint,
                startX, false, textWidths, srcList
            )
            return
        }
        val residualWidth = visibleWidth - desiredWidth
        val spaceSize = text.count { it == ' ' }
        val (words, widths) = getStringArrayAndTextWidths(text, textWidths, textPaint)
        if (spaceSize > 1) {
            val d = residualWidth / spaceSize
            var x = startX
            words.forEachIndexed { index, char ->
                val cw = widths[index]
                val x1 = if (char == " ") {
                    if (index != words.lastIndex) (x + cw + d) else (x + cw)
                } else {
                    (x + cw)
                }
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList
                )
                x = x1
            }
        } else {
            val gapCount: Int = words.lastIndex
            val d = residualWidth / gapCount
            var x = startX
            words.forEachIndexed { index, char ->
                val cw = widths[index]
                val x1 = if (index != words.lastIndex) (x + cw + d) else (x + cw)
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList
                )
                x = x1
            }
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 自然排列
     */
    private suspend fun addCharsToLineNatural(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        text: String,
        textPaint: TextPaint,
        startX: Float,
        hasIndent: Boolean,
        textWidths: List<Float>,
        srcList: LinkedList<String>?
    ) {
        val indentLength = ReadBookConfig.paragraphIndent.length
        var x = startX
        val (words, widths) = getStringArrayAndTextWidths(text, textWidths, textPaint)
        words.forEachIndexed { index, char ->
            val cw = widths[index]
            val x1 = x + cw
            addCharToLine(book, absStartX, textLine, char, x, x1, index + 1 == words.size, srcList)
            x = x1
            if (hasIndent && index == indentLength - 1) {
                textLine.indentWidth = x
            }
        }
        exceed(absStartX, textLine, words)
    }

    fun getStringArrayAndTextWidths(
        text: String,
        textWidths: List<Float>,
        textPaint: TextPaint
    ): Pair<List<String>, List<Float>> {
        val charArray = text.toCharArray()
        val strList = ArrayList<String>()
        val textWidthList = ArrayList<Float>()
        val lastIndex = charArray.lastIndex
        for (i in textWidths.indices) {
            if (charArray[i].isLowSurrogate()) {
                continue
            }
            val char = if (i + 1 <= lastIndex && charArray[i + 1].isLowSurrogate()) {
                charArray[i].toString() + charArray[i + 1].toString()
            } else {
                charArray[i].toString()
            }
            val w = textWidths[i]
            if (w == 0f && textWidthList.size > 0) {
                textWidthList[textWidthList.lastIndex] = textPaint.measureText(strList.last())
                textWidthList.add(textPaint.measureText(char))
            } else {
                textWidthList.add(w)
            }
            strList.add(char)
        }
        return strList to textWidthList
    }

    /**
     * 添加字符
     */
    private suspend fun addCharToLine(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        char: String,
        xStart: Float,
        xEnd: Float,
        isLineEnd: Boolean,
        srcList: LinkedList<String>?
    ) {
        val column = when {
            srcList != null && char == srcReplaceChar -> {
                val src = srcList.removeFirst()
                ImageProvider.cacheImage(book, src, ReadBook.bookSource)
                ImageColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    src = src
                )
            }

            isLineEnd && char == reviewChar -> {
                ReviewColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    count = 100
                )
            }

            else -> {
                TextColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    charData = char
                )
            }
        }
        textLine.addColumn(column)
    }

    /**
     * 超出边界处理
     */
    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        val visibleEnd = absStartX + visibleWidth
        val endX = textLine.columns.lastOrNull()?.end ?: return
        if (endX > visibleEnd) {
            val cc = (endX - visibleEnd) / words.size
            for (i in 0..words.lastIndex) {
                textLine.getColumnReverseAt(i).let {
                    val py = cc * (words.size - i)
                    it.start = it.start - py
                    it.end = it.end - py
                }
            }
        }
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        typeface = getTypeface(ReadBookConfig.textFont)
        getPaints(typeface).let {
            titlePaint = it.first
            contentPaint = it.second
            reviewPaint.color = contentPaint.color
            reviewPaint.textSize = contentPaint.textSize * 0.45f
            reviewPaint.textAlign = Paint.Align.CENTER
        }
        //间距
        lineSpacingExtra = ReadBookConfig.lineSpacingExtra / 10f
        paragraphSpacing = ReadBookConfig.paragraphSpacing
        titleTopSpacing = ReadBookConfig.titleTopSpacing.dpToPx()
        titleBottomSpacing = ReadBookConfig.titleBottomSpacing.dpToPx()
        val bodyIndent = ReadBookConfig.paragraphIndent
        indentCharWidth = StaticLayout.getDesiredWidth(bodyIndent, contentPaint) / bodyIndent.length
        titlePaintTextHeight = titlePaint.textHeight
        contentPaintTextHeight = contentPaint.textHeight
        titlePaintFontMetrics = titlePaint.fontMetrics
        contentPaintFontMetrics = contentPaint.fontMetrics
        upLayout()
    }

    private fun getTypeface(fontPath: String): Typeface? {
        return kotlin.runCatching {
            when {
                fontPath.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    appCtx.contentResolver
                        .openFileDescriptor(Uri.parse(fontPath), "r")!!
                        .use {
                            Typeface.Builder(it.fileDescriptor).build()
                        }
                }

                fontPath.isContentScheme() -> {
                    Typeface.createFromFile(RealPathUtil.getPath(appCtx, Uri.parse(fontPath)))
                }

                fontPath.isNotEmpty() -> Typeface.createFromFile(fontPath)
                else -> when (AppConfig.systemTypefaces) {
                    1 -> Typeface.SERIF
                    2 -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
            }
        }.getOrElse {
            ReadBookConfig.textFont = ""
            ReadBookConfig.save()
            Typeface.SANS_SERIF
        } ?: Typeface.DEFAULT
    }

    private fun getPaints(typeface: Typeface?): Pair<TextPaint, TextPaint> {
        // 字体统一处理
        val bold = Typeface.create(typeface, Typeface.BOLD)
        val normal = Typeface.create(typeface, Typeface.NORMAL)
        val (titleFont, textFont) = when (ReadBookConfig.textBold) {
            1 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(Typeface.create(typeface, 900, false), bold)
                else
                    Pair(bold, bold)
            }

            2 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(normal, Typeface.create(typeface, 300, false))
                else
                    Pair(normal, normal)
            }

            else -> Pair(bold, normal)
        }

        //标题
        val tPaint = TextPaint()
        tPaint.color = ReadBookConfig.textColor
        tPaint.letterSpacing = ReadBookConfig.letterSpacing
        tPaint.typeface = titleFont
        tPaint.textSize = with(ReadBookConfig) { textSize + titleSize }.toFloat().spToPx()
        tPaint.isAntiAlias = true
        //正文
        val cPaint = TextPaint()
        cPaint.color = ReadBookConfig.textColor
        cPaint.letterSpacing = ReadBookConfig.letterSpacing
        cPaint.typeface = textFont
        cPaint.textSize = ReadBookConfig.textSize.toFloat().spToPx()
        cPaint.isAntiAlias = true
        return Pair(tPaint, cPaint)
    }

    /**
     * 更新View尺寸
     */
    fun upViewSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && (width != viewWidth || height != viewHeight)) {
            viewWidth = width
            viewHeight = height
            upLayout()
            postEvent(EventBus.UP_CONFIG, true)
        }
    }

    /**
     * 更新绘制尺寸
     */
    fun upLayout() {
        isScroll = ReadBook.pageAnim() == 3

        when (AppConfig.doublePageHorizontal) {
            "0" -> doublePage = false
            "1" -> doublePage = true
            "2" -> {
                doublePage = (viewWidth > viewHeight) && !isScroll
            }

            "3" -> {
                doublePage = (viewWidth > viewHeight || appCtx.isPad) && !isScroll
            }
        }

        if (viewWidth > 0 && viewHeight > 0) {
            paddingLeft = ReadBookConfig.paddingLeft.dpToPx()
            paddingTop = ReadBookConfig.paddingTop.dpToPx()
            paddingRight = ReadBookConfig.paddingRight.dpToPx()
            paddingBottom = ReadBookConfig.paddingBottom.dpToPx()
            visibleWidth = if (doublePage) {
                viewWidth / 2 - paddingLeft - paddingRight
            } else {
                viewWidth - paddingLeft - paddingRight
            }

            //留1dp画最后一行下划线
            visibleHeight = viewHeight - paddingTop - paddingBottom
            visibleRight = viewWidth - paddingRight
            visibleBottom = paddingTop + visibleHeight

            if (paddingLeft >= visibleRight || paddingTop >= visibleBottom) {
                appCtx.toastOnUi("边距设置过大，请重新设置")
            }
        }
    }

}
