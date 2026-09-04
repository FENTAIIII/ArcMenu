package com.fentai.arcmenu.paper.input

/** Releases mouse-mode rendering only for the acknowledgement of its own activation sync. */
internal class CameraActivationGate {
    private var expectedTeleportId: Int? = null
    private var ready = false

    @Synchronized
    fun expect(teleportId: Int) {
        expectedTeleportId = teleportId
        ready = false
    }

    @Synchronized
    fun accept(teleportId: Int): Boolean {
        if (teleportId != expectedTeleportId) return false
        expectedTeleportId = null
        ready = true
        return true
    }

    @Synchronized
    fun isReady(): Boolean = ready
}
