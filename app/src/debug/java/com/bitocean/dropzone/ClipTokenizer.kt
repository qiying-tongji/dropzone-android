package com.bitocean.dropzone

import java.text.Normalizer
import java.util.Locale

/** WordPiece input for the Chinese-CLIP RN50 text encoder. */
internal class ClipTokenizer(words:List<String>){
 private val vocabulary=words.mapIndexed{index,word->word to index.toLong()}.toMap()
 private val cls=vocabulary.getValue("[CLS]")
 private val sep=vocabulary.getValue("[SEP]")
 private val unknown=vocabulary.getValue("[UNK]")
 private val pad=vocabulary.getValue("[PAD]")
 private val accents=Regex("\\p{Mn}+")
 private val spaces=Regex("\\s+")

 fun encode(query:String):LongArray{
  val basic=buildString{
   query.codePoints().forEach{cp->
    when{
     cp==0||cp==0xfffd->Unit
     Character.isWhitespace(cp)||Character.getType(cp)==Character.SPACE_SEPARATOR.toInt()->append(' ')
     Character.getType(cp) in setOf(Character.CONTROL.toInt(),Character.FORMAT.toInt())->Unit
     isChinese(cp)||punctuation(cp)->{append(' ');appendCodePoint(cp);append(' ')}
     else->appendCodePoint(cp)
    }
   }
  }
  val clean=Normalizer.normalize(basic.lowercase(Locale.ROOT),Normalizer.Form.NFD).replace(accents,"")
  val pieces=clean.trim().splitToSequence(spaces).filter{it.isNotBlank()}.flatMap{wordPieces(it).asSequence()}.take(50).toList()
  return LongArray(52){pad}.apply{
   this[0]=cls
   pieces.forEachIndexed{index,token->this[index+1]=token}
   this[pieces.size+1]=sep
  }
 }
 private fun wordPieces(word:String):List<Long>{
  if(word.length>100)return listOf(unknown)
  val pieces=mutableListOf<Long>()
  var start=0
  while(start<word.length){
   var end=word.length
   var token:Long?=null
   while(end>start){
    val prefix=if(start==0)"" else "##"
    token=vocabulary[prefix+word.substring(start,end)]
    if(token!=null)break
    end--
   }
   pieces.add(token?:return listOf(unknown))
   start=end
  }
  return pieces
 }
 private fun isChinese(cp:Int)=cp in 0x4e00..0x9fff||cp in 0x3400..0x4dbf||
  cp in 0x20000..0x2a6df||cp in 0x2a700..0x2b73f||cp in 0x2b740..0x2b81f||
  cp in 0x2b820..0x2ceaf||cp in 0xf900..0xfaff||cp in 0x2f800..0x2fa1f
 private fun punctuation(cp:Int)=cp in 33..47||cp in 58..64||cp in 91..96||cp in 123..126||
  Character.getType(cp) in setOf(20,21,22,23,24,29,30)
}
