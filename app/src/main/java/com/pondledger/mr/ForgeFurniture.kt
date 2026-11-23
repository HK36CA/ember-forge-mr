package com.pondledger.mr

/** Physical extent of the load-bearing pads, in the furniture's local metres. */
internal data class FurnitureFootprint(val bottom: Float, val halfWidth: Float, val halfDepth: Float)

internal fun furnitureFootprint(id: FurnitureId): FurnitureFootprint? = when (id) {
    FurnitureId.BENCH -> FurnitureFootprint(-.116f, .464f, .252f)
    FurnitureId.TRAY -> FurnitureFootprint(-.03848f, .288f, .1415f)
    FurnitureId.TRIBUTE -> FurnitureFootprint(-.087f, .218f, .168f)
    FurnitureId.RELIC -> FurnitureFootprint(-.105f, .248f, .207f)
    FurnitureId.BOARD -> null
}

internal data class FurnitureEnvelope(val halfX:Float,val halfZ:Float,val bottom:Float,val top:Float)
internal fun furnitureEnvelope(id:FurnitureId):FurnitureEnvelope=when(id){
    FurnitureId.BENCH->FurnitureEnvelope(.49f,.28f,-.116f,.204f)
    FurnitureId.TRAY->FurnitureEnvelope(.322f,.17f,-.03848f,.119f)
    FurnitureId.TRIBUTE->FurnitureEnvelope(.24f,.19f,-.087f,.285f)
    FurnitureId.RELIC->FurnitureEnvelope(.27f,.23f,-.105f,.322f)
    FurnitureId.BOARD->FurnitureEnvelope(BOARD_TRIM_WIDTH/2+.009f,.025f,-BOARD_TRIM_HEIGHT/2-.009f,BOARD_TRIM_HEIGHT/2+.009f)
}

internal const val BOARD_TRIM_WIDTH = .82f
internal const val BOARD_TRIM_HEIGHT = .40f
private const val OAK = 0x87553B
private const val CUT_WOOD = 0xB48154
private const val BLACK_IRON = 0x344048
private const val COPPER = 0xC78D54
private const val LEATHER = 0x744631
private const val SEAM = 0xC49769
private const val END_CAP = 0x986849
private const val STEEL = 0x8296A5
private const val WROUGHT = 0x4D5359
private const val FELT = 0x694246
private const val BRASS = 0xB79555
private const val PATINA = 0x647B73
private const val SLATE = 0x393D40

internal object ForgeFurniture {
    val finishes=mapOf(OAK to ForgeFinish.OAK, CUT_WOOD to ForgeFinish.OAK, END_CAP to ForgeFinish.HICKORY,
        BLACK_IRON to ForgeFinish.BLUED, WROUGHT to ForgeFinish.IRON, STEEL to ForgeFinish.STEEL,
        COPPER to ForgeFinish.COPPER, BRASS to ForgeFinish.BRASS, LEATHER to ForgeFinish.LEATHER,
        FELT to ForgeFinish.FELT, PATINA to ForgeFinish.ENAMEL, 0x955D40 to ForgeFinish.OAK,
        0x48535A to ForgeFinish.FIREBRICK)
    fun benchTop() = FurnitureGeometry().apply {
        // Five solid oak boards sit between transverse end caps. A dark base is visible inside the seams.
        block(0f,-.027f,0f,.968f,.012f,.548f,SLATE,.003f)
        val span=.854f
        repeat(5){i->
            block(0f,-.015f,(i-2)*.1115f,span,.030f,.1099f,OAK,.0026f)
        }
        for(side in listOf(-1f,1f)){
            block(side*.4595f,-.016f,0f,.061f,.032f,.56f,END_CAP,.0035f)
            for(z in listOf(-.207f,.207f)){
                block(side*.444f,-.0001f,z,.013f,.001f,.013f,CUT_WOOD,.001f)
            }
        }
        // The nominal four pad contacts stay at their original support-plane coordinates.
        for(x in listOf(-.433f,.433f))for(z in listOf(-.226f,.226f)){
            block(x,-.098f,z,.062f,.036f,.052f,BLACK_IRON,.004f)
        }
        for(z in listOf(-.252f,.252f))block(0f,-.055f,z,.948f,.056f,.034f,OAK,.004f)
        for(x in listOf(-.455f,.455f))block(x,-.055f,0f,.036f,.056f,.476f,OAK,.003f)
    }

    fun benchTrim() = FurnitureGeometry().apply {
        // A lipped drawer has a shadow recess and a separate face; its central pull uses the edit handle.
        block(0f,-.054f,.269f,.546f,.052f,.010f,BLACK_IRON,.003f)
        block(0f,-.054f,.275f,.531f,.045f,.014f,CUT_WOOD,.0035f)
        for (side in listOf(-1f,1f)) {
            block(side*.453f,-.042f,.269f,.065f,.068f,.020f,WROUGHT,.005f)
            block(side*.480f,-.040f,.237f,.014f,.069f,.074f,WROUGHT,.004f)
            for(z in listOf(.213f,.250f))block(side*.486f,-.040f,z,.004f,.010f,.010f,COPPER,.001f)
            for(x in listOf(.434f,.464f))block(side*x,-.042f,.280f,.009f,.009f,.004f,COPPER,.001f)
            block(side*.228f,-.054f,.284f,.024f,.025f,.004f,BRASS,.002f)
            block(side*.228f,-.054f,.2865f,.012f,.003f,.0015f,BLACK_IRON,.0004f)
        }
    }

    fun benchTongs()=FurnitureGeometry().apply{
        // Opposed reins, rounded knuckle and inward jaw tips sit on the back of the bench.
        for(side in listOf(-1f,1f)){
            val rivet=Point(.221f,.013f,-.180f)
            val elbow=Point(.178f,.015f,-.180f+side*.017f)
            strut(Point(.386f,.011f,-.180f+side*.023f),rivet,.011f,.010f,WROUGHT)
            strut(rivet,elbow,.014f,.012f,BLACK_IRON)
            strut(elbow,Point(.153f,.012f,-.180f+side*.006f),.012f,.010f,STEEL)
        }
    }

    fun tray() = FurnitureGeometry().apply {
        val foot=furnitureFootprint(FurnitureId.TRAY)!!
        for (x in listOf(-.261f,.261f)) for (z in listOf(-.1123f,.1123f)){
            block(x,foot.bottom+.005f,z,.054f,.010f,.0584f,BLACK_IRON)
        }
        block(0f,-.015f,0f,.635f,.030f,.332f,OAK,.005f)
        block(0f,.001f,0f,.630f,.006f,.329f,BLACK_IRON,.002f)
        val sideProfile=listOf(-.015f to .156f,-.015f to -.158f,.096f to -.158f,
            .096f to -.129f,.056f to .114f,.051f to .156f)
        for (side in listOf(-1f,1f)) {
            cheek(side*.307f,.029f,sideProfile,OAK)
            strut(Point(side*.307f,.094f,-.131f),Point(side*.307f,.056f,.114f),.031f,.006f,BRASS,.001f)
            for(z in listOf(-.135f,.136f)){
                block(side*.315f,.009f,z,.012f,.042f,.028f,BLACK_IRON,.002f)
                block(side*.321f,.011f,z,.003f,.009f,.009f,COPPER,.001f)
            }
        }
        block(0f,.037f,-.151f,.596f,.101f,.018f,OAK,.004f)
        block(0f,.089f,-.151f,.596f,.007f,.021f,BRASS,.0015f)
        block(0f,.012f,.153f,.611f,.046f,.022f,OAK,.003f)
        block(0f,.0355f,.153f,.613f,.006f,.023f,CUT_WOOD,.001f)
        val divider=listOf(.002f to .146f,.002f to -.146f,.075f to -.146f,.072f to -.120f,
            .043f to .109f,.038f to .146f)
        for (x in listOf(-.099f,.099f)){
            cheek(x,.014f,divider,BLACK_IRON)
            strut(Point(x,.073f,-.128f),Point(x,.043f,.110f),.016f,.004f,COPPER,.0008f)
        }
        val liners=listOf(LEATHER,FELT,BLACK_IRON)
        val badges=listOf(STEEL,BRASS,PATINA)
        for ((i,p) in trayOreTargets.withIndex()) {
            block(p.x,.012f,0f,.174f,.017f,.247f,liners[i],.006f)
            block(p.x,.018f,.166f,.083f,.033f,.005f,BLACK_IRON,.003f)
            for(side in listOf(-1,1))block(p.x+side*.032f,.018f,.1694f,.0044f,.0044f,.002f,COPPER,.0006f)
            repeat(i+1){n->
                block(p.x+(n-i*.5f)*.013f,.018f,.1695f,.0065f,.013f,.002f,badges[i],.0007f)
            }
        }
    }

    fun sideTable(kind:FurnitureId)=FurnitureGeometry().apply{
        val shape=furnitureEnvelope(kind)
        val foot=furnitureFootprint(kind)!!
        val w=shape.halfX*2
        val d=shape.halfZ*2
        val stone=kind==FurnitureId.RELIC
        val top=if(stone)0x48535A else 0x955D40
        block(0f,-.020f,0f,w,.040f,d,top,.009f)
        block(0f,-.044f,0f,w-.012f,.010f,d-.012f,if(stone)0xA07452 else COPPER)
        block(0f,-.058f,0f,w-.028f,.026f,d-.028f,if(stone)BLACK_IRON else OAK)
        for(sx in listOf(-1,1))for(sz in listOf(-1,1)){
            block(sx*(foot.halfWidth-.02f),(foot.bottom-.068f)/2,sz*(foot.halfDepth-.02f),.04f,-.068f-foot.bottom,.04f,BLACK_IRON)
            block(sx*(shape.halfX-.025f),.003f,sz*(shape.halfZ-.025f),.032f,.006f,.032f,COPPER)
        }
        if(stone){
            for(s in listOf(-1,1)){
                block(s*.21f,.012f,0f,.025f,.024f,d-.07f,BLACK_IRON)
                for(i in -2..2)block(s*.211f,.025f,i*.061f,.019f,.003f,.023f,0x77AD9D)
            }
        }else{
            block(0f,.002f,.147f,.350f,.004f,.034f,BLACK_IRON)
            for(i in -6..6)block(i*.023f,.005f,.148f,.003f,.003f,if(i%3==0).022f else .014f,COPPER)
        }
    }

    /** Local y=0 meets the four load pads. The runtime sizes the legs to a single floor plane. */
    fun stand(kind:FurnitureId)=FurnitureGeometry().apply{
        val pad=furnitureFootprint(kind)!!
        val bench=kind==FurnitureId.BENCH
        val stone=kind==FurnitureId.RELIC
        val profile=if(bench).065f else if(stone).058f else .044f
        val x=pad.halfWidth-profile/2
        val z=pad.halfDepth-profile/2
        for(sx in listOf(-1,1))for(sz in listOf(-1,1)){
            block(sx*x,-.5f,sz*z,profile,1f,profile,if(stone)BLACK_IRON else OAK,.004f)
            block(sx*x,-.951f,sz*z,profile+.005f,.094f,profile+.005f,BLACK_IRON)
            block(sx*x,-.123f,sz*z,profile+.002f,.032f,profile+.002f,COPPER)
        }
        for(sz in listOf(-1,1))block(0f,-.070f,sz*z,2*x,.115f,profile*.70f,if(stone)BLACK_IRON else CUT_WOOD)
        for(sx in listOf(-1,1)){
            block(sx*x,-.063f,0f,profile*.72f,.100f,2*z,if(stone)BLACK_IRON else OAK)
            block(sx*x,-.695f,0f,profile*.75f,.060f,2*z,if(stone)BLACK_IRON else OAK)
            // Each rail meets a leg with a visible shoulder and square peg.
            for(sz in listOf(-1,1)){
                val near=Point(sx*x,-.28f,sz*z)
                val upper=Point(sx*(x-minOf(.110f,x*.55f)),-.098f,sz*z)
                strut(near,upper,profile*.52f,profile*.63f,if(stone)WROUGHT else OAK,.002f)
                block(sx*x,-.074f,sz*(z+profile*.49f),profile*.24f,.020f,.005f,if(stone)COPPER else END_CAP,.001f)
            }
        }
        if(kind==FurnitureId.TRAY || stone){
            for(i in 0..2)block(0f,-.68f,(i-1)*z*.67f,2*x,.034f,z*.65f,if(stone)BLACK_IRON else CUT_WOOD)
            // Low open parts bin on the shelf, with visible bottom and cheeks rather than featureless cubes.
            val width=2*x*.64f
            block(-x*.18f,-.648f,0f,width,.024f,z,if(stone)WROUGHT else OAK)
            for(side in listOf(-1,1)){
                block(-x*.18f,-.610f,side*z*.48f,width,.085f,.013f,if(stone)BLACK_IRON else OAK)
                block(-x*.18f+side*(width*.5f-.009f),-.609f,0f,.018f,.088f,z,if(stone)WROUGHT else END_CAP)
            }
        }else{
            block(0f,-.695f,-z*.55f,2*x,.059f,profile,if(bench)OAK else BRASS)
            for(sx in listOf(-1,1))
                block(sx*x,-.698f,-z*.55f,profile*1.22f,.074f,profile*.66f,BLACK_IRON)
        }
    }

    fun boardTrim() = FurnitureGeometry().apply {
        val w=BOARD_TRIM_WIDTH
        val h=BOARD_TRIM_HEIGHT
        // The front lip lives outside the panel. The back has its own recessed layer and two stiffeners.
        block(0f,0f,-.020f,w+.014f,h+.015f,.012f,BLACK_IRON,.004f)
        block(0f,0f,-.026f,w-.021f,h-.024f,.005f,WROUGHT,.002f)
        for (sign in listOf(-1f,1f)) {
            block(0f,sign*(h*.5f+.0036f),-.008f,w+.013f,.0093f,.018f,BLACK_IRON,.002f)
            block(0f,sign*(h*.5f+.0062f),.002f,w+.011f,.0048f,.005f,BRASS,.001f)
            block(sign*(w*.5f+.0036f),0f,-.008f,.0093f,h+.012f,.018f,BLACK_IRON,.002f)
            block(sign*(w*.5f+.0062f),0f,.002f,.0048f,h+.013f,.005f,BRASS,.001f)
            block(sign*w*.29f,0f,-.030f,.012f,h-.042f,.008f,BLACK_IRON)
            for (sy in listOf(-1f,1f)) {
                val x=sign*(w*.5f+.0042f)
                val y=sy*(h*.5f+.0042f)
                block(x-sign*.014f,y,.0043f,.037f,.009f,.006f,COPPER,.0015f)
                block(x,y-sy*.013f,.0043f,.009f,.035f,.006f,COPPER,.0015f)
                block(x-sign*.013f,y,.0078f,.0047f,.0047f,.0018f,BLACK_IRON,.0005f)
            }
        }
    }

    fun handle(kind: FurnitureId) = FurnitureGeometry().apply {
        val span=when(kind){FurnitureId.BENCH->.20f;FurnitureId.BOARD->.144f;else->.16f}
        val radius=if(kind==FurnitureId.BOARD) .009f else .012f
        val hookDepth=when(kind){FurnitureId.BENCH->.046f;FurnitureId.TRAY->.045f;else->.033f}
        val anchorY=if(kind==FurnitureId.BOARD) .030f else -.003f
        for (sign in listOf(-1f,1f)) {
            val x=sign*(span*.5f+.002f)
            block(x,anchorY,-hookDepth,.025f,.031f,.006f,BLACK_IRON,.0025f)
            block(x,anchorY+.0095f,-hookDepth+.0045f,.006f,.006f,.003f,BRASS,.0008f)
            val shoulder=Point(x,anchorY,-hookDepth+.004f)
            val elbow=Point(x,-.002f,-hookDepth*.62f)
            strut(shoulder,elbow,.013f,.011f,COPPER,.0018f)
            strut(elbow,Point(x,0f,0f),.014f,.013f,COPPER,.0018f)
            rail(x,0f,0f,.016f,radius+.0025f,BRASS)
        }
        rail(0f,0f,0f,span,radius,LEATHER,18)
        for (side in listOf(-1f,1f)){
            rail(side*(span*.5f-.016f),0f,0f,.004f,radius+.0015f,COPPER)
        }
        for (j in -2..2)rail(j*span*.105f,0f,0f,.0018f,radius+.0006f,SEAM)
    }

    /** Open corners outline the selected solid while leaving materials and controls visible. */
    fun outline(kind: FurnitureId) = FurnitureGeometry().apply {
        val tint=0x7BE4CD
        val envelope=furnitureEnvelope(kind)
        val halfWidth=envelope.halfX+.011f
        val radius=if(kind==FurnitureId.BOARD)BOARD_TRIM_HEIGHT/2+.018f else envelope.halfZ+.011f
        val leg=if(kind==FurnitureId.TRAY) .026f else .045f
        for(xSign in listOf(-1f,1f))for(s in listOf(-1f,1f)) {
            val x=halfWidth*xSign
            if(kind==FurnitureId.BOARD) {
                block(x-leg*.5f*xSign,s*radius,.007f,leg,.004f,.004f,tint)
                block(x,s*(radius-leg*.5f),.007f,.004f,leg,.004f,tint)
            }else{
                val baseline=if(kind==FurnitureId.TRAY) -.013f else -.020f
                block(x-leg*.5f*xSign,baseline,s*radius,leg,.004f,.004f,tint)
                block(x,baseline,s*(radius-leg*.5f),.004f,.004f,leg,tint)
                block(x,baseline+.009f,s*radius,.004f,.018f,.004f,tint)
            }
        }
    }
}
