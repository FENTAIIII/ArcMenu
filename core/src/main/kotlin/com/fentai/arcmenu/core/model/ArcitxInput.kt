package com.fentai.arcmenu.core.model

/** A native input editor with an independent text display and submit region. */
data class ArcitxInput(
    val textId: String,
    val submitId: String,
    val title: String = "Enter text",
    val initial: String = "",
    val maxLength: Int = 50,
    val mask: String = "",
    val display: ArcitxDisplay = ArcitxDisplay(),
    val caret: ArcitxCaret = ArcitxCaret(),
)

data class ArcitxDisplay(
    val before: String = "&7Click to enter text",
    val editing: String = "{input}{caret}",
    val draft: String = "{input}",
    val complete: String = "{input}",
)

data class ArcitxCaret(val content: String = "_", val interval: Int = 10)
