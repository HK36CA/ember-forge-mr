package com.pondledger.mr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Indexed facets batched by material; both runtime and offline preview consume this geometry. */
internal class FurnitureSurface {
    val points = mutableListOf<Point>()
    val triangles = mutableListOf<Int>()
    val uvs = mutableListOf<Pair<Float,Float>>()

    fun face(corners: List<Point>, uv: List<Pair<Float,Float>> = FaceUv.fit(corners)) {
        require(corners.size == uv.size)
        uvs.addAll(uv)
        val start = points.size
        points.addAll(corners)
        for (i in 1 until corners.lastIndex) {
            triangles += start
            triangles += start + i
            triangles += start + i + 1
        }
    }
}

internal class FurnitureGeometry {
    val surfaces = linkedMapOf<Int, FurnitureSurface>()

    /** Chamfered in all three axes, so the rims catch light above and below eye level. */
    fun block(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, rgb: Int, chamfer: Float = .002f) {
        val skin = surfaces.getOrPut(rgb) { FurnitureSurface() }
        val bevel = minOf(chamfer, w * .18f, h * .18f, d * .18f)
        fun ring(height: Float, inset: Float): List<Point> {
            val u = w * .5f - inset
            val v = d * .5f - inset
            val clip = minOf(bevel * 1.75f, u * .40f, v * .40f)
            return listOf(Point(x+u,y+height,z+v-clip), Point(x+u-clip,y+height,z+v),
                Point(x-u+clip,y+height,z+v), Point(x-u,y+height,z+v-clip),
                Point(x-u,y+height,z-v+clip), Point(x-u+clip,y+height,z-v),
                Point(x+u-clip,y+height,z-v), Point(x+u,y+height,z-v+clip))
        }
        val levels = listOf(ring(-h/2, bevel), ring(-h/2+bevel,0f), ring(h/2-bevel,0f), ring(h/2,bevel))
        val projection = FaceUv(Point(x,y,z), Point(w,h,d))
        fun face(corners:List<Point>) = skin.face(corners,projection.map(corners))
        face(levels.first())
        face(levels.last().reversed())
        for ((bottom,top) in levels.zipWithNext()) for (i in bottom.indices) {
            val j = (i + 1) % bottom.size
            face(listOf(bottom[i],top[i],top[j],bottom[j]))
        }
    }

    /** Turned bar along X; the end caps are real faces. */
    fun rail(x: Float, y: Float, z: Float, width: Float, radius: Float, rgb: Int, sides: Int = 14) {
        val skin = surfaces.getOrPut(rgb) { FurnitureSurface() }
        val left = (0 until sides).map { n ->
            val a = 2 * PI * n / sides
            Point(x-width*.5f,y+radius*cos(a).toFloat(),z+radius*sin(a).toFloat())
        }
        val right = left.map { it + Point(width,0f,0f) }
        skin.face(left.reversed())
        skin.face(right)
        for (i in left.indices) {
            val j = (i + 1) % sides
            val v0=i.toFloat()/sides
            val v1=(i+1).toFloat()/sides
            skin.face(listOf(left[i],left[j],right[j],right[i]), listOf(0f to v0,0f to v1,1f to v1,1f to v0))
        }
    }

    fun strut(a:Point,b:Point,width:Float,depth:Float,rgb:Int,chamfer:Float=.002f) {
        val delta=b-a
        val length=sqrt(delta.dot(delta))
        val up=delta*(1f/length)
        val axis=if(abs(up.z)<.92f)Point(0f,0f,1f) else Point(1f,0f,0f)
        val right=up.cross(axis).unit()!!
        val back=right.cross(up)
        val center=(a+b)*.5f
        val piece=FurnitureGeometry().apply{block(0f,0f,0f,width,length,depth,rgb,chamfer)}.surfaces.getValue(rgb)
        val skin=surfaces.getOrPut(rgb){FurnitureSurface()}
        val first=skin.points.size
        skin.points+=piece.points.map{p->center+right*p.x+up*p.y+back*p.z}
        skin.uvs+=piece.uvs
        skin.triangles+=piece.triangles.map{first+it}
    }

    /** Extrude a clockwise polygon in the local YZ plane into a solid end cheek. */
    fun cheek(x:Float,width:Float,yz:List<Pair<Float,Float>>,rgb:Int){
        val skin=surfaces.getOrPut(rgb){FurnitureSurface()}
        val left=yz.map{(y,z)->Point(x-width/2,y,z)}
        val right=yz.map{(y,z)->Point(x+width/2,y,z)}
        skin.face(left.reversed())
        skin.face(right)
        for(i in yz.indices){
            val j=(i+1)%yz.size
            skin.face(listOf(left[i],left[j],right[j],right[i]))
        }
    }
}

/** Unwrap each part before batching. U follows its longest dimension on all longitudinal faces.
 * Each face uses a physical, isotropic crop within one texture tile, including small edge bevels. */
private class FaceUv(val center:Point,val size:Point){
    private val axes=listOf(Point(1f,0f,0f),Point(0f,1f,0f),Point(0f,0f,1f)).sortedByDescending{it.dot(size)}

    fun map(corners:List<Point>):List<Pair<Float,Float>>{
        val cross=(corners[1]-corners[0]).cross(corners[2]-corners[0])
        val norm=sqrt(cross.dot(cross))
        if(norm<1e-12f)return corners.map{.5f to .5f}
        val n=cross*(1f/norm)
        val grain=axes.first{abs(n.dot(it))<.90f}
        val tangent=(grain-n*grain.dot(n)).unit()!!
        val bitangent=n.cross(tangent)
        fun span(axis:Point) = abs(axis.x)*size.x+abs(axis.y)*size.y+abs(axis.z)*size.z
        val uSpan=span(tangent);val vSpan=span(bitangent)
        val tile=maxOf(.34f,1.04f*maxOf(uSpan,vSpan))
        val shiftU=(abs(center.x*2.53f+center.y*.91f+center.z*.61f)%.87f)+.04f
        val shiftV=(abs(center.z*3.43f+center.y*.83f+center.x*.78f)%.87f)+.04f
        val midU=.5f*uSpan/tile+shiftU*(1f-uSpan/tile)
        val midV=.5f*vSpan/tile+shiftV*(1f-vSpan/tile)
        return corners.map{p->val local=p-center
            (midU+local.dot(tangent)/tile).coerceIn(0f,1f) to (midV-local.dot(bitangent)/tile).coerceIn(0f,1f)}
    }
    companion object{
        fun fit(points:List<Point>):List<Pair<Float,Float>>{
            val lo=Point(points.minOf{it.x},points.minOf{it.y},points.minOf{it.z})
            val hi=Point(points.maxOf{it.x},points.maxOf{it.y},points.maxOf{it.z})
            return FaceUv((lo+hi)*.5f,hi-lo).map(points)
        }
    }
}
