package com.pondledger.mr

import kotlin.math.cos
import kotlin.math.sin

internal data class HammerImpact(val point:Point,val centered:Boolean)

/** The blade model, visible target and hit plane all use this one placement in bench metres. */
internal data class BladeSpot(val center:Point,val lift:Float=0f) {
    fun contains(point:Point):Boolean = withinRadius(point,RADIUS)

    private fun withinRadius(point:Point,r:Float):Boolean {
        val x=point.x-center.x
        val z=point.z-center.z
        return x*x+z*z<=r*r
    }

    fun gazed(ray:Aim):Boolean {
        if(ray.forward.y>=-.035f) return false
        val distance=(center.y-ray.origin.y)/ray.forward.y
        return distance in 0f..2.25f && withinRadius(ray.origin+ray.forward*distance,GAZE_RADIUS)
    }

    companion object {
        const val RADIUS=.018f
        const val GAZE_RADIUS=(RADIUS+.006f)*GAZE_RADIUS_MULTIPLIER
        const val YAW_DEGREES=18f
        val scale=Point(2.10f,1.40f,1.85f)
        val base=Point(.061f,.188f,0f)
        private val yaw=Math.toRadians(YAW_DEGREES.toDouble())
        private val c=cos(yaw).toFloat()
        private val s=sin(yaw).toFloat()
        val profile=listOf(Point(-.145f,0f,0f),Point(-.100f,0f,-.013f),Point(.041f,0f,-.019f),
            Point(.051f,0f,-.012f),Point(.051f,0f,.012f),Point(.041f,0f,.019f),Point(-.100f,0f,.013f),
            Point(-.098f,.004f,0f),Point(.045f,.007f,0f),Point(.035f,-.004f,0f)).map{Point(it.x*scale.x,it.y*scale.y,it.z*scale.z)}
        private val upper=listOf(0,7,1,0,6,7,1,7,8,1,8,2,2,8,3,3,8,4,4,8,5,5,8,7,5,7,6)
        val meshIndices=upper+listOf(0,1,9,1,2,9,2,3,9,3,4,9,4,5,9,5,6,9,6,0,9)
        private val upperFaces=upper.chunked(3).map{(a,b,c)->BladeTriangle(profile[a],profile[b],profile[c])}
        private val samples=listOf(hammerFaceLocal)+hammerFaceOutline

        private fun inBlade(p:Point,lift:Float):Point{
            val x=p.x-anvilTarget.x-base.x
            val z=p.z-anvilTarget.z-base.z
            return Point(c*x-s*z,p.y-base.y-lift,s*x+c*z)
        }

        /** Sweep the polished face against the same upper bevel triangles that are drawn. */
        fun impact(a:HammerPose,b:HammerPose,spot:BladeSpot?):HammerImpact?{
            val lift=spot?.lift ?: 0f
            if(b.face.y>base.y+lift+.040f || a.face.y<base.y+lift-.032f)return null
            var earliest=Float.POSITIVE_INFINITY
            var contact:Point?=null
            for(local in samples){
                val from=a.position+a.rotation.rotate(local)
                val to=b.position+b.rotation.rotate(local)
                val start=inBlade(from,lift)
                val end=inBlade(to,lift)
                if(end.y>=start.y-.0001f)continue
                for(surface in upperFaces){
                    val t=surface.crossing(start,end) ?: continue
                    if(t<earliest){
                        earliest=t
                        contact=from+(to-from)*t
                    }
                }
            }
            if(contact==null)return null
            val downward=a.faceNormal.y+(b.faceNormal.y-a.faceNormal.y)*earliest
            if(downward>-.55f)return null
            return HammerImpact(contact,spot?.contains(a.face+(b.face-a.face)*earliest)==true)
        }
        fun from(state:ForgeSceneState,lift:Float=0f):BladeSpot? {
            if(!state.active || !state.precisionOn || state.dragon || state.materialMode) return null
            val x=-.076f+.101f*state.spotT.coerceIn(0f,1f)
            // Raised ridge runs from (-.098, .004) to (.045, .007) in blade-local coordinates.
            val ridge=(.004f+(x+.098f)*(.003f/.143f))*scale.y
            val station=anvilTarget
            return BladeSpot(Point(station.x+base.x+cos(yaw).toFloat()*x*scale.x,
                base.y+lift+ridge+.0012f,station.z+base.z-sin(yaw).toFloat()*x*scale.x),lift)
        }
    }
}

private class BladeTriangle(private val a:Point,b:Point,c:Point){
    private val ab=b-a
    private val ac=c-a
    private val normal=ab.cross(ac)
    private val determinant=ab.x*ac.z-ac.x*ab.z

    fun crossing(from:Point,to:Point):Float?{
        val h0=(from-a).dot(normal)
        val h1=(to-a).dot(normal)
        if(h0<=0f || h1>0f)return null
        val t=h0/(h0-h1)
        val p=from+(to-from)*t-a
        val u=(p.x*ac.z-p.z*ac.x)/determinant
        val v=(ab.x*p.z-ab.z*p.x)/determinant
        return t.takeIf{u>=-.0001f && v>=-.0001f && u+v<=1.0001f}
    }
}

/** One downward stroke per lift; interpolate the crossing even if no sampled pose falls on the plane. */
internal class ForgeStroke {
    private var previous:HammerPose?=null
    private var sampled=0L
    private var armedAt:Long?=null
    private var spent=false
    private var returnedLow=false
    val primed:Boolean get()=armedAt!=null && !spent
    fun retarget() { previous=null;armedAt=null }
    fun reset() { previous=null;armedAt=null;spent=false;returnedLow=false }
    fun consume() { armedAt=null;spent=true;returnedLow=false }

    /** An ordinary blade strike and a centered precision strike both require actual tool contact. */
    fun update(pose:HammerPose,now:Long,spot:BladeSpot?):HammerImpact? {
        val anchor=anvilTarget
        val plane=spot?.center?.y ?: (BladeSpot.base.y+.006f)
        val point=pose.face
        if(kotlin.math.abs(point.x-anchor.x)>.32f || kotlin.math.abs(point.z-anchor.z)>.17f) {
            reset()
            return null
        }
        val last=previous
        val elapsed=now-sampled
        if(last!=null && elapsed==0L)return null
        previous=pose
        sampled=now
        val gripJump=if(last!=null)pose.position-last.position else null
        val maxTravel=.025f+.008f*elapsed.coerceIn(0L,120L)
        val flipped=last!=null && !pose.rotation.reachableFrom(last.rotation,elapsed)
        if(last==null || elapsed !in 1L..120L || flipped || (gripJump!=null && gripJump.dot(gripJump)>maxTravel*maxTravel)) {
            armedAt=null
            if(!spent && point.y-plane in .085f.. .28f) armedAt=now
            return null
        }
        val h=point.y-plane
        if(h<.046f) returnedLow=true
        if(h in .085f.. .28f) {
            if(returnedLow){spent=false;returnedLow=false}
            if(!spent) armedAt=now
        }
        val raised=armedAt ?: return null
        if(now-raised>550){armedAt=null;return null}
        if(spent || now-raised<12L) return null
        if((last.face.y-point.y)*1000f/elapsed<.18f)return null
        val impact=BladeSpot.impact(last,pose,spot) ?: return null
        consume()
        returnedLow=true
        return impact
    }
}
