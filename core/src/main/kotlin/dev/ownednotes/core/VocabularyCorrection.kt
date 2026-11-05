package dev.ownednotes.core

import java.text.Normalizer
import java.util.Locale

/** Only user-supplied spellings; ambiguous and short words remain untouched. */
object VocabularyCorrection {
    private val token=Regex("[\\p{L}\\p{M}]+(?:[-’'][\\p{L}\\p{M}]+)*")
    private fun normalized(s:String)=Normalizer.normalize(s,Normalizer.Form.NFC).lowercase(Locale.ROOT)
    fun correct(text:String,words:List<String>):String {
        if(words.isEmpty())return text
        val entries=words.map {it to normalized(it)}
        return token.replace(text) {match->
            val raw=match.value;val value=normalized(raw)
            val exact=entries.firstOrNull {it.second==value}
            if(exact!=null) exact.first else {
                val limit=when {value.length>=9->2;value.length>=5->1;else->0}
                if(limit==0)raw else {
                    val ranked=entries.filter {kotlin.math.abs(it.second.length-value.length)<=limit}
                        .map {it.first to distance(value,it.second)}.filter {it.second<=limit}
                    val best=ranked.minOfOrNull {it.second}
                    ranked.filter {it.second==best}.singleOrNull()?.first ?: raw
                }
            }
        }
    }
    private fun distance(a:String,b:String):Int {
        var previous=IntArray(b.length+1){it}
        for(i in a.indices) {
            val current=IntArray(b.length+1);current[0]=i+1
            for(j in b.indices)current[j+1]=minOf(current[j]+1,previous[j+1]+1,previous[j]+if(a[i]==b[j])0 else 1)
            previous=current
        }
        return previous[b.length]
    }
}
