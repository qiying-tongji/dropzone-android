package com.bitocean.dropzone

import android.content.Context

@Suppress("UNUSED_PARAMETER")
internal class VisualSearch(context:Context,store:Store,photos:PhotoLibrary){
 val canEnable=false
 val enabled=false
 fun status():String?=null
 fun enable()=Unit
 fun clear()=Unit
 fun schedule()=Unit
 fun merge(textHits:List<Search.Match>,items:List<Memory>,query:Search.Query,kind:String?,tag:String)=textHits
}
