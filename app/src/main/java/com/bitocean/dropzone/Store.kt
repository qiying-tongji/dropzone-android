package com.bitocean.dropzone

import android.content.*
import android.database.sqlite.*
import java.text.Normalizer
import java.time.LocalDate

data class Memory(val id:Long,val kind:String,val title:String,val body:String,val uri:String,val date:String,val tags:String,val ocr:String,val state:String,val automatic:Boolean,val hidden:Boolean,val labels:String,val labelsEn:String)
class Store(context:Context): SQLiteOpenHelper(context,"memories.db",null,3) {
 override fun onCreate(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE memory(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT,title TEXT,body TEXT,uri TEXT,date TEXT,tags TEXT,ocr TEXT DEFAULT '',state TEXT DEFAULT 'ready',automatic INTEGER DEFAULT 0,hidden INTEGER DEFAULT 0,labels TEXT DEFAULT '',labels_en TEXT DEFAULT '')")
  db.execSQL("CREATE UNIQUE INDEX source ON memory(uri) WHERE uri != '' AND kind='photo'")
  db.execSQL("CREATE TABLE attachment(event INTEGER,item INTEGER,PRIMARY KEY(event,item))")
  db.execSQL("CREATE TABLE excluded(uri TEXT PRIMARY KEY)")
 }
 override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int) { if(old<2) db.execSQL("ALTER TABLE memory ADD COLUMN labels TEXT DEFAULT ''");if(old<3){db.execSQL("ALTER TABLE memory ADD COLUMN labels_en TEXT DEFAULT ''");db.execSQL("UPDATE memory SET labels_en=labels WHERE labels!=''")} }
 fun all():List<Memory> = readableDatabase.rawQuery("SELECT * FROM memory ORDER BY id DESC",null).use { c -> buildList { while(c.moveToNext()) add(Memory(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8),c.getInt(9)==1,c.getInt(10)==1,c.getString(11),c.getString(12))) } }
 fun get(id:Long)=all().find{it.id==id}
 fun save(kind:String,title:String,body:String="",uri:String="",date:String=LocalDate.now().toString(),tags:String="",automatic:Boolean=false):Long {
  if(kind=="photo") all().find{it.uri==uri}?.let { if(!automatic) update(it.id,ContentValues().apply{put("automatic",0)}); return it.id }
  return writableDatabase.insertOrThrow("memory",null,ContentValues().apply { put("kind",kind);put("title",title);put("body",body);put("uri",uri);put("date",date);put("tags",tags);put("automatic",if(automatic)1 else 0) })
 }
 fun update(id:Long,v:ContentValues) {writableDatabase.update("memory",v,"id=?",arrayOf(id.toString()))}
 fun remove(id:Long,exclude:Boolean=true) { writableDatabase.beginTransaction();try {get(id)?.let { if(exclude&&it.kind=="photo") writableDatabase.execSQL("INSERT OR IGNORE INTO excluded VALUES(?)",arrayOf(it.uri)) }; writableDatabase.delete("memory","id=?",arrayOf(id.toString()));writableDatabase.delete("attachment","event=? OR item=?",arrayOf(id.toString(),id.toString()));writableDatabase.setTransactionSuccessful()}finally{writableDatabase.endTransaction()} }
 fun excluded(uri:String)=readableDatabase.rawQuery("SELECT 1 FROM excluded WHERE uri=?",arrayOf(uri)).use{it.moveToFirst()}
 fun attach(event:Long,item:Long){writableDatabase.execSQL("INSERT OR IGNORE INTO attachment VALUES(?,?)",arrayOf(event,item))}
 fun detach(event:Long,item:Long){writableDatabase.execSQL("DELETE FROM attachment WHERE event=? AND item=?",arrayOf(event,item))}
 fun members(event:Long):List<Memory> = readableDatabase.rawQuery("SELECT item FROM attachment WHERE event=?",arrayOf(event.toString())).use{c->buildList{while(c.moveToNext())get(c.getLong(0))?.let{add(it)}}}
 fun clear(){writableDatabase.execSQL("DELETE FROM attachment");writableDatabase.execSQL("DELETE FROM memory");writableDatabase.execSQL("DELETE FROM excluded")}
 fun localizeLabels(){all().filter{it.labelsEn.isNotBlank()}.forEach{m->val localized=LabelTranslator.localizeLine(m.labelsEn);if(m.labels!=localized)update(m.id,ContentValues().apply{put("labels",localized)})}}
 fun normalize(s:String)=Normalizer.normalize(s,Normalizer.Form.NFKC).lowercase()
 fun searchTerms(token:String):List<String> = when(normalize(token)) {
  "people","person","human","人物","人像","人" -> listOf("people","person","human","woman","man","girl","boy","portrait","model","face","child","baby","fashion","clothing","dress","jeans","swimwear","beauty")
  "building","architecture","建筑","建筑物" -> listOf("building","architecture","church","cathedral","tower","house","landmark")
  "food","美食","食物","吃的" -> listOf("food","dish","dessert","bread","ingredient","tableware","cup","saucer","cookie","drink")
  "animal","animals","动物" -> listOf("animal","bird","insect","butterfly","dog","cat","fish","wing")
  "scenery","landscape","风景","景色" -> listOf("scenery","landscape","sky","beach","sea","ocean","flower","plant","field","mountain","sunset","nature")
  else -> listOf(normalize(token))
 }
 fun evidence(m:Memory,q:String):String? {
  var input=normalize(q)
  val now=LocalDate.now()
  var range:Pair<LocalDate,LocalDate>?=null
  listOf("今天","昨天","上周","本周","上个月").firstOrNull{input.contains(it)}?.let{word->
   range=when(word){"昨天"->now.minusDays(1) to now.minusDays(1);"上周"->{val monday=now.minusDays((now.dayOfWeek.value-1).toLong());monday.minusDays(7) to monday.minusDays(1)};"本周"->now.minusDays((now.dayOfWeek.value-1).toLong()) to now;"上个月"->{val first=now.withDayOfMonth(1);first.minusMonths(1) to first.minusDays(1)};else->now to now}
   input=input.replace(word,"")
  }
  if(range!=null){val d=runCatching{LocalDate.parse(m.date)}.getOrNull()?:return null;if(d<range!!.first||d>range!!.second)return null}
  val tokens=input.split(Regex("\\s+")).filter{it.isNotBlank()}
  val termGroups=tokens.map{searchTerms(it)}
  val fields=listOf("标题" to m.title,"正文" to m.body,"标签" to m.tags,"OCR" to if(m.state=="unavailable")"" else m.ocr,"图像标签" to if(m.state=="unavailable")"" else m.labels,"日期" to m.date)
  val searchable=fields+("英文图像索引" to if(m.state=="unavailable")"" else m.labelsEn)
  if(termGroups.all{terms->searchable.any{field->terms.any{normalize(field.second).contains(it)}}}) {val visible=fields.filter{field->termGroups.any{terms->terms.any{normalize(field.second).contains(it)}}};return visible.joinToString(" · "){"${it.first}：${it.second.take(100)}"}.ifBlank{"图像标签：${m.labels.take(100)}"}}
  if(m.kind=="event") members(m.id).forEach{a->evidence(a,q)?.let{return "附件 ${a.title} · $it"}}
  return null
 }
}
