package com.bitocean.dropzone

import java.time.LocalDate

internal object MemoryReview {
 enum class Basis(val label:String){OCCURRED("发生或拍摄"),ADDED("加入记忆库")}
 data class Dated(val item:Memory,val date:LocalDate)

 fun date(item:Memory,basis:Basis):LocalDate? {
  val value=if(basis==Basis.OCCURRED)item.occurredDate else item.addedDate
  return if(value.isBlank())null else runCatching{LocalDate.parse(value)}.getOrNull()
 }

 fun eligible(items:List<Memory>):List<Memory> =
  items.filter{!it.hidden&&(it.kind!="photo"||it.state!="unavailable")}.distinctBy{it.id}

 fun yearly(items:List<Memory>,year:Int,basis:Basis):List<Dated> =
  eligible(items).mapNotNull{item->date(item,basis)?.takeIf{it.year==year}?.let{Dated(item,it)}}
   .sortedWith(compareByDescending<Dated>{it.date}.thenByDescending{it.item.id})

 fun anniversary(items:List<Memory>,today:LocalDate,attachments:Map<Long,List<Long>>):List<Memory>{
  val sameDay=eligible(items).filter{item->
   val date=date(item,Basis.OCCURRED)
   date!=null&&date.year<today.year&&date.monthDayEquals(today)
  }
  val events=sameDay.filter{it.kind=="event"}.associateBy{it.id}
  val grouped=sameDay.filter{it.kind!="event"}.filter{item->
   events[item.journalTarget]?.let{date(it,Basis.OCCURRED)?.year==date(item,Basis.OCCURRED)?.year}==true||
   events.values.any{event->
    date(event,Basis.OCCURRED)?.year==date(item,Basis.OCCURRED)?.year&&
    attachments[event.id].orEmpty().any{it==item.id||it==item.journalTarget}
   }
  }.mapTo(hashSetOf()){it.id}
  return sameDay.filter{it.id !in grouped}.sortedWith(compareByDescending<Memory>{it.occurredDate}.thenByDescending{it.id})
 }

 private fun LocalDate.monthDayEquals(other:LocalDate)=month==other.month&&dayOfMonth==other.dayOfMonth
}
