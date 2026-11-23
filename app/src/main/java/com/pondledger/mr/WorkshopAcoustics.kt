package com.pondledger.mr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class WorkSound { MENU, ANVIL, TRAY, TRIBUTE, RELIC, BELL }

data class StereoGain(val left:Float=1f,val right:Float=1f)
internal data class WorkshopListener(val position:Point,val right:Point)

/** One audio snapshot. Short contact sounds sample it when emitted, separately from UI state. */
class WorkshopMix private constructor(
    private val anvil:StereoGain,private val tray:StereoGain,private val ore:List<StereoGain>,
    private val tribute:StereoGain,private val relic:StereoGain,private val bell:StereoGain
){
    fun at(source:WorkSound,oreIndex:Int=-1):StereoGain=when(source){
        WorkSound.MENU->neutral
        WorkSound.ANVIL->anvil
        WorkSound.TRAY->ore.getOrNull(oreIndex) ?: tray
        WorkSound.TRIBUTE->tribute
        WorkSound.RELIC->relic
        WorkSound.BELL->bell
    }
    companion object {
        private val neutral=StereoGain()
        val CENTER=WorkshopMix(neutral,neutral,emptyList(),neutral,neutral,neutral)

        internal fun sample(frames:WorkshopFrames,listener:WorkshopListener?):WorkshopMix {
            val head=listener ?:return CENTER
            val right=head.right.unit() ?:return CENTER
            if(!head.position.valid())return CENTER
            fun mix(world:Point):StereoGain {
                if(!world.valid())return neutral
                val delta=world-head.position
                val distance=sqrt(delta.dot(delta))
                if(distance<.001f)return neutral
                // Preserve nearby loudness and keep distant furniture audible within room-scale bounds.
                val level=(1f/(1f+.53f*max(0f,distance-.48f))).coerceIn(.30f,1f)
                val pan=(delta.dot(right)/distance).coerceIn(-1f,1f)
                val lateral=abs(pan)*.86f
                val farEar=level*sqrt((1f-lateral)/(1f+lateral))
                return if(pan>=0f)StereoGain(farEar,level) else StereoGain(level,farEar)
            }
            val emitted=stationLocalTargets.mapIndexed{i,p->mix(frames[stationFurniture[i]].world(p))}
            return WorkshopMix(emitted[0],mix(frames.tray.center),trayOreTargets.map{mix(frames.tray.world(it))},
                emitted[1],emitted[2],mix(frames.board.center))
        }
    }
}
