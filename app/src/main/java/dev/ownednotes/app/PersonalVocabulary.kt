package dev.ownednotes.app

import android.content.Context
import android.widget.LinearLayout
import dev.ownednotes.core.VocabularyCorrection
import java.security.MessageDigest
import java.util.Locale

/** Device-local preference, not reviewed notebook text. Immutable snapshots cross recognition workers. */
internal object PersonalVocabulary {
    data class Snapshot(val words:List<String>,val fingerprint:String) {
        fun prefer(candidates:List<String>)=(candidates.map {VocabularyCorrection.correct(it,words)}+candidates).distinct()
    }
    @Volatile var snapshot=Snapshot(emptyList(),"empty");private set
    fun initialize(app:NotesApplication) {publish(app.getSharedPreferences(app.preferencesName,0).getStringSet("personal_vocabulary",emptySet()).orEmpty().sortedBy {it.lowercase(Locale.ROOT)})}
    private fun publish(words:List<String>) {
        val hash=MessageDigest.getInstance("SHA-256").digest(words.joinToString("\n").toByteArray()).joinToString(""){"%02x".format(it)}
        snapshot=Snapshot(words.toList(),hash)
    }
    fun add(app:NotesApplication,word:String) {
        val value=word.trim()
        require(value.length in 2..64 && value.matches(Regex("[\\p{L}\\p{M}]+(?:[-’'][\\p{L}\\p{M}]+)*"))) {"Add one word, using letters"}
        val words=snapshot.words.filterNot {it.equals(value,true)}+value
        require(words.size<=500) {"Your vocabulary holds up to 500 words"}
        save(app,words.sortedBy {it.lowercase(Locale.ROOT)})
    }
    fun remove(app:NotesApplication,word:String)=save(app,snapshot.words.filterNot {it==word})
    private fun save(app:NotesApplication,words:List<String>) {
        app.getSharedPreferences(app.preferencesName,0).edit().putStringSet("personal_vocabulary",words.toSet()).apply()
        publish(words);InkText.vocabularyChanged();app.searchIndexer.schedule(app.activeDocument.session?.notebook)
    }
}

/**
 * Functional settings inside the existing Study Text panel; shared Glass controls only. P615 (board 9): one row,
 * "Vocabulary" with how many words it holds; Add opens the word field and the list of words under the row.
 */
internal class VocabularyControls(context:Context,private val app:NotesApplication):LinearLayout(context) {
    val field=Kit.field(context,"Course term or name").apply {contentDescription="Personal vocabulary word"}
    private val list=LinearLayout(context).apply {orientation=VERTICAL}
    val add=Kit.button(context,"Add word",Kit.Button.TINTED,small=true) {
        runCatching {PersonalVocabulary.add(app,field.text.toString())}.onSuccess {field.text.clear();field.error=null;render()}
            .onFailure {field.error=it.message}
    }
    private val row=StudyRow(context,"Vocabulary",StudyRow.square(context,"M5 4h10a4 4 0 0 1 4 4v12H9a4 4 0 0 1-4-4zM9 9h6M9 13h4",0xff0a84ff.toInt()),"")
    private val toggle=Kit.button(context,"",Kit.Button.TINTED,small=true) {open=!open;render()}
    private val editor=LinearLayout(context).apply {
        orientation=VERTICAL;context.dpi(12f).let {setPadding(it,0,it,it)}
        addView(LinearLayout(context).apply {
            orientation=HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
            addView(field,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
            addView(add,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT).apply {marginStart=context.dpi(8f)})
        })
        addView(list)
    }
    init {
        orientation=VERTICAL
        row.trail(toggle);addView(row);addView(editor);render()
    }
    private fun render() {
        val words=PersonalVocabulary.snapshot.words
        row.detail.text=when(words.size) {0->"Words to prefer when reading";1->"1 word";else->"${words.size} words"}
        toggle.text=if(open) "Done" else "Add";toggle.contentDescription=if(open) "Close vocabulary" else "Add vocabulary words"
        editor.visibility=if(open) VISIBLE else GONE
        list.removeAllViews()
        words.forEach {word->
            val remove=Kit.button(context,"Remove",Kit.Button.PLAIN,small=true) {PersonalVocabulary.remove(app,word);render()}.apply {contentDescription="Remove $word"}
            list.addView(Kit.row(context,word,trailing=remove,menu=true).apply {setPadding(context.dpi(4f),0,0,0)})
        }
    }
    /** Open across panel rebuilds (each stroke can rebuild the panel), so the field does not close under the pen. */
    companion object { private var open=false }
}
