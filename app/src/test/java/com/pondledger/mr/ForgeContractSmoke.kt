package com.pondledger.mr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeContractSmoke {
    @Test
    fun frenzyProtectsTheTriggerAndFinalMomentThenRestoresNormalRisk() {
        val ready = ForgeGame.start(3, 19L).takeRelic(0).grab(Ore.CRYSTAL.ordinal).place()
            .copy(charge = 10, grade = 8, value = 800, strikes = 10)
        for (precise in listOf(false, true)) {
            val first = ready.strike(precise)
            val finalMoment = first.tick(first.frenzyDurationMs - 1)
            for (state in listOf(ready, first, finalMoment)) {
                val odds = state.strikeLot(precise)
                assertEquals(0, odds.weights[Mark.BREAK.ordinal])
                assertEquals(0, odds.weights[Mark.DENT.ordinal])
                assertTrue(odds.weights[Mark.HOLD.ordinal] > 0)
                val hit = state.strike(precise)
                assertTrue(hit.grade >= state.grade && hit.value >= state.value)
                assertEquals(state.blanks, hit.blanks)
                assertEquals(0, hit.charge)
            }
            val cooled = finalMoment.tick(1)
            assertEquals(0, cooled.frenzyMs)
            assertTrue(cooled.strikeLot(precise).weights[Mark.BREAK.ordinal] > 0)
            assertTrue(cooled.strikeLot(precise).weights[Mark.DENT.ordinal] > 0)
        }
    }

    @Test
    fun playContractsThroughTheSameCommandsAsTheTable() {
        for (length in listOf(3, 24)) {
            var g = ForgeGame.start(length, seed = 79L)
            val gift = g.shelf.indexOf(Relic.TOLL.ordinal).takeIf { it >= 0 } ?: 0
            g = g.takeRelic(gift)
            var actions = 0
            while (g.phase !in listOf(Phase.WON, Phase.LOST) && actions++ < 1800) {
                g = when (g.phase) {
                    Phase.MATERIAL -> {
                        val ore = if (g.blanks == 3) Ore.IRON else Ore.SILVER
                        val picked = g.grab(ore.ordinal)
                        assertEquals(ore.ordinal, picked.held)
                        assertEquals(-1, picked.drop().held)
                        val loaded = picked.place()
                        val marked = loaded.strike(true)
                        assertEquals(loaded.precisionHits + 1, marked.precisionHits)
                        assertTrue(marked.spotT != loaded.spotT && marked.spotT in 0f..1f)
                        assertTrue(loaded.projectedValue(Mark.RISE, true) > loaded.projectedValue(Mark.RISE, false))
                        val tickets = loaded.copy(strikes = 4)
                        val normal = tickets.strikeLot(false).weights
                        val precise = tickets.strikeLot(true).weights
                        assertEquals(normal[0], precise[0])
                        assertEquals(normal[3] * 2, precise[3])
                        loaded
                    }
                    Phase.FORGE -> {
                        assertEquals(g, g.openRelics().cancelRelics())
                        if (g.tribute >= g.quota) g.prepareBell()
                        else if (g.offerValue >= g.quota - g.tribute ||
                            g.offerValue * g.blanks >= g.quota + g.quota / 4) g.offer()
                        else g.strike().tick(450)
                    }
                    Phase.DUSK -> g.ringBell()
                    else -> error("unreachable phase ${g.phase}")
                }
            }
            assertEquals("contract length $length day ${g.day}", Phase.WON, g.phase)
            assertTrue(g.totalStrikes > 0 && g.lifetime > 0)
            if (length == 24) {
                var trial = g.faceDragon()
                while (trial.phase == Phase.DRAGON) trial = trial.hitRune(trial.rune)
                assertEquals(Phase.FREED, trial.phase)
                assertEquals(25, trial.keepForging().day)
            }
        }
    }
}
