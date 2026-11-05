package dev.ownednotes.app

import dev.ownednotes.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local study preferences contain stable references, never copies of notebook ink or attachments. */
internal class StudyCards(private val app:NotesApplication) {
    data class Ref(val page:String,val ids:ObjectIds)
    data class Card(val id:String,val notebook:String,val question:Ref,val answer:Ref,val due:Long=0,val streak:Int=0)
    private val preferences get()=app.getSharedPreferences(app.preferencesName,0)
    var pending:Pair<String,Ref>?=null;private set
    var revision=0L;private set
    fun list(notebook:String):List<Card> = read().filter {it.notebook==notebook}
    private fun read():List<Card> = runCatching {
        val rows=JSONArray(preferences.getString("study_cards","[]"))
        (0 until rows.length()).map {i->val j=rows.getJSONObject(i);Card(j.getString("id"),j.getString("notebook"),ref(j.getJSONObject("question")),ref(j.getJSONObject("answer")),j.optLong("due"),j.optInt("streak"))}
    }.getOrElse {emptyList()}
    private fun ref(j:JSONObject)=Ref(j.getString("page"),ObjectIds(strings(j.getJSONArray("ink")),strings(j.getJSONArray("images")),strings(j.getJSONArray("text"))))
    private fun strings(j:JSONArray)=(0 until j.length()).map {j.getString(it)}.toSet()
    private fun json(r:Ref)=JSONObject().put("page",r.page).put("ink",JSONArray(r.ids.strokes.toList())).put("images",JSONArray(r.ids.images.toList())).put("text",JSONArray(r.ids.texts.toList()))
    private fun save(cards:List<Card>) {
        require(cards.size<=5000) {"Up to 5,000 study cards can be saved on this device"}
        val rows=JSONArray();cards.forEach {rows.put(JSONObject().put("id",it.id).put("notebook",it.notebook).put("question",json(it.question)).put("answer",json(it.answer)).put("due",it.due).put("streak",it.streak))}
        preferences.edit().putString("study_cards",rows.toString()).apply();revision++
    }
    fun question(note:Notebook,page:String,ids:ObjectIds) {
        require(!ids.isEmpty) {"Select the question with the lasso first"};note.captureObjects(page,ids)
        pending=note.id to Ref(page,ids);revision++
    }
    fun answer(note:Notebook,page:String,ids:ObjectIds):Card {
        val q=requireNotNull(pending) {"Select and mark a question first"}
        require(q.first==note.id) {"Choose an answer in the same notebook"}
        require(!ids.isEmpty) {"Select the answer with the lasso first"};note.captureObjects(page,ids)
        require(available(note,q.second)) {"The question changed; select it again"}
        val card=Card(UUID.randomUUID().toString(),note.id,q.second,Ref(page,ids))
        save(read()+card);pending=null;return card
    }
    fun due(note:Notebook,now:Long=System.currentTimeMillis())=list(note.id).filter {it.due<=now && available(note,it.question) && available(note,it.answer)}.sortedBy {it.due}
    fun grade(id:String,knew:Boolean,now:Long=System.currentTimeMillis()) {
        save(read().map {card->if(card.id!=id)card else {
            val streak=if(knew)(card.streak+1).coerceAtMost(6)else 0
            val wait=if(knew)(1L shl (streak-1)).coerceAtMost(30)*86_400_000L else 600_000L
            card.copy(due=now+wait,streak=streak)
        }})
    }
    companion object {
        fun available(note:Notebook,ref:Ref):Boolean {
            val page=note.pages.firstOrNull {it.id==ref.page} ?: return false
            return !ref.ids.isEmpty && page.strokes.map {it.id}.containsAll(ref.ids.strokes) && page.images.map {it.id}.containsAll(ref.ids.images) && page.textBoxes.map {it.id}.containsAll(ref.ids.texts)
        }
    }
}
