package com.pondledger.mr

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.Closeable
import kotlin.random.Random

enum class EmberCue(val resource:Int,val durationMs:Int,val volume:Float=.77f,val priority:Int=2) {
    PICK(R.raw.ember_pick,155,.63f), STRIKE(R.raw.ember_strike,680), GREAT(R.raw.ember_great,850),
    HOLD(R.raw.ember_hold,260,.66f), DAMAGE(R.raw.ember_damage,550), BREAK(R.raw.ember_break,820),
    BANK(R.raw.ember_bank,660), OFFER(R.raw.ember_offer,860), DRAGON(R.raw.ember_frenzy,1120),
    FRENZY(R.raw.ember_frenzy_enter,900,.58f), FRENZY_TICK(R.raw.ember_frenzy_tick,96,.39f,1),
    DAY(R.raw.ember_day,1000), WIN(R.raw.ember_win,1450,.86f), LOSE(R.raw.ember_lose,1480,.80f)
}

/** Short original cues mix with the user's game/media volume. One instance per activity or stage. */
class EmberAudio(context: Context) : Closeable {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val sounds = mutableMapOf<EmberCue, Int>()
    private val loaded = mutableSetOf<Int>()
    private data class Origin(val source:WorkSound,val index:Int)
    private data class Pending(val cue:EmberCue,val gain:StereoGain,val origin:Origin?,val requestedAt:Long)
    private data class Stream(val id:Int,val cue:EmberCue,val origin:Origin?,val started:Long,val until:Long)
    private var mix=WorkshopMix.CENTER
    private var pending:Pending?=null
    private val streams=arrayOfNulls<Stream>(4)
    private var enabled=true
    private var closed = false

    init {
        pool.setOnLoadCompleteListener { _, sample, status ->
            synchronized(this) {
                if (!closed && status == 0) {
                    loaded += sample
                    val queued = pending
                    if (queued != null && sounds[queued.cue] == sample) {
                        pending = null
                        if (SystemClock.uptimeMillis() - queued.requestedAt < 300) playAt(queued.cue,queued.gain,queued.origin)
                    }
                }
            }
        }
        EmberCue.values().forEach { cue ->
            sounds[cue] = pool.load(context.applicationContext, cue.resource, 1)
        }
    }

    @Synchronized fun setEnabled(value:Boolean) {
        enabled=value
        if(!value)stopAll()
    }

    @Synchronized fun stopAll() {
        pending = null
        if (closed) return
        for (i in streams.indices) {
            streams[i]?.let { pool.stop(it.id) }
            streams[i] = null
        }
    }

    @Synchronized fun stopFrenzy() {
        if (closed) return
        for (i in streams.indices) {
            val stream = streams[i] ?: continue
            if (stream.cue == EmberCue.FRENZY || stream.cue == EmberCue.FRENZY_TICK) {
                pool.stop(stream.id)
                streams[i] = null
            }
        }
    }

    @Synchronized fun frenzyBeat(previousMs: Int, remainingMs: Int) {
        val beat = FrenzyRhythm.beat(previousMs, remainingMs) ?: return
        val source = WorkSound.ANVIL
        playAt(EmberCue.FRENZY_TICK, mix.at(source), Origin(source, -1), .95f + beat * .045f)
    }

    /** Rebalance ringing cues as the listener turns before their recorded tail ends. */
    @Synchronized fun updateMix(value:WorkshopMix){
        mix=value
        if(closed || !enabled)return
        val now=SystemClock.uptimeMillis()
        for(i in streams.indices){
            val stream=streams[i] ?:continue
            if(now>=stream.until){streams[i]=null;continue}
            val origin=stream.origin ?:continue
            val g=value.at(origin.source,origin.index)
            pool.setVolume(stream.id,stream.cue.volume*g.left,stream.cue.volume*g.right)
        }
    }
    @Synchronized fun play(cue:EmberCue,source:WorkSound,oreIndex:Int=-1) {
        playAt(cue,mix.at(source,oreIndex),if(source==WorkSound.MENU)null else Origin(source,oreIndex))
    }
    @Synchronized fun play(cue:EmberCue,gain:StereoGain=StereoGain()) = playAt(cue,gain,null)
    private fun playAt(cue:EmberCue,fallback:StereoGain,origin:Origin?,fixedRate:Float?=null) {
        if (closed || !enabled) return
        val sample = sounds[cue] ?: return
        if (sample !in loaded) {
            if (cue != EmberCue.FRENZY && cue != EmberCue.FRENZY_TICK) {
                pending = Pending(cue, fallback, origin, SystemClock.uptimeMillis())
            }
            return
        }
        val rate = fixedRate ?: if (cue == EmberCue.STRIKE || cue == EmberCue.HOLD || cue == EmberCue.GREAT) {
            .97f + Random.nextFloat() * .06f
        } else 1f
        val gain=origin?.let{mix.at(it.source,it.index)} ?:fallback
        val now=SystemClock.uptimeMillis()
        val vacant = streams.indices.firstOrNull { (streams[it]?.until ?: 0L) <= now }
        val slot = vacant ?: streams.indices.minWith(compareBy({ streams[it]!!.cue.priority }, { streams[it]!!.started }))
        if (vacant == null) {
            val displaced = streams[slot]!!
            if (displaced.cue.priority > cue.priority) return
            pool.stop(displaced.id)
            streams[slot] = null
        }
        val id=pool.play(sample,cue.volume*gain.left.coerceIn(0f,1f),cue.volume*gain.right.coerceIn(0f,1f),cue.priority,0,rate)
        if(id!=0)streams[slot]=Stream(id,cue,origin,now,now+(cue.durationMs/rate).toLong())
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        pending = null
        pool.setOnLoadCompleteListener(null)
        pool.release()
    }
}
