package com.bitocean.dropzone

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

internal class ClipModels(context:Context){
 private val folder=File(context.filesDir,"chinese-clip")
 private val image=File(folder,"image.onnx")
 private val text=File(folder,"text.onnx")
 private val vocab=File(folder,"vocab.txt")
 private val environment by lazy{OrtEnvironment.getEnvironment()}
 val ready:Boolean get()=File(folder,"rn50-fp32-v1.ready").isFile&&image.length()==153198835L&&
  text.length()==154724276L&&vocab.length()==109540L

 fun imageSession()=session(image)
 private fun session(model:File):OrtSession=OrtSession.SessionOptions().use{options->
  options.setIntraOpNumThreads(2)
  environment.createSession(model.absolutePath,options)
 }
 fun textEmbedding(query:String):FloatArray {
  val tokens=ClipTokenizer(vocab.readLines()).encode(query)
  return session(text).use{model->
   OnnxTensor.createTensor(environment,LongBuffer.wrap(tokens),longArrayOf(1,52)).use{input->
    run(model,"tokens",input)
   }
  }
 }
 fun imageEmbedding(model:OrtSession,context:Context,uri:Uri):FloatArray {
  val source=ImageDecoder.createSource(context.contentResolver,uri)
  val bitmap=ImageDecoder.decodeBitmap(source){decoder,_,_->
   decoder.setTargetSize(224,224)
   decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
   decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
  }
  val plane=224*224
  val pixels=IntArray(plane)
  try{bitmap.getPixels(pixels,0,224,0,0,224,224)}finally{bitmap.recycle()}
  val values=FloatArray(3*plane)
  for(i in pixels.indices){
   val pixel=pixels[i]
   values[i]=((pixel shr 16 and 255)/255f-.48145466f)/.26862954f
   values[plane+i]=((pixel shr 8 and 255)/255f-.4578275f)/.26130258f
   values[2*plane+i]=((pixel and 255)/255f-.40821073f)/.27577711f
  }
  return OnnxTensor.createTensor(environment,FloatBuffer.wrap(values),longArrayOf(1,3,224,224)).use{input->
   run(model,"image",input)
  }
 }
 private fun run(model:OrtSession,inputName:String,input:OnnxTensor):FloatArray=
  model.run(mapOf(inputName to input)).use{output->
   ((output[0].value as Array<*>)[0] as FloatArray).also{require(it.size==DIMENSIONS&&it.all(Float::isFinite))}
  }

 companion object {
  const val VERSION="rn50-fp32-v1"
  const val DIMENSIONS=1024
  fun serialize(vector:FloatArray):ByteArray=ByteBuffer.allocate(vector.size*4).order(ByteOrder.LITTLE_ENDIAN)
   .apply{asFloatBuffer().put(vector)}.array()
  fun similarity(bytes:ByteArray,query:FloatArray):Float{
   if(bytes.size!=query.size*4)return Float.NEGATIVE_INFINITY
   val image=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
   var score=0f
   for(value in query)score+=image.get()*value
   return score
  }
 }
}
