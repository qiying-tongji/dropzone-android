package com.bitocean.dropzone

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : Activity() {
 private lateinit var db: Store
 private val prefs by lazy { getSharedPreferences("settings",0) }
 private val worker=Executors.newSingleThreadExecutor()
 private val navy=Color.rgb(24,54,95); private val blue=Color.rgb(79,121,232)
 private val muted=Color.rgb(123,145,176); private val line=Color.rgb(231,237,246)
 private val surface=Color.rgb(247,249,252); private val pale=Color.rgb(238,245,255)
 private val green=Color.rgb(31,153,108); private val red=Color.rgb(205,75,82)
 private lateinit var root:LinearLayout; private lateinit var content:LinearLayout
 private var tab="首页"; private var query=""; private var filter="全部"; private var exportId=0L; private var alive=true
 private var currentBack:(()->Unit)?=null
 private val smartCollectionNames=setOf("人物","风景","建筑","动物","美食","文字图片")

 override fun onCreate(s:Bundle?){super.onCreate(s);db=Store(this);db.localizeLabels();window.statusBarColor=surface;window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;home();receive(intent)}
 override fun onNewIntent(i:Intent){super.onNewIntent(i);receive(i)}
 override fun onResume(){super.onResume();if(::db.isInitialized)reconcile()}
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

 private fun frame(title:String,back:Boolean=false,nav:Boolean=true,profile:(()->Unit)?=null,backAction:(()->Unit)?=null){
  currentBack=if(back)(backAction?:{render()})else null
  root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),statusBarHeight()+dp(6),dp(18),0);setBackgroundColor(surface)};setContentView(root)
  val top=row(root).apply{setPadding(dp(4),dp(4),dp(4),dp(14))}
  if(back)icon(top,R.drawable.ic_back,24,navy,currentBack).apply{contentDescription="返回";setPadding(dp(11),dp(11),dp(11),dp(11));layoutParams=LinearLayout.LayoutParams(dp(48),dp(48));isClickable=true;isFocusable=true}
  txt(top,title,21,true).apply{layoutParams=LinearLayout.LayoutParams(0,dp(48),1f);gravity=Gravity.CENTER_VERTICAL}
  if(profile!=null)icon(top,R.drawable.ic_profile,22,blue,profile).apply{contentDescription="设置与隐私";setPadding(dp(11),dp(11),dp(11),dp(11));background=shape(pale,24);layoutParams=LinearLayout.LayoutParams(dp(48),dp(48))}
  val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false};root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));content=col();content.setPadding(0,0,0,dp(24));scroll.addView(content)
  if(nav)bottomNav()
 }
 private fun bottomNav(){val n=row(root).apply{setPadding(0,dp(7),0,dp(7));background=shape(Color.WHITE,0,line)};listOf(Triple(R.drawable.ic_home,"首页","首页"),Triple(R.drawable.ic_search,"搜索","搜索"),Triple(R.drawable.ic_collection,"合集","合集"),Triple(R.drawable.ic_heart,"已保存","已保存")).forEach{(res,name,dest)->val item=col(n).apply{gravity=Gravity.CENTER;setPadding(0,dp(3),0,dp(3));setOnClickListener{tab=dest;render()}};icon(item,res,21,if(tab==dest)blue else muted);gap(item,3);txt(item,name,10,tab==dest,if(tab==dest)blue else muted,Gravity.CENTER);item.layoutParams=LinearLayout.LayoutParams(0,-2,1f)}}
 private fun render(){when(tab){"首页"->home();"搜索"->search();"合集"->collections();else->saved()}}

 private fun home(){tab="首页";frame("DropZone",profile={settings()})
  val search=row(content).apply{setPadding(dp(14),dp(12),dp(14),dp(12));background=shape(pale,24);setOnClickListener{tab="搜索";search()}}
  icon(search,R.drawable.ic_search,20,blue);txt(search,"搜索你的记忆…",14,false,muted).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(9)}};gap(content,11)
  val actions=row(content);quick(actions,R.drawable.ic_add,"导入"){importPage()};quick(actions,R.drawable.ic_camera,"相机"){openCamera()};quick(actions,R.drawable.ic_share,"分享"){shareHelp()};quick(actions,R.drawable.ic_menu,"更多"){importPage()}
  val unsorted=db.all().filter{isUnsorted(it)}
  section("未整理",unsorted.size.toString()){tab="已保存";filter="未整理";saved()}
  if(unsorted.isNotEmpty())txt(content,"点开一条内容，选择“整理到合集”即可完成整理。",12,false,muted)
  val recent=unsorted.take(4)
  if(recent.isEmpty())empty("这里已经整理好了","新保存且没有标签的内容会出现在这里。"){importPage()} else recent.forEach{memoryRow(it)}
  section("智能合集",null){tab="合集";collections()};val cs=row(content);collectionTile(cs,"人物",collectionItems("人物").size,Color.rgb(232,240,255));collectionTile(cs,"风景",collectionItems("风景").size,Color.rgb(235,246,243));collectionTile(cs,"建筑",collectionItems("建筑").size,Color.rgb(255,242,230))
  val today=LocalDate.now();val old=db.all().filter{!it.hidden&&it.kind in listOf("event","journal")&&runCatching{val d=LocalDate.parse(it.date);d.year<today.year&&d.month==today.month&&d.dayOfMonth==today.dayOfMonth}.getOrDefault(false)}
  if(prefs.getBoolean("review",true)&&old.isNotEmpty()){section("那年今日",old.size.toString());old.take(2).forEach{memoryRow(it)}}
 }
 private fun quick(p:LinearLayout,res:Int,name:String,click:()->Unit){val t=col(p).apply{gravity=Gravity.CENTER;setPadding(dp(4),dp(11),dp(4),dp(10));background=shape(pale,14);setOnClickListener{click()};layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(3),0,dp(3),0)}};icon(t,res,23,navy);gap(t,7);txt(t,name,11,true,navy,Gravity.CENTER)}
 private fun section(title:String,count:String?=null,click:(()->Unit)?=null){val r=row(content).apply{setPadding(dp(2),dp(20),dp(2),dp(8))};txt(r,title,19,true);if(count!=null)chip(r,count);r.addView(Space(this),LinearLayout.LayoutParams(0,1,1f));if(click!=null)txt(r,"查看全部",12,true).apply{setOnClickListener{click()}}}
 private fun collectionTile(p:LinearLayout,name:String,count:Int,color:Int){val t=col(p).apply{setPadding(dp(12),dp(14),dp(12),dp(14));background=shape(color,12);layoutParams=LinearLayout.LayoutParams(0,dp(82),1f).apply{setMargins(dp(3),0,dp(3),0)};setOnClickListener{showCollection(name)}};txt(t,name,13,true);gap(t,8);txt(t,"$count 项",11,false,muted)}
 private fun empty(title:String,sub:String,click:()->Unit){val b=card(content,18).apply{background=shape(surface,16)};txt(b,title,16,true);gap(b,5);txt(b,sub,13,false,muted);gap(b,12);subtle(b,"开始添加"){click()}}

 private fun memoryRow(m:Memory,evidence:String?=null,parent:ViewGroup=content){
  val r=row(parent).apply{setPadding(0,dp(10),0,dp(10));setOnClickListener{detail(m.id,evidence)}}
  val preview=FrameLayout(this).apply{background=shape(pale,10);layoutParams=LinearLayout.LayoutParams(dp(74),dp(74))};r.addView(preview)
  if(m.kind=="photo"&&m.state!="unavailable"){val image=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setPadding(dp(5),dp(5),dp(5),dp(5))};preview.addView(image,FrameLayout.LayoutParams(-1,-1));loadImage(image,m.uri)}else ImageView(this).apply{setImageResource(kindRes(m.kind));imageTintList=ColorStateList.valueOf(navy);setPadding(dp(22),dp(22),dp(22),dp(22));preview.addView(this,FrameLayout.LayoutParams(-1,-1))}
  val info=col(r).apply{setPadding(dp(12),dp(1),0,0);layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,m.title,15,true);gap(info,5);txt(info,"${kindName(m.kind)} · ${sourceName(m)}",11,false,muted);gap(info,4);txt(info,evidence?:summary(m),12,false,if(evidence==null)muted else blue)
  if(isUnsorted(m)){gap(info,5);txt(info,"未整理 · 点开整理",10,true,blue).apply{setPadding(dp(7),dp(3),dp(7),dp(3));background=shape(pale,8);layoutParams=LinearLayout.LayoutParams(-2,-2)}}
  parent.addView(View(this).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,dp(1)))
 }
 private fun loadImage(v:ImageView,uri:String){worker.execute{val b=runCatching{val s=ImageDecoder.createSource(contentResolver,Uri.parse(uri));ImageDecoder.decodeBitmap(s){d,i,_->val f=maxOf(i.size.width,i.size.height)/800f;if(f>1)d.setTargetSize((i.size.width/f).toInt(),(i.size.height/f).toInt())}}.getOrNull();runOnUiThread{b?.let{v.setImageBitmap(it)}}}}

 private fun search(){tab="搜索";frame("记忆搜索")
  val bar=row(content).apply{setPadding(dp(12),dp(4),dp(7),dp(4));background=shape(pale,22)};icon(bar,R.drawable.ic_search,20,navy);val input=EditText(this).apply{hint="例如：蓝色背包 500 元";setText(query);textSize=14f;setTextColor(navy);setHintTextColor(muted);background=null;setSingleLine();imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH;setOnEditorActionListener{_,_,_->query=text.toString().trim();search();true}};bar.addView(input,LinearLayout.LayoutParams(0,dp(44),1f));txt(bar,"搜索",12,true,blue,Gravity.CENTER).apply{setPadding(dp(10),0,dp(10),0);setOnClickListener{query=input.text.toString().trim();search()}};gap(content,13)
  val sc=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false};val fs=row();listOf("全部","照片","链接","日记","事件").forEach{name->chip(fs,name,filter==name){filter=name;query=input.text.toString().trim();search()}};sc.addView(fs);content.addView(sc);gap(content,12)
  if(query.isBlank()){val h=card(content,18).apply{background=shape(surface,16)};txt(h,"试着描述你记得的线索",17,true);gap(h,8);txt(h,"可以混合地点、时间、文字和用途：",13,false,muted);gap(h,10);listOf("上周的演出票根","朋友推荐的餐厅","北京日落照片").forEach{x->subtle(h,"⌕  $x"){query=x;search()};gap(h,7)};return}
  val matches=db.all().filter{filter=="全部"||kindName(it.kind)==filter}.mapNotNull{m->db.evidence(m,query)?.let{m to it}};val meta=row(content);txt(meta,"${matches.size} 个结果",13,false,muted).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(meta,"最相关",12)
  if(matches.isEmpty()){gap(content,16);empty("没有找到对应记忆","换一个更短的词，或检查照片是否已完成识别。"){importPage()}} else matches.forEach{(m,e)->val box=card(content,10);memoryRow(m,parent=box);val why=row(box).apply{setPadding(dp(11),dp(10),dp(11),dp(10));background=shape(pale,10);setOnClickListener{detail(m.id,e)}};txt(why,"✦",14,true,blue);txt(why,e.take(110),11,false,Color.rgb(71,105,167)).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(6)}}}
 }

 private fun saved(){tab="已保存";frame("已保存");val actions=row(content);subtle(actions,"＋ 添加"){importPage()}.apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f)};actions.addView(Space(this),LinearLayout.LayoutParams(dp(8),1));subtle(actions,if(filter=="未整理")"显示全部" else "只看未整理"){filter=if(filter=="未整理")"全部" else "未整理";saved()}.apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f)};val ms=db.all().filter{if(filter=="未整理")isUnsorted(it) else it.kind!="event"};section(if(filter=="未整理")"未整理" else "全部记忆",ms.size.toString());if(filter=="未整理"&&ms.isNotEmpty())txt(content,"点开内容后使用“整理到合集”，添加一个或多个合集标签。",12,false,muted);if(ms.isEmpty())empty(if(filter=="未整理")"没有待整理内容" else "这里还没有内容",if(filter=="未整理")"所有内容都已有合集标签，或已进入智能合集。" else "添加照片、截图、链接或文字，稍后都能搜索。"){importPage()}else ms.forEach{memoryRow(it)}}

 private fun collections(){tab="合集";frame("合集");val intro=card(content,16).apply{background=shape(pale,16)};txt(intro,"识别后自动归类，也可以手动整理",17,true);gap(intro,6);txt(intro,"智能合集来自照片识别；我的合集来自你为内容选择的标签。",13,false,muted);gap(intro,12);subtle(intro,"查看未整理内容"){tab="已保存";filter="未整理";saved()};section("智能合集");listOf("人物","风景","建筑","动物","美食","文字图片").forEach{name->collectionListRow(name,collectionItems(name).size,"由本地识别自动归类")};section("我的合集");listOf("旅行","购物","学习","工作","重要").forEach{name->collectionListRow(name,collectionItems(name).size,"手动整理的内容")};section("事件与日记");val es=db.all().filter{it.kind in listOf("event","journal")};if(es.isEmpty())txt(content,"创建事件或写下日记，让照片、链接和你的话回到同一段经历。",13,false,muted);es.forEach{memoryRow(it)};gap(content,10);subtle(content,"＋ 新建事件合集"){edit("event")};gap(content,7);subtle(content,"写一篇日记"){edit("journal")}}
 private fun collectionListRow(name:String,count:Int,source:String){val box=card(content,16).apply{setOnClickListener{showCollection(name)}};val r=row(box);txt(r,collectionIcon(name),25,false,blue,Gravity.CENTER).apply{setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(pale,12)};val info=col(r).apply{setPadding(dp(12),0,0,0);layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,name,16,true);gap(info,5);txt(info,"$count 项 · $source",12,false,muted);txt(r,"›",28,false,muted)}
 private fun showCollection(name:String){val ms=collectionItems(name);frame(name,back=true,nav=false,backAction={collections()});txt(content,"${ms.size} 项记忆",13,false,muted);gap(content,10);if(ms.isEmpty())empty("这个合集还是空的",if(name in smartCollectionNames)"识别到对应人物、场景或物体后会自动出现在这里。" else "打开一条未整理内容，选择“整理到合集”。"){tab="已保存";filter="未整理";saved()}else ms.forEach{memoryRow(it)}}

 private fun importPage(){frame("导入与处理",true,false);txt(content,"先保存，识别可以稍后完成。",13,false,muted);gap(content,14);importChoice(R.drawable.ic_add,"添加照片或截图","从系统照片库选择，可一次添加多张"){pick()};importChoice(R.drawable.ic_link,"保存公开链接","粘贴或从其他应用分享网址"){edit("link")};importChoice(R.drawable.ic_note,"保存一段文字","记下当时的想法或线索"){edit("text")};section("最近导入");val rs=db.all().filter{it.kind !in listOf("event","journal")}.take(6);if(rs.isEmpty())txt(content,"还没有导入记录。",13,false,muted);rs.forEach{processingRow(it)};gap(content,8);primary(content,"查看所有已保存内容"){tab="已保存";saved()}}
 private fun importChoice(res:Int,title:String,sub:String,click:()->Unit){val b=row(content).apply{setPadding(dp(14),dp(14),dp(14),dp(14));background=shape(if(title.startsWith("添加"))pale else Color.WHITE,14,line);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(5),0,dp(5))};setOnClickListener{click()}};val holder=FrameLayout(this).apply{background=shape(Color.rgb(225,236,255),25);layoutParams=LinearLayout.LayoutParams(dp(50),dp(50))};b.addView(holder);ImageView(this).apply{setImageResource(res);imageTintList=ColorStateList.valueOf(navy);setPadding(dp(13),dp(13),dp(13),dp(13));holder.addView(this,FrameLayout.LayoutParams(-1,-1))};val info=col(b).apply{setPadding(dp(13),0,0,0);layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,title,14,true);gap(info,6);txt(info,sub,11,false,muted)}
 private fun processingRow(m:Memory){val r=row(content).apply{setPadding(0,dp(11),0,dp(11));setOnClickListener{detail(m.id,back={importPage()})}};icon(r,kindRes(m.kind),25,navy).apply{setPadding(dp(15),dp(15),dp(15),dp(15));background=shape(pale,9);layoutParams=LinearLayout.LayoutParams(dp(58),dp(58))};val info=col(r).apply{setPadding(dp(12),0,0,0);layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,m.title,14,true);gap(info,4);val status=when(m.state){"processing"->"正在识别";"failed"->"需要重试";"unavailable"->"原件不可用";else->"已就绪"};txt(info,status,12,true,when(m.state){"failed","unavailable"->red;"processing"->Color.rgb(218,137,35);else->green});txt(info,when(m.state){"processing"->"正在识别文字与画面";"failed"->"识别未完成，原件仍保留";"unavailable"->"需要重新选择原照片";else->"已经可以搜索"},11,false,muted);if(m.state=="failed")subtle(r,"重试"){consentOcr{ocr(m.id)}};content.addView(View(this).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,dp(1)))}

 private fun detail(id:Long,evidence:String?=null,back:()->Unit={render()}){
  val m=db.get(id)?:return
  frame("记忆详情",back=true,nav=false,backAction=back)
  val hero=FrameLayout(this).apply{background=shape(Color.rgb(222,238,247),20);layoutParams=LinearLayout.LayoutParams(-1,dp(215))}
  content.addView(hero)
  if(m.kind=="photo"&&m.state!="unavailable"){
   val image=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP}
   hero.addView(image,FrameLayout.LayoutParams(-1,-1));loadImage(image,m.uri)
  }else ImageView(this).apply{setImageResource(kindRes(m.kind));imageTintList=ColorStateList.valueOf(navy);setPadding(dp(78),dp(78),dp(78),dp(78));hero.addView(this,FrameLayout.LayoutParams(-1,-1))}
  gap(content,14);txt(content,m.title,20,true);gap(content,7);txt(content,"保存于 ${m.date} · ${sourceName(m)}",12,false,muted);gap(content,12)
  val tags=row(content);m.tags.split(Regex("[ ,，]+")).filter{it.isNotBlank()}.take(4).forEach{chip(tags,it)};if(m.tags.isBlank())chip(tags,kindName(m.kind))
  if(m.kind!="event"){gap(content,9);subtle(content,if(isUnsorted(m))"整理到合集" else "修改所属合集"){organize(m,back)}}
  if(evidence!=null){val match=card(content,14).apply{background=shape(pale,12)};txt(match,"✦  为什么匹配这次搜索",14,true);gap(match,8);txt(match,evidence,12,false,Color.rgb(71,105,167))}
  if(m.body.isNotBlank()){section(if(m.kind=="journal")"日记" else "保存的文字");val block=card(content,14).apply{background=shape(Color.WHITE,10,line)};txt(block,m.body,13,false,Color.rgb(63,88,124))}
  if(m.kind=="photo"){
   section("图像识别标签")
   val labelBlock=card(content,14).apply{background=shape(pale,10)}
   txt(labelBlock,if(m.state=="unavailable")"原图不可用，识别内容已隐藏" else m.labels.ifBlank{"暂未识别到高置信度标签"},13,false,Color.rgb(63,88,124))
   section("识别出的文字")
   val textBlock=card(content,14).apply{background=shape(Color.WHITE,10,line)}
   txt(textBlock,if(m.state=="unavailable")"原图不可用，识别内容已隐藏" else m.ocr.ifBlank{"暂未识别到文字"},13,false,Color.rgb(63,88,124))
   subtle(content,"重新识别文字与画面"){consentOcr{ocr(id)}}
  }
  if(m.kind=="event"){section("关联资料",db.members(id).size.toString());db.members(id).forEach{memoryRow(it)};subtle(content,"＋ 关联已保存资料"){chooseAttachments(id)};gap(content,7);subtle(content,"导出事件文字与链接"){exportEvent(m)}}
  if(m.uri.isNotBlank())primary(content,"打开原始来源"){openOriginal(m)};gap(content,8);subtle(content,"编辑标题、文字与标签"){edit(m.kind,m)};gap(content,7)
  if(m.kind in listOf("event","journal")){subtle(content,if(m.hidden)"允许出现在回顾" else "从回顾中隐藏"){db.update(id,ContentValues().apply{put("hidden",if(m.hidden)0 else 1)});detail(id,back=back)};gap(content,7)}
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

 private fun settings(){frame("设置与隐私",true,false);val local=card(content,16).apply{background=shape(pale,16)};txt(local,"保存在这台设备上",17,true);gap(local,6);txt(local,"DropZone 保存原图引用和搜索索引。原照片仍由系统相册管理。",13,false,muted);section("相册");settingRow("手动添加照片","只读取你明确选择的照片"){pick()};settingRow("索引整个相册",if(prefs.getBoolean("album",false))"已开启" else "未开启"){enableAlbum()};if(prefs.getBoolean("album",false)){settingRow("刷新相册索引","检查新增或失效的照片"){scan()};settingRow("关闭全相册索引","移除自动登记的条目"){disableAlbum()}};section("本地识别");settingRow("照片文字与画面识别",if(prefs.getBoolean("ocr",false))"已开启" else "未开启"){if(prefs.getBoolean("ocr",false))disableOcr()else consentOcr{db.all().filter{it.kind=="photo"&&(it.ocr.isBlank()||it.labels.isBlank())}.forEach{ocr(it.id)};settings()}};settingRow("清除识别结果","保留照片引用"){clearOcr()};settingRow("周年回顾",if(prefs.getBoolean("review",true))"已开启" else "已关闭"){prefs.edit().putBoolean("review",!prefs.getBoolean("review",true)).apply();settings()};section("数据");settingRow("恢复排除的照片","下次扫描时重新加入"){db.writableDatabase.execSQL("DELETE FROM excluded");toast("已恢复排除记录")};txt(content,"清空资料库",14,true,red).apply{setPadding(dp(12),dp(16),dp(12),dp(16));background=shape(Color.rgb(255,244,244),12);setOnClickListener{confirmClear()}}}
 private fun settingRow(title:String,sub:String,click:()->Unit){val r=row(content).apply{setPadding(dp(4),dp(14),dp(4),dp(14));setOnClickListener{click()}};val info=col(r).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f)};txt(info,title,15,true);gap(info,5);txt(info,sub,12,false,muted);txt(r,"›",26,false,muted);content.addView(View(this).apply{setBackgroundColor(line)},LinearLayout.LayoutParams(-1,dp(1)))}

 private fun edit(kind:String,existing:Memory?=null){
  val form=col().apply{setPadding(dp(20),0,dp(20),0)}
  fun field(h:String,v:String)=EditText(this).apply{hint=h;setText(v);setPadding(dp(12),dp(12),dp(12),dp(12));background=shape(surface,10,line);form.addView(this,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(5),0,dp(5))})}
  val title=field("标题",existing?.title?:"");val body=field("保存的文字或备注",existing?.body?:"");val uri=if(kind=="link")field("https://…",existing?.uri?:"")else null;val date=field("日期 YYYY-MM-DD",existing?.date?:LocalDate.now().toString());val tags=field("标签，例如：旅行 北京",existing?.tags?:"")
  val dialog=AlertDialog.Builder(this).setTitle("${if(existing==null)"新建" else "编辑"}${kindName(kind)}").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
  dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(title.text.isBlank()){title.error="请输入标题";return@setOnClickListener};if(runCatching{LocalDate.parse(date.text)}.isFailure){date.error="请使用 YYYY-MM-DD";return@setOnClickListener};if(uri!=null&&Uri.parse(uri.text.toString()).scheme !in listOf("http","https")){uri.error="请输入 http 或 https 链接";return@setOnClickListener};if(existing==null)db.save(kind,title.text.toString(),body.text.toString(),uri?.text?.toString()?:"",date.text.toString(),tags.text.toString())else db.update(existing.id,ContentValues().apply{put("title",title.text.toString());put("body",body.text.toString());put("date",date.text.toString());put("tags",tags.text.toString());uri?.let{put("uri",it.text.toString())}});dialog.dismiss();render()}}
  dialog.show()
 }
 private fun pick(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQ_PICK)}
 override fun onActivityResult(req:Int,result:Int,data:Intent?){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null)return;if(req==REQ_PICK){val uris=if(data.clipData!=null)(0 until data.clipData!!.itemCount).map{data.clipData!!.getItemAt(it).uri}else listOfNotNull(data.data);uris.forEach{u->runCatching{contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);contentResolver.openInputStream(u)?.close()?:error("unreadable");val id=db.save("photo","照片 ${LocalDate.now()}",uri=u.toString());if(prefs.getBoolean("ocr",false))ocr(id)}.onFailure{toast("无法长期读取这张照片，请重新选择")}};importPage()}else if(req==REQ_EXPORT){data.data?.let{destination->runCatching{val event=db.get(exportId)?:return;val value=buildString{appendLine(event.title);appendLine(event.date);appendLine(event.body);db.members(event.id).forEach{appendLine("\n${it.title}\n${it.body}\n${if(it.kind=="link")it.uri else ""}")}};contentResolver.openOutputStream(destination)?.bufferedWriter()?.use{it.write(value)}}.onSuccess{toast("已导出事件")}.onFailure{toast("导出失败")}}}}
 private fun receive(i:Intent){if(i.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))return;val s=i.getStringExtra(Intent.EXTRA_TEXT);if(!s.isNullOrBlank()){val url=Regex("https?://[^\\s]+").find(s)?.value?:"";db.save(if(url.isEmpty())"text" else "link",i.getStringExtra(Intent.EXTRA_SUBJECT)?:s.take(50),s,url)};if(i.type?.startsWith("image/")==true)toast("为长期保存，请从照片库重新选择分享的图片");home()}
 private fun chooseAttachments(event:Long){val choices=db.all().filter{it.kind!="event"};AlertDialog.Builder(this).setTitle("选择关联资料").setMultiChoiceItems(choices.map{it.title}.toTypedArray(),null){_,index,checked->if(checked)db.attach(event,choices[index].id)else db.detach(event,choices[index].id)}.setPositiveButton("完成"){_,_->detail(event)}.show()}
 private fun exportEvent(m:Memory){exportId=m.id;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="text/plain";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"DropZone-${m.title}.txt")},REQ_EXPORT)}
 private fun openOriginal(m:Memory){runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(m.uri)).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);if(m.kind=="photo")setDataAndType(Uri.parse(m.uri),"image/*")})}.onFailure{toast("原始来源暂时无法打开")}}
 private fun openCamera(){runCatching{startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))}.onFailure{toast("没有可用的相机应用")}}
 private fun shareHelp(){AlertDialog.Builder(this).setTitle("从其他应用保存").setMessage("在浏览器、相册或聊天中点“分享”，然后选择 DropZone。链接和文字会立即保存；照片需要再从系统照片库确认一次，以便长期访问。").setPositiveButton("知道了",null).show()}
 private fun consentOcr(click:()->Unit){if(prefs.getBoolean("ocr",false)){click();return};AlertDialog.Builder(this).setTitle("开启本地照片识别").setMessage("DropZone 会在设备上识别照片中的文字、物体和场景，并保存为可搜索索引。照片不会上传到云端模型。").setNegativeButton("暂不开启",null).setPositiveButton("开启"){_,_->prefs.edit().putBoolean("ocr",true).apply();click()}.show()}
 private fun ocr(id:Long){if(db.get(id)==null||!prefs.getBoolean("ocr",false))return;OcrWorker.enqueue(this,id,prefs.getLong("epoch",0))}

 private fun fullPermission()=checkSelfPermission(if(Build.VERSION.SDK_INT>=33)"android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE")==PackageManager.PERMISSION_GRANTED
 private fun enableAlbum(){AlertDialog.Builder(this).setTitle("索引整个相册？").setMessage("会登记所有可访问的本机图片，包括敏感截图。只保存原图引用和搜索索引，可以随时关闭。").setNegativeButton("取消",null).setPositiveButton("确认开启"){_,_->if(fullPermission()){prefs.edit().putBoolean("album",true).apply();scan()}else requestPermissions(if(Build.VERSION.SDK_INT>=34)arrayOf("android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VISUAL_USER_SELECTED")else arrayOf(if(Build.VERSION.SDK_INT>=33)"android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"),REQ_PERMISSION)}.show()}
 private fun disableAlbum(){prefs.edit().putBoolean("album",false).apply();db.all().filter{it.automatic}.forEach{db.remove(it.id,false)};toast("已关闭全相册索引");settings()}
 private fun scan(){if(!prefs.getBoolean("album",false)||!fullPermission())return;toast("正在检查相册…");worker.execute{var count=0;runCatching{contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.DATE_TAKEN),null,null,null)?.use{c->while(c.moveToNext()&&prefs.getBoolean("album",false)&&fullPermission()){val uri=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,c.getLong(0)).toString();if(!db.excluded(uri)){val millis=c.getLong(2);val date=if(millis>0)java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString() else LocalDate.now().toString();val id=db.save("photo",c.getString(1)?:"照片",uri=uri,date=date,automatic=true);val saved=db.get(id);if(prefs.getBoolean("ocr",false)&&(saved?.ocr.isNullOrBlank()||saved?.labels.isNullOrBlank()))ocr(id);count++}}}};runOnUiThread{if(alive){toast("已检查 $count 张照片");settings()}}}}
 private fun reconcile(){if(prefs.getBoolean("album",false)&&!fullPermission()){prefs.edit().putBoolean("album",false).putLong("epoch",System.currentTimeMillis()).apply();db.all().filter{it.automatic}.forEach{db.remove(it.id,false)}};worker.execute{db.all().filter{it.kind=="photo"}.forEach{m->val readable=runCatching{contentResolver.openInputStream(Uri.parse(m.uri))?.use{true}?:false}.getOrDefault(false);if(!readable)db.update(m.id,ContentValues().apply{put("state","unavailable");put("ocr","");put("labels","");put("labels_en","")})}}}
 override fun onRequestPermissionsResult(req:Int,p:Array<out String>,results:IntArray){super.onRequestPermissionsResult(req,p,results);if(req==REQ_PERMISSION&&fullPermission()){prefs.edit().putBoolean("album",true).apply();scan()}else if(req==REQ_PERMISSION)toast("未获得全量权限，仍可手动添加照片")}
 private fun disableOcr(){prefs.edit().putBoolean("ocr",false).putLong("epoch",System.currentTimeMillis()).apply();WorkManager.getInstance(this).cancelAllWorkByTag("ocr");toast("已停止新的照片识别");settings()}
 private fun clearOcr(){prefs.edit().putLong("epoch",System.currentTimeMillis()).apply();WorkManager.getInstance(this).cancelAllWorkByTag("ocr");db.writableDatabase.execSQL("UPDATE memory SET ocr='',labels='',labels_en='',state='ready' WHERE kind='photo'");toast("已清除识别结果")}
 private fun confirmRemove(m:Memory){AlertDialog.Builder(this).setTitle("移除这条记忆？").setMessage("会删除 DropZone 中的引用和识别文字，不会删除系统相册中的原图。").setNegativeButton("取消",null).setPositiveButton("移除"){_,_->db.remove(m.id);render()}.show()}
 private fun confirmClear(){AlertDialog.Builder(this).setTitle("清空资料库？").setMessage("会删除所有条目、日记、事件和识别文字。系统照片不会被删除。").setNegativeButton("取消",null).setPositiveButton("清空"){_,_->prefs.edit().putBoolean("album",false).putLong("epoch",System.currentTimeMillis()).apply();WorkManager.getInstance(this).cancelAllWorkByTag("ocr");db.clear();home()}.show()}

 private fun isUnsorted(m:Memory)=!m.automatic&&m.kind!="event"&&m.tags.isBlank()
 private fun collectionItems(name:String):List<Memory>{
  val terms=when(name){
   "人物"->listOf("people","person","human","woman","man","girl","boy","portrait","model","face","child","baby","fashion","clothing","dress","jeans","swimwear","beauty")
   "风景"->listOf("scenery","landscape","sky","beach","sea","ocean","flower","plant","field","mountain","sunset","nature","vacation")
   "建筑"->listOf("building","architecture","church","cathedral","tower","house","landmark","city","skyline")
   "动物"->listOf("animal","bird","insect","butterfly","dog","cat","fish","wing")
   "美食"->listOf("food","dish","dessert","bread","ingredient","tableware","cup","saucer","cookie","drink")
   else->emptyList()
  }
  return db.all().filter{m->m.tags.split(Regex("[ ,，]+" )).any{it==name}||when(name){"文字图片"->m.kind=="photo"&&m.ocr.isNotBlank();in smartCollectionNames->m.kind=="photo"&&terms.any{db.normalize(m.labels+" "+m.labelsEn).contains(it)};else->false}}
 }
 private fun kindName(k:String)=mapOf("photo" to "照片","link" to "链接","text" to "文字","journal" to "日记","event" to "事件")[k]?:k
 private fun kindRes(k:String)=when(k){"photo"->R.drawable.ic_photo;"link"->R.drawable.ic_link;else->R.drawable.ic_note}
 private fun collectionIcon(n:String)=mapOf("人物" to "人","风景" to "景","建筑" to "建","动物" to "物","美食" to "食","文字图片" to "字","旅行" to "旅","购物" to "购","学习" to "学","工作" to "工","重要" to "★")[n]?:"集"
 private fun sourceName(m:Memory)=if(m.automatic)"系统相册" else when(m.kind){"photo"->"照片库";"link"->"分享链接";"journal"->"记忆日记";"event"->"事件合集";else->"手动保存"}
 private fun summary(m:Memory)=when{m.state=="processing"->"正在识别文字与画面…";m.state=="failed"->"识别失败，点按查看并重试";m.state=="unavailable"->"原始照片暂不可用";m.body.isNotBlank()->m.body.take(60);m.ocr.isNotBlank()->m.ocr.take(60);m.labels.isNotBlank()->m.labels.take(60);m.uri.isNotBlank()&&m.kind=="link"->m.uri.take(60);else->m.date}
 private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
 companion object{const val REQ_PICK=10;const val REQ_EXPORT=11;const val REQ_PERMISSION=20}
}
