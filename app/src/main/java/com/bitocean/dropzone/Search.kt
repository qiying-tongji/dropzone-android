package com.bitocean.dropzone

import android.icu.text.BreakIterator
import java.net.URI
import java.time.LocalDate
import java.util.Locale

internal object Search {
 data class Query(val terms:List<String>,val dates:ClosedRange<LocalDate>?)
 data class Match(val item:Memory,val evidence:String,val score:Int,val source:Memory=item)
 private data class Field(val name:String,val text:String,val weight:Int,val hiddenIndex:Boolean=false)
 private val relativeDate=Regex("上个月|本周|上周|昨天|今天")
 private val stopWords=setOf("的","了","找","查找")

 fun parse(text:String,today:LocalDate=LocalDate.now()):Query{
  val word=relativeDate.find(text)
  val monday=today.minusDays((today.dayOfWeek.value-1).toLong())
  val first=today.withDayOfMonth(1)
  val dates=when(word?.value){
   "今天" -> today..today
   "昨天" -> today.minusDays(1)..today.minusDays(1)
   "本周" -> monday..today
   "上周" -> monday.minusWeeks(1)..monday.minusDays(1)
   "上个月" -> first.minusMonths(1)..first.minusDays(1)
   else -> null
  }
  val remaining=if(word==null)text else text.removeRange(word.range).trimStart('的')
  return Query(remaining.split(Regex("[\\s，,、]+")).filter{it.isNotBlank()},dates)
 }

 fun find(store:Store,items:List<Memory>,query:Query,kind:String?,tag:String):List<Match> {
  val byId=items.associateBy{it.id}
  val attachments=if(items.any{it.kind=="event"}) store.attachmentIds() else emptyMap()
  val normalizedTag=store.normalize(tag.trim())
  return items.asSequence()
   .filter{kind==null||it.kind==kind}
   .filter{normalizedTag.isEmpty()||it.tags.split(Regex("[ ,，]+")).any{term->store.normalize(term)==normalizedTag}}
   .filter{item->query.dates?.let{overlaps(item,it)}?:true}
   .mapNotNull{item->
    val direct=match(store,item,query)
    if(direct!=null||item.kind!="event") direct
    else attachments[item.id].orEmpty().mapNotNull{byId[it]}
     .mapNotNull{match(store,it,query)}.maxByOrNull{it.score}
     ?.let{Match(item,"附件 ${it.item.title} · ${it.evidence}",it.score-1,it.item)}
   }
   .sortedWith(compareByDescending<Match>{it.score}.thenByDescending{it.item.id})
   .toList()
 }

 private fun overlaps(item:Memory,dates:ClosedRange<LocalDate>):Boolean=runCatching{
  val start=LocalDate.parse(item.date)
  val end=if(item.kind=="event"&&item.eventEndDate.isNotBlank())LocalDate.parse(item.eventEndDate) else start
  start<=dates.endInclusive&&end>=dates.start
 }.getOrDefault(false)

 private fun match(store:Store,item:Memory,query:Query):Match?{
  if(query.terms.isEmpty())return Match(item,"日期：${item.dateLabel}",0)
  val fields=listOf(
   Field("标题",item.title,9),Field("标签",item.tags,8),
   Field("图片文字",item.ocr,7),Field("图像标签",item.labels,5),
   Field("图像标签",item.labelsEn,5,true),Field(if(item.kind=="journal")"日记" else "正文",item.body,6),
   Field("原网址",item.uri.takeIf{item.kind=="link"}.orEmpty(),3),Field("日期",item.dateLabel,2)
  ).filter{it.text.isNotBlank()}
  val matched=linkedMapOf<Field,String>()
  for(word in query.terms){
   val whole=hits(store,word,fields)
   if(whole.isNotEmpty()){matched.putAll(whole);continue}
   val pieces=words(word)
   if(pieces.size<2)return null
   for(piece in pieces){
    val hits=hits(store,piece,fields)
    if(hits.isEmpty())return null
    matched.putAll(hits)
   }
  }
  val evidence=matched.entries.distinctBy{it.key.name}.joinToString(" · "){(field,term)->
   val value=if(field.hiddenIndex) LabelTranslator.localizeLine(field.text) else snippet(store,field.text,term)
   "${field.name}：$value"
  }
  return Match(item,evidence,matched.keys.distinctBy{it.name}.sumOf{it.weight}+matched.values.count{it in query.terms})
 }

 private fun hits(store:Store,word:String,fields:List<Field>):Map<Field,String>{
  val normalized=store.normalize(word)
  val related=store.searchTerms(normalized)
  return fields.mapNotNull{field->
   val value=store.normalize(field.text)
   val terms=if(field.name=="图像标签")listOf(normalized)+related+LabelTranslator.searchAliases(normalized) else listOf(normalized)
   terms.firstOrNull{value.contains(it)}?.let{field to it}
  }.toMap()
 }

 private fun words(text:String):List<String>{
  val iterator=BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE)
  iterator.setText(text)
  return buildList{
   var start=iterator.first()
   var end=iterator.next()
   while(end!=BreakIterator.DONE){
    val word=text.substring(start,end)
    if(word.any{it.isLetterOrDigit()}&&word !in stopWords)add(word)
    start=end
    end=iterator.next()
   }
  }
 }

 private fun snippet(store:Store,text:String,term:String):String {
  val index=store.normalize(text).indexOf(term).coerceIn(0,text.length)
  val start=(index-25).coerceAtLeast(0)
  return (if(start>0)"…" else "")+text.drop(start).take(95)
 }

 fun linkKey(value:String):String?{
  val uri=runCatching{URI(value.trim())}.getOrNull()?:return null
  val scheme=uri.scheme?.lowercase(Locale.ROOT)
  val host=uri.host?.lowercase(Locale.ROOT)
  if(scheme !in listOf("http","https")||host==null||uri.rawUserInfo!=null)return null
  return buildString{
   append(scheme);append("://");append(host)
   if(uri.port!=-1){append(':');append(uri.port)}
   append(uri.rawPath.orEmpty())
   uri.rawQuery?.let{append('?');append(it)}
   uri.rawFragment?.let{append('#');append(it)}
  }
 }
}
