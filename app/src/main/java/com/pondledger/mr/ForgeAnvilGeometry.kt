package com.pondledger.mr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Visual castings in the original anvil and blade-fitting frames. */
internal object ForgeAnvilGeometry {
    private const val CAST = 0x485561
    private const val BLUE_IRON = 0x323F47
    private const val GROUND = 0xABB7BC
    private const val GOLD = 0xC39B5F
    private const val WRAP = 0x6B4439
    private const val WINDING = 0xB67D55
    val finishes = mapOf(CAST to ForgeFinish.IRON, BLUE_IRON to ForgeFinish.BLUED, GROUND to ForgeFinish.STEEL,
        GOLD to ForgeFinish.BRASS, WRAP to ForgeFinish.LEATHER, WINDING to ForgeFinish.COPPER)

    private class Section(val points:List<Point>,val finish:Int)
    private fun length(v:Point)=sqrt(v.dot(v))

    /** All sections have the same winding and vertex order. The longitudinal seam unwraps once. */
    private fun FurnitureGeometry.shell(sections:List<Section>) {
        val middle=sections[sections.size/2].points
        val width=FloatArray(middle.size+1)
        for(i in middle.indices)width[i+1]=width[i]+length(middle[(i+1)%middle.size]-middle[i])
        val height=FloatArray(sections.size)
        for(i in 1 until sections.size) {
            var span=0f
            for(j in middle.indices)span+=length(sections[i].points[j]-sections[i-1].points[j])
            height[i]=height[i-1]+span/middle.size
        }
        fun cap(index:Int,reverse:Boolean) {
            val s=sections[index]
            surfaces.getOrPut(s.finish){FurnitureSurface()}.face(if(reverse)s.points.asReversed() else s.points)
        }
        cap(0,true)
        for(i in 0 until sections.lastIndex) {
            val a=sections[i]
            val b=sections[i+1]
            val surface=surfaces.getOrPut(a.finish){FurnitureSurface()}
            val v0=height[i]/height.last()
            val v1=height[i+1]/height.last()
            for(j in middle.indices){
                val next=(j+1)%middle.size
                val u0=width[j]/width.last()
                val u1=width[j+1]/width.last()
                surface.face(listOf(a.points[j],a.points[next],b.points[next],b.points[j]),
                    listOf(u0 to v0,u1 to v0,u1 to v1,u0 to v1))
            }
        }
        cap(sections.lastIndex,false)
    }

    /** Rounded horizontal casting section with real straight edges and three facets at each corner. */
    private fun slab(y:Float,x:Float,hx:Float,hz:Float,corner:Float,finish:Int):Section {
        val points=mutableListOf<Point>()
        for(k in 0..3) {
            val theta= -k*PI/2
            val cx=x+(hx-corner)*if(k==0 || k==3)1 else -1
            val cz=(hz-corner)*if(k<2)-1 else 1
            for(j in 0..3) {
                val a=theta-j*PI/6
                points+=Point(cx+corner*cos(a).toFloat(),y,cz+corner*sin(a).toFloat())
            }
        }
        return Section(points,finish)
    }

    private fun xSection(x:Float,cy:Float,cz:Float,ry:Float,rz:Float,sides:Int,finish:Int,
                         flatten:Float=1f):Section = Section(List(sides){j->
        val a=2*PI*j/sides
        val c=cos(a).toFloat()
        Point(x,cy+ry*sign(c)*abs(c).pow(flatten),cz+rz*sin(a).toFloat())
    },finish)

    /** Interpolate along the waist only. The sole, face and bevels retain their hard transitions. */
    private fun sweepWaist(profile:List<Section>):List<Section> = buildList {
        for(i in 0 until profile.lastIndex){
            val a=profile[i]
            val b=profile[i+1]
            add(a)
            if(i !in 3..11)continue
            val before=profile[i-1]
            val after=profile[i+2]
            val dy=b.points.first().y-a.points.first().y
            for(step in 1..3){
                val t=step*.25f
                val t2=t*t
                val t3=t2*t
                val points=a.points.indices.map{j->
                    val p=a.points[j]
                    val q=b.points[j]
                    val incoming=(q-before.points[j])*(dy/(q.y-before.points[j].y))
                    val outgoing=(after.points[j]-p)*(dy/(after.points[j].y-p.y))
                    p*(2*t3-3*t2+1)+incoming*(t3-2*t2+t)+q*(-2*t3+3*t2)+outgoing*(t3-t2)
                }
                add(Section(points,a.finish))
            }
        }
        add(profile.last())
    }

    fun casting()=FurnitureGeometry().apply {
        // The final bearing plane is the original .0985. At station scale the blade underside clears it by .175 mm.
        shell(sweepWaist(listOf(
            slab(.0023f,-.001f,.071f,.041f,.011f,BLUE_IRON),
            slab(.0044f,-.001f,.075f,.044f,.011f,CAST),
            slab(.0115f,-.001f,.074f,.043f,.010f,CAST),
            slab(.0165f,-.001f,.068f,.039f,.011f,CAST),
            slab(.0212f,-.001f,.059f,.035f,.012f,CAST),
            slab(.0285f,-.001f,.050f,.031f,.012f,CAST),
            slab(.0385f,-.002f,.043f,.027f,.012f,CAST),
            slab(.0505f,-.004f,.041f,.0257f,.012f,CAST),
            slab(.0576f,-.004f,.043f,.0266f,.011f,CAST),
            slab(.0648f,-.002f,.050f,.030f,.010f,CAST),
            slab(.0714f,-.001f,.061f,.034f,.009f,CAST),
            slab(.0768f,-.002f,.072f,.0375f,.008f,CAST),
            slab(.0804f,-.005f,.0825f,.0395f,.005f,CAST),
            slab(.0842f,-.005f,.0870f,.0425f,.0038f,BLUE_IRON),
            slab(.0894f,-.005f,.0875f,.0435f,.0032f,GROUND),
            slab(.0966f,-.005f,.0875f,.0435f,.0032f,GROUND),
            slab(.0985f,-.005f,.0858f,.0420f,.0033f,BLUE_IRON)
        )))
        // Forged pads extend from the lower casting and land on the existing bedding.
        for(sx in listOf(-1,1))for(sz in listOf(-1,1)){
            val x=sx*.058f
            val z=sz*.034f
            fun pad(scale:Float,y:Float,finish:Int)=Section(List(12){i->
                val a=2*PI*i/12
                Point(x+scale*.023f*cos(a).toFloat(),y,z-scale*.017f*sin(a).toFloat())
            },finish)
            shell(listOf(pad(.91f,.0023f,BLUE_IRON),pad(1f,.0043f,CAST),
                pad(.97f,.010f,CAST),pad(.68f,.015f,CAST)))
            turn(sx*.065f,.015f,sz*.042f,8,Turn(0f,.0037f,BLUE_IRON),
                Turn(.0016f,.0037f,GROUND),Turn(.0021f,.0028f,BLUE_IRON))
        }
        // Progressively flattened sections merge into the underside of the face at the horn root.
        shell(listOf(
            xSection(-.145f,.0880f,0f,.0010f,.0017f,18,GROUND),
            xSection(-.141f,.0876f,0f,.0021f,.0036f,18,GROUND),
            xSection(-.131f,.0863f,0f,.0045f,.0080f,18,GROUND),
            xSection(-.117f,.0842f,0f,.0080f,.0140f,18,GROUND,.90f),
            xSection(-.103f,.0820f,0f,.0115f,.0215f,18,GROUND,.80f),
            xSection(-.091f,.0806f,0f,.0140f,.0280f,18,BLUE_IRON,.73f),
            xSection(-.079f,.0805f,0f,.0159f,.0346f,18,CAST,.68f),
            xSection(-.071f,.0810f,0f,.0155f,.0365f,18,CAST,.67f)
        ))
    }

    fun fittings()=FurnitureGeometry().apply {
        val centerY=.00165f
        fun guard(t:Float):Section {
            val a=abs(t)
            // A thicker central socket flows out to two narrow swept tips.
            val width=.0047f-.0021f*a-.0015f*a*a
            val height=.0055f-.0031f*a-.0012f*a*a
            return Section(List(12){i->
                val theta=2*PI*i/12
                Point(.0529f-.0073f*a*a+width*cos(theta).toFloat(),
                    centerY+.001f*a+height*sin(theta).toFloat(),t*.0364f)
            },GOLD)
        }
        shell(listOf(-1f,-.94f,-.81f,-.59f,-.34f,-.16f,0f,.16f,.34f,.59f,.81f,.94f,1f).map(::guard))
        shell(listOf(xSection(.050f,centerY,0f,.0048f,.0132f,10,GOLD),
            xSection(.0529f,centerY,0f,.0056f,.0126f,10,GOLD),
            xSection(.0556f,centerY,0f,.0053f,.0098f,10,GOLD),
            xSection(.0580f,centerY,0f,.0050f,.0075f,10,GOLD)))

        fun swell(t:Float)=sin(PI*t).toFloat()
        fun gripX(t:Float)=.0578f+.0426f*t
        fun gripY(t:Float)=.00415f+.00135f*swell(t)
        fun gripZ(t:Float)=.00555f+.0021f*swell(t)
        shell((0..8).map{i->
            val t=i/8f
            xSection(gripX(t),centerY,0f,gripY(t),gripZ(t),18,WRAP)
        })
        // One copper winding follows the oval leather section and terminates beneath the ferrules.
        val winding=(0..52).map { i->
            val t=i/52f
            val a=2*PI*(3.75*t+.12f)
            val c=cos(a).toFloat();val s=sin(a).toFloat()
            Section(List(6){j->
                val b=2*PI*j/6
                val along=-.00037f*sin(b).toFloat()
                val radial=.00041f*cos(b).toFloat()
                Point(gripX(t)+along,centerY+(gripY(t)+.00013f+radial)*c,(gripZ(t)+.00013f+radial)*s)
            },WINDING)
        }
        shell(winding)
        for(x in listOf(.0578f,.0988f))shell(listOf(
            xSection(x,centerY,0f,.0049f,.0068f,18,WINDING),
            xSection(x+.00065f,centerY,0f,.0053f,.0073f,18,GOLD),
            xSection(x+.0021f,centerY,0f,.0050f,.0068f,18,GOLD)
        ))
        shell(listOf(xSection(.1000f,centerY,0f,.0044f,.0058f,8,GOLD),
            xSection(.1017f,centerY,0f,.0057f,.0088f,8,GOLD),
            xSection(.1050f,centerY,0f,.0077f,.0118f,8,GOLD),
            xSection(.1083f,centerY,0f,.0062f,.0092f,8,GOLD),
            xSection(.1118f,centerY,0f,.0025f,.0038f,8,GOLD)))
    }
}
