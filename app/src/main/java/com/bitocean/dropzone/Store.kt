package com.bitocean.dropzone

import android.content.*
import android.database.Cursor
import android.database.sqlite.*
import java.text.Normalizer
import java.time.LocalDate

data class Memory(val id:Long,val kind:String,val title:String,val body:String,val uri:String,val date:String,val tags:String,val ocr:String,val state:String,val automatic:Boolean,val hidden:Boolean,val labels:String,val labelsEn:String,
 val manualUri:String="",val albumUri:String="",val photoKey:String="",val ocrState:String="pending",val labelState:String="pending",val ocrRegions:String="",val titleSource:String="",
 val addedDate:String="",val occurredDate:String="",val journalTarget:Long?=null,val eventCover:Long?=null,val highlight:Boolean=false,
 val eventEndDate:String=""){
 val dateLabel:String get()=if(kind=="event"&&eventEndDate.isNotBlank())"$occurredDate 至 $eventEndDate" else date
}
class Store(context:Context): SQLiteOpenHelper(context,"memories.db",null,9) {
 override fun onCreate(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE memory(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT,title TEXT,body TEXT,uri TEXT,date TEXT,tags TEXT,ocr TEXT DEFAULT '',state TEXT DEFAULT 'ready',automatic INTEGER DEFAULT 0,hidden INTEGER DEFAULT 0,labels TEXT DEFAULT '',labels_en TEXT DEFAULT '')")
  db.execSQL("CREATE UNIQUE INDEX source ON memory(uri) WHERE uri != '' AND kind='photo'")
  db.execSQL("CREATE TABLE attachment(event INTEGER,item INTEGER,PRIMARY KEY(event,item))")
  db.execSQL("CREATE TABLE excluded(uri TEXT PRIMARY KEY)")
  addSourceColumns(db)
  addReviewColumns(db)
  addVisualIndex(db)
  addEventEndDate(db)
  MemoryExtras.create(db)
  MemoryExtras.addPreviewsAndCollections(db)
 }
 override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int) {
  if(old<2) db.execSQL("ALTER TABLE memory ADD COLUMN labels TEXT DEFAULT ''")
  if(old<3){
   db.execSQL("ALTER TABLE memory ADD COLUMN labels_en TEXT DEFAULT ''")
   db.execSQL("UPDATE memory SET labels_en=labels WHERE labels!=''")
  }
  if(old<4){
   addSourceColumns(db)
   db.execSQL("UPDATE memory SET manual_uri=CASE WHEN automatic=0 THEN uri ELSE '' END, album_uri=CASE WHEN automatic=1 THEN uri ELSE '' END, ocr_state=CASE WHEN ocr!='' THEN 'found' ELSE 'pending' END, label_state=CASE WHEN labels_en!='' THEN 'found' ELSE 'pending' END WHERE kind='photo'")
  }
  if(old<5){
   addReviewColumns(db)
   db.execSQL("UPDATE memory SET occurred_date=date WHERE kind IN ('event','journal')")
  }
  if(old<6)addVisualIndex(db)
  if(old<7)addEventEndDate(db)
  if(old<8)MemoryExtras.create(db)
  if(old<9)MemoryExtras.addPreviewsAndCollections(db)
 }
 private fun addEventEndDate(db:SQLiteDatabase){
  db.execSQL("ALTER TABLE memory ADD COLUMN event_end_date TEXT NOT NULL DEFAULT ''")
 }
 private fun addVisualIndex(db:SQLiteDatabase){
  db.execSQL("CREATE TABLE visual_embedding(memory_id INTEGER PRIMARY KEY, model TEXT NOT NULL, vector BLOB NOT NULL)")
  db.execSQL("CREATE TRIGGER remove_visual_embedding AFTER DELETE ON memory BEGIN DELETE FROM visual_embedding WHERE memory_id=old.id; END")
  db.execSQL("CREATE TRIGGER invalidate_visual_embedding AFTER UPDATE OF uri,state ON memory WHEN new.uri!=old.uri OR new.state='unavailable' BEGIN DELETE FROM visual_embedding WHERE memory_id=old.id; END")
 }
 fun indexedPhotos(model:String):Set<Long> = readableDatabase.rawQuery(
  "SELECT memory_id FROM visual_embedding WHERE model=?",arrayOf(model)
 ).use{rows->buildSet{while(rows.moveToNext())add(rows.getLong(0))}}
 fun writeVisualEmbedding(id:Long,model:String,vector:ByteArray){
  writableDatabase.insertWithOnConflict("visual_embedding",null,ContentValues().apply{
   put("memory_id",id);put("model",model);put("vector",vector)
  },SQLiteDatabase.CONFLICT_REPLACE)
 }
 fun scanVisualEmbeddings(model:String,read:(Long,ByteArray)->Unit){
  readableDatabase.rawQuery("SELECT memory_id,vector FROM visual_embedding WHERE model=?",arrayOf(model)).use{rows->
   while(rows.moveToNext())read(rows.getLong(0),rows.getBlob(1))
  }
 }
 fun clearVisualEmbeddings(){writableDatabase.delete("visual_embedding",null,null)}
 private fun addReviewColumns(db:SQLiteDatabase){
  db.execSQL("ALTER TABLE memory ADD COLUMN added_date TEXT NOT NULL DEFAULT ''")
  db.execSQL("ALTER TABLE memory ADD COLUMN occurred_date TEXT NOT NULL DEFAULT ''")
  db.execSQL("ALTER TABLE memory ADD COLUMN journal_target INTEGER")
  db.execSQL("ALTER TABLE memory ADD COLUMN event_cover INTEGER")
  db.execSQL("ALTER TABLE memory ADD COLUMN highlight INTEGER NOT NULL DEFAULT 0")
  db.execSQL("CREATE INDEX journal_target_index ON memory(journal_target) WHERE journal_target IS NOT NULL")
 }
 private fun addSourceColumns(db:SQLiteDatabase) {
  for(column in listOf("manual_uri","album_uri","photo_key","ocr_regions","title_source")){
   db.execSQL("ALTER TABLE memory ADD COLUMN ${column} TEXT NOT NULL DEFAULT ''")
  }
  for(column in listOf("ocr_state","label_state")){
   db.execSQL("ALTER TABLE memory ADD COLUMN ${column} TEXT NOT NULL DEFAULT 'pending'")
  }
  db.execSQL("CREATE INDEX photo_identity ON memory(photo_key) WHERE photo_key!=''")
 }
 private fun Cursor.memory()=Memory(getLong(0),getString(1),getString(2),getString(3),getString(4),getString(5),getString(6),getString(7),getString(8),getInt(9)==1,getInt(10)==1,getString(11),getString(12),getString(13),getString(14),getString(15),getString(18),getString(19),getString(16),getString(17),
  getString(20),getString(21),if(isNull(22))null else getLong(22),if(isNull(23))null else getLong(23),getInt(24)==1,getString(25))
 fun all():List<Memory> = readableDatabase.rawQuery("SELECT * FROM memory ORDER BY id DESC",null).use { c -> buildList { while(c.moveToNext()) add(c.memory()) } }
 fun get(id:Long)=readableDatabase.rawQuery("SELECT * FROM memory WHERE id=?",arrayOf(id.toString())).use{if(it.moveToFirst())it.memory() else null}
 fun save(kind:String,title:String,body:String="",uri:String="",date:String=LocalDate.now().toString(),tags:String="",titleSource:String="",journalTarget:Long?=null,eventEnd:String?=null):Long=synchronized(photoLock) {
  writableDatabase.insertOrThrow("memory",null,ContentValues().apply{
   put("kind",kind);put("title",title);put("body",body);put("uri",uri);put("date",date);put("tags",tags);put("title_source",titleSource)
   put("added_date",LocalDate.now().toString())
   if(kind=="event"||kind=="journal")put("occurred_date",date)
   if(kind=="event")put("event_end_date",validateEventEnd(date,eventEnd))
   if(kind=="journal")put("journal_target",validJournalTarget(journalTarget))
  })
 }
 fun edit(id:Long,kind:String,title:String,body:String,date:String?,tags:String,uri:String?,target:Long?,eventEnd:String?=null)=synchronized(photoLock){
  val existing=get(id)?:return@synchronized
  update(id,ContentValues().apply{
   put("title",title);put("body",body);put("tags",tags)
   if(kind in listOf("link","photo")&&existing.title!=title)put("title_source","用户填写")
   put("date",date?:existing.addedDate.ifBlank{existing.date})
   if(kind in listOf("event","journal","photo"))put("occurred_date",date.orEmpty())
   if(kind=="event")put("event_end_date",validateEventEnd(date,eventEnd))
   if(kind=="journal")put("journal_target",validJournalTarget(target))
   if(uri!=null)put("uri",uri)
  })
 }
 private fun validateEventEnd(start:String?,end:String?):String{
  if(end==null)return ""
  val first=LocalDate.parse(requireNotNull(start))
  val last=LocalDate.parse(end)
  require(last>=first){"Event end date must be on or after its start date"}
  return if(last==first)"" else last.toString()
 }
 private fun validJournalTarget(id:Long?):Long?=id?.takeIf{get(it)?.kind in listOf("photo","event")}
 fun journalsFor(id:Long):List<Memory> = readableDatabase.rawQuery("SELECT * FROM memory WHERE kind='journal' AND journal_target=? ORDER BY id DESC",arrayOf(id.toString())).use{rows->buildList{while(rows.moveToNext())add(rows.memory())}}
 fun eventsFor(id:Long):List<Memory> = readableDatabase.rawQuery("SELECT memory.* FROM memory JOIN attachment ON memory.id=attachment.event WHERE attachment.item=? AND memory.kind='event' ORDER BY memory.id DESC",arrayOf(id.toString())).use{rows->buildList{while(rows.moveToNext())add(rows.memory())}}
 fun setEventMembers(event:Long,items:Set<Long>)=synchronized(photoLock){
  val db=writableDatabase
  if(get(event)?.kind!="event")return@synchronized
  db.beginTransaction()
  try{
   val old=members(event).mapTo(mutableSetOf()){it.id}
   for(id in old-items)detach(event,id)
   for(id in items-old)if(get(id)?.kind in listOf("photo","link","text","journal"))attach(event,id)
   db.setTransactionSuccessful()
  }finally{db.endTransaction()}
 }
 fun setEventCover(event:Long,photo:Long?)=synchronized(photoLock){
  if(get(event)?.kind=="event"){
   update(event,ContentValues().apply{put("event_cover",photo?.takeIf{id->members(event).any{it.id==id&&it.kind=="photo"}})})
  }
 }
 fun saveLink(title:String,body:String,uri:String,date:String,titleSource:String):Long=synchronized(photoLock){
  val key=Search.linkKey(uri)
  val existing=readableDatabase.rawQuery("SELECT * FROM memory WHERE kind='link' ORDER BY id DESC",null).use { rows ->
   generateSequence { if(rows.moveToNext()) rows.memory() else null }.firstOrNull { Search.linkKey(it.uri)==key }
  }
  if(existing==null) save("link",title,body,uri,date,titleSource=titleSource)
  else {
   if(body.isNotBlank()&&body!=existing.body&&body !in existing.body.split("\n\n")){
    update(existing.id,ContentValues().apply{put("body",listOf(existing.body,body).filter{it.isNotBlank()}.joinToString("\n\n"))})
   }
   existing.id
  }
 }
 fun savePhoto(uri:String,key:String,title:String,date:String,fromAlbum:Boolean,verifiedAlias:String="",takenDate:String?=null):Long?=synchronized(photoLock){
  if(fromAlbum&&(excluded(uri)||(key.isNotEmpty()&&excluded(key)))) return@synchronized null
  val matches=readableDatabase.rawQuery("SELECT * FROM memory WHERE kind='photo' AND (uri=? OR manual_uri=? OR album_uri=? OR (photo_key!='' AND photo_key IN (?,?)) OR (album_uri!='' AND album_uri IN (?,?))) ORDER BY automatic,id",arrayOf(uri,uri,uri,key,verifiedAlias,key,verifiedAlias)).use{rows->
   buildList{while(rows.moveToNext())add(rows.memory())}
  }
  val existing=matches.firstOrNull()
  val manual=if(fromAlbum) existing?.manualUri.orEmpty() else uri
  val album=if(fromAlbum) uri else matches.firstOrNull{it.albumUri.isNotEmpty()}?.albumUri.orEmpty()
  val values=ContentValues().apply{
   put("uri",manual.ifBlank{album});put("manual_uri",manual);put("album_uri",album)
   put("automatic",if(manual.isBlank())1 else 0)
   put("photo_key",key.ifBlank{existing?.photoKey.orEmpty()})
  }
  if(existing==null) {
   values.put("kind","photo");values.put("title",title);values.put("date",date)
   values.put("body","");values.put("tags","")
   values.put("added_date",LocalDate.now().toString());values.put("occurred_date",takenDate.orEmpty())
   writableDatabase.insertOrThrow("memory",null,values)
  } else {
   if(existing.occurredDate.isBlank()&&takenDate!=null){values.put("occurred_date",takenDate);values.put("date",takenDate)}
   if(fromAlbum&&existing.titleSource!="用户填写"&&existing.title in listOf("照片","照片 ${existing.date}")){
    values.put("title",title)
   }
   if(existing.uri!=values.getAsString("uri")||(!fromAlbum&&existing.state=="unavailable")) {
    values.put("state","ready")
    if(existing.state=="processing") resetAnalysis(values)
   }
   val duplicates=matches.drop(1)
   if(duplicates.isEmpty()) update(existing.id,values)
   else mergePhotos(existing,duplicates,values)
   existing.id
  }
 }
 fun resolveManualIdentity(id:Long,manualUri:String,key:String){
  writableDatabase.update("memory",ContentValues().apply{put("photo_key",key)},"id=? AND manual_uri=?",arrayOf(id.toString(),manualUri))
 }
 private fun mergePhotos(primary:Memory,duplicates:List<Memory>,values:ContentValues){
  val entries=listOf(primary)+duplicates
  val tags=entries.flatMap{it.tags.split(Regex("[ ,，]+"))}.filter{it.isNotBlank()}.distinct()
  val notes=entries.map{it.body}.filter{it.isNotBlank()}.distinct()
  values.put("tags",tags.joinToString(" "))
  values.put("body",notes.joinToString("\n\n"))
  values.put("hidden",if(entries.any{it.hidden})1 else 0)
  entries.map{it.addedDate}.filter{it.isNotBlank()}.minOrNull()?.let{values.put("added_date",it)}
  val editedTitle=entries.firstOrNull{it.titleSource=="用户填写"}
  if(editedTitle!=null){values.put("title",editedTitle.title);values.put("title_source",editedTitle.titleSource)}
  if(primary.ocr.isBlank())duplicates.firstOrNull{it.ocr.isNotBlank()}?.let{
   values.put("ocr",it.ocr);values.put("ocr_regions",it.ocrRegions);values.put("ocr_state",it.ocrState)
  }
  if(primary.labelsEn.isBlank())duplicates.firstOrNull{it.labelsEn.isNotBlank()}?.let{
   values.put("labels",it.labels);values.put("labels_en",it.labelsEn);values.put("label_state",it.labelState)
  }
  val db=writableDatabase
  db.beginTransaction()
  try{
   for(duplicate in duplicates){
    db.execSQL("INSERT OR IGNORE INTO attachment(event,item) SELECT event,? FROM attachment WHERE item=?",arrayOf(primary.id,duplicate.id))
    db.execSQL("UPDATE memory SET journal_target=? WHERE journal_target=?",arrayOf(primary.id,duplicate.id))
    db.execSQL("UPDATE memory SET event_cover=? WHERE event_cover=?",arrayOf(primary.id,duplicate.id))
    db.execSQL("INSERT OR IGNORE INTO milestone_link SELECT milestone,? FROM milestone_link WHERE item=?",arrayOf(primary.id,duplicate.id))
    db.delete("attachment","item=?",arrayOf(duplicate.id.toString()))
    db.delete("memory","id=?",arrayOf(duplicate.id.toString()))
   }
   update(primary.id,values)
   db.setTransactionSuccessful()
  }finally{db.endTransaction()}
 }
 fun setPhotoSources(m:Memory,manual:String,album:String,activeUri:String=manual.ifBlank{album}) {
  if(manual.isBlank()&&album.isBlank()){remove(m.id,false);return}
  update(m.id,ContentValues().apply{
   put("manual_uri",manual);put("album_uri",album);put("uri",activeUri)
   put("automatic",if(manual.isBlank())1 else 0)
   if(m.uri!=activeUri&&m.state=="processing"){resetAnalysis(this);put("state","ready")}
  })
 }
 fun markUnavailable(id:Long)=update(id,ContentValues().apply{
  resetAnalysis(this);put("state","unavailable")
 })
 fun clearAnalysis(){
  writableDatabase.execSQL("UPDATE memory SET ocr='',labels='',labels_en='',ocr_regions='',ocr_state='pending',label_state='pending',state=CASE WHEN state='unavailable' THEN state ELSE 'ready' END WHERE kind='photo'")
  clearVisualEmbeddings()
 }
 fun update(id:Long,v:ContentValues) {writableDatabase.update("memory",v,"id=?",arrayOf(id.toString()))}
 fun remove(id:Long,exclude:Boolean=true)=synchronized(photoLock) {
  val db=writableDatabase
  db.beginTransaction()
  try {
   get(id)?.takeIf{exclude&&it.kind=="photo"}?.let{photo->
    listOf(photo.uri,photo.albumUri,photo.photoKey).filter{it.isNotBlank()}.distinct().forEach{
     db.execSQL("INSERT OR IGNORE INTO excluded VALUES(?)",arrayOf(it))
    }
   }
   db.delete("memory","id=?",arrayOf(id.toString()))
   db.delete("attachment","event=? OR item=?",arrayOf(id.toString(),id.toString()))
   db.execSQL("UPDATE memory SET journal_target=NULL WHERE journal_target=?",arrayOf(id))
   db.execSQL("UPDATE memory SET event_cover=NULL WHERE event_cover=?",arrayOf(id))
   db.setTransactionSuccessful()
  }finally{db.endTransaction()}
 }
 fun excluded(uri:String)=readableDatabase.rawQuery("SELECT 1 FROM excluded WHERE uri=?",arrayOf(uri)).use{it.moveToFirst()}
 fun attach(event:Long,item:Long){writableDatabase.execSQL("INSERT OR IGNORE INTO attachment VALUES(?,?)",arrayOf(event,item))}
 private fun detach(event:Long,item:Long){
  writableDatabase.execSQL("DELETE FROM attachment WHERE event=? AND item=?",arrayOf(event,item))
  writableDatabase.execSQL("UPDATE memory SET event_cover=NULL WHERE id=? AND event_cover=?",arrayOf(event,item))
 }
 fun members(event:Long):List<Memory> = readableDatabase.rawQuery("SELECT item FROM attachment WHERE event=?",arrayOf(event.toString())).use{c->buildList{while(c.moveToNext())get(c.getLong(0))?.let{add(it)}}}
 fun attachmentIds():Map<Long,List<Long>> = readableDatabase.rawQuery("SELECT event,item FROM attachment UNION SELECT target.id,journal.id FROM memory journal JOIN memory target ON journal.journal_target=target.id WHERE journal.kind='journal' AND target.kind='event'",null).use{rows->
  val ids=mutableMapOf<Long,MutableList<Long>>()
  while(rows.moveToNext())ids.getOrPut(rows.getLong(0)){mutableListOf()}.add(rows.getLong(1))
  ids
 }
 fun usesManualUri(uri:String):Boolean=readableDatabase.rawQuery("SELECT 1 FROM memory WHERE manual_uri=? LIMIT 1",arrayOf(uri)).use{it.moveToFirst()}
 fun manualUriForIdentity(key:String):String?=readableDatabase.rawQuery("SELECT manual_uri FROM memory WHERE photo_key=? AND manual_uri!='' LIMIT 1",arrayOf(key)).use{if(it.moveToFirst())it.getString(0) else null}
 fun clear(){
  val db=writableDatabase
  db.beginTransaction()
  try{
   for(table in listOf("attachment","memory","excluded","milestone_link","milestone_collection","milestone","memory_theme","memory_reminder","photo_group_member","photo_group_scan","photo_group")) db.delete(table,null,null)
   db.setTransactionSuccessful()
  }finally{db.endTransaction()}
 }
 fun localizeLabels()=synchronized(photoLock){
  all().filter{it.labelsEn.isNotBlank()&&it.state!="unavailable"}.forEach{m->
   val localized=LabelTranslator.localizeLine(m.labelsEn)
   if(m.labels!=localized)update(m.id,ContentValues().apply{put("labels",localized)})
  }
 }
 fun normalize(s:String)=Normalizer.normalize(s,Normalizer.Form.NFKC).lowercase()
 companion object {
  val photoLock=Any()
  fun resetAnalysis(values:ContentValues) {
   for(column in listOf("ocr","labels","labels_en","ocr_regions")) values.put(column,"")
   values.put("ocr_state","pending")
   values.put("label_state","pending")
  }
 }
 fun searchTerms(token:String):List<String> = when(normalize(token)) {
  "people","person","human","人物","人像","人" -> listOf("people","person","human","woman","man","girl","boy","portrait","model","face","child","baby","fashion","clothing","dress","jeans","swimwear","beauty")
  "building","architecture","建筑","建筑物" -> listOf("building","architecture","church","cathedral","tower","house","landmark")
  "food","美食","食物","吃的" -> listOf("food","dish","dessert","bread","ingredient","tableware","cup","saucer","cookie","drink")
  "animal","animals","动物" -> listOf("animal","bird","insect","butterfly","dog","cat","fish","wing")
  "scenery","landscape","风景","景色" -> listOf("scenery","landscape","sky","beach","sea","ocean","flower","plant","field","mountain","sunset","nature")
  else -> listOf(normalize(token))
 }
}
