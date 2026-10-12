package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.behavior.ActionReaction
import com.fentai.arcmenu.core.model.InteractionRegion

/** Tracks backend targets independently of tooltip and cursor refreshes. */
internal class MenuHoverState {
    private var current: InteractionRegion? = null
    private var revision = 0L

    fun changed(region: InteractionRegion?): Boolean = current?.id != region?.id

    fun update(
        region: InteractionRegion?,
        isActive: () -> Boolean,
        accepts: (InteractionRegion) -> Boolean,
        execute: (List<ActionReaction>) -> Unit,
    ): Boolean {
        if (!isActive()) return false
        if (!changed(region)) return true
        val previous = current
        current = region
        val expectedRevision = ++revision
        // Commit first: callbacks may navigate, close, refresh, or cause another target change.
        if (previous != null && previous.onLeave.isNotEmpty() && accepts(previous)) execute(previous.onLeave)
        if (!isActive() || revision != expectedRevision) return false
        if (region != null && region.onHover.isNotEmpty() && accepts(region)) execute(region.onHover)
        return isActive() && revision == expectedRevision
    }
}
