package io.legado.app.utils

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat

val WindowInsetsCompat.navigationBarHeight: Int
    get() = getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

val WindowInsetsCompat.imeHeight: Int
    get() = getInsets(WindowInsetsCompat.Type.ime()).bottom

val WindowInsetsCompat.systemBarsHeight: Int
    get() = getInsets(WindowInsetsCompat.Type.systemBars()).bottom

val WindowInsets.navigationBarHeight: Int
    get() = WindowInsetsCompat.toWindowInsetsCompat(this).getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

val WindowInsets.imeHeight: Int
    get() = WindowInsetsCompat.toWindowInsetsCompat(this).getInsets(WindowInsetsCompat.Type.ime()).bottom

val WindowInsets.systemBarsHeight: Int
    get() = WindowInsetsCompat.toWindowInsetsCompat(this).getInsets(WindowInsetsCompat.Type.systemBars()).bottom