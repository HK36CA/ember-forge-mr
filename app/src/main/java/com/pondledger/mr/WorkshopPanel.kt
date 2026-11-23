package com.pondledger.mr

import com.pondledger.mr.theme.ForgeSwatch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

private val FurnitureId.displayName get() = when (this) {
    FurnitureId.BENCH -> "锻造台"
    FurnitureId.TRAY -> "矿料架"
    FurnitureId.TRIBUTE -> "贡台"
    FurnitureId.RELIC -> "遗物台"
    FurnitureId.BOARD -> "读数板"
}

private val stationOrder = listOf(FurnitureId.BENCH, FurnitureId.TRAY, FurnitureId.TRIBUTE, FurnitureId.RELIC, FurnitureId.BOARD)

private class OrientedBody(val pose: FurniturePose, val shape: FurnitureEnvelope) {
    val xAxis = Point(cos(pose.yaw),0f,-sin(pose.yaw))
    val zAxis = Point(sin(pose.yaw),0f,cos(pose.yaw))
    fun extent(axis: Point) = abs(xAxis.dot(axis))*shape.halfX + abs(zAxis.dot(axis))*shape.halfZ
}

/** A non-blocking message is shown for interpenetration, not mere contact or vertical stacking. */
internal fun overlappingFurniture(layout: WorkshopLayout, selected: FurnitureId): FurnitureId? {
    val a = OrientedBody(layout[selected], furnitureEnvelope(selected))
    return FurnitureId.entries.firstOrNull { candidate ->
        if (candidate == selected) return@firstOrNull false
        val b = OrientedBody(layout[candidate], furnitureEnvelope(candidate))
        val overlapY = minOf(a.pose.y+a.shape.top,b.pose.y+b.shape.top) -
                maxOf(a.pose.y+a.shape.bottom,b.pose.y+b.shape.bottom)
        if (overlapY <= .006f) return@firstOrNull false
        val delta = a.pose.center-b.pose.center
        listOf(a.xAxis,a.zAxis,b.xAxis,b.zAxis).all { axis ->
            a.extent(axis)+b.extent(axis)-abs(delta.dot(axis)) > .006f
        }
    }
}

@Composable
private fun WorkshopText(
    text: String, size: Int = 17, ink: Color = ForgeSwatch.WorkshopText.color,
    bold: Boolean = false, maxLines: Int = 2
) {
    Text(
        text = text, color = ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = PicoTheme.typography.bodyLarge.copy(
            fontSize = size.sp, lineHeight = (size+5).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    )
}

@Composable
private fun WorkshopButton(
    text: String, enabled: Boolean = true, selected: Boolean = false,
    primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val color = when {
        primary -> ForgeSwatch.WorkshopBronze.color
        selected -> ForgeSwatch.WorkshopSelected.color
        else -> ForgeSwatch.WorkshopControl.color
    }
    Button(
        modifier = modifier, enabled = enabled, onClick = onClick, size = ButtonDefaults.Regular,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = if (primary || selected) ForgeSwatch.WorkshopDeep.color else ForgeSwatch.WorkshopText.color
        )
    ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun WorkshopRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp), content = content
    )
}

/** The header remains reachable while longer status messages or enlarged text scroll below it. */
@Composable
internal fun WorkshopEditorPanel(s: ForgeSession) {
    val w = s.workshop
    var fine by remember(w) { mutableStateOf(false) }
    val holding = w.held != null
    val available = !holding && !w.scanPending
    val overlap = remember(w.draft, w.selected) { overlappingFurniture(w.draft,w.selected) }
    PicoTheme {
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp))
                .background(ForgeSwatch.WorkshopFace.color).padding(horizontal = 24.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WorkshopRow {
                Column(Modifier.weight(1f)) {
                    WorkshopText("布置工坊", 28, bold = true, maxLines = 1)
                    WorkshopText(w.roomStatus, 14, ForgeSwatch.WorkshopSecondary.color, maxLines = 2)
                }
                WorkshopButton("取消", enabled = !holding) { s.finishWorkshop(false) }
                WorkshopButton("保存", enabled = available, primary = true) { s.finishWorkshop(true) }
                WorkshopButton("保存并固定到房间", enabled = available && !w.pinPending) {
                    s.finishWorkshop(true, true)
                }
            }

            WorkshopRow {
                stationOrder.forEach { id ->
                    WorkshopButton(id.displayName, enabled = available, selected = id == w.selected,
                        modifier = Modifier.weight(1f)) { w.select(id) }
                }
            }
            WorkshopRow {
                SegmentControl {
                    SegmentItem(selected = !fine, onClick = { fine = false }, title = { Text("摆放") })
                    SegmentItem(selected = fine, onClick = { fine = true }, title = { Text("微调") })
                }
                val mounting = when {
                    w.selected == FurnitureId.BOARD -> "悬挂"
                    w.selected in w.draft.tableMounted -> "桌放"
                    else -> "独立支架"
                }
                WorkshopText("${w.selected.displayName} · $mounting", 17, ForgeSwatch.WorkshopSecondary.color, bold = true, maxLines = 1)
            }

            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (fine) NudgePanel(w, available) else ArrangePanel(w, available)
            }

            val status = when {
                holding -> "正在移动${w.held?.displayName.orEmpty()}。松开握键或张开手指后，可撤销或保存。"
                w.scanPending -> "正在查找家具正下方的实桌。"
                w.hint.isNotBlank() -> w.hint
                fine -> "位移方向以工坊为准。"
                else -> "抓住发光把手，移动或转腕，再松开。"
            }
            if (overlap != null && !w.scanPending) {
                WorkshopText("${w.selected.displayName}与${overlap.displayName}重叠，可分开或照常保存。",
                    15, ForgeSwatch.WorkshopBronze.color, maxLines = 1)
            }
            WorkshopText(status, 15, if (holding || w.scanPending) ForgeSwatch.WorkshopBronze.color else ForgeSwatch.WorkshopSecondary.color)
        }
    }
}

@Composable
private fun ArrangePanel(w: WorkshopWorkspace, available: Boolean) {
    val mountable = w.selected != FurnitureId.BOARD
    val tabletop = w.selected in w.draft.tableMounted
    WorkshopRow {
        WorkshopButton("带到面前", available, onClick = w::bring)
        if (mountable) {
            WorkshopButton(if (tabletop) "展开独立支架" else "收架改为桌放", available, onClick = w::toggleStand)
            WorkshopButton(if (w.scanPending) "查找桌面中…" else "贴到实桌", available, onClick = w::scan)
        }
        WorkshopButton("撤销", available && w.canUndo, onClick = w::undo)
    }
    WorkshopText(if (mountable) "将家具移到真实桌面上方，点「贴到实桌」对齐高度。"
        else "拖动把手调整读数板，或在「微调」中移动、升降和转向。", 16, ForgeSwatch.WorkshopSecondary.color)
    WorkshopRow {
        WorkshopText("整组预设", 16, ForgeSwatch.WorkshopSecondary.color)
        WorkshopButton("默认摆法", available, onClick = w::reset)
        WorkshopButton("左手取料", available) { w.arrange(-1) }
        WorkshopButton("镜像 · 右手取料", available) { w.arrange(1) }
    }
}

@Composable
private fun NudgePanel(w: WorkshopWorkspace, available: Boolean) {
    val pose = w.draft[w.selected]
    val readout = String.format(Locale.ROOT, "相对工坊  X %.1f   Y %.1f   Z %.1f cm     朝向 %d°",
        pose.x*100,pose.y*100,pose.z*100, Math.toDegrees(pose.yaw.toDouble()).roundToInt())
    WorkshopRow {
        WorkshopButton(if (w.coarse) "步长 15 cm / 30°" else "步长 2.5 cm / 5°", available, onClick = w::toggleStep)
        WorkshopText(readout, 15, ForgeSwatch.WorkshopSecondary.color)
    }
    WorkshopRow {
        WorkshopText("平移", 16, ForgeSwatch.WorkshopSecondary.color)
        WorkshopButton("向左", available) { w.nudge(x = -1) }
        WorkshopButton("向右", available) { w.nudge(x = 1) }
        WorkshopButton("推远", available) { w.nudge(z = -1) }
        WorkshopButton("拉近", available) { w.nudge(z = 1) }
    }
    WorkshopRow {
        WorkshopText("姿态", 16, ForgeSwatch.WorkshopSecondary.color)
        WorkshopButton("升高", available) { w.nudge(y = 1) }
        WorkshopButton("降低", available) { w.nudge(y = -1) }
        WorkshopButton("左转", available) { w.nudge(turn = 1) }
        WorkshopButton("右转", available) { w.nudge(turn = -1) }
        WorkshopButton("撤销", available && w.canUndo, onClick = w::undo)
    }
}

/** This is attached to the moving ledger, separate from the stationary editing palette. */
@Composable
internal fun WorkshopBoardSample(game:ForgeGame?,editing:Boolean) {
    PicoTheme {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp)).background(ForgeSwatch.WorkshopFace.color)) {
            Image(
                painterResource(R.drawable.ember_cover), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                ForgeSwatch.WorkshopDeep.color.copy(alpha = .98f), ForgeSwatch.WorkshopDeep.color.copy(alpha = .91f),
                ForgeSwatch.WorkshopDeep.color.copy(alpha = .42f)
            ))))
            Column(
                Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                WorkshopText(if(editing)"读数板位置预览" else "工坊账板", 20, ForgeSwatch.WorkshopBronze.color, bold = true)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    WorkshopText(if(game==null)"今日贡盘" else "第 ${game.day} 日  ·  今日贡盘", 23, ForgeSwatch.WorkshopSecondary.color)
                    WorkshopText(game?.let{"${points(it.tribute)} / ${points(it.quota)}"} ?: "待签约",
                        if(game!=null && game.quota>=1000000L)40 else 58, bold = true, maxLines = 1)
                    WorkshopText(game?.commission?.let{"今日委托 · ${it.title}"} ?: "在面前的菜单选择契约，开始营业。", 17, ForgeSwatch.WorkshopSecondary.color)
                }
                if(editing) WorkshopText("移动下方把手，让这行字落在舒服的视线高度。", 19)
            }
        }
    }
}
