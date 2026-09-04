package com.fentai.arcmenu.paper.input

import kotlin.math.floor

/**
 * Entity spawn/move packets encode rotations as one signed byte. The client
 * therefore renders only these 256 angles, even when Bukkit stores a float.
 */
internal fun networkCameraAngle(degrees: Float): Float {
    val packed = floor((degrees * 256.0f / 360.0f).toDouble()).toInt().toByte()
    return packed.toInt() * CAMERA_ANGLE_STEP
}

private const val CAMERA_ANGLE_STEP = 360.0f / 256.0f
