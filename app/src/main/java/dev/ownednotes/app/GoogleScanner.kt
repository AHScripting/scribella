package dev.ownednotes.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.net.Uri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * P689 (B1, the user's yes of 22 November 2026): Google's document scanner, the one behind Drive's Scan and the Pixel's
 * crisp scans. It runs on the device inside Google Play services; Scribella gets back finished JPEGs, which join the
 * scan's review row like any page. Where Play services or the scanner is missing, Scribella's own camera opens instead.
 */
internal object GoogleScanner {
    private const val KEY = "scan-with-google"
    /** Tests turn Google's scanner off, so Scan opens Scribella's camera. */
    @Volatile var allowed = true

    /** The remembered choice: Scan opens Google's scanner (the default) or Scribella's own camera. */
    fun chosen(preferences: SharedPreferences) = preferences.getBoolean(KEY, true)
    fun choose(preferences: SharedPreferences, google: Boolean) { preferences.edit().putBoolean(KEY, google).apply() }

    /** Play services is present and current; the scanner itself may still need a moment to arrive the first time. */
    fun available(context: Context) = allowed &&
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }.getOrDefault(false)

    /**
     * Asks Play services for the scanner: full mode (finds, straightens and cleans each page), the gallery allowed, JPEG
     * pages, at most [pageLimit]. [launch] gets the scanner to start; [failed] runs when it cannot open.
     */
    fun start(activity: Activity, pageLimit: Int, launch: (IntentSender) -> Unit, failed: (Exception) -> Unit) {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit.coerceIn(1, MAX_PAGES))
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        runCatching { GmsDocumentScanning.getClient(options).getStartScanIntent(activity) }
            .onSuccess { task -> task.addOnSuccessListener { launch(it) }.addOnFailureListener { failed(it) } }
            .onFailure { failed(it as? Exception ?: IllegalStateException(it)) }
    }

    /** The finished pages' images, in order; empty when the scan was cancelled. */
    fun pages(data: Intent?): List<Uri> =
        runCatching { GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.map { it.imageUri } }.getOrNull().orEmpty()

    /** One scan's page limit, so a long scan stays a manageable batch. */
    const val MAX_PAGES = 100
}
