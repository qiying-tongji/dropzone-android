package com.bitocean.dropzone

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.LocalDate
import java.util.PriorityQueue

internal class VisualSearch(private val context:Context,private val store:Store,private val photos:PhotoLibrary){
 private val preferences=context.getSharedPreferences("settings",0)
 private val models=ClipModels(context)
 val canEnable:Boolean get()=models.ready
 val enabled:Boolean get()=preferences.getBoolean("visual_search",false)
 fun status():String?{
  if(!models.ready)return "画面匹配模型待安装（调试版）"
  if(!enabled)return "画面匹配已就绪，可和文字、标签一起搜索"
  val grants=photos.manualGrants()
  val ids=store.all().filter{it.kind=="photo"&&photos.accessible(it,grants)}.mapTo(hashSetOf()){it.id}
  val ready=store.indexedPhotos(ClipModels.VERSION).count{it in ids}
  return "画面索引 $ready/${ids.size} · 同时搜索文字和标签"
 }
 fun enable(){
  if(!models.ready)return
  preferences.edit().putBoolean("visual_search",true).apply()
  schedule()
 }
 fun clear(){
  synchronized(Store.photoLock){
   preferences.edit().putBoolean("visual_search",false)
    .putLong("visual_epoch",preferences.getLong("visual_epoch",0)+1).apply()
   WorkManager.getInstance(context).cancelUniqueWork(VisualIndexWorker.NAME)
   store.clearVisualEmbeddings()
  }
 }
 fun schedule(){
  if(!enabled||!models.ready)return
  val grants=photos.manualGrants()
  val indexed=store.indexedPhotos(ClipModels.VERSION)
  if(store.all().none{it.kind=="photo"&&it.id !in indexed&&photos.accessible(it,grants)})return
  val request=OneTimeWorkRequestBuilder<VisualIndexWorker>()
   .setInputData(workDataOf("epoch" to preferences.getLong("visual_epoch",0))).build()
  WorkManager.getInstance(context).enqueueUniqueWork(VisualIndexWorker.NAME,ExistingWorkPolicy.KEEP,request)
 }
 fun merge(textHits:List<Search.Match>,items:List<Memory>,query:Search.Query,kind:String?,tag:String):List<Search.Match>{
  if(!enabled||!models.ready||query.terms.isEmpty()||kind!=null&&kind!="photo")return textHits
  val dateRange=query.dates
  val normalizedTag=store.normalize(tag.trim())
  val candidates=items.filter{item->
   item.kind=="photo"&&(dateRange==null||runCatching{LocalDate.parse(item.date) in dateRange}.getOrDefault(false))&&
    (normalizedTag.isEmpty()||item.tags.split(Regex("[ ,，]+")).any{store.normalize(it)==normalizedTag})
  }.associateBy{it.id}
  if(candidates.isEmpty()||store.indexedPhotos(ClipModels.VERSION).none{it in candidates})return textHits
  val queryVector=try{models.textEmbedding(query.terms.joinToString(" "))}catch(error:Exception){
   Log.w("DropZoneClip","Text encoder unavailable",error)
   return textHits
  }
  val top=PriorityQueue(compareBy<VisualHit>{it.score}.thenBy{it.item.id})
  store.scanVisualEmbeddings(ClipModels.VERSION){id,bytes->
   val item=candidates[id]?:return@scanVisualEmbeddings
   val score=ClipModels.similarity(bytes,queryVector)
   if(score>=.35f){
    top.add(VisualHit(item,score))
    if(top.size>10)top.remove()
   }
  }
  val visual=top.sortedWith(compareByDescending<VisualHit>{it.score}.thenByDescending{it.item.id})
  val visualIds=visual.mapTo(hashSetOf()){it.item.id}
  val textIds=textHits.mapTo(hashSetOf()){it.item.id}
  return textHits.map{hit->
   if(hit.item.id in visualIds)hit.copy(evidence="画面相似 · "+hit.evidence) else hit
  }+visual.filter{it.item.id !in textIds}.map{Search.Match(it.item,"画面相似",0)}
 }
 private data class VisualHit(val item:Memory,val score:Float)
}

class VisualIndexWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters){
 override fun doWork():Result{
  val context=applicationContext
  val preferences=context.getSharedPreferences("settings",0)
  val epoch=inputData.getLong("epoch",-1)
  fun active()=!isStopped&&preferences.getBoolean("visual_search",false)&&preferences.getLong("visual_epoch",0)==epoch
  val models=ClipModels(context)
  if(!active()||!models.ready)return Result.success()
  return try{
   Store(context).use{store->
    val photos=PhotoLibrary(context,store)
    val grants=photos.manualGrants()
    val indexed=store.indexedPhotos(ClipModels.VERSION)
    val candidates=store.all().filter{it.kind=="photo"&&it.id !in indexed&&photos.accessible(it,grants)}
    if(candidates.isEmpty())return Result.success()
    models.imageSession().use{session->
     for(item in candidates){
      if(!active())return Result.success()
      if(!photos.accessible(item))continue
      val embedding=try{models.imageEmbedding(session,context,Uri.parse(item.uri))}catch(_:Exception){continue}
      synchronized(Store.photoLock){
       val latest=store.get(item.id)
       if(active()&&latest!=null&&latest.uri==item.uri&&photos.accessible(latest)){
        store.writeVisualEmbedding(latest.id,ClipModels.VERSION,ClipModels.serialize(embedding))
       }
      }
     }
    }
   }
   Result.success()
  }catch(error:Exception){
   Log.w("DropZoneClip","Image encoder unavailable",error)
   Result.failure()
  }
 }
 companion object{const val NAME="visual-search-index"}
}
