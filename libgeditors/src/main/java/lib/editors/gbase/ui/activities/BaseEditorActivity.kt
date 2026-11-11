package lib.editors.gbase.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import android.view.View
import android.view.WindowManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.content.ClipData
import android.content.ClipboardManager
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.convert.DocumentConverter
import lib.editors.base.engine.Alignment
import lib.editors.base.engine.CoEditingConnection
import lib.editors.base.engine.DrawingFileResult
import lib.editors.base.engine.Comment
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorSpelling
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.FillTarget
import lib.editors.base.engine.ListKind
import lib.editors.base.engine.Preview
import lib.editors.base.engine.Misspelling
import lib.editors.base.engine.SheetState
import lib.editors.base.engine.SpellService
import lib.editors.base.engine.DocumentResult
import lib.editors.base.engine.FormField
import lib.editors.base.engine.PageSize
import lib.editors.base.engine.PdfImage
import lib.editors.base.engine.PdfMarkup
import lib.editors.base.engine.PdfMarkupKind
import lib.editors.base.engine.PagedDocument
import lib.editors.base.engine.TextBlock
import lib.editors.base.engine.TextOptions
import lib.editors.base.engine.TouchAction
import lib.editors.base.engine.WRITABLE_FORMATS
import lib.editors.gbase.R
import lib.editors.gbase.ui.clipboard.EditorClipboard
import lib.editors.gbase.ui.views.BadgeButton
import lib.editors.gbase.ui.views.DocumentView
import lib.editors.gbase.ui.views.SheetView
import lib.toolkit.base.managers.utils.CrashLog
import lib.toolkit.base.managers.utils.EditType
import lib.toolkit.base.managers.utils.EditorDictionaries
import lib.toolkit.base.managers.utils.EditorsContract
import lib.toolkit.base.managers.utils.EditorsType
import lib.editors.resources.R as Resources
import java.io.File

/**
 * Base for the editor activities the app launches by class name (see [EditorsContract]).
 *
 * Documents and presentations on the device are opened by the editor engine, for editing unless
 * the app asks for viewing only. Slides are shown below each other, like pages. PDF, XPS and DjVu
 * files are shown read-only. Spreadsheets show one sheet at a time, with cells edited in a
 * formula bar (see [SpreadsheetUi]).
 *
 * Edits are saved into the document the app passed (the save button, leaving the editor and
 * going to the background save). The result then reports the document as modified, and the
 * app uploads it if it comes from a server.
 */
abstract class BaseEditorActivity : AppCompatActivity() {

    protected abstract val editorsType: EditorsType

    @Volatile
    private var document: EditorDocument? = null

    // PDF, XPS and DjVu files (PdfActivity), read-only
    @Volatile
    private var drawingDocument: PagedDocument? = null

    private lateinit var documentView: DocumentView
    // Spreadsheets: the sheet instead of the document view
    private var spreadsheetUi: SpreadsheetUi? = null
    private var searchBar: SearchBar? = null
    // The document's name without extension, the copies saved next to it for the app to
    // upload (EditorsContract.EXTRA_SAVED_FILES) and whether the document itself was saved
    private var baseName = "document"
    private val savedCopies = mutableListOf<Uri>()
    private var documentSaved = false
    // Plain text (txt, csv): the copy opened and how it was read, to read it differently
    private var textFile: File? = null
    private var textOptions: TextOptions? = null
    private var editing = false

    // PDF files: the current copy (changes are saved into new copies) and whether it changed
    private var pdfFile: File? = null
    private var pdfPassword = ""
    private var pdfEdited = false
    private var pdfText: PdfText? = null
    // The engine opened a PDF in sdkjs' PDF editor (DocsActivity and PdfActivity): PDF tools instead of the document editor's
    private var isPdfEditor = false
    private val hiddenTools = mutableListOf<View>()
    private var documentUi: DocumentUi? = null
    private var commentsUi: CommentsUi? = null
    private var formsUi: FormsUi? = null
    // A picture field waiting for a picture from the gallery
    private var pictureField: FormField? = null
    // A picture chosen for a fill (FillPanel), not to insert
    private var pictureFill: ((name: String, width: Int, height: Int) -> Unit)? = null
    private var slidesUi: SlidesUi? = null
    private lateinit var linkBar: View
    private val isSpreadsheet get() = editorsType == EditorsType.CELLS

    // The document the app passed, and the format edits are saved in (its extension)
    private var documentUri: Uri? = null
    private var extension = ""
    // The password the document is saved with ("" for none): the one it was opened with, or a new one
    private var documentPassword = ""
    private var modified = false
    private var saving = false
    // A slice of the layout of later pages is queued (see EditorDocument.tick)
    private var layingOut = false

    // Spell checking while editing documents and presentations: the checker (set on the
    // engine's thread), whether a slice of checking is queued, dictionaries asked for (each
    // once, unless its language is chosen), the ones downloading (true: shown) and how much of
    // them came
    @Volatile
    private var spellService: SpellService? = null
    private var spellChecking = false
    private val dictionaryDownloads = mutableSetOf<String>()
    private val downloadingDictionaries = mutableMapOf<String, Boolean>()
    private val dictionaryProgress = mutableMapOf<String, Float>()
    private val downloadIndicator by lazy { DownloadIndicator(documentView.parent as FrameLayout) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EditorsContract.KEY_KEEP_SCREEN_ON, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setResult(EditorsContract.RESULT_FAILED_OPEN)

        val uri = intent.data
        // Opened again with other text options (reopenWith): what was saved before still counts
        documentSaved = intent.getBooleanExtra(EXTRA_REOPEN_SAVED, false)
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(EXTRA_REOPEN_COPIES)?.let { savedCopies += it }
        if (uri != null && (documentSaved || savedCopies.isNotEmpty())) updateResult(uri)
        // PDFs in sdkjs' PDF editor (core's reader if the engine can't open one); XPS and DjVu in core's readers
        if (uri != null && (editorsType in ENGINE_EDITORS || editorsType == EditorsType.PDF) && EditorEngine.isAvailable) {
            showViewer(uri)
        } else {
            showPlaceholder(uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCoEditing()
        val documents = listOfNotNull(document, drawingDocument)
        document = null
        drawingDocument = null
        if (documents.isNotEmpty()) CoroutineScope(EditorEngine.dispatcher).launch { documents.forEach { it.close() } }
    }

    private fun setUpViewer() {
        onBackPressedDispatcher.addCallback(this) { onBack() }
        if (isSpreadsheet) {
            setContentView(R.layout.activity_editor_cells)
        } else {
            setContentView(R.layout.activity_editor_viewer)
            documentView = findViewById(R.id.documentView)
            documentView.addOnLayoutChangeListener(mobileViewResize)
        }

        findViewById<View>(R.id.toolClose).setOnClickListener { onBack() }
        linkBar = createLinkBar()
        Accessibility.pane(linkBar, getString(Resources.string.editors_link))
        // Read as switches: on while selected
        TEXT_TOOLS.forEach { Accessibility.toggle(findViewById(it)) }
        val root = findViewById<ViewGroup>(R.id.editorRoot)
        root.addView(linkBar, root.indexOfChild(findViewById(R.id.editorToolbar)))

        // Room for the system bars and the keyboard (the toolbar stays above it). The window
        // is laid out edge to edge, so the keyboard's size arrives as insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.editorRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun showViewer(uri: Uri) {
        setUpViewer()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { copyToCache(uri) }.getOrNull() }
            if (file == null) {
                Log.e(TAG, "Can't read $uri")
                finish()
                return@launch
            }
            findViewById<TextView>(R.id.editorTitle).text = file.name
            if (editorsType == EditorsType.PDF && !file.extension.equals("pdf", ignoreCase = true)) {
                openDrawingFile(uri, file, password = "")
                return@launch
            }
            val text = TextOptions(intent.getIntExtra(EXTRA_TEXT_ENCODING, -1), intent.getIntExtra(EXTRA_TEXT_DELIMITER, -1))
            openDocument(uri, file, password = "", text)
        }
    }

    // Encrypted files ask for their password and are opened again with it. Plain text is read
    // with text (guessed by the engine where it's -1).
    private fun openDocument(uri: Uri, file: File, password: String, text: TextOptions = TextOptions()) {
        val progress = findViewById<View>(R.id.documentProgress)
        val isEditable = isEditable()

        lifecycleScope.launch {
            var needsPassword = false
            var openedText: TextOptions? = null
            val opened = withContext(EditorEngine.dispatcher) {
                var start = SystemClock.elapsedRealtime()
                if (!EditorEngine.initialize(this@BaseEditorActivity)) return@withContext null
                Log.i(TAG, "engine ready: ${SystemClock.elapsedRealtime() - start} ms")

                start = SystemClock.elapsedRealtime()
                val document = when (val result = EditorEngine.openDocument(this@BaseEditorActivity, file, password, text)) {
                    is DocumentResult.Opened -> result.document
                    DocumentResult.NeedsPassword -> {
                        needsPassword = true
                        return@withContext null
                    }
                    DocumentResult.Failed -> return@withContext null
                }
                document.setTranslations(EditorTranslations.sdkjs(this@BaseEditorActivity))
                isPdfEditor = document.isPdf
                openedText = document.textOptions
                val pages = (0 until document.pageCount).map { document.pageSize(it) }
                Log.i(TAG, "open + layout: ${SystemClock.elapsedRealtime() - start} ms, ${pages.size} pages")
                Triple(document, pages, document.state())
            }
            if (needsPassword) {
                askPassword(wrong = password.isNotEmpty()) { openDocument(uri, file, it, text) }
                return@launch
            }
            if (opened == null) {
                // PDFs come here to be edited (EditorsContract), but the engine only opens PDF
                // forms: others are edited as PDFs (pages, markup, signatures)
                if (file.extension.equals("pdf", ignoreCase = true)) {
                    openDrawingFile(uri, file, password)
                    return@launch
                }
                finish()
                return@launch
            }

            document = opened.first
            documentUri = uri
            textFile = file
            textOptions = openedText
            updateResult()
            extension = file.extension.lowercase()
            baseName = file.nameWithoutExtension
            documentPassword = password
            progress.isVisible = false
            if (isSpreadsheet) {
                val sheetView = findViewById<SheetView>(R.id.sheetView)
                sheetView.setRenderer(::renderTile, lifecycleScope)
                val openedDocument = opened.first
                spreadsheetUi = SpreadsheetUi(this@BaseEditorActivity, sheetView, { command -> edit(command = command) }, object : SpreadsheetUi.Clipboard {
                    override fun cut() = this@BaseEditorActivity.copy(cut = true)
                    override fun copy() = this@BaseEditorActivity.copy(cut = false)
                    override fun paste() = editListener.onPaste()
                }, loadFunctions = { onLoaded ->
                    lifecycleScope.launch { onLoaded(withContext(EditorEngine.dispatcher) { openedDocument.functions() }) }
                })
                sheetView.requestFocus()
            } else {
                documentView.setPages(opened.second, ::renderTile, lifecycleScope)
                documentView.textBlocks = ::textBlocks
                // A hardware keyboard works right away
                documentView.requestFocus()
            }
            // Viewing: text can still be selected, copied and found
            if (!isSpreadsheet) {
                documentView.editListener = editListener
                documentView.readOnly = true
            }
            showToolbar(editable = false)
            findViewById<View>(R.id.toolFile).isVisible = true
            findViewById<View>(R.id.toolFile).setOnClickListener { fileUi.showMenu(it) }
            // Comments: written when editing or with comment access (KEY_EDIT_ACCESS), read otherwise
            val comments = CommentsUi(
                this@BaseEditorActivity, lifecycleScope, { document }, { command -> edit(command = command) },
                canComment = isEditable || editAccess() == EditorsContract.INTERNAL_EDIT_ACCESS_COMMENT,
                fileId = intent.getStringExtra(EditorsContract.EXTRA_ITEM_ID),
                panels = panels,
                reveal = { comment ->
                    if (isSpreadsheet) findViewById<SheetView>(R.id.sheetView).revealNextCell() else documentView.reveal(comment.page, comment.y)
                },
                onComments = ::showComments
            )
            commentsUi = comments
            comments.onState()
            // Tapping a comment's mark shows it
            if (isSpreadsheet) findViewById<SheetView>(R.id.sheetView).onCellComment = comments::showThread
            else documentView.onCommentMarker = comments::showThread
            findViewById<View>(R.id.toolComments).isVisible = true
            findViewById<View>(R.id.toolComments).setOnClickListener { comments.show() }
            // Commenting the selected text or cells from their menu
            commentAction()?.let { action ->
                if (isSpreadsheet) findViewById<SheetView>(R.id.sheetView).cellActions = listOf(action)
                else documentView.selectionActions = listOf(action)
            }
            if (editorsType == EditorsType.PRESENTATION) {
                val firstSlide = opened.second.firstOrNull()
                val ui = SlidesUi(
                    this@BaseEditorActivity, lifecycleScope, { document }, { command -> edit(command = command) },
                    render = { page, width, height -> renderTile(page, width, height, 0, 0, width, height) },
                    slideAspect = { firstSlide?.let { (it.widthMm / it.heightMm).toFloat() } ?: (16f / 9f) },
                    editable = false,
                    vertical = sideStrips,
                    previews = previewPicker,
                    panels = panels
                )
                addStrip(ui.strip)
                slidesUi = ui
                documentView.onDrawingsDroppedOnPage = { slide, offsetMm -> edit { moveToSlide(slide, offsetMm) } }
                findViewById<View>(R.id.toolSlideshow).isVisible = true
                findViewById<View>(R.id.toolSlideshow).setOnClickListener { ui.startSlideshow() }
            }
            addSearchBar(object : SearchBar.Target {
                override val canReplace get() = editing
                override fun find(text: String, matchCase: Boolean, wholeWords: Boolean, forward: Boolean) =
                    edit { find(text, matchCase, wholeWords, forward) }
                override fun replace(text: String, replacement: String, matchCase: Boolean, wholeWords: Boolean, all: Boolean) =
                    edit { replace(text, replacement, matchCase, wholeWords, all) }
                override fun close() = edit { endFind() }
            })
            applyState(opened.third)
            if (isPdfEditor) showPdfTools(opened.first, opened.second)
            // PDF forms (in the document editor): their pages as other PDFs'
            else if (extension == "pdf") showPageTools(opened.first, opened.second)
            if (editorsType == EditorsType.DOCS && isEditable && !isPdfEditor) {
                val forms = FormsUi(this@BaseEditorActivity, { command -> edit(command = command) },
                    pickPicture = { field ->
                        pictureField = field
                        pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onStartFilling = if (startsFilling()) ({ startFilling() }) else null)
                val root = findViewById<ViewGroup>(R.id.editorRoot)
                root.addView(forms.bar, root.indexOfChild(findViewById(R.id.editorToolbar)))
                formsUi = forms
            }
            // Co-editing through the integration's Document Server (Nextcloud): the copy just
            // opened is replaced by the session's version, and editing starts right away (the
            // others are editing already). Viewing, one joins as a viewer and sees the others'
            // changes as they come.
            // PDFs only in the PDF editor: the session is the web PDF editor's (forms open in the document editor here)
            val editorConfig = intent.getStringExtra(EditorsContract.KEY_DOC_SERVER)?.takeIf {
                editorsType in CO_EDITORS && (isPdfEditor || runCatching { org.json.JSONObject(it).optString("documentType") }.getOrNull() != "pdf")
            }
            // The app may ask to show the document first (EditType.Edit.initialView): editing then starts with a button
            // PDFs: their tools right away (annotations don't change the PDF's content)
            val wasEditing = intent.getBooleanExtra(EXTRA_REOPEN_EDITING, false)
            if (isEditable && (editorConfig != null || !startsInView() || fillsForms() || startsFilling() || isPdfEditor || wasEditing)) {
                enableEditing()
            } else if (isEditable) {
                findViewById<View>(R.id.toolEdit).isVisible = true
                findViewById<View>(R.id.toolEdit).setOnClickListener { enableEditing() }
            }
            if (editorConfig != null) startCoEditing(if (isEditable) editorConfig else viewerConfig(editorConfig))
        }
    }

    // ---------------------------------------------------------------------------------
    // Co-editing: sdkjs' co-authoring client in the engine talks through the connection
    // (socket.io); the server's events go to the engine in their order, sdkjs' timers run every
    // CO_TICK_MS and what sdkjs sends is carried after each command. The Document Server saves
    // the document into the integration: the editor saves and uploads nothing.

    private var coConnection: CoEditingConnection? = null
    private var coEditingUi: CoEditingUi? = null

    private fun startCoEditing(json: String) {
        val config = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        val key = config.optJSONObject("document")?.optString("key").orEmpty()
        val server = config.optString("documentServerUrl")
        val document = document
        if (key.isEmpty() || server.isEmpty() || document == null) return
        val events = Channel<CoEditingConnection.Event>(Channel.UNLIMITED)
        val root = findViewById<ViewGroup>(R.id.editorRoot)
        val bar = layoutInflater.inflate(R.layout.editor_coediting_bar, root, false) as TextView
        root.addView(bar, root.indexOfChild(findViewById(R.id.editorToolbar)))
        coEditingUi = CoEditingUi(this, bar, findViewById(R.id.toolCoEditors)) { stopEditing() }
        lifecycleScope.launch {
            val imagesDir = File(withContext(EditorEngine.dispatcher) { document.imagesDirectory() })
            coConnection = CoEditingConnection(server, key, File(cacheDir, "coediting"), imagesDir) { events.trySend(it) }
            launch {
                for (event in events) coEdit {
                    val image = event.image
                    if (image != null) {
                        coImageLoaded(image)
                    } else {
                        event.file?.let { (url, path) -> coSetFile(url, path) }
                        coReceive(event.name, event.args)
                    }
                }
            }
            coEdit { coOpen(json) }
            while (coConnection != null) {
                delay(CO_TICK_MS)
                coEdit(isTick = true) { coTick(CO_TICK_BUDGET_MS) }
            }
        }
    }

    // A command, then what sdkjs sends goes out; after the session's document was loaded the
    // pages and settings are read again
    private fun coEdit(isTick: Boolean = false, command: EditorDocument.() -> EditorState) = edit(isTick = isTick) {
        val state = command()
        coConnection?.handle(coTakeOutbox())
        if (coTakeReloaded()) {
            setTranslations(EditorTranslations.sdkjs(this@BaseEditorActivity))
            val pages = (0 until pageCount).map { pageSize(it) }
            val languages = spellService?.let { EditorSpelling.dictionaries(this@BaseEditorActivity).installedLanguages() }
            // Spreadsheets: the state has the sheet's new geometry
            if (!isSpreadsheet) runOnUiThread {
                documentView.updatePages(pages)
                documentView.reloadPages()
            }
            languages?.let { setSpellLanguages(it) } ?: state()
        } else {
            state
        }
    }

    private var leaving = false

    // Sends the changes not sent yet and waits for the server's answer, at most CO_FLUSH_MS; the
    // session's timer carries them (coEdit)
    private suspend fun flushCoEditing() {
        val document = document ?: return
        val end = SystemClock.elapsedRealtime() + CO_FLUSH_MS
        while (coConnection != null && SystemClock.elapsedRealtime() < end) {
            val pending = withContext(EditorEngine.dispatcher) { runCatching { document.coFlush() }.getOrDefault(false) }
            if (!pending) return
            delay(CO_TICK_MS)
        }
    }

    private fun stopCoEditing() {
        coConnection?.close()
        coConnection = null
    }

    /** Back to viewing: co-editing ended (sdkjs only lets one view the document then). */
    private fun stopEditing() {
        if (!editing) return
        editing = false
        showToolbar(editable = false)
        if (isSpreadsheet) spreadsheetUi?.disableEditing() else documentView.readOnly = true
        slidesUi?.editable = false
        documentUi?.bar?.isVisible = false
    }

    /** From viewing to editing the document. */
    private fun enableEditing() {
        if (editing || document == null) return
        if (isPdfEditor) {
            enablePdfEditing()
            return
        }
        editing = true
        findViewById<View>(R.id.toolEdit).isVisible = false
        showToolbar(editable = true)
        if (isSpreadsheet) spreadsheetUi?.enableEditing() else documentView.readOnly = false
        slidesUi?.editable = true
        if (editorsType == EditorsType.DOCS && !fillsForms()) {
            val ui = DocumentUi(
                this, lifecycleScope, documentView, { document }, panels, pageSettingsUi, { command -> edit(command = command) }
            )
            val root = findViewById<ViewGroup>(R.id.editorRoot)
            root.addView(ui.bar, root.indexOfChild(findViewById(R.id.editorToolbar)))
            documentUi = ui
            // Reviewers' changes are always tracked
            if (editAccess() == EditorsContract.INTERNAL_EDIT_ACCESS_TRACKED_CHANGES) {
                ui.trackChangesForced = true
                edit { setTrackChanges(true) }
            }
        }
        bindToolbar()
        if (fillsForms()) {
            edit { setFillMode(true) }
            // Only the form's fields take input: inserting and formatting don't apply
            FILLING_HIDDEN_TOOLS.forEach { findViewById<View>(it).isVisible = false }
        }
        if (editorsType == EditorsType.DOCS || editorsType == EditorsType.PRESENTATION) startSpelling()
    }

    // ---------------------------------------------------------------------------------
    // Spell checking: sdkjs asks for words to check (state.spelling.pending), Hunspell answers
    // in slices; dictionaries of languages it needs, and of the device's language, are downloaded

    private val spellCheckOn get() = getSharedPreferences(SPELLING_PREFERENCES, MODE_PRIVATE).getBoolean(KEY_SPELL_CHECK, true)

    private fun startSpelling() {
        if (!spellCheckOn) {
            edit { setSpellCheck(false) }
            return
        }
        val dictionaries = EditorSpelling.dictionaries(this)
        // Not one the user removed
        dictionaries.forLocale()?.takeIf { it !in EditorDictionaries.removed(this) }?.let { downloadDictionary(it) }
        edit {
            spellService = EditorSpelling.service(this@BaseEditorActivity)
            setSpellLanguages(dictionaries.installedLanguages())
        }
    }

    private fun setSpellCheck(on: Boolean) {
        getSharedPreferences(SPELLING_PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_SPELL_CHECK, on).apply()
        edit { setSpellCheck(on) }
        if (on) startSpelling() else spellService = null
    }

    // A dictionary the device doesn't have, in the background; [announce]: its progress shows
    // and a message if it fails (the text's languages, not the device's); [again]: also after it
    // failed (a language the user chose)
    private fun downloadDictionary(name: String, announce: Boolean = false, again: Boolean = false) {
        val dictionaries = EditorSpelling.dictionaries(this)
        if (dictionaries.files(name) != null) return
        val language = EditorDictionaries.languageName(name)
        fun show() = downloadIndicator.start(name, language, getString(Resources.string.editors_spell_downloading, language), dictionaryProgress[name])
        val announced = downloadingDictionaries[name]
        if (announced != null) {
            // On its way already: shown now if it wasn't
            if (announce && !announced) {
                downloadingDictionaries[name] = true
                show()
            }
            return
        }
        if (!dictionaryDownloads.add(name) && !again) return
        downloadingDictionaries[name] = announce
        if (announce) show()
        lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                dictionaries.download(name) { fraction ->
                    lifecycleScope.launch {
                        dictionaryProgress[name] = fraction
                        downloadIndicator.progress(name, fraction)
                    }
                }
            }
            dictionaryProgress.remove(name)
            downloadIndicator.end(name)
            if (downloadingDictionaries.remove(name) == true && !downloaded) {
                toast(getString(Resources.string.editors_spell_download_failed, language), long = true)
            }
            if (downloaded) EditorDictionaries.setRemoved(this@BaseEditorActivity, name, false)
            if (!downloaded || spellService == null) return@launch
            edit {
                EditorSpelling.service(this@BaseEditorActivity).reload()
                setSpellLanguages(dictionaries.installedLanguages())
            }
        }
    }

    private fun checkSpelling(state: EditorState) {
        val spelling = state.spelling ?: return
        val service = spellService ?: return
        if (spelling.pending && !spellChecking) {
            spellChecking = true
            edit(isSpellSlice = true) { checkSpelling(service, SPELL_SLICE_MS) }
        }
        val dictionaries = EditorSpelling.dictionaries(this)
        spelling.missing.forEach { lcid -> dictionaries.nameFor(lcid)?.let { downloadDictionary(it, announce = true) } }
    }

    // The misspelled word at the cursor: its suggestions, ignoring it and adding it to the user's words
    private fun spellingActions(word: Misspelling?): List<Pair<String, () -> Unit>> {
        if (word == null || spellService == null || !editing) return emptyList()
        val variants = word.variants.orEmpty().take(MAX_SUGGESTIONS).map { variant -> variant to { edit { replaceMisspelled(variant) } } }
        return variants + listOf(
            getString(Resources.string.editors_spell_ignore) to { edit { ignoreMisspelled(all = true) } },
            getString(Resources.string.editors_spell_add) to {
                edit {
                    EditorSpelling.service(this@BaseEditorActivity).addWord(word.word)
                    ignoreMisspelled(all = true)
                }
            }
        )
    }

    // Sends the form to its roles: the app's StartFillingActivity assigns people to them
    private fun startFilling() {
        val document = document ?: return
        lifecycleScope.launch {
            val roles = withContext(EditorEngine.dispatcher) { runCatching { document.formRoles() }.getOrDefault(emptyList()) }
            val json = org.json.JSONObject().put("formRoles", org.json.JSONArray(roles.map { role ->
                org.json.JSONObject().put("name", role.name)
                    .put("color", (role.color.toIntOrNull(16) ?: 0) or 0xFF000000.toInt())
                    .put("fieldCount", role.fieldCount)
            })).toString()
            val request = Intent().setClassName(this@BaseEditorActivity, EditorsContract.START_FILLING_CLASSNAME)
                .putExtra(EditorsContract.EXTRA_FORM_ROLES, json)
                .putExtra(EditorsContract.EXTRA_ROOM_ID, intent.getStringExtra(EditorsContract.EXTRA_ROOM_ID))
                .putExtra(EditorsContract.EXTRA_ITEM_ID, intent.getStringExtra(EditorsContract.EXTRA_ITEM_ID))
            runCatching { startFillingResult.launch(request) }.onFailure { toast(getString(Resources.string.editors_forms_start_failed)) }
        }
    }

    private val startFillingResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(EditorsContract.RESULT_START_FILLING_COMPLETE, Intent().setData(documentUri))
            finish()
        }
    }

    private fun editAccess() = intent.getIntExtra(EditorsContract.KEY_EDIT_ACCESS, EditorsContract.INTERNAL_EDIT_ACCESS_EDIT)

    private fun startsInView(): Boolean {
        @Suppress("DEPRECATION")
        return (intent.getSerializableExtra(EditorsContract.KEY_EDIT_TYPE) as? EditType.Edit)?.initialView == true
    }

    // ---------------------------------------------------------------------------------
    // Hardware keyboard: the editor's shortcuts. The document's own (Ctrl+B, Ctrl+Z, ...) are
    // handled by the document and sheet views.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && shortcut(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun shortcut(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && searchBar?.isShown == true) {
            searchBar?.hide()
            return true
        }
        if (!event.isCtrlPressed || event.isAltPressed) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_F -> searchBar?.show() ?: return false
            KeyEvent.KEYCODE_S -> if (canSave()) saveNow() else return false
            KeyEvent.KEYCODE_P -> if (document != null) fileUi.print() else return false
            else -> return false
        }
        return true
    }

    // The list of shortcuts the system shows (Meta + /)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        fun shortcut(label: String, keyCode: Int, shift: Boolean = false) =
            KeyboardShortcutInfo(label, keyCode, KeyEvent.META_CTRL_ON or if (shift) KeyEvent.META_SHIFT_ON else 0)
        val shortcuts = mutableListOf(
            shortcut(getString(Resources.string.editors_search), KeyEvent.KEYCODE_F),
            shortcut(getString(android.R.string.copy), KeyEvent.KEYCODE_C),
            shortcut(getString(android.R.string.selectAll), KeyEvent.KEYCODE_A)
        )
        if (document != null) shortcuts += shortcut(getString(Resources.string.editors_file_print), KeyEvent.KEYCODE_P)
        if (editing) {
            shortcuts += listOf(
                shortcut(getString(Resources.string.editors_save), KeyEvent.KEYCODE_S),
                shortcut(getString(android.R.string.cut), KeyEvent.KEYCODE_X),
                shortcut(getString(android.R.string.paste), KeyEvent.KEYCODE_V),
                shortcut(getString(Resources.string.editors_undo), KeyEvent.KEYCODE_Z),
                shortcut(getString(Resources.string.editors_redo), KeyEvent.KEYCODE_Y),
                shortcut(getString(Resources.string.editors_bold), KeyEvent.KEYCODE_B),
                shortcut(getString(Resources.string.editors_italic), KeyEvent.KEYCODE_I),
                shortcut(getString(Resources.string.editors_underline), KeyEvent.KEYCODE_U)
            )
        }
        data += KeyboardShortcutGroup(getString(getTitleRes()), shortcuts)
    }

    // Back closes the search first; changes (edits, or comments when only commenting) are saved before leaving
    private fun onBack() {
        if (searchBar?.isShown == true) searchBar?.hide() else leave()
    }

    // ---------------------------------------------------------------------------------
    // XPS and DjVu (and PDFs the engine can't open): drawn by core's readers, without sdkjs

    private fun openDrawingFile(uri: Uri, file: File, password: String) {
        lifecycleScope.launch {
            val opened = withContext(EditorEngine.dispatcher) {
                val start = SystemClock.elapsedRealtime()
                val result = EditorEngine.openDrawingFile(this@BaseEditorActivity, file, password)
                val pages = (result as? DrawingFileResult.Opened)?.document?.let { document ->
                    (0 until document.pageCount).map { document.pageSize(it) }
                }
                Log.i(TAG, "open ${file.extension}: ${SystemClock.elapsedRealtime() - start} ms, ${pages?.size} pages")
                result to pages
            }
            when (val result = opened.first) {
                is DrawingFileResult.Opened -> {
                    if (isDestroyed) {
                        withContext(EditorEngine.dispatcher) { result.document.close() }
                        return@launch
                    }
                    drawingDocument = result.document
                    documentUri = uri
                    pdfFile = file
                    pdfPassword = password
                    setResult(RESULT_OK, Intent().setData(uri).putExtra(EditorsContract.EXTRA_IS_MODIFIED, false))
                    findViewById<View>(R.id.documentProgress).isVisible = false
                    val pages = opened.second.orEmpty()
                    documentView.setPages(pages, ::renderTile, lifecycleScope)
                    documentView.textBlocks = ::textBlocks
                    val pdfText = PdfText(lifecycleScope, result.document, pages.size,
                        onState = { state ->
                            documentView.setEditorState(state)
                            searchBar?.update(state.search)
                        },
                        // Places in the file are shown at once; web addresses in the link bar
                        onLink = { link ->
                            if (link != null && link.destPage >= 0) {
                                showLink(null, null)
                                documentView.goToPage(link.destPage, link.destY)
                            } else {
                                showLink(link?.url, link?.url?.let { url -> { openUrl(url) } })
                            }
                        },
                        onCopy = { text ->
                            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
                        })
                    documentView.editListener = pdfText
                    documentView.readOnly = true
                    documentView.requestFocus()
                    addSearchBar(pdfText)
                    showToolbar(editable = false)
                    showPageTools(result.document, pages)
                    this@BaseEditorActivity.pdfText = pdfText
                    if (file.extension.equals("pdf", ignoreCase = true) && isEditable()) enablePdfChanges(pdfText)
                }
                DrawingFileResult.NeedsPassword -> askPassword(wrong = password.isNotEmpty()) { openDrawingFile(uri, file, it) }
                DrawingFileResult.Failed -> finish()
            }
        }
    }

    // A new password, typed twice: the document is saved with it from now on
    private fun askNewPassword() {
        fun field(hint: Int) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setHint(hint)
        }
        val password = field(Resources.string.editors_password_new)
        val repeated = field(Resources.string.editors_password_repeat)
        val mismatch = TextView(this).apply {
            setText(Resources.string.editors_password_mismatch)
            setTextColor(ContextCompat.getColor(context, Resources.color.editors_error))
            isVisible = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPaddingRelative(padding, 0, padding, 0)
            addView(password)
            addView(repeated)
            addView(mismatch)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (documentPassword.isEmpty()) Resources.string.editors_password_protect else Resources.string.editors_password_change)
            .setMessage(Resources.string.editors_password_new_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> changePassword(password.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // OK once both are the same
        fun check() {
            val typed = password.text.toString()
            val matches = typed == repeated.text.toString()
            mismatch.isVisible = !matches && repeated.text.isNotEmpty()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = typed.isNotEmpty() && matches
        }
        password.doAfterTextChanged { check() }
        repeated.doAfterTextChanged { check() }
        dialog.setOnShowListener { check() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        password.requestFocus()
    }

    // The password to save with ("" for none); the document is modified until it is saved
    private fun changePassword(password: String) {
        documentPassword = password
        edit {
            setPassword(password)
            state()
        }
        if (password.isEmpty()) toast(getString(Resources.string.editors_password_removed))
    }

    private fun askPassword(wrong: Boolean, onPassword: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val container = FrameLayout(this).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPaddingRelative(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(Resources.string.editors_password_title)
            .setMessage(if (wrong) Resources.string.editors_password_wrong else Resources.string.editors_password_message)
            .setView(container)
            .setPositiveButton(Resources.string.editors_open) { _, _ -> onPassword(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun isEditable(): Boolean {
        @Suppress("DEPRECATION")
        val editType = intent.getSerializableExtra(EditorsContract.KEY_EDIT_TYPE) as? EditType
        // Reading and commenting only (KEY_EDIT_ACCESS) keep the document as it is
        val access = editAccess()
        val accessEdits = access == EditorsContract.INTERNAL_EDIT_ACCESS_EDIT ||
            access == EditorsContract.INTERNAL_EDIT_ACCESS_TRACKED_CHANGES ||
            access == EditorsContract.INTERNAL_EDIT_ACCESS_CUSTOM_FILTER ||
            access == EditorsContract.INTERNAL_EDIT_ACCESS_FILLING_FORMS
        return (editType == null || editType is EditType.Edit || editType is EditType.Fill || editType is EditType.StartFilling) && accessEdits &&
            coEditingAllowsEditing()
    }

    // The integration's editor configuration (KEY_DOC_SERVER) may only allow viewing: a file shared read-only
    private fun coEditingAllowsEditing(): Boolean {
        val config = intent.getStringExtra(EditorsContract.KEY_DOC_SERVER)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return true
        return config.optJSONObject("editorConfig")?.optString("mode") != "view" &&
            config.optJSONObject("document")?.optJSONObject("permissions")?.optBoolean("edit", true) != false
    }

    // Joining a session to view (sdkjs edits nothing then)
    private fun viewerConfig(json: String): String = runCatching {
        org.json.JSONObject(json).apply { optJSONObject("editorConfig")?.put("mode", "view") }.toString()
    }.getOrDefault(json)

    // Only form fields take input: filling (EditType.Fill, form filling access) and finished forms (oform, PDF forms)
    private fun fillsForms(): Boolean {
        @Suppress("DEPRECATION")
        val editType = intent.getSerializableExtra(EditorsContract.KEY_EDIT_TYPE) as? EditType
        return editType is EditType.Fill || editAccess() == EditorsContract.INTERNAL_EDIT_ACCESS_FILLING_FORMS ||
            extension == "oform" || extension == "pdf"
    }

    private fun startsFilling(): Boolean {
        @Suppress("DEPRECATION")
        return intent.getSerializableExtra(EditorsContract.KEY_EDIT_TYPE) is EditType.StartFilling
    }

    // ---------------------------------------------------------------------------------
    // Editing: input goes to the engine in order, its state comes back to the view

    private val editListener = object : DocumentView.EditListener {
        override fun onTouch(action: TouchAction, page: Int, xMm: Double, yMm: Double, clickCount: Int, addToSelection: Boolean, extend: Boolean) =
            edit { touch(action, page, xMm, yMm, clickCount, addToSelection, extend) }

        override fun onKey(keyCode: Int, shift: Boolean, ctrl: Boolean) = edit { keyDown(keyCode, shift, ctrl) }

        override fun onCommitText(text: String) = edit { commitText(text) }

        override fun onComposingText(text: String) = edit { setComposingText(text) }

        override fun onFinishComposingText() = edit { finishComposingText() }

        override fun onSelect(startPage: Int, startXMm: Double, startYMm: Double, endPage: Int, endXMm: Double, endYMm: Double) =
            edit { select(startPage, startXMm, startYMm, endPage, endXMm, endYMm) }

        override fun onCut() = copy(cut = true)

        override fun onCopy() = copy(cut = false)

        override fun onPaste() {
            val content = EditorClipboard.get(this@BaseEditorActivity) ?: return
            edit { paste(content.text, content.internal) }
        }

        override fun onSelectAll() = edit { selectAll() }
    }

    private fun copy(cut: Boolean) = edit {
        val content = copy()
        if (content.text.isNotEmpty() || content.internal.isNotEmpty()) {
            EditorClipboard.put(this@BaseEditorActivity, content)
        }
        if (cut) cut() else state()
    }

    // The state applied last, for co-editing's timer (see isIdle)
    private var appliedState: EditorState? = null

    private fun edit(
        isLayoutSlice: Boolean = false,
        isSpellSlice: Boolean = false,
        isTick: Boolean = false,
        command: EditorDocument.() -> EditorState
    ) {
        lifecycleScope.launch {
            val result = withContext(EditorEngine.dispatcher) {
                val document = document ?: return@withContext null
                // A failed command changes nothing; editing goes on
                // Errors are kept for crash reports (CrashLog)
                val state = runCatching { document.command() }.onFailure { CrashLog.note(it) }.getOrNull()
                    ?: return@withContext null
                state.error?.let { CrashLog.note("sdkjs: ${it.take(MAX_ERROR_CHARS)}") }
                // Page count or sizes may change while editing
                val pages = if (state.layoutChanged) (0 until document.pageCount).map { document.pageSize(it) } else null
                state to pages
            }
            if (isLayoutSlice) layingOut = false
            if (isSpellSlice) spellChecking = false
            if (result == null) return@launch
            // Co-editing's timer runs often; a state that changes nothing isn't applied (that redraws)
            if (isTick && isIdle(result.first, appliedState)) return@launch
            if (!isSpreadsheet) result.second?.let { documentView.updatePages(it) }
            appliedState = result.first
            applyState(result.first)
        }
    }

    private fun isIdle(state: EditorState, previous: EditorState?): Boolean {
        previous ?: return false
        if (state.dirtyPages.isNotEmpty() || state.layoutChanged || state.layoutPending || state.error != null || state.openUrl != null ||
            state.navigate != null || state.pdf != previous.pdf
        ) {
            return false
        }
        if (state.cursor != previous.cursor || state.coEditing != previous.coEditing || state.toolbar != previous.toolbar ||
            state.modified != previous.modified || state.spelling != previous.spelling || state.format != previous.format ||
            state.link != previous.link || state.search != previous.search || state.currentPage != previous.currentPage
        ) {
            return false
        }
        fun same(a: List<DoubleArray>, b: List<DoubleArray>) = a.size == b.size && a.zip(b).all { (x, y) -> x.contentEquals(y) }
        if (!same(state.selection.map { it.points }, previous.selection.map { it.points }) ||
            !same(state.drawings.map { it.points }, previous.drawings.map { it.points })
        ) {
            return false
        }
        val sheet = state.sheet ?: return true
        val old = previous.sheet ?: return false
        return sheet.geometry == null && sheet.text == old.text && sheet.name == old.name && sheet.active == old.active &&
            listOf(sheet.c1, sheet.r1, sheet.c2, sheet.r2) == listOf(old.c1, old.r1, old.c2, old.r2) &&
            sheet.sheets.map { it.name to it.hidden } == old.sheets.map { it.name to it.hidden } &&
            same(sheet.objects, old.objects) && sheet.cellFormat == old.cellFormat && sheet.warning == null
    }

    private fun applyState(state: EditorState) {
        currentSheet = state.sheet
        if (!isSpreadsheet) documentView.spellingActions = spellingActions(state.spelling?.word)
        coEditingUi?.update(state.coEditing)
        if (isSpreadsheet) spreadsheetUi?.setState(state) else documentView.setEditorState(state)
        // The pages after the first ones are laid out in slices; input in between runs first
        if (state.layoutPending && !layingOut) {
            layingOut = true
            edit(isLayoutSlice = true) { tick(LAYOUT_SLICE_MS) }
        }
        checkSpelling(state)
        modified = state.modified
        // A picture, shape or chart is selected (not text in it): text formatting goes to its
        // text, paragraph and cell settings don't apply
        val drawingSelected = state.drawings.any { !it.tracking }
        val toolbar = state.toolbar
        findViewById<View>(R.id.toolBold).isSelected = toolbar.bold
        findViewById<View>(R.id.toolItalic).isSelected = toolbar.italic
        findViewById<View>(R.id.toolUnderline).isSelected = toolbar.underline
        findViewById<View>(R.id.toolAlignLeft).isSelected = !drawingSelected && toolbar.alignment == Alignment.LEFT
        findViewById<View>(R.id.toolAlignCenter).isSelected = !drawingSelected && toolbar.alignment == Alignment.CENTER
        findViewById<View>(R.id.toolAlignRight).isSelected = !drawingSelected && toolbar.alignment == Alignment.RIGHT
        listOf(R.id.toolAlignLeft, R.id.toolAlignCenter, R.id.toolAlignRight).forEach { findViewById<View>(it).isEnabled = !drawingSelected }
        if (isPdfEditor && editing) {
            val textBox = state.pdf?.textBox == true
            if (textBox != pdfTextBox) documentView.selectionActions = pdfSelectionActions(textBox)
            pdfTextBox = textBox
            pdfDrawing = state.pdf?.drawing == true
            pdfPageEditable = state.pdf?.canEditPage == true
            updatePdfTools()
        }
        findViewById<View>(R.id.toolUndo).isEnabled = toolbar.canUndo
        findViewById<View>(R.id.toolRedo).isEnabled = toolbar.canRedo
        // In a table (also one on a slide, which is a drawing): the table's and its text's settings
        val inTable = state.structure?.inTable == true
        formatsDrawings = if (isPdfEditor) state.pdf?.drawing == true else isEditable() && !fillsForms() && drawingSelected && !inTable
        // Pages laid out as the view shows them before going to a place
        updateMobileView(state)
        // A link or an entry of the contents was followed; the place kept in mobile view
        state.navigate?.let { documentView.goToPage(it.page, it.yMm.toFloat()) }
        // With what the toolbar shows too (bold, italic, underline, alignment)
        val textFormat = state.format.copy(bold = toolbar.bold, italic = toolbar.italic, underline = toolbar.underline, alignment = toolbar.alignment)
        formatPanel.update(textFormat, drawingSelected && !inTable, state.cursor != null || state.selection.isNotEmpty(), inTable)
        state.sheet?.let {
            cellFormatPanel.update(it.cellFormat.copy(bold = toolbar.bold, italic = toolbar.italic, underline = toolbar.underline, alignment = toolbar.alignment), drawingSelected)
        }
        searchBar?.update(state.search)
        slidesUi?.update(state)
        drawingUi.chart = state.chart
        commentsUi?.onState()
        // Tapped commented text, a PDF annotation with a comment: the comment
        state.shownComments.firstOrNull()?.let { commentsUi?.showThread(it) }
        formsUi?.update(state)
        documentUi?.update(state)
        val link = state.link?.takeIf { state.selection.isEmpty() }
        showLink(link?.let { it.url.ifEmpty { it.anchor } }, link?.let { { edit { followLink() } } })
        state.openUrl?.let { openUrl(it) }
    }

    // ---------------------------------------------------------------------------------
    // Mobile view (documents): the text laid out for the screen, as chosen last; phones start
    // with it

    private val viewPreferences by lazy { getSharedPreferences(VIEW_PREFERENCES, MODE_PRIVATE) }
    private var mobileViewChosen: Boolean
        get() = viewPreferences.getBoolean(MOBILE_VIEW, !resources.getBoolean(R.bool.editor_side_panels))
        set(value) = viewPreferences.edit().putBoolean(MOBILE_VIEW, value).apply()
    // The text's size in mobile view (a pinch changes it)
    private var mobileViewTextScale: Double
        get() = viewPreferences.getFloat(TEXT_SCALE, 1f).toDouble()
        set(value) = viewPreferences.edit().putFloat(TEXT_SCALE, value.toFloat()).apply()
    // Switching (the engine lays the document out again), and the width laid out for
    private var mobileViewPending = false
    private var mobileViewWidth = 0
    // The document is shown: in the chosen view (not the document's pages first), or after a while
    private var mobileViewSettled = false

    private fun canUseMobileView() = editorsType == EditorsType.DOCS && !isPdfEditor && !fillsForms() && document != null

    private fun updateMobileView(state: EditorState) {
        if (!canUseMobileView()) return
        documentView.continuous = state.mobileView
        documentView.textScale = mobileViewTextScale
        documentView.onTextScale = { scale ->
            mobileViewTextScale = scale
            applyMobileView()
        }
        documentUi?.mobileView = state.mobileView
        if (state.mobileView == mobileViewChosen) {
            mobileViewPending = false
            showDocument()
        } else if (!mobileViewPending) {
            // Also when co-editing opened the document again
            if (!mobileViewSettled) {
                documentView.isInvisible = true
                documentView.postDelayed(::showDocument, MOBILE_VIEW_SETTLE_MS)
            }
            applyMobileView()
        }
    }

    private fun showDocument() {
        mobileViewSettled = true
        documentView.isInvisible = false
    }

    // Pages of the view's width; their height the screen's, so that the keyboard doesn't change
    // them. The text at the top of the view stays there (the state's navigate).
    private fun applyMobileView() {
        val width = documentView.width
        if (width == 0) {
            documentView.post { applyMobileView() }
            return
        }
        mobileViewPending = true
        mobileViewWidth = width
        val on = mobileViewChosen
        val scale = mobileViewTextScale
        val mmPerPixel = 25.4 / resources.displayMetrics.densityDpi
        val widthMm = width * mmPerPixel
        val heightMm = maxOf(resources.displayMetrics.heightPixels, resources.displayMetrics.widthPixels) * mmPerPixel
        val place = documentView.topPlace().takeIf { mobileViewSettled }
        edit { setMobileView(on, widthMm, heightMm, scale, place?.first ?: -1, place?.second ?: 0.0, place?.third ?: 0.0) }
    }

    private fun toggleMobileView() {
        mobileViewChosen = !mobileViewChosen
        applyMobileView()
    }

    // A new width (turning the device, a window's size): laid out again for it
    private val mobileViewResize = View.OnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
        if (canUseMobileView() && mobileViewChosen && !mobileViewPending && mobileViewWidth != 0 && right - left != mobileViewWidth) {
            view.post { applyMobileView() }
        }
    }

    // ---------------------------------------------------------------------------------
    // Pictures and shapes

    // Formatting and comments: bottom sheets on phones, beside the document on large screens
    private val panels by lazy { PanelHost(this, findViewById(R.id.editorSidePane)) }
    private val iconPicker by lazy { IconPicker(this, panels) }
    private val colorPicker by lazy { ColorPicker(this, panels) { onLoaded -> read({ themeColors() }) { colors -> colors?.let(onLoaded) } } }

    private val formatPanel by lazy {
        FormatPanel(
            this, { command -> edit(command = command) },
            loadFonts = { onLoaded ->
                val document = document ?: return@FormatPanel
                lifecycleScope.launch {
                    val lists = withContext(EditorEngine.dispatcher) { document.fonts() to document.documentFonts() }
                    onLoaded(lists.first, lists.second)
                }
            },
            withParagraphs = editorsType != EditorsType.CELLS,
            withHighlights = editorsType != EditorsType.PDF,
            panels = panels,
            colorPicker = colorPicker,
            shapes = shapePanel,
            chooseStyle = if (editorsType == EditorsType.DOCS) ::chooseParagraphStyle else null,
            slide = if (editorsType != EditorsType.PRESENTATION) null else FormatPanel.SlideSettings(
                loadBackground = { onLoaded -> read({ slideBackground() }, onLoaded) },
                chooseLayout = { back -> slidesUi?.chooseLayout(forNewSlide = false, onBack = back) },
                chooseDesign = ::chooseDesign,
                chooseTransition = { back -> slidesUi?.chooseTransition(back) }
            ),
            chooseTableStyle = if (isSpreadsheet || editorsType == EditorsType.PDF) null else {
                { back ->
                    previewPicker.show(getString(Resources.string.editors_table_style), "tableStyles", keepOpen = true, onBack = back) { style ->
                        edit { setTableStyle(style.id) }
                    }
                }
            },
            chooseFill = if (editorsType == EditorsType.PDF) null else fillPanel::show,
            chooseList = if (editorsType == EditorsType.DOCS || editorsType == EditorsType.PRESENTATION) ::chooseListPreset else null,
            withNumberingRestart = editorsType == EditorsType.DOCS,
            withCaps = editorsType == EditorsType.DOCS || editorsType == EditorsType.PRESENTATION,
            withAdvanced = editorsType == EditorsType.DOCS,
            withShading = editorsType == EditorsType.DOCS,
            chooseLanguage = if (editorsType == EditorsType.DOCS || editorsType == EditorsType.PRESENTATION) ::chooseLanguage else null
        )
    }

    // Fills of shapes and slide backgrounds; a picture for one is picked as one to insert is
    private val fillPanel by lazy {
        FillPanel(this, lifecycleScope, { document }, { command -> edit(command = command) }, panels, colorPicker) { onPicked ->
            pictureFill = onPicked
            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    // A list preset of the kind by its picture, the current one marked; "None" ends the list.
    // Documents' numbering has the multilevel lists below its own.
    private fun chooseListPreset(kind: ListKind, onBack: () -> Unit) = read({ listPresets() }) { presets ->
        val title = getString(when (kind) {
            ListKind.BULLETS -> Resources.string.editors_list_bullets
            ListKind.NUMBERS -> Resources.string.editors_list_numbering
            ListKind.MULTILEVEL -> Resources.string.editors_list_multilevel
        })
        val none = TextView(this).apply {
            setText(Resources.string.editors_list_none)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setPaddingRelative((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            setBackgroundResource(R.drawable.editor_tool_background)
            setOnClickListener {
                edit { setList(if (kind == ListKind.BULLETS) 0 else 1, -1) }
                onBack()
            }
        }
        if (kind == ListKind.NUMBERS && editorsType == EditorsType.DOCS) {
            // Ids "<kind>-<index>", the kind as name
            val multilevel = getString(Resources.string.editors_list_multilevel)
            val selected = presets?.get(ListKind.MULTILEVEL)?.takeIf { it >= 0 }?.let { "multilevel-$it" }
                ?: presets?.get(ListKind.NUMBERS)?.takeIf { it >= 0 }?.let { "numbers-$it" }
            fun kindOf(preview: Preview) = if (preview.name == ListKind.MULTILEVEL.value) ListKind.MULTILEVEL else ListKind.NUMBERS
            fun indexOf(preview: Preview) = preview.id.substringAfterLast('-').toInt()
            previewPicker.show(
                title, "list-numbering", selected = selected, columnWidthDp = 88, header = none, onBack = onBack,
                section = { if (kindOf(it) == ListKind.MULTILEVEL) multilevel else null },
                describe = { getString(Resources.string.editors_list_style, if (kindOf(it) == ListKind.MULTILEVEL) multilevel else title, indexOf(it) + 1) }
            ) { preview -> edit { setListPreset(kindOf(preview), indexOf(preview)) } }
            return@read
        }
        previewPicker.show(
            title, "list-${kind.value}", selected = presets?.get(kind)?.takeIf { it >= 0 }?.toString(), columnWidthDp = 88,
            header = none, onBack = onBack, describe = { getString(Resources.string.editors_list_style, title, it.id.toInt() + 1) }
        ) { preview -> edit { setListPreset(kind, preview.id.toInt()) } }
    }

    // Text documents: the paragraph style by pictures of the styles (their names in them, in the
    // app's language), the current one marked
    private fun chooseParagraphStyle(current: String, onBack: () -> Unit) {
        val context = this
        read({ setStyleLabels(styles().associateWith { EditorTranslations.styleName(context, it) }) }) {
            previewPicker.show(
                getString(Resources.string.editors_format_style), "paragraphStyles", selected = current, columnWidthDp = 150,
                onBack = onBack, describe = { EditorTranslations.styleName(context, it.id) }
            ) { style -> edit { setStyle(style.id) } }
        }
    }

    private val languageChooser by lazy { LanguageChooser(this, panels) }

    // Documents and presentations: the text's language; its dictionary is downloaded right away
    // (again if that failed before)
    private fun chooseLanguage(current: Int, onBack: () -> Unit) {
        languageChooser.choose(EditorSpelling.dictionaries(this), current, onBack) { name, lcid ->
            edit { setTextLanguage(lcid) }
            if (spellCheckOn) downloadDictionary(name, announce = true, again = true)
        }
    }

    // Documents and presentations: the language of all the text and of new text; its dictionary
    // too, as for the text's language. Not while others edit parts of the document (co-editing).
    private fun chooseDocumentLanguage() {
        read({ documentLanguage() }) { current ->
            languageChooser.choose(EditorSpelling.dictionaries(this), current ?: 0, onBack = null, title = Resources.string.editors_document_language) { name, lcid ->
                edit {
                    val applied = setDocumentLanguage(lcid)
                    lifecycleScope.launch {
                        if (!applied) {
                            toast(getString(Resources.string.editors_document_language_locked), long = true)
                        } else if (spellCheckOn) {
                            downloadDictionary(name, announce = true, again = true)
                        }
                    }
                    state()
                }
            }
        }
    }

    // Spreadsheets: the cell styles by their pictures
    private fun chooseCellStyle(current: String, onBack: () -> Unit) {
        val context = this
        read({ setStyleLabels(cellStyles().associateWith { EditorTranslations.cellStyleName(context, it) }) }) {
            previewPicker.show(
                getString(Resources.string.editors_cells_cell_style), "cellStyles", selected = current, columnWidthDp = 112,
                onBack = onBack, describe = { EditorTranslations.cellStyleName(context, it.id) }
            ) { style -> edit { setCellStyle(style.id) } }
        }
    }

    private val cellFormatPanel by lazy {
        CellFormatPanel(this, { command -> edit(command = command) }, loadFonts = { onLoaded ->
            val document = document ?: return@CellFormatPanel
            lifecycleScope.launch {
                val lists = withContext(EditorEngine.dispatcher) { document.fonts() to document.documentFonts() }
                onLoaded(lists.first, lists.second)
            }
        }, panels = panels, colorPicker = colorPicker, shapes = shapePanel, chooseStyle = ::chooseCellStyle)
    }

    private val chartUi by lazy {
        ChartUi(this, lifecycleScope, { document }, iconPicker, panels, showBar = ::showChartBar) { command -> edit(command = command) }
    }

    private val pageSettingsUi by lazy { PageSettingsUi(this, lifecycleScope, { document }, panels) { command -> edit(command = command) } }

    private val previewPicker by lazy { PreviewPicker(this, lifecycleScope, { document }, panels) }

    // Presentations: the design of all slides, by pictures of them (converted the first time)
    private fun chooseDesign(onBack: (() -> Unit)?) = read({ prepareThemes() }) { count ->
        if ((count ?: 0) == 0) return@read
        previewPicker.show(getString(Resources.string.editors_slide_design), "themes", columnWidthDp = 136, withNames = true, onBack = onBack) { design ->
            design.id.toIntOrNull()?.let { edit { setTheme(it) } }
        }
    }

    // Reads from the document on the engine's thread, then hands the result (null if it failed) on
    private fun <T> read(query: EditorDocument.() -> T?, onResult: (T?) -> Unit) {
        val document = document ?: return
        lifecycleScope.launch {
            onResult(withContext(EditorEngine.dispatcher) { runCatching { document.query() }.getOrNull() })
        }
    }

    // A bar of the charts' (choosing a chart's cells) under the top bar
    private var chartBar: View? = null

    private fun showChartBar(bar: View?) {
        val root = findViewById<ViewGroup>(R.id.editorRoot)
        chartBar?.let { root.removeView(it) }
        chartBar = bar
        if (bar != null) root.addView(bar, root.indexOfChild(findViewById(R.id.editorTopBar)) + 1)
    }

    // The selected pictures, shapes and charts: the first section of the format panels
    private val shapePanel by lazy {
        ShapePanel(
            this, lifecycleScope, { document }, { command -> edit(command = command) }, chartUi,
            ChartSettingsPanel(this, { command -> edit(command = command) }, panels, previewPicker), colorPicker, panels,
            chart = { drawingUi.chart },
            withGroups = editorsType != EditorsType.PDF,
            chooseFill = if (editorsType == EditorsType.PDF) null else { back -> fillPanel.show(FillTarget.SHAPE, back) }
        )
    }

    private val drawingUi by lazy {
        DrawingUi(
            this, { command -> edit(command = command) },
            { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, ::takePhoto,
            chartUi,
            iconPicker,
            // Tables with a style: documents and presentations (spreadsheets' tables are cells)
            tablePicker = previewPicker.takeUnless { isSpreadsheet },
            moreInsertItems = { menu -> documentUi?.addInsertItems(menu) },
            // Text boxes take the typing at once (in spreadsheets at the active cell)
            insertTextBox = if (isSpreadsheet) {
                { edit { insertTextBox(0, 0.0, 0.0) } }
            } else {
                {
                    val (page, x, y) = documentView.visibleCenter() ?: Triple(0, 0.0, 0.0)
                    documentView.restartKeyboardInput()
                    edit { insertTextBox(page, x, y) }
                    documentView.showKeyboard()
                }
            }
        )
    }

    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            insertPicture(uri)
        } else {
            pictureField = null
            pictureFill = null
        }
    }

    private val photoFile by lazy { File(cacheDir, "editor-camera/photo.jpg") }

    private val photo = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) insertPicture(Uri.fromFile(photoFile))
    }

    // The app declares the camera permission: the camera app is only started for it once granted
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else toast(getString(Resources.string.editors_camera_denied))
    }

    private fun takePhoto() {
        val declared = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        }.getOrNull()?.contains(Manifest.permission.CAMERA) == true
        if (declared && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        photoFile.parentFile?.mkdirs()
        photoFile.delete()
        // The app's FileProvider (documents-app-android) shares the cache directory
        val uri = FileProvider.getUriForFile(this, "$packageName.asc.provider", photoFile)
        runCatching { photo.launch(uri) }.onFailure {
            Log.e(TAG, "no camera", it)
            toast(getString(Resources.string.editors_camera_failed))
        }
    }

    // Copies the picture into the document's images and inserts it at the cursor
    private fun insertPicture(uri: Uri) {
        Log.i(TAG, "inserting a picture")
        val document = document ?: return
        lifecycleScope.launch {
            val directory = withContext(EditorEngine.dispatcher) { document.imagesDirectory() }
            val picture = withContext(Dispatchers.IO) {
                runCatching { DrawingUi.importPicture(this@BaseEditorActivity, uri, File(directory)) }
                    .onFailure { Log.e(TAG, "can't import $uri", it) }.getOrNull()
            }
            if (picture == null) {
                toast(getString(Resources.string.editors_picture_failed))
                return@launch
            }
            val field = pictureField
            pictureField = null
            val fill = pictureFill
            pictureFill = null
            // Co-editing: the picture goes to the Document Server first, for the others and the saved document
            val name = coConnection?.let { connection ->
                uploadPicture(connection, document, File(directory, picture.first)) ?: run {
                    toast(getString(Resources.string.editors_coediting_upload_failed))
                    return@launch
                }
            } ?: picture.first
            if (fill != null) {
                fill(name, picture.second, picture.third)
            } else if (field != null) {
                edit { setFormPicture(field, name, picture.second, picture.third) }
            } else {
                edit { insertImage(name, picture.second, picture.third) }
            }
        }
    }

    // The server keeps the picture under a name of its own: the engine's copy takes that name
    private suspend fun uploadPicture(connection: CoEditingConnection, document: EditorDocument, file: File): String? {
        Log.i(TAG, "uploading the picture ${file.name} (${file.length()} bytes)")
        val (documentId, token) = withContext(EditorEngine.dispatcher) { document.coUploadInfo() }
        val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val urls = withContext(Dispatchers.IO) { connection.upload(file, type, documentId, token) } ?: return null
        val key = urls.keys().asSequence().firstOrNull { it.startsWith(MEDIA_PREFIX) } ?: return null
        val name = key.removePrefix(MEDIA_PREFIX)
        if (name.contains('/') || name.contains("..")) return null
        val renamed = withContext(Dispatchers.IO) { name == file.name || file.renameTo(File(file.parentFile, name)) }
        if (!renamed) return null
        withContext(EditorEngine.dispatcher) { document.coAddUrls(urls.toString()) }
        Log.i(TAG, "uploaded the picture as $name")
        return name
    }

    // A toolbar button's command, given whether it is to be on (a switch's)
    private fun bindTool(id: Int, command: EditorDocument.(selected: Boolean) -> EditorState) {
        findViewById<View>(id).setOnClickListener { view -> edit { command(!view.isSelected) } }
    }

    private fun bindTextTools() {
        bindTool(R.id.toolBold) { setBold(it) }
        bindTool(R.id.toolItalic) { setItalic(it) }
        bindTool(R.id.toolUnderline) { setUnderline(it) }
        bindTool(R.id.toolAlignLeft) { setAlignment(Alignment.LEFT) }
        bindTool(R.id.toolAlignCenter) { setAlignment(Alignment.CENTER) }
        bindTool(R.id.toolAlignRight) { setAlignment(Alignment.RIGHT) }
    }

    private fun bindToolbar() {
        bindTextTools()
        bindTool(R.id.toolUndo) { undo() }
        bindTool(R.id.toolRedo) { redo() }
        // Slides are added and deleted at the slides (the button at their end, a slide's menu)
        findViewById<View>(R.id.toolInsert).setOnClickListener { drawingUi.showInsertMenu(it) }
        documentUi?.let { ui ->
            // PDF forms keep their contents (showPageTools)
            val navigation = findViewById<View>(R.id.toolNavigation)
            if (!navigation.isVisible) {
                navigation.isVisible = true
                navigation.setOnClickListener { ui.showNavigation() }
            }
        }
        // The table at the cursor: adding and deleting rows from the text's menu too
        if (!isSpreadsheet) documentView.tableActions = listOf(
            getString(Resources.string.editors_doc_row_below) to { edit { tableAction("rowBelow") } },
            getString(Resources.string.editors_doc_delete_row) to { edit { tableAction("deleteRow") } }
        )
        findViewById<View>(R.id.toolFormat).isVisible = true
        findViewById<View>(R.id.toolFormat).setOnClickListener { showFormat() }
        // Selected pictures, shapes and charts: "Format" in their menu too
        val formatAction = listOf(getString(Resources.string.editors_format) to { showFormat() })
        if (isSpreadsheet) findViewById<SheetView>(R.id.sheetView).drawingActions = formatAction else documentView.drawingActions = formatAction
    }

    // The text's or cells' formatting; for a shape's text, back to the shape's panel
    // The format panel: of the selected drawings, text or cells, which the document shows above it
    private fun showFormat() {
        if (isSpreadsheet) {
            findViewById<SheetView>(R.id.sheetView).revealSelection()
            cellFormatPanel.show()
        } else {
            documentView.revealSelection()
            formatPanel.show()
        }
    }

    // The format button formats what is selected: pictures, shapes and charts while some are,
    // otherwise text or cells. Its picture tells which, and it pulses when it changes to them.
    private var formatsDrawings = false
        set(value) {
            if (field == value) return
            field = value
            val button = findViewById<ImageButton>(R.id.toolFormat)
            button.setImageResource(if (value) Resources.drawable.ic_editor_shape else Resources.drawable.ic_editor_format)
            if (value && button.isShown) {
                button.animate().scaleX(FORMAT_PULSE).scaleY(FORMAT_PULSE).setDuration(FORMAT_PULSE_MS)
                    .withEndAction { button.animate().scaleX(1f).scaleY(1f).setDuration(FORMAT_PULSE_MS) }
            }
        }

    // "Comment" in the menu of selected text or cells
    // On a commented cell (one comment per cell) it shows that comment
    private fun commentAction(): Pair<String, () -> Unit>? {
        val comments = commentsUi?.takeIf { it.canComment } ?: return null
        return getString(Resources.string.editors_comment) to {
            val sheet = currentSheet
            val existing = if (sheet == null) null else documentComments.firstOrNull {
                it.sheet == sheet.active && it.col == sheet.col && it.row == sheet.row
            }
            if (existing != null) comments.showThread(existing.id) else comments.add()
        }
    }

    // Spreadsheets: the sheet and the active cell, after the last command
    private var currentSheet: SheetState? = null

    // The comments: how many are open on their button, their marks on slides and cells
    private var documentComments: List<Comment> = emptyList()

    private fun showComments(comments: List<Comment>) {
        documentComments = comments
        findViewById<BadgeButton>(R.id.toolComments).count = comments.count { !it.solved }
        if (editorsType == EditorsType.PRESENTATION) {
            documentView.commentMarkers = comments.filter { it.page >= 0 }.map { DocumentView.CommentMarker(it.id, it.page, it.x, it.y) }
        }
        if (isSpreadsheet) {
            findViewById<SheetView>(R.id.sheetView).cellComments =
                comments.filter { it.sheet >= 0 }.map { SheetView.CellComment(it.id, it.sheet, it.col, it.row) }
        }
    }

    // Saving from the file menu or with Ctrl+S; co-editing, the Document Server saves
    private fun canSave() = editing && coConnection == null

    private fun saveNow() =
        save { error -> if (error == 0) toast(getString(Resources.string.editors_saved)) else toast(saveErrorMessage(error)) }

    // ---------------------------------------------------------------------------------
    // PDFs in sdkjs' PDF editor: pages, contents and search; marking text up, drawing, notes
    // (comments), signatures and others' annotations, saved into the PDF

    private var penOn = false
    // A text box or its text is selected (PdfState.textBox)
    private var pdfTextBox = false
    // The text of the page shown can be made editable (PdfState.canEditPage)
    private var pdfPageEditable = false
    // Pictures, shapes or text boxes are selected (PdfState.drawing)
    private var pdfDrawing = false

    private fun showPdfTools(document: EditorDocument, pages: List<PageSize>) {
        showPageTools(document, pages)
        // Searching starts on the page shown; its text can be made editable or not
        val onPage = documentView.onVisiblePageChanged
        documentView.onVisiblePageChanged = { page ->
            onPage?.invoke(page)
            lifecycleScope.launch {
                val editable = withContext(EditorEngine.dispatcher) {
                    val current = this@BaseEditorActivity.document ?: return@withContext false
                    current.setCurrentPage(page)
                    current.canEditPage(page)
                }
                pdfPageEditable = editable
                if (editing) updatePdfTools()
            }
        }
    }

    // While a text box is selected its text's tools take the place of the PDF's (marking up,
    // drawing, signing, text boxes), and formatting while a picture or shape is; "Edit text" for
    // pages whose text isn't editable yet
    private fun updatePdfTools() {
        val selected = pdfTextBox || pdfDrawing
        TEXT_TOOLS.forEach { findViewById<View>(it).isVisible = pdfTextBox }
        findViewById<View>(R.id.toolFormat).isVisible = selected
        listOf(R.id.toolSign, R.id.toolPen, R.id.toolTextBox).forEach { findViewById<View>(it).isVisible = !selected }
        findViewById<View>(R.id.toolEditText).isVisible = !selected && pdfPageEditable
    }

    private fun enablePdfEditing() {
        editing = true
        findViewById<View>(R.id.toolEdit).isVisible = false
        showToolbar(editable = true)
        // The document editor's tools don't apply
        listOf(
            R.id.toolBold, R.id.toolItalic, R.id.toolUnderline, R.id.toolAlignLeft, R.id.toolAlignCenter, R.id.toolAlignRight,
            R.id.toolInsert, R.id.toolFormat, R.id.toolSlideshow
        ).forEach { findViewById<View>(it).isVisible = false }
        documentView.readOnly = false
        // Typing only goes into text boxes and form fields
        documentView.keyboardWithCursor = true
        documentView.selectionActions = pdfSelectionActions(textBox = false)
        findViewById<View>(R.id.toolPen).isVisible = true
        findViewById<View>(R.id.toolPen).setOnClickListener { showPenMenu(it) }
        findViewById<View>(R.id.toolSign).isVisible = true
        findViewById<View>(R.id.toolSign).setOnClickListener { SignaturePad(this) { picture, aspect -> placeSignature(picture, aspect) }.show() }
        findViewById<View>(R.id.toolTextBox).isVisible = true
        findViewById<View>(R.id.toolTextBox).setOnClickListener { addTextBox() }
        findViewById<View>(R.id.toolEditText).setOnClickListener { editPageText() }
        // The text of text boxes (of pages whose text was made editable): its tools show while
        // one is selected (applyState)
        bindTextTools()
        // Pictures and shapes: fill, outline and order (their menu too); text: its formatting
        findViewById<View>(R.id.toolFormat).setOnClickListener { showFormat() }
        documentView.drawingActions = listOf(getString(Resources.string.editors_format) to { showFormat() })
        findViewById<View>(R.id.toolUndo).setOnClickListener { edit { undo() } }
        findViewById<View>(R.id.toolRedo).setOnClickListener { edit { redo() } }
    }

    // Selected text of the page is marked up; in text boxes it is formatted
    private fun pdfSelectionActions(textBox: Boolean): List<Pair<String, () -> Unit>> = if (textBox) listOf(
        getString(Resources.string.editors_pdf_underline) to { edit { setUnderline(true) } },
        getString(Resources.string.editors_pdf_strikeout) to { edit { setStrikeout(true) } }
    ) else listOfNotNull(
        getString(Resources.string.editors_pdf_highlight) to { edit { markup(PdfMarkupKind.HIGHLIGHT, "FFEB3B") } },
        getString(Resources.string.editors_pdf_underline) to { edit { markup(PdfMarkupKind.UNDERLINE, "3366D9") } },
        getString(Resources.string.editors_pdf_strikeout) to { edit { markup(PdfMarkupKind.STRIKEOUT, "D92626") } },
        commentAction()
    )

    // The text, shapes and pictures of the page shown become text boxes, shapes and pictures,
    // edited as on slides
    private fun editPageText() {
        val document = document ?: return
        if (penOn) setPen(null, 0.0)
        val page = documentView.visiblePage()
        lifecycleScope.launch {
            if (!withContext(EditorEngine.dispatcher) { document.canEditPage(page) }) {
                toast(getString(Resources.string.editors_pdf_edit_text_already))
                return@launch
            }
            edit { editPage(page) }
            // Co-editing: the others get the page's pictures from the Document Server
            coConnection?.let { connection -> uploadPagePictures(connection, document) }
            toast(getString(Resources.string.editors_pdf_edit_text_done))
        }
    }

    // The pictures a page made editable waits for (EditorDocument.imageUploads), uploaded; one
    // that can't be keeps its name (the others don't see it)
    private suspend fun uploadPagePictures(connection: CoEditingConnection, document: EditorDocument) {
        val names = withContext(EditorEngine.dispatcher) { document.imageUploads() }
        if (names.isEmpty()) return
        val directory = withContext(EditorEngine.dispatcher) { document.imagesDirectory() }
        var failed = false
        val uploaded = names.map { name ->
            uploadPicture(connection, document, File(directory, name)) ?: name.also { failed = true }
        }
        if (failed) toast(getString(Resources.string.editors_coediting_upload_failed))
        edit { finishImageUploads(uploaded) }
    }

    // Freehand drawing: pens of a few colors and a marker; one finger draws, two scroll
    private fun showPenMenu(anchor: View) {
        val pens = listOf(
            Triple(Resources.string.editors_color_black, "000000", 0.7),
            Triple(Resources.string.editors_color_red, "E53935", 0.7),
            Triple(Resources.string.editors_color_blue, "1E63D6", 0.7),
            Triple(Resources.string.editors_color_green, "2E9E44", 0.7),
            Triple(Resources.string.editors_pdf_marker, "FFEB3B", 4.0)
        )
        val menu = PopupMenu(this, anchor)
        pens.forEachIndexed { index, pen -> menu.menu.add(0, index, index, pen.first) }
        if (penOn) menu.menu.add(0, pens.size, pens.size, Resources.string.editors_pdf_pen_stop)
        menu.setOnMenuItemClickListener { item ->
            val pen = pens.getOrNull(item.itemId)
            setPen(pen?.second, pen?.third ?: 0.0)
            true
        }
        menu.show()
    }

    private fun setPen(color: String?, widthMm: Double) {
        penOn = color != null
        documentView.penMode = penOn
        findViewById<View>(R.id.toolPen).isSelected = penOn
        if (color != null) {
            toast(getString(Resources.string.editors_pdf_pen_on))
            edit { startInk(color, widthMm) }
        } else {
            edit { stopInk() }
        }
    }

    // A text box in the middle of the view (on its page), typed into at once
    private fun addTextBox() {
        val document = document ?: return
        if (penOn) setPen(null, 0.0)
        val (page, x, y) = documentView.visibleCenter() ?: return
        documentView.restartKeyboardInput()
        lifecycleScope.launch {
            val size = withContext(EditorEngine.dispatcher) { document.pageSize(page) }
            // All of it on the page (sdkjs makes it 200 x 85 points)
            val halfWidth = minOf(TEXT_BOX_WIDTH_MM, size.widthMm) / 2
            val halfHeight = minOf(TEXT_BOX_HEIGHT_MM, size.heightMm) / 2
            edit { addFreeText(page, x.coerceIn(halfWidth, size.widthMm - halfWidth), y.coerceIn(halfHeight, size.heightMm - halfHeight)) }
            documentView.showKeyboard()
        }
    }

    // A drawn signature, 50 mm wide in the middle of the page shown, selected to be moved
    private fun placeSignature(picture: File, aspect: Float) {
        val document = document ?: return
        val page = documentView.visiblePage()
        lifecycleScope.launch {
            val placed = withContext(EditorEngine.dispatcher) {
                val name = "signature-${System.currentTimeMillis()}.png"
                val copy = runCatching { picture.copyTo(File(document.imagesDirectory(), name)) }.getOrNull() ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(picture.path, bounds)
                Triple(copy, bounds, document.pageSize(page))
            } ?: return@launch
            val (copy, bounds, size) = placed
            // Co-editing: the others get it from the Document Server
            val name = coConnection?.let { connection ->
                uploadPicture(connection, document, copy) ?: run {
                    toast(getString(Resources.string.editors_coediting_upload_failed))
                    return@launch
                }
            } ?: copy.name
            val width = minOf(50.0, size.widthMm * 0.8)
            val height = width / aspect
            edit { placeImage(name, bounds.outWidth, bounds.outHeight, page, (size.widthMm - width) / 2, (size.heightMm - height) / 2, width, height) }
        }
    }

    // PDF files: marking selected text up and signing, written into the file
    private fun enablePdfChanges(text: PdfText) {
        fun markup(type: Int, color: FloatArray) = lifecycleScope.launch {
            applyPdfChanges(text.takeSelectionMarkups(type, color), emptyList())
        }
        documentView.selectionActions = listOf(
            getString(Resources.string.editors_pdf_highlight) to { markup(8, floatArrayOf(1f, 0.92f, 0.23f)) },
            getString(Resources.string.editors_pdf_underline) to { markup(9, floatArrayOf(0.2f, 0.4f, 0.85f)) },
            getString(Resources.string.editors_pdf_strikeout) to { markup(11, floatArrayOf(0.85f, 0.15f, 0.15f)) }
        )
        findViewById<View>(R.id.toolSign).isVisible = true
        findViewById<View>(R.id.toolSign).setOnClickListener {
            SignaturePad(this) { picture, aspect ->
                toast(getString(Resources.string.editors_sign_tap))
                text.onPlace = { page, x, y ->
                    val width = 50f
                    val height = width / aspect
                    applyPdfChanges(emptyList(), listOf(PdfImage(page, picture.path, x - width / 2, y - height / 2, width, height)))
                }
            }.show()
        }
    }

    // Saves the PDF with the changes into a new copy and shows that one
    private fun applyPdfChanges(markups: List<PdfMarkup>, images: List<PdfImage>) {
        if (markups.isEmpty() && images.isEmpty()) return
        val document = drawingDocument ?: return
        val uri = documentUri ?: return
        val current = pdfFile ?: return
        val text = pdfText ?: return
        // Not drawn while it is saved and closed
        drawingDocument = null
        val page = documentView.visiblePage()
        lifecycleScope.launch {
            val target = File(cacheDir, "pdf-edit/edit-${System.currentTimeMillis()}.pdf").apply { parentFile?.mkdirs() }
            val author = getSharedPreferences("lib.editors.comments", MODE_PRIVATE).getString("user_name", null).orEmpty()
            val error = withContext(EditorEngine.dispatcher) { document.saveWithChanges(target.path, markups, images, author) }
            if (error != 0) toast(getString(Resources.string.editors_save_failed, error))
            val file = if (error == 0) target else current
            val reopened = withContext(EditorEngine.dispatcher) {
                (EditorEngine.openDrawingFile(this@BaseEditorActivity, file, pdfPassword) as? DrawingFileResult.Opened)?.document
            }
            if (reopened == null) {
                finish()
                return@launch
            }
            drawingDocument = reopened
            if (error == 0) {
                pdfFile = target
                pdfEdited = true
                setResult(RESULT_OK, Intent().setData(uri).putExtra(EditorsContract.EXTRA_IS_MODIFIED, true))
            }
            text.replaceDocument(reopened)
            documentView.reloadPages()
            documentView.goToPage(page)
        }
    }

    // Large screens: thumbnails of slides and pages beside them, instead of above the toolbar
    private val sideStrips get() = resources.getBoolean(R.bool.editor_side_panels)

    private fun addStrip(strip: View) {
        if (sideStrips) {
            findViewById<ViewGroup>(R.id.editorStartPane).apply {
                addView(strip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
                isVisible = true
            }
        } else {
            val root = findViewById<ViewGroup>(R.id.editorRoot)
            root.addView(strip, root.indexOfChild(findViewById(R.id.editorToolbar)))
        }
    }

    // PDF, XPS and DjVu: page thumbnails and the outline with going to a page
    private fun showPageTools(file: PagedDocument, pages: List<PageSize>) {
        val strip = PageStrip(this, lifecycleScope, pages,
            render = { page, width, height -> renderTile(page, width, height, 0, 0, width, height) },
            onPage = { documentView.goToPage(it) }, vertical = sideStrips)
        addStrip(strip.view)
        documentView.onVisiblePageChanged = { strip.setCurrent(it) }
        findViewById<View>(R.id.toolPages).isVisible = true
        findViewById<View>(R.id.toolPages).setOnClickListener { strip.toggle() }
        // Contents and pages: the top bar's navigation, as in documents
        findViewById<View>(R.id.toolNavigation).isVisible = true
        findViewById<View>(R.id.toolNavigation).setOnClickListener {
            lifecycleScope.launch {
                val outline = withContext(EditorEngine.dispatcher) { file.fileOutline() }
                val labels = listOf(getString(Resources.string.editors_doc_go_to_page)) +
                    outline.map { "\u2003".repeat((it.level - 1).coerceAtLeast(0)) + it.title }
                AlertDialog.Builder(this@BaseEditorActivity)
                    .setTitle(Resources.string.editors_pages_contents)
                    .setItems(labels.toTypedArray()) { _, position ->
                        if (position == 0) askPage(pages.size) else outline[position - 1].let { documentView.goToPage(it.page, it.yMm) }
                    }
                    .show()
            }
        }
    }

    private fun askPage(pageCount: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((documentView.visiblePage() + 1).toString())
            selectAll()
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        AlertDialog.Builder(this)
            .setTitle(getString(Resources.string.editors_pages_go_to, pageCount))
            .setView(FrameLayout(this).apply {
                setPaddingRelative(padding, 0, padding, 0)
                addView(input)
            })
            .setPositiveButton(android.R.string.ok) { _, _ -> input.text.toString().toIntOrNull()?.let { documentView.goToPage(it - 1) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------
    // Find, links

    // The toolbar; viewing only, it has just the search
    private fun showToolbar(editable: Boolean) {
        findViewById<View>(R.id.editorTopBar).isVisible = true
        findViewById<View>(R.id.editorToolbar).isVisible = true
        if (!editable) {
            // The editing buttons come back when editing starts
            for (buttons in listOf<ViewGroup>(findViewById(R.id.editorTopButtons), findViewById(R.id.editorToolButtons))) {
                for (i in 0 until buttons.childCount) {
                    val button = buttons.getChildAt(i)
                    if (button.isVisible && button.id !in VIEWING_TOOLS) {
                        hiddenTools += button
                        button.isVisible = false
                    }
                }
            }
        } else {
            hiddenTools.forEach { it.isVisible = true }
            hiddenTools.clear()
        }
    }

    private fun addSearchBar(target: SearchBar.Target) {
        val bar = SearchBar(this, target)
        // Under the top bar
        val root = findViewById<ViewGroup>(R.id.editorRoot)
        root.addView(bar.view, root.indexOfChild(findViewById(R.id.editorTopBar)) + 1)
        searchBar = bar
    }

    private fun createLinkBar(): View = layoutInflater.inflate(R.layout.editor_link_bar, findViewById(R.id.editorRoot), false)

    /** Shows the link (its address or place) with a button that opens it; hides it for null. */
    private fun showLink(text: String?, onOpen: (() -> Unit)?) {
        linkBar.isVisible = text != null && onOpen != null
        if (text == null || onOpen == null) return
        linkBar.findViewById<TextView>(R.id.linkText).text = text
        linkBar.findViewById<View>(R.id.linkOpen).setOnClickListener { onOpen() }
    }

    private fun openUrl(url: String) {
        val address = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url else "https://$url"
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
            .onFailure { toast(getString(Resources.string.editors_link_failed)) }
    }

    // ---------------------------------------------------------------------------------
    // Saving

    override fun onStop() {
        super.onStop()
        // The app may be closed in the background: keep the edits (co-editing: the server keeps
        // them, once they are sent)
        if (modified && !isChangingConfigurations && coEditingUi == null) save {}
        if (coEditingUi != null) document?.let { lifecycleScope.launch(EditorEngine.dispatcher) { runCatching { it.coFlush() } } }
    }

    /** Leaves the editor, saving the edits first; asks what to do if that doesn't work. */
    private fun leave() {
        // Co-editing: the Document Server saves into the integration when everybody left; the
        // last changes go to it first (sdkjs sends them a second or two after the last one)
        if (coEditingUi != null) {
            if (leaving) return
            leaving = true
            lifecycleScope.launch {
                flushCoEditing()
                stopCoEditing()
                setResult(RESULT_OK, Intent().setData(documentUri).putExtra(EditorsContract.EXTRA_IS_MODIFIED, false))
                finish()
            }
            return
        }
        if (pdfEdited || modified) {
            AlertDialog.Builder(this)
                .setTitle(Resources.string.editors_save_changes_title)
                .setMessage(getString(Resources.string.editors_save_changes_message, findViewById<TextView>(R.id.editorTitle).text))
                .setPositiveButton(Resources.string.editors_save) { _, _ -> saveAndLeave() }
                .setNegativeButton(Resources.string.editors_discard) { _, _ -> finish() }
                .setNeutralButton(Resources.string.editors_cancel, null)
                .show()
            return
        }
        finish()
    }

    // Leaving with the changes saved: if saving fails, leaving without them or staying
    private fun saveAndLeave() {
        // A changed PDF goes back into the file the app gave
        if (pdfEdited) {
            val file = pdfFile
            val uri = documentUri
            pdfEdited = false
            lifecycleScope.launch {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri!!, "wt")?.use { output -> file!!.inputStream().use { it.copyTo(output) } }
                    }.getOrNull() != null
                }
                if (!written) toast(getString(Resources.string.editors_save_failed, WRITE_ERROR))
                finish()
            }
            return
        }
        save { error ->
            if (error == 0) {
                finish()
            } else if (!isFinishing) {
                AlertDialog.Builder(this)
                    .setTitle(Resources.string.editors_leave_unsaved_title)
                    .setMessage(saveErrorMessage(error))
                    .setPositiveButton(Resources.string.editors_leave) { _, _ -> finish() }
                    .setNegativeButton(Resources.string.editors_stay, null)
                    .show()
            }
        }
    }

    /**
     * Saves into the document the app passed. [onDone] gets 0, the converter's error code,
     * [UNSUPPORTED_FORMAT] or [WRITE_ERROR].
     */
    private fun save(onDone: (Int) -> Unit) {
        val uri = documentUri ?: return
        // Taken now: onDestroy forgets it, but closes it only after this save (same thread)
        val document = document ?: return
        if (saving) return
        saving = true
        // Not tied to the activity: saving finishes even if it is destroyed meanwhile. Started at
        // once, so it is on the engine's thread before onDestroy's close
        CoroutineScope(Dispatchers.Main).launch(start = CoroutineStart.UNDISPATCHED) {
            val (error, state) = withContext(EditorEngine.dispatcher) {
                saveInto(document, uri) to runCatching { document.state() }.getOrNull()
            }
            saving = false
            if (error == 0) {
                documentSaved = extension in WRITABLE_FORMATS
                updateResult()
            } else {
                Log.e(TAG, "save failed: $error")
            }
            if (!isDestroyed && state != null) applyState(state)
            onDone(error)
        }
    }

    // Runs on the engine's thread: x2t writes a file, which then replaces the document. Formats
    // the engine can't write (doc, xls, ppt, ...) are saved as a modern copy next to it instead.
    private fun saveInto(document: EditorDocument, uri: Uri): Int {
        if (extension !in WRITABLE_FORMATS) {
            val format = modernFormat()
            val dir = File(cacheDir, "editor-output").apply { mkdirs() }
            val file = File(dir, "$baseName.$format")
            file.delete()
            val error = document.save(file.path, format)
            if (error != 0) return error
            val message = deliverCopy(file, "$baseName.$format")
            runOnUiThread { toast(message) }
            return 0
        }
        val start = SystemClock.elapsedRealtime()
        val dir = File(cacheDir, "editor-output").apply { mkdirs() }
        val file = File(dir, "document.$extension")
        file.delete()
        val error = document.save(file.path, extension)
        if (error != 0) return error
        val written = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
        }.getOrNull() != null
        file.delete()
        if (!written) {
            // sdkjs considers the document saved
            document.execute("Asc.editor.SetDocumentModified(true)")
            return WRITE_ERROR
        }
        Log.i(TAG, "saved: ${SystemClock.elapsedRealtime() - start} ms")
        return 0
    }

    private fun modernFormat() = when (editorsType) {
        EditorsType.CELLS -> "xlsx"
        EditorsType.PRESENTATION -> "pptx"
        else -> "docx"
    }

    // Formats for "save as": the document's own (if the engine writes it) and the common ones
    private fun copyFormats(): List<String> {
        val common = when (editorsType) {
            EditorsType.CELLS -> listOf("xlsx", "ods", "csv", "pdf")
            EditorsType.PRESENTATION -> listOf("pptx", "odp", "pdf")
            else -> listOf("docx", "odt", "rtf", "txt", "pdf")
        }
        return (listOfNotNull(extension.takeIf { it in WRITABLE_FORMATS }) + common).distinct()
    }

    /**
     * Puts a copy of the document next to it: in the same folder for files on the device, or
     * for the app to upload into the document's folder when the editor is closed. Returns a
     * message for the user.
     */
    private fun deliverCopy(file: File, name: String): String {
        val uri = documentUri
        val path = uri?.takeIf { it.scheme == "file" }?.path
        if (path != null) {
            val folder = File(path).parentFile
            if (folder != null && folder.canWrite()) {
                val target = uniqueFile(folder, name)
                file.copyTo(target, overwrite = true)
                return getString(Resources.string.editors_file_saved_next_to, target.name)
            }
        }
        val dir = File(cacheDir, "editor-copies").apply { mkdirs() }
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        // The app's FileProvider (documents-app-android) shares the cache directory
        val copy = FileProvider.getUriForFile(this, "$packageName.asc.provider", target)
        if (copy !in savedCopies) savedCopies += copy
        runOnUiThread { updateResult() }
        return getString(Resources.string.editors_file_saved_on_close, name)
    }

    private fun uniqueFile(folder: File, name: String): File {
        var file = File(folder, name)
        var number = 2
        while (file.exists()) {
            file = File(folder, "${name.substringBeforeLast('.')} ($number).${name.substringAfterLast('.')}")
            number++
        }
        return file
    }

    private fun updateResult(uri: Uri? = documentUri) {
        uri ?: return
        setResult(
            RESULT_OK,
            Intent().setData(uri)
                .putExtra(EditorsContract.EXTRA_IS_MODIFIED, documentSaved)
                .putParcelableArrayListExtra(EditorsContract.EXTRA_SAVED_FILES, ArrayList(savedCopies))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private val fileUi by lazy {
        FileUi(
            this, lifecycleScope, { document }, copyFormats(), { baseName }, ::deliverCopy,
            spellCheck = { spellCheckOn.takeIf { editing && editorsType != EditorsType.CELLS } },
            setSpellCheck = ::setSpellCheck,
            chooseDocumentLanguage = ::chooseDocumentLanguage,
            moreItems = { menu ->
                fun item(label: Int, icon: Int, action: () -> Unit) =
                    menu.add(label).setIcon(icon).setOnMenuItemClickListener { action(); true }
                if (searchBar != null) item(Resources.string.editors_search, Resources.drawable.ic_editor_search) { searchBar?.show() }
                if (canSave()) item(Resources.string.editors_save, Resources.drawable.ic_editor_save) { saveNow() }.isEnabled = modified
                if (canSave() && !isPdfEditor && extension in ENCRYPTABLE_FORMATS) {
                    val label = if (documentPassword.isEmpty()) Resources.string.editors_password_protect else Resources.string.editors_password_change
                    item(label, Resources.drawable.ic_editor_lock) { askNewPassword() }
                    if (documentPassword.isNotEmpty()) {
                        item(Resources.string.editors_password_remove, Resources.drawable.ic_editor_lock_open) { changePassword("") }
                    }
                }
                // Not in co-editing, where the Document Server reads the file
                val text = textOptions
                val file = textFile
                if (text != null && file != null && coConnection == null) {
                    val label = if (text.delimiter >= 0) Resources.string.editors_text_options_csv else Resources.string.editors_text_options
                    item(label, Resources.drawable.ic_editor_encoding) {
                        TextOptionsDialog(this@BaseEditorActivity, file, text, discards = modified, onChosen = ::reopenWith).show()
                    }
                }
                if (canUseMobileView()) {
                    item(Resources.string.editors_doc_mobile_view, Resources.drawable.ic_editor_mobile_view) { toggleMobileView() }.apply {
                        isCheckable = true
                        isChecked = mobileViewChosen
                    }
                }
                documentUi?.addDocumentItems(menu)
                // Presentations' and spreadsheets' page setup (documents': documentUi's)
                if (editing && !isPdfEditor) {
                    when (editorsType) {
                        EditorsType.PRESENTATION -> item(Resources.string.editors_doc_page_setup, Resources.drawable.ic_editor_page_setup) { pageSettingsUi.showSlides() }
                        EditorsType.CELLS -> item(Resources.string.editors_doc_page_setup, Resources.drawable.ic_editor_page_setup) { pageSettingsUi.showSheet() }
                        else -> Unit
                    }
                }
            }
        )
    }

    private fun saveErrorMessage(error: Int): String =
        if (error == UNSUPPORTED_FORMAT) getString(Resources.string.editors_save_unsupported, extension)
        else getString(Resources.string.editors_save_failed, error)

    private fun toast(text: String, long: Boolean = false) = Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private suspend fun renderTile(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, width: Int, height: Int): Bitmap? =
        withContext(EditorEngine.dispatcher) {
            val document = document ?: drawingDocument ?: return@withContext null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val start = SystemClock.elapsedRealtime()
            val rendered = document.renderTile(page, pageWidth, pageHeight, x, y, bitmap)
            Log.i(TAG, "render page ${page + 1} ${width}x$height: ${SystemClock.elapsedRealtime() - start} ms")
            if (rendered) bitmap else null
        }

    // Screen readers: the text of a page
    private suspend fun textBlocks(page: Int): List<TextBlock> = withContext(EditorEngine.dispatcher) {
        (document ?: drawingDocument)?.let { runCatching { it.textBlocks(page) }.getOrNull() }.orEmpty()
    }

    // Plain text read with other options: the editor starts again on the file with them, and
    // gives the app its result (FLAG_ACTIVITY_FORWARD_RESULT) with what was saved before
    private fun reopenWith(text: TextOptions) {
        val opened = document ?: return
        document = null
        lifecycleScope.launch {
            withContext(EditorEngine.dispatcher) { opened.close() }
            val restart = Intent(intent)
                .putExtra(EXTRA_TEXT_ENCODING, text.encoding)
                .putExtra(EXTRA_TEXT_DELIMITER, text.delimiter)
                .putExtra(EXTRA_REOPEN_SAVED, documentSaved)
                .putParcelableArrayListExtra(EXTRA_REOPEN_COPIES, ArrayList(savedCopies))
                .putExtra(EXTRA_REOPEN_EDITING, editing)
            // The app starts editors single-top, which would hand this one the intent instead
            restart.flags = (intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP.inv()) or Intent.FLAG_ACTIVITY_FORWARD_RESULT
            startActivity(restart)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun copyToCache(uri: Uri): File {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        val dir = File(cacheDir, "editor-input").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, name)
        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun showPlaceholder(uri: Uri?) {
        setContentView(R.layout.activity_editor_placeholder)
        findViewById<TextView>(R.id.editorTitle).setText(getTitleRes())
        findViewById<TextView>(R.id.editorFile).text =
            uri?.lastPathSegment ?: getString(Resources.string.editors_server_document)

        if (uri != null && DocumentConverter.isAvailable) {
            findViewById<Button>(R.id.editorConvert).apply {
                visibility = View.VISIBLE
                setOnClickListener { convert(uri) }
            }
        }
    }

    /** Developer check of the native core for editors that don't exist yet: converts the document with x2t. */
    private fun convert(uri: Uri) {
        val button = findViewById<Button>(R.id.editorConvert)
        val status = findViewById<TextView>(R.id.editorConvertStatus)
        button.isEnabled = false
        status.setText(Resources.string.editors_converting)

        lifecycleScope.launch {
            val (result, duration) = withContext(Dispatchers.IO) {
                val source = copyToCache(uri)
                val target = File(source.parentFile, "Editor.bin")
                val start = SystemClock.elapsedRealtime()
                DocumentConverter.convertToEditorBin(this@BaseEditorActivity, source, target) to
                    SystemClock.elapsedRealtime() - start
            }
            button.isEnabled = true
            val output = result.output
            status.text = if (output == null) {
                getString(Resources.string.editors_convert_failed, result.code)
            } else {
                getString(Resources.string.editors_convert_success, duration, output.length() / 1024)
            }
        }
    }

    private fun getTitleRes(): Int = when (editorsType) {
        EditorsType.DOCS -> Resources.string.editors_type_docs
        EditorsType.CELLS -> Resources.string.editors_type_cells
        EditorsType.PRESENTATION -> Resources.string.editors_type_slides
        EditorsType.PDF -> Resources.string.editors_type_pdf
    }

    private companion object {
        const val TAG = "EditorViewer"
        const val VIEW_PREFERENCES = "editors_view"
        const val MOBILE_VIEW = "mobileView"
        const val TEXT_SCALE = "mobileViewTextScale"
        const val MOBILE_VIEW_SETTLE_MS = 3000L
        private const val TEXT_BOX_WIDTH_MM = 200 * 25.4 / 72
        private const val TEXT_BOX_HEIGHT_MM = 85 * 25.4 / 72

        val ENGINE_EDITORS = setOf(EditorsType.DOCS, EditorsType.PRESENTATION, EditorsType.CELLS)
        const val LAYOUT_SLICE_MS = 30
        /** Editors that co-edit through a Document Server. */
        val CO_EDITORS = setOf(EditorsType.DOCS, EditorsType.PRESENTATION, EditorsType.CELLS, EditorsType.PDF)
        const val CO_TICK_MS = 100L
        const val CO_FLUSH_MS = 5000L
        const val FORMAT_PULSE = 1.25f
        const val FORMAT_PULSE_MS = 150L
        const val MEDIA_PREFIX = "media/"
        const val CO_TICK_BUDGET_MS = 20
        /** Formats the engine saves encrypted with a password (Office's and ODF). */
        val ENCRYPTABLE_FORMATS = setOf(
            "docx", "docm", "dotx", "dotm", "xlsx", "xlsm", "xltx", "xltm", "pptx", "pptm", "ppsx", "ppsm", "potx", "potm",
            "odt", "ott", "ods", "ots", "odp", "otp"
        )
        const val MAX_ERROR_CHARS = 2000
        const val SPELL_SLICE_MS = 30
        const val MAX_SUGGESTIONS = 3
        const val SPELLING_PREFERENCES = "lib.editors.spelling"
        const val KEY_SPELL_CHECK = "spell_check"
        // Opening plain text again with other options (reopenWith)
        const val EXTRA_TEXT_ENCODING = "lib.editors.gbase.TEXT_ENCODING"
        const val EXTRA_TEXT_DELIMITER = "lib.editors.gbase.TEXT_DELIMITER"
        const val EXTRA_REOPEN_SAVED = "lib.editors.gbase.REOPEN_SAVED"
        const val EXTRA_REOPEN_COPIES = "lib.editors.gbase.REOPEN_COPIES"
        const val EXTRA_REOPEN_EDITING = "lib.editors.gbase.REOPEN_EDITING"
        private val TEXT_TOOLS = listOf(R.id.toolBold, R.id.toolItalic, R.id.toolUnderline, R.id.toolAlignLeft, R.id.toolAlignCenter, R.id.toolAlignRight)
        // Toolbar buttons also when viewing
        private val FILLING_HIDDEN_TOOLS = listOf(
            R.id.toolInsert, R.id.toolFormat, R.id.toolBold, R.id.toolItalic,
            R.id.toolUnderline, R.id.toolAlignLeft, R.id.toolAlignCenter, R.id.toolAlignRight
        )
        private val VIEWING_TOOLS = setOf(
            R.id.toolFile, R.id.toolComments, R.id.toolSlideshow, R.id.toolEdit, R.id.toolPages, R.id.toolNavigation,
            R.id.toolSign, R.id.toolCoEditors
        )
        const val UNSUPPORTED_FORMAT = -1
        const val WRITE_ERROR = -2
    }
}
