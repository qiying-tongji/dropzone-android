package com.bitocean.dropzone

import android.app.*
import android.content.*
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.database.ContentObserver
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
 private lateinit var db: Store
 private lateinit var photos:PhotoLibrary
 private val visualSearch by lazy{VisualSearch(this,db,photos)}
 private var albumScanning=false
 private var rescanRequested=false
 private var albumProgress=""
 private var importProgress=""
 private var screen=""
 private var refreshReviewPage:(()->Unit)?=null
 private var homeDate=LocalDate.now()
 private var shownPhoto:Long?=null
 private var searchFilter="全部"
 private var dateFilter:ClosedRange<LocalDate>?=null
 private var ignoreParsedDate=false
 private var tagFilter=""
 private val photoPicker=registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()){uris->
  if(uris.isNotEmpty()) importPhotos(uris,false)
 }
 private val mediaHandler=Handler(Looper.getMainLooper())
 private val recheckMedia=Runnable{if(alive&&::photos.isInitialized)reconcile()}
 private val albumObserver=object:ContentObserver(mediaHandler){
  override fun onChange(selfChange:Boolean){
   mediaHandler.removeCallbacks(recheckMedia)
   mediaHandler.postDelayed(recheckMedia,400)
  }
 }
 private val prefs by lazy { getSharedPreferences("settings",0) }
 private val worker=Executors.newSingleThreadExecutor()
 private val navy=Color.rgb(24,54,95); private val blue=Color.rgb(79,121,232)
 private val muted=Color.rgb(123,145,176); private val line=Color.rgb(231,237,246)
 private val surface=Color.rgb(247,249,252); private val pale=Color.rgb(238,245,255)
 private val green=Color.rgb(31,153,108); private val red=Color.rgb(205,75,82)
 private lateinit var root:LinearLayout; private lateinit var content:LinearLayout
 private var tab="首页"; private var query=""; private var filter="全部"; private var exportId=0L; private var alive=true
 private var currentBack:(()->Unit)?=null
 private val myCollectionNames = listOf("旅行","购物","学习","工作","重要")
 private val offlinePlaces by lazy { OfflinePlaceNames(assets) }
 private val smartCollectionNames=setOf("人物","风景","建筑","动物","美食","文字图片")

 override fun onCreate(s:Bundle?){super.onCreate(s);db=Store(this);photos=PhotoLibrary(this,db);db.localizeLabels();window.statusBarColor=surface;window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;home();receive(intent)}
 override fun onNewIntent(i:Intent){super.onNewIntent(i);receive(i)}
 override fun onStart(){
  super.onStart()
  runCatching{contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,albumObserver)}
 }
 override fun onStop(){mediaHandler.removeCallbacks(recheckMedia);contentResolver.unregisterContentObserver(albumObserver);super.onStop()}
 override fun onResume(){
  super.onResume()
  if(::db.isInitialized){
   if(PhotoGroupingWorker.optedIn(this,PhotoGroupingWorker.PLACE)&&!PhotoGroupingWorker.hasLocation(this))
    PhotoGroupingWorker.setEnabled(this,extras,PhotoGroupingWorker.PLACE,false)
   reconcile()
   schedulePhotoGrouping()
  }
 }
 override fun onDestroy(){alive=false;worker.shutdownNow();super.onDestroy()}
 @Deprecated("Handled for compatibility with Android 10+")
 override fun onBackPressed(){currentBack?.invoke()?:super.onBackPressed()}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun statusBarHeight():Int{val id=resources.getIdentifier("status_bar_height","dimen","android");return if(id>0)resources.getDimensionPixelSize(id)else dp(24)}
 private fun shape(c:Int,r:Int=16,stroke:Int?=null)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat();stroke?.let{setStroke(dp(1),it)}}
 private fun col(p:ViewGroup?=null)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;p?.addView(this)}
 private fun row(p:ViewGroup?=null)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;p?.addView(this)}
 private fun txt(p:ViewGroup,s:String,size:Int=15,bold:Boolean=false,color:Int=navy,gravity:Int=Gravity.START)=TextView(this).apply{text=s;textSize=size.toFloat();setTextColor(color);this.gravity=gravity;includeFontPadding=false;if(bold)setTypeface(null,Typeface.BOLD);p.addView(this)}
 private fun icon(p:ViewGroup,res:Int,size:Int=24,tint:Int=navy,click:(()->Unit)?=null)=ImageView(this).apply{setImageResource(res);imageTintList=ColorStateList.valueOf(tint);scaleType=ImageView.ScaleType.CENTER_INSIDE;contentDescription="";p.addView(this,LinearLayout.LayoutParams(dp(size),dp(size)));click?.let{setOnClickListener{it()}}}
 private fun gap(p:ViewGroup,h:Int){p.addView(Space(this),LinearLayout.LayoutParams(1,dp(h)))}
 private fun card(p:ViewGroup,pad:Int=14)=col(p).apply{setPadding(dp(pad),dp(pad),dp(pad),dp(pad));background=shape(Color.WHITE,16,line);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(6),0,dp(6))};elevation=dp(1).toFloat()}
 private fun chip(p:ViewGroup,s:String,selected:Boolean=false,click:(()->Unit)?=null)=txt(p,s,12,selected,if(selected)Color.WHITE else navy,Gravity.CENTER).apply{setPadding(dp(14),dp(8),dp(14),dp(8));background=shape(if(selected)blue else pale,20);layoutParams=LinearLayout.LayoutParams(-2,-2).apply{setMargins(0,0,dp(8),0)};click?.let{setOnClickListener{it()}}}
 private fun subtle(p:ViewGroup,s:String,click:()->Unit)=txt(p,s,14,true,blue,Gravity.CENTER).apply{setPadding(dp(12),dp(12),dp(12),dp(12));background=shape(pale,12);setOnClickListener{click()}}
 private fun primary(p:ViewGroup,s:String,click:()->Unit)=txt(p,s,15,true,Color.WHITE,Gravity.CENTER).apply{setPadding(dp(16),dp(15),dp(16),dp(15));background=shape(navy,12);setOnClickListener{click()};layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(8),0,dp(4))}}

 private fun frame(title:String,back:Boolean=false,nav:Boolean=true,profile:(()->Unit)?=null,backAction:(()->Unit)?=null):ScrollView{
  groupWork?.removeObservers(this);groupWork=null
  screen=title
  shownPhoto=null
  refreshReviewPage=null
  currentBack=if(back)(backAction?:{render()})else null
  root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),statusBarHeight()+dp(6),dp(18),0);setBackgroundColor(surface)};setContentView(root)
  val top=row(root).apply{setPadding(dp(4),dp(4),dp(4),dp(14))}
  if(back)icon(top,R.drawable.ic_back,24,navy,currentBack).apply{contentDescription="返回";setPadding(dp(11),dp(11),dp(11),dp(11));layoutParams=LinearLayout.LayoutParams(dp(48),dp(48));isClickable=true;isFocusable=true}
  txt(top,title,21,true).apply{layoutParams=LinearLayout.LayoutParams(0,dp(48),1f);gravity=Gravity.CENTER_VERTICAL}
  if(profile!=null)icon(top,R.drawable.ic_profile,22,blue,profile).apply{contentDescription="设置与隐私";setPadding(dp(11),dp(11),dp(11),dp(11));background=shape(pale,24);layoutParams=LinearLayout.LayoutParams(dp(48),dp(48))}
  val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false};root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));content=col();content.setPadding(0,0,0,dp(24));scroll.addView(content)
  if(nav)bottomNav()
  return scroll
 }
 private fun bottomNav(){val n=row(root).apply{setPadding(0,dp(7),0,dp(7));background=shape(Color.WHITE,0,line)};listOf(Triple(R.drawable.ic_home,"首页","首页"),Triple(R.drawable.ic_search,"搜索","搜索"),Triple(R.drawable.ic_collection,"合集","合集"),Triple(R.drawable.ic_heart,"已保存","已保存")).forEach{(res,name,dest)->val item=col(n).apply{gravity=Gravity.CENTER;setPadding(0,dp(3),0,dp(3));setOnClickListener{tab=dest;render()}};icon(item,res,21,if(tab==dest)blue else muted);gap(item,3);txt(item,name,10,tab==dest,if(tab==dest)blue else muted,Gravity.CENTER);item.layoutParams=LinearLayout.LayoutParams(0,-2,1f)}}
 private fun render(){when(tab){"首页"->home();"搜索"->search();"合集"->collections();else->saved()}}

 private fun home(){tab="首页";homeDate=LocalDate.now();frame("DropZone",profile={settings()})
  val search=row(content).apply{setPadding(dp(14),dp(12),dp(14),dp(12));background=shape(pale,24);setOnClickListener{tab="搜索";search()}}
  icon(search,R.drawable.ic_search,20,blue);txt(search,"搜索你的记忆…",14,false,muted).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(9)}};gap(content,11)
  val actions=row(content);quick(actions,R.drawable.ic_add,"导入"){importPage()};quick(actions,R.drawable.ic_camera,"相机"){openCamera()};quick(actions,R.drawable.ic_share,"分享"){shareHelp()};quick(actions,R.drawable.ic_menu,"更多"){importPage()}
  val unsorted=visibleItems().filter{isUnsorted(it)}
  section("未整理",unsorted.size.toString()){tab="已保存";filter="未整理";saved()}
  if(unsorted.isNotEmpty())txt(content,"点开一条内容，选择“整理到合集”即可完成整理。",12,false,muted)
  val recent=unsorted.take(4)
  if(recent.isEmpty())empty("这里已经整理好了","新保存且没有标签的内容会出现在这里。"){importPage()} else recent.forEach{memoryRow(it)}
  section("智能合集",null){tab="合集";collections()};val cs=row(content);collectionTile(cs,"人物",collectionItems("人物").size,Color.rgb(232,240,255));collectionTile(cs,"风景",collectionItems("风景").size,Color.rgb(235,246,243));collectionTile(cs,"建筑",collectionItems("建筑").size,Color.rgb(255,242,230))
  if(prefs.getBoolean("review",true)){
   section("回顾")
   subtle(content,"年度回顾"){annualReview()}
   val old=MemoryReview.anniversary(visibleItems(),homeDate,db.attachmentIds())
   if(old.isNotEmpty()){
    section("那年今日",old.size.toString())
    old.take(2).forEach{memoryRow(it,review=true)}
    if(old.size>2)subtle(content,"查看那年今日"){anniversaryPage()}
   }
  }
 }
 private fun quick(p:LinearLayout,res:Int,name:String,click:()->Unit){val t=col(p).apply{gravity=Gravity.CENTER;setPadding(dp(4),dp(11),dp(4),dp(10));background=shape(pale,14);setOnClickListener{click()};layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(3),0,dp(3),0)}};icon(t,res,23,navy);gap(t,7);txt(t,name,11,true,navy,Gravity.CENTER)}
 private fun section(title:String,count:String?=null,click:(()->Unit)?=null){val r=row(content).apply{setPadding(dp(2),dp(20),dp(2),dp(8))};txt(r,title,19,true);if(count!=null)chip(r,count);r.addView(Space(this),LinearLayout.LayoutParams(0,1,1f));if(click!=null)txt(r,"查看全部",12,true).apply{setOnClickListener{click()}}}
 private fun collectionTile(p:LinearLayout,name:String,count:Int,color:Int){val t=col(p).apply{setPadding(dp(12),dp(14),dp(12),dp(14));background=shape(color,12);layoutParams=LinearLayout.LayoutParams(0,dp(82),1f).apply{setMargins(dp(3),0,dp(3),0)};setOnClickListener{showCollection(name)}};txt(t,name,13,true);gap(t,8);txt(t,"$count 项",11,false,muted)}
 private fun empty(title:String,sub:String,click:()->Unit){val b=card(content,18).apply{background=shape(surface,16)};txt(b,title,16,true);gap(b,5);txt(b,sub,13,false,muted);gap(b,12);subtle(b,"开始添加"){click()}}

 private fun visibleItems():List<Memory>{
  val grants=photos.manualGrants()
  return db.all().filter{photos.accessible(it,grants,includeUnavailable=true)}
 }
 private fun anniversaryPage(){
  frame("那年今日",back=true,nav=false,backAction={home()})
  refreshReviewPage={anniversaryPage()}
  val items=MemoryReview.anniversary(visibleItems(),LocalDate.now(),db.attachmentIds())
  if(items.isEmpty())txt(content,"往年同一天还没有可回顾的条目。",14,false,muted)
  else items.forEach{memoryRow(it,back={anniversaryPage()},review=true)}
 }
 private fun annualReview(year:Int?=null,basis:MemoryReview.Basis=MemoryReview.Basis.OCCURRED){
  val records=MemoryReview.eligible(visibleItems())
  val currentYear=LocalDate.now().year
  val years=(records.flatMap{item->MemoryReview.Basis.entries.mapNotNull{MemoryReview.date(item,it)?.year}}+currentYear)
   .distinct().sortedDescending()
  val selected=year?:years.firstOrNull{candidate->records.any{MemoryReview.date(it,basis)?.year==candidate}}?:currentYear
  val entries=MemoryReview.yearly(records,selected,basis)
  frame("年度回顾",back=true,nav=false,backAction={home()})
  refreshReviewPage={annualReview(selected,basis)}
  val controls=row(content)
  chip(controls,"${selected} 年",true){
   AlertDialog.Builder(this).setTitle("选择年份").setItems(years.map{"${it} 年"}.toTypedArray()){_,index->
    annualReview(years[index],basis)
   }.show()
  }
  for(mode in MemoryReview.Basis.entries)chip(controls,mode.label,mode==basis){annualReview(selected,mode)}
  val headline=card(content,18).apply{background=shape(pale,16)}
  txt(headline,"${selected} 年 · ${entries.size} 条记忆",22,true).setOnClickListener{
   reviewEntries(selected,basis,"全年记录"){true}
  }
  val unknown=records.count{MemoryReview.date(it,basis)==null}
  if(unknown>0)txt(headline,"另有 $unknown 条缺少此类日期。",12,false,muted)
  if(entries.isEmpty()){
   txt(headline,"这一年还没有符合此日期口径的资料。",14,false,muted)
   return
  }
  val months=entries.groupingBy{it.date.monthValue}.eachCount()
  val busiest=months.maxByOrNull{it.value}?.key
  if(busiest!=null)txt(headline,"${busiest} 月记录最多",13,true).setOnClickListener{
   reviewEntries(selected,basis,"${busiest} 月"){it.date.monthValue==busiest}
  }
  section("按类型")
  for((kind,items) in entries.groupBy{it.item.kind})settingRow(kindName(kind),"${items.size} 条"){
   reviewEntries(selected,basis,kindName(kind)){it.item.kind==kind}
  }
  section("按月份")
  for((month,count) in months.toSortedMap())settingRow("${month} 月","$count 条"){
   reviewEntries(selected,basis,"${month} 月"){it.date.monthValue==month}
  }
  val commonTags=entries.flatMap{it.item.tags.split(Regex("[ ,，]+")).filter(String::isNotBlank).distinct()}
   .groupingBy{it}.eachCount().entries.sortedByDescending{it.value}.take(5)
  if(commonTags.isNotEmpty()){
   section("常用标签")
   for((tag,count) in commonTags)settingRow(tag,"$count 条"){
    reviewEntries(selected,basis,tag){tag in it.item.tags.split(Regex("[ ,，]+"))}
   }
  }
  val highlights=entries.filter{it.item.kind=="event"&&it.item.highlight}
  section("代表事件")
  if(highlights.isEmpty())txt(content,"可在事件详情中选择年度代表事件。",13,false,muted)
  else highlights.forEach{memoryRow(it.item,back={annualReview(selected,basis)},review=true)}
  subtle(content,"分享年度统计"){
   val summary="${selected} 年 DropZone 回顾 · ${basis.label}\n${entries.size} 条记忆\n"+
    entries.groupingBy{kindName(it.item.kind)}.eachCount().entries.joinToString("、"){"${it.key} ${it.value} 条"}
   AlertDialog.Builder(this).setTitle("分享预览").setMessage(summary).setNegativeButton("取消",null)
    .setPositiveButton("分享"){_,_->startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,summary)},null))}.show()
  }
 }
 private fun reviewEntries(year:Int,basis:MemoryReview.Basis,label:String,include:(MemoryReview.Dated)->Boolean){
  frame("回顾条目",back=true,nav=false,backAction={annualReview(year,basis)})
  refreshReviewPage={reviewEntries(year,basis,label,include)}
  val entries=MemoryReview.yearly(visibleItems(),year,basis).filter(include)
  section(label,entries.size.toString())
  val destination=content
  var shown=0
  fun page(){
   val end=(shown+30).coerceAtMost(entries.size)
   while(shown<end)memoryRow(entries[shown++].item,back={reviewEntries(year,basis,label,include)},review=true)
   if(shown<entries.size){
    lateinit var more:TextView
    more=subtle(destination,"显示更多"){destination.removeView(more);page()}
   }
  }
  page()
 }
 private fun memoryRow(m:Memory,evidence:String?=null,parent:ViewGroup=content,back:()->Unit={render()},review:Boolean=false){
  if(!photos.accessible(m,includeUnavailable=true))return
  val r=row(parent).apply{setPadding(0,dp(10),0,dp(10));setOnClickListener{detail(m.id,evidence,back)}}
  val preview=FrameLayout(this).apply{background=shape(pale,10);layoutParams=LinearLayout.LayoutParams(dp(74),dp(74))};r.addView(preview)
  val imageSource=if(m.kind=="photo")m.takeIf{it.state!="unavailable"} else coverOf(m,review)
  if(imageSource!=null){val image=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setPadding(dp(5),dp(5),dp(5),dp(5))};preview.addView(image,FrameLayout.LayoutParams(-1,-1));loadImage(image,imageSource.uri,dp(120))}else ImageView(this).apply{setImageResource(kindRes(m.kind));imageTintList=ColorStateList.valueOf(navy);setPadding(dp(22),dp(22),dp(22),dp(22));preview.addView(this,FrameLayout.LayoutParams(-1,-1))}
  val info=col(r).apply{setPadding(dp(12),dp(1),0,0);layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,m.title,15,true);gap(info,5);txt(info,"${kindName(m.kind)} · ${sourceName(m)}",11,false,muted);gap(info,4)
  if(m.kind=="event"&&m.dateLabel.isNotBlank())txt(info,m.dateLabel,11,false,muted)
  if(m.kind!="event"||m.body.isNotBlank()||evidence!=null)txt(info,evidence?:summary(m),12,false,if(evidence==null)muted else blue)
  if(isUnsorted(m)){gap(info,5);txt(info,"未整理 · 点开整理",10,true,blue).apply{setPadding(dp(7),dp(3),dp(7),dp(3));background=shape(pale,8);layoutParams=LinearLayout.LayoutParams(-2,-2)}}
  parent.addView(View(this).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,dp(1)))
 }
 private fun loadImage(view: ImageView, uri: String, maxSide: Int = 800) {
  val request = Any()
  view.tag = request
  worker.execute {
   if (view.tag != request) return@execute
   val bitmap = runCatching {
    val source = ImageDecoder.createSource(contentResolver, Uri.parse(uri))
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
     val scale = maxOf(info.size.width,info.size.height) / maxSide.toFloat()
     if (scale > 1) decoder.setTargetSize((info.size.width/scale).toInt().coerceAtLeast(1),
      (info.size.height/scale).toInt().coerceAtLeast(1))
    }
   }.getOrNull()
   runOnUiThread {
    if (bitmap != null) {
     if (alive && view.tag == request) view.setImageBitmap(bitmap) else bitmap.recycle()
    }
   }
  }
 }

 private fun search(restoreOffset:Int=0,restoreCount:Int=30){tab="搜索";val scroll=frame("记忆搜索")
  val bar=row(content).apply{setPadding(dp(12),dp(4),dp(7),dp(4));background=shape(pale,22)}
  icon(bar,R.drawable.ic_search,20,navy)
  val input=EditText(this).apply{
   hint="例如：天空 或 上周的演出票根";setText(query);textSize=14f;setTextColor(navy);setHintTextColor(muted)
   background=null;setSingleLine();imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
  }
  fun submit(){
   val value=input.text.toString().trim()
   if(value!=query){dateFilter=null;ignoreParsedDate=false}
   query=value
   search()
  }
  input.setOnEditorActionListener{_,_,_->submit();true}
  bar.addView(input,LinearLayout.LayoutParams(0,dp(44),1f))
  txt(bar,"搜索",12,true,blue,Gravity.CENTER).apply{setPadding(dp(10),0,dp(10),0);setOnClickListener{submit()}}
  gap(content,12)
  val parsed=Search.parse(query)
  val dates=dateFilter?:parsed.dates.takeUnless{ignoreParsedDate}
  val filters=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
  val chips=row()
  listOf("全部","照片","链接","文字","日记","事件").forEach{name->
   chip(chips,name,searchFilter==name){searchFilter=name;submit()}
  }
  filters.addView(chips);content.addView(filters);gap(content,8)
  val extras=row(content)
  chip(extras,dates?.let{if(it.start==it.endInclusive)it.start.toString() else "${it.start} 至 ${it.endInclusive}"}?:"日期",dates!=null){
   editSearchFilters(dates)
  }
  chip(extras,tagFilter.ifBlank{"标签"},tagFilter.isNotBlank()){editSearchFilters(dates)}
  visualSearch.status()?.let{status->
   gap(content,8)
   txt(content,status,12,false,muted)
   if(visualSearch.canEnable&&!visualSearch.enabled)subtle(content,"开启画面匹配"){
    AlertDialog.Builder(this).setTitle("启用本机画面匹配")
     .setMessage("DropZone 只为已加入且仍获授权的照片建立向量索引。索引和查询均在本机进行。")
     .setNegativeButton("取消",null).setPositiveButton("开启"){_,_->
      visualSearch.enable()
      search()
     }.show()
   }
   if(visualSearch.enabled)subtle(content,"关闭画面匹配"){
    visualSearch.clear()
    search()
   }
  }
  gap(content,12)
  if(query.isBlank()&&dates==null&&tagFilter.isBlank()){
   val hints=card(content,18).apply{background=shape(surface,16)}
   txt(hints,"试着描述你记得的线索",17,true);gap(hints,8)
   val examples=listOf("天空","上周的演出票根","朋友推荐的餐厅")
   val suggestions=if(visualSearch.enabled)examples+"天空中有闪电的城市天际线" else examples
   suggestions.forEach{sample->
    subtle(hints,"⌕  $sample"){query=sample;dateFilter=null;ignoreParsedDate=false;search()};gap(hints,7)
   }
   return
  }
  val destination=content
  val keyword=parsed.copy(dates=dates)
  val kind=mapOf("照片" to "photo","链接" to "link","文字" to "text","日记" to "journal","事件" to "event")[searchFilter]
  val tag=tagFilter
  worker.execute{
   val start=SystemClock.elapsedRealtime()
   val grants=photos.manualGrants()
   val candidates=db.all().filter{photos.accessible(it,grants)}
   visualSearch.schedule()
   val textHits=Search.find(db,candidates,keyword,kind,tag)
   val matches=visualSearch.merge(textHits,candidates,keyword,kind,tag)
   val eligible=matches.filter{it.source.kind!="photo"||photos.readable(Uri.parse(it.source.uri))}
   val resultIds=eligible.filter{it.item.kind!="event"}.mapTo(hashSetOf()){it.item.id}
   val eventMatches=if(resultIds.isEmpty())emptyMap() else eventLinks(candidates).eventsFor(resultIds)
   val searchMs=SystemClock.elapsedRealtime()-start
   runOnUiThread{
    if(!alive||content!==destination)return@runOnUiThread
    val renderStarted=SystemClock.elapsedRealtime()
    val currentGrants=photos.manualGrants()
    val visible=eligible.filter{photos.accessible(it.source,currentGrants)}
    txt(destination,"${visible.size} 个结果",13,false,muted)
    if(visible.isEmpty()){
     gap(destination,16)
     empty("没有找到对应记忆","试试缩短关键词或调整筛选。"){importPage()}
    }else{
     var shown=0
     fun showPage(count:Int=30){
      val last=(shown+count).coerceAtMost(visible.size)
      while(shown<last){
       val hit=visible[shown++]
       val (item,evidence)=hit
       if(!photos.accessible(hit.source))continue
       val box=card(destination,10)
       memoryRow(item,parent=box)
       val why=row(box).apply{
        setPadding(dp(11),dp(10),dp(11),dp(10));background=shape(pale,10)
        setOnClickListener{
         if(!photos.accessible(hit.source)||(hit.source.kind=="photo"&&!photos.readable(Uri.parse(hit.source.uri)))){
          toast("原图已不可用");search()
         }else detail(item.id,evidence)
        }
       }
       txt(why,"✦",14,true,blue)
       txt(why,evidence.take(110),11,false,Color.rgb(71,105,167))
       if(eventMatches[item.id]?.isNotEmpty()==true){
        gap(box,6)
        subtle(box,"查看同一事件"){
         val offset=scroll.scrollY
         val count=shown
         openRelatedEvent(item.id){search(offset,count)}
        }
       }
      }
      if(shown<visible.size){
       lateinit var more:TextView
       more=subtle(destination,"显示更多"){destination.removeView(more);showPage()}
      }
     }
     showPage(restoreCount)
     if(restoreOffset>0)destination.addOnLayoutChangeListener(object:View.OnLayoutChangeListener{
      override fun onLayoutChange(view:View,left:Int,top:Int,right:Int,bottom:Int,oldLeft:Int,oldTop:Int,oldRight:Int,oldBottom:Int){
       view.removeOnLayoutChangeListener(this)
       scroll.scrollTo(0,restoreOffset)
      }
     })
    }
    if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)android.util.Log.d("DropZoneSearch","items=${candidates.size} results=${visible.size} queryMs=$searchMs firstPageMs=${SystemClock.elapsedRealtime()-renderStarted}")
   }
  }
 }
 private fun eventLinks(items:List<Memory> = visibleItems().filter{photos.accessible(it)}):EventLinks=
  EventLinks(items,db.attachmentIds())
 private fun openRelatedEvent(itemId:Long,back:()->Unit){
  val current=db.get(itemId)
  if(current==null||!photos.accessible(current)||(current.kind=="photo"&&!photos.readable(Uri.parse(current.uri)))){
   toast("这条资料已不可访问")
   back()
   return
  }
  val events=eventLinks().eventsFor(setOf(itemId))[itemId].orEmpty()
  when(events.size){
   0->{toast("这条资料的事件关联已变更");back()}
   1->detail(events.single().id,back=back,focusRelations=true)
   else->{
    val choices=events.map{event->"${event.title} · ${event.dateLabel.ifBlank{"日期未知"}}"}
    AlertDialog.Builder(this).setTitle("选择同一事件").setItems(choices.toTypedArray()){_,index->
     detail(events[index].id,back=back,focusRelations=true)
    }.setNegativeButton("取消",null).show()
   }
  }
 }
 private fun editSearchFilters(dates:ClosedRange<LocalDate>?){
  val form=col().apply{setPadding(dp(20),dp(8),dp(20),0)}
  fun field(hint:String,value:String)=EditText(this).apply{this.hint=hint;setText(value);setSingleLine();form.addView(this)}
  val from=field("开始日期 YYYY-MM-DD",dates?.start?.toString().orEmpty())
  val to=field("结束日期 YYYY-MM-DD",dates?.endInclusive?.toString().orEmpty())
  val tag=field("标签",tagFilter)
  val dialog=AlertDialog.Builder(this).setTitle("日期和标签").setView(form)
   .setNegativeButton("取消",null).setNeutralButton("清除"){_,_->
    dateFilter=null;ignoreParsedDate=true;tagFilter="";search()
   }.setPositiveButton("应用",null).create()
  dialog.setOnShowListener{
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
    val start=runCatching{LocalDate.parse(from.text.toString().trim())}.getOrNull()
    val end=runCatching{LocalDate.parse(to.text.toString().trim())}.getOrNull()
    if(from.text.isNotBlank()&&start==null){from.error="请使用 YYYY-MM-DD";return@setOnClickListener}
    if(to.text.isNotBlank()&&end==null){to.error="请使用 YYYY-MM-DD";return@setOnClickListener}
    if(start!=null&&end!=null&&start>end){to.error="结束日期不能早于开始日期";return@setOnClickListener}
    dateFilter=if(start==null&&end==null)null else (start?:LocalDate.MIN)..(end?:LocalDate.MAX)
    ignoreParsedDate=true;tagFilter=tag.text.toString().trim()
    dialog.dismiss();search()
   }
  }
  dialog.show()
 }

 private fun saved(){
  tab="已保存"
  frame("已保存")
  subtle(row(content),"＋ 添加"){importPage()}
  gap(content,8)
  val scopes=row(content)
  listOf("全部","未整理","相册索引").forEach{scope->chip(scopes,scope,filter==scope){filter=scope;saved()}}
  val items=visibleItems().filter{item->
   when(filter){
    "未整理"->isUnsorted(item)
    "相册索引"->item.albumUri.isNotEmpty()
    else->item.kind!="event"
   }
  }
  section(if(filter=="全部")"全部记忆" else filter,items.size.toString())
  when(filter){
   "未整理"->txt(content,"手选和自动索引的条目都按合集标签判断是否已整理。",12,false,muted)
   "相册索引"->txt(content,"这里显示全库索引中的照片。没有合集标签的照片也会出现在「未整理」。",12,false,muted)
  }
  if(items.isEmpty())empty(if(filter=="未整理")"没有待整理内容" else "这里还没有内容",
   if(filter=="未整理")"待整理内容会在这里汇总。" else "添加照片、截图、链接或文字，稍后都能搜索。"){importPage()}
  else items.forEach{memoryRow(it)}
 }

 private fun collections(){tab="合集";frame("合集");subtle(content,"记忆流沙"){memorySands()};gap(content,8);subtle(content,"记忆提醒"){reminderList()};gap(content,8);val intro=card(content,16).apply{background=shape(pale,16)};txt(intro,"识别后自动归类，也可以手动整理",17,true);gap(intro,6);txt(intro,"智能合集由识别归类。「我的合集」按标签持续收纳；「事件」整理某一次经历。",13,false,muted);gap(intro,12);subtle(intro,"查看未整理内容"){tab="已保存";filter="未整理";saved()};section("智能合集");listOf("人物","风景","建筑","动物","美食","文字图片").forEach{name->collectionListRow(name,collectionItems(name).size,"由本地识别自动归类")};subtle(content,"地点"){groupedPhotos(PhotoGroupingWorker.PLACE)};section("我的合集");myCollectionNames.forEach{name->collectionListRow(name,collectionItems(name).size,"手动整理的内容")};section("事件与日记");val es=visibleItems().filter{it.kind in listOf("event","journal")};if(es.isEmpty())txt(content,"创建事件或写下日记，让照片、链接和你的话回到同一段经历。",13,false,muted);es.forEach{memoryRow(it)};gap(content,10);subtle(content,"＋ 新建事件合集"){edit("event")};gap(content,7);subtle(content,"写一篇日记"){edit("journal")}}
 private fun collectionListRow(name:String,count:Int,source:String){val box=card(content,16).apply{setOnClickListener{showCollection(name)}};val r=row(box);txt(r,collectionIcon(name),25,false,blue,Gravity.CENTER).apply{setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(pale,12)};val info=col(r).apply{setPadding(dp(12),0,0,0);layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,name,16,true);gap(info,5);txt(info,"$count 项 · $source",12,false,muted);txt(r,"›",28,false,muted)}
 private fun showCollection(name:String){val ms=collectionItems(name);frame(name,back=true,nav=false,backAction={collections()});if(name=="人物")subtle(content,"按同一人分组"){groupedPhotos(PhotoGroupingWorker.FACE)};reminderButton(ReminderTarget("collection",name.hashCode().toLong(),name),name){showCollection(name)};txt(content,"${ms.size} 项记忆",13,false,muted);gap(content,10);if(ms.isEmpty())empty("这个合集还是空的",if(name in smartCollectionNames)"识别到对应人物、场景或物体后会自动出现在这里。" else "打开一条未整理内容，选择“整理到合集”。"){tab="已保存";filter="未整理";saved()}else ms.forEach{memoryRow(it)}}

 private fun importPage(){frame("导入与处理",true,false);if(importProgress.isNotEmpty())txt(content,importProgress,13,false,muted);txt(content,"先保存，识别可以稍后完成。",13,false,muted);gap(content,14);importChoice(R.drawable.ic_add,"添加照片或截图","从系统照片库选择，可一次添加多张"){pick()};importChoice(R.drawable.ic_link,"保存公开链接","粘贴或从其他应用分享网址"){edit("link")};importChoice(R.drawable.ic_note,"保存一段文字","记下当时的想法或线索"){edit("text")};section("最近导入");val rs=visibleItems().filter{it.kind !in listOf("event","journal")}.take(6);if(rs.isEmpty())txt(content,"还没有导入记录。",13,false,muted);rs.forEach{processingRow(it)};gap(content,8);primary(content,"查看所有已保存内容"){tab="已保存";saved()}}
 private fun importChoice(res:Int,title:String,sub:String,click:()->Unit){val b=row(content).apply{setPadding(dp(14),dp(14),dp(14),dp(14));background=shape(if(title.startsWith("添加"))pale else Color.WHITE,14,line);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(5),0,dp(5))};setOnClickListener{click()}};val holder=FrameLayout(this).apply{background=shape(Color.rgb(225,236,255),25);layoutParams=LinearLayout.LayoutParams(dp(50),dp(50))};b.addView(holder);ImageView(this).apply{setImageResource(res);imageTintList=ColorStateList.valueOf(navy);setPadding(dp(13),dp(13),dp(13),dp(13));holder.addView(this,FrameLayout.LayoutParams(-1,-1))};val info=col(b).apply{setPadding(dp(13),0,0,0);layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,title,14,true);gap(info,6);txt(info,sub,11,false,muted)}
 private fun processingRow(m:Memory){val r=row(content).apply{setPadding(0,dp(11),0,dp(11));setOnClickListener{detail(m.id,back={importPage()})}};icon(r,kindRes(m.kind),25,navy).apply{setPadding(dp(15),dp(15),dp(15),dp(15));background=shape(pale,9);layoutParams=LinearLayout.LayoutParams(dp(58),dp(58))};val info=col(r).apply{setPadding(dp(12),0,0,0);layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,m.title,14,true);gap(info,4);val status=when(m.state){"processing"->"正在识别";"partial"->"部分可搜索";"failed"->"需要重试";"unavailable"->"原件不可用";else->"已就绪"};txt(info,status,12,true,when(m.state){"failed","unavailable"->red;"processing"->Color.rgb(218,137,35);else->green});txt(info,when(m.state){"processing"->"正在识别文字与画面";"partial"->"文字或画面有一项未完成";"failed"->"识别未完成，原件仍保留";"unavailable"->"需要重新选择原照片";else->"已经可以搜索"},11,false,muted);if(m.state in listOf("failed","partial"))subtle(r,"重试"){consentOcr{ocr(m.id)}};content.addView(View(this).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,dp(1)))}

 private fun detail(id:Long,evidence:String?=null,back:()->Unit={render()},focusRelations:Boolean=false){
  val m=db.get(id)?:return
  if(!photos.accessible(m,includeUnavailable=true)){toast("原图授权已失效");back();return}
  if(m.kind=="photo"&&m.state!="unavailable"&&!photos.readable(Uri.parse(m.uri))){
   synchronized(Store.photoLock){if(db.get(id)?.uri==m.uri)db.markUnavailable(id)}
   detail(id,back=back)
   return
  }
  val scroll=frame("记忆详情",back=true,nav=false,backAction=back)
  if(m.kind=="photo")shownPhoto=id
  val hero=FrameLayout(this).apply{background=shape(Color.rgb(222,238,247),20);layoutParams=LinearLayout.LayoutParams(-1,dp(215))}
  content.addView(hero)
  val cover=if(m.kind=="photo")m.takeIf{it.state!="unavailable"} else coverOf(m)
  if(cover!=null){
   val image=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP}
   hero.addView(image,FrameLayout.LayoutParams(-1,-1));loadImage(image,cover.uri)
  }else ImageView(this).apply{setImageResource(kindRes(m.kind));imageTintList=ColorStateList.valueOf(navy);setPadding(dp(78),dp(78),dp(78),dp(78));hero.addView(this,FrameLayout.LayoutParams(-1,-1))}
  gap(content,14);txt(content,m.title,20,true);gap(content,7);txt(content,displayDates(m)+" · "+sourceName(m),12,false,muted);gap(content,12)
  val tags=row(content);m.tags.split(Regex("[ ,，]+")).filter{it.isNotBlank()}.take(4).forEach{chip(tags,it)};if(m.tags.isBlank())chip(tags,kindName(m.kind))
  if(m.kind!="event"){gap(content,9);subtle(content,if(isUnsorted(m))"整理到合集" else "修改所属合集"){organize(m,back)}}
  if(evidence!=null&&m.state!="unavailable"){val match=card(content,14).apply{background=shape(pale,12)};txt(match,"✦  为什么匹配这次搜索",14,true);gap(match,8);txt(match,evidence,12,false,Color.rgb(71,105,167))}
  if(m.body.isNotBlank()){section(if(m.kind=="journal")"日记" else "保存的文字");val block=card(content,14).apply{background=shape(Color.WHITE,10,line)};txt(block,m.body,13,false,Color.rgb(63,88,124))}
  if(m.kind=="photo"){
   section("图像识别标签")
   val labelBlock=card(content,14).apply{background=shape(pale,10)}
   txt(labelBlock,if(m.state=="unavailable")"原图不可用，识别内容已隐藏" else m.labels.ifBlank{analysisStatus(m.labelState,"高置信度标签")},13,false,Color.rgb(63,88,124))
   section("识别出的文字")
   val textBlock=card(content,14).apply{background=shape(Color.WHITE,10,line)}
   txt(textBlock,if(m.state=="unavailable")"原图不可用，识别内容已隐藏" else m.ocr.ifBlank{analysisStatus(m.ocrState,"文字")},13,false,Color.rgb(63,88,124))
   subtle(content,"重新识别文字与画面"){consentOcr{ocr(id)}}
  }
  if(m.kind=="event"||m.kind=="photo"&&m.state!="unavailable") reminderButton(ReminderTarget("memory",id),m.title){detail(id,back=back)}
  if(m.kind=="photo"&&m.state!="unavailable")subtle(content,"手动指定地点"){assignPlace(m){detail(id,back=back)}}
  val relationPosition=content.childCount
  if(focusRelations&&m.kind=="event")txt(content,m.title,17,true)
  showRelations(m,back,focusRelations)
  if(focusRelations&&m.kind=="event"){
   val relation=content.getChildAt(relationPosition)
   relation?.post{scroll.scrollTo(0,relation.top)}
  }
  if(m.uri.isNotBlank()&&m.state!="unavailable")primary(content,"打开原始来源"){openOriginal(m)}
  if(m.kind=="photo"&&m.state=="unavailable")subtle(content,"重新选择照片"){pick()}
  if(m.kind=="link"&&m.titleSource.isNotBlank())txt(content,"标题来源：${m.titleSource}",12,false,muted)
  gap(content,8)
  subtle(content,"编辑标题、文字与标签"){edit(m.kind,m,after={detail(id,back=back)})}
  gap(content,7)
  subtle(content,if(m.hidden)"允许出现在回顾" else "从回顾中隐藏"){
   db.update(id,ContentValues().apply{put("hidden",if(m.hidden)0 else 1)})
   detail(id,back=back)
  }
  gap(content,7)
  txt(content,"移除这条记忆",13,true,red,Gravity.CENTER).apply{setPadding(dp(12),dp(14),dp(12),dp(14));setOnClickListener{confirmRemove(m)}}
 }

 private fun organize(m:Memory,back:()->Unit){
  val presets=listOf("旅行","购物","学习","工作","人物","重要")
  val current=m.tags.split(Regex("[ ,，]+" )).filter{it.isNotBlank()}.toSet()
  val form=col().apply{setPadding(dp(18),dp(4),dp(18),0)}
  txt(form,"可以多选；保存后这条内容就不再显示为“未整理”。",12,false,muted)
  gap(form,8)
  val checks=presets.map{name->CheckBox(this).apply{text=name;textSize=15f;setTextColor(navy);isChecked=name in current;form.addView(this)}}
  val custom=EditText(this).apply{hint="其他标签，用空格分隔";setText(current.filter{it !in presets}.joinToString(" "));setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(surface,10,line);form.addView(this,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(8),0,0)})}
  AlertDialog.Builder(this).setTitle("整理到合集").setView(form).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->
   val selected=checks.filter{it.isChecked}.map{it.text.toString()}+custom.text.toString().split(Regex("[ ,，]+" )).filter{it.isNotBlank()}
   db.update(m.id,ContentValues().apply{put("tags",selected.distinct().joinToString(" "))})
   toast(if(selected.isEmpty())"已保留为未整理" else "已整理到 ${selected.distinct().joinToString("、")}")
   detail(m.id,back=back)
  }.show()
 }

 private fun settings(){frame("设置与隐私",true,false);val local=card(content,16).apply{background=shape(pale,16)};txt(local,"保存在这台设备上",17,true);gap(local,6);txt(local,"DropZone 保存原图引用和搜索索引。原照片仍由系统相册管理。",13,false,muted);section("相册");settingRow("手动添加照片","只读取你明确选择的照片"){pick()};settingRow("索引整个相册",if(photos.albumActive())"已开启 · 全部照片" else if(photos.partialPermission())"仅部分授权 · 全库未开启" else "未开启"){enableAlbum()};if(prefs.getBoolean("album",false)){settingRow("刷新相册索引",albumProgress.ifBlank{"检查新增或失效的照片"}){scan(true)};settingRow("关闭全相册索引","移除自动登记的条目"){disableAlbum()}};section("本地识别");settingRow("照片文字与画面识别",if(prefs.getBoolean("ocr",false))"已开启" else "未开启"){if(prefs.getBoolean("ocr",false))disableOcr()else consentOcr{visibleItems().filter{it.kind=="photo"&&(it.ocr.isBlank()||it.labels.isBlank())}.forEach{ocr(it.id)};settings()}};settingRow("清除识别结果","保留照片引用"){clearOcr()};settingRow("周年回顾",if(prefs.getBoolean("review",true))"已开启" else "已关闭"){prefs.edit().putBoolean("review",!prefs.getBoolean("review",true)).apply();settings()};section("数据");settingRow("恢复排除的照片","下次扫描时重新加入"){db.writableDatabase.execSQL("DELETE FROM excluded");if(photos.albumActive())scan(true);toast("已恢复排除记录")};txt(content,"清空资料库",14,true,red).apply{setPadding(dp(12),dp(16),dp(12),dp(16));background=shape(Color.rgb(255,244,244),12);setOnClickListener{confirmClear()}}}
 private fun settingRow(title:String,sub:String,click:()->Unit){val r=row(content).apply{setPadding(dp(4),dp(14),dp(4),dp(14));setOnClickListener{click()}};val info=col(r).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,title,15,true);gap(info,5);txt(info,sub,12,false,muted);txt(r,"›",26,false,muted);content.addView(View(this).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,dp(1)))}

 private fun edit(kind:String,existing:Memory?=null,target:Memory?=null,after:(Long)->Unit={id->
  if(kind=="journal"||kind=="event")detail(id) else render()
 }){
  val form=col().apply{setPadding(dp(20),0,dp(20),0)}
  fun field(label:String,value:String)=EditText(this).apply{
   hint=label;setText(value);setPadding(dp(12),dp(12),dp(12),dp(12));background=shape(surface,10,line)
   form.addView(this,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(5),0,dp(5))})
  }
  val title=field("标题",existing?.title.orEmpty())
  val body=field(if(kind=="journal")"日记正文" else "保存的文字或备注",existing?.body.orEmpty())
  val uri=if(kind=="link")field("https://…",existing?.uri.orEmpty()) else null
  val datedKind=kind in listOf("photo","journal")
  val date=if(kind=="event")null else field(when(kind){"photo"->"拍摄日期（可留空）";"journal"->"日记日期 YYYY-MM-DD";else->"日期 YYYY-MM-DD"},
   when{
    existing!=null&&datedKind->existing.occurredDate
    existing!=null->existing.date
    else->target?.occurredDate?.takeIf{it.isNotBlank()}?:LocalDate.now().toString()
   })
  var eventStart=runCatching{LocalDate.parse(existing?.occurredDate.orEmpty())}.getOrNull()?:LocalDate.now()
  var eventEnd=runCatching{LocalDate.parse(existing?.eventEndDate.orEmpty())}.getOrNull()?.takeIf{it>=eventStart}
  if(kind=="event"){
   lateinit var startButton:TextView
   lateinit var endButton:TextView
   lateinit var clearEnd:TextView
   fun updateDates(){
    startButton.text="开始日期 · $eventStart"
    endButton.text=eventEnd?.let{"结束日期 · $it"}?:"结束日期（可选）"
    clearEnd.visibility=if(eventEnd==null)View.GONE else View.VISIBLE
   }
   startButton=subtle(form,"开始日期"){
    pickEventDate("开始日期",eventStart){chosen->
     eventStart=chosen
     if(eventEnd?.let{it<chosen}==true){
      eventEnd=null
      toast("请重新选择结束日期")
     }
     updateDates()
    }
   }
   gap(form,6)
   endButton=subtle(form,"结束日期"){
    pickEventDate("结束日期",eventEnd?:eventStart,eventStart){chosen->
     eventEnd=chosen
     updateDates()
    }
   }
   clearEnd=txt(form,"清除结束日期",13,true,blue).apply{
    setPadding(dp(10),dp(10),dp(10),dp(10))
    setOnClickListener{eventEnd=null;updateDates()}
   }
   updateDates()
  }
  val tags=field("标签，例如：旅行 北京",existing?.tags.orEmpty())
  var journalTarget=existing?.journalTarget?:target?.id
  if(kind=="journal"){
   fun targetLabel()=db.get(journalTarget?:-1)?.takeIf{photos.accessible(it)}?.title?:"仅关联日期"
   lateinit var targetButton:TextView
   targetButton=subtle(form,"关联：${targetLabel()}"){
    chooseJournalTarget(journalTarget){chosen->
     journalTarget=chosen
     targetButton.text="关联：${targetLabel()}"
    }
   }
  }
  val dialog=AlertDialog.Builder(this).setTitle("${if(existing==null)"新建" else "编辑"}${kindName(kind)}")
   .setView(ScrollView(this).apply{addView(form)})
   .setNegativeButton("取消",null).setPositiveButton("保存",null).create()
  dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
   if(title.text.isBlank()){title.error="请输入标题";return@setOnClickListener}
   val enteredDate=if(kind=="event")eventStart.toString() else date?.text?.toString()?.trim()?.ifBlank{null}
   if((enteredDate==null&&kind!="photo")||(enteredDate!=null&&runCatching{LocalDate.parse(enteredDate)}.isFailure)){
    date?.error="请使用 YYYY-MM-DD";return@setOnClickListener
   }
   val url=uri?.text?.toString()?.trim()
   if(uri!=null&&(url==null||Search.linkKey(url)==null)){uri.error="请输入 http 或 https 链接";return@setOnClickListener}
   val cleanTitle=title.text.toString().trim()
   val notes=body.text.toString()
   val userTags=tags.text.toString()
   val targetId=journalTarget?.takeIf{id->db.get(id)?.let{photos.accessible(it,includeUnavailable=false)}==true}
   val endDate=if(kind=="event")eventEnd?.toString() else null
   val id=when{
    existing!=null->existing.id.also{db.edit(it,kind,cleanTitle,notes,enteredDate,userTags,url,targetId,endDate)}
    url!=null->db.saveLink(cleanTitle,notes,url,enteredDate!!,"用户填写").also{id->
     if(userTags.isNotBlank())db.update(id,ContentValues().apply{put("tags",userTags)})
    }
    else->db.save(kind,cleanTitle,notes,date=enteredDate?:LocalDate.now().toString(),tags=userTags,journalTarget=targetId,eventEnd=endDate)
   }
   dialog.dismiss()
   after(id)
  }}
  dialog.show()
 }
 private fun pickEventDate(label:String,initial:LocalDate,minimum:LocalDate?=null,selected:(LocalDate)->Unit){
  val picker=DatePickerDialog(this,{_,year,month,day->selected(LocalDate.of(year,month+1,day))},
   initial.year,initial.monthValue-1,initial.dayOfMonth)
  if(minimum!=null)picker.datePicker.minDate=minimum.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
  picker.setTitle(label)
  picker.show()
 }
 private fun chooseJournalTarget(current: Long?, save: (Long?) -> Unit) {
  val items = accessibleMemories().filter { it.kind in listOf("photo","event") }.map(::pickOption)
  val choices = listOf(PickOption("date", "照片", "仅关联日记日期")) + items
  pickCards("关联照片、事件或日期",choices,setOf(current?.toString() ?: "date"),single=true) {
   save(it.single().toLongOrNull())
  }
 }

 private fun pick(){photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
 override fun onActivityResult(req:Int,result:Int,data:Intent?){
  super.onActivityResult(req,result,data)
  if(result!=RESULT_OK||data==null)return
  if(req==REQ_EXPORT){data.data?.let{destination->runCatching{val event=db.get(exportId)?:return;val value=buildString{appendLine(event.title);appendLine(event.dateLabel);appendLine(event.body);(db.members(event.id)+db.journalsFor(event.id)).distinctBy{it.id}.filter{photos.accessible(it)}.forEach{appendLine("\n${it.title}\n${it.body}\n${if(it.kind=="link")it.uri else ""}")}};contentResolver.openOutputStream(destination)?.bufferedWriter()?.use{it.write(value)}}.onSuccess{toast("已导出事件")}.onFailure{toast("导出失败")}}}}
 private fun receive(intent:Intent){
  if(intent.action==ReminderWorker.OPEN_ACTION){
   val segments=intent.data?.pathSegments.orEmpty()
   val target=extras.reminders().firstOrNull{it.target.kind==segments.getOrNull(0)&&it.target.id.toString()==segments.getOrNull(1)}?.target
   if(target!=null)openReminderTarget(target){reminderList()} else toast("提醒已取消或资料已移除")
   return
  }
  if(intent.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))return
  val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
  if(text.isNotBlank()){
   val url=Regex("https?://[^\\s<>]+",RegexOption.IGNORE_CASE).find(text)?.value
   val key=url?.let{Search.linkKey(it)}
   val subject=intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf{it.isNotBlank()}
   if(url!=null&&key!=null){
    db.saveLink(subject?:Uri.parse(url).host.orEmpty(),text,url,LocalDate.now().toString(),if(subject==null)"网址域名" else "分享时提供")
   }else db.save("text",subject?:text.take(50),text)
  }
  if(intent.type?.startsWith("image/")==true){
   @Suppress("DEPRECATION")
   val extra=if(intent.action==Intent.ACTION_SEND_MULTIPLE)intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
   val clip=intent.clipData?.let{data->(0 until data.itemCount).mapNotNull{data.getItemAt(it).uri}}.orEmpty()
   val images=(clip+extra).distinct()
   if(images.isEmpty())toast("请从系统照片库重新选择分享的照片") else importPhotos(images,true)
  }
  home()
 }
 private fun importPhotos(uris:List<Uri>,fromShare:Boolean){
  val cycle=prefs.getLong("library_cycle",0)
  importProgress="正在登记 ${uris.size} 张照片…"
  if(screen=="导入与处理")importPage()
  worker.execute{
   var saved=0
   for(uri in uris.distinct()){
    val id=synchronized(Store.photoLock){
     if(prefs.getLong("library_cycle",0)!=cycle)return@execute
     runCatching{photos.addManual(uri,fromShare)}.getOrNull()
    }?:continue
    saved++
    queuePending(id)
   }
   runOnUiThread{
    if(!alive)return@runOnUiThread
    val failures=uris.size-saved
    importProgress=if(failures==0)"已登记 $saved 张照片" else "已登记 $saved 张，$failures 张需从照片库重选"
    if(!fromShare||screen=="导入与处理")importPage() else toast(importProgress)
   }
  }
 }

 private fun chooseAttachments(event: Long, back: () -> Unit) {
  val choices = accessibleMemories().filter { it.kind != "event" }.map(::pickOption)
  val selected = db.members(event).mapTo(mutableSetOf()) { it.id.toString() }
  pickCards("选择关联资料", choices, selected) {
   db.setEventMembers(event,it.mapNotNull(String::toLongOrNull).toSet())
   detail(event,back=back)
  }
 }

 private fun chooseCover(event: Memory, back: () -> Unit) {
  val photosForCover = db.members(event.id).filter { it.kind == "photo" && photos.accessible(it) }.map(::pickOption)
  val choices = listOf(PickOption("none","照片","无封面")) + photosForCover
  pickCards("选择事件封面",choices,setOf(event.eventCover?.toString() ?: "none"),single=true) {
   db.setEventCover(event.id,it.single().toLongOrNull())
   detail(event.id,back=back)
  }
 }

 private fun coverOf(item:Memory,review:Boolean=false):Memory?{
  if(item.kind!="event")return null
  return item.eventCover?.let(db::get)?.takeIf{it.kind=="photo"&&photos.accessible(it)&&(!review||!it.hidden)}
 }
 private fun displayDates(item:Memory):String{
  val added=if(item.addedDate.isBlank())"加入日期未知" else "加入于 ${item.addedDate}"
  return when(item.kind){
   "photo"->if(item.occurredDate.isBlank())"拍摄日期未知 · $added" else "拍摄于 ${item.occurredDate} · $added"
   "event"->"发生于 ${item.dateLabel.ifBlank{"日期未知"}} · $added"
   "journal"->"日记日期 ${item.occurredDate.ifBlank{"未知"}} · $added"
   else->added
  }
 }
 private fun showRelations(item:Memory,back:()->Unit,focusRelations:Boolean){
  val returnHere={detail(item.id,back=back,focusRelations=focusRelations)}
  if(item.kind=="event"){
   val linked=eventLinks().contents(item.id)
   section("关联资料",linked.size.toString())
   val groups=linked.groupBy{it.kind}
   for(kind in listOf("photo","link","journal","text")){
    val entries=groups[kind]?:continue
    txt(content,kindName(kind),13,true)
    entries.forEach{memoryRow(it,back=returnHere)}
   }
   subtle(content,"管理关联资料"){chooseAttachments(item.id,back)}
   subtle(content,"设置封面"){chooseCover(item,back)}
   subtle(content,if(item.highlight)"取消年度代表事件" else "设为年度代表事件"){
    db.update(item.id,ContentValues().apply{put("highlight",if(item.highlight)0 else 1)})
    returnHere()
   }
   subtle(content,"导出事件文字与链接"){exportEvent(item)}
  }else{
   val events=db.eventsFor(item.id)
   if(events.isNotEmpty()){section("关联事件");events.forEach{memoryRow(it,back=returnHere)}}
   if(item.kind=="photo"){
    val journals=db.journalsFor(item.id)
    if(journals.isNotEmpty()){section("关联日记");journals.forEach{memoryRow(it,back=returnHere)}}
   }
  }
  if(item.kind=="journal"){
   val target=item.journalTarget?.let(db::get)?.takeIf{photos.accessible(it)}
   if(target==null)txt(content,"关联日期：${item.occurredDate}",12,false,muted)
   else{section("日记关联");memoryRow(target,back=returnHere)}
  }
  if(item.kind=="event"||item.kind=="photo"&&photos.accessible(item)){
   subtle(content,"＋ 写关联日记"){edit("journal",target=item,after={id->detail(id,back=returnHere)})}
  }
 }

 private fun exportEvent(m:Memory){exportId=m.id;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="text/plain";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"DropZone-${m.title}.txt")},REQ_EXPORT)}
 private fun openOriginal(m:Memory){if(!photos.accessible(m)||(m.kind=="photo"&&!photos.readable(Uri.parse(m.uri)))){toast("原始来源暂时无法打开");return};runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(m.uri)).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);if(m.kind=="photo")setDataAndType(Uri.parse(m.uri),"image/*")})}.onFailure{toast("原始来源暂时无法打开")}}
 private fun openCamera(){runCatching{startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))}.onFailure{toast("没有可用的相机应用")}}
 private fun shareHelp(){AlertDialog.Builder(this).setTitle("从其他应用保存").setMessage("在浏览器、相册或聊天中点“分享”，然后选择 DropZone。链接和文字会立即保存。照片若只有临时读取权限，会提示从系统照片库重选。").setPositiveButton("知道了",null).show()}
 private fun consentOcr(click:()->Unit){if(prefs.getBoolean("ocr",false)){click();return};AlertDialog.Builder(this).setTitle("开启本地照片识别").setMessage("DropZone 会在设备上识别照片中的文字、物体和场景，并保存为可搜索索引。照片不会上传到云端模型。").setNegativeButton("暂不开启",null).setPositiveButton("开启"){_,_->prefs.edit().putBoolean("ocr",true).apply();click()}.show()}
 private fun ocr(id:Long){if(db.get(id)==null||!prefs.getBoolean("ocr",false))return;OcrWorker.enqueue(this,id,prefs.getLong("epoch",0))}

 private fun enableAlbum(){
  AlertDialog.Builder(this).setTitle("索引整个相册？")
   .setMessage("会登记所有可访问的本机图片，包括敏感截图。只保存原图引用和搜索索引，可以随时关闭。")
   .setNegativeButton("取消",null).setPositiveButton("确认开启"){_,_->
    if(photos.fullPermission())startAlbum()
    else requestPermissions(if(Build.VERSION.SDK_INT>=34)arrayOf("android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
     else arrayOf(if(Build.VERSION.SDK_INT>=33)"android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"),REQ_PERMISSION)
   }.show()
 }
 private fun startAlbum(){
  prefs.edit().putBoolean("album",true).apply()
  scan(true)
 }
 private fun disableAlbum(){
  photos.disableAlbum()
  albumProgress=""
  toast("已关闭全相册索引")
  settings()
 }
 private fun queuePending(id:Long){
  val photo=db.get(id)?:return
  if(photo.ocrState=="pending"||photo.labelState=="pending")ocr(id)
 }
 private fun scan(forceFull:Boolean){
  if(!photos.albumActive())return
  if(albumScanning){if(forceFull)rescanRequested=true;return}
  albumScanning=true
  albumProgress="正在检查相册…"
  if(screen=="设置与隐私")settings()
  worker.execute{
   val result=runCatching{photos.scan(forceFull,{count->
    runOnUiThread{
     if(alive&&photos.albumActive()){
      albumProgress="已检查 $count 张照片"
      if(screen=="设置与隐私")settings()
     }
    }
   },::queuePending)}
   runOnUiThread{
    albumScanning=false
    if(!alive||!photos.albumActive())return@runOnUiThread
    if(rescanRequested){rescanRequested=false;scan(true);return@runOnUiThread}
    albumProgress=result.fold({"已检查 $it 张照片"},{"扫描未完成，可重新刷新"})
    when(screen){
     "设置与隐私"->settings()
     "已保存"->saved()
     "DropZone"->if(result.getOrDefault(0)>0)home()
    }
   }
  }
 }
 private fun reconcile(){
  val albumRevoked=prefs.getBoolean("album",false)&&!photos.fullPermission()
  if(albumRevoked)photos.disableAlbum()
  worker.execute{
   val changed=photos.reconcile(::queuePending)||albumRevoked
   runOnUiThread{
    if(!alive)return@runOnUiThread
    if(changed&&refreshReviewPage!=null)refreshReviewPage?.invoke()
    else if(changed){
     val photo=shownPhoto
     if(photo!=null){
      val back=currentBack?:{render()}
      if(db.get(photo)==null)back() else detail(photo,back=back)
     }else when(screen){
      "设置与隐私"->settings()
      "导入与处理"->importPage()
      "DropZone","记忆搜索","合集","已保存"->render()
      "记忆详情"->render()
     }
    }
    if(screen=="DropZone"&&homeDate!=LocalDate.now())home()
    if(photos.albumActive())scan(false)
   }
  }
 }
 override fun onRequestPermissionsResult(req:Int,p:Array<out String>,results:IntArray){
  super.onRequestPermissionsResult(req,p,results)
  if(req!=REQ_PERMISSION)return
  if(photos.fullPermission())startAlbum()
  else {
   toast(if(photos.partialPermission())"仅部分照片可访问，仍需手动选择要加入的照片" else "未获得全量权限，仍可手动添加照片")
   if(screen=="设置与隐私")settings()
  }
 }
 private fun disableOcr(){
  synchronized(Store.photoLock){
   prefs.edit().putBoolean("ocr",false).putLong("epoch",prefs.getLong("epoch",0)+1).apply()
   WorkManager.getInstance(this).cancelAllWorkByTag("ocr")
   db.writableDatabase.execSQL("UPDATE memory SET state='ready' WHERE kind='photo' AND state='processing'")
  }
  toast("已停止新的照片识别");settings()
 }
 private fun clearOcr(){
  synchronized(Store.photoLock){
   visualSearch.clear()
   prefs.edit().putLong("epoch",prefs.getLong("epoch",0)+1).apply()
   WorkManager.getInstance(this).cancelAllWorkByTag("ocr")
   db.clearAnalysis()
  }
  toast("已清除识别结果")
 }
 private fun confirmRemove(m:Memory){
  AlertDialog.Builder(this).setTitle("移除这条记忆？")
   .setMessage("会删除 DropZone 中的引用和识别结果，不会删除系统相册中的原图。")
   .setNegativeButton("取消",null).setPositiveButton("移除"){_,_->
    synchronized(Store.photoLock){
     val uri=db.get(m.id)?.manualUri.orEmpty()
     db.remove(m.id)
     ReminderWorker.cancel(this,ReminderTarget("memory",m.id))
     WorkManager.getInstance(this).cancelUniqueWork("ocr-${m.id}")
     photos.releaseUnused(uri)
    }
    render()
   }.show()
 }
 private fun confirmClear(){
  AlertDialog.Builder(this).setTitle("清空资料库？")
   .setMessage("会删除所有条目、日记、事件和识别结果。系统照片不会被删除。")
   .setNegativeButton("取消",null).setPositiveButton("清空"){_,_->
    synchronized(Store.photoLock){
     visualSearch.clear()
     val grants=db.all().map{it.manualUri}.filter{it.isNotBlank()}.distinct()
     photos.disableAlbum()
     prefs.edit().putLong("epoch",prefs.getLong("epoch",0)+1)
      .putLong("library_cycle",prefs.getLong("library_cycle",0)+1).apply()
     WorkManager.getInstance(this).cancelAllWorkByTag("ocr")
     ReminderWorker.cancelAll(this,extras.reminders())
     PhotoGroupingWorker.setEnabled(this,extras,PhotoGroupingWorker.FACE,false)
     PhotoGroupingWorker.setEnabled(this,extras,PhotoGroupingWorker.PLACE,false)
     db.clear()
     grants.forEach(photos::releaseUnused)
    }
    home()
   }.show()
 }

 private val extras by lazy { MemoryExtras(db) }
 private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
  if (granted) PhotoGroupingWorker.setEnabled(this, extras, PhotoGroupingWorker.PLACE, true)
  else toast("未授权读取照片位置，仍可手动指定地点")
  if (screen == "地点") groupedPhotos(PhotoGroupingWorker.PLACE)
 }
 private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
  if (!granted) toast("提醒已保存，可在应用内查看到期事项")
 }
 private var groupWork: androidx.lifecycle.LiveData<List<androidx.work.WorkInfo>>? = null

 private fun accessibleMemories(): List<Memory> = visibleItems().filter { photos.accessible(it) }

 private fun askName(title: String, initial: String = "", save: (String) -> Unit) {
  val input = EditText(this).apply { setSingleLine(); setText(initial); setSelectAllOnFocus(true) }
  val dialog = AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("取消", null)
   .setPositiveButton("保存", null).show()
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
   val name = input.text.toString().trim()
   if (name.isBlank()) input.error = "请输入名称"
   else { dialog.dismiss(); save(name) }
  }
 }

 private fun memorySands() {
  frame("记忆流沙", back=true, nav=false, backAction={collections()})
  txt(content,"把重要日期留在自己的主题里，未来可以倒数，过去可以回看。",14,false,muted)
  for ((id, name) in extras.themes()) {
   val tile = card(content)
   txt(tile, name, 17, true)
   txt(tile, "${extras.milestones(id).size} 个记忆标尺", 13, false, muted)
   tile.setOnClickListener { memoryTheme(id) }
  }
  gap(content, 8)
  primary(content, "＋ 新建主题合集") { askName("新建主题合集") { extras.saveTheme(it); memorySands() } }
 }

 private fun memoryTheme(id: Long) {
  val name = extras.themes().firstOrNull { it.first == id }?.second ?: return memorySands()
  frame(name, back=true, nav=false, backAction={memorySands()})
  for (mark in extras.milestones(id)) {
   val tile = card(content)
   txt(tile, mark.title, 17, true)
   txt(tile, "${mark.day} · ${milestoneDistance(mark.day)}", 13, false, muted)
   tile.setOnClickListener { showMilestone(mark.id) }
  }
  gap(content, 8)
  primary(content,"＋ 新建记忆标尺") { editMilestone(id) }
  gap(content, 8)
  subtle(content,"重命名主题") { askName("重命名主题", name) { extras.saveTheme(it, id); memoryTheme(id) } }
  gap(content, 8)
  subtle(content,"删除主题") {
   AlertDialog.Builder(this).setTitle("删除这个主题及其中的标尺？").setMessage("已有照片、事件和日记会保留。")
    .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ ->
     synchronized(Store.photoLock) {
      extras.milestones(id).forEach { ReminderWorker.cancel(this, ReminderTarget("milestone", it.id)) }
      extras.deleteTheme(id)
     }
     memorySands()
    }.show()
  }
 }

 private fun milestoneDistance(day: LocalDate): String {
  val delta = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), day)
  return when {
   delta > 0 -> "还有 ${delta} 天"
   delta < 0 -> "已过去 ${-delta} 天"
   else -> "就是今天"
  }
 }

 private fun editMilestone(theme: Long, mark: Milestone? = null) {
  frame(if (mark == null) "新建记忆标尺" else "编辑记忆标尺", true, false,
   backAction={if (mark == null) memoryTheme(theme) else showMilestone(mark.id)})
  val name = EditText(this).apply { hint="标题"; setSingleLine(); setText(mark?.title.orEmpty()); content.addView(this) }
  val body = EditText(this).apply { hint="这一天发生了什么？"; minLines=3; setText(mark?.body.orEmpty()); content.addView(this) }
  var day = mark?.day ?: LocalDate.now()
  gap(content, 8)
  val date = subtle(content, "日期 · $day") {
   pickEventDate("选择重要日期", day) { selected ->
    day = selected
    (content.findViewWithTag<TextView>("milestone-date"))?.text = "日期 · $day"
   }
  }
  date.tag = "milestone-date"
  gap(content, 8)
  primary(content, "保存记忆标尺") {
   val title = name.text.toString().trim()
   if (title.isBlank()) name.error = "请输入标题"
   else showMilestone(extras.saveMilestone(theme, mark?.id, title, day, body.text.toString().trim()))
  }
 }

 private fun showMilestone(id: Long) {
  val mark = extras.milestone(id) ?: return memorySands()
  frame("记忆标尺", true, false, backAction={memoryTheme(mark.theme)})
  val header = card(content)
  txt(header, mark.title, 21, true)
  txt(header, "${mark.day} · ${milestoneDistance(mark.day)}", 14, true, blue)
  if (mark.body.isNotBlank()) { gap(header,8); txt(header,mark.body,15) }
  reminderButton(ReminderTarget("milestone", id), mark.title, mark.day) { showMilestone(id) }
  section(if (mark.byDate) "当天拍摄" else "已关联的资料")
  refreshReviewPage={showMilestone(id)}
  val visible = accessibleMemories()
  val collections = extras.collectionLinks(id)
  if (collections.isNotEmpty()) {
   txt(content, "我的合集 · " + collections.joinToString("、"), 13, true, blue)
   txt(content, "合集里的资料更新时，这里也会更新。", 12, false, muted)
  }
  val collectionItems = visible.filter { item -> tagsOf(item).any { it in collections } }.mapTo(mutableSetOf()) { it.id }
  val items = extras.material(mark, visible, collectionItems)
  if (items.isEmpty()) txt(content, if (mark.byDate) "记忆库里还没有当天拍摄的照片" else "暂时没有可访问的关联资料", 13, false, muted)
  items.forEach { memoryRow(it, back={showMilestone(id)}) }
  gap(content, 8)
  subtle(content,"选择照片、事件或我的合集") { chooseMilestoneLinks(mark) }
  if (!mark.byDate) {
   gap(content, 8)
   subtle(content,"改为自动显示当天照片") { extras.setLinks(id, emptySet(), true); showMilestone(id) }
  }
  gap(content, 8)
  subtle(content,"编辑记忆标尺") { editMilestone(mark.theme, mark) }
  gap(content, 8)
  subtle(content,"删除记忆标尺") {
   AlertDialog.Builder(this).setTitle("删除这张记忆标尺？").setNegativeButton("取消",null)
    .setPositiveButton("删除") { _,_ ->
     synchronized(Store.photoLock) {
      extras.deleteMilestone(id)
      ReminderWorker.cancel(this, ReminderTarget("milestone", id))
     }
     memoryTheme(mark.theme)
    }.show()
  }
 }

 private fun chooseMilestoneLinks(mark: Milestone) {
  val available = accessibleMemories()
  val membership = available.groupBy { it.kind }
  val collectionOptions = myCollectionNames.map { name ->
   val count = available.count { name in tagsOf(it) }
   val cover = membership["photo"].orEmpty().firstOrNull { name in tagsOf(it) }
   PickOption("collection:$name","我的合集",name,cover,"$name · $count 项")
  }
  val options = available.map(::pickOption) + collectionOptions
  val selected = extras.links(mark.id).mapTo(mutableSetOf()) { it.toString() }
  selected.addAll(extras.collectionLinks(mark.id).map { "collection:$it" })
  pickCards("选择关联资料",options,selected) { chosen ->
   val ids = chosen.mapNotNull(String::toLongOrNull).toSet()
   val collections = myCollectionNames.filterTo(mutableSetOf()) { "collection:$it" in chosen }
   extras.setLinks(mark.id,ids,ids.isEmpty() && collections.isEmpty(),collections)
   showMilestone(mark.id)
  }
 }

 private data class PickOption(
  val key: String, val tab: String, val label: String, val photo: Memory? = null, val caption: String = label, val faceBounds: String? = null
 )

 private fun pickOption(item: Memory): PickOption {
  val category = when (item.kind) {
   "photo" -> "照片"
   "event" -> "事件"
   else -> "其他"
  }
  val photo = if (item.kind == "photo") item else coverOf(item)
  val caption = if (item.kind == "photo") item.occurredDate.ifBlank { "拍摄日期未知" } else item.title
  return PickOption(item.id.toString(), category, item.title, photo, caption)
 }

 private fun pickCards(title: String, options: List<PickOption>, selected: Set<String>,
                       single: Boolean = false, save: (Set<String>) -> Unit) {
  val chosen = selected.toMutableSet()
  val panel = col().apply { setPadding(dp(16), dp(10), dp(16), dp(6)) }
  val pages = options.groupBy { it.tab }
  val tabs = row()
  if (pages.size > 1) panel.addView(HorizontalScrollView(this).apply {
   isHorizontalScrollBarEnabled = false
   addView(tabs)
  })
  val grid = GridView(this).apply {
   numColumns = 3
   verticalSpacing = dp(8)
   horizontalSpacing = dp(8)
   layoutParams = LinearLayout.LayoutParams(-1, minOf(dp(450), (resources.displayMetrics.heightPixels * .55f).toInt()))
  }
  panel.addView(grid)
  val dialog = AlertDialog.Builder(this).setTitle(title).setView(panel)
   .setNegativeButton("取消",null).apply {
    if (!single) setPositiveButton("保存") { _,_ -> save(chosen) }
   }.create()

  fun show(page: String) {
   tabs.removeAllViews()
   for (name in pages.keys) chip(tabs, name, name == page) { show(name) }
   val entries = pages[page].orEmpty()
   grid.adapter = object : BaseAdapter() {
    override fun getCount() = entries.size
    override fun getItem(position: Int) = entries[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
     val option = getItem(position)
     val cell = recycled as? LinearLayout ?: col().apply {
      layoutParams = AbsListView.LayoutParams(-1, dp(116))
      val preview = FrameLayout(this@MainActivity)
      addView(preview, LinearLayout.LayoutParams(-1, dp(89)))
      preview.addView(ImageView(this@MainActivity).apply {
       scaleType = ImageView.ScaleType.CENTER_CROP
      }, FrameLayout.LayoutParams(-1,-1))
      val check = TextView(this@MainActivity).apply {
       text = "✓"
       gravity = Gravity.CENTER
       setTextColor(Color.WHITE)
       background = shape(blue, 12)
      }
      preview.addView(check, FrameLayout.LayoutParams(dp(24),dp(24),Gravity.TOP or Gravity.END))
      txt(this,"",11,false,muted,Gravity.CENTER).apply {
       maxLines=1
       ellipsize=android.text.TextUtils.TruncateAt.END
      }
     }
     val preview = cell.getChildAt(0) as FrameLayout
     val image = preview.getChildAt(0) as ImageView
     preview.background = shape(pale, 10)
     preview.clipToOutline = true
     image.tag = null
     image.setImageDrawable(null)
     if (option.photo != null) {
      image.setPadding(0,0,0,0)
      image.clearColorFilter()
      if (option.faceBounds == null) loadImage(image, option.photo.uri, dp(180))
      else loadFaceCrop(image,option.photo,option.faceBounds)
     } else {
      image.setPadding(dp(24),dp(24),dp(24),dp(24))
      image.scaleType = ImageView.ScaleType.FIT_CENTER
      image.setImageResource(R.drawable.ic_collection)
      image.setColorFilter(muted)
     }
     if (option.photo != null) image.scaleType = ImageView.ScaleType.CENTER_CROP
     (cell.getChildAt(1) as TextView).text = option.caption
     val checked = option.key in chosen
     preview.getChildAt(1).visibility = if (checked) View.VISIBLE else View.INVISIBLE
     cell.contentDescription = option.label + if (checked) "，已选择" else ""
     cell.setOnClickListener {
      if (single) {
       dialog.dismiss()
       save(setOf(option.key))
      } else {
       if (!chosen.add(option.key)) chosen.remove(option.key)
       notifyDataSetChanged()
      }
     }
     return cell
    }
   }
  }

  if (pages.isEmpty()) txt(panel,"没有可选择的资料",14,false,muted)
  show(options.firstOrNull { single && it.key in chosen }?.tab ?: pages.keys.firstOrNull().orEmpty())
  dialog.show()
 }

 private fun showFaceCover(parent: LinearLayout, groupId: Long, visible: Map<Long,Memory>, size: Int) {
  val cover = extras.faceCovers(groupId).firstOrNull { it.item in visible }
  val source = cover?.let { visible[it.item] }
  val image = ImageView(this).apply {
   contentDescription = if (cover == null) "暂无可用的人脸封面" else "该分组的人脸封面"
   scaleType = ImageView.ScaleType.CENTER_CROP
   background = shape(pale, size / 2)
   clipToOutline = true
   layoutParams = LinearLayout.LayoutParams(dp(size),dp(size)).apply { marginEnd = dp(12) }
   setImageResource(R.drawable.ic_profile)
   setColorFilter(muted)
   setPadding(dp(size/4),dp(size/4),dp(size/4),dp(size/4))
  }
  parent.addView(image)
  if (cover == null || source == null) return
  loadFaceCrop(image,source,cover.bounds)
 }

 private fun loadFaceCrop(image: ImageView, source: Memory, encodedBounds: String) {
  val bounds = encodedBounds.split(',').mapNotNull(String::toFloatOrNull)
  if (bounds.size != 4 || bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) return
  val request = Any()
  image.tag = request
  worker.execute {
   if (image.tag != request) return@execute
   val crop = runCatching {
    val original = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, Uri.parse(source.uri))) { decoder, info, _ ->
     val sample = (maxOf(info.size.width, info.size.height) + 1599) / 1600
     decoder.setTargetSampleSize(sample)
     decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    try {
     val centerX = (bounds[0] + bounds[2]) * original.width / 2
     val centerY = (bounds[1] + bounds[3]) * original.height / 2
     val length = (maxOf((bounds[2] - bounds[0]) * original.width, (bounds[3] - bounds[1]) * original.height) * 1.25f)
      .toInt().coerceIn(1, minOf(original.width, original.height))
     val left = (centerX - length / 2f).toInt().coerceIn(0, original.width - length)
     val top = (centerY - length / 2f).toInt().coerceIn(0, original.height - length)
     val edge = minOf(length, 256)
     Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).apply {
      Canvas(this).drawBitmap(original, Rect(left, top, left + length, top + length),
       Rect(0, 0, edge, edge), Paint(Paint.FILTER_BITMAP_FLAG))
     }
    } finally { original.recycle() }
   }.getOrNull()
   runOnUiThread {
    if (crop != null) {
     if (!alive || image.tag != request || !image.isAttachedToWindow || !PhotoGroupingWorker.enabled(this,PhotoGroupingWorker.FACE) || !photos.accessible(source)) crop.recycle()
     else {
      image.clearColorFilter()
      image.setPadding(0,0,0,0)
      image.setImageBitmap(crop)
     }
    }
   }
  }
 }

 private fun groupOption(kind: String, group: PhotoGroup, visible: Map<Long,Memory>): PickOption {
  val cover = if (kind == PhotoGroupingWorker.FACE) extras.faceCovers(group.id).firstOrNull { it.item in visible } else null
  val photo = if (kind == PhotoGroupingWorker.FACE) cover?.let { visible[it.item] }
   else extras.members(group.id).firstNotNullOfOrNull { visible[it] }
  return PickOption(group.id.toString(),"分组",groupName(kind,group),photo,faceBounds=cover?.bounds)
 }

 private fun reminderButton(target: ReminderTarget, name: String, date: LocalDate? = null, refresh: () -> Unit) {
  val reminder = extras.reminder(target)
  gap(content, 8)
  subtle(content, if (reminder == null) "设提醒" else "管理提醒 · ${reminderTime(reminder)}") {
   val choose = { pickReminderTime(target, date, refresh) }
   if (reminder == null) choose()
   else AlertDialog.Builder(this).setTitle("$name · ${reminderTime(reminder)}")
    .setItems(arrayOf("改期", "标记完成", "取消提醒")) { _, index ->
     when (index) {
      0 -> choose()
      1 -> {
       synchronized(Store.photoLock) {
        extras.setReminderState(reminder,"done")
        ReminderWorker.cancel(this,target)
       }
       refresh()
      }
      2 -> {
       synchronized(Store.photoLock) {
        extras.deleteReminder(target)
        ReminderWorker.cancel(this,target)
       }
       refresh()
      }
     }
    }.show()
  }
 }

 private fun reminderTime(reminder: Reminder): String {
  val date = java.time.Instant.ofEpochMilli(reminder.time).atZone(ZoneId.systemDefault())
  val value = date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
  val state = when (reminder.state) {
   "done" -> "已完成"
   "sent" -> "已通知"
   "missed" -> "未送达"
   else -> if (reminder.time <= System.currentTimeMillis()) "已到期" else "待提醒"
  }
  return "$value · $state"
 }

 private fun ocrReminderSuggestion(text: String): java.time.LocalDateTime? {
  val pattern = Regex("""(?<!\d)(20\d{2})\s*[年./-]\s*(\d{1,2})\s*[月./-]\s*(\d{1,2})\s*日?(?:[ \t]+(\d{1,2})[:：](\d{2}))?(?!\d)""")
  val now = java.time.LocalDateTime.now()
  return pattern.findAll(java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC)).mapNotNull { match ->
   val (year,month,day,hour,minute) = match.destructured
   runCatching {
    LocalDate.of(year.toInt(),month.toInt(),day.toInt()).atTime(hour.toIntOrNull() ?: 9,minute.toIntOrNull() ?: 0)
   }.getOrNull()
  }.firstOrNull { it.isAfter(now) }
 }


 private fun pickReminderTime(target: ReminderTarget, date: LocalDate?, refresh: () -> Unit) {
  val now = java.time.ZonedDateTime.now()
  val previous = extras.reminder(target)?.let { java.time.Instant.ofEpochMilli(it.time).atZone(now.zone) }
  val suggestion = if (previous == null && target.kind == "memory") {
   db.get(target.id)?.takeIf { it.kind == "photo" && photos.accessible(it) }?.let { ocrReminderSuggestion(it.ocr) }
  } else null
  val initial = (previous?.toLocalDate() ?: suggestion?.toLocalDate() ?: date ?: now.toLocalDate()).coerceAtLeast(now.toLocalDate())
  pickEventDate(if(suggestion==null) "提醒日期" else "请确认识别出的提醒日期", initial, now.toLocalDate()) { day ->
   val time = previous ?: suggestion?.atZone(now.zone) ?: now.plusMinutes(5)
   TimePickerDialog(this, { _,hour,minute ->
    val trigger = day.atTime(hour,minute).atZone(now.zone).toInstant().toEpochMilli()
    if (trigger <= System.currentTimeMillis()) toast("请选择未来的时间")
    else {
     ReminderWorker.schedule(this, extras, target, trigger)
     if (Build.VERSION.SDK_INT >= 33 && !ReminderWorker.notificationsAllowed(this))
      notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
     refresh()
    }
   },time.hour,time.minute,true).apply { setTitle("提醒时间"); show() }
  }
 }

 private fun reminderList() {
  frame("记忆提醒", true, false, backAction={collections()})
  val reminders = extras.reminders().filter { reminderTitle(it.target) != null }
  if (reminders.isEmpty()) txt(content,"还没有提醒。可从照片、合集或标尺详情设置。",14,false,muted)
  for (reminder in reminders) {
   val tile = card(content)
   txt(tile, reminderTitle(reminder.target).orEmpty(), 16, true)
   txt(tile, reminderTime(reminder), 13, false, muted)
   tile.setOnClickListener { openReminderTarget(reminder.target) { reminderList() } }
  }
 }

 private fun reminderTitle(target: ReminderTarget): String? = when (target.kind) {
  "milestone" -> extras.milestone(target.id)?.title
  "collection" -> target.collection.takeIf { it in smartCollectionNames || it in myCollectionNames }
  "memory" -> db.get(target.id)?.takeIf { photos.accessible(it) }?.title
  else -> null
 }

 private fun openReminderTarget(target: ReminderTarget, back: () -> Unit) {
  if (reminderTitle(target) == null) { toast("提醒关联的资料已不可用"); return }
  when (target.kind) {
   "milestone" -> showMilestone(target.id)
   "collection" -> showCollection(target.collection)
   "memory" -> detail(target.id, back=back)
  }
 }

 private fun groupedPhotos(kind: String) {
  val places = kind == PhotoGroupingWorker.PLACE
  val title = if (places) "地点" else "同人分组"
  frame(title,true,false,backAction={if(places) collections() else showCollection("人物")})
  refreshReviewPage={groupedPhotos(kind)}
  val enabled = PhotoGroupingWorker.enabled(this,kind)
  txt(content, if (places) "根据照片自带的拍摄坐标在本机分组，也可以在照片详情手动指定地点。" else
   "在本机检测脸部并建议同人分组。可命名、合并分组或调整误分的照片。",14,false,muted)
  gap(content,8)
  subtle(content,if (enabled) "关闭并清除自动分组" else if (places) "开启拍摄地点归类" else "开启同人分组") {
   if (enabled) {
    PhotoGroupingWorker.setEnabled(this,extras,kind,false)
    groupedPhotos(kind)
   } else AlertDialog.Builder(this).setTitle(if (places) "允许读取照片的拍摄地点？" else "允许在设备上分析照片中的人脸？")
    .setMessage(if (places) "仅分析已加入且仍有访问权的照片。地点数据只保存在本机。" else
     "仅分析已加入且仍有访问权的照片。脸部特征只保存在本机，可随时清除。")
    .setNegativeButton("取消",null).setPositiveButton("开启") { _,_ ->
     if (places && !PhotoGroupingWorker.hasLocation(this)) locationPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
     else {
      PhotoGroupingWorker.setEnabled(this,extras,kind,true)
      groupedPhotos(kind)
     }
    }.show()
  }
  if (places) txt(content,"城市名为离线估算 · 地名数据来自 GeoNames",12,false,muted)
  if (enabled) {
   val status = txt(content,"",13,false,muted)
   val manager = WorkManager.getInstance(this)
   val work = manager.getWorkInfosForUniqueWorkLiveData(PhotoGroupingWorker.key(kind))
   groupWork = work
   val list = col(content)
   fun fillGroups() {
    list.removeAllViews()
    val available = accessibleMemories().filter { it.kind=="photo" }.associateBy { it.id }
    for (group in extras.groups(kind)) {
     val members = extras.members(group.id).filter { it in available }
     if (members.isNotEmpty()) {
      val tile = card(list)
       val info = if (places) tile else {
        val line = row(tile)
        showFaceCover(line,group.id,available,66)
        col(line)
       }
       txt(info,groupName(kind,group),16,true)
       txt(info,"${members.size} 张照片",13,false,muted)
      tile.setOnClickListener { showPhotoGroup(kind,group.id) }
     }
    }
    if (list.childCount == 0) txt(list,"暂时没有可显示的分组",13,false,muted)
   }
   fillGroups()
   work.observe(this) { records ->
    val active = records.firstOrNull { !it.state.isFinished }
    val count = active?.progress?.getInt("total",0) ?: 0
    status.text = when {
     count > 0 -> "本机整理中 · ${active?.progress?.getInt("done",0)} / $count"
     active != null -> "等待本机整理…"
     records.firstOrNull()?.state == androidx.work.WorkInfo.State.FAILED -> "本次整理未完成，可重新整理"
     else -> "已更新"
    }
    if (active == null) fillGroups()
   }
   gap(content,8)
   subtle(content,"整理新照片") { PhotoGroupingWorker.enqueue(this,kind) }
  } else if (places) {
   val available = accessibleMemories().mapTo(mutableSetOf()) { it.id }
   for (group in extras.groups(kind)) {
    val count = extras.members(group.id).count { it in available }
    if (count > 0) subtle(content,"${groupName(kind,group)} · $count 张") { showPhotoGroup(kind,group.id) }
   }
  }
 }

 private fun groupName(kind: String, group: PhotoGroup): String {
  if (group.name.isNotBlank()) return group.name
  if (kind != PhotoGroupingWorker.PLACE) return "未命名人物"
  val lat = group.latitude ?: return "未命名地点"
  val lon = group.longitude ?: return "未命名地点"
  return offlinePlaces.nearbyCity(lat,lon)?.plus("附近")
   ?: String.format(java.util.Locale.ROOT, "拍摄地 %.3f°, %.3f°", lat, lon)
 }

 private fun showPhotoGroup(kind: String,id: Long) {
  val groups = extras.groups(kind)
  val group = groups.firstOrNull { it.id == id } ?: return groupedPhotos(kind)
  if (kind == PhotoGroupingWorker.FACE && !PhotoGroupingWorker.enabled(this,kind)) return groupedPhotos(kind)
  val name = groupName(kind,group)
  frame(name,true,false,backAction={groupedPhotos(kind)})
  refreshReviewPage={showPhotoGroup(kind,id)}
  val members = extras.members(id)
  val available = accessibleMemories().filter { it.kind=="photo" }.associateBy { it.id }
  val items = available.values.filter { it.id in members }
  if (kind == PhotoGroupingWorker.FACE) {
   val header = row(content)
   showFaceCover(header,id,items.associateBy { it.id },86)
   txt(header,"该人物的照片",16,true)
  }
  for (item in items) memoryRow(item,back={showPhotoGroup(kind,id)})
  gap(content,8)
  subtle(content,"重命名分组") { askName("重命名分组",name) { extras.renameGroup(id,it);showPhotoGroup(kind,id) } }
  gap(content,8)
  if (items.isNotEmpty()) subtle(content,"调整误分照片") {
   pickCards("选择要调整的照片",items.map(::pickOption),emptySet(),single=true) { chosen ->
    val item = items.first { it.id.toString() in chosen }
    val destinations = groups.filter { it.id != id }
    val choices = listOf(PickOption("remove","分组","移出分组"),PickOption("split","分组","拆到新分组")) +
     destinations.map { groupOption(kind,it,available) }
    pickCards("选择移动目标",choices,emptySet(),single=true) { selection ->
     when(val option = selection.single()) {
      "remove" -> { extras.moveMember(id,item.id,null);showPhotoGroup(kind,id) }
      "split" -> askName("新分组名称") { extras.moveMember(id,item.id,extras.createGroup(kind,it));showPhotoGroup(kind,id) }
      else -> { extras.moveMember(id,item.id,option.toLong());showPhotoGroup(kind,id) }
     }
    }
   }
  }
  val otherGroups = groups.filter { it.id != id && extras.members(it.id).isNotEmpty() }
  if (otherGroups.isNotEmpty()) {
   gap(content,8)
   subtle(content,"合并到其他分组") {
    pickCards("选择目标分组",otherGroups.map { groupOption(kind,it,available) },emptySet(),single=true) { chosen ->
     val destination = chosen.single().toLong()
     extras.mergeGroups(id,destination)
     showPhotoGroup(kind,destination)
    }
   }
  }
 }

 private fun assignPlace(item: Memory, back: () -> Unit) {
  val kind = PhotoGroupingWorker.PLACE
  val groups = extras.groups(kind)
  val choices = listOf("新建地点","移出地点分组") + groups.map { groupName(kind,it) }
  AlertDialog.Builder(this).setTitle("手动指定地点").setItems(choices.toTypedArray()) { _, index ->
   if (index == 0) askName("地点名称") { name ->
    extras.setPlace(item.id,extras.createGroup(kind,name));back()
   } else {
    extras.setPlace(item.id,if(index == 1) null else groups[index-2].id)
    back()
   }
  }.show()
 }

 private fun schedulePhotoGrouping() {
  for (kind in listOf(PhotoGroupingWorker.PLACE,PhotoGroupingWorker.FACE)) PhotoGroupingWorker.enqueue(this,kind)
 }


 private fun isUnsorted(m:Memory)=m.kind!="event"&&m.tags.isBlank()
 private fun tagsOf(item: Memory) = item.tags.split(Regex("[ ,，]+")).filter(String::isNotBlank)

 private fun collectionItems(name:String):List<Memory>{
  val terms=when(name){
   "人物"->listOf("people","person","human","woman","man","girl","boy","portrait","model","face","child","baby","fashion","clothing","dress","jeans","swimwear","beauty")
   "风景"->listOf("scenery","landscape","sky","beach","sea","ocean","flower","plant","field","mountain","sunset","nature","vacation")
   "建筑"->listOf("building","architecture","church","cathedral","tower","house","landmark","city","skyline")
   "动物"->listOf("animal","bird","insect","butterfly","dog","cat","fish","wing")
   "美食"->listOf("food","dish","dessert","bread","ingredient","tableware","cup","saucer","cookie","drink")
   else->emptyList()
  }
  return visibleItems().filter{m->name in tagsOf(m)||when(name){"文字图片"->m.kind=="photo"&&m.ocr.isNotBlank();in smartCollectionNames->m.kind=="photo"&&terms.any{db.normalize(m.labels+" "+m.labelsEn).contains(it)};else->false}}
 }
 private fun kindName(k:String)=mapOf("photo" to "照片","link" to "链接","text" to "文字","journal" to "日记","event" to "事件")[k]?:k
 private fun kindRes(k:String)=when(k){"photo"->R.drawable.ic_photo;"link"->R.drawable.ic_link;else->R.drawable.ic_note}
 private fun collectionIcon(n:String)=mapOf("人物" to "人","风景" to "景","建筑" to "建","动物" to "物","美食" to "食","文字图片" to "字","旅行" to "旅","购物" to "购","学习" to "学","工作" to "工","重要" to "★")[n]?:"集"
 private fun sourceName(m:Memory)=when(m.kind){
  "photo"->when{
   m.manualUri.isNotEmpty()&&m.albumUri.isNotEmpty()->"手选＋相册索引"
   m.albumUri.isNotEmpty()->"相册索引"
   else->"手动添加"
  }
  "link"->"分享链接"
  "journal"->"记忆日记"
  "event"->"事件合集"
  else->"手动保存"
 }
 private fun analysisStatus(state:String,subject:String)=when(state){
  "pending"->"待识别"
  "failed"->"识别失败，可重试"
  else->"未识别到$subject"
 }
 private fun summary(m:Memory)=when{m.state=="processing"->"正在识别文字与画面…";m.state=="partial"->"部分结果可搜索";m.state=="failed"->"识别失败，点按查看并重试";m.state=="unavailable"->"原始照片暂不可用";m.body.isNotBlank()->m.body.take(60);m.ocr.isNotBlank()->m.ocr.take(60);m.labels.isNotBlank()->m.labels.take(60);m.uri.isNotBlank()&&m.kind=="link"->m.uri.take(60);else->m.date}
 private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
 companion object{const val REQ_EXPORT=11;const val REQ_PERMISSION=20}
}
