package com.pondledger.mr

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.eye.EyeTrackingData
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import kotlin.math.roundToInt

internal enum class EyeHealth { OFF, WAITING, LIVE, UNAVAILABLE, NEEDS_FULL_SPACE, NEEDS_PERMISSION, FAILED }
internal data class EyeReading(val health: EyeHealth, val ray: Aim? = null, val sampledAt: Long = 0L)
internal data class EyePanelPoint(val x: Float, val y: Float)
internal data class EyeReport(val health: EyeHealth, val point: EyePanelPoint? = null)

// Acquisition radii in workshop space. Hand reach and physical contact use their own geometry.
internal const val GAZE_RADIUS_MULTIPLIER = 2f

internal fun gazeTarget(ray: Aim, centers: List<Point>, radius: Float, visible: (Point) -> Boolean): Int? {
    var best: Int? = null
    var closestAngle = Float.POSITIVE_INFINITY
    var closestDepth = Float.POSITIVE_INFINITY
    for ((index, center) in centers.withIndex()) {
        val delta = center - ray.origin
        val depth = delta.dot(ray.forward)
        if (depth <= 0f || depth > 4.5f) continue
        val offset = delta - ray.forward * depth
        val missSquared = offset.dot(offset)
        if (missSquared > radius * radius || !visible(center)) continue
        // Overlapping volumes select the centre closest to the line of sight.
        val angle = missSquared / (depth * depth)
        if (angle < closestAngle || (angle == closestAngle && depth < closestDepth)) {
            best = index
            closestAngle = angle
            closestDepth = depth
        }
    }
    return best
}

internal fun gazeOnPanel(eye: Aim, panel: BenchPlacement, width: Float, height: Float): EyePanelPoint? {
    if (width <= 0f || height <= 0f) return null
    val ray = panel.localAim(eye)
    if (ray.forward.z >= -.001f) return null
    val depth = -ray.origin.z / ray.forward.z
    if (depth !in 0f..4.5f) return null
    val hit = ray.origin + ray.forward * depth
    val x = .5f + hit.x / width
    val y = .5f - hit.y / height
    if (x !in 0f..1f || y !in 0f..1f) return null
    return EyePanelPoint((x*1000).roundToInt()/1000f, (y*1000).roundToInt()/1000f)
}

internal class EyeCheckSequence {
    companion object { val targets=listOf(.19f,.50f,.81f) }
    private var step=0
    private var observedAt:Long?=null
    private var lostAt:Long?=null
    private var previousTick:Long?=null
    fun update(now:Long,live:Boolean,point:EyePanelPoint?,aspect:Float):Int {
        if(previousTick?.let{now-it !in 0L..160L}==true)observedAt=null
        previousTick=now
        if(!live){
            val since=lostAt ?: now.also{lostAt=it}
            if(now-since>=1200L)step=0
        }else lostAt=null
        val target=targets.getOrNull(step)
        if(!live || point==null || target==null || aspect<=0f)observedAt=null
        else{
            val dx=(point.x-target)*aspect
            val dy=point.y-.5f
            if(dx*dx+dy*dy<=.47f*.47f){
                val since=observedAt ?: now.also{observedAt=it}
                if(now-since>=280L){step++;observedAt=null}
            }else observedAt=null
        }
        return step
    }
}

/** The native stamp identifies a sample. Android's local monotonic clock supplies its age. */
internal class EyeSamples {
    private data class Frame(val stamp:Long, val receivedAt:Long, val ray:Aim?)
    @Volatile private var latest:Frame? = null
    @Synchronized fun offer(stamp:Long, receivedAt:Long, ray:Aim?) {
        val previous=latest
        if(stamp<=0 || ray==null){latest=Frame(stamp,receivedAt,null);return}
        if(previous!=null && previous.ray!=null){
            if(stamp==previous.stamp)return
            if(stamp<previous.stamp && receivedAt-previous.receivedAt in 0L..160L)return
        }
        latest=Frame(stamp,receivedAt,ray)
    }
    fun reading(now:Long):EyeReading? {
        val frame=latest ?: return null
        val ray=frame.ray ?: return null
        return if(now-frame.receivedAt in 0L..160L) EyeReading(EyeHealth.LIVE,ray,frame.receivedAt) else null
    }
}

/** Reads PICO's permission-gated eye pose and releases its native listener when the Stage loses focus. */
internal class ForgeEyeSensor : AutoCloseable {
    private var samples=EyeSamples()
    @Volatile private var provider: EyeTrackingProvider? = null
    private var listener: DataProvider.DataListener<EyeTrackingData>? = null
    private var wanted = false
    private var nextAttempt = 0L
    private var silentSince = -1L
    private var pollAt = 0L
    private var status = EyeHealth.WAITING

    fun enable(value: Boolean) {
        if (wanted == value) return
        wanted = value
        if (wanted) start(SystemClock.elapsedRealtime()) else close()
    }

    private fun start(now: Long) {
        nextAttempt = now + 3500L
        silentSince = -1L
        pollAt = 0L
        status = EyeHealth.WAITING
        try {
            val native = EyeTrackingProvider()
            provider = native
            val feed=EyeSamples()
            samples=feed
            val callback = DataProvider.DataListener<EyeTrackingData> { data ->
                if (provider === native) {
                    val pose=data.eyePose
                    feed.offer(data.timestamp,SystemClock.elapsedRealtime(),aim(pose.position,pose.rotation))
                }
            }
            listener = callback
            native.addListener(callback)
            native.start()
        } catch (e: Exception) {
            release()
            status = EyeHealth.FAILED
            Log.w("EmberForge", "Eye provider could not start", e)
        }
    }

    fun read(now: Long): EyeReading {
        if (!wanted) return EyeReading(EyeHealth.OFF)
        if (provider == null && now >= nextAttempt) start(now)
        val native = provider ?: return EyeReading(status)
        if (now >= pollAt) {
            pollAt = now + 180L
            status = try {
                when (native.supportState) {
                    DataProvider.SupportState.DEVICE_NOT_SUPPORTED -> EyeHealth.UNAVAILABLE
                    DataProvider.SupportState.NOT_IN_FULL_SPACE -> EyeHealth.NEEDS_FULL_SPACE
                    DataProvider.SupportState.WITHOUT_PERMISSION -> EyeHealth.NEEDS_PERMISSION
                    DataProvider.SupportState.SUPPORTED -> {
                        if (native.state == DataProvider.State.STARTED) EyeHealth.LIVE else {
                            // Full Space or permission can become available after the first start.
                            if (now >= nextAttempt) { nextAttempt=now+3500L; native.start() }
                            EyeHealth.WAITING
                        }
                    }
                    else -> EyeHealth.WAITING
                }
            } catch (_: Exception) { release(); nextAttempt=now+3500L; EyeHealth.FAILED }
        }
        if (status != EyeHealth.LIVE) { silentSince=-1L; return EyeReading(status) }
        val reading = samples.reading(now)
        if (reading != null) { silentSince=-1L; return reading }
        if (silentSince<0) silentSince=now
        if (now-silentSince>=12000L) { release(); start(now) }
        return EyeReading(EyeHealth.WAITING)
    }

    private fun release() {
        val native = provider
        provider = null
        listener?.let { l -> runCatching { native?.removeListener(l) } }
        runCatching { native?.stop() }
        listener = null
        samples=EyeSamples()
    }
    override fun close() {
        wanted = false
        release()
        status = EyeHealth.OFF
    }
}

internal const val BLADE_GAZE = 3
private const val BLADE_DWELL_MS = 88L // Half of 175 ms, rounded up to a whole millisecond.
private const val WORKSHOP_DWELL_MS = 220L
internal data class GazeMark(val point: Point, val facing: Point, val steps: Int, val small: Boolean)
internal class GazeDwell {
    var target = -1
        private set
    var steps = 0
        private set
    private var since = 0L
    private var last = 0L
    val locked get() = target >= 0 && steps == 8
    fun update(id: Int?, now: Long, eyeSampleAt: Long) {
        val next = id ?: -1
        if (next != target || now-last > 260L) since = now
        target = next
        last = now
        // A cached ray can stay visible for 160 ms. Repainting one sample must never lock a blade target.
        val elapsed = (if (next == BLADE_GAZE) eyeSampleAt else now) - since
        val duration = if (next == BLADE_GAZE) BLADE_DWELL_MS else WORKSHOP_DWELL_MS
        steps = if (next < 0) 0 else (elapsed.coerceIn(0L,duration)*8L / duration).toInt()
    }
    fun reset() { target = -1; steps = 0; last = 0L }
}
