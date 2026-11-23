package com.pondledger.mr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommissionSmoke {
    @Test fun preciseSilverCommissionPaysOnceAndChangesAtTheBell() {
        val gift = ForgeGame.start(3, 79L).takeRelic(0)
        val dayTwo = gift.copy(day = 2, phase = Phase.MATERIAL)
        val wrong = dayTwo.grab(Ore.IRON.ordinal).place().strike(true)
        assertFalse(wrong.commissionReady)
        val silver = dayTwo.grab(Ore.SILVER.ordinal).place()
        assertFalse(silver.commissionReady)
        val struck = silver.strike(true)
        assertTrue(struck.commissionReady)
        val expected = struck.offerValue + struck.commission.reward
        val sent = struck.offer()
        assertEquals(expected, sent.tribute)
        assertEquals(struck.commission.reward, sent.commissionEarnings)
        assertEquals(1, sent.commissionsCompleted)
        assertEquals(0, sent.bladePrecisionHits)

        val repeat = sent.grab(Ore.SILVER.ordinal).place().strike(true)
        assertFalse(repeat.commissionReady)
        assertEquals(0L, repeat.commissionBonusNow)
        val later = repeat.offer()
        assertEquals(sent.tribute + repeat.offerValue, later.tribute)
        assertEquals(1, later.commissionsCompleted)
        val nextDay = later.copy(phase = Phase.DUSK).ringBell()
        assertFalse(nextDay.commissionPaid)
        assertEquals(Ore.CRYSTAL, nextDay.commission.ore)
    }
}
