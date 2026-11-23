package com.pondledger.mr

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.TextureColorSpace
import com.pico.spatial.core.ecs.resource.TextureCreateOption
import com.pico.spatial.core.ecs.resource.TextureEncoding
import com.pico.spatial.core.ecs.resource.TextureMipmapMode
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Color4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/** One set of finishes for a Stage. Bind and close run on its UI thread. */
internal class ForgeFinishLibrary : AutoCloseable {
    private data class Resources(val material:PhysicallyBasedMaterial,val albedo:TextureResource)
    private val loaded=mutableMapOf<ForgeFinish,Resources>()
    private val attempted=mutableSetOf<ForgeFinish>()
    private val waiting=mutableMapOf<ForgeFinish,MutableList<Entity>>()
    private val customMaterials=mutableMapOf<ForgeFinish,MutableList<PhysicallyBasedMaterial>>()
    private val requests=Channel<ForgeFinish>(Channel.UNLIMITED)
    private var running=false
    private var closed=false

    fun bind(finish:ForgeFinish,entity:Entity) {
        if(closed || !entity.valid)return
        val resources=loaded[finish]
        if(resources!=null){applyMaterial(entity,resources.material);return}
        request(finish)
        waiting[finish]?.add(entity)
    }

    /** This material keeps its own heat colour and emission while it shares the metal albedo. */
    fun bindAlbedo(finish:ForgeFinish,material:PhysicallyBasedMaterial) {
        if(closed || !material.valid)return
        val resources=loaded[finish]
        if(resources!=null){material.setBaseColorTexture(resources.albedo);return}
        request(finish)
        if(finish in waiting)customMaterials.getOrPut(finish){mutableListOf()}.add(material)
    }

    private fun request(finish:ForgeFinish) {
        if(!attempted.add(finish))return
        waiting[finish]=mutableListOf()
        requests.trySend(finish)
    }

    private fun applyMaterial(entity:Entity,material:PhysicallyBasedMaterial) {
        if(!entity.valid)return
        try {
            val slots=entity.components[ModelComponent::class.java]?.materials ?:return
            if(slots.size>0)slots[0]=material
        }catch(e:Exception){Log.w("EmberForge","Could not bind a surface finish",e)}
    }

    suspend fun load() {
        if(running || closed)return
        running=true
        try {
            for(finish in requests) {
                if(closed)break
                load(finish)
            }
        }finally{running=false}
    }

    private suspend fun load(finish:ForgeFinish) {
        // Keep partially acquired resources reachable even if cancellation wins the return from IO.
        var texture:TextureResource?=null
        var material:PhysicallyBasedMaterial?=null
        try {
            withContext(Dispatchers.IO){
                val option=TextureCreateOption().apply{
                    name=finish.asset
                    colorSpace=TextureColorSpace.SRGB
                    textureEncoding=TextureEncoding.SRGB
                    mipmapMode=TextureMipmapMode.GENERATE_ALL
                }
                texture=TextureResource.load("forge/${finish.asset}.png",LoadType.FROM_ASSETS,option)
            }
            val map=texture ?:return
            if(closed || !map.valid)return
            map.toGlobal()
            val pbr=PhysicallyBasedMaterial.create()
            material=pbr
            pbr.setBaseColor(Color4(1f,1f,1f,1f))
            pbr.setBaseColorTexture(map)
            pbr.setMetallic(finish.metallic)
            pbr.setRoughness(finish.roughness)
            if(finish==ForgeFinish.DRAGONCRYSTAL)pbr.setEmissiveColor(Color4(.018f,.06f,.022f,1f))
            pbr.toGlobal()
            loaded[finish]=Resources(pbr,map)
            texture=null
            material=null
            waiting[finish]?.forEach{applyMaterial(it,pbr)}
            customMaterials[finish]?.forEach{custom->
                if(custom.valid)runCatching{custom.setBaseColorTexture(map)}
                    .onFailure{Log.w("EmberForge","Could not bind the blade albedo",it)}
            }
        } catch(e:CancellationException){throw e}
          catch(e:Exception){Log.w("EmberForge","Using fallback for ${finish.asset}",e)}
        finally {
            waiting.remove(finish)
            customMaterials.remove(finish)
            material?.let{runCatching{if(it.valid)it.close()}}
            texture?.let{runCatching{if(it.valid)it.close()}}
        }
    }

    override fun close() {
        if(closed)return
        closed=true
        requests.cancel()
        waiting.clear()
        customMaterials.clear()
        for(handles in loaded.values){
            runCatching{if(handles.material.valid)handles.material.close()}
            runCatching{if(handles.albedo.valid)handles.albedo.close()}
        }
        loaded.clear()
    }
}
