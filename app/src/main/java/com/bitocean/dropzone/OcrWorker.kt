package com.bitocean.dropzone

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class OcrWorker(context:Context,params:WorkerParameters):Worker(context,params) {
 override fun doWork():Result {
  val prefs=applicationContext.getSharedPreferences("settings",0)
  val id=inputData.getLong("id",-1)
  val epoch=inputData.getLong("epoch",-1)
  val store=Store(applicationContext)
  val photos=PhotoLibrary(applicationContext,store)
  val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
  val labeler=ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
  fun current(uri:String?=null):Memory?{
   if(isStopped||!prefs.getBoolean("ocr",false)||prefs.getLong("epoch",0)!=epoch)return null
   return store.get(id)?.takeIf{(uri==null||it.uri==uri)&&photos.accessible(it,includeUnavailable=true)}
  }
  var source:String?=null
  try {
   val item=synchronized(Store.photoLock){
    val eligible=current()?:return Result.success()
    store.update(id,ContentValues().apply{put("state","processing")})
    eligible
   }
   source=item.uri
   val image=InputImage.fromFilePath(applicationContext,Uri.parse(item.uri))
   val textResult=runCatching{Tasks.await(recognizer.process(image),60,TimeUnit.SECONDS)}
   val labelResult=runCatching{Tasks.await(labeler.process(image),60,TimeUnit.SECONDS)}
   val detected=labelResult.getOrNull().orEmpty().sortedByDescending{it.confidence}.take(8)
   val labelsEn=detected.joinToString(" · "){"${it.text} ${(it.confidence*100).roundToInt()}%"}
   val labels=LabelTranslator.localizeLine(labelsEn)
   val recognized=textResult.getOrNull()
   val blocks=JSONArray()
   recognized?.textBlocks?.forEach{block->
    val bounds=block.boundingBox
    blocks.put(JSONObject().put("text",block.text).apply{
     if(bounds!=null&&image.width>0&&image.height>0){
      put("bounds",JSONArray(listOf(
       (bounds.left.toDouble()/image.width).coerceIn(0.0,1.0),
       (bounds.top.toDouble()/image.height).coerceIn(0.0,1.0),
       (bounds.right.toDouble()/image.width).coerceIn(0.0,1.0),
       (bounds.bottom.toDouble()/image.height).coerceIn(0.0,1.0)
      )))
     }
    })
   }
   val bothFailed=textResult.isFailure&&labelResult.isFailure
   synchronized(Store.photoLock){
    if(current(source)==null)return Result.success()
    if(!photos.readable(Uri.parse(item.uri))){store.markUnavailable(id);return Result.failure()}
    store.update(id,ContentValues().apply{
     if(recognized!=null){put("ocr",recognized.text);put("ocr_regions",blocks.toString())}
     if(labelResult.isSuccess){put("labels",labels);put("labels_en",labelsEn)}
     put("ocr_state",if(textResult.isFailure)"failed" else if(recognized?.text.isNullOrBlank())"empty" else "found")
     put("label_state",if(labelResult.isFailure)"failed" else if(labels.isBlank())"empty" else "found")
     put("state",if(bothFailed)"failed" else if(textResult.isFailure||labelResult.isFailure)"partial" else "ready")
    })
   }
   return if(bothFailed&&runAttemptCount<2)Result.retry() else Result.success()
  } catch(_:Exception){
   synchronized(Store.photoLock){
    val eligible=current(source)?:return Result.success()
    if(!photos.readable(Uri.parse(eligible.uri)))store.markUnavailable(id)
    else store.update(id,ContentValues().apply{put("state","failed");put("ocr_state","failed");put("label_state","failed")})
   }
   return if(runAttemptCount<2)Result.retry()else Result.failure()
  }
  finally{recognizer.close();labeler.close();store.close()}
 }
 companion object {
  fun enqueue(context:Context,id:Long,epoch:Long){
   val request=OneTimeWorkRequestBuilder<OcrWorker>().setInputData(workDataOf("id" to id,"epoch" to epoch)).addTag("ocr").build()
   WorkManager.getInstance(context).enqueueUniqueWork("ocr-$id",ExistingWorkPolicy.KEEP,request)
  }
 }
}
