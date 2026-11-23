package com.pondledger.mr.content

import com.pondledger.mr.theme.ForgeSwatch
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager
import com.pondledger.mr.*
import com.pondledger.mr.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun HomeStage() {
    val app = LocalContext.current.applicationContext
    val session = remember(app) { ForgeSession(app) }
    val focused by LocalSpatialContainerStateManager.current.isFocused
    var hadFocus by remember { mutableStateOf(false) }
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(focused) {
        if (focused) { hadFocus = true; session.refreshPermission() }
        else if (hadFocus) session.pause()
    }
    val g = session.game
    val ticking = session.page == Page.TABLE && g?.phase == Phase.FORGE && g.isFrenzy
    LaunchedEffect(session.page, focused, ticking) {
        if (!focused) return@LaunchedEffect
        if (session.page == Page.DEVICES) while (isActive) { session.refreshPermission(); delay(650) }
        if (ticking) {
            var then = SystemClock.elapsedRealtime()
            while (isActive) {
                val left = session.game?.frenzyMs ?: break
                if (left <= 0) break
                delay(FrenzyRhythm.nextTickDelay(left))
                val now = SystemClock.elapsedRealtime()
                session.tick((now - then).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                then = now
            }
        }
    }
    ForgeStage(
        state = ForgeSceneState(
            active = session.page == Page.TABLE && g?.phase in listOf(Phase.FORGE, Phase.MATERIAL, Phase.DRAGON),
            selected = session.selected, strikeSerial = g?.totalStrikes ?: 0,
            eyeEnabled = session.eyeEnabled, eyeInspect = session.page == Page.DEVICES, recenterSerial = session.recenterSerial,
            heat = if (g?.isFrenzy == true) 1f else (.26f + .065f * (g?.grade ?: 0)).coerceAtMost(.96f),
            materialMode = session.workshop.editing || g?.phase == Phase.MATERIAL,
            heldMaterial = if (session.workshop.editing) -1 else g?.held ?: -1,
            dragon = !session.workshop.editing && g?.phase == Phase.DRAGON, rune = if (g?.phase == Phase.DRAGON) g.rune else -1,
            precisionOn = session.page == Page.TABLE && g?.phase == Phase.FORGE, spotT = g?.spotT ?: .5f,
            panelStrikeSerial = session.panelStrikeSerial, panelPrecise = session.panelPrecise,
            assistedSwing = session.assistedSwing, forgedOre = g?.ore ?: -1,
            frenzyLeft = g?.frenzyMs ?: 0, frenzySpan = g?.frenzyDurationMs ?: 4000
        ),
        workshop = session.workshop,
        tablePage = session.page == Page.TABLE && g?.phase in listOf(Phase.FORGE,Phase.MATERIAL,Phase.DRAGON),
        menuKey = session.page.ordinal * 32 + (if(session.page==Page.TABLE) g?.phase?.ordinal ?: 0 else 0),
        menuDistance = session.menuDistance,
        ledger = { WorkshopBoardSample(g,session.workshop.editing) },
        onRecall = session::editWorkshop, onMix = session::updateMix, onRoomRelocated = session::pause,
        onAction = session::action, onFocus = session::focus, onGrab = session::grab, onPlace = session::place,
        onDrop = session::drop, onEyeReport = session::tracking, onStrike = session::strike, onSpotLock = session::lockSpot
    ) { PicoTheme { ForgePanel(session) } }
}

@Composable
private fun CopyLine(text: String, heading: Boolean = false, accent: Boolean = false) {
    Text(text, style = if (heading) PicoTheme.typography.titleMedium.copy(fontSize = 31.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
        else PicoTheme.typography.bodyMedium.copy(fontSize = 19.sp, lineHeight = 28.sp),
        color = if (accent) ForgeSwatch.MenuGold.color else if (heading) ForgeSwatch.MenuCream.color else ForgeSwatch.MenuMuted.color)
}

@Composable
private fun Action(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(
        containerColor = if (primary) ForgeSwatch.MenuGold.color else ForgeSwatch.MenuButton.color,
        contentColor = if (primary) ForgeSwatch.MenuDark.color else ForgeSwatch.MenuCream.color)) { Text(label) }
}

@Composable
private fun ForgePanel(s: ForgeSession) {
    // A physical instrument attached to the Mixed Stage has its own opaque face for room-independent contrast.
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxSize().clip(shape).background(ForgeSwatch.MenuCharcoal.color)) {
        if (s.page == Page.HOME) {
            Image(painterResource(R.drawable.ember_cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                listOf(ForgeSwatch.MenuDark.color.copy(alpha = .98f), ForgeSwatch.MenuDark.color.copy(alpha = .81f), Color.Transparent))))
            Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 27.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (s.record > 0) CopyLine("最高剑值  ${points(s.record)}", accent = true)
                    Text("余烬工坊", color = ForgeSwatch.MenuCream.color, style = PicoTheme.typography.displayMedium.copy(
                        fontSize = 53.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold))
                    CopyLine("每日三枚剑胚，三次欠贡便失去工坊。")
                    CopyLine("再敲一锤，还是现在进贡？", accent = true)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (s.canContinue) Action("继续契约", true, s::resume)
                        Action("三日短约", !s.canContinue) { s.start(3) }
                        Action("二十四日长约") { s.start(24) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Action("布置工坊", onClick = s::editWorkshop)
                        Action(if(s.eyeEnabled)"检查眼动" else "启用眼动") {
                            s.details(Page.DEVICES)
                            if(!s.eyeEnabled)s.toggleEye()
                        }
                        Action("操作指南") { s.details(Page.GUIDE) }
                        Action("设备与定位") { s.details(Page.DEVICES) }
                    }
                }
            }
        } else if (s.page == Page.WORKSHOP) {
            WorkshopEditorPanel(s)
        } else if (s.page == Page.TABLE && s.game != null) {
            Box(Modifier.fillMaxSize().padding(24.dp)) { ForgeTablePanel(s, s.game!!) }
        } else {
            Column(Modifier.fillMaxSize().padding(25.dp), verticalArrangement = Arrangement.SpaceBetween) {
                when (s.page) {
                    Page.DEVICES -> {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.SpaceBetween) {
                            CopyLine("设备与定位",true)
                            Action(s.menuDistanceLabel,onClick=s::cycleMenuDistance)
                        }
                        EyeDeviceReadout(s)
                        CopyLine(s.workshop.roomStatus)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action(if (s.eyeEnabled) "关闭眼动" else if (s.wantsEye) "请求眼动权限" else "启用眼动", !s.eyeEnabled, s::toggleEye)
                            Action(if (s.sound) "声音 · 开" else "声音 · 关", onClick = s::toggleSound)
                            Action(if (s.assistedSwing) "辅助落锤 · 开" else "辅助落锤 · 关", onClick = s::toggleAssistedSwing)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("整坊移到面前", onClick = s::recenter)
                            if (s.workshop.reference != null) Action("找回房间", onClick = s.workshop::restore)
                            Action("返回", onClick = s::back)
                        }
                    }
                    Page.GUIDE -> {
                        CopyLine("操作指南", true)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CopyLine("取料　手柄或裸手可以指向矿石。开启眼动后，看稳一块矿石，等青色注视环合拢，再握住手柄握键或用拇指与食指捏合，移至铁砧松开。")
                            CopyLine("落锤　上砧后，短柄锤会出现在手里。把锤头抬起约十厘米，让银色锤面朝下敲到剑身。敲中光环，或看稳光环再敲中剑身，可得精准加成。")
                            CopyLine("换手　用另一只手握住握键或持续捏合约半秒，锤柄会交到那只手。")
                            CopyLine("坐姿远距　可在设备页打开「辅助落锤」，再指向铁砧扣扳机或捏合，让辅助锤落下。")
                            CopyLine("营业　贡台收剑，遗物台用剑换遗物。满十格后下一锤开启狂热，彩虹字亮起时剑不会损伤或碎裂。每日委托可以和进贡一起完成。", accent = true)
                            CopyLine("布置　抓住家具的发光把手，移动、转腕、松开。双手同时长捏或双手柄同时握紧 1.5 秒，可把布置面板叫到面前。")
                        }
                        Action("返回", onClick = s::back)
                    }
                    Page.BOOK -> {
                        CopyLine("遗物册", true)
                        val relics = Relic.entries.filter { (s.game?.count(it) ?: 0) > 0 }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (relics.isEmpty()) CopyLine("开炉时可挑选第一件遗物。")
                            relics.forEach { CopyLine("${it.title} ×${s.game?.count(it)}　${it.text}") }
                            s.game?.tickets?.takeIf { it.sum() > 0 }?.let { CopyLine("幽商签　维持 +${it[0]}　成功 +${it[1]}　大成功 +${it[2]}", accent = true) }
                        }
                        Action("返回", onClick = s::back)
                    }
                    Page.REPLACE -> {
                        CopyLine("重新签约？", true)
                        CopyLine("新契约会替换第 ${s.game?.day} 日的存档，最高剑值会保留。")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Action("保留存档", true, s::back); Action("开始新契约", onClick = s::confirmStart)
                        }
                    }
                    else -> {
                        CopyLine("炉火暂歇", true)
                        CopyLine("第 ${s.game?.day} 日　·　进度已保存", accent = true)
                        CopyLine(s.workshop.roomStatus)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Action("继续", true, s::resume); Action("遗物册") { s.details(Page.BOOK) }
                            Action("设备与定位") { s.details(Page.DEVICES) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Action("布置工坊", onClick = s::editWorkshop)
                            Action("操作指南") { s.details(Page.GUIDE) }; Action("回主菜单", onClick = s::home)
                        }
                    }
                }
            }
        }
    }
}
