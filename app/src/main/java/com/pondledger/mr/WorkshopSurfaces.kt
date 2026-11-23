package com.pondledger.mr

import com.pico.spatial.sense.base.SemanticLabelType
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneOrientation
import com.pico.spatial.sense.plane.PlaneTrackingManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

internal data class SurfaceSnap(val layout: WorkshopLayout?, val message: String)
internal enum class SupportLabel { TABLE, CABINET, UNKNOWN, FLOOR }
internal data class SupportPatch(val label: SupportLabel, val worldVertices: List<Point>, val indices: List<Int>)

private val surfaceScan = Mutex()
private const val MAX_ANCHORS = 256
private const val MAX_VERTICES = 16_384
private const val MAX_INDICES = 49_152

/** Native vertices belong to the anchor, including its non-uniform scale. */
private fun PlaneAnchor.supportPatch(): SupportPatch? {
    if (planeOrientation != PlaneOrientation.HORIZONTAL_UPWARD) return null
    val label = when (semantics) {
        SemanticLabelType.TABLE -> SupportLabel.TABLE
        SemanticLabelType.CABINET -> SupportLabel.CABINET
        SemanticLabelType.FLOOR -> SupportLabel.FLOOR
        SemanticLabelType.UNKNOWN -> SupportLabel.UNKNOWN
        else -> return null
    }
    val localVertices = vertices
    val ids = indices
    if (localVertices.size !in 3..MAX_VERTICES || ids.size !in 3..MAX_INDICES || ids.size % 3 != 0) return null
    val pose = transform
    val position = pose.position
    val scale = pose.scale
    val rotation = pose.quaternion
    val length = sqrt(rotation.x*rotation.x + rotation.y*rotation.y + rotation.z*rotation.z + rotation.w*rotation.w)
    if (!length.isFinite() || length < .001f || !scale.x.isFinite() || !scale.y.isFinite() || !scale.z.isFinite() ||
        minOf(abs(scale.x), abs(scale.y), abs(scale.z)) < .00001f) return null
    val q = Point(rotation.x/length, rotation.y/length, rotation.z/length)
    val w = rotation.w/length
    val origin = Point(position.x, position.y, position.z)
    val placed = localVertices.map { vertex ->
        val p = Point(vertex.x*scale.x, vertex.y*scale.y, vertex.z*scale.z)
        val cross = q.cross(p)*2f
        (origin + p + cross*w + q.cross(cross)).also { if (!it.valid()) return null }
    }
    return SupportPatch(label, placed, ids.toList())
}

private data class Face(val a: Point, val b: Point, val c: Point) {
    // Signed projected determinant. Mesh winding may vary; planeOrientation supplies the facing.
    private val det = (b.x-a.x)*(c.z-a.z) - (b.z-a.z)*(c.x-a.x)
    fun nearlyLevel(): Boolean {
        val normal = (b-a).cross(c-a)
        val magnitude = normal.dot(normal)
        return magnitude > 1e-14f && normal.y*normal.y >= .989f*magnitude // approx. six degrees
    }
    fun height(x: Float, z: Float): Float? {
        if (abs(det) < 1e-9f) return null
        val u = ((x-a.x)*(c.z-a.z) - (z-a.z)*(c.x-a.x))/det
        val v = ((b.x-a.x)*(z-a.z) - (b.z-a.z)*(x-a.x))/det
        if (u < -.00002f || v < -.00002f || u+v > 1.00002f) return null
        return a.y + u*(b.y-a.y) + v*(c.y-a.y)
    }
}

/** Work in furniture-local XZ: rotated desks and trays get their real rectangular footprint. */
private fun SupportPatch.localFaces(frame: BenchPlacement): List<Face>? {
    if (worldVertices.size !in 3..MAX_VERTICES || indices.size !in 3..MAX_INDICES || indices.size % 3 != 0) return null
    if (indices.any { it !in worldVertices.indices }) return null
    val local = worldVertices.map { if (!it.valid()) return null else frame.localPoint(it) }
    return List(indices.size/3) { i -> Face(local[indices[3*i]], local[indices[3*i+1]], local[indices[3*i+2]]) }
}

private data class EdgeKey(val low: Long, val high: Long)
private data class Edge(val a: Point, val b: Point, var count: Int)
private fun vertexKey(p: Point): Long {
    // Work in local metres; 0.1 mm weld handles split vertices on ordinary triangle seams.
    val x = (p.x * 10000.0).roundToLong()
    val z = (p.z * 10000.0).roundToLong()
    return (x shl 32) xor (z and 0xffffffffL)
}
private fun crossesInterior(a: Point, b: Point, hw: Float, hd: Float): Boolean {
    var lo = 0f
    var hi = 1f
    fun slab(start: Float, delta: Float, half: Float): Boolean {
        if (abs(delta) < 1e-8f) return abs(start) <= half
        val t0 = (-half-start)/delta
        val t1 = (half-start)/delta
        lo = max(lo,min(t0,t1))
        hi = min(hi,max(t0,t1))
        return lo <= hi
    }
    return slab(a.x, b.x-a.x, hw-.0002f) && slab(a.z, b.z-a.z, hd-.0002f)
}

private fun supportHeight(faces: List<Face>, foot: FurnitureFootprint): Float? {
    val hw = foot.halfWidth
    val hd = foot.halfDepth
    val touching = faces.filter { f ->
        maxOf(f.a.x,f.b.x,f.c.x) >= -hw && minOf(f.a.x,f.b.x,f.c.x) <= hw &&
            maxOf(f.a.z,f.b.z,f.c.z) >= -hd && minOf(f.a.z,f.b.z,f.c.z) <= hd
    }
    if (touching.isEmpty() || touching.size > 4096) return null
    // Corners, side midpoints and the centre must all lie within actual triangles, not their AABB.
    val level = touching.filter { it.nearlyLevel() }
    val heights = ArrayList<Float>(9)
    for (x in listOf(-hw,0f,hw)) for (z in listOf(-hd,0f,hd)) {
        val found = level.mapNotNull { it.height(x,z) }
        if (found.isEmpty() || found.max()-found.min() > .004f) return null
        heights.add(found.max())
    }
    // A singly-owned edge crossing the open support rectangle reveals a hole or an unfinished scan.
    // Slope seams are deliberately boundaries because a steep face cannot support a foot.
    val edges = HashMap<EdgeKey,Edge>()
    fun record(a: Point,b: Point) {
        if (abs(a.x) <= hw && abs(a.z) <= hd) heights.add(a.y)
        // Over a triangle clipped to our rectangle the height extrema occur at vertices:
        // original vertices, rectangle corners (above), or triangle-edge / rectangle intersections.
        val dx = b.x-a.x
        val dz = b.z-a.z
        val dy = b.y-a.y
        if (abs(dx) > 1e-8f) for (x in listOf(-hw,hw)) {
            val t = (x-a.x)/dx
            if (t in 0f..1f && abs(a.z+dz*t) <= hd) heights.add(a.y+dy*t)
        }
        if (abs(dz) > 1e-8f) for (z in listOf(-hd,hd)) {
            val t = (z-a.z)/dz
            if (t in 0f..1f && abs(a.x+dx*t) <= hw) heights.add(a.y+dy*t)
        }
        val u = vertexKey(a)
        val v = vertexKey(b)
        if (u == v) return
        val key = EdgeKey(min(u,v),max(u,v))
        val edge = edges[key]
        if (edge == null) edges[key] = Edge(a,b,1) else edge.count++
    }
    for (f in level) { record(f.a,f.b); record(f.b,f.c); record(f.c,f.a) }
    if (heights.max()-heights.min() > .012f) return null
    if (edges.values.any { it.count != 2 && crossesInterior(it.a,it.b,hw,hd) }) return null
    return heights.max() // the highest supported foot prevents the desk penetrating the surface
}

internal fun fitWorkshopSurface(frames: WorkshopFrames, id: FurnitureId, patches: List<SupportPatch>,
                                headY: Float = frames.room.center.y + worktopBelowEyes): SurfaceSnap {
    val foot = furnitureFootprint(id) ?: return SurfaceSnap(null,"读数板悬挂在空中，可用升降键调整。")
    val frame = frames[id]
    val floorHeights = patches.asSequence().filter { it.label == SupportLabel.FLOOR }.flatMap {
        it.localFaces(frame).orEmpty().asSequence()
    }.filter { it.nearlyLevel() }.mapNotNull { it.height(0f,0f) }.toList()
    data class Fit(val label: SupportLabel, val offset: Float)
    val candidates = mutableListOf<Fit>()
    for (patch in patches) {
        if (patch.label == SupportLabel.FLOOR) continue
        val faces = patch.localFaces(frame) ?: continue
        val surface = supportHeight(faces,foot) ?: continue
        val worldY = frame.center.y+surface
        if (headY-worldY !in .17f..1.15f) continue
        val offset = surface-foot.bottom
        if (offset !in -.40f.. .16f) continue
        // An unlabeled plane is eligible only with a measured floor below this actual footprint.
        if (patch.label == SupportLabel.UNKNOWN &&
            (abs(offset) > .19f || floorHeights.none { surface-it in .38f..1.25f })) continue
        candidates.add(Fit(patch.label,offset))
    }
    val best = candidates.minWithOrNull(compareBy<Fit> { it.label == SupportLabel.UNKNOWN }.thenBy { abs(it.offset) })
        ?: return SurfaceSnap(null,"未找到完整承托四脚的水平桌面。请把家具移到桌面上方，或手动升降。")
    val source = frames.layout[id]
    val newY = source.y + best.offset
    val next = frames.layout.withPose(id,source.copy(y=newY)).copy(tableMounted=frames.layout.tableMounted+id)
    if (abs(next[id].y-newY) > .0001f) return SurfaceSnap(null,"该表面超出可摆放高度，可用升降键调整。")
    val kind = when(best.label) {
        SupportLabel.TABLE -> "实桌"
        SupportLabel.CABINET -> "柜面"
        else -> "实测水平面（未标注类别）"
    }
    return SurfaceSnap(next,"已贴到$kind，位置和朝向已保留。")
}

internal suspend fun snapFurnitureToTable(frames: WorkshopFrames, id: FurnitureId, headY: Float): SurfaceSnap {
    currentCoroutineContext().ensureActive()
    if (furnitureFootprint(id) == null) return SurfaceSnap(null,"读数板悬挂在空中，可用升降键调整。")
    if (!surfaceScan.tryLock()) return SurfaceSnap(null,"桌面扫描进行中。")
    try {
        val sampled = WorkshopFrames(BenchPlacement(frames.room.center, frames.room.yaw),frames.layout)
        var last: SurfaceSnap? = null
        val result = withTimeoutOrNull(4500L) {
            val subscription = PlaneTrackingManager.subscribeAnchorUpdate { /* Each scan reads full snapshots. */ }
            try {
                PlaneTrackingManager.start()
                repeat(6) { attempt ->
                    if (attempt > 0) delay(360)
                    val anchors = PlaneTrackingManager.loadAllAnchors()
                    if (anchors.size > MAX_ANCHORS) return@withTimeoutOrNull SurfaceSnap(null,"房间平面过多，请靠近要使用的桌面后重试。")
                    val result = withContext(Dispatchers.Default) {
                        var totalIndices = 0
                        var totalVertices = 0
                        val patches = ArrayList<SupportPatch>()
                        for (anchor in anchors) {
                            currentCoroutineContext().ensureActive()
                            val patch = anchor.supportPatch() ?: continue
                            totalIndices += patch.indices.size
                            totalVertices += patch.worldVertices.size
                            if (totalIndices > 180_000 || totalVertices > 65_536)
                                return@withContext SurfaceSnap(null,"平面网格过大，请靠近目标桌面后再试。")
                            patches.add(patch)
                        }
                        fitWorkshopSurface(sampled,id,patches,headY)
                    }
                    if (result.layout != null) return@withTimeoutOrNull result
                    last = result
                }
                last
            } finally {
                try { subscription.cancel() } finally { PlaneTrackingManager.stop() }
            }
        }
        return result ?: SurfaceSnap(null,"桌面扫描超时。可重新扫描，或手动升降。")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (denied: SecurityException) {
        return SurfaceSnap(null,"房间感知权限未获准，可在系统中授权，或手动升降。")
    } catch (unavailable: Exception) {
        currentCoroutineContext().ensureActive()
        val denied = generateSequence<Throwable>(unavailable) { it.cause }.take(6).any { it is SecurityException }
        val message = if (denied) "房间感知权限未获准，可在系统中授权，或手动升降。"
                      else "头显暂未提供桌面检测，请完成房间扫描后重试，或手动升降。"
        return SurfaceSnap(null,message)
    } finally { surfaceScan.unlock() }
}
