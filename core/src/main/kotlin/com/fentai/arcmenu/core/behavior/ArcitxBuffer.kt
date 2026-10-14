package com.fentai.arcmenu.core.behavior

import com.fentai.arcmenu.core.model.ArcitxInput

enum class ArcitxState { BEFORE, EDITING, DRAFT, COMPLETE }

/** Mode-independent draft storage. Closing the native editor never commits its text. */
class ArcitxBuffer(val spec: ArcitxInput) {
    var state: ArcitxState = ArcitxState.BEFORE
        private set
    var draft: String = spec.initial
        private set
    var committedValue: String? = null
        private set
    private var edited = false
    private var previousState = ArcitxState.BEFORE

    val displayTemplate: String
        get() = when (state) {
            ArcitxState.BEFORE -> spec.display.before
            ArcitxState.EDITING -> spec.display.editing
            ArcitxState.DRAFT -> spec.display.draft
            ArcitxState.COMPLETE -> spec.display.complete
        }

    init {
        require(spec.maxLength in 1..50) { "Arcitx maxLength must be between 1 and 50" }
        require(spec.mask.isEmpty() || spec.mask.codePointCount(0, spec.mask.length) == 1) {
            "Arcitx mask must be empty or one Unicode code point"
        }
        draft = limit(spec.initial)
    }

    fun openEditor(): String {
        if (state != ArcitxState.EDITING) {
            previousState = state
            edited = false
        }
        state = ArcitxState.EDITING
        return draft
    }

    /** Returns whether an accepted editor update changed the draft. */
    fun update(value: String): Boolean {
        if (state != ArcitxState.EDITING) return false
        val next = limit(value)
        if (draft == next) return false
        draft = next
        edited = true
        return true
    }

    fun closeEditor(): Boolean {
        if (state != ArcitxState.EDITING) return false
        state = if (edited) ArcitxState.DRAFT else previousState
        return true
    }

    /** Only the independent submit region may call this after the editor is closed. */
    fun commit(): String? {
        if (state == ArcitxState.EDITING) return null
        committedValue = draft
        state = ArcitxState.COMPLETE
        return draft
    }

    fun display(caretVisible: Boolean = true): String {
        val input = if (spec.mask.isEmpty()) draft else spec.mask.repeat(draft.codePointCount(0, draft.length))
        val caret = if (state == ArcitxState.EDITING && caretVisible) spec.caret.content else ""
        // Replace template tokens in one pass so user-entered tokens stay literal.
        return TOKEN.replace(displayTemplate) { if (it.value == "{input}") input else caret }
    }

    /** Matches the native anvil's UTF-16 limit without cutting a supplementary character in half. */
    private fun limit(value: String): String {
        if (value.length <= spec.maxLength) return value
        var end = spec.maxLength
        if (value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        return value.substring(0, end)
    }

    companion object {
        private val TOKEN = Regex("\\{(?:input|caret)}")
    }
}
