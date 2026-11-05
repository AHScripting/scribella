package dev.ownednotes.app

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.*
import dev.ownednotes.core.HandwritingInput
import dev.ownednotes.core.HandwritingLines
import dev.ownednotes.core.HandwritingLayout
import dev.ownednotes.core.HandwritingParagraphs
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal enum class HandwritingLanguage(val label: String, val tag: String) {
    ENGLISH("English", "en-US"), GERMAN("Deutsch", "de-DE")
}

/** Async boundary also allows deterministic stale/error UI checks without downloading a model. */
internal interface HandwritingEngine {
    fun downloaded(language: HandwritingLanguage): Task<Boolean>
    fun download(language: HandwritingLanguage): Task<Void>
    fun recognize(input: HandwritingInput, language: HandwritingLanguage, separateLines: Boolean = true, layout: HandwritingLayout = HandwritingLayout()): Task<List<String>>
}

internal object DigitalHandwriting : HandwritingEngine {
    private val worker = Executors.newSingleThreadExecutor()
    private fun model(language: HandwritingLanguage): DigitalInkRecognitionModel {
        val identifier = requireNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(language.tag)) {
            "No handwriting model for ${language.label}."
        }
        return DigitalInkRecognitionModel.builder(identifier).build()
    }
    override fun downloaded(language: HandwritingLanguage): Task<Boolean> =
        Tasks.withTimeout(RemoteModelManager.getInstance().isModelDownloaded(model(language)), 15, TimeUnit.SECONDS)

    override fun download(language: HandwritingLanguage): Task<Void> =
        Tasks.withTimeout(RemoteModelManager.getInstance().download(model(language), DownloadConditions.Builder().build()), 120, TimeUnit.SECONDS)

    override fun recognize(input: HandwritingInput, language: HandwritingLanguage, separateLines: Boolean, layout: HandwritingLayout): Task<List<String>> {
        val vocabulary = PersonalVocabulary.snapshot
        val seconds = if (separateLines || layout.columns == 2) 90L else 30L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        val result = Tasks.call(worker) {
            layout.prepare(input.strokes, separateLines).map { it.strokes }
        }.onSuccessTask(worker) { lines ->
            val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model(language)).build())
            HandwritingPipeline.run(lines, deadline, worker, readLine = { points ->
                val builder = Ink.builder()
                points.forEach { fragment ->
                    val stroke = Ink.Stroke.builder()
                    fragment.forEach { stroke.addPoint(Ink.Point.create(it.x, it.y)) }
                    builder.addStroke(stroke.build())
                }
                recognizer.recognize(builder.build()).continueWith(worker) { task ->
                    vocabulary.prefer(task.getResult(Exception::class.java).candidates.map { it.text }.filter { it.isNotBlank() }.distinct().take(10))
                }
            }, close = { recognizer.close() })
        }
        return Tasks.withTimeout(result, seconds, TimeUnit.SECONDS)
    }
}

/** One sequential request; failures never expose a partial paragraph or start later rows. */
internal object HandwritingPipeline {
    fun run(lines: List<List<List<dev.ownednotes.core.Point>>>, deadline: Long, worker: Executor,
        readLine: (List<List<dev.ownednotes.core.Point>>) -> Task<List<String>>, close: () -> Unit): Task<List<String>> {
        try {
            require(lines.isNotEmpty() && lines.size <= HandwritingLines.MAX_LINES) { "Invalid handwriting line count." }
            fun checkDeadline() = check(System.nanoTime() < deadline) {
                "Handwriting recognition timed out. Select fewer lines."
            }
            var chain: Task<List<List<String>>> = Tasks.forResult(emptyList())
            lines.forEachIndexed { index, points ->
                chain = chain.onSuccessTask(worker) { previous ->
                    checkDeadline()
                    readLine(points).continueWith(worker) { task ->
                        val choices = task.getResult(Exception::class.java)
                        checkDeadline()
                        require(choices.any { it.isNotBlank() }) { "No text recognised on line${index + 1}. Select that line and try again." }
                        previous + listOf(choices)
                    }
                }
            }
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Handwriting recognition timed out. Select fewer lines." }
            // Bounding only the UI task leaves a stalled model request's client open. Bound
            // this chain too; late SDK results cannot publish or start another expired row.
            return Tasks.withTimeout(chain, remaining, TimeUnit.NANOSECONDS).continueWith(worker) { task ->
                try {
                    val choices = task.getResult(Exception::class.java)
                    checkDeadline()
                    HandwritingParagraphs.candidates(choices).also { checkDeadline() }
                }
                finally { close() }
            }
        } catch (error: Exception) { close(); return Tasks.forException(error) }
    }
}
