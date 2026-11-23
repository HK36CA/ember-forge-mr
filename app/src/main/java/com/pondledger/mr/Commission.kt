package com.pondledger.mr

/** The day's single optional commission uses the same blade and tribute tray as normal play. */
data class Commission(
    val title: String,
    val ore: Ore,
    val minGrade: Int,
    val minPrecision: Int,
    val reward: Long
) {
    val requirement: String
        get() = listOfNotNull(
            ore.title,
            if (minGrade > 0) "品级 +$minGrade" else null,
            if (minPrecision > 0) "本剑精准 $minPrecision 锤" else null
        ).joinToString(" · ")

    fun accepts(bladeOre: Int, grade: Int, preciseHits: Int) =
        bladeOre == ore.ordinal && grade >= minGrade && preciseHits >= minPrecision

    companion object {
        private data class Request(val title: String, val ore: Ore, val grade: Int, val hits: Int)
        private val requests = listOf(
            Request("晨钟礼器", Ore.IRON, 1, 0),
            Request("星图刻纹", Ore.SILVER, 0, 1),
            Request("余火祭礼", Ore.CRYSTAL, 2, 1),
            Request("银匠样剑", Ore.SILVER, 2, 0),
            Request("晶灯铭礼", Ore.CRYSTAL, 0, 2),
            Request("守炉徽记", Ore.IRON, 1, 1)
        )

        fun forDay(day: Int, quota: Long): Commission {
            val index = (day.coerceAtLeast(1) - 1) % requests.size
            val request = requests[index]
            val grade = request.grade + if (request.grade > 0) (day / 7).coerceIn(0, 2) else 0
            val hits = request.hits + if (request.hits > 0 && day >= 13) 1 else 0
            val percent = 10 + (grade.coerceAtMost(4) * 2) + (hits.coerceAtMost(3) * 3)
            val reward = ((quota * percent + 99) / 100).coerceAtLeast(3)
            return Commission(request.title, request.ore, grade, hits, reward)
        }
    }
}
