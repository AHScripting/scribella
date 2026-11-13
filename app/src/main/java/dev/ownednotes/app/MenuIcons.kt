package dev.ownednotes.app

/**
 * P605: the icon-grid menus' drawings and short names. Each drawing is SVG path data on a 24-unit grid, stroked at 1.75
 * with round ends by [Kit.Icon]; the redesign sketches use these same strings, so a tile looks exactly as drawn there.
 * The short name sits under the tile; the command's full label stays the tile's spoken name and long-press tip.
 */
internal object MenuIcons {
    const val PAPER = Kit.Paths.PAPER
    const val DUPLICATE = "M8 8h12v12H8zM16 8V4H4v12h4"
    const val MORE_ROOM = "M5 4h14M5 20h14M12 7v10M9 9.5l3-3 3 3M9 14.5l3 3 3-3"
    const val BOOKMARK = "M6.5 3.5h11v17L12 16.5l-5.5 4z"
    const val MOVE = "M12 3.5v17M8.5 7L12 3.5 15.5 7M8.5 17l3.5 3.5 3.5-3.5"
    const val COPY_PAGE = "M9 3.5h8l3 3v11H9zM6 7v13.5h9"
    const val TRANSFER = "M4 6.5h6l2 2h8v10H4zM10 13.5h6M13.5 11l2.5 2.5-2.5 2.5"
    const val LAYERS = "M12 4l8.5 4.5L12 13 3.5 8.5zM3.5 12.5L12 17l8.5-4.5M3.5 16.5L12 21l8.5-4.5"
    const val UNLOCK = "M6 11h12v9.5H6zM8.5 11V7.5a3.5 3.5 0 0 1 6.8-1.2"
    const val ERASER = "M9 19.5l-4.5-4.5a1.5 1.5 0 0 1 0-2.1l8.4-8.4a1.5 1.5 0 0 1 2.1 0l4.5 4.5a1.5 1.5 0 0 1 0 2.1L13 19.5zM9 19.5h11M8 9.5l6.5 6.5"
    const val TRASH = "M4.5 6.5h15M9.5 6.5v-2h5v2M6.5 6.5l1 13.5h9l1-13.5M10 10.5v6M14 10.5v6"
    const val PASTE = "M9 4.5h6v2.5H9zM9 5.5H6.5v14.5h11V5.5H15"
    const val BLANK = "M6 3h9l4 4v14H6zM15 3v4h4M12.5 11v6M9.5 14h6"
    const val TEXT_BOX = "M4 5h16v14H4zM8 9h8M12 9v7"
    const val STICKY = "M5 5h14v9l-5 5H5zM14 19v-5h5"
    const val TABLE = "M4 5h16v14H4zM4 10h16M4 14.5h16M10 5v14"
    const val STAR = "M12 4l2.4 5 5.4.6-4 3.7 1.1 5.3L12 16l-4.9 2.6 1.1-5.3-4-3.7 5.4-.6z"
    const val STAMP = "M9.5 4h5v4l-1 1.5V12h-3V9.5l-1-1.5zM5 12h14v4H5zM6.5 20h11"
    const val VIDEO = "M3.5 7h12v10h-12zM15.5 10.5l5-3v9l-5-3"
    const val SCAN = "M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4M4 12h16"
    const val CAMERA = Kit.Paths.CAMERA
    const val PDF_PAGES = "M8 3h8l4 4v11H8zM16 3v4h4M5 7v14h11"
    const val IMAGE = Kit.Paths.IMAGE
    const val CLIP = "M16.5 7.5l-7.6 7.6a2 2 0 0 0 2.8 2.8l8-8a4 4 0 0 0-5.7-5.7l-8 8a6 6 0 0 0 8.5 8.5l6.5-6.5"
    const val LINK = "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"
    const val SEARCH = "M10.5 4a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13zM15.5 15.5L20 20"
    const val HISTORY = "M4.5 12a7.5 7.5 0 1 0 2.2-5.3M4.5 4v3.5H8M12 8v4.5l3 1.8"
    const val GRID = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"
    const val SELECT_TEXT = "M9 4h1.5A1.5 1.5 0 0 1 12 5.5v13a1.5 1.5 0 0 1-1.5 1.5H9M15 4h-1.5A1.5 1.5 0 0 0 12 5.5v13a1.5 1.5 0 0 0 1.5 1.5H15M9.5 12h5"
    const val LASER = "M4 20l9.4-12.4M18.5 5.5a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"
    const val BOOK = "M12 6.5C10 5 7 4.5 3.5 5v13.5c3.5-.5 6.5 0 8.5 1.5 2-1.5 5-2 8.5-1.5V5C17 4.5 14 5 12 6.5zM12 6.5V20"
    const val SCRIBBLE = "M3.5 17c2-3 3.5-9 5.5-9s0 9 2 9 2.5-5 4-5 1 3 2.5 3 2-1 3-2"
    const val PAGE_TEXT = "M6 3h9l4 4v14H6zM15 3v4h4M9 11h7M9 14.5h7M9 18h4"
    const val MARKER = "M4 20h7M9.5 15.5l-2-2 9-9a1.4 1.4 0 0 1 2 2zM7.5 13.5l-2.5 4h3.5l1-2"
    const val SPARKLE = "M12 3.5l1.8 5.2 5.2 1.8-5.2 1.8L12 17.5l-1.8-5.2L5 10.5l5.2-1.8zM18.5 16v4M16.5 18h4"
    const val SHARE_OUT = "M12 14V3.5M8 7.5l4-4 4 4M5 12v8h14v-8"
    /** P621 (board 10): a roll of study tape, pulled out with a torn end. */
    const val TAPE = "M4 6h16M4 18h11M3 9.5h13l2 1.25-2 1.25 2 1.25-2 1.25H3z"
    const val EYE = "M2.5 12c2-4 5.5-6.5 9.5-6.5s7.5 2.5 9.5 6.5c-2 4-5.5 6.5-9.5 6.5S4.5 16 2.5 12zM12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6z"
    const val PEN = Kit.Paths.PEN
    const val MERGE = "M6 4v4.5c0 3 6 3.5 6 7.5v4.5M18 4v4.5c0 3-6 3.5-6 7.5M9 17.5l3 3 3-3"
    const val SPLIT = "M3.5 5h17v14h-17zM12 5v14"
    const val IMPORT = "M12 3.5V14M8 10l4 4 4-4M5 12v8h14v-8"
    const val IMPORT_PDF = "M6 3h9l4 4v14H6zM15 3v4h4M12.5 10.5V17M9.5 14l3 3 3-3"
    const val SAVE = "M5 4h11l3 3v13H5zM8 4v5h7V4M8 20v-6h8v6"
    const val HAND = Kit.Paths.HAND
    const val SLIDERS = "M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4"
    const val PACKAGE = "M12 3.5l8 4.2v8.6L12 20.5l-8-4.2V7.7zM4 7.7l8 4.3 8-4.3M12 12v8.5"
    const val PDF_OUT = "M6 3h9l4 4v14H6zM15 3v4h4M12.5 17v-6.5M9.5 13.5l3-3 3 3"
    const val LINES = "M4.5 6h15M4.5 10h15M4.5 14h15M4.5 18h9"
    // The Library's menus.
    const val NEW_FOLDER = "M3 6.5h6l2 2h10v10.5H3zM12 11.5v5M9.5 14h5"
    const val SELECT = "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17zM8.5 12.2l2.4 2.4 4.6-4.8"
    /** P612: Select's two parts for its Done state: a filled disc and the tick drawn over it. */
    const val SELECT_DISC = "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17z"
    const val SELECT_TICK = "M8.5 12.2l2.4 2.4 4.6-4.8"
    const val BACKUP = "M7 18.5a4.5 4.5 0 0 1-.6-9A6 6 0 0 1 18 9.5a4.5 4.5 0 0 1-.5 9M12 19.5v-7M9.5 15l2.5-2.5 2.5 2.5"
    const val SYNC = "M19.5 12a7.5 7.5 0 0 1-13.1 5M4.5 12a7.5 7.5 0 0 1 13.1-5M17.6 3.5V7h-3.5M6.4 20.5V17h3.5"
    const val LOCK = Kit.Paths.LOCK
    const val SHIELD = Kit.Paths.SHIELD
    const val ARCHIVE = "M3.5 4.5h17v4h-17zM5 8.5v11h14v-11M10 12.5h4"
    const val OFFLINE = "M12 4v10M8 10l4 4 4-4M5 19.5h14"
    const val PIN = "M9 3.5h6M10 3.5v6l-3 3.5h10l-3-3.5v-6M12 13v7.5"
    const val MOVE_FOLDER = "M3 6.5h6l2 2h10v10.5H3zM9.5 14h5.5M12.5 11.5L15 14l-2.5 2.5"
    const val COVER = "M6 3.5h11.5v17H6zM9 3.5v17M12 8h3"
    const val TAG = "M3.5 12.3V4.5h7.8l9 9-7.8 7.8zM8 8h.01"

    /** Family colours: one per editor menu, red for anything that removes work. Matches the sketches' palette. */
    const val INSERT = 0xff0a66d9.toInt()
    const val CAPTURE = 0xff168f84.toInt()
    const val STUDY = 0xff6f52e0.toInt()
    const val NAVIGATE = 0xff3f5bd9.toInt()
    const val PAGES = 0xffd9821c.toInt()
    const val EXPORT = 0xff2b9156.toInt()
    const val NOTEBOOK = 0xff667085.toInt()

    fun path(command: EditorCommand): String = when (command) {
        EditorCommand.PASTE -> PASTE; EditorCommand.BLANK -> BLANK; EditorCommand.TEXT -> TEXT_BOX; EditorCommand.STICKY_NOTE -> STICKY
        EditorCommand.TABLE -> TABLE; EditorCommand.STICKERS -> STAR; EditorCommand.STAMPS -> STAMP
        EditorCommand.VIDEO -> VIDEO; EditorCommand.SCAN -> SCAN; EditorCommand.PHOTO -> CAMERA; EditorCommand.PDF_PAGES -> PDF_PAGES
        EditorCommand.IMAGE_PAGE -> IMAGE; EditorCommand.ATTACH -> CLIP; EditorCommand.PDF_SOURCES -> LINK
        EditorCommand.PDF_SELECT_TEXT -> SELECT_TEXT; EditorCommand.LASER -> LASER; EditorCommand.STUDY_PANEL -> BOOK
        EditorCommand.HANDWRITING -> SCRIBBLE; EditorCommand.PAGE_TEXT -> PAGE_TEXT; EditorCommand.TEXT_MARK -> MARKER
        EditorCommand.PREPARE_STUDY -> SPARKLE; EditorCommand.INK_EXPORT -> SHARE_OUT; EditorCommand.CLEAR_TRANSCRIPT -> TRASH; EditorCommand.TAPE -> TAPE
        EditorCommand.SEARCH -> SEARCH; EditorCommand.BOOKMARKS -> BOOKMARK; EditorCommand.HISTORY -> HISTORY; EditorCommand.ORGANIZER -> GRID
        EditorCommand.DUPLICATE_PAGE -> DUPLICATE; EditorCommand.MORE_ROOM -> MORE_ROOM; EditorCommand.BOOKMARK -> BOOKMARK; EditorCommand.MOVE_PAGE -> MOVE
        EditorCommand.COPY_PAGE -> COPY_PAGE; EditorCommand.TRANSFER_PAGE -> TRANSFER; EditorCommand.LAYERS -> LAYERS; EditorCommand.UNLOCK -> UNLOCK
        EditorCommand.CLEAR_INK -> ERASER; EditorCommand.DELETE_PAGE -> TRASH
        EditorCommand.NATIVE_EXPORT -> PACKAGE; EditorCommand.PDF_EXPORT -> PDF_OUT; EditorCommand.PNG_EXPORT -> IMAGE; EditorCommand.TEXT_EXPORT -> LINES
        EditorCommand.READ_ONLY -> EYE; EditorCommand.RENAME -> PEN; EditorCommand.MERGE -> MERGE; EditorCommand.OPEN_ALONGSIDE -> SPLIT
        EditorCommand.IMPORT -> IMPORT; EditorCommand.IMPORT_PDF -> IMPORT_PDF; EditorCommand.SAVE -> SAVE; EditorCommand.GESTURES -> HAND
        EditorCommand.SETTINGS -> SLIDERS
    }

    fun short(command: EditorCommand): String = when (command) {
        EditorCommand.PASTE -> "Paste"; EditorCommand.BLANK -> "Blank page"; EditorCommand.TEXT -> "Text box"; EditorCommand.STICKY_NOTE -> "Sticky note"
        EditorCommand.TABLE -> "Table"; EditorCommand.STICKERS -> "Stickers"; EditorCommand.STAMPS -> "Stamps"
        EditorCommand.VIDEO -> "Video"; EditorCommand.SCAN -> "Scan"; EditorCommand.PHOTO -> "Photo"; EditorCommand.PDF_PAGES -> "PDF pages"
        EditorCommand.IMAGE_PAGE -> "Image page"; EditorCommand.ATTACH -> "Attach file"; EditorCommand.PDF_SOURCES -> "PDF sources"
        EditorCommand.PDF_SELECT_TEXT -> "Select PDF text"; EditorCommand.LASER -> "Laser"; EditorCommand.STUDY_PANEL -> "Study panel"
        EditorCommand.HANDWRITING -> "Read writing"; EditorCommand.PAGE_TEXT -> "Copy page text"; EditorCommand.TEXT_MARK -> "Highlight text"
        EditorCommand.PREPARE_STUDY -> "Study copy"; EditorCommand.INK_EXPORT -> "Export ink"; EditorCommand.CLEAR_TRANSCRIPT -> "Clear text"; EditorCommand.TAPE -> "Study tape"
        EditorCommand.SEARCH -> "Search"; EditorCommand.BOOKMARKS -> "Bookmarks"; EditorCommand.HISTORY -> "History"; EditorCommand.ORGANIZER -> "All pages"
        EditorCommand.DUPLICATE_PAGE -> "Duplicate"; EditorCommand.MORE_ROOM -> "More room"; EditorCommand.BOOKMARK -> "Bookmark"; EditorCommand.MOVE_PAGE -> "Move"
        EditorCommand.COPY_PAGE -> "Copy to notebook"; EditorCommand.TRANSFER_PAGE -> "Move to notebook"; EditorCommand.LAYERS -> "Layers"; EditorCommand.UNLOCK -> "Unlock"
        EditorCommand.CLEAR_INK -> "Clear ink"; EditorCommand.DELETE_PAGE -> "Delete"
        EditorCommand.NATIVE_EXPORT -> "Notebook file"; EditorCommand.PDF_EXPORT -> "PDF"; EditorCommand.PNG_EXPORT -> "Page image"; EditorCommand.TEXT_EXPORT -> "Text"
        EditorCommand.READ_ONLY -> "Read only"; EditorCommand.RENAME -> "Rename"; EditorCommand.MERGE -> "Merge"; EditorCommand.OPEN_ALONGSIDE -> "Side by side"
        EditorCommand.IMPORT -> "Import"; EditorCommand.IMPORT_PDF -> "PDF as notebook"; EditorCommand.SAVE -> "Save now"; EditorCommand.GESTURES -> "Gestures"
        EditorCommand.SETTINGS -> "Settings"
    }

    fun tint(menu: EditorMenu): Int = when (menu) {
        EditorMenu.INSERT -> INSERT; EditorMenu.CAPTURE -> CAPTURE; EditorMenu.STUDY -> STUDY; EditorMenu.NAVIGATE -> NAVIGATE
        EditorMenu.PAGES -> PAGES; EditorMenu.EXPORT -> EXPORT; EditorMenu.NOTEBOOK -> NOTEBOOK
    }
}
