# Changelog

Scribella is an alpha: expect rough edges, and export your notebooks now and then so you always have a copy.

## 0.3.0 alpha

The third alpha adds a floating second page, lets you choose the two-page pairs, finishes the slim bar and makes the app sturdier with damaged pages and slow cloud folders.

### New

- **Peek.** A floating window shows another page of the notebook while you write. Move it, resize it, or fold it into a small bubble at the edge of the screen; drag the bubble anywhere and tap it to open it again.
- **Show beside.** In the landscape two-page view, choose which page sits next to this one (Page actions). The pages around it pair up on their own.
- **Add highlights from PDF.** Under Study, pick a marked-up copy of the notebook's PDF and its highlights come into the notebook as highlighter ink you can erase or recolour, in one Undo step. The same file twice adds nothing.
- **Imported PDFs of another shape.** When a PDF's pages are a different shape from your paper, Scribella asks once: *Fit to my paper* (the slide at the top, room to write below) or *Keep the PDF's size*, with *Remember for next time*. Settings → Pages & layout → Imported PDFs changes it.

### Slim bar

- It now holds every button of the full toolbar. Buttons that do not fit wait behind ». Pens stand tip up, as on the full toolbar.
- The page buttons sit in the middle, and *Show pages* has its own button by the pin.
- The notebook's name is a button beside Back. It opens a small pill with the name and a pencil: tap the pencil to rename, and the check keeps the new name.
- An S Pen hovering over a button names it.

### Fixed

- **Fountain pen on full pages.** A page full of fountain writing draws about as fast as ballpoint and looks the same.
- **Two-page view.** The second page of a PDF or picture is as sharp as the first. A page with landscape paper shows alone and fills the screen. Turning between pages of different sizes lands where the page will show.
- **Damaged pages.** A notebook with a damaged page opens its other pages; the damaged one shows blank, marked "Damaged page", and a copy of the damaged file is kept.
- **Slow cloud folders.** Sync, backups and restores run in their own process with time limits, so a cloud folder that stops answering can no longer freeze Scribella; it fails after a minute with a message instead.

## 0.2.0 alpha

The second alpha brings a new way to hold the toolbar, pens you choose yourself, PDF markings that survive import, and a long list of fixes for phones, large text and screen readers.

### New

- **Slim bar.** The button at the end of the toolbar folds everything into one thin, floating strip at the top. Rest the pen or a finger on a pen to see its tip and a sample line, slide along to pick another, and tap the pen in hand for all its settings. The pin brings the full toolbar back. A new setting, *Fold into the slim bar when I write*, does the folding for you as soon as you start writing. The page glides to its new size instead of jumping. On a phone, or wherever the slim bar would not fit, the full toolbar stays.
- **Your own pens and highlighters.** The app starts with one pen and one highlighter. The + after each adds another (up to eight) from a menu of pen types (calligraphy, fountain, ballpoint, pencil, pressure, dashed) or highlighter tips (chisel or fine). Hold one to remove it.
- **Fountain pen** with two nibs, Everyday and Soft: wider when you write slowly and on downstrokes, thinner on quick upstrokes. Notebooks with fountain pen writing need 0.2.0 or later to open.
- **Highlighters** look like real ones and come in bright colours. Highlights start and end with straight, upright edges. A *Mix colours* switch lets overlapping highlights blend and darken; it is off at first, so going over a highlight no longer darkens it.
- **PDF markings come with the PDF.** Highlights, underlines, strike-outs, squiggly underlines and pen drawings already in a PDF show up when you import it, as ink you can erase or recolour. The import report names sticky notes, typed comments and form fields that stay out.
- **Palm rest.** Writing with a rubber-tip pen or a finger, you can rest your hand on the screen without ending the line or moving the page. Two fingers put down together still move and zoom.
- **Page turns like paper.** In portrait the page rolls over with soft light and shadow, follows the speed of your swipe and settles without a flicker.
- **Drag to a folder.** In the Library, hold a notebook and drag it onto a folder to move it there.
- **What's new and update check.** After an update a short note shows once. An automatic update check, off until you turn it on, asks GitHub at most once a day and tells you once about each new version. Nothing about your notebooks is sent.
- **Full screen.** The status bar is hidden while you use the app; swipe down from the top to see it for a moment.

### Redesigned

- The editor menus (Insert, Search, Page and paper, More), the Library menu and each notebook's menu are grids of icons with short names. More starts with a *Find a command* box, and a notebook's menu shows its cover, title and page count.
- Page & paper and the Study panel fit on one screen.
- The Pages panel floats as a rounded card: the current page has a blue ring, bookmarked pages an orange ribbon, and holding a page shows Duplicate, Settings and Delete beside it.
- Search in notebook is a glass panel with page snapshots of each match.
- The colour picker and the Shapes board have the glass look.
- Pen and highlighter settings close by themselves as soon as you start writing.

### Fixed

- **Phones.** The Library's side panel slides out from a menu button, two notebooks show side by side, the editor's top bar, tool palette, pen settings, sideways toolbars, Pages panel and Export choices all fit, and nothing is cut short.
- **Large text.** Titles, buttons, page numbers, pen names, folder and tag chips grow with the text and no longer split words or run under other controls. Long names end in "…" instead of shrinking.
- **Screen readers.** Every control has a name, a row with a switch is one stop that says whether it is on, and only the tool in use says "selected".
- **Right-to-left languages.** The app keeps its normal layout instead of a half-mirrored one; times and numbers match the rest of the app.
- **Turning the tablet** with a sheet open re-fits it to the new screen; a popover or sheet no longer stays behind when the screen switches between day and night.
- **Back** in a Library folder or search goes up a folder or clears the search; with the Study panel or flashcards open it closes them.
- **Match pen size** and **Match page shape** on a long notebook no longer show a recovery error.
- The eraser keeps up on pages full of writing.
- Scrolling a notebook no longer flashes pages blank, and in a landscape spread a new page always appears in the same place.
- Page previews in Page & paper show the chosen lines, grid or dots again; in the light look the page sits on a grey well.
- At night, a few faint words are lighter so they can be read.
- With many tags, the Tags sheet keeps its typing field and buttons on screen.

## 0.1.0 alpha

The first public alpha.
