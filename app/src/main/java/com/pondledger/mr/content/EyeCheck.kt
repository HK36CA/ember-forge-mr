package com.pondledger.mr.content

import com.pondledger.mr.theme.ForgeSwatch
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pondledger.mr.EyeHealth
import com.pondledger.mr.EyeCheckSequence
import com.pondledger.mr.EyePanelPoint
import com.pondledger.mr.ForgeSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal val LocalEyePanel = compositionLocalOf<LayoutCoordinates?> { null }

@Composable
internal fun EyeDeviceReadout(s: ForgeSession) {
    val report = s.eyeReport
    val live = s.eyeEnabled && report.health == EyeHealth.LIVE
    val status = when {
        !s.wantsEye -> "眼动已关，可用手或手柄指向取料。"
        !s.permitted -> "未获系统眼动权限，点「请求眼动权限」可再次授权。"
        report.health == EyeHealth.NEEDS_PERMISSION -> "系统眼动服务未开放，请在头显设置里检查眼动权限。"
        else -> when (report.health) {
            EyeHealth.LIVE -> "正在接收目光。看稳矿石，等青环合拢，再握住手柄握键或捏合。"
            EyeHealth.UNAVAILABLE -> "头显当前未提供眼动设备。请检查设备支持与系统眼动设置。"
            EyeHealth.NEEDS_FULL_SPACE -> "请返回工坊，等待全空间恢复焦点。"
            EyeHealth.FAILED -> "眼动服务暂未连接，工坊会重试。手柄和裸手仍可用。"
            else -> "眼动已授权，等待新目光数据。请确认佩戴和系统眼动设置。"
        }
    }
    val mint = ForgeSwatch.EyeTracked.color
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(status,color=if(live)mint else ForgeSwatch.EyeWaiting.color,
            style=PicoTheme.typography.bodyMedium.copy(fontSize=18.sp,lineHeight=24.sp))
        if (!s.eyeEnabled) return@Column
        var step by remember { mutableIntStateOf(0) }
        var scope by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val panel=LocalEyePanel.current
        val field=scope
        val point=report.point?.takeIf { live && panel?.isAttached==true && field?.isAttached==true }?.let { p ->
            val inPanel=Offset(p.x*panel!!.size.width,p.y*panel.size.height)
            field!!.localPositionOf(panel,inPanel)
        }
        val slots=EyeCheckSequence.targets
        val size=field?.takeIf{it.isAttached}?.size
        val cursor by rememberUpdatedState(if(point!=null && size!=null && size.width>0 && size.height>0)
            EyePanelPoint(point.x/size.width,point.y/size.height) else null)
        val aspect by rememberUpdatedState(if(size!=null && size.height>0)size.width.toFloat()/size.height else 0f)
        val receiving by rememberUpdatedState(live)
        LaunchedEffect(Unit) {
            val check=EyeCheckSequence()
            while(isActive){
                step=check.update(SystemClock.elapsedRealtime(),receiving,cursor,aspect)
                delay(40)
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            val instruction=if (!live) "目光检查 · 等待信号" else when(step){
                0->"目光检查 · 看左点"
                1->"目光检查 · 看中点"
                2->"目光检查 · 看右点"
                else->"三点已确认"
            }
            Text(instruction,color=if(step==3)mint else ForgeSwatch.EyeInstruction.color,style=PicoTheme.typography.bodyMedium.copy(fontSize=16.sp))
            Canvas(Modifier.width(292.dp).height(36.dp).background(ForgeSwatch.EyeTrack.color,RoundedCornerShape(16.dp))
                .onGloballyPositioned{scope=it}) {
                for((i,x) in slots.withIndex()){
                    val c=Offset(x*this.size.width,this.size.height*.5f)
                    drawCircle(if(i<=step)mint else ForgeSwatch.EyeInactive.color,if(i==step)9.dp.toPx() else 5.dp.toPx(),c,
                        style=Stroke(if(i==step)2.dp.toPx() else 1.dp.toPx()))
                    if(i<step)drawCircle(mint,2.dp.toPx(),c)
                }
                if(point!=null && point.x in 0f..this.size.width && point.y in 0f..this.size.height)
                    drawCircle(Color.White,3.5.dp.toPx(),point)
            }
        }
    }
}
