package io.legado.app.ui.book.read.page.entities.column

import androidx.annotation.Keep

/**
 * 图片列
 */
@Keep
data class ImageColumn(
    override var start: Float,
    override var end: Float,
    var src: String,
    var totalPages: Int = 0,
    var cropStartY: Int = 0,
    var cropEndY: Int = 0,
    var originalWidth: Int = 0,
    var originalHeight: Int = 0
) : BaseColumn