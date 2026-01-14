package io.legado.app.utils

import androidx.core.view.WindowInsetsCompat

val WindowInsetsCompat.navigationBarHeight: Int
    get() = getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

val WindowInsetsCompat.imeHeight: Int
    get() = getInsets(WindowInsetsCompat.Type.ime()).bottom

val WindowInsetsCompat.systemBarsHeight: Int
    get() = getInsets(WindowInsetsCompat.Type.systemBars()).bottom

val WindowInsetsCompat.statusBarHeight: Int
    get() = getInsets(WindowInsetsCompat.Type.statusBars()).top