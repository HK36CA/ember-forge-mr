package com.pondledger.mr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class FireLayer(val tint:Int) {
    COALS(0xFF512E), TONGUES(0xFFBD40), WAVE(0xFFE791), SPARKS(0xFF9654)
}

internal data class FirePose(val radius:Float,val height:Float,val y:Float,val turn:Float)
internal data class ForgeFireFrame(val elapsed:Int,val left:Int,val ignition:Boolean) {
    fun pose(layer:FireLayer):FirePose? {
        val t=elapsed/1000f
        val end=(1f-left/1050f).coerceIn(0f,1f)
        val tighten=end*end*(3f-2f*end)
        val breath=sin(2f*PI.toFloat()*(1.16f*t+.65f*tighten)).toFloat()
        return when(layer){
            FireLayer.COALS->FirePose(1f-.18f*tighten+.017f*breath,1f-.51f*tighten,.013f,2.5f*breath)
            FireLayer.TONGUES->FirePose(1f-.16f*tighten-.018f*breath,.91f+.18f*breath-.38f*tighten,
                .016f,3.8f*sin(4.5f*t).toFloat())
            FireLayer.WAVE->{
                if(!ignition || elapsed>=540)return null
                val p=elapsed/540f
                val remainder=1f-p
                FirePose(.76f+.49f*(1f-remainder*remainder),1.20f*remainder*remainder,.018f,0f)
            }
            FireLayer.SPARKS->{
                if(!ignition || elapsed>=640)return null
                val p=elapsed/640f
                FirePose(.72f+.49f*p,1.15f*(1f-p),.019f+.055f*4f*p*(1f-p),3.5f*p)
            }
        }
    }
}

/** Game time owns the effect. Interpolation only fills the gap between two 100 ms game ticks. */
internal class ForgeFireClock {
    private var observed=false
    private var previousStrike=0
    private var previousLeft=0
    private var sampleAt=0L
    private var ignition=false
    fun suspend(){observed=false;ignition=false}
    fun frame(visible:Boolean,remaining:Int,duration:Int,strike:Int,now:Long):ForgeFireFrame? {
        if(!visible || duration<=0){suspend();return null}
        val left=remaining.coerceIn(0,duration)
        if(!observed || previousLeft!=left || now<sampleAt)sampleAt=now
        if(left==0)ignition=false
        else if(!observed || previousLeft==0)ignition=observed && previousLeft==0 && strike>previousStrike
        observed=true
        previousStrike=strike
        previousLeft=left
        if(left==0)return null
        val displayLeft=(left-(now-sampleAt).coerceIn(0L,100L).toInt()).coerceAtLeast(1)
        return ForgeFireFrame(duration-displayLeft,displayLeft,ignition)
    }
}

/** Four opaque, faceted batches in anvil-local metres. The open centre is reserved for striking. */
internal fun forgeFireGeometry(layer:FireLayer):FurnitureGeometry=FurnitureGeometry().apply {
    val skin=FurnitureSurface()
    surfaces[layer.tint]=skin
    fun face(points:List<Point>,inside:Point){
        val a=points[0]
        val normal=(points[1]-a).cross(points[2]-a)
        val outward=normal.dot(a-inside)>0f
        skin.face(if(outward)points else points.asReversed(),List(points.size){0f to 0f})
    }
    fun radial(degrees:Float,x:Float,z:Float,y:Float=0f):Point{
        val angle=degrees*PI.toFloat()/180f
        return Point(cos(angle)*x,y,sin(angle)*z)
    }
    fun ribbon(start:Float,arc:Float,rx:Float,rz:Float,height:Float,thick:Float){
        val pieces=6
        var previous:List<Point>?=null
        for(i in 0..pieces){
            val u=i.toFloat()/pieces
            val taper=.12f+.88f*sin(PI.toFloat()*u)
            val a=start+u*arc
            val ring=listOf(radial(a,rx-thick*taper,rz-thick*taper),
                radial(a,rx+thick*taper,rz+thick*taper),
                radial(a,rx,rz,height*taper))
            previous?.let{old->
                val inside=(old[0]+old[1]+old[2]+ring[0]+ring[1]+ring[2])*(1f/6f)
                for(j in 0..2){
                    val k=(j+1)%3
                    face(listOf(old[j],old[k],ring[k],ring[j]),inside)
                }
                if(i==1)face(old,inside)
                if(i==pieces)face(ring,inside)
            }
            previous=ring
        }
    }
    fun flame(base:Point,tip:Point,side:Point,girth:Float){
        val along=(tip-base).unit() ?:return
        val right=along.cross(side).unit() ?:return
        val across=right.cross(along)*girth
        val midpoint=base+(tip-base)*.29f
        val bevel=right*(girth*.65f)
        val waist=listOf(midpoint+across,midpoint+bevel,midpoint-across,midpoint-bevel)
        for(j in waist.indices){
            val k=(j+1)%waist.size
            face(listOf(base,waist[j],waist[k]),midpoint)
            face(listOf(waist[k],waist[j],tip),midpoint)
        }
    }
    when(layer){
        FireLayer.COALS->{
            for(i in 0..9)ribbon(i*36f+3f,26f,.304f,.166f,.015f+(i%3)*.003f,.0034f)
        }
        FireLayer.WAVE->{
            for(i in 0..6)ribbon(i*360f/7f+4f,42f,.298f,.157f,.033f,.0023f)
        }
        FireLayer.TONGUES->{
            for(i in 0..15){
                val a=i*22.5f+2f
                val rise=.028f+((i*7)%9)*.0030f
                val start=radial(a,.282f,.152f,.001f)
                val end=radial(a+7f+(i%3)*1.4f,.295f,.157f,rise)
                flame(start,end,radial(a,1f,1f),.0059f)
            }
        }
        FireLayer.SPARKS->{
            for(i in 0..13){
                val a=i*(360f/14f)+(i%3)*3.5f
                val r=.85f+.034f*((i*5)%8)
                val y=.004f+.006f*((i*3)%7)
                val start=radial(a,.296f*r,.159f*r,y)
                val end=radial(a+1.6f,.296f*r+.029f,.159f*r+.020f,y+.017f+(i%4)*.004f)
                flame(start,end,Point(0f,1f,0f),.0029f+(i%2)*.001f)
            }
        }
    }
}
