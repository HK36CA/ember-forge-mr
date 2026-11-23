package com.pondledger.mr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ForgeInputSmoke {
    @Test
    fun eyeSamplesDriveThreePointCheckWithEitherClock() {
        val panel=BenchPlacement(Point(.73f,1.4f,-.9f),.63f)
        val origin=panel.world(Point(0f,0f,.56f))
        for(clockBase in listOf(452100L,1_793_000_000_000L)){
            val samples=EyeSamples()
            val check=EyeCheckSequence()
            var now=200L
            var locked=0
            var lastRay:Aim?=null
            for((i,x) in EyeCheckSequence.targets.withIndex()){
                val target=panel.world(Point((x-.5f)*.54f,0f,0f))
                val ray=Aim(origin,(target-origin).unit()!!)
                lastRay=ray
                for(frame in 0..7){
                    now+=40
                    samples.offer(clockBase+now,now,ray)
                    val hit=gazeOnPanel(samples.reading(now)!!.ray!!,panel,.54f,.18f)
                    locked=check.update(now,true,hit,3f)
                    if(frame<7)assertEquals(i,locked)
                }
                assertEquals(i+1,locked)
            }
            samples.offer(clockBase+now,now+130,lastRay)
            assertEquals(now,samples.reading(now+130)!!.sampledAt)
            assertNull(samples.reading(now+161))
            check.update(now+161,false,null,3f)
            assertEquals(0,check.update(now+1361,false,null,3f))
        }
    }

    @Test
    fun grabCarryAndPlaceWithGazeAndWithPointer() {
        // A translated, rotated bench exercises the same global-to-table conversion as the headset.
        val room=BenchPlacement()
        assertTrue(room.place(Aim(Point(.43f, 1.58f, -.14f), Point(-.6f, 0f, -.8f))))
        val frames=WorkshopFrames(room,WorkshopLayout(
            bench=FurniturePose(.18f,.055f,-.10f,.24f),
            tray=FurniturePose(-.13f,.08f,.11f,-.33f),
            board=FurniturePose(.20f,.60f,-.64f,.30f)))
        val bench=frames.bench
        val resting = Point(.04f, .18f, .36f)
        fun ray(from: Point, to: Point): Aim {
            val origin = bench.world(from)
            val delta = bench.world(to) - origin
            return Aim(origin, delta * (1f / sqrt(delta.dot(delta))))
        }
        val ore = 2
        // Close centres overlap at the new 17.2 cm gaze radius; follow the eye and skip occluded ore.
        val straight = Aim(Point(0f,0f,0f),Point(0f,0f,-1f))
        val competingOres = listOf(Point(.155f,0f,-.64f),Point(.11f,0f,-.92f))
        assertEquals(1,gazeTarget(straight,competingOres,.086f*GAZE_RADIUS_MULTIPLIER){true})
        assertEquals(0,gazeTarget(straight,competingOres,.086f*GAZE_RADIUS_MULTIPLIER){it==competingOres[0]})
        assertNull(gazeTarget(straight,listOf(Point(.174f,0f,-.9f)),.086f*GAZE_RADIUS_MULTIPLIER){true})
        val pointAtOre = ray(resting, frames.oreInBench[ore])
        val panelAim = ray(resting,bench.localPoint(frames.board.center))
        val away = ray(resting, Point(.80f, .12f, .00f))
        val viewpoint = Point(0f,.48f,.66f)
        val outside = (frames.oreInBench[ore] - frames.oreInBench[ore - 1]).unit()!!
        val lineOfSight = (frames.oreInBench[ore] - viewpoint).unit()!!
        val acrossSight = (outside - lineOfSight * outside.dot(lineOfSight)).unit()!!
        val gazeAtOre = ray(viewpoint, frames.oreInBench[ore] + acrossSight * .125f)
        val idleSlots = (0..3).map { Pointer(away, bench.world(resting), 0f, it) }

        for (gazeEnabled in listOf(true, false)) for (source in listOf(0, 3)) {
            val picker = ForgePicking()
            val events = mutableListOf<ForgePress>()
            val highlights = mutableListOf<Int>()
            val followed = mutableListOf<Point>()
            var state = ForgeSceneState(active=true, eyeEnabled=gazeEnabled, materialMode=true)
            val controller = source < 2
            fun feed(time: Long, eye: Aim?, aim: Aim, joint: Point, amount: Float, competing: Boolean=false) {
                val slots = idleSlots.toMutableList()
                val usesGrip = controller && gazeEnabled
                slots[source] = Pointer(aim, bench.world(joint), if (usesGrip) 0f else amount,
                    source, if (usesGrip) amount else null)
                // A second, tracked source remains neutral until the first already owns the ore.
                val other = if (controller) 3 else 0
                if (competing) slots[other] = Pointer(pointAtOre, bench.world(resting), 1f, other)
                picker.acceptFrame(bench, state, time, eye, slots, .82f, .40f, highlights::add, { event ->
                    events.add(event)
                    state = state.copy(heldMaterial=if (event.type==ForgeInputAction.Grab) event.station else -1)
                }, { p -> if (p!=null) followed.add(p) },frames=frames)
            }
            // A real grip may grab the gazed ore even when its system ray crosses the HUD.
            val selectionRay = if (!gazeEnabled) pointAtOre else if (controller) panelAim else away
            val eye = if (gazeEnabled) gazeAtOre else ray(resting, frames.oreInBench[0])
            feed(100, eye, selectionRay, resting, 0f)
            feed(230, eye, selectionRay, resting, 0f)
            if(gazeEnabled) assertTrue((picker.gazeMark?.steps ?: -1) in 1..7) else assertNull(picker.gazeMark)
            feed(400, eye, selectionRay, resting, 0f)
            assertEquals(listOf(ore), highlights)
            if(gazeEnabled)assertEquals(8,picker.gazeMark?.steps)
            feed(420, away, away, resting, 0f)
            assertNull(picker.gazeMark)
            assertEquals("Clear outline on exit", -1, highlights.last())
            assertTrue(events.isEmpty())
            feed(450, eye, selectionRay, resting, 0f)
            feed(590, eye, selectionRay, resting, 0f)
            feed(750, eye, selectionRay, resting, 0f)
            feed(800, eye, selectionRay, resting, 1f)
            assertEquals(listOf(ForgePress(ore,source,ForgeInputAction.Grab)), events)

            val carried = if (controller) Point(-.21f,.20f,.27f) else anvilTarget+Point(0f,.035f,0f)
            val placeAim = if (controller) ray(carried,anvilTarget) else away
            feed(900, null, placeAim, carried, 1f, competing=true)
            val measured = followed.last()
            assertEquals(carried.x,measured.x,.0001f)
            assertEquals(carried.y,measured.y,.0001f)
            assertEquals(carried.z,measured.z,.0001f)
            feed(950, null, placeAim, carried, 0f, competing=true)
            assertEquals(listOf(ForgePress(ore,source,ForgeInputAction.Grab),
                ForgePress(0,source,ForgeInputAction.Place)),events)
        }
        // A panel on the same ray only owns input when it is in front of the selected object.
        val flat=BenchPlacement()
        for(operation in listOf(ForgeInputAction.Grab,ForgeInputAction.Place,ForgeInputAction.Use)){
            val pickOre=operation==ForgeInputAction.Grab
            val point=when(operation){
                ForgeInputAction.Grab -> WorkshopFrames(flat,WorkshopLayout()).oreInBench[1]
                ForgeInputAction.Place -> anvilTarget
                else -> WorkshopFrames(flat,WorkshopLayout()).stationInBench[1]
            }
            val origin=flat.world(point.copy(z=point.z+.75f))
            val aim=Aim(origin,Point(0f,0f,-1f))
            for(front in listOf(false,true)){
                val frame=WorkshopFrames(flat,WorkshopLayout(board=FurniturePose(point.x,point.y,point.z+if(front).12f else -.12f)))
                val state=ForgeSceneState(active=true,materialMode=operation!=ForgeInputAction.Use,
                    heldMaterial=if(operation==ForgeInputAction.Place)1 else -1)
                val picker=ForgePicking()
                val hits=mutableListOf<ForgePress>()
                for((t,pressure) in listOf(10L to 0f,280L to 1f)){
                    picker.acceptFrame(frame.bench,state,t,null,listOf(Pointer(aim,origin,pressure,1)),.5f,.4f,
                        {},hits::add,{},frames=frame)
                }
                assertEquals(if(front)emptyList() else listOf(ForgePress(if(pickOre || operation==ForgeInputAction.Use)1 else 0,1,operation)),hits)
            }
        }
    }

    @Test
    fun layoutGrabPreservesSiblingFramesAndCancelsLostDrag() {
        val room=BenchPlacement(Point(.2f,1.1f,-.16f),.64f)
        val initial=WorkshopLayout()
        var draft=initial
        val drag=WorkshopDrag()
        val height=.40f
        val width=.82f
        val start=WorkshopFrames(room,draft)
        val nearHandle=start.tray.world(furnitureHandle(FurnitureId.TRAY,height))
        val nearRoom=room.localPoint(nearHandle)
        val displacement=Point(.17f,.12f,-.09f)
        fun feed(pointer:Pointer?,t:Long):WorkshopDragResult{
            val r=drag.frame(WorkshopFrames(room,draft),listOfNotNull(pointer),t,width,height)
            r.changed?.let{draft=it}
            return r
        }
        fun pointing(o:Point,target:Point)=Aim(o,(target-o).unit()!!)
        fun near(value:Float,local:Point,heading:Float):Pointer{
            val pos=room.world(local)
            val aim=Aim(pos,HammerRotation.yaw(room.yaw+heading).rotate(Point(0f,0f,-1f)))
            return Pointer(aim,pos,0f,1,grip=value)
        }
        assertEquals(FurnitureId.TRAY,feed(near(0f,nearRoom,0f),100).hover)
        assertEquals(FurnitureId.TRAY,feed(near(1f,nearRoom,0f),120).held)
        assertEquals(null,feed(near(1f,nearRoom,0f),140).changed)
        feed(near(1f,nearRoom+displacement,.42f),180)
        val moved=WorkshopFrames(room,draft)
        assertEquals(initial.bench,draft.bench)
        assertEquals(initial.board,draft.board)
        assertEquals(initial.tray.yaw+.42f,draft.tray.yaw,.0001f)
        val landed=room.localPoint(moved.tray.world(furnitureHandle(FurnitureId.TRAY,height)))
        assertEquals((nearRoom+displacement).x,landed.x,.0001f)
        assertEquals((nearRoom+displacement).y,landed.y,.0001f)
        assertEquals((nearRoom+displacement).z,landed.z,.0001f)
        feed(near(0f,nearRoom+displacement,.42f),200)
        val kept=draft

        // The bare hand uses a remote ray on the board handle. Its other two siblings must remain fixed.
        val boardHandle=moved.board.world(furnitureHandle(FurnitureId.BOARD,height))
        val hand=room.world(Point(.56f,.17f,.58f))
        val direction=pointing(hand,boardHandle).forward
        fun far(p:Point,v:Float)=Pointer(Aim(p,direction),p,v,2)
        assertEquals(null,WorkshopDrag().frame(moved,listOf(far(hand,0f)),230,width,height){true}.hover)
        assertEquals(FurnitureId.BOARD,feed(far(hand,0f),240).hover)
        assertEquals(FurnitureId.BOARD,feed(far(hand,1f),260).held)
        feed(far(hand+Point(0f,.2f,0f),1f),290)
        assertEquals(kept.tray,draft.tray);assertEquals(kept.bench,draft.bench)
        assertEquals(kept.board.y+.2f,draft.board.y,.0001f)
        assertEquals(FurnitureId.BOARD,feed(null,310).held)
        assertEquals(null,feed(null,631).held)
        assertEquals(kept,draft)
        assertEquals(null,feed(far(hand,1f),650).held)
        assertEquals(null,drag.cancel())

        // After moving the bench, saved board and bin coordinates continue to resolve against the room.
        val translated=WorkshopFrames(room,kept.nudge(FurnitureId.BENCH,dx=.8f,dz=-.32f,dyaw=.6f))
        // Moving one service counter moves its action, rune and sound source without disturbing its siblings.
        for((index,id) in listOf(1 to FurnitureId.TRIBUTE,2 to FurnitureId.RELIC)){
            val shifted=WorkshopFrames(room,kept.nudge(id,dx=.8f,dy=.12f,dz=.3f,dyaw=.75f))
            val hit=shifted.bench.world(shifted.stationInBench[index])
            val modelCenter=shifted[id].world(stationLocalTargets[index])
            assertTrue((hit-modelCenter).dot(hit-modelCenter)<.000001f)
            assertEquals(moved.stationInBench[3-index],shifted.stationInBench[3-index])
            val rune=shifted.bench.world(shifted.runeInBench[index])
            assertTrue((rune-hit).y>.13f)
            val listener=WorkshopListener(moved[id].center+Point(0f,.38f,.45f),Point(1f,0f,0f))
            val cue=if(index==1)WorkSound.TRIBUTE else WorkSound.RELIC
            assertTrue(WorkshopMix.sample(moved,listener).at(cue)!=WorkshopMix.sample(shifted,listener).at(cue))
        }
        assertEquals(moved.board.center,translated.board.center)
        assertEquals(moved.tray.world(trayOreTargets[2]),translated.tray.world(trayOreTargets[2]))
        val oreWorld=translated.bench.world(translated.oreInBench[2])
        assertEquals(moved.tray.world(trayOreTargets[2]).x,oreWorld.x,.0001f)
        assertTrue(translated.overPanel(pointing(hand,translated.board.center),width,height))
        assertTrue(!translated.overPanel(pointing(hand,translated.tray.center),width,height))
        val acousticRoom=BenchPlacement(Point(0f,0f,0f),0f)
        val leftBin=WorkshopLayout(tray=FurniturePose(-.8f,0f,-.4f))
        val ears=WorkshopListener(Point(0f,0f,0f),Point(1f,0f,0f))
        fun pickup(layout:WorkshopLayout,right:Point=ears.right):StereoGain =
            WorkshopMix.sample(WorkshopFrames(acousticRoom,layout),ears.copy(right=right)).at(WorkSound.TRAY,1)
        val left=pickup(leftBin)
        val right=pickup(leftBin.nudge(FurnitureId.TRAY,dx=1.6f))
        assertTrue(left.left>left.right && right.right>right.left)
        assertEquals(left.left,pickup(leftBin,Point(-1f,0f,0f)).right,.0001f)
        assertTrue(pickup(leftBin.nudge(FurnitureId.TRAY,dx=-1f)).left<left.left)
        assertEquals(StereoGain(),WorkshopMix.sample(moved,null).at(WorkSound.ANVIL))
    }
    @Test
    fun hammerFaceContactAndGazeConfirmedSwing() {
        val bench=BenchPlacement()
        val state=ForgeSceneState(active=true,precisionOn=true,spotT=.42f,eyeEnabled=true)
        val spot=BladeSpot.from(state)!!
        val center=spot.center
        fun verticalAim(offset:Float)=Aim(center+Point(offset,.55f,0f),Point(0f,-1f,0f))
        assertTrue(spot.gazed(verticalAim(.0475f)))
        assertFalse(spot.gazed(verticalAim(.0485f)))
        assertFalse(spot.contains(center+Point(.025f,0f,0f)))
        val faceDown=HammerRotation.pitch(-90f)
        fun globalPose(local:HammerPose)=HammerPose(bench.world(local.position),HammerRotation.yaw(bench.yaw)*local.rotation)
        fun toAim(origin:Point,target:Point):Aim {
            val from=bench.world(origin)
            return Aim(from,(bench.world(target)-from).unit()!!)
        }
        val eye=toAim(Point(.08f,.46f,.65f),center+Point(.040f,0f,0f))
        val away=toAim(Point(.0f,.12f,.35f),Point(.9f,.12f,.0f))

        fun stroke(xOffset:Float,handOnly:Boolean=false,rotation:HammerRotation=faceDown,
                   zOffset:Float=0f,lock:Boolean=false):List<ForgePress> {
            val picker=ForgePicking()
            val strikes=mutableListOf<ForgePress>()
            val source=if(handOnly)3 else 1
            if(lock)for(t in listOf(1L,190L)){
                picker.acceptFrame(bench,state,t,eye,emptyList(),.82f,.40f,{},{strikes.add(it)},{})
            }
            for((time,y) in listOf(100L to .16f,145L to .075f,168L to -.012f,199L to -.013f)){
                val focusPoint=center+Point(xOffset,y,zOffset)
                val pose=globalPose(if(handOnly)HammerPose(focusPoint,rotation) else HammerPose.atFace(focusPoint,rotation))
                // The tracked grip never substitutes for the hammer face.
                picker.acceptFrame(bench,state,if(lock)200+time else time,if(lock)eye else null,listOf(Pointer(away,pose.position,0f,source,hammer=pose)),
                    .82f,.40f,{},{strikes.add(it)},{})
            }
            return strikes
        }
        assertEquals(listOf(ForgePress(0,1,precise=true)),stroke(0f))
        assertEquals(listOf(ForgePress(0,1,precise=false)),stroke(.039f))
        assertEquals(listOf(ForgePress(0,1,precise=true)),stroke(.039f,lock=true))
        assertTrue("Bare anvil behind the blade is never a forge hit",stroke(.030f,zOffset=.145f,lock=true).isEmpty())
        assertTrue("The peen and the cheek cannot smith",stroke(0f,rotation=HammerRotation.pitch(90f)).isEmpty() &&
            stroke(0f,rotation=HammerRotation.pitch(-15f)).isEmpty())
        assertEquals("The extended blade can be struck beyond the former end",listOf(ForgePress(0,1)),stroke(.15f))
        val halfBladeWidth=BladeSpot.profile.maxOf{it.z}
        val faceRadius=hammerFaceOutline.maxOf{val d=it-hammerFaceLocal;sqrt(d.dot(d))}
        val across=HammerRotation.yaw(Math.toRadians(BladeSpot.YAW_DEGREES.toDouble()).toFloat())
            .rotate(Point(0f,0f,halfBladeWidth+faceRadius+.010f))
        assertTrue("A whole hammer face just outside the blade cannot score",stroke(across.x,zOffset=across.z).isEmpty())
        assertTrue("Bare grip in circle is not contact when the face is elsewhere",stroke(0f,true).isEmpty())

        for(left in listOf(true,false)){
            val palm=Point(0f,0f,0f)
            val hand=HammerPose.fromHand(palm,Point(0f,0f,.04f),Point(0f,0f,-.04f),
                Point(0f,.035f,-.04f),Point(0f,-.035f,-.04f),left)!!
            assertTrue("The handle exits the thumb side of either fist",hand.rotation.rotate(Point(0f,1f,0f)).y>.9f)
            assertTrue("The leather grip lies inside the fingers",(if(left)1f else -1f)*hand.position.x>0f)
        }
        val holder=ForgePicking()
        val raised=globalPose(HammerPose.atFace(center+Point(0f,.14f,0f),faceDown))
        val close=globalPose(HammerPose.atFace(center,faceDown))
        val far=globalPose(HammerPose.atFace(Point(.31f,.35f,.4f),faceDown))
        fun hands(t:Long,left:HammerPose,right:HammerPose,pinch:Float){
            holder.acceptFrame(bench,state,t,null,listOf(Pointer(away,left.position,pinch,2,hammer=left),
                Pointer(away,right.position,0f,1,grip=0f,hammer=right)),.82f,.40f,{},{},{})
        }
        hands(10,far,raised,0f)
        hands(20,close,far,0f)
        assertEquals("The other hand cannot take the hammer by approaching the anvil",far.inBench(bench),holder.hammerPose)
        hands(40,close,far,1f)
        hands(395,close,far,1f)
        assertEquals("Holding the new hand's grip requests a handoff",close.inBench(bench),holder.hammerPose)
        val reacquired=ForgeStroke()
        val nearTop=HammerPose.atFace(center+Point(0f,.16f,0f),faceDown)
        val under=HammerPose.atFace(center-Point(0f,.01f,0f),faceDown)
        reacquired.update(nearTop,10L,spot)
        assertTrue("A telemetry gap never supplies the missing stroke",reacquired.update(under,200L,spot)==null)
        reacquired.reset();reacquired.update(nearTop,10L,spot)
        assertTrue("An implausible one-frame tracking jump cannot strike",reacquired.update(under,18L,spot)==null)

        val picker=ForgePicking()
        val events=mutableListOf<ForgePress>()
        val remoteTip=center.copy(x=furnitureEnvelope(FurnitureId.BENCH).halfX+.15f,y=center.y+.20f)
        val held=globalPose(HammerPose.atFace(remoteTip,faceDown))
        val assist=state.copy(assistedSwing=true)
        fun frame(t:Long,trigger:Float,gaze:Aim?,config:ForgeSceneState=assist,sampleAt:Long=t){
            picker.acceptFrame(bench,config,t,gaze,listOf(Pointer(away,held.position,trigger,1,hammer=held)),.82f,.40f,
                {},{events.add(it)},{},eyeSampleAt=sampleAt)
        }
        frame(50,0f,eye,state)
        frame(139,0f,eye,state,sampleAt=50)
        assertFalse("Repainting a cached eye ray cannot lock a fresh marker",picker.spotLocked)
        frame(140,0f,eye,state,sampleAt=137)
        assertFalse("87 ms is still acquiring",picker.spotLocked)
        frame(141,0f,eye,state,sampleAt=138)
        assertTrue(picker.spotLocked)
        assertTrue("Gaze alone never strikes",events.isEmpty())
        frame(236,1f,eye,state)
        assertTrue("A trigger or pinch cannot start a default swing",picker.echoPose==null)
        frame(242,0f,away)
        assertTrue(!picker.spotLocked)
        frame(270,0f,eye)
        frame(460,0f,eye)
        frame(480,1f,eye)
        assertTrue("Confirmation starts the visible swing; no score before impact",events.isEmpty())
        assertTrue(picker.echoPose!=null)
        frame(580,1f,eye)
        assertEquals(held.inBench(bench),picker.hammerPose)
        frame(711,1f,eye)
        assertEquals(listOf(ForgePress(0,1,precise=true)),events)
        val face=picker.echoPose!!.face
        assertEquals(center.y,face.y,.0001f)
        assertTrue(spot.contains(face))
        frame(753,0f,eye,assist.copy(strikeSerial=1,spotT=.85f))
        assertTrue(!picker.spotLocked)
        assertEquals(1,events.size)

        val panelPicker=ForgePicking()
        val panelHits=mutableListOf<ForgePress>()
        for(config in listOf(state,assist)){
            panelPicker.reset()
            for((t,id) in listOf(10L to 0,20L to 1,210L to 1,250L to 1,300L to 1)){
                panelPicker.acceptFrame(bench,config.copy(panelStrikeSerial=id),t,null,emptyList(),.82f,.40f,
                    {},{panelHits.add(it)},{})
            }
            if(!config.assistedSwing)assertTrue(panelHits.isEmpty())
        }
        assertEquals("Explicit seated assistance animates the panel strike",listOf(ForgePress(0,-1)),panelHits)
    }
}
