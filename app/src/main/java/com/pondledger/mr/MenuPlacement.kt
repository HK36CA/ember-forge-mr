package com.pondledger.mr

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal val menuDistances = listOf(.45f, .56f, .72f)
internal const val defaultMenuDistance = .56f

/** Capture the viewer's horizontal facing once when a menu opens. Keep it still for tapping. */
internal fun menuPlacement(head: Aim, distance: Float, editing: Boolean, previousYaw: Float): BenchPlacement? {
    if (!head.origin.valid() || !head.forward.valid()) return null
    val horizontal = head.forward.x * head.forward.x + head.forward.z * head.forward.z
    val yaw = if (horizontal > .04f) atan2(-head.forward.x, -head.forward.z) else previousYaw
    val angle = if (editing) yaw - .28f else yaw
    val reach = distance + if (editing) .04f else 0f
    val at = head.origin + Point(-sin(angle) * reach, if (editing) -.05f else -.035f, -cos(angle) * reach)
    return BenchPlacement(at, angle)
}
