package com.bitocean.dropzone

import android.Manifest.permission
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class PhotoLibrary(private val context:Context,private val store:Store){
 private val resolver=context.contentResolver
 private val preferences=context.getSharedPreferences("settings",0)
 private val scanState=context.getSharedPreferences("album_scan",0)
 private val readFlag=Intent.FLAG_GRANT_READ_URI_PERMISSION

 fun fullPermission():Boolean=granted(if(Build.VERSION.SDK_INT>=33)permission.READ_MEDIA_IMAGES else permission.READ_EXTERNAL_STORAGE)
 fun partialPermission():Boolean=Build.VERSION.SDK_INT>=34&&granted(permission.READ_MEDIA_VISUAL_USER_SELECTED)
 private fun granted(name:String)=context.checkSelfPermission(name)==PackageManager.PERMISSION_GRANTED
 fun albumActive()=preferences.getBoolean("album",false)&&fullPermission()
 fun manualGrants():Set<String> = resolver.persistedUriPermissions.filter{it.isReadPermission}.mapTo(mutableSetOf()){it.uri.toString()}

 fun accessible(item:Memory,grants:Set<String> = manualGrants(),includeUnavailable:Boolean=false):Boolean{
  if(item.kind!="photo")return true
  return (includeUnavailable||item.state!="unavailable")&&
   ((item.uri==item.manualUri&&item.manualUri in grants)||(item.uri==item.albumUri&&albumActive()&&item.albumUri.isNotEmpty()))
 }

 fun addManual(uri:Uri,fromShare:Boolean):Long? {
  if(uri.scheme!="content"||resolver.getType(uri)?.startsWith("image/")==false)return null
  val original=uri.toString()
  val persisted=runCatching{
   resolver.takePersistableUriPermission(uri,readFlag)
   original in manualGrants()
  }.getOrDefault(false)
  val media=mediaIdentity(uri)
  if(!persisted){
   if(!fromShare||!albumActive()||media==null||!readable(media))return null
   return synchronized(Store.photoLock){
    if(!albumActive())null else {
     val taken=takenDate(media)
     store.savePhoto(media.toString(),media.toString(),displayName(media)?:"照片",taken?:LocalDate.now().toString(),true,takenDate=taken)
    }
   }
  }
  return synchronized(Store.photoLock){
   val previous=media?.toString()?.let(store::manualUriForIdentity).orEmpty()
   try{
    if(!readable(uri))null
    else {
     val today=LocalDate.now().toString()
     val title=media?.let(::displayName)?:displayName(uri)?.takeUnless{
      uri.pathSegments.firstOrNull() in setOf("picker","picker_get_content")&&it.substringBeforeLast('.').toLongOrNull()!=null
     }?:"照片 $today"
     val taken=media?.let(::takenDate)?:takenDate(uri)
     store.savePhoto(original,media?.toString().orEmpty(),title,taken?:today,false,takenDate=taken)
    }
   }finally{
    releaseUnused(original)
    if(previous!=original)releaseUnused(previous)
   }
  }
 }

 private fun mediaIdentity(uri:Uri):Uri? {
  if(uri.authority==MediaStore.AUTHORITY&&uri.pathSegments.firstOrNull() in setOf("picker","picker_get_content")){
   return localPickerIdentity(uri)
  }
  val media=if(uri.authority==MediaStore.AUTHORITY)uri else runCatching{MediaStore.getMediaUri(context,uri)}.getOrNull()
  val parts=media?.pathSegments?:return null
  if(parts.size!=4||parts[1]!="images"||parts[2]!="media")return null
  val id=parts[3].toLongOrNull()?:return null
  if(parts[0]!=MediaStore.VOLUME_EXTERNAL)return media
  val projection=arrayOf(MediaStore.MediaColumns.VOLUME_NAME)
  val volume=runCatching{resolver.query(media,projection,null,null,null)?.use{if(it.moveToFirst())it.getString(0) else null}}.getOrNull()?:return media
  return ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume),id)
 }

 // This picker provider uses the MediaStore image ID for local items. Other providers
 // and other profiles keep their independent URI unless Android resolves it directly.
 private fun localPickerIdentity(uri:Uri):Uri? {
  val parts=uri.pathSegments
  if(!fullPermission()||parts.size!=5||parts[1]!="0"||
   Process.myUserHandle()!=UserHandle.getUserHandleForUid(0)||
   parts[2]!="com.android.providers.media.photopicker"||parts[3]!="media")return null
  val id=parts[4].toLongOrNull()?:return null
  val media=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id)
  val canonical=mediaIdentity(media)?:return null
  return canonical.takeIf{it!=media}
 }

 private fun displayName(uri:Uri):String?=runCatching{
  resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{
   if(it.moveToFirst())it.getString(0)?.takeIf(String::isNotBlank) else null
  }
 }.getOrNull()
 private fun takenDate(uri:Uri):String?=runCatching{
  resolver.query(uri,arrayOf(MediaStore.Images.Media.DATE_TAKEN),null,null,null)?.use{
   if(it.moveToFirst())photoDate(it.getLong(0)) else null
  }
 }.getOrNull()
 private fun photoDate(milliseconds:Long):String?=milliseconds.takeIf{it>0}?.let{
  Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()
 }

 fun releaseUnused(uri:String){
  if(uri.isNotBlank()&&!store.usesManualUri(uri)){
   runCatching{resolver.releasePersistableUriPermission(Uri.parse(uri),readFlag)}
  }
 }

 fun disableAlbum(){
  synchronized(Store.photoLock){
   preferences.edit().putBoolean("album",false).putLong("album_cycle",preferences.getLong("album_cycle",0)+1).apply()
   scanState.edit().clear().apply()
   val grants=manualGrants()
   store.all().filter{it.albumUri.isNotEmpty()}.forEach{item->
    store.setPhotoSources(item,item.manualUri.takeIf{it in grants}.orEmpty(),"")
   }
  }
 }

 fun reconcile(onRecovered:(Long)->Unit):Boolean{
  var changed=false
  val grants=manualGrants()
  val mounted=MediaStore.getExternalVolumeNames(context)
  for(item in store.all().filter{it.kind=="photo"}){
   val manual=item.manualUri.takeIf{it in grants}.orEmpty()
   val album=item.albumUri.takeIf{albumActive()}.orEmpty()
   val candidate=listOf(manual,album).filter{it.isNotEmpty()}.distinct().firstOrNull{readable(Uri.parse(it))}
   val key=item.photoKey.ifBlank{album}
   val media=key.takeIf{it.isNotEmpty()}?.let(Uri::parse)
   val removed=candidate==null&&media!=null&&fullPermission()&&media.pathSegments.firstOrNull() in mounted&&
    runCatching{resolver.query(media,arrayOf(MediaStore.MediaColumns._ID),null,null,null)?.use{!it.moveToFirst()}==true}.getOrDefault(false)
   synchronized(Store.photoLock){
    val latest=store.get(item.id)?:return@synchronized
    if(latest.manualUri!=item.manualUri||latest.albumUri!=item.albumUri)return@synchronized
    if(removed&&fullPermission()){
     store.remove(item.id,false)
     releaseUnused(item.manualUri)
     changed=true
     return@synchronized
    }
    val verifiedManual=manual.takeIf{it.isNotEmpty()&&it in manualGrants()}.orEmpty()
    val verifiedAlbum=album.takeIf{albumActive()}.orEmpty()
    val verifiedSource=candidate?.takeIf{it==verifiedManual||it==verifiedAlbum}
    val active=verifiedSource?:verifiedManual.ifBlank{verifiedAlbum}
    if(verifiedManual!=item.manualUri||verifiedAlbum!=item.albumUri||active!=item.uri){
     store.setPhotoSources(latest,verifiedManual,verifiedAlbum,active)
     releaseUnused(item.manualUri)
     changed=true
    }
    if(verifiedManual.isEmpty()&&verifiedAlbum.isEmpty())return@synchronized
    if(verifiedSource==null&&latest.state!="unavailable"){
     store.markUnavailable(item.id)
     changed=true
    }else if(verifiedSource!=null&&latest.state=="unavailable"){
     store.update(item.id,ContentValues().apply{put("state","ready")})
     onRecovered(item.id)
     changed=true
    }
   }
  }
  return changed
 }

 fun scan(forceFull:Boolean,onProgress:(Int)->Unit,onPhoto:(Long)->Unit):Int{
  var count=0
  val cycle=preferences.getLong("album_cycle",0)
  fun active()=albumActive()&&preferences.getLong("album_cycle",0)==cycle
  val firstIdentityScan=!scanState.getBoolean("local_picker_reconciled",false)||!scanState.getBoolean("review_dates_indexed",false)
  val manualPhotos=if(forceFull||firstIdentityScan){
   val grants=manualGrants()
   store.all().filter{it.kind=="photo"&&it.manualUri in grants}.also{items->
    for(item in items){
     if(!active())return count
     val key=mediaIdentity(Uri.parse(item.manualUri))?.toString()?:continue
     synchronized(Store.photoLock){
      if(!active())return count
      store.resolveManualIdentity(item.id,item.manualUri,key)
     }
    }
   }
  }else emptyList()
  val oldAlbumSources=store.all().flatMapTo(hashSetOf()){listOf(it.albumUri,it.photoKey)}
  for(volume in MediaStore.getExternalVolumeNames(context)){
   if(!active())break
   val collection=MediaStore.Images.Media.getContentUri(volume)
   val version=if(Build.VERSION.SDK_INT>=30)MediaStore.getVersion(context,volume) else MediaStore.getVersion(context)
   val generation=if(Build.VERSION.SDK_INT>=30)MediaStore.getGeneration(context,volume) else -1L
   val lastFull=scanState.getLong("$volume.full",0)
   val full=forceFull||firstIdentityScan||generation<0||scanState.getString("$volume.version",null)!=version||
    System.currentTimeMillis()-lastFull>=24*60*60*1000L
   val seen=mutableSetOf<String>()
   val columns=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.DATE_TAKEN)
   val selection=if(full)null else "${MediaStore.MediaColumns.GENERATION_MODIFIED}>?"
   val args=if(full)null else arrayOf(scanState.getLong("$volume.generation",0).toString())
   val rows=resolver.query(collection,columns,selection,args,null)?:return count
   rows.use{
    while(it.moveToNext()){
     if(!active()||Thread.currentThread().isInterrupted)return count
     val uri=ContentUris.withAppendedId(collection,it.getLong(0))
     val legacy=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,it.getLong(0)).toString()
     val key=uri.toString()
     val oldAlias=legacy.takeIf{it in oldAlbumSources&&mediaIdentity(Uri.parse(it))==uri}.orEmpty()
     seen.add(key)
     val taken=photoDate(it.getLong(2))
     val date=taken?:LocalDate.now().toString()
     synchronized(Store.photoLock){
      if(!active())return count
      if(!store.excluded(legacy)){
       store.savePhoto(key,key,it.getString(1)?:"照片",date,true,oldAlias,taken)?.let(onPhoto)
      }
     }
     count++
     if(count%50==0)onProgress(count)
    }
   }
   synchronized(Store.photoLock){
    if(!active())return count
    if(full){
     val grants=manualGrants()
     store.all().filter{it.albumUri.startsWith("$collection/")&&it.albumUri !in seen}.forEach{item->
      store.setPhotoSources(item,item.manualUri.takeIf{it in grants}.orEmpty(),"")
     }
    }
    val edit=scanState.edit().putString("$volume.version",version).putLong("$volume.generation",generation)
    if(full)edit.putLong("$volume.full",System.currentTimeMillis())
    edit.apply()
   }
  }
  synchronized(Store.photoLock){
   if(active()){
    scanState.edit().putBoolean("local_picker_reconciled",true).putBoolean("review_dates_indexed",true).apply()
    manualPhotos.forEach{releaseUnused(it.manualUri)}
   }
  }
  onProgress(count)
  return count
 }

 fun readable(uri:Uri):Boolean=try{
  resolver.openAssetFileDescriptor(uri,"r")?.use{true}?:false
 }catch(_:IOException){false}catch(_:SecurityException){false}catch(_:IllegalArgumentException){false}
}
