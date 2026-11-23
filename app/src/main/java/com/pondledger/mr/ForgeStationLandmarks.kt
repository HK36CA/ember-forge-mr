package com.pondledger.mr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Permanent rear fittings, in the unscaled furniture frame. The working side faces positive Z. */
internal object ForgeStationLandmarks {
    private const val IRON = 0x44545B
    private const val RECESS = 0x201D25
    private const val COPPER = 0xAD794F
    private const val GOLD = 0xE8C882
    private const val JADE = 0x63BBA9
    private const val GLASS = 0xA1CFD6
    private const val CABINET_BACK = 0x345453
    private const val TIMBER = 0x775138
    private const val LID = 0x6A4C3D
    val finishes = mapOf(IRON to ForgeFinish.BLUED, COPPER to ForgeFinish.COPPER, GOLD to ForgeFinish.BRASS,
        CABINET_BACK to ForgeFinish.ENAMEL, TIMBER to ForgeFinish.OAK, LID to ForgeFinish.IRON)

    /** Raised solids from a counterclockwise convex front outline. */
    private fun FurnitureGeometry.relief(
        x: Float, y: Float, front: Float, depth: Float, color: Int,
        vararg outline: Pair<Float, Float>
    ) {
        val surface = surfaces.getOrPut(color) { FurnitureSurface() }
        val near = outline.map { (dx, dy) -> Point(x + dx, y + dy, front) }
        val far = near.map { it.copy(z = front - depth) }
        surface.face(near)
        surface.face(far.asReversed())
        near.indices.forEach { i ->
            val next = (i + 1) % near.size
            surface.face(listOf(near[i], far[i], far[next], near[next]))
        }
    }

    fun tributeWindow() = FurnitureGeometry().apply {
        // Offset from the existing scale dial so the eye and needle remain visible side by side.
        val x = -.129f
        val y = .213f
        relief(x, y, -.173f, .011f, IRON,
            -.103f to -.078f, .103f to -.078f, .103f to .038f,
            .085f to .058f, .045f to .071f, -.045f to .071f, -.085f to .058f, -.103f to .038f)
        relief(x, y, -.170f, .003f, RECESS,
            -.093f to -.064f, .093f to -.064f, .093f to .031f,
            .077f to .046f, .040f to .058f, -.040f to .058f, -.077f to .046f, -.093f to .031f)
        // Recessed cast housing with a raised folded brow and two bolted diagonal feet.
        for (side in listOf(-1, 1)) {
            val post = x + side * .099f
            block(post, .121f, -.163f, .013f, .239f, .028f, IRON)
            block(post,.007f,-.151f,.027f,.014f,.058f,IRON)
            pin(Point(post,.013f,-.131f),Point(post,.069f,-.158f),.0047f,COPPER)
            turn(post,.014f,-.131f,8,Turn(0f,.0055f,COPPER),Turn(.0018f,.0055f,GOLD))
            pin(Point(post,.233f,-.161f),Point(x+side*.081f,.263f,-.161f),.0038f,COPPER)
            pin(Point(x+side*.081f,.263f,-.161f),Point(x+side*.041f,.277f,-.161f),.0038f,COPPER)
        }
        rail(x,.278f,-.161f,.085f,.0038f,COPPER,8)
        rail(x, .151f, -.152f, .203f, .0045f, COPPER, 8)
        for (offset in listOf(-.072f, -.036f, .027f, .065f)) {
            val h = if (kotlin.math.abs(offset) > .06f) .108f else .119f
            pin(Point(x+offset,.151f,-.151f),Point(x+offset,.151f+h,-.151f),.0034f,IRON,8)
            turn(x+offset,0f,-.151f,8,Turn(.151f,.005f,COPPER),Turn(.155f,.005f,COPPER),Turn(.158f,.0038f,IRON))
        }
        relief(x,y+.004f,-.1645f,.003f,LID,
            -.070f to .001f, -.035f to .018f, .002f to .024f, .039f to .018f, .062f to .004f,
            .025f to .012f, -.005f to .016f, -.034f to .010f)
        relief(x, y+.004f, -.159f, .002f, RECESS,
            -.0045f to 0f, 0f to -.013f, .0045f to 0f, 0f to .013f)
    }

    /** A separate emissive mesh lets the pupil and bars cast a dark silhouette across the eye. */
    fun tributeEye() = FurnitureGeometry().apply {
        relief(-.129f, .217f, -.163f, .0025f, 0xBB4F31,
            -.065f to -.002f, -.031f to -.014f, .004f to -.018f, .032f to -.011f,
            .062f to .004f, .027f to .013f, -.004f to .017f, -.034f to .011f)
        relief(-.129f,.217f,-.1615f,.0012f,0xF39140,
            -.024f to -.004f, -.012f to -.014f, .004f to -.016f, .021f to -.005f,
            .022f to .005f, .008f to .013f, -.008f to .015f, -.024f to .008f)
        relief(-.137f,.224f,-.158f,.0012f,0xFFDCA0,
            -.0026f to 0f, 0f to -.0022f, .0034f to .0008f, 0f to .0031f)
    }

    fun relicCabinet() = FurnitureGeometry().apply {
        val face = -.191f
        block(0f, .234f, -.207f, .442f, .157f, .016f, TIMBER, .005f)
        for (x in listOf(-.14f,0f,.14f)) {
            block(x,.235f,-.197f,.126f,.127f,.007f,CABINET_BACK,.005f)
            // A ledge supports the exhibits and a small round foot holds them above its front lip.
            block(x,.179f,-.183f,.114f,.009f,.034f,TIMBER,.003f)
            turn(x,.184f,-.181f,20,Turn(0f,.018f,COPPER),Turn(.003f,.018f,GOLD),Turn(.0055f,.013f,COPPER))
        }
        for (side in listOf(-1, 1)) {
            val leg=side*.211f
            block(leg,.151f,-.190f,.017f,.302f,.043f,TIMBER)
            block(leg,.009f,-.185f,.040f,.018f,.065f,IRON)
            pin(Point(leg,.014f,-.160f),Point(leg,.093f,-.185f),.0062f,COPPER)
            block(leg,.187f,-.187f,.022f,.012f,.049f,COPPER)
        }
        block(0f,.305f,-.193f,.452f,.016f,.057f,TIMBER,.003f)
        block(0f,.315f,-.194f,.437f,.006f,.046f,COPPER,.002f)
        rail(0f,.297f,-.167f,.444f,.0025f,GOLD,8)
        block(0f, .171f, -.190f, .439f, .012f, .060f, IRON)
        for (x in listOf(-.070f, .070f)) block(x, .236f, -.184f, .007f, .130f, .029f, COPPER)

        // Three distinctive silhouettes on one recessed shelf: hourglass, moth and faceted ring.
        val hourglass = -.139f
        for(y in listOf(.190f,.266f)) turn(hourglass,y,-.181f,16,
            Turn(0f,.027f,COPPER),Turn(.0035f,.030f,GOLD),Turn(.007f,.026f,GOLD))
        for (side in listOf(-1, 1)) pin(Point(hourglass+side*.025f,.197f,-.177f),
            Point(hourglass+side*.025f,.266f,-.177f),.0021f,COPPER)
        turn(hourglass,0f,-.181f,12,Turn(.198f,.014f,GLASS),Turn(.203f,.018f,GLASS),Turn(.212f,.017f,GLASS),
            Turn(.229f,.0035f,GOLD),Turn(.233f,.0035f,GLASS),Turn(.251f,.017f,GLASS),
            Turn(.259f,.018f,GLASS),Turn(.265f,.014f,GLASS))

        relief(0f, .229f, -.178f, .007f, JADE,
            -.044f to -.001f, -.008f to -.015f, -.003f to .005f, -.043f to .041f, -.050f to .024f)
        relief(0f, .229f, -.178f, .007f, JADE,
            .003f to .005f, .008f to -.015f, .044f to -.001f, .050f to .024f, .043f to .041f)
        relief(0f, .225f, -.175f, .010f, GOLD,
            -.006f to .008f, 0f to -.030f, .006f to .008f, 0f to .020f)
        for(s in listOf(-1,1)){
            pin(Point(s*.007f,.231f,-.175f),Point(s*.042f,.258f,-.177f),.0014f,GOLD,6)
            pin(Point(s*.007f,.229f,-.175f),Point(s*.030f,.224f,-.177f),.0011f,COPPER,6)
            pin(Point(s*.002f,.241f,-.174f),Point(s*.011f,.255f,-.176f),.0012f,COPPER,6)
        }
        pin(Point(0f,.186f,-.181f),Point(0f,.213f,-.184f),.0035f,COPPER)

        val ring = surfaces.getOrPut(GOLD) { FurnitureSurface() }
        val n = 14
        fun point(i: Int, radius: Float, z: Float): Point {
            val angle = 2 * PI * i / n
            return Point(.140f + radius * cos(angle).toFloat(), .229f + radius * sin(angle).toFloat(), z)
        }
        repeat(n) { i ->
            val j = (i + 1) % n
            val outer = point(i, .036f, -.177f)
            val nextOuter = point(j, .036f, -.177f)
            val inner = point(i, .024f, -.177f)
            val nextInner = point(j, .024f, -.177f)
            ring.face(listOf(outer, nextOuter, nextInner, inner))
            ring.face(listOf(outer, outer.copy(z=face), nextOuter.copy(z=face), nextOuter))
            ring.face(listOf(inner, nextInner, nextInner.copy(z=face), inner.copy(z=face)))
        }
        turn(.140f,.262f,-.180f,8,Turn(0f,.014f,COPPER),Turn(.003f,.014f,GOLD),Turn(.004f,.0117f,GOLD),
            Turn(.009f,.008f,JADE),Turn(.011f,.005f,GLASS),facesFront=true)
        pin(Point(.14f,.185f,-.182f),Point(.14f,.195f,-.182f),.004f,COPPER)

        // A single horizontal intake, below the left bay and offset from the round working crucible.
        block(-.150f, .135f, -.188f, .150f, .054f, .037f, IRON, .004f)
        block(-.150f, .135f, -.167f, .119f, .018f, .007f, RECESS)
        for (y in listOf(.122f, .148f)) block(-.150f, y, -.165f, .131f, .007f, .016f, COPPER)
        for(s in listOf(-1,1)) block(-.15f+s*.066f,.135f,-.167f,.006f,.031f,.012f,IRON)
    }
}
