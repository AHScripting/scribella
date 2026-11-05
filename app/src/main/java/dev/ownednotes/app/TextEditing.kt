package dev.ownednotes.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.ownednotes.core.*

/** Dialog values belong to one immutable snapshot, one page and optionally one existing text box. */
data class TextEditTarget(val notebook: Notebook, val pageId: String, val textId: String? = null) {
    val box get() = notebook.pages.firstOrNull { it.id == pageId }?.textBoxes?.firstOrNull { it.id == textId }
}

internal data class TextFormDraft(val text: String, val font: Int, val size: String, val weight: Int,
    val italic: Boolean, val underline: Boolean, val alignment: Int, val color: String, val opacity: Int,
    val selectionStart: Int, val selectionEnd: Int, val noteColor:String?=null)
internal data class TextEditorState(val target: TextEditTarget, var draft: TextFormDraft? = null, val sticky:Boolean=false)
internal data class TextEditorHost(val state: TextEditorState, val dialog: AlertDialog, val form: TextEditorForm)

fun NotebookRepository.captureTextEditTarget(textId: String? = null): TextEditTarget? {
    if (readOnly || !canChangeNotebook) return null
    val note = session?.notebook ?: return null
    val page = currentPage ?: return null
    if (textId != null && page.textBoxes.none { it.id == textId }) return null
    return TextEditTarget(note,page.id,textId)
}

fun NotebookRepository.canEditText(target: TextEditTarget): Boolean = !readOnly && canChangeNotebook &&
    session?.notebook === target.notebook && selectedPageId == target.pageId &&
    target.notebook.pages.any { it.id == target.pageId } && (target.textId == null || target.box != null)

/** Returns an inserted/retained ID, or null for a stale/busy/reading target. Invalid values throw before commit. */
fun NotebookRepository.applyTextEdit(target: TextEditTarget, text: String, style: TextStyle = target.box?.style ?: TextStyle(), sticky:StickyNote?=target.box?.sticky): String? {
    if (!canEditText(target)) return null
    val page = target.notebook.pages.first { it.id == target.pageId }
    val original = target.box
    var id: String? = null
    if (original != null) {
        val changed = original.copy(text=text,style=style,sticky=sticky)
        edit { id=original.id; it.updateTextBox(page.id,changed) }
    } else {
        val w=maxOf(1f,page.width*(if(sticky!=null).35f else .8f)); val h=maxOf(1f,minOf(240f,page.height*(if(sticky!=null).2f else .3f)))
        val placement=ImagePlacement((page.width-w)/2,page.height*.1f,w,h)
        edit { id=it.insertTextBox(page.id,text,placement,style,sticky);true }
    }
    return id
}

/**
 * Draft-only visual style samples; no notebook changes until Save. P201 step 5: laid out as the Selection board's
 * text box: the text in an accent-edged box, a glass format bar under it (font, A− size A+, bold, italic,
 * underline, alignment, colour), the colour, opacity and weight in a panel the colour key opens, then the preview.
 * The fields keep their types, so drafts, guards and tests read them as before.
 */
internal class TextEditorForm(context: Context, text: String, initial: TextStyle, private val initialSticky:StickyNote?=null) : LinearLayout(context) {
    private val night=context.night
    internal val noteColorInput=initialSticky?.let {RgbColorPicker(context,it.color).apply {contentDescription="Sticky note colour";visibility=View.GONE}}
    val textInput=EditText(context).apply {
        contentDescription="Text box content"
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines=4;maxLines=8;filters=arrayOf(InputFilter.LengthFilter(TextBox.MAX_TEXT_LENGTH));setText(text)
        textSize=18f;setTextColor(Glass.text(night));gravity=android.view.Gravity.TOP or android.view.Gravity.START
        background=rounded(if (night) Kit.group(true) else 0xe6ffffff.toInt(),dp(10f),Glass.accent(night),dpi(1.5f))
        setPadding(dpi(18f),dpi(14f),dpi(18f),dpi(14f))
    }
    /** A spinner whose closed face is drawn by [face]; [row] shows each option in its open list. */
    private fun spinner(label: String, items: List<String>, selected: Int, row: ((TextView, Int) -> Unit)? = null, face: ((TextView, Int) -> Unit)? = null) = Spinner(context).apply {
        contentDescription=label
        adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_spinner_dropdown_item,items) {
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View =
                if (face==null) super.getView(position,convertView,parent) else TextView(context).also { face(it,position) }
            override fun getDropDownView(position:Int,convertView:View?,parent:android.view.ViewGroup):View =
                super.getDropDownView(position,convertView,parent).also { if(row!=null)row(it as TextView,position) }
        }
        if (face!=null) background=null
        setSelection(selected)
    }
    private fun changeList(kind:TextListEditing.Kind?=null) {
        val value=textInput.text.toString()
        val start=textInput.selectionStart.coerceIn(0,value.length);val end=textInput.selectionEnd.coerceIn(0,value.length)
        val result=if(kind==null)TextListEditing.toggleChecks(value,start,end) else TextListEditing.apply(value,start,end,kind)
        if(result.text.length>TextBox.MAX_TEXT_LENGTH){textInput.error="The list would exceed the text box limit";return}
        if(result.text!=value)textInput.text.replace(0,textInput.length(),result.text)
        textInput.setSelection(result.selectionStart,result.selectionEnd);textInput.error=null
    }
    internal val bulletInput=Kit.key(context,"•","Bulleted list") { changeList(TextListEditing.Kind.BULLET) }
    internal val numberInput=Kit.key(context,"1.","Numbered list") { changeList(TextListEditing.Kind.NUMBER) }
    internal val checklistInput=Kit.key(context,"☐","Checklist") { changeList(TextListEditing.Kind.CHECKLIST) }
    internal val checkInput=Kit.key(context,"✓","Check or uncheck selected items") { changeList() }
    private val faces=listOf(android.graphics.Typeface.SANS_SERIF,android.graphics.Typeface.SERIF,android.graphics.Typeface.MONOSPACE)
    private val fontNames=listOf("Sans serif","Serif","Monospace")
    val fontInput=spinner("Font",fontNames,initial.font.ordinal,{ view,position -> view.typeface=faces[position] }) { view,position ->
        view.text=fontNames[position];view.textSize=14.5f;view.typeface=android.graphics.Typeface.create(faces[position],600,false);view.setTextColor(Glass.text(night))
        view.minHeight=dpi(44f);view.gravity=android.view.Gravity.CENTER_VERTICAL;view.setPadding(dpi(12f),0,dpi(8f),0);view.background=null
        view.setCompoundDrawablesRelative(null,null,Kit.Icon("M7 10l5 5 5-5",Glass.text(night),dpi(14f)).apply { setBounds(0,0,dpi(14f),dpi(14f)) },null)
        view.compoundDrawablePadding=dpi(6f)
    }
    val sizeInput=EditText(context).apply {
        contentDescription="Font size";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine();setText(shown(initial.size))
        textSize=14f;typeface=Tokens.SANS_SEMIBOLD;fontFeatureSettings="tnum";setTextColor(Glass.text(night))
        gravity=android.view.Gravity.CENTER;background=null;minWidth=dpi(40f);setPadding(dpi(2f),0,dpi(2f),0)
    }
    private val weights=((100..900 step 100).toList()+initial.weight).distinct().sorted()
    val weightInput=spinner("Font weight",weights.map { it.toString() },weights.indexOf(initial.weight),{ view,position -> view.typeface=android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,weights[position],false) })
    val italicInput=CheckBox(context).apply { this.text="Italic";isChecked=initial.italic }
    val underlineInput=CheckBox(context).apply { this.text="Underline";isChecked=initial.underline }
    private val alignPaths=listOf("M4 6h16M4 12h10M4 18h16","M4 6h16M7 12h10M4 18h16","M4 6h16M10 12h10M4 18h16")
    val alignmentInput=spinner("Alignment",listOf("Start","Center","End"),initial.alignment.ordinal,{ view,position ->
        view.setCompoundDrawablesRelative(Kit.Icon(alignPaths[position],view.currentTextColor,dpi(22f)).apply { setBounds(0,0,dpi(22f),dpi(22f)) },null,null,null)
        view.compoundDrawablePadding=dpi(12f)
    }) { view,position ->
        view.text="";view.minWidth=dpi(44f);view.minHeight=dpi(44f);view.setPadding(0,0,0,0)
        view.background=android.graphics.drawable.InsetDrawable(Kit.Icon(alignPaths[position],Glass.text(night),dpi(22f)),dpi(11f))
    }
    val colorInput=RgbColorPicker(context,0xff000000L or (initial.color and 0xffffff))
    val opacityInput=SeekBar(context).apply { max=255;progress=(initial.color ushr 24).toInt();contentDescription="Text opacity" }
    private val opacityLabel=TextView(context)
    internal val stylePreview=TextStylePreview(context)
    private val previewIssue=ToolControls.caption(context,"")
    private val bold=Kit.key(context,"B","Bold") {
        val heavy=weights[weightInput.selectedItemPosition]>=600
        weightInput.setSelection(weights.indexOf(if (heavy) 400 else 700).coerceAtLeast(0));updatePreview()
    }
    private val dotFill=android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL;setStroke(dpi(2f),if (night) Glass.PANE_NIGHT else -1) }
    /** Colour, opacity and weight, opened from the colour key. */
    internal val stylePanel=LinearLayout(context).apply { orientation=VERTICAL;visibility=View.GONE }
    private val colorKey=Kit.key(context,"","Text colour, opacity and weight") {
        stylePanel.visibility=if (stylePanel.visibility==View.VISIBLE) View.GONE else View.VISIBLE;updatePreview()
    }
    init {
        tag="text-box-form";orientation=VERTICAL;setPadding(dpi(16f),dpi(8f),dpi(16f),dpi(12f))
        addView(textInput,LayoutParams(-1,-2))
        noteColorInput?.let { picker ->
            addView(Kit.section(context,"Note colour"))
            val colours=listOf(0xffffe59a.toInt() to "Yellow",0xffffc2cb.toInt() to "Pink",0xffbde7ff.toInt() to "Blue",0xffc7edbb.toInt() to "Green",0xffe3ceff.toInt() to "Purple")
            val swatches=Kit.Swatches(context,colours,colours.indexOfFirst {it.first==initialSticky!!.color.toInt()}.takeIf {it>=0}) { chosen ->picker.hexInput.setText(String.format(java.util.Locale.ROOT,"#%06x",colours[chosen].first and 0xffffff))}
            addView(swatches)
            picker.hexInput.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){runCatching {picker.color().toInt()}.onSuccess {swatches.mark(it)}}
                override fun afterTextChanged(s:Editable?){}
            })
            addView(Kit.button(context,"Choose any note colour",Kit.Button.PLAIN,small=true) {picker.visibility=if(picker.visibility==View.VISIBLE)View.GONE else View.VISIBLE})
            addView(picker)
        }

        fun step(by: Float) { val size=sizeInput.text.toString().trim().toFloatOrNull() ?: return;sizeInput.setText(shown((size+by).coerceIn(4f,512f))) }
        val smaller=Kit.key(context,"A","Smaller text") { step(-2f) }.apply { textSize=13f }
        val larger=Kit.key(context,"A","Larger text") { step(2f) }.apply { textSize=19f }
        bold.typeface=android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,800,false)
        fun toggle(box: CheckBox, label: String, face: CharSequence) = box.apply {
            buttonDrawable=null;this.text=face;contentDescription=label;tooltipText=label
            gravity=android.view.Gravity.CENTER;minWidth=dpi(44f);minHeight=dpi(44f);setPadding(0,0,0,0);textSize=16f
            foreground=ripple(night,dp(12f))
        }
        toggle(italicInput,"Italic","I").typeface=android.graphics.Typeface.create(android.graphics.Typeface.SERIF,android.graphics.Typeface.ITALIC)
        toggle(underlineInput,"Underline",android.text.SpannableString("U").apply { setSpan(android.text.style.UnderlineSpan(),0,1,0) })
        colorKey.background=null
        addView(Kit.formatBar(context,listOf(listOf(fontInput),listOf(smaller,sizeInput,larger),listOf(bold,italicInput,underlineInput),listOf(alignmentInput),listOf(colorKey),listOf(bulletInput,numberInput,checklistInput,checkInput))),LayoutParams(-1,-2).apply { topMargin=dpi(10f) })
        stylePanel.addView(Kit.section(context,"Colour"));stylePanel.addView(colorInput)
        fun showOpacity() { opacityLabel.text="Opacity ${(opacityInput.progress*100f/255f).toInt()}%" }
        opacityLabel.apply { textSize=13f;typeface=Tokens.SANS_SEMIBOLD;setTextColor(Glass.secondary(night));setPadding(0,dpi(10f),0,0) }
        opacityInput.apply {
            val fill=android.graphics.drawable.ClipDrawable(rounded(Glass.accent(night),dp(4f)),android.view.Gravity.START,android.graphics.drawable.ClipDrawable.HORIZONTAL)
            progressDrawable=android.graphics.drawable.LayerDrawable(arrayOf(rounded(Kit.track(night),dp(4f)),fill)).apply {
                setId(0,android.R.id.background);setId(1,android.R.id.progress)
                for (i in 0..1) { setLayerGravity(i,android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.FILL_HORIZONTAL);setLayerHeight(i,dpi(8f)) }
            }
            thumb=ToolControls.Knob(dpi(30f),dp(15f));splitTrack=false;background=null;setPadding(dpi(15f),0,dpi(15f),0)
        }
        showOpacity();stylePanel.addView(opacityLabel);stylePanel.addView(opacityInput,LayoutParams(-1,dpi(40f)))
        stylePanel.addView(Kit.section(context,"Weight"));stylePanel.addView(weightInput)
        addView(stylePanel)
        addView(Kit.section(context,"Preview"));addView(stylePreview,LayoutParams(-1,dpi(120f)));addView(previewIssue)
        opacityInput.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean) { showOpacity();updatePreview() }
            override fun onStartTrackingTouch(bar:SeekBar) {}
            override fun onStopTrackingTouch(bar:SeekBar) {}
        })
        listOf(fontInput,weightInput,alignmentInput).forEach {input->input.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){updatePreview()}
        } }
        listOf(italicInput,underlineInput).forEach {it.setOnCheckedChangeListener {_,_->updatePreview()}}
        val watcher=object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){updatePreview()}
            override fun afterTextChanged(s:Editable?){}
        }
        listOf(textInput,sizeInput,colorInput.hexInput).forEach {it.addTextChangedListener(watcher)}
        updatePreview()
    }
    /** The format bar shows the draft: bold, italic and underline lit, the colour key in the text's colour. */
    private fun showKeys() {
        Kit.mark(bold,weights[weightInput.selectedItemPosition]>=600);Kit.mark(italicInput,italicInput.isChecked);Kit.mark(underlineInput,underlineInput.isChecked)
        Kit.mark(colorKey,stylePanel.visibility==View.VISIBLE)
        runCatching { dotFill.setColor((colorInput.color() or 0xff000000L).toInt()) }
        colorKey.background=android.graphics.drawable.LayerDrawable(arrayOf(colorKey.background,android.graphics.drawable.InsetDrawable(dotFill,dpi(11f))))
    }    private fun updatePreview(){showKeys();runCatching {styleValue()}.onSuccess {stylePreview.show(textInput.text.toString(),it);previewIssue.text=""}.onFailure {previewIssue.text="Correct the style values to refresh the preview."}}
    fun stickyValue():StickyNote?=initialSticky?.copy(color=noteColorInput!!.color())
    fun styleValue(): TextStyle = TextStyle(font=TextFont.entries[fontInput.selectedItemPosition],
        size=sizeInput.text.toString().trim().toFloatOrNull() ?: error("Enter a font size from 4 to 512"),
        weight=weights[weightInput.selectedItemPosition],italic=italicInput.isChecked,underline=underlineInput.isChecked,
        color=(colorInput.color() and 0xffffff) or (opacityInput.progress.toLong() shl 24),
        alignment=TextAlignment.entries[alignmentInput.selectedItemPosition])
    fun snapshot() = TextFormDraft(textInput.text.toString(),fontInput.selectedItemPosition,sizeInput.text.toString(),
        weightInput.selectedItemPosition,italicInput.isChecked,underlineInput.isChecked,alignmentInput.selectedItemPosition,
        colorInput.hexInput.text.toString(),opacityInput.progress,textInput.selectionStart,textInput.selectionEnd,noteColorInput?.hexInput?.text?.toString())
    fun restore(draft: TextFormDraft) {
        if(draft.noteColor!=null)noteColorInput?.hexInput?.setText(draft.noteColor)
        textInput.setText(draft.text);fontInput.setSelection(draft.font.coerceIn(0,2));sizeInput.setText(draft.size)
        weightInput.setSelection(draft.weight.coerceIn(0,weights.lastIndex));italicInput.isChecked=draft.italic
        underlineInput.isChecked=draft.underline;alignmentInput.setSelection(draft.alignment.coerceIn(0,2))
        colorInput.hexInput.setText(draft.color);opacityInput.progress=draft.opacity.coerceIn(0,255)
        textInput.setSelection(draft.selectionStart.coerceIn(0,textInput.length()),draft.selectionEnd.coerceIn(0,textInput.length()))
        updatePreview()
    }
    private fun shown(size: Float)=if (size==size.toInt().toFloat()) size.toInt().toString() else size.toString()
}

/** Selection/menu integration supplies the post-save selection callback; stale snapshots remain unmodified. */
fun MainActivity.textBoxDialog(textId: String? = null, sticky:Boolean=false, onSaved: (String)->Unit = {}): AlertDialog? {
    textEditor?.dialog?.takeIf { it.isShowing }?.let { return it }
    if (ink.isEditing || photoCropEditor?.dialog?.isShowing == true) return null
    val repository=(application as NotesApplication).activeDocument
    val target=repository.captureTextEditTarget(textId) ?: return null
    return showTextBoxDialog(TextEditorState(target,sticky=sticky || target.box?.sticky!=null),onSaved)
}

private fun MainActivity.showTextBoxDialog(state: TextEditorState, onSaved: (String)->Unit): AlertDialog {
    val app=application as NotesApplication
    val repository=app.activeDocument
    val target=state.target
    val form=TextEditorForm(this,target.box?.text.orEmpty(),target.box?.style ?: TextStyle(),if(state.sticky)target.box?.sticky ?: StickyNote() else null)
    state.draft?.let { form.restore(it) }
    val dialog=AlertDialog.Builder(this).setTitle(if(state.sticky) {if(target.textId==null)"New sticky note" else "Edit sticky note"} else if(target.textId==null) "New text box" else "Edit text box")
        .setView(ScrollView(this).apply { addView(form) }).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
    val host=TextEditorHost(state,dialog,form)
    textEditor=host;app.textEditorState=state
    dialog.setOnDismissListener {
        state.draft=form.snapshot()
        if (!isChangingConfigurations && app.textEditorState === state) app.textEditorState=null
        if (textEditor === host) textEditor=null
    }
    dialog.show();Glass.sheet(dialog);dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        runCatching { repository.applyTextEdit(target,form.textInput.text.toString(),form.styleValue(),form.stickyValue()) }
            .onSuccess { id ->
                if(id==null) form.textInput.error="The page changed or is busy. Reopen the text box."
                else { dialog.dismiss();onSaved(id) }
            }.onFailure { form.textInput.error=it.message ?: "Cannot save text box" }
    }
    return dialog
}

internal fun MainActivity.saveTextEditorDraft() { textEditor?.let { it.state.draft=it.form.snapshot() } }
internal fun MainActivity.closeTextEditorForDestroy() { saveTextEditorDraft();textEditor?.dialog?.dismiss() }
internal fun MainActivity.restoreTextEditor() {
    (application as NotesApplication).textEditorState?.let { state -> showTextBoxDialog(state) { ink.selectText(it) } }
}
