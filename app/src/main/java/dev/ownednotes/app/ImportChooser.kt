package dev.ownednotes.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract

/**
 * B34 (P197): every import first asks which app to take the item from (Gallery, Photos, My Files, Files,
 * Drive…), then opens that app. Android's own chooser cannot do this: it drops every app ranked below
 * the system Files picker and opens Files straight away, so the choice is this panel.
 */
internal object ImportChooser {
    /** [app] null opens whatever Android picks, the Files picker. */
    class Request(val types: Array<String>, val app: ComponentName? = null)

    fun intent(request: Request, multiple: Boolean): Intent {
        val types = request.types
        val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        if ("*/*" in types || types.isEmpty()) pick.type = "*/*"
        else {
            pick.type = types.singleOrNull() ?: "*/*"
            if (types.size > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, types)
        }
        request.app?.let { pick.component = it }
        return pick
    }

    fun uris(resultCode: Int, result: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || result == null) return emptyList()
        val clip = result.clipData
        val chosen = if (clip != null && clip.itemCount > 0) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            else listOfNotNull(result.data)
        return chosen.distinct()
    }

    class One : ActivityResultContract<Request, Uri?>() {
        override fun createIntent(context: Context, input: Request) = intent(input, multiple = false)
        override fun parseResult(resultCode: Int, intent: Intent?) = uris(resultCode, intent).firstOrNull()
    }

    class Many : ActivityResultContract<Request, List<Uri>>() {
        override fun createIntent(context: Context, input: Request) = intent(input, multiple = true)
        override fun parseResult(resultCode: Int, intent: Intent?) = uris(resultCode, intent)
    }

    private fun component(info: ResolveInfo) = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
    private fun prefs(context: Context) = context.getSharedPreferences("import-sources", Context.MODE_PRIVATE)

    /**
     * Apps that can hand over [types], in groups. For photos the gallery apps (Gallery, Photos) come first and the
     * file apps after them; files are one group led by the system Files picker. In each group the app used last
     * for [key] leads, then by name.
     */
    fun groups(context: Context, key: String, types: Array<String>, photos: Boolean): List<Pair<String?, List<ResolveInfo>>> {
        val pm = context.packageManager
        val found = pm.queryIntentActivities(intent(Request(types), false), PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.exported }
            .distinctBy { component(it) }
        val last = prefs(context).getString(key, null)
        val sorted = found.sortedWith(compareBy<ResolveInfo>({ component(it).flattenToString() != last }, { -it.priority },
            { it.loadLabel(pm).toString().lowercase() }))
        if (!photos) return listOf(null to sorted)
        val gallery = galleries(context)
        val (photoApps, others) = sorted.partition { it.activityInfo.packageName in gallery }
        return if (photoApps.isEmpty() || others.isEmpty()) listOf(null to sorted) else listOf("Photo apps" to photoApps, "Other apps" to others)
    }
    fun apps(context: Context, key: String, types: Array<String>, photos: Boolean) = groups(context, key, types, photos).flatMap { it.second }
    /** Apps that call themselves a gallery; failing that, apps that pick from the photo library. */
    fun galleries(context: Context): Set<String> {
        val pm = context.packageManager
        fun packages(intent: Intent) = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
        return packages(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY))
            .ifEmpty { packages(Intent(Intent.ACTION_PICK).setType("image/*")) }
    }

    class Panel(val popover: Popover, val tiles: List<View>)
    /** The panel on screen, if any; tests tap its tiles. */
    var open: Panel? = null; private set

    /**
     * Shows the panel titled [title]. Choosing an app remembers it for [key] and calls [launch]; closing the panel
     * without a choice calls [cancel]. With one app or none there is nothing to choose, so [launch] runs at once.
     */
    fun choose(activity: Activity, title: String, key: String, types: Array<String>, photos: Boolean,
               launch: (ComponentName?) -> Unit, cancel: () -> Unit) {
        open?.popover?.dismiss()
        val groups = groups(activity, key, types, photos)
        val apps = groups.flatMap { it.second }
        if (apps.size <= 1) { launch(apps.firstOrNull()?.let(::component)); return }
        val pm = activity.packageManager
        val last = prefs(activity).getString(key, null)
        var chosen = false
        lateinit var popover: Popover
        val tiles = mutableListOf<View>()
        popover = Popover(activity.window.decorView, 460f) { box ->
            val context = box.context
            box.addView(ToolControls.heading(context, title))
            groups.forEach { (label, members) ->
            if (label != null) box.addView(TextView(context).apply {
                text = label; textSize = 13f; typeface = Tokens.SANS_MEDIUM; setTextColor(Glass.secondary(context.night))
                setPadding(dpi(4f), dpi(if (box.childCount > 1) 10f else 0f), 0, dpi(2f))
            })
            val grid = GridLayout(context).apply { columnCount = 4 }
            members.forEach { info ->
                val app = component(info)
                val label = info.loadLabel(pm).toString()
                val usedLast = app.flattenToString() == last
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dpi(6f), dpi(10f), dpi(6f), dpi(8f))
                    background = ripple(context.night, dp(Glass.CHIP))
                    isClickable = true; isFocusable = true
                    contentDescription = if (usedLast) "$label, used last" else label
                    addView(ImageView(context).apply {
                        setImageDrawable(info.loadIcon(pm)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
                    addView(TextView(context).apply {
                        text = label; textSize = 13f; typeface = Tokens.SANS; setTextColor(Glass.text(context.night))
                        gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dpi(6f), 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    if (usedLast) addView(TextView(context).apply {
                        text = "Used last"; textSize = 11f; typeface = Tokens.SANS_MEDIUM
                        setTextColor(Glass.accent(context.night)); gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    setOnClickListener {
                        chosen = true
                        prefs(activity).edit().putString(key, app.flattenToString()).apply()
                        popover.dismiss()
                        launch(app)
                    }
                }
                tiles += tile
                grid.addView(tile, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, GridLayout.TOP),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
            }
            // A short group keeps the four-column width, so its tiles line up with the group below.
            repeat((4 - members.size).coerceAtLeast(0)) {
                grid.addView(View(context), GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = 0 })
            }
            box.addView(grid, LinearLayout.LayoutParams(-1, -2))
            }
        }
        popover.window.setOnDismissListener { if (open?.popover === popover) open = null; if (!chosen) cancel() }
        open = Panel(popover, tiles)
        popover.showCentered()
        // The choice is modal: the page dims behind it, so the light panel stands out on white paper.
        runCatching {
            val root = popover.window.contentView.rootView
            val params = root.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND; params.dimAmount = 0.28f
            activity.windowManager.updateViewLayout(root, params)
        }
    }
}
