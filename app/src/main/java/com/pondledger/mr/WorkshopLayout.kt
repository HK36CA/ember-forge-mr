package com.pondledger.mr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class FurnitureId { BENCH, TRAY, BOARD, TRIBUTE, RELIC }

/** Position in room-relative metres. Positive yaw is a right-handed rotation about Y, in radians. */
data class FurniturePose(val x:Float,val y:Float,val z:Float,val yaw:Float=0f) {
    internal val center get()=Point(x,y,z)
    internal fun bounded(default:FurniturePose):FurniturePose {
        fun axis(value:Float,fallback:Float,min:Float,max:Float) = if(value.isFinite()) value.coerceIn(min,max) else fallback
        val a=if(yaw.isFinite())atan2(sin(yaw),cos(yaw)) else default.yaw
        return FurniturePose(axis(x,default.x,-16f,16f),axis(y,default.y,-3f,5f),axis(z,default.z,-16f,16f),a)
    }
}

data class WorkshopLayout(
    val bench:FurniturePose=FurniturePose(0f,0f,0f),
    val tray:FurniturePose=FurniturePose(-.89f,-.055f,.30f,.36f),
    val board:FurniturePose=FurniturePose(0f,.51f,-.42f),
    val tribute:FurniturePose=FurniturePose(.84f,-.015f,.27f,-.44f),
    val relic:FurniturePose=FurniturePose(.90f,.015f,-.37f,-.46f),
    val tableMounted:Set<FurnitureId> = emptySet()
){
    operator fun get(id:FurnitureId):FurniturePose=when(id){
        FurnitureId.BENCH->bench; FurnitureId.TRAY->tray; FurnitureId.BOARD->board
        FurnitureId.TRIBUTE->tribute; FurnitureId.RELIC->relic
    }
    fun withPose(id:FurnitureId,pose:FurniturePose):WorkshopLayout {
        val valid=pose.bounded(WorkshopLayout()[id])
        return when(id){FurnitureId.BENCH->copy(bench=valid);FurnitureId.TRAY->copy(tray=valid);FurnitureId.BOARD->copy(board=valid)
            FurnitureId.TRIBUTE->copy(tribute=valid);FurnitureId.RELIC->copy(relic=valid)}
    }
    fun nudge(id:FurnitureId,dx:Float=0f,dy:Float=0f,dz:Float=0f,dyaw:Float=0f):WorkshopLayout {
        val p=get(id)
        return withPose(id,FurniturePose(p.x+dx,p.y+dy,p.z+dz,p.yaw+dyaw))
    }
    fun sanitized():WorkshopLayout{
        val defaults=WorkshopLayout()
        return copy(bench=bench.bounded(defaults.bench),tray=tray.bounded(defaults.tray),board=board.bounded(defaults.board),
            tribute=tribute.bounded(defaults.tribute),relic=relic.bounded(defaults.relic),tableMounted=tableMounted-FurnitureId.BOARD)
    }
}

internal val trayOreTargets=listOf(-.198f,0f,.198f).map{Point(it,.065f,0f)}
internal val stationFurniture=listOf(FurnitureId.BENCH,FurnitureId.TRIBUTE,FurnitureId.RELIC)
internal val stationLocalTargets=listOf(Point(.04f,.196f,.043f),Point(0f,.133f,.015f),Point(0f,.201f,0f))
internal val anvilTarget get()=stationLocalTargets[0]
internal val runeLocalTargets=stationLocalTargets.map{it+Point(0f,.135f,0f)}
internal fun furnitureHandle(id:FurnitureId,panelHeight:Float):Point=when(id){
    FurnitureId.BENCH->Point(0f,-.052f,.320f)
    FurnitureId.TRAY->Point(0f,.017f,.206f)
    FurnitureId.TRIBUTE->Point(0f,-.036f,.222f)
    FurnitureId.RELIC->Point(0f,-.043f,.263f)
    FurnitureId.BOARD->Point(0f,-panelHeight*.5f-.036f,.019f)
}

/** Every furniture placement is a sibling in the room frame. Target lists preserve the game station indices. */
internal class WorkshopFrames(val room:BenchPlacement,val layout:WorkshopLayout){
    private fun compose(p:FurniturePose)=BenchPlacement(room.world(p.center),room.yaw+p.yaw)
    val bench=compose(layout.bench)
    val tray=compose(layout.tray)
    val board=compose(layout.board)
    val tribute=compose(layout.tribute)
    val relic=compose(layout.relic)
    val oreInBench=trayOreTargets.map{bench.localPoint(tray.world(it))}
    val stationInBench=stationLocalTargets.mapIndexed{i,p->bench.localPoint(this[stationFurniture[i]].world(p))}
    val runeInBench=runeLocalTargets.mapIndexed{i,p->bench.localPoint(this[stationFurniture[i]].world(p))}
    operator fun get(id:FurnitureId):BenchPlacement=when(id){
        FurnitureId.BENCH->bench;FurnitureId.TRAY->tray;FurnitureId.BOARD->board
        FurnitureId.TRIBUTE->tribute;FurnitureId.RELIC->relic
    }

    fun panelDistance(worldRay:Aim,width:Float,height:Float,padding:Float=.014f):Float? {
        val ray=board.localAim(worldRay)
        // The SDK panel receives clicks from its positive-Z face.
        if(ray.forward.z>=-.001f)return null
        val t=-ray.origin.z/ray.forward.z
        if(t<=0f)return null
        val hit=ray.origin+ray.forward*t
        return t.takeIf{abs(hit.x)<=width*.5f+padding && abs(hit.y)<=height*.5f+padding}
    }
    fun overPanel(worldRay:Aim?,width:Float,height:Float)=worldRay!=null && panelDistance(worldRay,width,height)!=null
}
