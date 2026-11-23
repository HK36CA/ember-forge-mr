package com.pondledger.mr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopSurfaceSmoke {
    @Test fun acceptsRotatedSupportButRejectsHolesTiltAndUnconfirmedPlanes() {
        val room = BenchPlacement(Point(.9f,1.04f,-.4f),.78f)
        val frames = WorkshopFrames(room,WorkshopLayout().withPose(FurnitureId.BENCH,FurniturePose(.23f,.03f,-.10f,.35f)))
        val bench = frames.bench

        // A dense plane with a tiny scanned-out rectangle missed by nine centre/edge point tests.
        fun grid(label: SupportLabel, y:Float = -.22f, hole:Boolean = false, skew:Float = 0f): SupportPatch {
            val xs = listOf(-.55f,.08f,.12f,.55f)
            val zs = listOf(-.30f,.04f,.07f,.30f)
            val v = zs.flatMap { z -> xs.map { x -> bench.world(Point(x, y + skew*x,z)) } }
            val i = mutableListOf<Int>()
            for (row in 0..2) for (col in 0..2) {
                if (hole && row == 1 && col == 1) continue
                val a = row*4+col
                i.addAll(listOf(a,a+4,a+1,a+1,a+4,a+5))
            }
            return SupportPatch(label,v,i)
        }
        val clear = grid(SupportLabel.TABLE)
        val good = fitWorkshopSurface(frames,FurnitureId.BENCH,listOf(clear)).layout
        assertNotNull(good)
        assertEquals(frames.layout.bench.y-.22f-furnitureFootprint(FurnitureId.BENCH)!!.bottom,good!!.bench.y,.00015f)
        assertEquals(setOf(FurnitureId.BENCH),good.tableMounted)
        assertEquals(frames.layout.bench.yaw,good.bench.yaw,1e-5f)
        assertEquals(frames.layout.tray,good.tray)
        assertNull(fitWorkshopSurface(frames,FurnitureId.BENCH,listOf(grid(SupportLabel.TABLE,hole=true))).layout)
        assertNull(fitWorkshopSurface(frames,FurnitureId.BENCH,listOf(grid(SupportLabel.CABINET,skew=.25f))).layout)
        val unknown = grid(SupportLabel.UNKNOWN)
        assertNull(fitWorkshopSurface(frames,FurnitureId.BENCH,listOf(unknown)).layout)
        assertNotNull(fitWorkshopSurface(frames,FurnitureId.BENCH,listOf(unknown,grid(SupportLabel.FLOOR,y=-.95f))).layout)
        assertNull(fitWorkshopSurface(frames,FurnitureId.BENCH,listOf(clear.copy(indices=listOf(0,1,999)))).layout)
        assertNull(fitWorkshopSurface(frames,FurnitureId.BOARD,listOf(clear)).layout)
    }
}
