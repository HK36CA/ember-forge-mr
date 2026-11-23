package com.pondledger.mr

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class WorkshopSaveSmoke {
    @Test fun cancelledDraftSavedLayoutAndRoomRelocation() {
        // Exercise the same preference keys and editor transaction as an upgrade from 1.0.
        val contents = mutableMapOf("contract" to "existing")
        val prefs = object : SharedPreferences by stub(SharedPreferences::class.java) {
            override fun getString(key: String?, defValue: String?) = contents[key] ?: defValue
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by stub(SharedPreferences.Editor::class.java) {
                private val pending = mutableMapOf<String,String>()
                override fun putString(key: String?, value: String?) = apply { pending[key!!] = value!! }
                override fun commit(): Boolean { contents.putAll(pending); return true }
            }
        }
        val workshop = WorkshopWorkspace(prefs)
        val defaults=WorkshopLayout()
        assertTrue(FurnitureId.entries.all{overlappingFurniture(defaults,it)==null})
        val oldDefault="1|0|0|0|0|0|-.08|.295|0|0|.53|-.4|0"
        val migrated=WorkshopFile.decode(oldDefault)!!
        assertEquals(defaults.tray,migrated.tray)
        assertTrue(migrated.tableMounted.isEmpty())
        val moved=WorkshopFile.decode("1|.3|.1|-.2|.5|-.86|.07|.34|-.6|.21|.77|-.8|.3")!!
        assertEquals(setOf(FurnitureId.BENCH,FurnitureId.TRAY),moved.tableMounted)
        assertEquals(-.86f,moved.tray.x,.00001f)
        assertEquals(.21f,moved.board.x,.00001f)
        val movedFrame=BenchPlacement(moved.bench.center,moved.bench.yaw)
        assertEquals(movedFrame.world(defaults.tribute.center).x,moved.tribute.x,.00001f)
        assertTrue(FurnitureId.entries.all { id -> val p=WorkshopFile.decode(WorkshopFile.encode(moved))!![id]
            val original=moved[id]; (p.center-original.center).dot(p.center-original.center)<.000001f &&
                kotlin.math.abs(p.yaw-original.yaw)<.0001f })
        for(d in menuDistances) {
            val head=Aim(Point(1.8f,1.64f,.78f),Point(-.8f,-.2f,-.6f))
            val menu=menuPlacement(head,d,false,0f)!!
            val delta=menu.center-head.origin
            assertEquals(d*d,delta.x*delta.x+delta.z*delta.z,.00001f)
            assertEquals(.035f,-delta.y,.00001f)
        }
        workshop.begin()
        workshop.select(FurnitureId.TRAY)
        workshop.arrange(-1)
        val left = workshop.draft
        workshop.toggleStand()
        assertTrue(FurnitureId.TRAY in workshop.draft.tableMounted)
        workshop.undo()
        workshop.nudge(turn = 1)
        assertNotEquals(left, workshop.draft)
        workshop.undo()
        assertEquals(left, workshop.draft)
        assertTrue(workshop.save(pin = true))
        assertTrue(workshop.pinPending)
        val restarted = WorkshopWorkspace(prefs)
        assertEquals(left, restarted.saved)
        restarted.begin()
        restarted.reset()
        restarted.cancel()
        assertEquals(left, restarted.layout)
        assertEquals("existing", contents["contract"])
        assertNull(WorkshopFile.decode("1|NaN"))

        val anchor = BenchPlacement(Point(.4f,1.1f,-.8f), .8f)
        val room = BenchPlacement(Point(-.3f,.9f,.5f), -.53f)
        val id = UUID.randomUUID()
        val ref = WorkshopRoom.relative(id,anchor,room)
        assertTrue(restarted.replacePin(ref))
        val stored = WorkshopWorkspace(prefs).reference!!
        assertEquals(ref, stored)
        val reread = WorkshopRoom.recover(anchor,stored)
        assertEquals(room.center.x,reread.center.x,.00001f)
        assertEquals(room.center.y,reread.center.y,.00001f)
        assertEquals(room.center.z,reread.center.z,.00001f)
        assertEquals(room.yaw,reread.yaw,.00001f)
    }

    private fun <T: Any> stub(type: Class<T>): T = requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _, method, _ ->
        error("Unexpected preference operation " + method.name)
    }))
}
