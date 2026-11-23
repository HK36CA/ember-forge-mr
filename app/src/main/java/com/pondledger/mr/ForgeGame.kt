package com.pondledger.mr

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

enum class Phase { GIFT, MATERIAL, FORGE, RELICS, DUSK, WON, LOST, DRAGON, FREED }
enum class Ore(val title: String, val detail: String) {
    IRON("磁铁矿", "组织致密，损毁权重减半。"),
    SILVER("星银矿", "起始贡值增加 35%。"),
    CRYSTAL("龙晶矿", "大成功权重翻倍，也更容易损毁。")
}
enum class Mark(val title: String) {
    BREAK("损毁"), DENT("损伤"), HOLD("维持"), RISE("成功"), BLOOM("大成功")
}

enum class Relic(val title: String, val text: String) {
    SCALE("琉璃龙鳞", "降低损毁权重。与余烬珠同持时，损伤会额外充能。"),
    COAL("余烬珠", "每把剑首锤多充 2 格入神。"),
    BELL("铜风铃", "维持时，下次升值多升一级。"),
    INK("夜金墨", "提高大成功权重。入神时效果翻倍。"),
    TOLL("贡台银印", "每把进贡的剑获得 18% 额外贡值。"),
    MOTH("煤羽蛾", "损伤时收集火种，下次升值多升一级。"),
    GLASS("赤沙漏", "每层延长入神一秒。"),
    RING("折光指环", "提高成功权重；每层再增加新胚价值。")
}

data class Lot(val weights: List<Int>) {
    fun percent(mark: Mark) = (weights[mark.ordinal] * 100.0 / weights.sum()).roundToLong().toInt()
    fun draw(roll: Int): Mark {
        var pick = roll % weights.sum()
        for (mark in Mark.entries) {
            pick -= weights[mark.ordinal]
            if (pick < 0) return mark
        }
        return Mark.RISE
    }
}

/** All amounts are fictional tribute points. The saved generator state makes a resumed strike deterministic. */
data class ForgeGame(
    val days: Int, val rng: Long, val phase: Phase = Phase.GIFT,
    val day: Int = 1, val blanks: Int = 3, val tribute: Long = 0, val anger: Int = 0,
    val grade: Int = 0, val value: Long = 6, val charge: Int = 0, val frenzyMs: Int = 0,
    val dents: Int = 0, val bonus: Int = 0, val strikes: Int = 0, val totalStrikes: Int = 0,
    val relics: List<Int> = List(Relic.entries.size) { 0 }, val shelf: List<Int> = listOf(0,1,2),
    val best: Long = 0, val lifetime: Long = 0, val last: String = "先挑选一件开炉赠礼。",
    val dragonHp: Int = 0, val shields: Int = 3, val rune: Int = 0, val endless: Boolean = false,
    val ore: Int = 0, val held: Int = -1,
    val tickets: List<Int> = listOf(0,0,0), val lastTradeDay: Int = 0,
    val spotLane: Int = 2, val precisionHits: Int = 0,
    val bladePrecisionHits: Int = 0, val commissionPaidDay: Int = 0,
    val commissionsCompleted: Int = 0, val commissionEarnings: Long = 0
) {
    companion object {
        const val LIMIT = 900_000_000_000L
        fun start(days: Int, seed: Long = Random.nextLong()) =
            ForgeGame(days = if (days == 24) 24 else 3, rng = seed).newShelf()
    }
    val quota: Long get() {
        if (days == 3) return listOf(22L, 32L, 46L)[(day - 1).coerceIn(0, 2)]
        // A readable early curve and a four-million-point final tribute, then 30% per endless day.
        val base = if (day <= 24) 20.0 * (4_000_000.0 / 20).pow((day - 1) / 23.0)
                   else 4_000_000.0 * 1.3.pow(day - 24)
        return base.coerceAtMost(LIMIT.toDouble()).roundToLong()
    }
    val swordName get() = listOf("灰铁", "赤铜", "流银", "星辉", "龙纹")[(grade / 3).coerceIn(0, 4)] +
            listOf("庭剑", "月刃", "仪剑")[(day + 3 - blanks).mod(3)]
    val isFrenzy get() = frenzyMs > 0
    val frenzyDurationMs get() = 4_000 + 1_000 * count(Relic.GLASS)
    val spotT get() = spotLane.coerceIn(0, 4) / 4f
    val commission get() = Commission.forDay(day, quota)
    val commissionPaid get() = commissionPaidDay == day
    val commissionReady get() = phase == Phase.FORGE && !commissionPaid &&
            commission.accepts(ore, grade, bladePrecisionHits)
    val commissionBonusNow get() = if (commissionReady)
        commission.reward.coerceAtMost((LIMIT - tribute - offerValue).coerceAtLeast(0L)) else 0L
    val totalOfferValue get() = (offerValue + commissionBonusNow).coerceAtMost((LIMIT - tribute).coerceAtLeast(0L))
    val merchantHere get() = (day % 4 == 0 || (days == 3 && day == 3)) && lastTradeDay < day
    val merchantSlot get() = (day / 4) % 3
    val merchantMark get() = listOf(Mark.HOLD, Mark.RISE, Mark.BLOOM)[merchantSlot]
    val lot: Lot get() {
        val pressure = (strikes / 2 + dents * 2).coerceAtMost(19)
        val risk = max(1, 2 + pressure - count(Relic.SCALE) * 3)
        val hazard = when (ore) { 0 -> max(1, risk / 2); 2 -> risk + 5; else -> risk }
        val frenzyProtected = isFrenzy || charge >= 10
        return Lot(listOf(
            if (frenzyProtected || strikes == 0) 0 else hazard,
            if (frenzyProtected) 0 else 10 + pressure,
            18 + tickets[0], 45 + count(Relic.RING) * 5 + tickets[1],
            (7 + count(Relic.INK) * (if (frenzyProtected) 8 else 4)) * (if (ore == 2) 2 else 1) + tickets[2]
        ))
    }
    fun count(relic: Relic) = relics[relic.ordinal]
    fun strikeLot(precise: Boolean): Lot {
        val base = lot
        // Integer tickets keep the relative destruction weight exactly halved, even with a single ticket.
        return if (precise) Lot(base.weights.mapIndexed { i, w -> if (i == Mark.BREAK.ordinal) w else w * 2 }) else base
    }
    fun projectedValue(mark: Mark, precise: Boolean): Long = when (mark) {
        Mark.BREAK -> 0
        Mark.DENT -> max(1L, (value * .75).roundToLong())
        Mark.HOLD -> value
        Mark.RISE, Mark.BLOOM -> {
            val boost = if (precise) 1.25 else 1.0
            val multiplier = if (mark == Mark.BLOOM) 3.5 else 1.5
            (value * multiplier * 1.23.pow(bonus) * boost).coerceAtMost(LIMIT.toDouble()).roundToLong()
        }
    }
    fun tick(ms: Int): ForgeGame {
        if (phase != Phase.FORGE || !isFrenzy || ms <= 0) return this
        val remaining = (frenzyMs - ms).coerceAtLeast(0)
        return copy(frenzyMs = remaining, last = if (remaining == 0) "狂热入神结束，普通锻打概率已恢复。" else last)
    }
    // A 64-bit LCG, using upper bits for every bounded draw.
    private fun advance() = rng * 6364136223846793005L + 1442695040888963407L
    private fun index(bits: Long, size: Int) = ((bits ushr 32) % size.toLong()).toInt()
    private fun newShelf(): ForgeGame {
        val available = Relic.entries.indices.toMutableList()
        var seed = rng
        val choices = List(3) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            available.removeAt(index(seed, available.size))
        }
        return copy(rng = seed, shelf = choices)
    }
    fun openRelics() = if (phase == Phase.FORGE) copy(phase = Phase.RELICS) else this
    fun cancelRelics() = if (phase == Phase.RELICS) copy(phase = Phase.FORGE) else this
    fun buyTickets(): ForgeGame {
        if (phase != Phase.RELICS || !merchantHere || grade < 3) return this
        val bag = tickets.toMutableList().also { it[merchantSlot] += 5 }
        return copy(tickets = bag, lastTradeDay = day)
            .useBlade("幽商收下了剑。往后的每次锻打都多 5 张${merchantMark.title}签。")
    }
    fun takeRelic(slot: Int): ForgeGame {
        if (phase !in listOf(Phase.GIFT, Phase.RELICS) || slot !in shelf.indices) return this
        val relic = Relic.entries[shelf[slot]]
        val inventory = relics.toMutableList().also { it[relic.ordinal]++ }
        val enhanced = copy(relics = inventory)
        return if (phase == Phase.GIFT) enhanced.newBlade().newShelf()
            .copy(last = "带上${relic.title}。从三块矿石中取一块上砧。")
        else enhanced.useBlade("献出${swordName}，获得${relic.title}。")
    }
    private fun newBlade(): ForgeGame {
        val base = if (days == 3) 6L + day - 1 else max(6L, (quota / 7).coerceAtMost(4_000_000))
        return copy(phase = Phase.MATERIAL, held = -1, ore = 0, value = base + count(Relic.RING) * 2, grade = 0, dents = 0, bonus = 0,
            frenzyMs = 0, strikes = 0, charge = 0, bladePrecisionHits = 0)
    }
    fun grab(slot: Int) = if (phase == Phase.MATERIAL && held < 0 && slot in Ore.entries.indices)
        copy(held = slot, last = "已拿起${Ore.entries[slot].title}。移到铁砧松开，或选择「放上铁砧」。") else this
    fun drop() = if (phase == Phase.MATERIAL && held >= 0) copy(held = -1, last = "矿石已放回架上。") else this
    fun place() = if (phase == Phase.MATERIAL && held in Ore.entries.indices)
        copy(phase = Phase.FORGE, ore = held, held = -1, spotLane = index(rng,5), value = if (held == 1) (value * 1.35).roundToLong() else value,
            last = "${Ore.entries[held].title}已上砧。首锤无损毁风险。") else this
    private fun useBlade(message: String): ForgeGame {
        val remaining = blanks - 1
        val spent = copy(blanks = remaining, last = message, frenzyMs = 0, charge = 0)
        return if (remaining <= 0) spent.copy(phase = Phase.DUSK)
            else spent.newBlade().newShelf()
    }
    val offerValue: Long get() = (value * (1.0 + .18 * count(Relic.TOLL))).roundToLong().coerceAtMost(LIMIT)
    fun offer(): ForgeGame {
        if (phase != Phase.FORGE) return this
        val matched = commissionReady
        val premium = commissionBonusNow
        val payout = totalOfferValue
        val finished = copy(
            tribute = tribute + payout, best = max(best, value),
            lifetime = (lifetime + payout).coerceAtMost(LIMIT),
            commissionPaidDay = if (matched) day else commissionPaidDay,
            commissionsCompleted = commissionsCompleted + if (matched) 1 else 0,
            commissionEarnings = (commissionEarnings + premium).coerceAtMost(LIMIT)
        )
        val report = if (matched) "${commission.title}交付 · 贡盘 +${points(payout)}（含酬金 ${points(premium)}）。"
                     else "贡盘收入 ${points(payout)}，${swordName}已封存。"
        return finished.useBlade(report)
    }
    fun strike(precise: Boolean = false): ForgeGame {
        if (phase != Phase.FORGE) return this
        val ignite = charge >= 10 && !isFrenzy
        val hot = if (ignite) copy(charge = 0, frenzyMs = frenzyDurationMs) else this
        val seed = advance()
        val odds = hot.strikeLot(precise)
        val mark = odds.draw(index(seed, odds.weights.sum()))
        val extra = (if (strikes == 0) count(Relic.COAL) * 2 else 0) +
            (if (mark == Mark.DENT && count(Relic.COAL) > 0 && count(Relic.SCALE) > 0) 1 else 0)
        val next = hot.copy(rng = seed, totalStrikes = totalStrikes + 1, strikes = strikes + 1,
            spotLane = (spotLane + 1 + index(seed shl 11, 4)) % 5,
            precisionHits = precisionHits + if (precise) 1 else 0,
            bladePrecisionHits = bladePrecisionHits + if (precise) 1 else 0,
            charge = if (hot.isFrenzy) 0 else (charge + 1 + extra + if (precise) 1 else 0).coerceAtMost(10))
        val msg = (if (ignite) "狂热入神开启！" else "") + (if (precise) "精准 · " else "")
        return when (mark) {
            Mark.BREAK -> next.useBlade("${msg}损毁！${swordName}化为碎屑。")
            Mark.DENT -> next.copy(grade = max(0, grade - 1), value = projectedValue(mark, precise),
                dents = (dents + 1).coerceAtMost(20), bonus = (bonus + count(Relic.MOTH)).coerceAtMost(8),
                last = "${msg}损伤 · 剑值降为 75%。")
            Mark.HOLD -> next.copy(bonus = (bonus + count(Relic.BELL)).coerceAtMost(8), last = "${msg}维持 · 剑身承住了这一锤。")
            Mark.RISE, Mark.BLOOM -> {
                val amount = if (mark == Mark.BLOOM) 3 else 1
                val v = projectedValue(mark, precise)
                next.copy(value = v, grade = (grade + amount + bonus).coerceAtMost(299), bonus = 0,
                    best = max(best, v), last = "${msg}${mark.title} · ${points(value)} → ${points(v)}")
            }
        }
    }
    fun prepareBell() = if (phase == Phase.FORGE && tribute >= quota) copy(phase = Phase.DUSK, frenzyMs = 0) else this
    fun backFromBell() = if (phase == Phase.DUSK && blanks > 0) copy(phase = Phase.FORGE) else this
    fun ringBell(): ForgeGame {
        if (phase != Phase.DUSK) return this
        val paid = tribute >= quota
        val wrath = anger + if (paid) 0 else 1
        val report = if (paid) "第 ${day} 日贡额已清。" else "贡值不足，龙怒增加一格。"
        if (wrath >= 3) return copy(anger = wrath, phase = Phase.LOST, last = "三次失约，巨龙收回了工坊。")
        if (day == days && !endless) return copy(phase = Phase.WON, anger = wrath, last = report)
        return copy(day = day + 1, anger = wrath, blanks = 3, tribute = 0,
            phase = Phase.FORGE, last = report).newBlade().newShelf()
    }
    fun keepForging() = if (phase in listOf(Phase.WON, Phase.FREED) && days == 24)
        copy(endless = true, day = day + 1, blanks = 3, tribute = 0, phase = Phase.FORGE,
            last = "无尽契约生效，每日贡额增长 30%。").newBlade().newShelf() else this
    fun faceDragon(): ForgeGame {
        if (phase != Phase.WON || days != 24) return this
        val seed = advance()
        val hp = if (best >= 4_000_000) 4 else 6
        return copy(phase = Phase.DRAGON, dragonHp = hp, shields = 3, rng = seed, rune = index(seed,3),
            last = "巨龙现形。击中亮起的契印，破开龙契。")
    }
    fun hitRune(slot: Int): ForgeGame {
        if (phase != Phase.DRAGON || slot !in 0..2) return this
        if (slot != rune) return copy(shields = shields - 1, phase = if (shields == 1) Phase.LOST else phase,
            last = if (shields == 1) "龙焰吞没了最后一重护盾。" else "契印不符，龙焰击碎一重护盾。")
        val seed = advance()
        return copy(dragonHp = dragonHp - 1, phase = if (dragonHp == 1) Phase.FREED else phase,
            rng = seed, rune = (rune + 1 + index(seed, 2)) % 3,
            last = if (dragonHp == 1) "最后一道契印碎裂。工坊重获自由。" else "龙印破裂，寻找下一枚符文。")
    }
}

fun points(n: Long): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n)
