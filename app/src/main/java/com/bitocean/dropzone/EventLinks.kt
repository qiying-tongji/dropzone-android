package com.bitocean.dropzone

/** Resolves explicit associations among the currently accessible entries. */
internal class EventLinks(items:List<Memory>,private val attachments:Map<Long,List<Long>>){
 private val byId=items.associateBy{it.id}
 private val photoJournals=items.filter{it.kind=="journal"&&it.journalTarget!=null}.groupBy{it.journalTarget}

 fun contents(event:Long):List<Memory>{
  val direct=attachments[event].orEmpty().mapNotNull{byId[it]}
  val journals=direct.filter{it.kind=="photo"}.flatMap{photoJournals[it.id].orEmpty()}
  return (direct+journals).distinctBy{it.id}
 }

 fun eventsFor(itemIds:Set<Long>):Map<Long,List<Memory>>{
  if(itemIds.isEmpty())return emptyMap()
  val result=mutableMapOf<Long,MutableList<Memory>>()
  val events=attachments.keys.mapNotNull{byId[it]}.filter{it.kind=="event"}
   .sortedWith(compareByDescending<Memory>{it.occurredDate}.thenByDescending{it.id})
  for(event in events){
   for(item in contents(event.id)){
    if(item.id in itemIds)result.getOrPut(item.id){mutableListOf()}.add(event)
   }
  }
  return result
 }
}
