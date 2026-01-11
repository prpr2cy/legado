package io.legado.app.ui.widget.text

import splitties.init.appCtx

data class EditEntity(
    var key: String,
    var value: String?,
    var hint: String,
    val viewType: Int = 0,
    var cursor: Int = 0 // 新增：保存光标位置
) {

    constructor(
        key: String,
        value: String?,
        hint: Int,
        viewType: Int = 0
    ) : this(
        key,
        value,
        appCtx.getString(hint),
        viewType
    )

    constructor(
        key: String,
        value: String?,
        hint: Int,
        viewType: Int = 0,
        cursor: Int = 0 // 新增带cursor参数的构造方法
    ) : this(
        key,
        value,
        appCtx.getString(hint),
        viewType,
        cursor
    )

    @Suppress("unused")
    object ViewType {

        const val checkBox = 1

    }

}