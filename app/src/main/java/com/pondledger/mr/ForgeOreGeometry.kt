package com.pondledger.mr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object ForgeOreGeometry {
    private data class Ring(val y:Float,val radius:Float,val zScale:Float=1f,val dx:Float=0f,val dz:Float=0f)

    fun core(kind:Int):FurnitureSurface {
        val rings=when(kind){
            0->listOf(Ring(-.026f,.010f),Ring(-.003f,.033f),Ring(.001f,.033f),Ring(.029f,.0075f))
            1->listOf(Ring(-.0305f,.013f,.82f),Ring(-.017f,.032f,.84f,-.002f),Ring(.011f,.033f,.78f,.002f),
                Ring(.024f,.019f,.75f,.007f,-.003f))
            else->listOf(Ring(-.031f,.011f,.86f),Ring(-.018f,.019f,.88f),Ring(.022f,.018f,.92f,.002f),
                Ring(.036f,.0038f,.86f,.004f,-.001f))
        }
        val sides=when(kind){0->4;1->8;else->6}
        fun ring(r:Ring)=List(sides){i->
            val a=2*PI*(i+.13)/sides
            val noise=if(kind==1)listOf(1f,.79f,1.07f,.94f,.85f,1f,.76f,1.05f)[i] else 1f
            Point(r.dx+r.radius*noise*cos(a).toFloat(),r.y,r.dz+r.radius*noise*r.zScale*sin(a).toFloat())
        }
        val levels=rings.map(::ring)
        val surface=FurnitureSurface()
        surface.face(levels.first())
        surface.face(levels.last().asReversed())
        for(tier in 0 until levels.lastIndex){
            val low=levels[tier];val high=levels[tier+1]
            val v0=1f-(rings[tier].y-rings.first().y)/(rings.last().y-rings.first().y)
            val v1=1f-(rings[tier+1].y-rings.first().y)/(rings.last().y-rings.first().y)
            for(i in 0 until sides){
                val j=(i+1)%sides
                val u0=i.toFloat()/sides;val u1=(i+1).toFloat()/sides
                surface.face(listOf(low[i],high[i],high[j],low[j]),listOf(u0 to v0,u0 to v1,u1 to v1,u1 to v0))
            }
        }
        return surface
    }
}
