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

/** Functional settings inside the existing Study Text panel; shared Glass controls only. */
internal class VocabularyControls(context:Context,private val app:NotesApplication):LinearLayout(context) {
    val field=Kit.field(context,"Course term or name").apply {contentDescription="Personal vocabulary word"}
    private val list=LinearLayout(context).apply {orientation=VERTICAL}
    val add=Kit.button(context,"Add word",Kit.Button.TINTED) {
        runCatching {PersonalVocabulary.add(app,field.text.toString())}.onSuccess {field.text.clear();field.error=null;render()}
            .onFailure {field.error=it.message}
    }
    init {
        orientation=VERTICAL
        addView(Kit.section(context,"Personal vocabulary"))
        addView(Kit.text(context,"Prefer your course terms when reading handwriting. Reviewed text stays as you saved it.",Kit.Type.CAPTION,secondary=true))
        addView(field);addView(add);addView(list);render()
    }
    private fun render() {
        list.removeAllViews()
        PersonalVocabulary.snapshot.words.forEach {word->
            val remove=Kit.button(context,"Remove",Kit.Button.PLAIN,small=true) {PersonalVocabulary.remove(app,word);render()}.apply {contentDescription="Remove $word"}
            list.addView(Kit.row(context,word,trailing=remove))
        }
    }
}
