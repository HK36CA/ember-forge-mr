package com.pondledger.mr

/** Both the forge chime and the countdown read the same beats in remaining game time. */
internal object FrenzyRhythm {
    private val marks = intArrayOf(2000, 1440, 1000, 650, 380, 170)

    fun nextTickDelay(remainingMs: Int): Long {
        val nextBeat = marks.firstOrNull { it < remainingMs } ?: 0
        return (remainingMs - nextBeat).coerceIn(1, 100).toLong()
    }

    fun beat(previousMs: Int, remainingMs: Int): Int? {
        // Do not catch up missed sounds when a stalled frame finally arrives.
        if (remainingMs <= 0 || previousMs - remainingMs !in 1..185) return null
        return marks.indices.firstOrNull { previousMs > marks[it] && remainingMs <= marks[it] }
    }

    fun pulse(remainingMs: Int): Float = marks.maxOf { threshold ->
        val age = threshold - remainingMs
        if (age in 0..170) 1f - age / 170f else 0f
    }
}
