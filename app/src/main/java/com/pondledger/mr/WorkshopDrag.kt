package com.pondledger.mr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class WorkshopDragResult(val hover:FurnitureId?,val held:FurnitureId?,val changed:WorkshopLayout?=null)

/** Edits a draft only. The owner persists it when the player chooses Save. */
internal class WorkshopDrag {
    private data class Hit(val id:FurnitureId,val pointer:Int,val near:Boolean,val distance:Float)
    private data class Grip(val id:FurnitureId,val pointer:Int,val near:Boolean,val distance:Float,
                            val original:WorkshopLayout,val offset:Point,
                            var previousHeading:Float?,var turn:Float=0f,var lostAt:Long?=null)
    private var grip:Grip?=null
    private var lastDraft:WorkshopLayout?=null
    private val armed=BooleanArray(4)

    /** Roll back just the unfinished gesture. The caller still owns the enclosing edit transaction. */
    fun cancel(current:WorkshopLayout?=lastDraft):WorkshopLayout? {
        val restore=grip?.let{(current ?: it.original).withPose(it.id,it.original[it.id])}
        lastDraft=null
        grip=null
        armed.fill(false)
        return restore
    }
    private fun pressure(p:Pointer)=if(p.id<2)p.grip else p.press

    private fun halfSpan(id:FurnitureId,width:Float)=when(id) {
        FurnitureId.BENCH->.125f; FurnitureId.TRAY,FurnitureId.TRIBUTE,FurnitureId.RELIC->.102f
        FurnitureId.BOARD->minOf(.12f,width*.28f)
    }
    private fun rayDistance(ray:Aim,center:Point,half:Point):Float? {
        var enter=0f
        var exit=4.5f
        val origin=ray.origin-center
        fun slab(o:Float,d:Float,h:Float):Boolean {
            if(abs(d)<.0001f)return abs(o)<=h
            val a=(-h-o)/d
            val b=(h-o)/d
            enter=maxOf(enter,minOf(a,b))
            exit=minOf(exit,maxOf(a,b))
            return exit>=enter
        }
        return if(slab(origin.x,ray.forward.x,half.x) && slab(origin.y,ray.forward.y,half.y) &&
            slab(origin.z,ray.forward.z,half.z))enter else null
    }
    private fun hit(p:Pointer,frames:WorkshopFrames,width:Float,height:Float,excludedRay:(Aim)->Boolean):Hit? {
        val joint=p.joint?.takeIf{it.valid()}
        val ray=p.ray
        val uiOwnsRay=ray!=null && excludedRay(ray)
        val panelBlock=ray?.let{frames.panelDistance(it,width,height,padding=0f)}
        var nearest:Hit?=null
        for(id in FurnitureId.entries) {
            val f=frames[id]
            val handle=furnitureHandle(id,height)
            val half=halfSpan(id,width)
            val delta=joint?.let(f::localPoint)?.minus(handle)
            val direct=delta?.let{
                val dx=it.x-it.x.coerceIn(-half,half)
                sqrt(dx*dx+it.y*it.y+it.z*it.z)
            }
            val overBoardButtons=id==FurnitureId.BOARD && delta!=null && delta.y>.030f && panelBlock!=null
            val candidate=if(direct!=null && direct<=.057f && !overBoardButtons)Hit(id,p.id,true,direct)
                else ray?.takeUnless{uiOwnsRay}?.let{
                    val t=rayDistance(f.localAim(it),handle,Point(half+.014f,.029f,.031f))
                    // A beam on the face of the menu belongs to its buttons. The handle lives below that face.
                    if(t!=null && (panelBlock==null || t<=panelBlock+.004f))Hit(id,p.id,false,t) else null
                }
            if(candidate!=null && (nearest==null || candidate.near && !nearest.near ||
                    candidate.near==nearest.near && candidate.distance<nearest.distance))nearest=candidate
        }
        return nearest
    }
    private fun cursor(p:Pointer,near:Boolean,distance:Float):Point? =
        if(near)p.joint?.takeIf{it.valid()} else p.ray?.let{it.origin+it.forward*distance}?.takeIf{it.valid()}

    private fun heading(p:Pointer):Float? {
        val gripDirection=p.hammer?.rotation?.let{(it*HammerRotation.pitch(if(p.id<2)52f else 20f)).rotate(Point(0f,0f,-1f))}
        val dir=gripDirection?.takeIf{it.valid() && it.x*it.x+it.z*it.z>.04f}
            ?: p.ray?.forward?.takeIf{it.valid() && it.x*it.x+it.z*it.z>.04f} ?:return null
        return atan2(-dir.x,-dir.z)
    }
    fun frame(frames:WorkshopFrames,pointers:List<Pointer>,now:Long,panelWidth:Float,panelHeight:Float,excludedRay:(Aim)->Boolean={false}):WorkshopDragResult {
        lastDraft=frames.layout
        val holding=grip
        if(holding!=null){
            val p=pointers.firstOrNull{it.id==holding.pointer}
            val world=p?.let{cursor(it,holding.near,holding.distance)}
            val value=p?.let(::pressure)
            if(holding.lostAt?.let{now-it>=300L}==true){
                val restored=frames.layout.withPose(holding.id,holding.original[holding.id])
                cancel()
                return WorkshopDragResult(null,null,restored)
            }
            if(world==null || value==null){
                val lost=holding.lostAt ?: now.also{holding.lostAt=it}
                if(now-lost<300)return WorkshopDragResult(holding.id,holding.id)
                val restored=frames.layout.withPose(holding.id,holding.original[holding.id])
                cancel()
                return WorkshopDragResult(null,null,restored)
            }
            if(holding.lostAt!=null){
                // A short gap can continue at the last preview with no spatial jump.
                if(value<=.28f){
                    grip=null;armed[holding.pointer]=true
                    return WorkshopDragResult(holding.id,null)
                }
                val here=frames.room.localPoint(world)
                val h=heading(p)
                val current=frames.layout[holding.id]
                val delta=current.center-here
                val c=cos(holding.turn);val s=sin(holding.turn)
                val offset=Point(c*delta.x-s*delta.z,delta.y,s*delta.x+c*delta.z)
                grip=holding.copy(offset=offset,previousHeading=h,lostAt=null)
                return WorkshopDragResult(holding.id,holding.id)
            }
            val h=heading(p)
            val prev=holding.previousHeading
            if(h!=null && prev!=null)holding.turn+=atan2(sin(h-prev),cos(h-prev))
            holding.previousHeading=h
            val c=cos(holding.turn);val s=sin(holding.turn)
            val o=holding.offset
            val place=frames.room.localPoint(world)+Point(c*o.x+s*o.z,o.y,-s*o.x+c*o.z)
            val next=frames.layout.withPose(holding.id,FurniturePose(place.x,place.y,place.z,
                holding.original[holding.id].yaw+holding.turn))
            if(value<=.28f){grip=null;armed[holding.pointer]=true}
            val old=frames.layout[holding.id]
            val p2=next[holding.id]
            val moved=old.center-p2.center
            val turned=abs(atan2(sin(p2.yaw-old.yaw),cos(p2.yaw-old.yaw)))
            return WorkshopDragResult(holding.id,grip?.id,next.takeIf{moved.dot(moved)>.00000025f || turned>.001f})
        }
        val visible=pointers.map{it.id}.toSet()
        for(i in armed.indices)if(i !in visible)armed[i]=false
        var hover:Hit?=null
        var press:Hit?=null
        fun better(hit:Hit,old:Hit?)=old==null || hit.near && !old.near ||
            hit.near==old.near && hit.distance<old.distance
        for(p in pointers){
            if(p.id !in armed.indices)continue
            val h=hit(p,frames,panelWidth,panelHeight,excludedRay)
            if(h!=null && better(h,hover))hover=h
            val v=pressure(p)
            if(v==null){armed[p.id]=false;continue}
            if(v<=.28f)armed[p.id]=true
            else if(v>=.68f && armed[p.id]){
                armed[p.id]=false
                if(h!=null && better(h,press))press=h
            }
        }
        val grab=press ?:return WorkshopDragResult(hover?.id,null)
        val p=pointers.first{it.id==grab.pointer}
        val world=cursor(p,grab.near,grab.distance) ?:return WorkshopDragResult(grab.id,null)
        val offset=frames.layout[grab.id].center-frames.room.localPoint(world)
        val yaw=heading(p)
        grip=Grip(grab.id,grab.pointer,grab.near,grab.distance,frames.layout,offset,yaw)
        // Other hands must return to a known neutral state before they may start a new drag.
        armed.fill(false)
        return WorkshopDragResult(grab.id,grab.id)
    }
}
