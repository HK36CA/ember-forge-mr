package com.pondledger.mr

import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.world.WorldAnchor
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal object WorkshopRoom {
    private fun observed(anchor: WorldAnchor): BenchPlacement? {
        val pose = anchor.transform
        val p = pose.position
        val q = pose.quaternion
        val rotation = HammerRotation.normalized(q.x,q.y,q.z,q.w) ?: return null
        val forward = rotation.rotate(Point(0f,0f,-1f))
        val center = Point(p.x,p.y,p.z)
        if (!center.valid() || rotation.rotate(Point(0f,1f,0f)).y < .92f) return null
        return BenchPlacement(center,atan2(-forward.x,-forward.z))
    }
    fun relative(id: UUID, anchor: BenchPlacement, room: BenchPlacement): RoomReference {
        val delta = anchor.localPoint(room.center)
        return RoomReference(id,FurniturePose(delta.x,delta.y,delta.z,room.yaw-anchor.yaw))
    }
    fun recover(anchor: BenchPlacement, reference: RoomReference): BenchPlacement =
        BenchPlacement(anchor.world(reference.local.center),anchor.yaw+reference.local.yaw)

    fun different(a: BenchPlacement, b: BenchPlacement, distance: Float, radians: Float): Boolean {
        val delta = a.center - b.center
        val angle = a.yaw - b.yaw
        return delta.dot(delta) >= distance * distance || abs(atan2(sin(angle), cos(angle))) >= radians
    }

    /** Deliver poses on the caller's Stage coroutine. Native events can arrive on other threads. */
    suspend fun follow(reference: RoomReference, report: (BenchPlacement?) -> Unit) = coroutineScope {
        val events = Channel<BenchPlacement?>(Channel.CONFLATED)
        val nativeSample = AtomicBoolean(false)
        val subscription = WorldTrackingManager.subscribeAnchorUpdate { update ->
            if (update.anchor.anchorUUID == reference.id) {
                val room = when (update.event) {
                    AnchorUpdate.Event.REMOVED -> null
                    AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED, AnchorUpdate.Event.ADDED ->
                        observed(update.anchor)?.let { recover(it,reference) } ?: return@subscribeAnchorUpdate
                    else -> return@subscribeAnchorUpdate
                }
                nativeSample.set(true)
                events.trySend(room)
            }
        }
        try {
            launch {
                val initial = try {
                    withTimeoutOrNull(6500L) {
                        when (val r = WorldTrackingManager.loadAnchor(arrayOf(reference.id))) {
                            is WorldTrackingResult.Success -> r.data?.firstOrNull { it.anchorUUID == reference.id }
                                ?.let(::observed)?.let { recover(it,reference) }
                            is WorldTrackingResult.Error -> null
                        }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                // A direct load may finish after the runtime has published a newer pose or removal.
                if (!nativeSample.get()) report(initial)
            }
            for (room in events) {
                report(room)
            }
        } finally {
            subscription.cancel()
            events.cancel()
        }
    }
    suspend fun create(room: BenchPlacement): RoomReference? = withTimeoutOrNull(8500L) {
        when (val r = WorldTrackingManager.createAnchor(room.center.vector(),EulerAngles(0f,0f,0f),"EmberForgeWorkshop")) {
            is WorldTrackingResult.Error -> null
            is WorldTrackingResult.Success -> {
                val a = r.data ?: return@withTimeoutOrNull null
                val p = observed(a)
                if (p == null) { forget(a.anchorUUID); null } else relative(a.anchorUUID,p,room)
            }
        }
    }
    suspend fun forget(id: UUID) {
        try { withTimeoutOrNull(2500L) { WorldTrackingManager.removeAnchor(id) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* A removed or absent anchor is harmless. */ }
    }
}

/** The workshop controls can always be recalled even if the player put the board behind them. */
internal class WorkshopRecall {
    private var started = -1L
    private var latched = false
    fun reset() { started = -1; latched = false }
    fun update(pointers: List<Pointer>, now: Long): Boolean {
        fun pressed(id: Int): Boolean {
            val p = pointers.firstOrNull { it.id == id && it.joint?.valid() == true } ?: return false
            return ((if (id < 2) p.grip else p.press) ?: 0f) > .84f
        }
        val both = (pressed(0) && pressed(1)) || (pressed(2) && pressed(3))
        if (!both) { reset(); return false }
        if (started < 0) started = now
        if (latched || now-started < 1500L) return false
        latched = true
        return true
    }
    val consuming get() = started >= 0
}
