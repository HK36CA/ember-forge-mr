package com.pondledger.mr

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Grip pivot at the origin, handle towards +Y, hitting face towards −Z, all in metres. */
internal val hammerFaceLocal = Point(0f, .225f, -.073f)
internal val hammerFaceOutline = headRing(hammerFaceLocal.z, .0205f, .0205f)

private enum class Finish(val rgb: Int, val roughness: Float, val metallic: Float, val surface: ForgeFinish? = null) {
    HICKORY(0xB6753E, .64f, .03f, ForgeFinish.HICKORY),
    LEATHER(0x64392E, .76f, .01f, ForgeFinish.LEATHER),
    STITCH(0xA7805C, .80f, .01f),
    IRON(0x526370, .48f, .52f, ForgeFinish.BLUED),
    FACE(0xCDDAE2, .30f, .70f, ForgeFinish.STEEL),
    COPPER(0xD58B56, .37f, .59f, ForgeFinish.COPPER)
}

/** One mesh batch per finish. Geometry retains its true local grip and face positions. */
internal class ForgeHammer(root: Entity, private val echo: Boolean = false,
    private val bindFinish: (ForgeFinish, Entity) -> Unit = { _, _ -> }) : AutoCloseable {
    private val entity = Entity().apply {
        setName(if (echo) "RemoteHammerEcho" else "SmithingHammer")
        enabled = false
        root.addChild(this)
    }
    private val meshes = mutableListOf<MeshResource>()
    private val materials = mutableMapOf<Finish, PhysicallyBasedMaterial>()
    private val parts = mutableListOf<Entity>()
    private var visible = false
    private var closed = false

    init {
        try {
            for ((finish, batch) in hammerGeometry()) {
                // Shrink the echo about the striking face, so even its final landing pose stays exact.
                val renderPositions = if (echo) batch.positions.map { vertex ->
                    val face = hammerFaceLocal
                    Vector3(face.x + (vertex.x - face.x) * .91f, face.y + (vertex.y - face.y) * .91f,
                        face.z + (vertex.z - face.z) * .91f)
                } else batch.positions
                val model = MeshModel(positions = renderPositions,
                    triangleIndices = batch.positions.indices.toList(), normals = batch.normals, uv0 = batch.uvs)
                val mesh = MeshResource.createWithMeshModel(model = model, name = "Hammer_${finish.name}")
                attach(finish, mesh)
            }
        } catch (failure: Exception) {
            Log.w("EmberForge", "Using simple hammer mesh", failure)
            clearParts()
            try {
                val face = hammerFaceLocal
                attach(Finish.HICKORY, MeshResource.createBox(Vector3(.026f, .30f, .034f), .006f),
                    Point(0f, .073f, 0f))
                attach(Finish.IRON, MeshResource.createBox(Vector3(.047f, .046f, .135f), .007f),
                    Point(0f, face.y, face.z + .074f))
                attach(Finish.FACE, MeshResource.createBox(Vector3(.045f, .043f, .007f), .0015f),
                    Point(0f, face.y, face.z + .0035f))
            } catch (fallbackFailure: Exception) {
                close()
                throw fallbackFailure
            }
        }
    }

    fun show(pose: HammerPose?) {
        if (closed || !entity.valid) {
            check(pose == null) { "Hammer entity is unavailable" }
            return
        }
        if (pose != null) {
            val transform = checkNotNull(entity.components[TransformComponent::class.java]) { "Hammer transform is unavailable" }
            transform.position = pose.position.vector()
            val q = pose.rotation
            transform.quaternion = Quat(q.x, q.y, q.z, q.w)
        }
        val requested = pose != null
        if (requested != visible) {
            entity.enabled = requested
            visible = requested
        }
    }

    private fun attach(finish: Finish, mesh: MeshResource, position: Point? = null) {
        meshes += mesh
        mesh.toGlobal()
        val material = materials.getOrPut(finish) { makeMaterial(finish) }
        val part = Entity()
        parts += part
        part.setName("Hammer_${finish.name}")
        entity.addChild(part)
        part.components.set(ModelComponent(mesh, material))
        if (!echo) finish.surface?.let { bindFinish(it, part) }
        if (position != null) part.components[TransformComponent::class.java]?.position = position.vector()
    }

    private fun makeMaterial(finish: Finish): PhysicallyBasedMaterial {
        val rgb = if (echo) when (finish) {
            Finish.IRON, Finish.HICKORY -> 0xD77832
            Finish.LEATHER -> 0xA65538
            Finish.FACE -> 0xFFF1AF
            Finish.STITCH, Finish.COPPER -> 0xFFD677
        } else finish.rgb
        fun linear(shift: Int): Float {
            val s = ((rgb shr shift) and 255) / 255f
            return if (s < .04045f) s / 12.92f else ((s + .055f) / 1.055f).pow(2.4f)
        }
        val r = linear(16)
        val g = linear(8)
        val b = linear(0)
        val material = PhysicallyBasedMaterial.create()
        return try {
            material.apply {
                setBaseColor(Color4(r, g, b, 1f))
                setMetallic(if (echo) .12f else finish.metallic)
                setRoughness(finish.roughness)
                val glow = if (echo) .78f else .15f
                setEmissiveColor(Color4(r * glow, g * glow, b * glow, 1f))
                toGlobal()
            }
        } catch (failure: Exception) {
            if (material.valid) material.close()
            throw failure
        }
    }

    private fun clearParts() {
        parts.forEach { runCatching { if (it.valid) it.destroy() } }
        meshes.forEach { runCatching { if (it.valid) it.close() } }
        materials.values.forEach { runCatching { if (it.valid) it.close() } }
        parts.clear()
        meshes.clear()
        materials.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        clearParts()
        if (entity.valid) entity.destroy()
    }
}

/** Flat normals preserve bevels in grazing light. All polygons are wound towards the viewer. */
private class Batch {
    val positions = mutableListOf<Vector3>()
    val normals = mutableListOf<Vector3>()
    val uvs = mutableListOf<Vector2>()

    fun triangle(a: Point, b: Point, c: Point, ta: Vector2? = null, tb: Vector2? = null, tc: Vector2? = null) {
        val u = b - a
        val v = c - a
        val normal = Point(u.y * v.z - u.z * v.y, u.z * v.x - u.x * v.z, u.x * v.y - u.y * v.x)
        val magnitude = sqrt(normal.dot(normal))
        if (magnitude < 1e-10f) return
        val unit = (normal * (1f / magnitude)).vector()
        positions.addAll(listOf(a.vector(), b.vector(), c.vector()))
        repeat(3) { normals.add(unit) }
        fun planar(p: Point): Vector2 {
            val x = kotlin.math.abs(unit.x)
            val y = kotlin.math.abs(unit.y)
            val z = kotlin.math.abs(unit.z)
            return when {
                z >= x && z >= y -> Vector2(.5f + p.x * 14f, .5f + (p.y - hammerFaceLocal.y) * 14f)
                y >= x -> Vector2(.5f + p.x * 14f, .5f + p.z * 6f)
                else -> Vector2(.5f + p.z * 5.6f, .5f + (p.y - hammerFaceLocal.y) * 14f)
            }
        }
        uvs.addAll(listOf(ta ?: planar(a), tb ?: planar(b), tc ?: planar(c)))
    }

    fun quad(a: Point, b: Point, c: Point, d: Point) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    fun quad(a: Point, b: Point, c: Point, d: Point, ta: Vector2, tb: Vector2, tc: Vector2, td: Vector2) {
        triangle(a, b, c, ta, tb, tc)
        triangle(a, c, d, ta, tc, td)
    }
}

private data class Section(val y: Float, val rx: Float, val rz: Float, val z: Float = 0f)

private fun Section.point(angle: Double) = Point(rx * cos(angle).toFloat(), y, z + rz * sin(angle).toFloat())

private fun Batch.handle(vararg levels: Section, count: Int = 16) {
    for (i in 0 until count) {
        val start = 2 * PI * i / count
        val end = 2 * PI * (i + 1) / count
        for ((bottom, top) in levels.asList().zipWithNext()) {
            // Both wood textures are authored with the grain running horizontally.
            fun u(s: Section) = (s.y - levels.first().y) / (levels.last().y - levels.first().y)
            val v0 = i.toFloat() / count
            val v1 = (i + 1f) / count
            quad(bottom.point(start), top.point(start), top.point(end), bottom.point(end),
                Vector2(u(bottom), v0), Vector2(u(top), v0), Vector2(u(top), v1), Vector2(u(bottom), v1))
        }
        val low = levels.first()
        val high = levels.last()
        fun cap(angle: Double) = Vector2(.5f + .45f * cos(angle).toFloat(), .5f + .45f * sin(angle).toFloat())
        val centre = Vector2(.5f, .5f)
        triangle(Point(0f, low.y, low.z), low.point(start), low.point(end), centre, cap(start), cap(end))
        triangle(Point(0f, high.y, high.z), high.point(end), high.point(start), centre, cap(end), cap(start))
    }
}

private fun headRing(z: Float, width: Float, height: Float): List<Point> {
    val y = hammerFaceLocal.y
    val trimX = width * .24f
    val trimY = height * .24f
    return listOf(
        Point(width, y + height - trimY, z), Point(width - trimX, y + height, z),
        Point(-width + trimX, y + height, z), Point(-width, y + height - trimY, z),
        Point(-width, y - height + trimY, z), Point(-width + trimX, y - height, z),
        Point(width - trimX, y - height, z), Point(width, y - height + trimY, z)
    )
}

private fun Batch.linkHeadRings(front: List<Point>, back: List<Point>) {
    for (i in front.indices) {
        val next = (i + 1) % front.size
        fun uv(p: Point, index: Int) = Vector2((p.z - hammerFaceLocal.z) / (.081f - hammerFaceLocal.z), index.toFloat() / front.size)
        quad(front[i], front[next], back[next], back[i],
            uv(front[i], i), uv(front[next], i+1), uv(back[next], i+1), uv(back[i], i))
    }
}

private fun hammerGeometry(): Map<Finish, Batch> {
    val batches = Finish.values().associateWith { Batch() }
    val wood = batches.getValue(Finish.HICKORY)
    val leather = batches.getValue(Finish.LEATHER)
    val thread = batches.getValue(Finish.STITCH)
    val iron = batches.getValue(Finish.IRON)
    val face = batches.getValue(Finish.FACE)
    val copper = batches.getValue(Finish.COPPER)
    val y = hammerFaceLocal.y
    val z = hammerFaceLocal.z

    // A flared butt and slightly forward-curving neck read from above and from the side.
    wood.handle(Section(-.082f,.012f,.017f), Section(-.079f,.0165f,.021f),
        Section(-.066f,.016f,.020f), Section(-.054f,.0133f,.0172f),
        Section(.056f,.0133f,.0172f), Section(.084f,.0138f,.017f,.001f),
        Section(.138f,.0132f,.0163f,.004f), Section(.177f,.0125f,.0157f,.003f),
        Section(y + .025f,.0113f,.0154f))
    leather.handle(Section(-.058f,.014f,.018f), Section(-.054f,.015f,.0192f),
        Section(.052f,.015f,.0192f), Section(.057f,.014f,.018f))

    // The overlapping rolled edge catches light on the grip. Use the same leather UVs
    // as its sleeve, with the angular seam at the last segment of each revolution.
    val turns = 5
    val segmentsPerTurn = 24
    val segments = turns * segmentsPerTurn
    fun lip(step: Int, offset: Float, crown: Float): Point {
        val t = step.toFloat() / segments
        return Section(-.054f + .104f * t + offset, .0151f + crown, .0193f + crown)
            .point(step * 2 * PI / segmentsPerTurn)
    }
    for (i in 0 until segments) {
        val v0 = (i % segmentsPerTurn).toFloat() / segmentsPerTurn
        val v1 = ((i % segmentsPerTurn) + 1f) / segmentsPerTurn
        fun strip(start: Float, startRadius: Float, end: Float, endRadius: Float) {
            val a = lip(i,start,startRadius)
            val b = lip(i,end,endRadius)
            val c = lip(i+1,end,endRadius)
            val d = lip(i+1,start,startRadius)
            fun uv(p: Point, v: Float) = Vector2((p.y + .058f) / .115f, v)
            leather.quad(a,b,c,d,uv(a,v0),uv(b,v0),uv(c,v1),uv(d,v1))
        }
        strip(0f,0f,.0011f,.00085f)
        strip(.0011f,.00085f,.0028f,0f)
    }
    // Short saddle stitches secure the ends of the wrap on the underside.
    for (band in listOf(-.049f,.047f)) {
        for (i in 0..3) {
            val a = PI * 1.26 + i * .17
            fun stitch(h: Float, angle: Double) = Section(band + h,.01565f,.01985f).point(angle)
            thread.quad(stitch(0f,a),stitch(.0011f,a),stitch(.0024f,a+.105),stitch(.0013f,a+.105))
        }
    }
    copper.handle(Section(-.071f,.0164f,.0204f), Section(-.067f,.0164f,.0205f))
    copper.handle(Section(.055f,.0144f,.0185f), Section(.060f,.0142f,.0183f))
    iron.handle(Section(.171f,.0133f,.0168f,.003f), Section(.199f,.0144f,.0174f,.002f))
    copper.handle(Section(.172f,.0137f,.0172f,.003f), Section(.178f,.0137f,.0172f,.003f))
    copper.handle(Section(.192f,.0145f,.0176f,.002f), Section(.197f,.0147f,.0178f,.002f))

    // The main polished face is the shared collision plane, with no geometry in front of it.
    val front = hammerFaceOutline
    val bevel = headRing(z + .0035f,.024f,.024f)
    val rim = headRing(z + .010f,.024f,.024f)
    val waist = headRing(-.040f,.019f,.019f)
    val eye = headRing(-.015f,.025f,.0245f)
    val poll = headRing(.021f,.025f,.0245f)
    val backSlope = headRing(.059f,.022f,.012f)
    val peen = headRing(.078f,.019f,.005f)
    val tip = headRing(.081f,.0175f,.0041f)
    for (i in front.indices) {
        val next = (i + 1) % front.size
        face.triangle(hammerFaceLocal, front[next], front[i])
        face.triangle(Point(0f,y,tip.first().z), tip[i], tip[next])
    }
    face.linkHeadRings(front, bevel)
    face.linkHeadRings(bevel, rim)
    for ((a,b) in listOf(rim,waist,eye,poll,backSlope,peen).zipWithNext()) iron.linkHeadRings(a,b)
    face.linkHeadRings(peen, tip)

    // A hickory through-tenon and narrow wedge sit proud of the crown.
    wood.handle(Section(y + .0242f,.0102f,.0138f), Section(y + .026f,.0098f,.0133f))
    copper.quad(Point(-.008f,y+.02615f,-.0035f),Point(-.008f,y+.02615f,-.0015f),
        Point(.008f,y+.02615f,.0035f),Point(.008f,y+.02615f,.0015f))

    // A paired brass chevron is visible on either cheek while the face is turned away.
    for (side in listOf(-1f,1f)) {
        val x = side * .02525f
        for (start in listOf(-.010f,0f)) {
            val points = listOf(Point(x,y-.007f,start),Point(x,y+.000f,start+.006f),
                Point(x,y+.007f,start),Point(x,y+.007f,start+.0028f),
                Point(x,y+.000f,start+.0088f),Point(x,y-.007f,start+.0028f))
            for ((a,b,c) in listOf(Triple(0,1,4),Triple(0,4,5),Triple(1,2,3),Triple(1,3,4))) {
                if (side > 0) copper.triangle(points[a],points[b],points[c])
                else copper.triangle(points[a],points[c],points[b])
            }
        }
    }
    return batches
}
