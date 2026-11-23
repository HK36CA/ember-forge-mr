package com.pondledger.mr

import com.pondledger.mr.theme.ForgeSwatch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.delay

private val workNames = listOf("铁砧", "贡台", "遗物炉")

@Composable
private fun TableText(copy: String, size: Int = 18, color: Color = ForgeSwatch.LedgerParchment.color, bold: Boolean = false) {
    Text(
        text = copy,
        color = color,
        style = PicoTheme.typography.bodyLarge.copy(
            fontSize = size.sp, lineHeight = (size + 5).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    )
}

@Composable
private fun TableKey(label: String, accent: Boolean = false, click: () -> Unit) {
    Button(
        onClick = click,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) ForgeSwatch.LedgerHotBrass.color else ForgeSwatch.LedgerColdIron.color,
            contentColor = if (accent) ForgeSwatch.LedgerDarkInk.color else ForgeSwatch.LedgerParchment.color
        )
    ) { Text(text = label) }
}

@Composable
private fun TableRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun ForgeTablePanel(s: ForgeSession, g: ForgeGame) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TableRow {
            val date = if (g.endless) "无尽 · 第 ${g.day} 日" else "第 ${g.day} / ${g.days} 日"
            Column(modifier = Modifier.weight(1f)) {
                TableText(date, 19, ForgeSwatch.LedgerHotBrass.color, true)
                TableText("贡盘 ${points(g.tribute)} / ${points(g.quota)}    余胚 ${g.blanks}    龙怒 ${g.anger}/3", 16, ForgeSwatch.LedgerCoolAsh.color)
            }
            TableKey("遗物册") { s.details(Page.BOOK) }
            TableKey("暂停", click = s::pause)
        }
        if (g.phase == Phase.FORGE) ForgeHeat(g)
        key(g.phase) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (g.phase) {
                    Phase.GIFT, Phase.RELICS -> RelicChoices(s, g)
                    Phase.MATERIAL -> MaterialChoice(s, g)
                    Phase.FORGE -> ForgeReadings(s, g)
                    Phase.DUSK -> DayBell(s, g)
                    Phase.WON, Phase.LOST, Phase.FREED -> ContractEnd(s, g)
                    Phase.DRAGON -> DragonSeal(s, g)
                }
            }
        }
        // Keep transaction actions reachable even with a long commission, a large number or enlarged text.
        if (g.phase == Phase.FORGE) ForgeActions(s, g)
    }
}

@Composable
private fun RelicChoices(s: ForgeSession, g: ForgeGame) {
    val gift = g.phase == Phase.GIFT
    TableText(if (gift) "选一件开炉赠礼" else "用${g.swordName}换一件遗物", 29, bold = true)
    TableText(if (gift) "三选一，效果持续到本次契约结束。" else "选定后交出当前剑，贡盘不会增加。", 17, ForgeSwatch.LedgerCoolAsh.color)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        g.shelf.forEachIndexed { slot, relicIndex ->
            val relic = Relic.entries[relicIndex]
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                TableKey(relic.title, accent = s.selected == slot) { s.relic(slot) }
                TableText(relic.text, 16, ForgeSwatch.LedgerCoolAsh.color)
            }
        }
    }
    if (!gift) TableRow {
        TableKey("保留这把剑", click = s::cancelRelic)
        if (g.merchantHere && g.grade >= 3) TableKey("幽商 · 换5张${g.merchantMark.title}签", click = s::visitMerchant)
        else if (g.merchantHere) TableText("幽商收 +3 以上的剑，可换本局永久签。", 16, ForgeSwatch.LedgerHotBrass.color)
    }
}

@Composable
private fun CommissionHint(g: ForgeGame, onAnvil: Boolean) {
    val order = g.commission
    val message = when {
        g.commissionPaid -> "今日委托 · ${order.title}已交付"
        g.commissionReady -> "${order.title}已达标 · 现在进贡可得酬金 +${compactPoints(g.commissionBonusNow)}"
        onAnvil && g.ore == order.ore.ordinal -> {
            val progress = listOfNotNull(
                if (order.minGrade > 0) "品级 ${g.grade}/${order.minGrade}" else null,
                if (order.minPrecision > 0) "本剑精准 ${g.bladePrecisionHits}/${order.minPrecision}" else null
            ).joinToString("   ")
            "${order.title} · $progress    酬金 +${compactPoints(order.reward)}"
        }
        onAnvil -> "普通贡剑 · 今日「${order.title}」收${order.ore.title}，酬金 +${compactPoints(order.reward)}"
        else -> "可选委托「${order.title}」   ${order.requirement}    进贡加 ${compactPoints(order.reward)}"
    }
    TableText(message, 16, if (g.commissionReady || !onAnvil) ForgeSwatch.LedgerHotBrass.color else ForgeSwatch.LedgerCoolAsh.color)
}

@Composable
private fun MaterialChoice(s: ForgeSession, g: ForgeGame) {
    val held = Ore.entries.getOrNull(g.held)
    if (held != null) {
        TableText("手中 · ${held.title}", 30, ForgeSwatch.LedgerHotBrass.color, true)
        TableText(held.detail, 19)
        CommissionHint(g, onAnvil = false)
        TableText("靠近铁砧再松开握键或张开手指；远处也可点「放上铁砧」。", 17, ForgeSwatch.LedgerCoolAsh.color)
        TableRow {
            TableKey("放上铁砧", true, s::place)
            TableKey("放回料架", click = s::drop)
        }
        return
    }
    val focus = Ore.entries.getOrNull(s.selected)
    TableText(focus?.let { "选料 · ${it.title}" } ?: "为这把剑选料", 29, bold = true)
    CommissionHint(g, onAnvil = false)
    TableText(focus?.detail ?: "看向或指向矿石，按住手柄握键或捏合取料。", 18, ForgeSwatch.LedgerHotBrass.color)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Ore.entries.forEachIndexed { index, ore ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                TableKey("取${ore.title}", accent = s.selected == index) { s.grab(index) }
                TableText(ore.detail, 16, ForgeSwatch.LedgerCoolAsh.color)
            }
        }
    }
    TableText(g.last, 15, ForgeSwatch.LedgerCoolAsh.color)
}

private fun compactPoints(value: Long): String = when {
    value >= 100_000_000 -> java.lang.String.format(java.util.Locale.ROOT, "%.1f亿", value / 100_000_000.0)
    value >= 100_000 -> java.lang.String.format(java.util.Locale.ROOT, "%.1f万", value / 10_000.0)
    else -> points(value)
}

@Composable
internal fun AnvilFrenzy(remainingMs: Int, durationMs: Int) {
    FrenzySignal(remainingMs, durationMs, true, PicoTheme.typography.bodyLarge, Modifier.size(225.dp,52.dp))
}

@Composable
private fun ForgeHeat(g: ForgeGame) {
    var previouslyActive by remember { mutableStateOf(g.isFrenzy) }
    var showExpiry by remember { mutableStateOf(false) }
    LaunchedEffect(g.isFrenzy) {
        showExpiry = previouslyActive && !g.isFrenzy
        previouslyActive = g.isFrenzy
        if (showExpiry) {
            delay(1500)
            showExpiry = false
        }
    }
    if (g.isFrenzy) {
        TableRow {
            FrenzySignal(g.frenzyMs, g.frenzyDurationMs, false, PicoTheme.typography.bodyLarge,
                Modifier.weight(1f).height(56.dp))
            TableText("进贡 ${compactPoints(g.totalOfferValue)}", 17, ForgeSwatch.LedgerCoolAsh.color)
        }
        return
    }
    TableRow {
        val status = when {
            g.charge >= 10 -> "入神就绪 · 下一锤开启保护"
            showExpiry -> "入神结束 · 已恢复普通锻打"
            else -> "入神蓄能  ${g.charge} / 10"
        }
        TableText(status, 18, if (g.charge >= 10 || showExpiry) ForgeSwatch.LedgerHotBrass.color else ForgeSwatch.LedgerCoolAsh.color, true)
        val fraction = g.charge / 10f
        Canvas(Modifier.size(79.dp, 8.dp)) {
            val gap = 2.dp.toPx()
            val width = (size.width - 9 * gap) / 10f
            repeat(10) { index ->
                val start = Offset(index * (width + gap), 0f)
                drawRect(ForgeSwatch.LedgerColdIron.color, start, Size(width, size.height))
                val fill = (fraction * 10f - index).coerceIn(0f, 1f)
                if (fill > 0f) drawRect(ForgeSwatch.LedgerHotBrass.color, start, Size(width * fill, size.height))
            }
        }
        Spacer(Modifier.weight(1f))
        TableText("进贡 ${compactPoints(g.totalOfferValue)}", 17, ForgeSwatch.LedgerCoolAsh.color)
    }
}

@Composable
private fun ForgeReadings(s: ForgeSession, g: ForgeGame) {
    TableRow {
        TableText("${g.swordName}  +${g.grade}", 28, bold = true)
        Spacer(Modifier.width(6.dp))
        TableText("剑值 ${points(g.value)}", 26, ForgeSwatch.LedgerHotBrass.color, true)
    }
    val guide = when {
        s.assistedSwing && s.spotLocked -> "精准点已瞄准。点「辅助落锤」，锤头会落在光环上。"
        s.assistedSwing -> "瞄准剑身光环，点「辅助落锤」；锤头落到剑上时结算。"
        s.spotLocked -> "精准点已瞄准。抬起短锤，平锤面朝下敲到剑身。"
        else -> "抬起跟手短锤，把平锤面朝下敲到剑身。击中光环可获精准奖励。"
    }
    TableText(guide, 17, if (s.spotLocked) ForgeSwatch.LedgerHotBrass.color else ForgeSwatch.LedgerParchment.color)
    val rise = compactPoints(g.projectedValue(Mark.RISE, false))
    val aimedRise = compactPoints(g.projectedValue(Mark.RISE, true))
    val bloom = compactPoints(g.projectedValue(Mark.BLOOM, false))
    val aimedBloom = compactPoints(g.projectedValue(Mark.BLOOM, true))
    TableText("剑值［基础 → 精准］    成功 $rise → $aimedRise    大成功 $bloom → $aimedBloom", 16, ForgeSwatch.LedgerCoolAsh.color)
    val lot = g.strikeLot(s.spotLocked)
    TableRow {
        Mark.entries.forEach { mark ->
            Column(Modifier.weight(1f)) {
                TableText(mark.title, 15, ForgeSwatch.LedgerCoolAsh.color)
                TableText("${lot.percent(mark)}%", 21, if (mark == Mark.BLOOM) ForgeSwatch.LedgerHotBrass.color else ForgeSwatch.LedgerParchment.color, true)
            }
        }
    }
    TableText(g.last, 17, ForgeSwatch.LedgerParchment.color)
    CommissionHint(g, onAnvil = true)
}

@Composable
private fun ForgeActions(s: ForgeSession, g: ForgeGame) {
    TableRow {
        if (s.assistedSwing) {
            TableKey("辅助落锤", accent = s.spotLocked || s.selected == 0) { s.action(0) }
        }
        TableKey(if (g.commissionReady) "进贡交单" else "进贡", accent = s.selected == 1 || g.commissionReady) { s.action(1) }
        TableKey(if (g.merchantHere) "遗物 / 幽商" else "换遗物", accent = s.selected == 2) { s.action(2) }
        if (g.tribute >= g.quota) TableKey("提前敲钟", click = s::prepareBell)
    }
}

@Composable
private fun DayBell(s: ForgeSession, g: ForgeGame) {
    val paid = g.tribute >= g.quota
    TableText(if (paid) "今日贡额已齐" else "今天的贡盘不足", 30, bold = true)
    TableText(
        if (paid) "贡盘比目标多 ${points(g.tribute - g.quota)} 点。敲钟结束今天，余贡随日结清。"
        else "还差 ${points(g.quota - g.tribute)} 点。敲钟将增加一格龙怒；三格会终止契约。"
    )
    val orderResult = if (g.commissionPaid) "已交付" else "未交，敲钟后到期"
    TableText("今日委托「${g.commission.title}」$orderResult    ·    累计精准 ${g.precisionHits} 锤", 17, ForgeSwatch.LedgerHotBrass.color)
    if (g.blanks > 0) TableText("尚有 ${g.blanks} 枚剑胚，敲钟后随本日一同结清。", 17, ForgeSwatch.LedgerCoolAsh.color)
    TableRow {
        TableKey("敲贡钟", true, s::ringBell)
        if (g.blanks > 0) TableKey("继续锻造", click = s::cancelBell)
    }
}

@Composable
private fun DragonSeal(s: ForgeSession, g: ForgeGame) {
    TableText("守印巨龙", 30, bold = true)
    TableText("击破 ${workNames[g.rune]} 上的契印", 24, ForgeSwatch.LedgerHotBrass.color, true)
    TableText("余印 ${g.dragonHp}    护盾 ${g.shields} / 3", 18, ForgeSwatch.LedgerCoolAsh.color)
    TableRow {
        workNames.forEachIndexed { i, name ->
            TableKey(name, accent = g.rune == i) { s.action(i) }
        }
    }
    TableText(g.last, 17, ForgeSwatch.LedgerCoolAsh.color)
}

@Composable
private fun ContractEnd(s: ForgeSession, g: ForgeGame) {
    val title = when (g.phase) {
        Phase.LOST -> "契约终止"
        Phase.FREED -> "工坊重获自由"
        else -> "契约期满"
    }
    TableText(title, 32, ForgeSwatch.LedgerHotBrass.color, true)
    TableText(g.last, 20)
    TableText("委托 ${g.commissionsCompleted} 单    酬金 +${points(g.commissionEarnings)}", 18, ForgeSwatch.LedgerHotBrass.color)
    TableText("最高剑值 ${points(g.best)}    累计进贡 ${points(g.lifetime)}    总锤数 ${g.totalStrikes}    精准 ${g.precisionHits}", 17, ForgeSwatch.LedgerCoolAsh.color)
    TableRow {
        if (g.days == 24 && g.phase == Phase.WON) TableKey("挑战守印巨龙", true, s::dragon)
        if (g.days == 24 && g.phase != Phase.LOST) TableKey("续签无尽", click = s::endless)
        if (g.phase == Phase.LOST) TableKey("重开契约", true) { s.start(g.days) }
        TableKey("回到首页", click = s::home)
    }
}
