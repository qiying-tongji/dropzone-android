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
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class OcrWorker(context:Context,params:WorkerParameters):Worker(context,params) {
 override fun doWork():Result {
  val prefs=applicationContext.getSharedPreferences("settings",0)
  val id=inputData.getLong("id",-1)
  val epoch=inputData.getLong("epoch",-1)
  val store=Store(applicationContext)
  val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
  val labeler=ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
  try {
   if(!prefs.getBoolean("ocr",false)||prefs.getLong("epoch",0)!=epoch)return Result.success()
   val item=store.get(id)?:return Result.success()
   store.update(id,ContentValues().apply{put("state","processing")})
   val image=InputImage.fromFilePath(applicationContext,Uri.parse(item.uri))
   val textResult=runCatching{Tasks.await(recognizer.process(image),60,TimeUnit.SECONDS)}
   val labelResult=runCatching{Tasks.await(labeler.process(image),60,TimeUnit.SECONDS)}
   if(textResult.isFailure&&labelResult.isFailure) throw (textResult.exceptionOrNull()?:labelResult.exceptionOrNull()?:IllegalStateException("Recognition failed"))
   val detected=labelResult.getOrNull().orEmpty().sortedByDescending{it.confidence}.take(8)
   val labelsEn=detected.joinToString(" · "){"${it.text} ${(it.confidence*100).roundToInt()}%"}
   val labels=detected.joinToString(" · "){"${LabelTranslator.translate(it.text)} ${(it.confidence*100).roundToInt()}%"}
   if(!isStopped && prefs.getBoolean("ocr",false)&&prefs.getLong("epoch",0)==epoch&&store.get(id)!=null){
    store.update(id,ContentValues().apply{put("ocr",textResult.getOrNull()?.text.orEmpty());put("labels",labels);put("labels_en",labelsEn);put("state","ready")})
   }
   return Result.success()
  } catch(e:SecurityException){if(store.get(id)!=null)store.update(id,ContentValues().apply{put("ocr","");put("state","unavailable")});return Result.failure()}
  catch(e:Exception){if(store.get(id)!=null)store.update(id,ContentValues().apply{put("state","failed")});return if(runAttemptCount<2)Result.retry()else Result.failure()}
  finally{recognizer.close();labeler.close();store.close()}
 }
 companion object {
  fun enqueue(context:Context,id:Long,epoch:Long){
   val request=OneTimeWorkRequestBuilder<OcrWorker>().setInputData(workDataOf("id" to id,"epoch" to epoch)).addTag("ocr").build()
   WorkManager.getInstance(context).enqueueUniqueWork("ocr-$id",ExistingWorkPolicy.KEEP,request)
  }
 }
}
