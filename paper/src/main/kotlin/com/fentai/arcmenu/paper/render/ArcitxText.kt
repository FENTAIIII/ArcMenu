package com.fentai.arcmenu.paper.render

import com.fentai.arcmenu.core.behavior.ArcitxBuffer
import com.fentai.arcmenu.core.behavior.ArcitxState
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/** Resolve authored templates first, then insert the buffer as literal text. */
internal object ArcitxText {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun render(buffer: ArcitxBuffer, caretVisible: Boolean, resolve: (String) -> String): Component {
        val spec = buffer.spec
        val input = if (spec.mask.isEmpty()) buffer.draft
            else spec.mask.repeat(buffer.draft.codePointCount(0, buffer.draft.length))
        val caret = if (buffer.state == ArcitxState.EDITING && caretVisible) spec.caret.content else ""
        return legacy.deserialize(resolve(buffer.displayTemplate)).replaceText { replacement ->
            replacement.match("\\{(?:input|caret)}").replacement { match, text ->
                if (match.group() == "{input}") text.content(input).build() else legacy.deserialize(caret)
            }
        }
    }
}
