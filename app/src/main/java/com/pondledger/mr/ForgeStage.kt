package com.pondledger.mr

import com.pondledger.mr.theme.ForgeSwatch
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.pondledger.mr.content.LocalEyePanel
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.AttachmentPanelComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Transform
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.panelSize
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.HandController
import com.pico.spatial.ui.platform.HandControllerHapticType
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalHandControllerHaptic
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

data class ForgeSceneState(
    val active:Boolean=false,
    val selected:Int=0,
    val strikeSerial:Int=0,
    val eyeEnabled:Boolean=false,
    val eyeInspect:Boolean=false,
    val recenterSerial:Int=0,
    val heat:Float=.5f,
    val materialMode:Boolean=false,
    val heldMaterial:Int=-1,
    val dragon:Boolean=false,
    val rune:Int=-1,
    val precisionOn:Boolean=false,
    val spotT:Float=.5f,
    val panelStrikeSerial:Int=0,
    val panelPrecise:Boolean=false,
    val assistedSwing:Boolean=false,
    val forgedOre:Int=-1,
    val frenzyLeft:Int=0,
    val frenzySpan:Int=4000
)

internal class StageBinding {
    val room=BenchPlacement()
    val controls=BenchPlacement(Point(0f,1.485f,-defaultMenuDistance),0f)
    var frames=WorkshopFrames(room,WorkshopLayout())
    private var baseAt=room.center
    private var baseYaw=room.yaw
    val picking=ForgePicking()
    val drag=WorkshopDrag()
    val recall=WorkshopRecall()
    var models:ForgeModels?=null
    var panel:Entity?=null
    var preview:Entity?=null
    var heatLabel:Entity?=null
    var hot=false
    var client:ForgeTracking?=null
    var recenter:Int?=null
    var basisReady=false
    var manualEpoch=0
    var detachedAtRestore by mutableStateOf<Int?>(null)
    var pinned:java.util.UUID?=null
    var pending:Pair<RoomReference,BenchPlacement>?=null
    var refocusCheck=false
    var unlocatedRoom:RoomReference?=null
    var panelScale=1f
    var menuDistance=defaultMenuDistance
    private var controlsRatio=.66f
    fun controlsWidth(boardW:Float)=boardW*controlsRatio
    fun controlsHeight(boardH:Float)=boardH*controlsRatio
    var panelPlaced=false
    var buildTries=0
    var nextBuildAt=0L
    var kind=0 // 0 front menu, 1 world board, 2 edit palette
    var controlAimPending=true
    var dirty=true
    fun sync(layout:WorkshopLayout,w:Float,h:Float) {
        if(frames.layout!=layout || baseAt!=room.center || baseYaw!=room.yaw){
            frames=WorkshopFrames(room,layout); baseAt=room.center; baseYaw=room.yaw; dirty=true
        }
        if(!dirty)return
        dirty=false
        models?.pose(frames,w,h)
        val at=if(kind==1)frames.board else controls
        val maxWidth=minOf(if(kind==2).59f else .54f,menuDistance*.98f)
        controlsRatio=if(w>.001f)minOf(1f,maxWidth/w) else 1f
        panel?.takeIf{it.valid}?.let{position(it,at,if(kind==1)1f else controlsRatio);panelPlaced=true}
        preview?.takeIf{it.valid}?.let{p->
            p.enabled=kind!=1
            if(kind!=1)position(p,frames.board)
        }
        heatLabel?.takeIf{it.valid}?.let{p->
            p.enabled=hot && kind==1
            if(hot && kind==1){
                val anchor=frames.bench.world(anvilTarget+Point(0f,.270f,-.225f))
                position(p,BenchPlacement(anchor,frames.bench.yaw))
            }
        }
    }
    fun aimControls(head:Aim?,editing:Boolean) {
        val next=menuPlacement(head ?: return,menuDistance,editing,controls.yaw) ?: return
        controls.setPose(next.center,next.yaw)
        controlAimPending=false;dirty=true
    }
    fun paletteBlocks(ray:Aim,w:Float,h:Float):Boolean {
        val r=controls.localAim(ray)
        if(r.forward.z>=-.001f)return false
        val t=-r.origin.z/r.forward.z
        if(t !in 0f..4f)return false
        val p=r.origin+r.forward*t
        return kotlin.math.abs(p.x)<controlsWidth(w)*.5f && kotlin.math.abs(p.y)<controlsHeight(h)*.5f
    }
    private fun position(entity:Entity,at:BenchPlacement,ratio:Float=1f) {
        val half=at.yaw*.5f
        val s=panelScale*ratio
        val world=Transform(at.center.vector(),Quat(0f,sin(half),0f,cos(half)),Vector3(s,s,s))
        entity.components.set(TransformComponent(entity.getParent()?.convertTransformFrom(world,null) ?: world))
    }
}

/** Station numbers are left anvil, center tribute and right relic furnace. Focus previews only. */
@Composable
internal fun ForgeStage(
    state:ForgeSceneState,
    workshop:WorkshopWorkspace,
    tablePage:Boolean,
    menuKey:Int,
    menuDistance:Float,
    onRecall:()->Unit,
    onMix:(WorkshopMix)->Unit,
    onAction:(Int)->Unit,
    onFocus:(Int)->Unit,
    onGrab:(Int)->Unit={},
    onPlace:()->Unit={},
    onDrop:()->Unit={},
    onEyeReport:(EyeReport)->Unit={},
    onRoomRelocated:()->Unit={},
    onStrike:(Boolean)->Boolean,
    onSpotLock:(Boolean)->Unit={},
    panelWidth:Int=820,
    panelHeight:Int=420,
    ledger:@Composable ()->Unit,
    panel:@Composable ()->Unit
) {
    val binding=remember{StageBinding()}
    var renderNotice by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    val attachmentSize=panelSize(panelWidth.dp,panelHeight.dp)
    val heatSize=panelSize(225.dp,52.dp)
    val latest by rememberUpdatedState(state)
    val action by rememberUpdatedState(onAction)
    val focus by rememberUpdatedState(onFocus)
    val grab by rememberUpdatedState(onGrab)
    val place by rememberUpdatedState(onPlace)
    val drop by rememberUpdatedState(onDrop)
    val eyeReport by rememberUpdatedState(onEyeReport)
    val pauseForRoom by rememberUpdatedState(onRoomRelocated)
    val recall by rememberUpdatedState(onRecall)
    val mix by rememberUpdatedState(onMix)
    val playingPage by rememberUpdatedState(tablePage)
    val menuRequest by rememberUpdatedState(menuKey)
    val userDistance by rememberUpdatedState(menuDistance)
    val strike by rememberUpdatedState(onStrike)
    val spotLock by rememberUpdatedState(onSpotLock)
    val focused by LocalSpatialContainerStateManager.current.isFocused
    val haptic=LocalHandControllerHaptic.current
    val converter=LocalPhysicalLengthConverter.current
    val rawWidth=converter.dpToLength(panelWidth.dp,LengthUnit.Meters).toFloat()
    val rawHeight=converter.dpToLength(panelHeight.dp,LengthUnit.Meters).toFloat()
    val scale=minOf(1f,.82f/rawWidth,.40f/rawHeight)
    WorkshopPositioning(binding,workshop,focused)
    var retryPulse by remember{mutableIntStateOf(0)}
    LaunchedEffect(binding) {
        repeat(36) {
            if(binding.panelPlaced && binding.preview?.valid==true && binding.heatLabel?.valid==true &&
                (binding.models!=null || binding.buildTries>=3))return@LaunchedEffect
            delay(200)
            retryPulse++
        }
    }
    DisposableEffect(binding,focused){
        val tracker=if(focused) ForgeTracking().also{it.start()} else null
        binding.client=tracker
        if(focused)binding.controlAimPending=true
        binding.picking.reset()
        onDispose{
            binding.models?.suspendFrenzy()
            binding.heatLabel?.takeIf { it.valid }?.enabled=false
            binding.hot=false
            binding.dirty=true
            workshop.cancelHold(binding.drag.cancel())
            workshop.cancelScan()
            if(tracker!=null){
                binding.pending=null;binding.pinned=null;binding.unlocatedRoom=null
                binding.refocusCheck=binding.basisReady
                eyeReport(EyeReport(if(latest.eyeEnabled)EyeHealth.NEEDS_FULL_SPACE else EyeHealth.OFF))
            }
            binding.client=null
            tracker?.close()
            binding.picking.reset()
            binding.recall.reset()
            mix(WorkshopMix.CENTER)
        }
    }
    SideEffect{binding.client?.eyes(state.eyeEnabled)}
    LaunchedEffect(binding,rawWidth,rawHeight,scale,focused) {
        var lastEye:EyeReport?=null
        var eyeReportAt=0L
        var lastLock:Boolean?=null
        var lastPalette=workshop.paletteTicket
        var lastBring=workshop.bringTicket
        var lastMix=0L
        var lastRevision=workshop.revision
        var requestedRoomPause=false
        var shownMenu=-1
        while(isActive)withFrameNanos {
            val now=SystemClock.elapsedRealtime()
            val tracker=binding.client
            val config=latest
            val reading=when {
                !config.eyeEnabled -> EyeReading(EyeHealth.OFF)
                !focused || tracker==null -> EyeReading(EyeHealth.NEEDS_FULL_SPACE)
                else -> tracker.gaze(now)
            }
            val eye=reading.ray
            val models=binding.models
            val kind=if(workshop.editing)2 else if(playingPage)1 else 0
            if(shownMenu!=menuRequest || binding.menuDistance!=userDistance){
                binding.menuDistance=userDistance;shownMenu=menuRequest
                binding.controlAimPending=true;binding.dirty=true
            }
            if(kind!=binding.kind || lastPalette!=workshop.paletteTicket){
                binding.kind=kind;binding.dirty=true;binding.controlAimPending=true
                binding.picking.reset(); workshop.cancelHold(binding.drag.cancel())
                lastPalette=workshop.paletteTicket
            }
            if(tracker!=null && binding.recenter!=config.recenterSerial) {
                val head=tracker.head(now)
                if(head!=null && binding.room.place(head)) {
                    if(binding.recenter!=null) {
                        binding.pending=null;binding.pinned=null;binding.manualEpoch++;binding.detachedAtRestore=workshop.restoreTicket
                        binding.refocusCheck=false;binding.unlocatedRoom=null
                        binding.controlAimPending=true
                        workshop.reportRoom("工坊已移至面前。可重新固定到这个房间。")
                    }
                    binding.recenter=config.recenterSerial
                    binding.basisReady=true;binding.dirty=true;binding.picking.reset()
                }
            }
            if(kind==0 && focused && binding.unlocatedRoom?.let{it==workshop.reference}==true &&
                !workshop.pinPending && binding.detachedAtRestore!=workshop.restoreTicket) {
                // The previous Stage pose may belong to another room after the headset was removed.
                tracker?.head(now)?.let{head->
                    if(binding.room.place(head)){
                        binding.unlocatedRoom=null;binding.basisReady=true;binding.recenter=config.recenterSerial
                        binding.dirty=true;binding.picking.reset()
                    }
                }
            }
            if(kind==0 && focused && !workshop.pinPending) binding.pending?.let{(ref,located)->
                binding.pending=null
                if(workshop.reference==ref && binding.detachedAtRestore!=workshop.restoreTicket){
                    val first=binding.pinned!=ref.id
                    binding.room.setPose(located.center,located.yaw); binding.recenter=config.recenterSerial
                    binding.basisReady=true;binding.pinned=ref.id;binding.dirty=true;binding.detachedAtRestore=null
                    binding.picking.reset()
                    if(requestedRoomPause) workshop.reportRoom("系统重新定位了房间，工坊已归位，合约已暂停。")
                    else if(first) workshop.reportRoom("已找回这间工坊在房间里的位置。")
                }
            }
            val roomJump=focused && kind==1 && binding.pending?.let{(ref,located)->
                binding.pinned==ref.id && workshop.reference==ref && WorkshopRoom.different(binding.room,located,.08f,.105f)
            }==true
            if(roomJump && !requestedRoomPause) {
                binding.picking.reset()
                workshop.reportRoom("房间定位发生变化，已暂停锻打并重新对齐工坊。")
                pauseForRoom()
            }
            requestedRoomPause=roomJump
            val hot=focused && kind==1 && !roomJump && !binding.recall.consuming && config.active && config.frenzyLeft>0
            if(binding.hot!=hot){binding.hot=hot;binding.dirty=true}
            if(binding.controlAimPending && kind!=1)binding.aimControls(tracker?.head(now),kind==2)
            binding.sync(workshop.layout,rawWidth*scale,rawHeight*scale)
            if(lastEye?.health!=reading.health || now>=eyeReportAt) {
                eyeReportAt=now+50L
                val panelHit=if(config.eyeInspect && kind==0 && binding.panelPlaced && eye!=null)
                    gazeOnPanel(eye,binding.controls,binding.controlsWidth(rawWidth*scale),binding.controlsHeight(rawHeight*scale)) else null
                val report=EyeReport(reading.health,panelHit)
                if(report!=lastEye){lastEye=report;eyeReport(report)}
            }
            if(now-lastMix>=100 && focused) {
                lastMix=now;mix(WorkshopMix.sample(binding.frames,tracker?.listener(now)))
            }
            val pointers=if(focused)tracker?.pointers(now).orEmpty() else emptyList()
            if(binding.recall.update(pointers,now)){
                workshop.cancelHold(binding.drag.cancel());binding.picking.reset();recall()
            }
            if(lastRevision!=workshop.revision){ binding.drag.cancel();lastRevision=workshop.revision }
            if(workshop.editing && lastBring!=workshop.bringTicket){
                lastBring=workshop.bringTicket
                val here=BenchPlacement();val head=tracker?.head(now)
                if(head!=null && here.place(head)){
                    val id=workshop.selected
                    val target=here.world(Point(0f,WorkshopLayout()[id].y,if(id==FurnitureId.BOARD).07f else 0f))
                    val local=binding.room.localPoint(target)
                    val proposed=FurniturePose(local.x,local.y,local.z,here.yaw-binding.room.yaw)
                    val next=workshop.draft.withPose(id,proposed)
                    workshop.edit(next, if((next[id].center-local).dot(next[id].center-local)>.0025f)
                        "已移到布置范围边缘。需要时可在设备页把整间工坊移到面前。" else "已把这件家具带到面前。")
                }else workshop.notice("等待头显定位后再试。")
            }
            val editHit=if(workshop.editing && focused && !binding.recall.consuming)
                binding.drag.frame(binding.frames,pointers,now,rawWidth*scale,rawHeight*scale) { ray->
                    binding.paletteBlocks(ray,rawWidth*scale,rawHeight*scale)
                } else null
            if(editHit!=null){
                workshop.drag(editHit)
                binding.sync(workshop.layout,rawWidth*scale,rawHeight*scale)
            }
            if(models!=null && models.root.valid){
                models.frame(config,now,focused && !workshop.editing && !roomJump && !binding.recall.consuming)
                models.showFurnitureEdit(workshop.editing,editHit?.hover ?: workshop.selected,editHit?.held,now)
                val spot=BladeSpot.from(config,models.bladeLift)
                val hammerMissing=models.hammerUnavailable && !config.materialMode && !config.dragon
                if(focused && !roomJump && !hammerMissing && !binding.recall.consuming && !workshop.editing) {
                    val usable=if(config.assistedSwing && models.echoUnavailable)config.copy(assistedSwing=false) else config
                    binding.picking.acceptFrame(binding.frames.bench,usable,now,eye,pointers,rawWidth*scale,rawHeight*scale,focus,hit@{ event ->
                        var ignited=false
                        when(event.type) {
                            ForgeInputAction.Use->if(event.station==0 && !config.dragon) {
                                val echo=binding.picking.echoPose
                                models.showHammer(binding.picking.hammerPose,echo)
                                if(models.hammerUnavailable || (echo!=null && models.echoUnavailable))return@hit
                                binding.picking.lastImpactPoint?.let { models.impactAt(it,event.precise) }
                                ignited=strike(event.precise)
                            } else action(event.station)
                            ForgeInputAction.Grab->grab(event.station)
                            ForgeInputAction.Place->place()
                            ForgeInputAction.Drop->drop()
                        }
                        if(event.source in 0..1 && event.type!=ForgeInputAction.Drop) runCatching {
                            val pulse=when {
                                ignited -> HandControllerHapticType(220,125,110)
                                event.precise -> HandControllerHapticType(145,205,60)
                                else -> HandControllerHapticType(95,160,42)
                            }
                            haptic.feedback(pulse,if(event.source==0)HandController.Left else HandController.Right)
                        }
                    },models::heldPosition,spot,binding.frames,reading.sampledAt)
                }else if(workshop.editing || binding.recall.consuming){
                    binding.picking.reset()
                }
                models.showGaze(if(focused && !workshop.editing && !roomJump && !hammerMissing && !binding.recall.consuming &&
                    config.active && eye!=null) binding.picking.gazeMark else null)
                val locked=focused && tracker!=null && binding.picking.spotLocked && spot!=null
                models.showSpot(if(focused)spot else null,locked)
                models.showHammer(if(focused && !workshop.editing)binding.picking.hammerPose else null,if(focused && !workshop.editing)binding.picking.echoPose else null)
                if(hammerMissing){
                    binding.picking.reset()
                    renderNotice="锤子模型加载失败。契约已保存，可重新打开工坊。"
                } else if(models.echoUnavailable && config.assistedSwing)
                    renderNotice="辅助锤未能显示，可继续用手中的锤子敲击。"
                else if(renderNotice.isNotEmpty())renderNotice=""
                if(lastLock!=locked){lastLock=locked;spotLock(locked)}
            }
        }
    }
    DisposableEffect(binding){onDispose{
        // The AttachmentPanel is owned by SpatialView, separate from the model tree.
        binding.panel=null
        binding.models?.close()
        binding.models=null
    }}
    fun bind(add:(Entity)->Unit,entity:Entity?,ghost:Entity?,heat:Entity?){
        if(binding.models==null && binding.buildTries<3 && SystemClock.elapsedRealtime()>=binding.nextBuildAt){
            binding.buildTries++
            val model=ForgeModels(Entity())
            try{
                add(model.root)
                model.build()
                binding.models=model
                binding.dirty=true
                scope.launch{model.loadTextures()}
            }
            catch(e:Exception){
                model.close()
                binding.nextBuildAt=SystemClock.elapsedRealtime()+700L*binding.buildTries
                if(binding.buildTries==3)renderNotice="工坊模型加载失败。请重新打开游戏，已保存的契约可以继续。"
                Log.e("EmberForge","Unable to load 3D workbench",e)
            }
        }
        if(entity!=null && entity.valid &&
            (binding.panel?.id!=entity.id || binding.panelScale!=scale || !binding.panelPlaced)) {
            if(entity.getParent()==null) add(entity)
            binding.panel=entity
            binding.panelScale=scale
            binding.dirty=true
        }
        if(ghost!=null && ghost.valid && ghost.id!=binding.preview?.id){
            if(ghost.getParent()==null)add(ghost)
            binding.preview=ghost;binding.dirty=true
        }
        if(heat!=null && heat.valid && heat.id!=binding.heatLabel?.id){
            if(heat.getParent()==null)add(heat)
            binding.heatLabel=heat;binding.dirty=true
        }
        binding.sync(workshop.layout,rawWidth*scale,rawHeight*scale)
    }
    SpatialView(modifier=Modifier.fillMaxSize(),
        initial={content,attachments->bind({content.addEntity(it)},attachments.entity("ForgePanel"),attachments.entity("BoardSample"),attachments.entity("AnvilHeat"))},
        update={content,attachments->
            retryPulse
            bind({content.addEntity(it)},attachments.entity("ForgePanel"),attachments.entity("BoardSample"),attachments.entity("AnvilHeat"))
        },
        attachments={
            AttachmentPanel("ForgePanel",size=attachmentSize,alignment=AttachmentPanelComponent.Alignment.CENTER) {
                var viewport by remember{mutableStateOf<LayoutCoordinates?>(null)}
                Column(Modifier.size(panelWidth.dp,panelHeight.dp).onGloballyPositioned{viewport=it}){
                    if(renderNotice.isNotEmpty())Text(renderNotice,modifier=Modifier.padding(8.dp))
                    CompositionLocalProvider(LocalEyePanel provides viewport){Box(Modifier.fillMaxSize()){panel()}}
                }
            }
            AttachmentPanel("BoardSample",size=attachmentSize,alignment=AttachmentPanelComponent.Alignment.CENTER) {
                if(!tablePage || workshop.editing) ledger()
            }
            AttachmentPanel("AnvilHeat",size=heatSize,alignment=AttachmentPanelComponent.Alignment.CENTER) {
                if(state.active && state.frenzyLeft>0) AnvilFrenzy(state.frenzyLeft,state.frenzySpan)
            }
        })
}
