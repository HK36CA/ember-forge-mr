package com.pondledger.mr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Workshop metal profiles. A point on the section owns the finish of the strip above it. */
internal data class Turn(val y: Float, val radius: Float, val color: Int)

internal fun FurnitureGeometry.turn(
    x: Float, y: Float, z: Float, sides: Int, vararg profile: Turn, facesFront: Boolean = false, capStart:Boolean=true
) {
    fun ring(s: Turn) = List(sides) { i ->
        val theta = 2 * PI * i / sides
        val dx = s.radius * cos(theta).toFloat()
        val dz = s.radius * sin(theta).toFloat()
        if (facesFront) Point(x + dx, y - dz, z + s.y) else Point(x + dx, y + s.y, z + dz)
    }
    var lower = ring(profile.first())
    if(capStart) surfaces.getOrPut(profile.first().color) { FurnitureSurface() }.face(lower)
    // Preserve the grain around the full circumference and through changes of radius at the lip.
    val path = profile.asList().zipWithNext { a,b -> hypot(b.y-a.y,b.radius-a.radius) }
    val length = path.sum().coerceAtLeast(.00001f)
    var distance=0f
    for (j in 1 until profile.size) {
        val upper = ring(profile[j])
        val mat = surfaces.getOrPut(profile[j-1].color) { FurnitureSurface() }
        val bottomV=distance/length
        distance+=path[j-1]
        val topV=distance/length
        repeat(sides) { i ->
            val next = (i + 1) % sides
            val left=i.toFloat()/sides
            val right=(i+1f)/sides
            mat.face(listOf(lower[i], upper[i], upper[next], lower[next]),
                listOf(left to bottomV,left to topV,right to topV,right to bottomV))
        }
        lower = upper
    }
    surfaces.getOrPut(profile.last().color) { FurnitureSurface() }.face(lower.asReversed())
}

/** Small cast rods and braces have a hexagonal or octagonal section, independent of orientation. */
internal fun FurnitureGeometry.pin(a: Point, b: Point, radius: Float, color: Int, sides: Int = 7) {
    val shaft = (b-a).unit() ?: return
    val ref = if (abs(shaft.y) > .85f) Point(1f,0f,0f) else Point(0f,1f,0f)
    val u = shaft.cross(ref).unit() ?: return
    val v = shaft.cross(u)
    val foot = List(sides) { i ->
        val t = 2*PI*i/sides
        a + u * (radius*cos(t).toFloat()) + v * (radius*sin(t).toFloat())
    }
    val head = foot.map { it + (b-a) }
    val mat = surfaces.getOrPut(color) { FurnitureSurface() }
    mat.face(foot.asReversed())
    mat.face(head)
    repeat(sides) { i ->
        val n = (i+1)%sides
        mat.face(listOf(foot[i],foot[n],head[n],head[i]))
    }
}

internal object ForgeStationVessels {
    const val IRON = 0x46545D
    const val OXIDE = 0x303940
    const val BRASS = 0xB27F48
    const val EDGE = 0xD7AF70
    const val VELVET = 0x793944
    const val STONE = 0x705247
    private const val INSET = 0x202C31
    private const val MARK = 0x4C372A
    private const val ENAMEL = 0x426761
    private const val DIAL_TICK = 0xF1DCB0
    val finishes = mapOf(IRON to ForgeFinish.IRON, OXIDE to ForgeFinish.BLUED,
        BRASS to ForgeFinish.BRASS, EDGE to ForgeFinish.BRASS, VELVET to ForgeFinish.FELT,
        STONE to ForgeFinish.FIREBRICK, ENAMEL to ForgeFinish.ENAMEL)

    fun balance() = FurnitureGeometry().apply {
        // One casting joins the carriage, spindle and pan. Its outer lip curves back into a shallow bowl.
        turn(0f,0f,0f,36,Turn(.001f,.056f,OXIDE),Turn(.005f,.063f,IRON),Turn(.012f,.063f,BRASS),
            Turn(.015f,.059f,IRON),Turn(.019f,.049f,OXIDE),Turn(.021f,.033f,IRON),Turn(.0215f,.029f,IRON))
        turn(0f,0f,0f,22,Turn(.019f,.026f,BRASS),Turn(.025f,.027f,EDGE),Turn(.028f,.021f,IRON),
            Turn(.036f,.016f,BRASS),Turn(.045f,.020f,BRASS),Turn(.051f,.036f,IRON),Turn(.052f,.035f,IRON))
        turn(0f,0f,0f,44,Turn(.047f,.021f,BRASS),Turn(.050f,.046f,BRASS),Turn(.056f,.071f,BRASS),
            Turn(.064f,.084f,EDGE),Turn(.068f,.087f,BRASS),Turn(.071f,.085f,EDGE),Turn(.071f,.081f,BRASS),
            Turn(.067f,.077f,BRASS),Turn(.062f,.066f,BRASS),Turn(.059f,.051f,BRASS))
        turn(0f,0f,0f,40,Turn(.0647f,.071f,VELVET),Turn(.0629f,.066f,VELVET),
            Turn(.0607f,.054f,VELVET),Turn(.0603f,.046f,VELVET),capStart=false)
        val stacks = listOf(Triple(-.022f,.012f,3), Triple(.009f,-.019f,5), Triple(.033f,.016f,2))
        for ((x,z,count) in stacks) {
            for (n in 0 until count) {
                val cx=x+(if(n%2==0) .0014f else -.0005f)
                turn(cx,.0608f+n*.0036f,z,22,Turn(0f,.0157f,BRASS),Turn(.00065f,.0174f,BRASS),
                    Turn(.0029f,.0174f,EDGE),Turn(.0035f,.0156f,EDGE))
            }
            val y=.0608f+count*.0036f
            // Three incuse-looking strokes form the treasury's maker mark on the uppermost coin.
            for ((x1,z1,x2,z2) in listOf(listOf(-.006f,.002f,0f,-.006f),listOf(0f,-.006f,.006f,.002f),listOf(-.003f,.002f,.003f,.002f))) {
                pin(Point(x+x1,y,z+z1),Point(x+x2,y,z+z2),.00067f,MARK,5)
            }
        }

        block(0f,.004f,-.077f,.048f,.008f,.038f,IRON)
        for (sign in listOf(-1,1)) {
            pin(Point(sign*.016f,.006f,-.083f),Point(sign*.013f,.087f,-.087f),.0046f,IRON,8)
            pin(Point(sign*.018f,.038f,-.082f),Point(sign*.008f,.057f,-.038f),.0027f,BRASS)
        }
        // The face and hand are forward of the pillar and dial casing in the player's view.
        turn(0f,.111f,-.085f,40,Turn(-.006f,.031f,IRON),Turn(-.003f,.037f,BRASS),
            Turn(.004f,.038f,EDGE),Turn(.007f,.036f,BRASS),Turn(.0074f,.032f,ENAMEL),facesFront=true)
        for (i in 0..10) {
            val a=(-125+i*25)*PI/180
            val rx=sin(a).toFloat(); val ry=cos(a).toFloat()
            val r=if(i%2==0) .023f else .026f
            pin(Point(rx*r,.111f+ry*r,-.07715f),Point(rx*.029f,.111f+ry*.029f,-.07715f),
                if(i%2==0) .0011f else .00075f,DIAL_TICK,5)
        }
        pin(Point(.006f,.103f,-.075f),Point(-.011f,.131f,-.075f),.0017f,0xFFAE70)
        turn(0f,.112f,-.075f,12,Turn(0f,.0032f,BRASS),Turn(.0015f,.0027f,MARK),facesFront=true)
        for(i in 0..2){
            val r=.008f+i*.002f
            turn(.104f,0f,-.050f+i*.035f,14,Turn(.001f,r,OXIDE),Turn(.004f,r+.001f,IRON),
                Turn(.014f+i*.004f,r*.86f,BRASS),Turn(.017f+i*.004f,r*.52f,BRASS),
                Turn(.020f+i*.004f,r*.55f,EDGE))
        }
    }

    fun kiln() = FurnitureGeometry().apply {
        // A continuous foot, shoulder, rolled rim, sloping inner wall and recessed hearth.
        turn(0f,0f,0f,40,Turn(.001f,.064f,OXIDE),Turn(.004f,.077f,IRON),Turn(.009f,.082f,OXIDE),
            Turn(.015f,.081f,IRON),Turn(.019f,.073f,BRASS),Turn(.025f,.070f,OXIDE),Turn(.035f,.066f,IRON),
            Turn(.055f,.073f,IRON),Turn(.071f,.075f,BRASS),Turn(.076f,.078f,EDGE),
            Turn(.079f,.078f,BRASS),Turn(.081f,.074f,IRON),Turn(.086f,.077f,IRON),Turn(.090f,.076f,EDGE),
            Turn(.092f,.072f,IRON),Turn(.091f,.066f,IRON),Turn(.085f,.063f,STONE),
            Turn(.072f,.057f,STONE),Turn(.062f,.047f,INSET),Turn(.060f,.032f,INSET))
        // Short external retaining bosses leave visible iron between the refractory ribs.
        for (i in 0 until 10) {
            val theta=2*PI*i/10
            val x=cos(theta).toFloat();val z=sin(theta).toFloat()
            pin(Point(x*.067f,.029f,z*.067f),Point(x*.074f,.065f,z*.074f),.0053f,STONE,6)
            pin(Point(x*.073f,.063f,z*.073f),Point(x*.077f,.063f,z*.077f),.0040f,BRASS,8)
        }
        for(side in listOf(-1,1)){
            val sx=side.toFloat()
            pin(Point(sx*.072f,.052f,-.018f),Point(sx*.084f,.057f,-.013f),.004f,IRON)
            pin(Point(sx*.072f,.052f,.018f),Point(sx*.084f,.057f,.013f),.004f,IRON)
            pin(Point(sx*.084f,.057f,-.013f),Point(sx*.084f,.057f,.013f),.005f,BRASS)
        }
    }

    fun kilnLight() = FurnitureGeometry().apply {
        // Narrow seams and faceted material emerge from below the lip rather than hovering over it.
        val palette=listOf(0x4CAEA5,0x88DCD0,0x6492B4)
        for (i in 0 until 4) {
            val angle=2*PI*i/4+.5
            val dx=cos(angle).toFloat();val dz=sin(angle).toFloat()
            val x=dx*.028f;val z=dz*.028f
            val peak=if(i%2==0).106f else .095f
            val base=(0 until 5).map { k->
                val a=2*PI*k/5+.4
                Point(x+.009f*cos(a).toFloat(),.061f,z+.009f*sin(a).toFloat())
            }
            val shoulder=base.map{p->Point(p.x+dx*.004f,peak-.011f,p.z+dz*.004f)}
            val tip=Point(x+dx*.0055f,peak,z+dz*.0055f)
            for (j in base.indices) {
                val next=(j+1)%5
                val skin=surfaces.getOrPut(palette[(i+j)%palette.size]){FurnitureSurface()}
                skin.face(listOf(base[j],shoulder[j],shoulder[next],base[next]))
                skin.face(listOf(shoulder[j],tip,shoulder[next]))
            }
        }
        for (i in 0 until 8) {
            val a=2*PI*i/8+.18
            val dx=cos(a).toFloat();val dz=sin(a).toFloat()
            pin(Point(dx*.040f,.0619f,dz*.040f),Point(dx*.054f,.069f,dz*.054f),.0010f,0x83CABC,6)
        }
    }
}
