package com.pondledger.mr

import android.content.SharedPreferences
import androidx.compose.runtime.*
import java.util.UUID

/** Layout and room references are independent of the contract save and of each other. */
internal object WorkshopFile {
    private val order=listOf(FurnitureId.BENCH,FurnitureId.TRAY,FurnitureId.BOARD,FurnitureId.TRIBUTE,FurnitureId.RELIC)
    private val oldTray=FurniturePose(0f,-.08f,.295f)
    fun encode(value: WorkshopLayout): String = (listOf("2") + order.flatMap {
        val p = value[it]
        listOf(p.x, p.y, p.z, p.yaw).map(Float::toString)
    } + order.filter { it in value.tableMounted && it!=FurnitureId.BOARD }.joinToString(",") { it.name }.ifEmpty { "-" }).joinToString("|")

    fun decode(raw: String?): WorkshopLayout? {
        val fields = raw?.split('|') ?: return null
        val legacy=fields.first()=="1" && fields.size==13
        if (!legacy && (fields.first()!="2" || fields.size!=22)) return null
        val values=fields.subList(1,if(legacy)13 else 21).map { it.toFloatOrNull()?.takeIf(Float::isFinite) ?: return null }
        fun pose(i: Int) = FurniturePose(values[i], values[i+1], values[i+2], values[i+3])
        if(legacy)return migrate(pose(0),pose(4),pose(8))
        val mounted=order.filter { it!=FurnitureId.BOARD && it.name in fields.last().split(',') }.toSet()
        return WorkshopLayout(pose(0),pose(4),pose(8),pose(12),pose(16),mounted).sanitized()
    }

    private fun migrate(bench:FurniturePose,tray:FurniturePose,board:FurniturePose):WorkshopLayout {
        val fresh=WorkshopLayout()
        val basis=BenchPlacement(bench.center,bench.yaw)
        fun around(p:FurniturePose,angle:Float=0f):FurniturePose {
            val offset=HammerRotation.yaw(angle).rotate(p.center)
            val at=basis.world(offset)
            return FurniturePose(at.x,at.y,at.z,bench.yaw+p.yaw+angle)
        }
        // Previously saved custom tabletop heights and poses stay usable during an in-place upgrade.
        val mounted=buildSet {
            if(bench!=FurniturePose(0f,0f,0f))add(FurnitureId.BENCH)
            if(tray!=oldTray)add(FurnitureId.TRAY)
        }
        var layout=fresh.copy(bench=bench,board=board,tray=if(tray==oldTray)around(fresh.tray) else tray,
            tribute=around(fresh.tribute),relic=around(fresh.relic),tableMounted=mounted).sanitized()
        // Only the two new stations can be displaced while preserving a hand-arranged old layout.
        for(id in listOf(FurnitureId.TRIBUTE,FurnitureId.RELIC)) {
            if(overlappingFurniture(layout,id)==null)continue
            for(turn in listOf(.50f,-.50f,1f,-1f,1.55f,-1.55f,2.10f,-2.10f,3.14f)){
                val candidate=layout.withPose(id,around(fresh[id],turn))
                if(overlappingFurniture(candidate,id)==null){layout=candidate;break}
            }
        }
        return layout
    }
}

internal class WorkshopWorkspace(private val prefs: SharedPreferences) {
    var saved by mutableStateOf(WorkshopFile.decode(prefs.getString("furniture_v1", null)) ?: WorkshopLayout()); private set
    var draft by mutableStateOf(saved); private set
    var editing by mutableStateOf(false); private set
    var selected by mutableStateOf(FurnitureId.BENCH); private set
    var held by mutableStateOf<FurnitureId?>(null); private set
    var coarse by mutableStateOf(false); private set
    var revision by mutableIntStateOf(0); private set
    var paletteTicket by mutableIntStateOf(0); private set
    var bringTicket by mutableIntStateOf(0); private set
    var scanTicket by mutableIntStateOf(0); private set
    var scanPending by mutableStateOf(false); private set
    var pinTicket by mutableIntStateOf(0); private set
    var pinPending by mutableStateOf(false); private set
    var restoreTicket by mutableIntStateOf(0); private set
    var reference by mutableStateOf(RoomReference.decode(prefs.getString("room_pin_v1", null))); private set
    var roomStatus by mutableStateOf(if (reference == null) "随起始朝向摆放" else "正在查找上次的房间"); private set
    var hint by mutableStateOf(""); private set
    private val history = mutableStateListOf<WorkshopLayout>()
    private var dragStart: WorkshopLayout? = null
    val canUndo get() = held == null && history.isNotEmpty()
    val layout get() = if (editing) draft else saved
    fun begin() {
        if (editing) { paletteTicket++; return }
        draft = saved; history.clear(); held = null; dragStart = null
        hint = "抓住发光把手移动，转腕可调整朝向。"; editing = true; revision++; paletteTicket++
    }
    fun select(id: FurnitureId) {
        if (held != null || selected == id) return
        selected = id; cancelScan(); hint = ""
    }
    fun toggleStep() { coarse = !coarse }
    private fun record(before: WorkshopLayout) {
        if (history.size >= 24) history.removeAt(0)
        history.add(before)
    }
    fun edit(next: WorkshopLayout, message: String = "") {
        if (!editing || held != null) return
        val valid = next.sanitized()
        if (valid != draft) { record(draft); draft = valid; revision++ }
        cancelScan(); hint = message
    }
    fun nudge(x: Int=0,y: Int=0,z: Int=0,turn: Int=0) {
        val step = if (coarse) .15f else .025f
        val radians = Math.toRadians(if (coarse) 30.0 else 5.0).toFloat()
        edit(draft.nudge(selected,x*step,y*step,z*step,turn*radians))
    }
    fun undo() {
        if (!canUndo) return
        draft = history.removeAt(history.lastIndex); revision++; cancelScan(); hint = "已撤销上一步。"
    }
    fun reset() = edit(WorkshopLayout(),"已预览分站工坊，可撤销。")
    fun arrange(side: Int) {
        val base = WorkshopLayout()
        fun mirrored(p:FurniturePose)=p.copy(x=-p.x,yaw=-p.yaw)
        val layout=if(side<0)base else base.copy(tray=mirrored(base.tray),tribute=mirrored(base.tribute),relic=mirrored(base.relic))
        edit(layout,if(side<0)"料架已摆到左手边。" else "整组工位已镜像，料架在右手边。")
    }
    fun toggleStand() {
        if(selected==FurnitureId.BOARD)return
        val mounted=selected in draft.tableMounted
        val ids=if(mounted)draft.tableMounted-selected else draft.tableMounted+selected
        edit(draft.copy(tableMounted=ids),if(mounted)"支架已展开。" else "支架已收起，可放在实桌上。")
    }
    fun drag(result: WorkshopDragResult) {
        if (!editing) return
        if (result.held != null && held == null) { dragStart = draft; selected = result.held; cancelScan() }
        result.changed?.let { draft = it }
        if (result.held == null && held != null) {
            dragStart?.takeIf { it != draft }?.let { record(it); revision++ }
            dragStart = null
        }
        if (held != result.held) held = result.held
    }
    fun cancelHold(original: WorkshopLayout?) {
        if (held != null) draft = original ?: dragStart ?: draft
        held = null; dragStart = null
    }
    fun save(pin: Boolean): Boolean {
        if (held != null || !editing) return false
        if (!prefs.edit().putString("furniture_v1", WorkshopFile.encode(draft)).commit()) {
            hint = "布局写入失败，请重试。"; return false
        }
        saved = draft; closeEditor()
        if (pin) { pinTicket++; pinPending = true; roomStatus = "正在记住房间位置…" }
        else if (reference == null) roomStatus = "已保存家具布局，下次按起始朝向摆放。"
        return true
    }
    fun cancel() { draft = saved; closeEditor() }
    private fun closeEditor() {
        editing = false; cancelScan(); history.clear(); held = null; dragStart = null; revision++
    }
    fun bring() { if (held == null) { cancelScan(); bringTicket++ } }
    fun scan() {
        if (selected == FurnitureId.BOARD || held != null || scanPending) return
        scanPending = true; scanTicket++; hint = "正在查找这件家具下方的真实桌面…"
    }
    fun scanDone(next: WorkshopLayout?, message: String) {
        if (!scanPending || !editing) return
        scanPending = false
        if (next != null) edit(next,message) else hint = message
    }
    fun cancelScan() { scanPending = false }
    fun notice(value: String) { hint = value }
    fun reportRoom(value: String) { roomStatus = value }
    fun restore() { restoreTicket++ }
    fun clearPendingPin() { pinPending = false }
    fun replacePin(pin: RoomReference): Boolean {
        if (!prefs.edit().putString("room_pin_v1",pin.encode()).commit()) return false
        reference = pin
        return true
    }
}

internal data class RoomReference(val id: UUID, val local: FurniturePose) {
    fun encode() = listOf(id.toString(),local.x,local.y,local.z,local.yaw).joinToString("|")
    companion object {
        fun decode(raw: String?): RoomReference? = runCatching {
            val f = raw?.split('|') ?: return null
            require(f.size == 5)
            val v = f.drop(1).map { it.toFloat().also { n -> require(n.isFinite()) } }
            RoomReference(UUID.fromString(f[0]),FurniturePose(v[0],v[1],v[2],v[3]))
        }.getOrNull()
    }
}
