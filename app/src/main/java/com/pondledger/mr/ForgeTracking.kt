package com.pondledger.mr

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandTrackingData
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingData
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class Point(val x:Float, val y:Float, val z:Float) {
    operator fun plus(other:Point) = Point(x+other.x,y+other.y,z+other.z)
    operator fun minus(other:Point) = Point(x-other.x,y-other.y,z-other.z)
    operator fun times(f:Float) = Point(x*f,y*f,z*f)
    fun dot(other:Point) = x*other.x+y*other.y+z*other.z
    fun vector() = Vector3(x,y,z)
    fun valid() = x.isFinite() && y.isFinite() && z.isFinite()
}
internal data class Aim(val origin:Point, val forward:Point)

internal const val worktopBelowEyes=.72f
internal class BenchPlacement(center:Point=Point(0f,.88f,-.70f),yaw:Float=0f) {
    var center = center
        private set
    var yaw = yaw
        private set
    private var c = cos(yaw)
    private var s = sin(yaw)
    fun setPose(center:Point,yaw:Float):Boolean {
        if(!center.valid() || !yaw.isFinite())return false
        this.center=center
        this.yaw=atan2(sin(yaw),cos(yaw))
        c=cos(this.yaw);s=sin(this.yaw)
        return true
    }
    fun place(head:Aim):Boolean {
        val forward = head.forward
        val horizontal = sqrt(forward.x*forward.x+forward.z*forward.z)
        if (horizontal < .25f) return false
        val heading=atan2(-forward.x,-forward.z)
        return setPose(head.origin+Point(-sin(heading)*.70f,-worktopBelowEyes,-cos(heading)*.70f),heading)
    }
    fun localPoint(world:Point) = localDirection(world-center)
    private fun localDirection(v:Point) = Point(c*v.x-s*v.z,v.y,s*v.x+c*v.z)
    fun localAim(aim:Aim) = Aim(localPoint(aim.origin),localDirection(aim.forward))
    fun world(v:Point) = center + Point(c*v.x+s*v.z,v.y,-s*v.x+c*v.z)
}

internal val boardPanel = Point(0f,.53f,-.40f)

private fun Vector3.point() = Point(x,y,z)
internal fun aim(position:Vector3, rotation:Quat):Aim? {
    val origin = position.point()
    val x=rotation.x; val y=rotation.y; val z=rotation.z; val w=rotation.w
    val n=x*x+y*y+z*z+w*w
    if (!origin.valid() || !n.isFinite() || n < .0001f) return null
    return Aim(origin, Point(-2f*(x*z+w*y)/n,2f*(w*x-y*z)/n,2f*(x*x+y*y)/n-1f))
}

private fun Quat.hammerRotation()=HammerRotation.normalized(x,y,z,w)
private fun handGrip(joints:HandPose,left:Boolean):HammerPose? {
    fun p(j:HandJoint.Index)=joints[j].position.point()
    return HammerPose.fromHand(p(HandJoint.Index.PALM),p(HandJoint.Index.WRIST),p(HandJoint.Index.MIDDLE_PROXIMAL),
        p(HandJoint.Index.INDEX_PROXIMAL),p(HandJoint.Index.LITTLE_PROXIMAL),left)
}

private data class Sample<T>(val value:T, val at:Long = SystemClock.elapsedRealtime())
private fun <T> Sample<T>?.fresh(now:Long) = this?.takeIf { (now-at) in 0L..240L }?.value

/** Listeners only publish immutable SDK frames. All decisions and ECS writes run on the Stage thread. */
internal class ForgeTracking : AutoCloseable {
    private val head = HMDTrackingProvider()
    private val hands = HandTrackingProvider()
    private val controllers = ControllerTrackingProvider()
    private val gazeSensor=ForgeEyeSensor()
    @Volatile private var headFrame:Sample<HMDTrackingData>? = null
    @Volatile private var handFrame:Sample<HandTrackingData>? = null
    @Volatile private var controllerFrame:Sample<ControllerTrackingData>? = null
    @Volatile private var actions:Sample<ControllerActionData>? = null
    private val headListener = DataProvider.DataListener<HMDTrackingData>{headFrame=Sample(it)}
    private val handListener = DataProvider.DataListener<HandTrackingData>{handFrame=Sample(it)}
    private val controllerListener = DataProvider.DataListener<ControllerTrackingData>{controllerFrame=Sample(it)}
    private val buttonListener = ControllerTrackingProvider.ControllerActionListener{actions=Sample(it)}

    fun start() {
        attempt("hmd"){head.addListener(headListener); head.start()}
        attempt("hands"){hands.addListener(handListener); hands.start()}
        attempt("controllers"){controllers.addListener(controllerListener);controllers.addControllerActionListener(buttonListener);controllers.start()}
    }
    fun eyes(enabled:Boolean) = gazeSensor.enable(enabled)
    fun listener(now:Long):WorkshopListener? {
        val pose=headFrame.fresh(now)?.hmdPose ?:return null
        val right=pose.rotation.hammerRotation()?.rotate(Point(1f,0f,0f)) ?:return null
        return WorkshopListener(pose.position.point(),right)
    }
    fun head(now:Long):Aim? = headFrame.fresh(now)?.hmdPose?.let{aim(it.position,it.rotation)}
    fun gaze(now:Long):EyeReading = gazeSensor.read(now)
    fun pointers(now:Long):List<Pointer> {
        val sampledHand=handFrame
        val sampledController=controllerFrame
        val hand=sampledHand.fresh(now)
        val controller=sampledController.fresh(now)
        val buttons=actions
        // Each action state is persistent until changed. Tracking loss invalidates older buttons.
        if (controller==null) {controllerLostAt=now}
        return (0..3).map { index ->
            val left=index%2==0
            if(index<2) {
                val pose = if(left) controller?.left else controller?.right
                if(pose==null) sideLostAt[index]=now
                val action=buttons?.takeIf{it.at>=maxOf(sideLostAt[index],controllerLostAt)}?.value?.let { if(left)it.left else it.right }
                Pointer(pose?.let { aim(it.position,it.rotation) },pose?.position?.point(),action?.let{if(it.triggerPressed)1f else it.triggerValue},index, action?.let{if(it.gripPressed)1f else it.gripValue},
                    pose?.let{c->c.rotation.hammerRotation()?.let{HammerPose(c.position.point(),it*HammerPose.gripOffset)}},sampledController?.at)
            } else {
                val joints=if(left) hand?.left else hand?.right
                val ray=if(left) hand?.leftAim else hand?.rightAim
                val pinch=if(left) hand?.leftPinch else hand?.rightPinch
                Pointer(ray?.takeIf{it.isReady}?.aimPose?.let{aim(it.position,it.rotation)},
                    pinch?.takeIf{it.isReady}?.pinchPose?.position?.point() ?: joints?.get(HandJoint.Index.PALM)?.position?.point(),
                    pinch?.takeIf{it.isReady}?.pinchValue ?: joints?.let { skeleton ->
                        val delta=skeleton[HandJoint.Index.INDEX_TIP].position.point()-skeleton[HandJoint.Index.THUMB_TIP].position.point()
                        ((.052f-sqrt(delta.dot(delta)))/.034f).coerceIn(0f,1f)
                    },index,hammer=joints?.let{handGrip(it,left)},hammerSampleAt=sampledHand?.at)
            }
        }
    }
    private var controllerLostAt = Long.MIN_VALUE
    private val sideLostAt = LongArray(2){Long.MIN_VALUE}
    override fun close() {
        eyes(false)
        attempt("controller action listener"){controllers.removeControllerActionListener(buttonListener)}
        attempt("controller listener"){controllers.removeListener(controllerListener)}
        attempt("hand listener"){hands.removeListener(handListener)}
        attempt("head listener"){head.removeListener(headListener)}
        attempt("controller stop"){controllers.stop()}
        attempt("hand stop"){hands.stop()}
        attempt("head stop"){head.stop()}
    }
    private inline fun attempt(label:String, block:()->Unit) {
        runCatching(block).onFailure{Log.w("ForgeTracking",label,it)}
    }
}
internal data class Pointer(val ray:Aim?, val joint:Point?, val press:Float?, val id:Int, val grip:Float?=null,
                            val hammer:HammerPose?=null,val hammerSampleAt:Long?=null) {
    fun pressure(grabbing:Boolean):Float? = if(grabbing && grip!=null && press!=null)max(grip,press) else press
}
internal enum class ForgeInputAction { Use, Grab, Place, Drop }
internal data class ForgePress(val station:Int,val source:Int,val type:ForgeInputAction=ForgeInputAction.Use,val precise:Boolean=false)

internal class ForgePicking {
    private val armed=BooleanArray(4)
    private val stroke=ForgeStroke()
    private var hammerOwner:Int?=null
    private val handoffReady=BooleanArray(4)
    private val handoffSince=arrayOfNulls<Long>(4)
    var lastImpactPoint:Point?=null
        private set
    var hammerPose:HammerPose?=null
        private set
    var echoPose:HammerPose?=null
        private set
    private var remote:RemoteHammer?=null
    private var seenPanel:Int?=null
    var spotLocked=false
        private set
    private val dwell=GazeDwell()
    var gazeMark:GazeMark?=null
        private set
    private var spotKey:Pair<Int,Float>?=null
    private var candidate=-1
    private var since=0L
    private var reported=-1
    private var lastAction=Long.MIN_VALUE/2
    private var previousActive=false
    private var holder:Int?=null
    private var hasMaterial=false
    private var holdLostAt:Long?=null
    private var oldMode:Boolean?=null

    fun reset() {
        armed.fill(false)
        stroke.reset()
        hammerOwner=null;handoffReady.fill(false);handoffSince.fill(null)
        hammerPose=null;echoPose=null;remote=null;lastImpactPoint=null;seenPanel=null
        spotLocked=false;dwell.reset();gazeMark=null;spotKey=null
        candidate=-1
        reported=-1
        previousActive=false
        holder=null
        hasMaterial=false
        holdLostAt=null
        oldMode=null
    }
    private fun target(ray:Aim,targets:List<Point>,radius:Float):Int? {
        var closest=Float.POSITIVE_INFINITY
        var found:Int?=null
        targets.forEachIndexed{index,center->
            val delta=center-ray.origin
            val t=delta.dot(ray.forward)
            if(t !in 0f..4.5f)return@forEachIndexed
            val error=delta-ray.forward*t
            val score=error.dot(error)
            if(score>=radius*radius)return@forEachIndexed
            val depth=t-sqrt(radius*radius-score)
            if(depth<closest){closest=depth;found=index}
        }
        return found
    }
    fun frame(bench:BenchPlacement,tracking:ForgeTracking?,state:ForgeSceneState,now:Long,panelWidth:Float,panelHeight:Float,
              focus:(Int)->Unit,press:(ForgePress)->Unit,heldPosition:(Point?)->Unit,spot:BladeSpot?,frames:WorkshopFrames=WorkshopFrames(bench,WorkshopLayout())) {
        val reading = if (state.active && state.eyeEnabled) tracking?.gaze(now) else null
        val pointers = if (state.active) tracking?.pointers(now).orEmpty() else emptyList()
        acceptFrame(bench, state, now, reading?.ray, pointers, panelWidth, panelHeight, focus, press, heldPosition,
            spot,frames,reading?.sampledAt ?: now)
    }

    /** One immutable snapshot from the providers, consumed on the same Stage frame. */
    fun acceptFrame(bench:BenchPlacement,state:ForgeSceneState,now:Long,eye:Aim?,pointers:List<Pointer>,
                    panelWidth:Float,panelHeight:Float,focus:(Int)->Unit,
                    press:(ForgePress)->Unit,heldPosition:(Point?)->Unit,spot:BladeSpot?=BladeSpot.from(state),
                    frames:WorkshopFrames=WorkshopFrames(bench,WorkshopLayout()),eyeSampleAt:Long=now) {
        val panelRequest=seenPanel!=null && seenPanel!=state.panelStrikeSerial
        seenPanel=state.panelStrikeSerial
        if(!state.active){if(previousActive)reset();return}
        previousActive=true
        if(oldMode!=state.materialMode) {
            armed.fill(false); stroke.reset(); candidate=-1;reported=-1;holder=null;dwell.reset()
            hasMaterial=false;oldMode=state.materialMode
        }
        val orePoints=if(state.materialMode)frames.oreInBench else null
        val localEye=eye?.takeIf{state.eyeEnabled}?.let(bench::localAim)
        val blockingDepth=eye?.let{frames.panelDistance(it,panelWidth,panelHeight)}
        fun canSee(point:Point):Boolean = localEye!=null && (blockingDepth==null ||
            blockingDepth+.025f>=(point-localEye.origin).dot(localEye.forward))
        val centers=orePoints ?: if(state.dragon)frames.runeInBench else frames.stationInBench
        val radius=if(state.materialMode).086f else if(state.dragon).085f else .145f
        val gaze=localEye?.let{gazeTarget(it,centers,radius*GAZE_RADIUS_MULTIPLIER,::canSee)}
        fun nearestDirect(pointer:Pointer):Int? {
            val p=pointer.joint?.takeIf{it.valid()}?.let(bench::localPoint) ?:return null
            return centers.indices.minByOrNull{val d=p-centers[it];d.dot(d)}?.takeIf{
                val d=p-centers[it];d.dot(d)<radius*radius && (orePoints!=null || state.dragon || it!=0)
            }
        }
        val near=if(gaze!=null && orePoints!=null)pointers.firstNotNullOfOrNull(::nearestDirect) else null
        val sight=when {
            state.heldMaterial>=0 -> null
            localEye!=null && spot!=null && canSee(spot.center) && spot.gazed(localEye) -> BLADE_GAZE
            near==null || near==gaze -> gaze
            else -> null
        }
        val key=spot?.let{state.strikeSerial to state.spotT}
        if(key!=spotKey){dwell.reset();spotKey=key;stroke.retarget()}
        dwell.update(sight,now,eyeSampleAt)
        spotLocked=dwell.target==BLADE_GAZE && dwell.locked
        gazeMark=if(sight!=null && localEye!=null) {
            val center=if(sight==BLADE_GAZE)spot!!.center else centers[sight]
            (localEye.origin-center).unit()?.let{facing->
                val offset=if(sight==BLADE_GAZE).007f else if(orePoints!=null).059f else .086f
                GazeMark(center+facing*offset,facing,dwell.steps,sight==BLADE_GAZE)
            }
        }else null
        val forging=!state.materialMode && !state.dragon
        val poses=if(forging)pointers.mapNotNull{p->p.hammer?.takeIf{it.position.valid()}?.let{p.id to it.inBench(bench)}}.toMap() else emptyMap()
        val incumbent=hammerOwner?.takeIf{it in poses}
        val source=chooseHandoff(pointers,poses,incumbent,now) ?: incumbent ?: selectHammer(poses)
        if(source!=hammerOwner){stroke.reset();hammerOwner=source}
        hammerPose=if(forging)poses[source] ?: HammerPose.docked else null
        val playing=remote
        if(playing!=null) {
            if(!state.assistedSwing || !forging || (playing.source>=0 && playing.source!=source) ||
                (!playing.delivered && (playing.serial!=state.strikeSerial || playing.targetT!=state.spotT))) {
                remote=null;echoPose=null
            }else {
                val elapsed=now-playing.started
                if(!playing.delivered && elapsed>=228){
                    // Display at least one impact frame even after a delayed display tick.
                    echoPose=HammerPose.atFace(playing.contact,HammerRotation.pitch(-90f))
                    playing.delivered=true
                    lastAction=now
                    lastImpactPoint=playing.contact
                    press(ForgePress(0,playing.source,precise=playing.precision))
                    return
                }
                if(elapsed<413){echoPose=playing.pose(now,playing.start);return}
                remote=null;echoPose=null;stroke.reset()
            }
        }
        if(panelRequest && forging && state.assistedSwing){
            startRemote(state,spot,state.panelPrecise && spot!=null,-1,now)
            return
        }
        var hover:Int? = null
        var directHover:Int? = null
        var request:ForgePress? = null
        fun panelFirst(ray:Aim?,point:Point?):Boolean{
            val depth=ray?.let{frames.panelDistance(it,panelWidth,panelHeight)} ?: return false
            return point==null || depth+.025f<(bench.world(point)-ray.origin).dot(ray.forward)
        }
        if(state.heldMaterial in 0..2 && state.materialMode){
            hasMaterial=true
            val id=holder ?: run {
                // Picked in the panel, or restored after focus loss. A fresh station press can still place it.
                for(p in pointers) {
                    val v=p.pressure(true)
                    if(v==null || p.joint==null){armed[p.id]=false;continue}
                    if(v<=.28f)armed[p.id]=true
                    else if(v>=.68f && armed[p.id]){
                        armed[p.id]=false
                        val ray=p.ray?.let(bench::localAim)
                        if(ray!=null && target(ray,frames.stationInBench,.16f)==0 && !panelFirst(p.ray,anvilTarget)){
                            press(ForgePress(0,p.id,ForgeInputAction.Place))
                            break
                        }
                    }
                }
                return
            }
            val owner=pointers.firstOrNull{it.id==id} ?: run {
                holder=null;press(ForgePress(-1,id,ForgeInputAction.Drop));return
            }
            val joint=owner.joint?.takeIf{it.valid()}?.let(bench::localPoint)
            val holding=owner.pressure(true)
            if(joint==null || holding==null) {
                val lost=holdLostAt ?: now.also{holdLostAt=it}
                if(now-lost>=300L) {holder=null;press(ForgePress(-1,id,ForgeInputAction.Drop))}
                return
            }
            holdLostAt=null
            heldPosition(joint)
            if(holding<=.28f){
                val point=anvilTarget
                val offset=joint-point
                val near=abs(offset.x)<.235f && abs(offset.z)<.145f && offset.y in -.06f.. .25f
                val rayHit=owner.ray?.let(bench::localAim)?.let{target(it,frames.stationInBench,.16f)}==0 && !panelFirst(owner.ray,point)
                holder=null
                val result=if(near || rayHit) ForgeInputAction.Place else ForgeInputAction.Drop
                press(ForgePress(0,id,result))
            }
            return
        }
        if(hasMaterial) {
            hasMaterial=false;holder=null;armed.fill(false);reported=-1;candidate=-1
        }
        for(pointer in pointers) {
            val localRay=pointer.ray?.let(bench::localAim)
            val pointed=localRay?.let{target(it,centers,radius)}
            val value=pointer.pressure(state.materialMode)
            val id=pointer.id
            val joint=pointer.joint?.takeIf{it.valid()}?.let(bench::localPoint)
            val nearOre=nearestDirect(pointer)
            val panel=panelFirst(pointer.ray,(nearOre ?: pointed)?.let{centers[it]})
            if(!panel && hover==null)hover=pointed
            if(!panel && directHover==null)directHover=nearOre
            if(value==null || joint==null) armed[id]=false
            else if(value<=.28f) armed[id]=true
            else if(value>=.68f && armed[id]) {
                armed[id]=false
                val stableEye=gaze?.takeIf{it==dwell.target && dwell.locked}
                // Grip is a dedicated physical grab, independent of the system panel click.
                val dedicatedGrip=state.materialMode && (pointer.grip ?: 0f)>=.68f && (pointer.press ?: 0f)<=.28f
                if(!panel || (dedicatedGrip && (nearOre!=null || stableEye!=null))) {
                    val chosen=when {
                        panel -> nearOre ?: stableEye
                        nearOre!=null -> nearOre
                        spotLocked -> 0
                        stableEye!=null -> stableEye
                        gaze==null -> pointed
                        pointed==gaze -> pointed
                        else -> null
                    }
                    chosen?.let { request=ForgePress(it,id,if(state.materialMode) ForgeInputAction.Grab else ForgeInputAction.Use) }
                }
            }

        }
        val activePose=poses[source]
        if(forging && activePose!=null){
            val sampleTime=pointers.firstOrNull{it.id==source}?.hammerSampleAt ?: now
            val impact=stroke.update(activePose,sampleTime,spot)
            if(impact!=null && now-lastAction>=340L){
                lastAction=now
                lastImpactPoint=impact.point
                press(ForgePress(0,source!!,precise=impact.centered || spotLocked))
                return
            }
        }
        val next = directHover ?: gaze ?: hover ?: -1
        if(next!=candidate){
            candidate=next;since=now
            if(reported!=-1){reported=-1;focus(-1)}
        }
        val ready=when {
            directHover!=null -> now-since>=40L
            gaze!=null -> dwell.locked && (dwell.target==next || (dwell.target==BLADE_GAZE && next==0))
            else -> now-since>=110L
        }
        if(next>=0 && next!=reported && ready){
            reported=next
            focus(next)
        }
        request?.takeIf{now-lastAction>=220L}?.let{
            if(it.type==ForgeInputAction.Grab) {
                holder=it.source;holdLostAt=null
                if(reported!=it.station){reported=it.station;focus(it.station)}
            }
            if(it.type==ForgeInputAction.Use && it.station==0 && forging) {
                val pose=poses[it.source] ?: return@let
                if(!state.assistedSwing || it.source!=hammerOwner)return@let
                // A primed physical swing is judged only when its face crosses the contact plane.
                if(nearAnvil(pose) && stroke.primed && hammerOwner==it.source) return@let
                startRemote(state,spot,spotLocked && spot!=null,it.source,now)
            } else {
                lastAction=now
                press(it)
            }
        }
    }
    private fun nearAnvil(p:HammerPose?):Boolean {
        val v=p?.face ?:return false
        val a=anvilTarget
        return abs(v.x-a.x)<.29f && abs(v.z-a.z)<.18f && v.y-a.y in -.025f.. .27f
    }
    private fun chooseHandoff(pointers:List<Pointer>,poses:Map<Int,HammerPose>,owner:Int?,now:Long):Int?{
        var next:Int?=null
        for(id in handoffReady.indices){
            val p=pointers.firstOrNull{it.id==id}
            val hold=if(id<2)p?.grip else p?.press
            if(id !in poses || hold==null){
                handoffReady[id]=false
                handoffSince[id]=null
            }else if(hold<=.28f){
                handoffReady[id]=true
                handoffSince[id]=null
            }else if(id==owner && hold>=.68f){
                handoffReady[id]=false
                handoffSince[id]=null
            }else if(owner!=null && hold>=.68f && handoffReady[id]){
                val since=handoffSince[id] ?: now.also{handoffSince[id]=it}
                if(now-since>=350L && remote==null){
                    handoffReady[id]=false
                    handoffSince[id]=null
                    next=id
                }
            }else handoffSince[id]=null
        }
        return next
    }
    private fun selectHammer(poses:Map<Int,HammerPose>):Int? {
        if(poses.isEmpty())return null
        val a=anvilTarget+Point(0f,.12f,0f)
        fun score(id:Int):Float {
            val p=poses.getValue(id)
            val delta=p.face-a
            return sqrt(delta.dot(delta))+(if(nearAnvil(p))0f else .22f) - (if(id%2==1).007f else 0f)
        }
        return poses.keys.minByOrNull(::score)
    }
    private fun startRemote(s:ForgeSceneState,spot:BladeSpot?,precise:Boolean,source:Int,now:Long) {
        val hit=if(spot!=null && precise)spot.center
            else if(spot!=null) {
                // An ordinary remote hit visibly lands elsewhere on the blade.
                val other=BladeSpot.from(s.copy(spotT=if(s.spotT<.5f)1f else 0f))!!
                other.center.copy(y=spot.center.y)
            }else anvilTarget
        val start=HammerPose.atFace(hit+Point(.019f,.075f,.042f),HammerRotation.pitch(-50f))
        remote=RemoteHammer(start,hit,precise,source,now,s.strikeSerial,s.spotT)
        echoPose=start
        lastAction=now
        stroke.consume()
    }
}
