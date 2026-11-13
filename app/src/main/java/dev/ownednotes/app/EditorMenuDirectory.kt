package dev.ownednotes.app

/**
 * Each action has one task home. New actions must choose a home instead of growing an overflow menu.
 * P186: CAPTURE opens from the Insert button, STUDY from Search and PAGES from "Page and paper", each under its own caption.
 * P605: every menu home above is drawn as an icon grid (MenuIcons gives each command a short label, icon and family tint);
 * homes, order and guards are unchanged. The full label stays the accessibility name, and "Find a command" is a pill at the top.
 * P606: the Library menu and a notebook's menu use the same tiles (LibraryScreen.tileMenu); see MENU-AND-VISUAL-AUDIT.md.
 * P608: in the Library, hold a notebook and drag it onto a folder (a grid tile, or a folder in the side list) to file it;
 * letting go without moving still opens its menu. Move to folder… in the menu is unchanged.
 * P607: the page strip is a floating Pages card (the design canvas's Pages-Panel board). A page's menu (duplicate, move,
 * delete) opens by holding the page and letting go where it was; the ⋮ under each page is gone. A New page tile ends the strip.
 */
internal enum class EditorMenu(val label: String) { INSERT("Insert"), CAPTURE("Capture and files"), STUDY("Study"), NAVIGATE("Search and navigation"), PAGES("Page actions"), EXPORT("Export"), NOTEBOOK("Notebook") }
internal enum class EditorCommand(val label: String, val home: EditorMenu) {
    PDF_SELECT_TEXT("Select PDF text…", EditorMenu.STUDY),
    PASTE("Paste", EditorMenu.INSERT), BLANK("Blank page after this page", EditorMenu.INSERT), TEXT("Text box…", EditorMenu.INSERT), STICKY_NOTE("Sticky note…", EditorMenu.INSERT), TABLE("Table…", EditorMenu.INSERT), STICKERS("Stickers…", EditorMenu.INSERT), STAMPS("Stamps…", EditorMenu.INSERT),
    VIDEO("Video…", EditorMenu.CAPTURE), SCAN("Scan document…", EditorMenu.CAPTURE), PHOTO("Photo…", EditorMenu.CAPTURE), PDF_PAGES("PDF pages…", EditorMenu.CAPTURE), IMAGE_PAGE("Image page…", EditorMenu.CAPTURE), ATTACH("Attach file…", EditorMenu.CAPTURE), PDF_SOURCES("Manage PDF sources…", EditorMenu.CAPTURE),
    LASER("Laser pointer", EditorMenu.STUDY), STUDY_PANEL("Study panel…", EditorMenu.STUDY), HANDWRITING("Read handwriting…", EditorMenu.STUDY), PAGE_TEXT("Copy printed page text (OCR)…", EditorMenu.STUDY), TEXT_MARK("Highlight printed text…", EditorMenu.STUDY), PREPARE_STUDY("Prepare study copy for PC", EditorMenu.STUDY), INK_EXPORT("Export handwriting only…", EditorMenu.STUDY), CLEAR_TRANSCRIPT("Remove saved handwriting text…", EditorMenu.STUDY), TAPE("Reveal / cover study tape", EditorMenu.STUDY),
    SEARCH("Search text…", EditorMenu.NAVIGATE), BOOKMARKS("Bookmarks…", EditorMenu.NAVIGATE), HISTORY("Navigation history…", EditorMenu.NAVIGATE), ORGANIZER("All pages…", EditorMenu.NAVIGATE),
    DUPLICATE_PAGE("Duplicate page", EditorMenu.PAGES), MORE_ROOM("More room…", EditorMenu.PAGES), BOOKMARK("Bookmark page…", EditorMenu.PAGES), MOVE_PAGE("Move page…", EditorMenu.PAGES), COPY_PAGE("Copy page to notebook…", EditorMenu.PAGES), TRANSFER_PAGE("Move page to notebook…", EditorMenu.PAGES), LAYERS("Layers…", EditorMenu.PAGES), UNLOCK("Unlock page objects", EditorMenu.PAGES), CLEAR_INK("Clear page ink…", EditorMenu.PAGES), DELETE_PAGE("Delete page…", EditorMenu.PAGES),
    NATIVE_EXPORT("Notebook file…", EditorMenu.EXPORT), PDF_EXPORT("PDF copy…", EditorMenu.EXPORT), PNG_EXPORT("Current page image…", EditorMenu.EXPORT), TEXT_EXPORT("Text or Markdown…", EditorMenu.EXPORT),
    READ_ONLY("Read-only mode", EditorMenu.NOTEBOOK), RENAME("Rename notebook…", EditorMenu.NOTEBOOK), MERGE("Merge another notebook…", EditorMenu.NOTEBOOK),
    // P530 (F081/F082) task: work in two notebooks at once; scope: two open notebooks; home: the open notebook's menu; frequency: sometimes per session;
    // destructive: none (closing saves first and keeps both notebooks separate).
    OPEN_ALONGSIDE("Open alongside…", EditorMenu.NOTEBOOK), IMPORT("Import notebook…", EditorMenu.NOTEBOOK), IMPORT_PDF("Import PDF as new notebook…", EditorMenu.NOTEBOOK), SAVE("Save now", EditorMenu.NOTEBOOK), GESTURES("Gestures", EditorMenu.NOTEBOOK), SETTINGS("Settings", EditorMenu.NOTEBOOK);
    companion object {
        fun inMenu(menu: EditorMenu) = entries.filter { it.home == menu }
        /** P565: what this build offers. The study copy and handwriting-only export stay out of public builds; see PersonalTools.kt. */
        private val PERSONAL = setOf(PREPARE_STUDY, INK_EXPORT)
        fun offered(command: EditorCommand, context: android.content.Context) = context.personalTools || command !in PERSONAL
        fun shownIn(menu: EditorMenu, context: android.content.Context) = inMenu(menu).filter { offered(it, context) }
    }
}

/**
 * P178 (G3) feature record: page-strip actions are not menu commands. Task: choose several pages to delete or move them.
 * Scope: pages. Home: the round "Select" button at the top of the open page strip, then the bar at its foot. Frequency: occasional.
 * Destructive: Delete removes the chosen pages after a count confirmation, as one Undo step; one page always stays and
 * assets that remaining pages use stay. See MENU-AND-VISUAL-AUDIT.md.
 * P358: All pages has Pages / Bookmarks / PDF Contents tabs; source sections expand independently.
 * Contents navigation is read-only and resolves original PDF page identity; bulk actions stay in Pages.
 * P201 step 6 feature record: the page organizer. Task: see every page at once to find, reorder, move or delete pages.
 * Scope: pages. Home: the "All pages" grid button beside "Select" in the page strip, and ORGANIZER in Search and navigation.
 * Frequency: occasional. Destructive: the same Move…/Delete… and drag as the strip, with the same guards and Undo.
 */
internal enum class PageStripAction(val label: String) { SELECT("Select"), DONE("Done"), MOVE("Move…"), DELETE("Delete…"), ALL("All pages") }

/*
 * P183 (G8) feature record: the quick ring is not a menu command. Task: switch tool or ink colour. Scope: tool state only.
 * Home: a still finger held on the page (the tool bar stays primary). Frequency: frequent. Destructive: none. Finger only;
 * pen holds keep HoldAssist, locked objects keep Unlock.
 * P184 (G9) feature record: the command palette. Task: find and run any EditorCommand by name. Home: "Find a command…" at
 * the top of the Notebook (⋮) menu. Frequency: occasional. Destructive: none of its own; commands keep their guards.
 * P190 feature record: the colour circle is not a menu command. Task: pick any ink colour for the pen or highlighter.
 * Scope: tool state only. Home: the tool bar's all-colours dot and the "Choose any colour" swatch in the pen and
 * highlighter popovers, both opening a pop-out beside the tool bar (it replaces the centred "Choose color" dialog).
 * Frequency: occasional. Destructive: none; nothing changes until "Use colour", and tapping outside or Back cancels.
 * P192 (B27) feature record: restyle selected handwriting. Task: change the pen kind, colour and thickness of selected
 * ink together. Scope: selection. Home: "Style…" on the selection bar (it was "Color…"; "Width…" leaves More, since
 * the pop-out covers it). Frequency: occasional. Destructive: changes notebook ink, one Undo step on "Apply"; tapping
 * outside cancels. Highlighter and tape strokes keep their kind; every stroke keeps its opacity.
 * See MENU-AND-VISUAL-AUDIT.md.
 *
 * P193 (B31) feature record: paint brush. Task: brush over handwriting so it takes the chosen settings. Scope: the
 * touched strokes on the page's active layer. Home: a paint-roller tool after the eraser; tapping it again opens what it
 * paints and the selected pen's settings. Frequency: occasional. Destructive: changes notebook ink, one Undo step per
 * brush stroke; highlights take the highlighter's colour and thickness; tape and locked ink are skipped; every stroke
 * keeps its opacity. See MENU-AND-VISUAL-AUDIT.md.
 *
 * P201 step 9 feature record: the Study panel (STUDY_PANEL). Task: see the page's read handwriting and study tape, run a
 * Recall check, reach the Study tools. Scope: the open page. Home: first in the Study group of the Search popover.
 * Frequency: occasional. Destructive: none of its own; rows keep their commands' guards, and the Recall check only
 * covers and uncovers tape on screen. See MENU-AND-VISUAL-AUDIT.md.
 *
 * P617 feature record: Mix colours (highlighter). Task: choose whether overlapping highlights darken and blend. Scope:
 * the highlighter tool, every page. Home: a switch under the highlighter popover's colours, off at first. Frequency:
 * occasional. Destructive: none; display only, saved strokes unchanged. See MENU-AND-VISUAL-AUDIT.md.
 *
 * P619 feature record: pen and highlighter favourites. Task: keep the pens and highlighters you use. Scope: the tool bar,
 * every notebook. Home: a + after the pens and after the highlighters; hold one for Remove. Frequency: occasional.
 * Destructive: Remove drops that saved style only (never ink); the last pen and the last highlighter stay.
 *
 * P621 feature record: the slim bar. Task: fold the app bar and palette into one 26 dp strip. Scope: the editor. Home: the
 * fold button at the palette's end; the pin at the strip's right end brings the full toolbar back. Frequency: occasional
 * (it stays as chosen). Destructive: none. See MENU-AND-VISUAL-AUDIT.md.
 *
 * P625 feature record: the + menu. Task: add a pen of a chosen kind, or a chisel or fine highlighter. Scope: the tool bar
 * and the slim bar. Home: the + after the pens and after the highlighters opens it. Frequency: occasional. Destructive: none.
 *
 * P628 (board 12): the slim bar's areas are now wells inside one bar (back and name; pens and +; highlighters and +;
 * eraser, lasso, shapes, text, tape; colours; Undo and Redo; Study, Search and the menu; the pin). Nothing moved.
 *
 * P630 feature record: fold into the slim bar when writing. Task: the full toolbar turns into the slim bar by itself once
 * a stroke is written, the page staying still. Scope: app setting. Home: Settings, Pages & layout, Toolbar, "Fold into
 * the slim bar when I write" (off until chosen). Frequency: set once. Destructive: none; the pin and the fold button
 * still switch by hand.
 *
 * P631: a palette that scrolls keeps the fold button still at its end, outside the scroll. P632: where the slim bar
 * cannot fit (a phone, or a tablet held upright with many pens and highlighters) the fold button is hidden and the full
 * toolbar shows; a slim bar chosen comes back by itself once it fits again. Nothing else moved.
 */

/** F054 / P372 menu placement: selected closed shapes use the existing Style… pop-out for
 * independent outline colour/thickness and Fill (No fill, swatches, any-colour wheel).
 * Selection scope, occasional task, one Undo; no new menu or design surface. */

/* P388 F120: approved Glass password sheet is contextual to locked-PDF Library import, Insert PDF pages
 * and active-page reopen. No menu duplication. Show/Remember are device-local; Cancel never alters notebook originals. */

// P399 F090: Study menu / command palette toggles temporary stylus pointing. No document operation or new surface.

/* P413 auto-refine R1 feature record: Refine… Task: tidy chosen handwriting (level lines, rest words on the line or rule,
 * even size, slant and spacing, smooth pen ticks and shake). Scope: selection. Home: "Refine…" on the selection bar,
 * beside Style… Frequency: occasional. Destructive: changes notebook ink; the page is the preview (Natural on opening,
 * Original/Gentle/Neat swap in place) and one Undo puts it back. Highlights, tape, shapes, locked and linked ink are
 * skipped. On-device only. "Straighten" stays in More for turning a line without tidying it. See AUTO-REFINE-DESIGN.md. */

/* P415 handwriting style feature record. Task: rewrite handwriting in a chosen professional hand, bent toward the
 * writer (Letters, Rhythm, P416 Heights, Level lines), tried first on a writing pad with a live preview beside it. Scope: app setting;
 * applies to pen lines written afterwards. Home: "Handwriting style" row in the pen popover, opening the Handwriting
 * style sheet (pad left, preview right, 15 hands since P417, sliders, "Improve my handwriting as I write"). Frequency: set once.
 * Destructive: with the switch on, each finished word is replaced by rewritten strokes (P416: about 0.8 s after it, cross-faded), one Undo per batch puts
 * the exact originals back; unread words, arrows, highlights, tape, shapes, locked, linked, recorded and partly erased
 * ink stay as written. On-device recognizer and bundled OFL fonts only. Not in Pages/More. See AUTO-REFINE-DESIGN.md §9. */

// P401 F108: Capture → Video filters the existing retained-file picker. Card → Play video hands out a read-only copy.
