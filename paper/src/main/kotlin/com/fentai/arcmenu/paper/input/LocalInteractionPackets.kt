package com.fentai.arcmenu.paper.input

/** 26.1 split attacks out of Interact and replaced its action hierarchy with hand/location. */
internal class LocalInteractionPackets(private val interact: Class<*>, private val attack: Class<*>?) {
    data class Click(val entityId: Int, val primary: Boolean, val dispatch: Boolean)

    private val entityId = interact.getDeclaredField("entityId").apply { isAccessible = true }
    private val hand = interact.methods.firstOrNull { it.name == "hand" && it.parameterCount == 0 }
    private val action = if (hand == null) interact.getDeclaredField("action").apply { isAccessible = true } else null
    private val attackId = attack?.getMethod("entityId")

    fun read(packet: Any): Click? {
        if (attack?.isInstance(packet) == true) return Click(attackId!!.invoke(packet) as Int, true, true)
        if (!interact.isInstance(packet)) return null
        val id = entityId.getInt(packet)
        // Modern clients send ONE right-click packet including the hit position; unlike
        // 1.21.11 it is not a duplicate INTERACT_AT followed by an INTERACT packet.
        if (hand != null) return Click(id, false, (hand.invoke(packet) as Enum<*>).name == "MAIN_HAND")
        val value = action!!.get(packet)
        val kind = value.javaClass.getDeclaredMethod("getType").apply { isAccessible = true }.invoke(value) as Enum<*>
        val primary = kind.name == "ATTACK"
        val mainHand = primary || (value.javaClass.getDeclaredField("hand").apply { isAccessible = true }.get(value) as Enum<*>).name == "MAIN_HAND"
        return Click(id, primary, mainHand && kind.name != "INTERACT_AT")
    }
}
