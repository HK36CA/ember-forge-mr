package com.pondledger.mr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun Point.unit():Point? {
    val n=sqrt(dot(this))
    return if(n.isFinite() && n>.00001f) this*(1f/n) else null
}
internal fun Point.cross(other:Point)=Point(y*other.z-z*other.y,z*other.x-x*other.z,x*other.y-y*other.x)
internal data class HammerRotation(val x:Float,val y:Float,val z:Float,val w:Float) {
    fun rotate(v:Point):Point {
        val q=Point(x,y,z)
        val twice=q.cross(v)*2f
        return v+twice*w+q.cross(twice)
    }
    operator fun times(o:HammerRotation)=HammerRotation(w*o.x+x*o.w+y*o.z-z*o.y,
        w*o.y-x*o.z+y*o.w+z*o.x,w*o.z+x*o.y-y*o.x+z*o.w,w*o.w-x*o.x-y*o.y-z*o.z)
    fun reachableFrom(last:HammerRotation,elapsedMs:Long):Boolean{
        val alignment=abs(x*last.x+y*last.y+z*last.z+w*last.w)
        val maxTurn=.35f+.024f*elapsedMs.coerceIn(0L,120L)
        return alignment>=cos(maxTurn*.5f)
    }
    fun blend(other:HammerRotation,t:Float):HammerRotation {
        val sign=if(x*other.x+y*other.y+z*other.z+w*other.w<0f) -1 else 1
        return normalized(x*(1-t)+other.x*t*sign,y*(1-t)+other.y*t*sign,
            z*(1-t)+other.z*t*sign,w*(1-t)+other.w*t*sign) ?: this
    }
    companion object {
        fun normalized(x:Float,y:Float,z:Float,w:Float):HammerRotation?{
            val n=sqrt(x*x+y*y+z*z+w*w)
            return if(n.isFinite()&&n>.00001f)HammerRotation(x/n,y/n,z/n,w/n) else null
        }
        fun pitch(deg:Float):HammerRotation {
            val h=Math.toRadians(deg.toDouble())*.5
            return HammerRotation(sin(h).toFloat(),0f,0f,cos(h).toFloat())
        }
        fun yaw(radians:Float)=HammerRotation(0f,sin(radians*.5f),0f,cos(radians*.5f))
        /** Convert the orthonormal right/up/back basis to a unit orientation, including near half-turn poses. */
        fun basis(r:Point,u:Point,b:Point):HammerRotation? {
            val trace=r.x+u.y+b.z
            return when {
                trace>0f -> {val s=sqrt(trace+1f)*2;normalized((u.z-b.y)/s,(b.x-r.z)/s,(r.y-u.x)/s,.25f*s)}
                r.x>u.y && r.x>b.z -> {val s=sqrt(1+r.x-u.y-b.z)*2;normalized(.25f*s,(u.x+r.y)/s,(b.x+r.z)/s,(u.z-b.y)/s)}
                u.y>b.z -> {val s=sqrt(1+u.y-r.x-b.z)*2;normalized((u.x+r.y)/s,.25f*s,(b.y+u.z)/s,(b.x-r.z)/s)}
                else -> {val s=sqrt(1+b.z-r.x-u.y)*2;normalized((b.x+r.z)/s,(b.y+u.z)/s,.25f*s,(r.y-u.x)/s)}
            }
        }
    }
}
internal data class HammerPose(val position:Point,val rotation:HammerRotation){
    val face:Point get()=position+rotation.rotate(hammerFaceLocal)
    val faceNormal:Point get()=rotation.rotate(Point(0f,0f,-1f))
    fun inBench(bench:BenchPlacement)=HammerPose(bench.localPoint(position),HammerRotation.yaw(-bench.yaw)*rotation)
    fun blend(o:HammerPose,t:Float)=HammerPose(position+(o.position-position)*t,rotation.blend(o.rotation,t))
    companion object {
        val gripOffset=HammerRotation.pitch(-52f)
        /** Fingers curl around a power grip across the palm, with the shaft exiting by the index knuckle. */
        fun fromHand(palm:Point,wrist:Point,middle:Point,index:Point,little:Point,left:Boolean):HammerPose?{
            if(!palm.valid())return null
            val thumbward=(index-little).unit() ?: return null
            val knuckles=middle-wrist
            val forward=(knuckles-thumbward*knuckles.dot(thumbward)).unit() ?: return null
            val right=forward.cross(thumbward)
            val basis=HammerRotation.basis(right,thumbward,forward*-1f) ?: return null
            val inward=right*(if(left)1f else -1f)
            return HammerPose(palm+inward*.012f+forward*.008f,basis*handPitch)
        }
        private val handPitch=HammerRotation.pitch(-20f)
        fun atFace(contact:Point,rotation:HammerRotation)=HammerPose(contact-rotation.rotate(hammerFaceLocal),rotation)
        val docked=atFace(Point(-.11f,.24f,.135f),HammerRotation.pitch(-36f))
    }
}

/** The optional seated-assist echo has the same working face as the tool in the player's hand. */
internal class RemoteHammer(val start:HammerPose,val contact:Point,val precision:Boolean,val source:Int,
                           val started:Long,val serial:Int,val targetT:Float){
    var delivered=false
    private val raised=HammerPose.atFace(contact+Point(.017f,.15f,.025f),HammerRotation.pitch(-42f))
    private val hit=HammerPose.atFace(contact,HammerRotation.pitch(-90f))
    fun pose(now:Long,rest:HammerPose):HammerPose {
        val dt=(now-started).coerceAtLeast(0L)
        return when {
            dt<138 -> start.blend(raised,smooth(dt/138f))
            dt<228 -> { val u=(dt-138)/90f;raised.blend(hit,u*u) }
            dt<=248 -> hit
            else -> hit.blend(rest,smooth(((dt-248)/165f).coerceIn(0f,1f)))
        }
    }
    private fun smooth(t:Float)=t*t*(3-2*t)
}
