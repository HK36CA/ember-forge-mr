package com.pondledger.mr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pondledger.mr.theme.ForgeSwatch

private val spectrum = listOf(ForgeSwatch.FrenzyRose, ForgeSwatch.FrenzyAmber, ForgeSwatch.FrenzyLime,
    ForgeSwatch.FrenzyMint, ForgeSwatch.FrenzyIce, ForgeSwatch.FrenzyBlue, ForgeSwatch.FrenzyLilac).map { it.color }

private fun spectralColor(phase: Float): Color {
    val whole = phase.toInt()
    return lerp(spectrum[whole % spectrum.size], spectrum[(whole + 1) % spectrum.size], phase - whole)
}

private fun edge(left:Float,top:Float,right:Float,bottom:Float,cut:Float) = Path().apply {
    moveTo(left+cut,top);lineTo(right,top);lineTo(right,bottom-cut);lineTo(right-cut,bottom)
    lineTo(left,bottom);lineTo(left,top+cut);close()
}

/** Rendered on both the physical anvil attachment and the ledger. Its only clock is remaining game time. */
@Composable
internal fun FrenzySignal(remainingMs:Int, durationMs:Int, compact:Boolean, type:TextStyle, modifier:Modifier=Modifier) {
    val duration=durationMs.coerceAtLeast(1)
    val sample=remainingMs.coerceIn(0,duration)
    var left by remember(sample,duration){mutableIntStateOf(sample)}
    // Interpolate at most one 100 ms game tick. Removing the sign on pause or expiry cancels its frame clock.
    LaunchedEffect(sample,duration) {
        if(sample<=1)return@LaunchedEffect
        val maxAdvance=minOf(100,sample-1)
        val start=withFrameNanos { it }
        do {
            val advance=withFrameNanos { now -> ((now-start)/1_000_000L).coerceIn(0L,maxAdvance.toLong()).toInt() }
            left=sample-advance
        } while (advance<maxAdvance)
    }
    val elapsed=duration-left
    val progress=left.toFloat()/duration
    val launch=(1f-elapsed/340f).coerceIn(0f,1f)
    val urgent=left<=2000
    val pulse=FrenzyRhythm.pulse(left)
    val title=remember(elapsed) {
        buildAnnotatedString {
            "狂热入神".forEachIndexed { i,glyph ->
                // Different glyphs occupy different parts of the spectrum even on the first frame.
                withStyle(SpanStyle(color=spectralColor(elapsed/270f + i*1.82f))) { append(glyph) }
            }
        }
    }
    val tenths=(left+99)/100
    val countdown="${tenths/10}.${tenths%10}"
    val paper=ForgeSwatch.FrenzyPaper.color
    val urgentInk=ForgeSwatch.FrenzyDeadline.color
    val rail=ForgeSwatch.FrenzyRail.color
    val bed=ForgeSwatch.FrenzyInk.color
    // These are physical sign letters. The compact attachment has a fixed size in the scene.
    val density=LocalDensity.current
    val titleSize=with(density){(if(compact)27 else 30).dp.toSp()}
    val timeSize=with(density){(if(compact)25 else 32).dp.toSp()}
    val fine=with(density){(if(compact)13 else 15).dp.toSp()}
    val titleLine=with(density){(if(compact)33 else 37).dp.toSp()}
    val timeLine=with(density){(if(compact)33 else 38).dp.toSp()}
    val fineLine=with(density){18.dp.toSp()}
    val titleShadow=Shadow(Color.Black,Offset(0f,with(density){1.4.dp.toPx()}),with(density){2.dp.toPx()})
    Box(modifier.clearAndSetSemantics {
        contentDescription="狂热入神，免损伤和损毁"
        stateDescription="剩余 ${(sample+999)/1000} 秒"
    }.drawBehind {
        val cut=7.dp.toPx()
        val pad=2.dp.toPx()
        val outline=edge(pad,pad,size.width-pad,size.height-pad,cut)
        drawPath(outline,bed)
        drawPath(outline,Brush.horizontalGradient(listOf(spectrum[0],spectrum[6],spectrum[4])),
            style=Stroke((1.25f+launch*1.25f).dp.toPx()))
        // The inset ten-part track and its moving tip carry timing without a background flash.
        val start=12.dp.toPx()
        val end=size.width-start
        val gap=2.dp.toPx()
        val slant=1.4.dp.toPx()
        val top=size.height-(if(compact)9 else 10).dp.toPx()
        val bottom=size.height-5.dp.toPx()
        val segment=(end-start-gap*9f)/10f
        fun tick(x:Float,amount:Float,color:Color) {
            if(amount<=0f)return
            val inset=minOf(slant,amount*.45f)
            val shape=Path().apply {
                moveTo(x+inset,top);lineTo(x+amount,top);lineTo(x+amount-inset,bottom);lineTo(x,bottom);close()
            }
            drawPath(shape,color)
        }
        repeat(10){i->
            val x=start+i*(segment+gap)
            tick(x,segment,rail)
            val active=(progress*10f-i).coerceIn(0f,1f)
            tick(x,active*segment,if(urgent)urgentInk else spectralColor(i*.64f+.12f))
        }
    }) {
        Row(Modifier.fillMaxSize().padding(start=(if(compact)13 else 17).dp,end=(if(compact)12 else 16).dp,
            bottom=(if(compact)7 else 9).dp),verticalAlignment=Alignment.CenterVertically) {
            BasicText(title,modifier=Modifier.graphicsLayer {
                scaleX=1f+.055f*launch;scaleY=scaleX
            },style=type.copy(fontSize=titleSize,lineHeight=titleLine,fontWeight=FontWeight.Black,shadow=titleShadow),
                maxLines=1,softWrap=false)
            if(!compact){
                Spacer(Modifier.width(17.dp))
                Column(verticalArrangement=Arrangement.Center) {
                    BasicText(if(urgent)"即将结束" else "保护已开启",
                        style=type.copy(color=if(urgent)urgentInk else paper,fontSize=fine,lineHeight=fineLine,fontWeight=FontWeight.Bold),
                        maxLines=1,softWrap=false)
                    BasicText("免损伤 · 免损毁",style=type.copy(color=ForgeSwatch.LedgerCoolAsh.color,
                        fontSize=fine,lineHeight=fineLine),maxLines=1,softWrap=false)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment=Alignment.CenterVertically) {
                BasicText(countdown,modifier=Modifier.graphicsLayer{
                    scaleX=1f+.08f*pulse;scaleY=scaleX
                },style=type.copy(color=if(urgent)urgentInk else paper,fontFamily=FontFamily.Monospace,
                    fontWeight=FontWeight.Bold,fontSize=timeSize,lineHeight=timeLine),maxLines=1,softWrap=false)
                Spacer(Modifier.width(4.dp))
                BasicText("秒",style=type.copy(color=paper,fontSize=fine,lineHeight=fineLine,fontWeight=FontWeight.Bold),maxLines=1)
            }
        }
    }
}
