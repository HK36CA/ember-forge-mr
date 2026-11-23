package com.pondledger.mr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pondledger.mr.platform.EYE_PERMISSION
import com.pondledger.mr.platform.EyePermissionActivity

enum class Page { HOME, TABLE, PAUSE, DEVICES, GUIDE, BOOK, REPLACE, WORKSHOP }

class ForgeSession(private val context: Context) : AutoCloseable {
    private val prefs = context.getSharedPreferences("ember_forge_v1", Context.MODE_PRIVATE)
    private val save = ForgeSave(prefs)
    internal val workshop = WorkshopWorkspace(prefs)
    private var layoutReturn = Page.HOME
    private val audio = runCatching { EmberAudio(context) }.getOrNull()
    var game by mutableStateOf(save.read()); private set
    var page by mutableStateOf(Page.HOME); private set
    private var backPage = Page.HOME
    private var contractChoice = 3
    var selected by mutableIntStateOf(-1); private set
    var recenterSerial by mutableIntStateOf(0); private set
    internal var menuDistance by mutableStateOf(
        menuDistances.getOrElse(prefs.getInt("menu_distance",1)){defaultMenuDistance}
    ); private set
    val menuDistanceLabel get()="菜单 ${(menuDistance*100+.5f).toInt()} cm"
    fun cycleMenuDistance() {
        val i=(menuDistances.indexOf(menuDistance)+1)%menuDistances.size
        menuDistance=menuDistances[i]
        prefs.edit().putInt("menu_distance",i).apply()
    }
    var sound by mutableStateOf(prefs.getBoolean("sound", true)); private set
    var assistedSwing by mutableStateOf(prefs.getBoolean("assisted_swing",false)); private set
    var wantsEye by mutableStateOf(prefs.getBoolean("eye", false)); private set
    var permitted by mutableStateOf(hasPermission()); private set
    internal var eyeReport by mutableStateOf(EyeReport(EyeHealth.OFF)); private set
    var spotLocked by mutableStateOf(false); private set
    var panelStrikeSerial by mutableIntStateOf(0); private set
    var panelPrecise by mutableStateOf(false); private set
    var hardwareHint by mutableStateOf(""); private set
    var record by mutableStateOf(prefs.getLong("record", 0L)); private set
    private var lastStrikeAt = 0L
    val eyeEnabled get() = wantsEye && permitted
    val canContinue get() = game != null && game?.phase != Phase.LOST

    init { audio?.setEnabled(sound) }
    fun updateMix(mix:WorkshopMix) { audio?.updateMix(mix) }

    fun focus(index: Int) { selected = index.coerceIn(-1, 2) }
    internal fun tracking(report: EyeReport) { eyeReport = report }
    fun lockSpot(locked: Boolean) { spotLocked = locked && page == Page.TABLE && game?.phase == Phase.FORGE }
    private fun hasPermission() = context.checkSelfPermission(EYE_PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun refreshPermission() {
        permitted = hasPermission()
        if (!permitted && eyeReport.health != EyeHealth.OFF) eyeReport = EyeReport(EyeHealth.OFF)
    }
    fun toggleEye() {
        refreshPermission()
        if (wantsEye && permitted) {
            wantsEye = false
            eyeReport = EyeReport(EyeHealth.OFF)
        } else {
            wantsEye = true
            if (!permitted) {
                runCatching {
                    context.startActivity(Intent(context, EyePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { hardwareHint = "请在系统应用权限里允许眼动追踪。" }
            }
        }
        prefs.edit().putBoolean("eye", wantsEye).apply()
    }
    fun toggleAssistedSwing() {
        assistedSwing=!assistedSwing
        prefs.edit().putBoolean("assisted_swing",assistedSwing).apply()
    }
    fun toggleSound() {
        sound=!sound
        audio?.setEnabled(sound)
        prefs.edit().putBoolean("sound",sound).apply()
    }
    fun recenter() { recenterSerial++; hardwareHint = "本次将整间工坊移到面前，家具之间的摆法会保留。" }
    fun editWorkshop() {
        if (page != Page.WORKSHOP) {
            if (page == Page.TABLE) pause()
            layoutReturn = page
            page = Page.WORKSHOP
            selected = -1
            spotLocked = false
        }
        workshop.begin()
    }
    fun finishWorkshop(saveLayout: Boolean, pin: Boolean = false) {
        if (page != Page.WORKSHOP) return
        if (saveLayout) { if (!workshop.save(pin)) return } else workshop.cancel()
        page = layoutReturn
    }
    fun details(target: Page) {
        if (page == Page.TABLE) parkTools()
        backPage = page
        page = target
    }
    fun back() { page = backPage }
    fun home() { audio?.stopAll();game?.let(save::write);page=Page.HOME }
    fun pause() {
        if (page != Page.TABLE) { audio?.stopAll();return }
        parkTools()
        page = Page.PAUSE
    }
    private fun parkTools() {
        audio?.stopAll()
        game?.let { current ->
            val parked = if (current.phase == Phase.MATERIAL && current.held >= 0) current.drop() else current
            game = parked
            save.write(parked)
        }
        selected = -1
        spotLocked = false
    }
    fun resume() { if (game != null) { selected = -1; page = Page.TABLE } }
    fun start(days: Int) {
        contractChoice = days
        if (game != null && game?.phase !in listOf(Phase.LOST, Phase.FREED, Phase.WON)) details(Page.REPLACE)
        else confirmStart()
    }
    fun confirmStart() { selected = -1; lastStrikeAt = 0; page = Page.TABLE; commit(ForgeGame.start(contractChoice), EmberCue.DAY) }
    private fun commit(next: ForgeGame, cue: EmberCue? = null, source:WorkSound=WorkSound.MENU, oreIndex:Int=-1) {
        val old = game
        if (old == next) return
        if(old?.phase==Phase.FORGE && old.isFrenzy && (next.phase!=Phase.FORGE || !next.isFrenzy))audio?.stopFrenzy()
        if (old?.phase != next.phase) { selected = -1; spotLocked = false }
        game = next
        save.write(next)
        if (next.best > record) { record = next.best; prefs.edit().putLong("record", record).apply() }
        if (cue != null && sound) audio?.play(cue,source,oreIndex)
    }
    fun tick(ms: Int) {
        val g = game ?: return
        if(page!=Page.TABLE || g.phase!=Phase.FORGE || !g.isFrenzy)return
        val next=g.tick(ms)
        game=next
        if(!next.isFrenzy)audio?.stopFrenzy()
        else if(sound)audio?.frenzyBeat(g.frenzyMs,next.frenzyMs)
    }
    fun grab(index: Int) { if (page == Page.TABLE) game?.let { commit(it.grab(index),EmberCue.PICK,WorkSound.TRAY,index) } }
    fun drop() { if (page == Page.TABLE) game?.let { commit(it.drop(),EmberCue.PICK,WorkSound.TRAY,it.held) } }
    fun place() { if (page == Page.TABLE) game?.let { commit(it.place(), EmberCue.HOLD,WorkSound.ANVIL) } }
    fun strike(precise: Boolean):Boolean {
        val g = game ?: return false
        if (page != Page.TABLE || g.phase != Phase.FORGE) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastStrikeAt < 340L) return false
        lastStrikeAt = now
        spotLocked = false
        val next = g.strike(precise)
        val cue = when {
            next.blanks < g.blanks -> EmberCue.BREAK
            next.value < g.value -> EmberCue.DAMAGE
            precise || next.grade - g.grade >= 3 -> EmberCue.GREAT
            next.value == g.value -> EmberCue.HOLD
            else -> EmberCue.STRIKE
        }
        commit(next, cue,WorkSound.ANVIL)
        val ignited=!g.isFrenzy && next.isFrenzy
        if(ignited && sound)audio?.play(EmberCue.FRENZY,WorkSound.ANVIL)
        return ignited
    }
    fun action(index: Int) {
        val g = game ?: return
        if (page != Page.TABLE || index !in 0..2) return
        when (g.phase) {
            Phase.DRAGON -> {
                val next = g.hitRune(index)
                val source=when(index){0->WorkSound.ANVIL;1->WorkSound.TRIBUTE;else->WorkSound.RELIC}
                commit(next, when { next.phase == Phase.FREED -> EmberCue.WIN; index == g.rune -> EmberCue.GREAT; else -> EmberCue.DAMAGE },source)
            }
            Phase.FORGE -> when (index) {
                0 -> if(assistedSwing) { panelPrecise = spotLocked; panelStrikeSerial++ }
                1 -> commit(g.offer(), EmberCue.BANK,WorkSound.TRIBUTE)
                2 -> commit(g.openRelics(),EmberCue.HOLD,WorkSound.RELIC)
            }
            else -> Unit
        }
    }
    fun relic(index: Int) { game?.let { commit(it.takeRelic(index), EmberCue.OFFER,WorkSound.RELIC) } }
    fun cancelRelic() { game?.let { commit(it.cancelRelics()) } }
    fun visitMerchant() { game?.let { commit(it.buyTickets(), EmberCue.OFFER,WorkSound.RELIC) } }
    fun prepareBell() { game?.let { commit(it.prepareBell()) } }
    fun cancelBell() { game?.let { commit(it.backFromBell()) } }
    fun ringBell() {
        val next = game?.ringBell() ?: return
        commit(next, when (next.phase) { Phase.LOST -> EmberCue.LOSE; Phase.WON -> EmberCue.WIN; else -> EmberCue.DAY },WorkSound.BELL)
    }
    fun dragon() { game?.let { commit(it.faceDragon(), EmberCue.DRAGON) } }
    fun endless() { game?.let { commit(it.keepForging(), EmberCue.DAY) } }
    override fun close() { game?.let(save::write); audio?.close() }
}
