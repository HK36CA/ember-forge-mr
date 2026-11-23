package com.pondledger.mr

import android.util.Log
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Transform
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val iron=0x384856
private val steel=0x8497A3
private val brass=0xC79452
private val shadow=0x26252E
private val ember=0xFF9A36
private val blue=0x50D7C9
private val frenzySteel=run {
    val keys=intArrayOf(0xF7A79A,0xF9C184,0xE8DC8E,0x95DEC1,0x8BD2E5,0xAAAFF1,0xEBA3D9)
    IntArray(keys.size*4){i->
        val a=keys[i/4];val b=keys[(i/4+1)%keys.size]
        fun mix(shift:Int):Int {
            val start=(a ushr shift) and 255
            val end=(b ushr shift) and 255
            return (start+(end-start)*(i%4)/4) shl shift
        }
        mix(16) or mix(8) or mix(0)
    }
}

internal class ForgeModels(val root:Entity):AutoCloseable {
    private val meshes=mutableMapOf<String,MeshResource>()
    private val materials=mutableMapOf<Pair<Int,Boolean>,PhysicallyBasedMaterial>()
    private val surfaceLibrary=ForgeFinishLibrary()
    private var closed=false
    private var hammer:ForgeHammer?=null
    private var hammerEcho:ForgeHammer?=null
    var hammerUnavailable=false
        private set
    var echoUnavailable=false
        private set
    private val rings=mutableListOf<Entity>()
    private val sparks=mutableListOf<Entity>()
    private val ores=mutableListOf<Entity>()
    private lateinit var oreFrame:Entity
    private lateinit var trayRoot:Entity
    private lateinit var boardRoot:Entity
    private val owners=mutableMapOf<FurnitureId,Entity>()
    private val stands=mutableMapOf<FurnitureId,Entity>()
    private lateinit var boardTrim:Entity
    private val handles=mutableMapOf<FurnitureId,Entity>()
    private val indicators=mutableMapOf<FurnitureId,LayoutIndicator>()
    private var frames:WorkshopFrames?=null
    private var panelDimensions=0f to 0f
    private var lastHeldWorld:Point?=null
    private var lastGrabbed:FurnitureId?=null
    private var landing:FurnitureId?=null
    private var landedAt=0L
    private class LayoutIndicator(val root:Entity,val edges:List<Entity>,val handles:List<Entity>) {
        var mode=-1
    }
    private var oldOreMode:Boolean?=null
    private var held=-1
    private lateinit var blade:Entity
    var bladeLift:Float=0f
        private set
    private lateinit var spotMarker:Entity
    private lateinit var amberBullseye:Entity
    private lateinit var tealBullseye:Entity
    private var visibleSpot:BladeSpot?=null
    private var lockedColor=false
    private var eyeRing:Entity?=null
    private val eyeSegments=mutableListOf<Entity>()
    private var eyeProgress=-1
    private var ringUnavailable=false
    private lateinit var bladeFace:Entity
    private var bladeSteel:PhysicallyBasedMaterial?=null
    private var lastSerial:Int?=null
    private var strikeAt=0L
    private var glowing=-1
    private var glowOre=-1
    private var hueStep=-1
    private val fireClock=ForgeFireClock()
    private var fireRoot:Entity?=null
    private val fireBatches=mutableMapOf<FireLayer,Entity>()
    private val fireShown=BooleanArray(FireLayer.entries.size)
    private var fireActive=false
    private var lastFireFrame=-1L
    private var sparkOrigin=Point(0f,.118f,0f)
    private var precisionSparks=false
    private var selected=-1
    private var lastAnim=-1L
    private var settled=true
    private lateinit var dragonHead:Entity
    private val dragonRunes=mutableListOf<Entity>()
    private val litRunes=mutableListOf<Entity>()
    private var wasDragon=false
    private var lastRune=-1
    private var dragonAt=0L

    fun build() {
        root.setName("EmberForgeTabletop")
        buildBench()
        meshFurniture(root,"ApronFittings",ForgeFurniture.benchTrim())
        val defaultLayout=WorkshopLayout()
        owners[FurnitureId.BENCH]=root
        for((kind,name) in listOf(FurnitureId.TRAY to "OreCaddy",FurnitureId.BOARD to "LedgerMount",
            FurnitureId.TRIBUTE to "TributeDesk",FurnitureId.RELIC to "RelicKiln")){
            val p=defaultLayout[kind]
            owners[kind]=group(root,name,p.x,p.y,p.z).apply{
                angle(0f,Math.toDegrees(p.yaw.toDouble()).toFloat(),0f)
            }
        }
        trayRoot=owners.getValue(FurnitureId.TRAY)
        boardRoot=owners.getValue(FurnitureId.BOARD)
        meshFurniture(trayRoot,"OreCaddyJoinery",ForgeFurniture.tray())
        for(id in listOf(FurnitureId.TRIBUTE,FurnitureId.RELIC)){
            meshFurniture(owners.getValue(id),"Counter_${id.name}",ForgeFurniture.sideTable(id))
        }
        boardTrim=group(boardRoot,"LedgerBezel",0f,0f,0f)
        meshFurniture(boardTrim,"Bezel",ForgeFurniture.boardTrim())
        for ((kind,owner) in owners) {
            furnitureFootprint(kind)?.let{pad->
                val legs=group(owner,"Stand_${kind.name}",0f,pad.bottom,0f)
                legs.resize(1f,.72f,1f)
                meshFurniture(legs,"Legs_${kind.name}",ForgeFurniture.stand(kind))
                stands[kind]=legs
            }
            val p=furnitureHandle(kind,BOARD_TRIM_HEIGHT)
            val grip=group(owner,"LayoutHandle_${kind.name}",p.x,p.y,p.z)
            handles[kind]=grip
            meshFurniture(grip,"Bracket_${kind.name}",ForgeFurniture.handle(kind))
            val outlineParent=if(kind==FurnitureId.BOARD)boardTrim else owner
            val marker=group(outlineParent,"PlacementCorners_${kind.name}",0f,0f,0f)
            val edges=meshFurniture(marker,"Corner_${kind.name}",ForgeFurniture.outline(kind),true)
            // A thin collar on either end responds even when looking straight at the handle.
            val lights=listOf(-1f,1f).map { side->
                val x=side*if(kind==FurnitureId.TRAY).031f else .054f
                cylinder(grip,"GripMarker",x,0f,0f,.005f,if(kind==FurnitureId.TRAY).009f else .010f,blue)
                    .apply { angle(0f,0f,90f);enabled=false }
            }
            marker.enabled=false
            indicators[kind]=LayoutIndicator(marker,edges,lights)
        }
        val stations=stationLocalTargets.mapIndexed { i,p -> group(owners.getValue(stationFurniture[i]),listOf("Anvil","TributePan","RelicFurnace")[i],p.x,0f,p.z) }
        anvil(stations[0])
        buildFrenzy(stations[0])
        spotMarker=group(root,"StrikeTarget",0f,0f,0f)
        fun bullseye(name:String,tint:Int):Entity=group(spotMarker,name,0f,0f,0f).also{e->
            torus(e,"StrikeCircle",0f,0f,0f,BladeSpot.RADIUS,.00135f,tint,true)
            torus(e,"SightRing",0f,.0001f,0f,.006f,.00085f,tint,true)
            val mesh=geometry("StrikeDot"){MeshResource.createCylinder(.0012f,.002f)}
            node(e,"Center",mesh,tint,true)
        }
        amberBullseye=bullseye("GoldTarget",0xFFCF5D)
        tealBullseye=bullseye("LockedTarget",0x41FFE1)
        tealBullseye.enabled=false
        spotMarker.enabled=false
        tribute(group(stations[1],"WeighingScale",0f,0f,0f).apply{resize(1.76f,1.82f,1.76f)})
        furnace(group(stations[2],"CastingVessel",0f,0f,0f).apply{resize(2.02f,2.18f,2.02f)})
        for((i,station) in stations.withIndex()) {
            val color=listOf(ember,0xF9D777,blue)[i]
            val ring=torus(station,"SelectionHalo",0f,.013f,0f,listOf(.230f,.171f,.202f)[i],.004f,color,true)
            ring.enabled=false
            rings+=ring
        }
        buildDragon()
        buildOres()
        repeat(10) { i ->
            val spark=box(stations[0],"Spark_$i",0f,.13f,0f,.004f,.013f,.004f,if(i%2==0)0xFFE7A0 else ember,true)
            spark.enabled=false
            sparks+=spark
        }
    }
    private fun buildBench() {
        meshFurniture(root,"BenchJoinery",ForgeFurniture.benchTop())
        meshFurniture(root,"ForgingTongs",ForgeFurniture.benchTongs())
        surface(ForgeFinish.IRON,box(root,"AnvilBedding",anvilTarget.x,.002f,anvilTarget.z,.58f,.004f,.31f,0x48454A))
        for(x in listOf(-.433f,.433f))for(z in listOf(-.226f,.226f)){
            val head=cylinder(root,"Rivet",x,.003f,z,.006f,.008f,brass)
            surface(ForgeFinish.BRASS,head)
        }
        surface(ForgeFinish.BRASS,cylinder(root,"TongPin",.221f,.018f,-.180f,.013f,.009f,brass))
    }

    private fun anvil(station:Entity) {
        val body=group(station,"AnvilCasting",0f,0f,0f).apply{resize(2.35f,1.85f,2.0f)}
        meshFurniture(body,"ForgedAnvil",ForgeAnvilGeometry.casting(),finishes=ForgeAnvilGeometry.finishes)
        val base=BladeSpot.base
        blade=group(station,"SwordBlank",base.x,base.y,base.z)
        blade.angle(0f,BladeSpot.YAW_DEGREES,0f)
        // Two bevels converge at a raised spine. The whole silhouette remains readable from above.
        val profile=BladeSpot.profile
        val tip=profile.minOf{it.x}
        val length=profile.maxOf{it.x}-tip
        val bladeUvs=profile.map{(it.x-tip)/length to .5f-it.z/length}
        val swordMesh=shape("TemperedBlade",profile,BladeSpot.meshIndices,bladeUvs)
        bladeFace=node(blade,"TemperedBlade",swordMesh,ember,true)
        var acquired:PhysicallyBasedMaterial?=null
        try {
            val steel=PhysicallyBasedMaterial.create()
            acquired=steel
            steel.setBaseColor(Color4(.90f,.91f,.92f,1f))
            steel.setMetallic(ForgeFinish.STEEL.metallic)
            steel.setRoughness(ForgeFinish.STEEL.roughness)
            steel.toGlobal()
            surfaceLibrary.bindAlbedo(ForgeFinish.STEEL,steel)
            bladeFace.components[ModelComponent::class.java]!!.materials[0]=steel
            bladeSteel=steel
            acquired=null
        }catch(e:Exception){Log.w("EmberForge","Using plain blade finish",e)}
        finally{acquired?.let{runCatching{if(it.valid)it.close()}}}
        val fittings=group(blade,"SwordFittings",0f,0f,0f).apply{resize(BladeSpot.scale.x,BladeSpot.scale.y,BladeSpot.scale.z)}
        meshFurniture(fittings,"SwordHilt",ForgeAnvilGeometry.fittings(),finishes=ForgeAnvilGeometry.finishes)
        box(fittings,"BladeRune",-.006f,.0069f,0f,.038f,.001f,.002f,0xFFEA99,true)
    }
    private fun tribute(station:Entity) {
        val counter=owners.getValue(FurnitureId.TRIBUTE)
        meshFurniture(counter,"TitheWindow",ForgeStationLandmarks.tributeWindow(),finishes=ForgeStationLandmarks.finishes)
        meshFurniture(counter,"WyrmEye",ForgeStationLandmarks.tributeEye(),lit=true)
        meshFurniture(station,"TributeBalance",ForgeStationVessels.balance(),finishes=ForgeStationVessels.finishes)
    }

    private fun furnace(station:Entity) {
        meshFurniture(owners.getValue(FurnitureId.RELIC),"RelicCabinet",ForgeStationLandmarks.relicCabinet(),
            finishes=ForgeStationLandmarks.finishes)
        meshFurniture(station,"RelicCasting",ForgeStationVessels.kiln(),finishes=ForgeStationVessels.finishes)
        meshFurniture(station,"KilnCore",ForgeStationVessels.kilnLight(),lit=true)
    }

    private fun buildDragon() {
        dragonHead=group(root,"AshWyrm",0f,.10f,-.10f)
        fun ball(parent:Entity,name:String,x:Float,y:Float,z:Float,w:Float,h:Float,d:Float,color:Int,lit:Boolean=false):Entity {
            val mesh=geometry("sphere"){MeshResource.createSphere(.5f)}
            return node(parent,name,mesh,color,lit).also{
                it.move(x,y,z)
                it.components[TransformComponent::class.java]!!.scaleVector=Vector3(w,h,d)
            }
        }
        val violet=0x554961
        val hide=0x73647F
        ball(dragonHead,"Neck",0f,.020f,-.020f,.098f,.130f,.098f,violet)
        ball(dragonHead,"Brow",0f,.075f,0f,.145f,.091f,.105f,hide)
        ball(dragonHead,"Muzzle",0f,.054f,.056f,.095f,.042f,.078f,violet)
        for(side in listOf(-1,1)){
            val horn=geometry("wyrmHorn"){MeshResource.createCone(.066f,.013f)}
            node(dragonHead,"CrownHorn",horn,brass).apply{
                move(side*.039f,.133f,-.014f);angle(-19f,0f,-side*22f)
            }
            ball(dragonHead,"GoldenEye",side*.047f,.083f,.038f,.026f,.020f,.016f,0xFFCE71,true)
            ball(dragonHead,"Pupil",side*.046f,.084f,.047f,.005f,.014f,.004f,shadow)
            ball(dragonHead,"Nostril",side*.022f,.065f,.087f,.009f,.004f,.004f,shadow)
            box(dragonHead,"CheekFin",side*.069f,.065f,-.009f,.031f,.042f,.012f,violet).angle(-15f,side*32f,-side*32f)
        }
        dragonHead.enabled=false
        for((i,t) in runeLocalTargets.withIndex()){
            val sign=group(owners.getValue(stationFurniture[i]),"Rune$i",t.x,t.y,t.z).apply{resize(1.45f,1.45f,1.45f)}
            val face=box(sign,"ObsidianToken",0f,0f,0f,.060f,.060f,.009f,iron)
            face.angle(0f,0f,45f)
            val mark=group(sign,"RuneGlow",0f,0f,.007f)
            when(i){
                0->{
                    box(mark,"Upper",-.007f,.002f,0f,.005f,.034f,.002f,0xFFB65A,true).angle(0f,0f,-25f)
                    box(mark,"Lower",.007f,.002f,0f,.005f,.034f,.002f,0xFFB65A,true).angle(0f,0f,25f)
                }
                1->{
                    for(z in listOf(-.006f,.006f))box(mark,"Bar",0f,z,0f,.028f,.005f,.003f,0xFFE181,true)
                }
                2->{
                    box(mark,"Post",0f,0f,0f,.005f,.030f,.003f,blue,true)
                    box(mark,"Crossbar",0f,.006f,0f,.025f,.004f,.003f,blue,true)
                }
            }
            val halo=box(sign,"ChosenRune",0f,0f,-.008f,.073f,.073f,.007f,0xFFDE8B,true)
            halo.angle(0f,0f,45f)
            sign.enabled=false
            dragonRunes+=sign;litRunes+=halo
        }
    }

    private fun updateDragon(state:ForgeSceneState,now:Long){
        if(wasDragon!=state.dragon || lastRune!=state.rune) {
            if(state.dragon) dragonAt=now
            wasDragon=state.dragon
            lastRune=state.rune
            dragonHead.enabled=state.dragon
            dragonRunes.forEachIndexed{i,r->
                r.enabled=state.dragon
                litRunes[i].enabled=i==state.rune
            }
        }
        if(state.dragon && now-dragonAt<=600) {
            val t=((now-dragonAt)/450f).coerceIn(0f,1f)
            val ease=1-(1-t)*(1-t)
            dragonHead.move(0f,.083f+.055f*ease,-.103f)
            dragonHead.angle(18f*(1-ease),(state.rune.coerceIn(0,2)-1)*24f*ease,0f)
        }
    }
    private fun buildOres() {
        val names=listOf("Magnetite","StarSilver","DragonCrystal")
        val colors=listOf(0x526575,0xB8D5EC,0xA0E88E)
        for((i,p) in trayOreTargets.withIndex()) {
            torus(trayRoot,"SampleRecess",p.x,.020f,p.z,.051f,.0022f,brass)
            val ore=group(trayRoot,names[i],p.x,p.y,p.z).apply{resize(1.45f,1.45f,1.45f)}
            val mineral=ForgeOreGeometry.core(i)
            val mesh=shape("OreCrystal$i",mineral.points,mineral.triangles,mineral.uvs)
            val finish=listOf(ForgeFinish.MAGNETITE,ForgeFinish.STARSILVER,ForgeFinish.DRAGONCRYSTAL)[i]
            surface(finish,node(ore,"Core",mesh,colors[i],i==2))
            for(k in 0..if(i==1)0 else 1){
                val shard=node(ore,"Inclusion",mesh,colors[i],i==2)
                surface(finish,shard)
                if(i==1){
                    shard.move(.014f,.005f,.005f)
                    shard.resize(.48f,.44f,.65f)
                    shard.angle(15f,40f,-48f)
                }else{
                    val side=if(k==0)-1f else 1f
                    shard.move(side*.013f,-.008f,if(k==0).009f else -.010f)
                    shard.resize(if(i==2).66f else .54f,if(i==2).75f else .54f,.62f)
                    shard.angle(side*18f,35f+55f*k,-side*31f)
                }
            }
            ores+=ore
        }
        oreFrame=group(trayRoot,"OreFocusCage",0f,0f,0f)
        val tint=0xFFDC8C
        val r=.066f
        // A physical cuboid outline keeps the chosen ore legible from side views too.
        for(a in listOf(-r,r))for(b in listOf(-r,r)) {
            box(oreFrame,"XEdge",0f,a,b,2*r,.0022f,.0022f,tint,true)
            box(oreFrame,"YEdge",a,0f,b,.0022f,2*r,.0022f,tint,true)
            box(oreFrame,"ZEdge",a,b,0f,.0022f,.0022f,2*r,tint,true)
        }
        oreFrame.enabled=false
    }
    fun heldPosition(position:Point?) {
        val layout=frames ?:return
        if(held !in ores.indices || position==null)return
        val world=layout.bench.world(position)
        lastHeldWorld=world
        val local=layout.tray.localPoint(world)
        ores[held].move(local.x,local.y,local.z)
    }
    fun showHammer(held:HammerPose?,echo:HammerPose?) {
        if(!hammerUnavailable)try{
            if(held!=null && hammer==null)hammer=ForgeHammer(root,bindFinish=::surface)
            hammer?.show(held)
        }catch(e:Exception){
            hammerUnavailable=true
            runCatching{hammer?.close()}
            Log.e("EmberForge","Hammer could not be displayed. Forging is suspended.",e)
        }
        if(!echoUnavailable)try{
            if(echo!=null && hammerEcho==null)hammerEcho=ForgeHammer(root,echo=true)
            hammerEcho?.show(echo)
        }catch(e:Exception){
            echoUnavailable=true
            runCatching{hammerEcho?.close()}
            Log.w("EmberForge","Assisted swing echo could not be displayed. Seated assistance is suspended.",e)
        }
    }
    fun showSpot(spot:BladeSpot?,locked:Boolean){
        if(spot!=visibleSpot){
            if((visibleSpot==null)!=(spot==null))spotMarker.enabled=spot!=null
            if(spot!=null)spotMarker.move(spot.center.x,spot.center.y,spot.center.z)
            visibleSpot=spot
        }
        if(locked!=lockedColor){
            amberBullseye.enabled=!locked;tealBullseye.enabled=locked;lockedColor=locked
        }
    }

    /** The segmented cyan sight is exclusive to native eye data. Its plane faces the observer. */
    fun showGaze(mark:GazeMark?) {
        if(ringUnavailable)return
        if(mark==null){
            if(eyeProgress>=0)eyeRing?.enabled=false
            eyeProgress=-1
            return
        }
        try {
            val mesh=geometry("NativeGazeArc"){
                val vertices=ArrayList<Vector3>()
                val indices=ArrayList<Int>()
                for(i in 0..6){
                    val angle=(4f + i*(37f/6f))*(PI/180.0)
                    for(radius in listOf(.017f,.021f))
                        vertices+=Vector3((sin(angle)*radius).toFloat(),(cos(angle)*radius).toFloat(),0f)
                    if(i>0){val n=2*i;indices+=listOf(n-2,n-1,n,n,n-1,n+1,n,n-1,n-2,n+1,n-1,n)}
                }
                MeshResource.createWithMeshModel(model=MeshModel(positions=vertices,triangleIndices=indices,
                    normals=vertices.map{Vector3(0f,0f,1f)}),name="GazeDwellArc")
            }
            val ring=eyeRing ?: group(root,"NativeEyeSight",0f,0f,0f).also{r->
                eyeRing=r
                for(i in 0..7)eyeSegments+=node(r,"DwellSegment$i",mesh,0x3F8790,true).apply{angle(0f,0f,-45f*i)}
                node(r,"GazeCenter",geometry("GazeCenter"){MeshResource.createSphere(.0021f)},0xC0FFF8,true)
            }
            val z=mark.facing
            val x=Point(0f,1f,0f).cross(z).unit() ?:Point(1f,0f,0f)
            val q=HammerRotation.basis(x,z.cross(x),z) ?:return
            val scale=GAZE_RADIUS_MULTIPLIER * (if(mark.small) .68f else 1f)
            ring.components[TransformComponent::class.java]?.apply{
                position=mark.point.vector();quaternion=Quat(q.x,q.y,q.z,q.w);scaleVector=Vector3(scale,scale,scale)
            }
            if(eyeProgress!=mark.steps) {
                for((i,part) in eyeSegments.withIndex()) {
                    if(eyeProgress<0 || (i<eyeProgress)!=(i<mark.steps)) {
                        val tint=if(i<mark.steps)0x75FFF0 else 0x3F8790
                        part.components.set(ModelComponent(mesh,material(tint,true)))
                    }
                }
                if(eyeProgress<0)ring.enabled=true
                eyeProgress=mark.steps
            }
        }catch(e:Exception){
            ringUnavailable=true
            runCatching{eyeRing?.enabled=false}
            Log.w("EmberForge","Eye sight could not be rendered",e)
        }
    }

    fun pose(placement:BenchPlacement) {
        val half=placement.yaw*.5f
        val world=Transform(placement.center.vector(),Quat(0f,sin(half),0f,cos(half)),Vector3.ONE)
        root.components.set(TransformComponent(root.getParent()?.convertTransformFrom(world,null) ?: world))
    }

    /** The SDK parent is shared, but tray and board remain fixed in their own room frames. */
    fun pose(layout:WorkshopFrames,panelWidth:Float,panelHeight:Float) {
        val previous=frames
        val changed=previous==null || previous.bench.center!=layout.bench.center || previous.bench.yaw!=layout.bench.yaw
        if (changed)pose(layout.bench)
        fun applyRelative(entity:Entity,space:BenchPlacement) {
            val center=layout.bench.localPoint(space.center)
            val half=(space.yaw-layout.bench.yaw)*.5f
            val transform=entity.components[TransformComponent::class.java] ?:return
            transform.position=center.vector()
            transform.quaternion=Quat(0f,sin(half),0f,cos(half))
        }
        for((id,owner) in owners){
            val f=layout[id]
            val updated=changed || previous[id].center!=f.center || previous[id].yaw!=f.yaw
            if(updated && id!=FurnitureId.BENCH)applyRelative(owner,f)
            if(id==FurnitureId.TRAY && updated)lastHeldWorld?.takeIf{held in ores.indices}?.let{world->
                val p=f.localPoint(world)
                ores[held].move(p.x,p.y,p.z)
            }
            val legs=stands[id] ?:continue
            val table=id in layout.layout.tableMounted
            if(updated || previous?.layout?.tableMounted!=layout.layout.tableMounted){
                legs.enabled=!table
                val bottom=furnitureFootprint(id)!!.bottom
                val floorHeight=f.center.y+bottom
                // Floor-origin tracking supplies absolute height. Eye-origin sessions use one shared floor estimate.
                val span=if(layout.room.center.y in .30f..1.60f)floorHeight else .77f+f.center.y-layout.room.center.y+bottom
                legs.resize(1f,span.coerceIn(.045f,3.50f),1f)
            }
        }
        frames=layout
        if(panelWidth>0f && panelHeight>0f && (panelDimensions.first!=panelWidth || panelDimensions.second!=panelHeight)) {
            boardTrim.components[TransformComponent::class.java]?.scaleVector=
                Vector3(panelWidth/BOARD_TRIM_WIDTH,panelHeight/BOARD_TRIM_HEIGHT,1f)
            val p=furnitureHandle(FurnitureId.BOARD,panelHeight)
            handles[FurnitureId.BOARD]?.move(p.x,p.y,p.z)
            panelDimensions=panelWidth to panelHeight
        }
    }

    fun showFurnitureEdit(editing:Boolean,hovered:FurnitureId?,grabbed:FurnitureId?,now:Long) {
        if(lastGrabbed!=null && grabbed!=lastGrabbed && editing){landing=lastGrabbed;landedAt=now}
        lastGrabbed=grabbed
        if(!editing || now-landedAt>=380L)landing=null
        for((kind,indicator) in indicators) {
            val mode=when {
                !editing -> 0
                grabbed==kind -> 3
                landing==kind -> 4
                hovered==kind -> 2
                else -> 1
            }
            if(mode==indicator.mode)continue
            indicator.mode=mode
            val color=when(mode){2->0x94FFE2;3->0xFFBF6A;4->0xE7FFE1;else->0x52938E}
            indicator.root.enabled=mode>0
            indicator.handles.forEach{it.enabled=mode>0}
            if(mode>0) for(e in indicator.edges+indicator.handles) {
                val model=e.components[ModelComponent::class.java]
                if(model!=null && model.materials.size>0)model.materials[0]=material(color,true)
            }
            val size=when(mode){2->1.018f;3->1.04f;4->1.022f;else->1f}
            indicator.root.components[TransformComponent::class.java]?.scaleVector=Vector3(size,size,size)
        }
    }
    fun impactAt(benchLocal:Point,precise:Boolean) {
        val anvil=anvilTarget
        sparkOrigin=Point(benchLocal.x-anvil.x,benchLocal.y+.003f,benchLocal.z-anvil.z)
        if(precise!=precisionSparks){
            precisionSparks=precise
            sparks.forEachIndexed{i,spark->
                val hue=if(precise) { if(i%2==0)0xD3FFF4 else 0x71F5DB }
                    else if(i%2==0)0xFFE7A0 else ember
                spark.components[ModelComponent::class.java]?.let{m->if(m.materials.size>0)m.materials[0]=material(hue,true)}
            }
        }
    }
    private fun buildFrenzy(station:Entity){
        val host=group(station,"FrenzyAtAnvil",0f,0f,0f)
        host.enabled=false
        try{
            for(layer in FireLayer.entries){
                val part=meshFurniture(host,"Frenzy_${layer.name}",forgeFireGeometry(layer),lit=true).single()
                part.enabled=false
                fireBatches[layer]=part
            }
            fireRoot=host
        }catch(e:Exception){
            fireBatches.clear()
            if(host.valid)host.destroy()
            Log.w("EmberForge","Anvil fire could not be displayed",e)
        }
    }
    fun suspendFrenzy(){
        fireClock.suspend()
        hideFrenzy()
    }
    private fun hideFrenzy(){
        if(fireActive)fireRoot?.takeIf{it.valid}?.enabled=false
        fireActive=false
        lastFireFrame=-1L
    }
    private fun showFrenzy(frame:ForgeFireFrame?,now:Long){
        if(frame==null){hideFrenzy();return}
        val host=fireRoot?.takeIf{it.valid} ?:return
        if(fireActive && now-lastFireFrame in 0L..21L)return
        lastFireFrame=now
        for((layer,part) in fireBatches){
            val pose=frame.pose(layer)
            val visible=pose!=null
            if(fireShown[layer.ordinal]!=visible){
                part.enabled=visible
                fireShown[layer.ordinal]=visible
            }
            if(pose!=null){
                val transform=part.components[TransformComponent::class.java] ?:continue
                transform.position=Vector3(0f,pose.y,0f)
                transform.scaleVector=Vector3(pose.radius,pose.height,pose.radius)
                transform.eulerAngles=EulerAngles(0f,pose.turn,0f)
            }
        }
        if(!fireActive){host.enabled=true;fireActive=true}
    }
    fun frame(state:ForgeSceneState,now:Long,sceneVisible:Boolean=true) {
        val forging=sceneVisible && state.active && !state.materialMode && !state.dragon
        val fire=fireClock.frame(forging,state.frenzyLeft,state.frenzySpan,state.strikeSerial,now)
        showFrenzy(fire,now)
        updateDragon(state,now)
        if(oldOreMode!=state.materialMode || held!=state.heldMaterial || selected!=state.selected){
            oldOreMode=state.materialMode
            val previousHeld=held
            held=state.heldMaterial
            if(previousHeld!=held)lastHeldWorld=null
            ores.forEachIndexed{i,e->
                val p=trayOreTargets[i]
                if(i!=held) e.move(p.x,p.y,p.z)
                // Panel pickup has no physical hand pose. Lift the sample over its own compartment
                // until a live hand pose is available; never assign it a guessed world position.
                else if(lastHeldWorld==null) e.move(p.x,p.y+.052f,p.z)
            }
            blade.enabled=!state.materialMode
            oreFrame.enabled=state.materialMode && held<0 && state.selected in ores.indices
            trayOreTargets.getOrNull(state.selected)?.let{oreFrame.move(it.x,it.y,it.z)}
            rings.forEachIndexed{i,e->e.enabled=!state.materialMode && i==state.selected}
        }
        if(selected!=state.selected) {
            selected=state.selected
            rings.forEachIndexed{i,e->e.enabled=!state.materialMode && i==selected}
        }
        val nextGlow=(state.heat.coerceIn(0f,1f)*4).toInt()
        val heatChanged=glowing!=nextGlow
        val nextHue=if(fire!=null)(fire.elapsed/100)%frenzySteel.size else -1
        if(heatChanged || glowOre!=state.forgedOre || hueStep!=nextHue){
            glowing=nextGlow;glowOre=state.forgedOre;hueStep=nextHue
            val hue=if(hueStep>=0)frenzySteel[hueStep] else when(glowOre) {
                Ore.SILVER.ordinal -> listOf(0x849FB1,0xAEC1CE,0xCDD2C6,0xEAD4AE,0xFFF1C4)
                Ore.CRYSTAL.ordinal -> listOf(0xA74F5F,0xCA5864,0xE97051,0xFB9C61,0xFFD59B)
                else -> listOf(0xD47548,0xF28B39,0xFFB650,0xFFD169,0xFFEDAA)
            }[glowing]
            val steel=bladeSteel
            if(steel!=null){
                val hot=glowing*.25f
                val tint=if(hueStep>=0).80f else .15f+.55f*hot
                val emission=if(hueStep>=0).24f else .009f+.16f*hot*hot
                val r=linearChannel(hue,16)
                val g=linearChannel(hue,8)
                val b=linearChannel(hue,0)
                steel.setBaseColor(Color4(1f+(r-1f)*tint,1f+(g-1f)*tint,1f+(b-1f)*tint,1f))
                steel.setEmissiveColor(Color4(r*emission,g*emission,b*emission,1f))
                if(heatChanged){
                    steel.setMetallic(.80f-.20f*hot)
                    steel.setRoughness(.32f+.10f*hot)
                }
            }else{
                val model=bladeFace.components[ModelComponent::class.java]
                if(model!=null && model.materials.size>0)model.materials[0]=material(hue,true)
            }
        }
        if(lastSerial!=state.strikeSerial){
            if(lastSerial!=null){ strikeAt=now;settled=false }
            lastSerial=state.strikeSerial
        }
        if(settled || now-lastAnim<16L)return
        lastAnim=now
        val t=((now-strikeAt)/470f).coerceIn(0f,1f)
        bladeLift=sin(t*PI).toFloat()*(1-t)*.034f
        blade.move(BladeSpot.base.x,BladeSpot.base.y+bladeLift,BladeSpot.base.z)
        sparks.forEachIndexed {i,spark->
            spark.enabled=t<.88f
            if(t<.88f) {
                val angle=i*2.39996f
                val r=t*(.07f+(i%4)*.018f)
                spark.move(sparkOrigin.x+cos(angle)*r,sparkOrigin.y+sin(t*PI).toFloat()*(.055f+(i%3)*.020f),
                    sparkOrigin.z+sin(angle)*r)
                spark.angle(17f+95f*t,i*30f,i*37f)
            }
        }
        settled=t>=1f
    }

    private fun group(parent:Entity,name:String,x:Float,y:Float,z:Float)=Entity().also{
        it.setName(name);parent.addChild(it);it.move(x,y,z)
    }
    private fun Entity.resize(x:Float,y:Float,z:Float){components[TransformComponent::class.java]!!.scaleVector=Vector3(x,y,z)}
    private fun Entity.move(x:Float,y:Float,z:Float){components[TransformComponent::class.java]!!.position=Vector3(x,y,z)}
    private fun Entity.angle(x:Float,y:Float,z:Float){components[TransformComponent::class.java]!!.eulerAngles=EulerAngles(x,y,z)}
    private fun node(parent:Entity,name:String,mesh:MeshResource,color:Int,lit:Boolean=false)=group(parent,name,0f,0f,0f).also{
        it.components.set(ModelComponent(mesh,material(color,lit)))
    }
    private fun meshFurniture(parent:Entity,name:String,geometry:FurnitureGeometry,lit:Boolean=false,
                              finishes:Map<Int,ForgeFinish> = ForgeFurniture.finishes):List<Entity> =
        geometry.surfaces.map { (color,skin) ->
            val key="${name}_${color.toString(16)}"
            node(parent,key,shape(key,skin.points,skin.triangles,skin.uvs),color,lit).also{e->
                if(!lit)finishes[color]?.let{surface(it,e)}
            }
        }
    private fun box(parent:Entity,name:String,x:Float,y:Float,z:Float,w:Float,h:Float,d:Float,color:Int,lit:Boolean=false):Entity {
        val mesh=geometry("box:$w/$h/$d"){MeshResource.createBox(Vector3(w,h,d),minOf(w,h,d)*.13f)}
        return node(parent,name,mesh,color,lit).also{it.move(x,y,z)}
    }
    private fun cylinder(parent:Entity,name:String,x:Float,y:Float,z:Float,height:Float,r:Float,color:Int):Entity{
        val mesh=geometry("cyl:$height/$r"){MeshResource.createCylinder(height,r)}
        return node(parent,name,mesh,color).also{it.move(x,y,z)}
    }
    private fun torus(parent:Entity,name:String,x:Float,y:Float,z:Float,r:Float,tube:Float,color:Int,lit:Boolean=false):Entity{
        val mesh=geometry("ring:$r/$tube"){
            val vertices=mutableListOf<Vector3>()
            val normals=mutableListOf<Vector3>()
            val indices=mutableListOf<Int>()
            val n=40; val m=6
            for(i in 0 until n)for(j in 0 until m){
                val a=2*PI*i/n;val b=2*PI*j/m
                val radial=r+tube*cos(b).toFloat()
                vertices+=Vector3(radial*cos(a).toFloat(),tube*sin(b).toFloat(),radial*sin(a).toFloat())
                normals+=Vector3((cos(a)*cos(b)).toFloat(),sin(b).toFloat(),(sin(a)*cos(b)).toFloat())
                val i0=i*m+j;val i1=i*m+(j+1)%m;val i2=((i+1)%n)*m+j;val i3=((i+1)%n)*m+(j+1)%m
                indices+=listOf(i0,i1,i2,i1,i3,i2)
            }
            MeshResource.createWithMeshModel(model=MeshModel(positions=vertices,triangleIndices=indices,normals=normals),name="ForgedRing")
        }
        return node(parent,name,mesh,color,lit).also{it.move(x,y,z)}
    }
    private fun shape(name:String,points:List<Point>,triangles:List<Int>,sourceUvs:List<Pair<Float,Float>>?=null)=geometry(name){
        val vertices=mutableListOf<Vector3>()
        val normals=mutableListOf<Vector3>()
        val uvs=mutableListOf<Vector2>()
        val lo=Point(points.minOf{it.x}, points.minOf{it.y}, points.minOf{it.z})
        val hi=Point(points.maxOf{it.x}, points.maxOf{it.y}, points.maxOf{it.z})
        val dim=hi-lo
        val authored=sourceUvs?.takeIf{it.size==points.size}
        for((i,j,k) in triangles.chunked(3)){
            val a=points[i];val b=points[j];val c=points[k]
            val u=b-a;val v=c-a
            val n=Point(u.y*v.z-u.z*v.y,u.z*v.x-u.x*v.z,u.x*v.y-u.y*v.x)
            val l=sqrt(n.dot(n)).coerceAtLeast(.000001f)
            vertices+=listOf(a.vector(),b.vector(),c.vector())
            repeat(3){normals+=(n*(1f/l)).vector()}
            val ax=kotlin.math.abs(n.x);val ay=kotlin.math.abs(n.y);val az=kotlin.math.abs(n.z)
            for(index in listOf(i,j,k)){
                if(authored!=null){
                    val uv=authored[index]
                    uvs+=Vector2(uv.first,uv.second)
                    continue
                }
                val vtx=points[index]
                val q=vtx-lo
                val x=q.x/dim.x.coerceAtLeast(.00001f)
                val y=q.y/dim.y.coerceAtLeast(.00001f)
                val z=q.z/dim.z.coerceAtLeast(.00001f)
                uvs+=when { ax>=ay && ax>=az -> Vector2(z,1-y); ay>=az -> Vector2(x,z); else -> Vector2(x,1-y) }
            }
        }
        MeshResource.createWithMeshModel(model=MeshModel(positions=vertices,triangleIndices=vertices.indices.toList(),normals=normals,uv0=uvs),name=name)
    }
    private inline fun geometry(key:String,make:()->MeshResource)=meshes.getOrPut(key){
        val mesh=make()
        try{mesh.apply{toGlobal()}}catch(e:Exception){
            runCatching{if(mesh.valid)mesh.close()}
            throw e
        }
    }
    private fun linearChannel(rgb:Int,shift:Int):Float {
        val s=((rgb shr shift) and 255)/255f
        return if(s<.04045f)s/12.92f else ((s+.055f)/1.055f).pow(2.4f)
    }
    private fun material(rgb:Int,lit:Boolean)=materials.getOrPut(rgb to lit) {
        val r=linearChannel(rgb,16);val g=linearChannel(rgb,8);val b=linearChannel(rgb,0)
        val pbr=PhysicallyBasedMaterial.create()
        try{
            pbr.apply{
                setBaseColor(Color4(r,g,b,1f));setRoughness(.55f);setMetallic(if(lit).15f else .38f)
                val glow=if(lit).65f else .21f
                setEmissiveColor(Color4(r*glow,g*glow,b*glow,1f));toGlobal()
            }
        }catch(e:Exception){
            runCatching{if(pbr.valid)pbr.close()}
            throw e
        }
    }
    private fun surface(finish:ForgeFinish,e:Entity) = surfaceLibrary.bind(finish,e)
    suspend fun loadTextures() = surfaceLibrary.load()
    override fun close(){
        if(closed)return
        closed=true
        runCatching{hammer?.close()};runCatching{hammerEcho?.close()}
        runCatching{if(root.valid)root.destroy()}
        bladeSteel?.let{runCatching{if(it.valid)it.close()}}
        bladeSteel=null
        for(m in materials.values) runCatching{if(m.valid)m.close()}
        for(m in meshes.values) runCatching{if(m.valid)m.close()}
        surfaceLibrary.close()
        materials.clear();meshes.clear()
    }
}
