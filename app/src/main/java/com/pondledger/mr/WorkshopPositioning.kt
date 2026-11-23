package com.pondledger.mr

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Composable
internal fun WorkshopPositioning(binding: StageBinding, workshop: WorkshopWorkspace, focused: Boolean) {
    val restore = workshop.restoreTicket
    val ref = workshop.reference
    val follow = focused && !workshop.pinPending && binding.detachedAtRestore != restore
    LaunchedEffect(binding, follow, ref, restore) {
        if (!follow || ref == null) return@LaunchedEffect
        if (binding.pinned != ref.id) workshop.reportRoom("正在查找已固定的房间位置…")
        fun observe(located: BenchPlacement?) {
            if (workshop.reference != ref || workshop.pinPending || binding.detachedAtRestore == restore) return
            if (located != null) binding.unlocatedRoom = null
            else if (binding.refocusCheck) binding.unlocatedRoom = ref
            binding.refocusCheck = false
            if (located == null) {
                binding.pending = null
                binding.pinned = null
                workshop.reportRoom("暂未找到房间锚点，保留当前摆法。回到原房间后可点「找回房间」。")
            } else if (binding.pinned == ref.id && !WorkshopRoom.different(binding.room, located, .012f, .0175f)) {
                binding.pending = null
            } else {
                val first = binding.pending == null && binding.pinned != ref.id
                binding.pending = ref to located
                if (first && binding.kind != 0) workshop.reportRoom("已定位，回到菜单时工坊会归位。")
            }
        }
        try {
            WorkshopRoom.follow(ref, ::observe)
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            observe(null)
        }
    }

    val pin = workshop.pinTicket
    LaunchedEffect(binding, focused, pin) {
        if (!focused || !workshop.pinPending) return@LaunchedEffect
        val old = workshop.reference
        if (old != null && old.id == binding.pinned && binding.detachedAtRestore != restore) {
            workshop.reportRoom("家具布局已保存，仍固定在这个房间。")
            workshop.clearPendingPin()
            return@LaunchedEffect
        }
        if (!binding.basisReady || binding.client?.head(SystemClock.elapsedRealtime()) == null) {
            workshop.reportRoom("家具布局已保存。头显恢复定位后可再次固定。")
            workshop.clearPendingPin()
            return@LaunchedEffect
        }
        val epoch = binding.manualEpoch
        val room = BenchPlacement(binding.room.center, binding.room.yaw)
        var discard: RoomReference? = null
        try {
            val next = WorkshopRoom.create(room)
            discard = next
            if (pin != workshop.pinTicket) return@LaunchedEffect
            if (next != null && binding.manualEpoch == epoch && workshop.replacePin(next)) {
                discard = null
                binding.pinned = next.id
                binding.pending = null
                binding.unlocatedRoom = null
                binding.refocusCheck = false
                binding.detachedAtRestore = null
                workshop.reportRoom("已把工坊固定在这个房间。下次启动会尝试找回。")
                workshop.clearPendingPin()
                if (old != null && old.id != next.id) WorkshopRoom.forget(old.id)
            } else {
                workshop.reportRoom("家具布局已保存。房间固定未完成，可在定位稳定后再次固定。")
            }
        } catch (e: CancellationException) {
            if (workshop.pinPending && pin == workshop.pinTicket) workshop.reportRoom("家具布局已保存。固定中断，可再次固定。")
            throw e
        } catch (_: Exception) {
            workshop.reportRoom("家具布局已保存。当前房间定位服务不可用。")
        } finally {
            if (pin == workshop.pinTicket) workshop.clearPendingPin()
            discard?.let { ref -> withContext(NonCancellable) { WorkshopRoom.forget(ref.id) } }
        }
    }

    val scan = workshop.scanTicket
    LaunchedEffect(binding, focused, scan, workshop.scanPending, workshop.editing) {
        if (!focused || !workshop.editing || !workshop.scanPending) return@LaunchedEffect
        val head = binding.client?.head(SystemClock.elapsedRealtime())
        if (!binding.basisReady || head == null) {
            workshop.scanDone(null, "等待头显定位，再把家具移到真实桌面上方。")
            return@LaunchedEffect
        }
        val draft = workshop.draft
        val id = workshop.selected
        val frames = WorkshopFrames(BenchPlacement(binding.room.center, binding.room.yaw), draft)
        val result = snapFurnitureToTable(frames, id, head.origin.y)
        if (scan == workshop.scanTicket && draft == workshop.draft && id == workshop.selected)
            workshop.scanDone(result.layout, result.message)
    }
}
