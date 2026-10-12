package com.fentai.arcmenu.paper.util

import java.util.Locale

fun decimal(value: Double, places: Int = 2): String =
    String.format(Locale.ROOT, "%.${places}f", value)
