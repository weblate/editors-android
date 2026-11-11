package lib.editors.gbase.ui.activities

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.view.Menu
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.resources.R as Resources
import java.io.File
import java.io.FileOutputStream

/**
 * The file menu (the top bar's "⋮"): save a copy in another format (next to the document, see
 * [deliverCopy]), export as PDF, print and share; spell checking on or off and the document's
 * language while editing. The
 * editor puts its own items first ([moreItems]).
 */
internal class FileUi(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    /** Formats a copy can be saved in, the document's own first. */
    private val formats: List<String>,
    /** The document's name without its extension. */
    private val baseName: () -> String,
    /** Puts a saved copy next to the document; returns a message for the user. */
    private val deliverCopy: (file: File, name: String) -> String,
    /** Whether spell checking is on; null where there is none (viewing, spreadsheets). */
    private val spellCheck: () -> Boolean? = { null },
    private val setSpellCheck: (Boolean) -> Unit = {},
    /** The document's language, where spell checking is. */
    private val chooseDocumentLanguage: () -> Unit = {},
    /** Items first in the menu, which do their actions themselves: finding, saving, the document's settings. */
    private val moreItems: (Menu) -> Unit = {}
) {

    private val density = activity.resources.displayMetrics.density

    fun showMenu(anchor: View) {
        val actions = listOf<Triple<Int, Int, () -> Unit>>(
            Triple(Resources.string.editors_file_save_as, Resources.drawable.ic_editor_download) { saveAs() },
            Triple(Resources.string.editors_file_export_pdf, Resources.drawable.ic_editor_download) { saveCopy(baseName(), "pdf") },
            Triple(Resources.string.editors_file_print, Resources.drawable.ic_editor_print) { print() },
            Triple(Resources.string.editors_file_share, Resources.drawable.ic_editor_export) { share(formats.first()) },
            Triple(Resources.string.editors_file_share_pdf, Resources.drawable.ic_editor_export) { share("pdf") }
        )
        PopupMenu(activity, anchor).apply {
            moreItems(menu)
            actions.forEachIndexed { i, (label, icon, _) -> menu.add(Menu.NONE, i, FILE_ORDER + i, label).setIcon(icon) }
            spellCheck()?.let { on ->
                menu.add(Menu.NONE, MENU_SPELL_CHECK, FILE_ORDER + actions.size, Resources.string.editors_spell_check).apply {
                    setIcon(Resources.drawable.ic_editor_spellcheck)
                    isCheckable = true
                    isChecked = on
                }
                menu.add(Menu.NONE, MENU_DOCUMENT_LANGUAGE, FILE_ORDER + actions.size + 1, Resources.string.editors_document_language)
                    .setIcon(Resources.drawable.ic_editor_language)
            }
            setOnMenuItemClickListener {
                // Opening a submenu of moreItems (their ids aren't these)
                if (it.hasSubMenu()) return@setOnMenuItemClickListener false
                when (it.itemId) {
                    MENU_SPELL_CHECK -> setSpellCheck(!it.isChecked)
                    MENU_DOCUMENT_LANGUAGE -> chooseDocumentLanguage()
                    else -> actions[it.itemId].third()
                }
                true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setForceShowIcon(true)
        }.show()
    }

    private fun saveAs() {
        val name = EditText(activity).apply {
            setText(activity.getString(Resources.string.editors_file_copy_name, baseName()))
            setSingleLine()
            selectAll()
        }
        val group = RadioGroup(activity)
        formats.forEachIndexed { index, format ->
            group.addView(RadioButton(activity).apply {
                id = View.generateViewId()
                text = format.uppercase()
                isChecked = index == 0
                tag = format
            })
        }
        val padding = (20 * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(padding, 0, padding, 0)
            addView(name)
            addView(group)
        }
        AlertDialog.Builder(activity)
            .setTitle(Resources.string.editors_file_save_as)
            .setView(content)
            .setPositiveButton(Resources.string.editors_save) { _, _ ->
                val format = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String ?: formats.first()
                saveCopy(name.text.toString().trim().ifEmpty { baseName() }, format)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveCopy(name: String, format: String) = export(format) { file ->
        toast(deliverCopy(file, "$name.$format"))
    }

    // The document with its edits in the format, in the cache; then onFile on the main thread
    private fun export(format: String, onFile: (File) -> Unit) {
        val document = document() ?: return
        toast(activity.getString(Resources.string.editors_file_exporting))
        scope.launch {
            val dir = File(activity.cacheDir, "editor-export").apply { mkdirs() }
            val file = File(dir, "${baseName()}.$format")
            file.delete()
            val error = withContext(EditorEngine.dispatcher) { runCatching { document.save(file.path, format) }.getOrDefault(-1) }
            if (error != 0 || !file.exists()) {
                toast(activity.getString(Resources.string.editors_save_failed, error))
                return@launch
            }
            onFile(file)
        }
    }

    fun print() = export("pdf") { file ->
        val printManager = activity.getSystemService(PrintManager::class.java) ?: return@export
        printManager.print(baseName(), PdfPrintAdapter(file, baseName()), PrintAttributes.Builder().build())
    }

    private fun share(format: String) = export(format) { file ->
        val dir = File(activity.cacheDir, "editor-share").apply {
            deleteRecursively()
            mkdirs()
        }
        val shared = File(dir, "${baseName()}.$format")
        file.copyTo(shared, overwrite = true)
        // The app's FileProvider (documents-app-android) shares the cache directory
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.asc.provider", shared)
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(format) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(shared.name, uri)
        activity.startActivity(Intent.createChooser(intent, activity.getString(Resources.string.editors_file_share)))
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MENU_SPELL_CHECK = 100
        const val MENU_DOCUMENT_LANGUAGE = 101
        // After moreItems
        const val FILE_ORDER = 100
    }

    /** Prints a PDF file as it is. */
    private class PdfPrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback, extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) return callback.onLayoutCancelled()
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder("$name.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),
                true
            )
        }

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            runCatching {
                file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            }.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                .onFailure { callback.onWriteFailed(it.message) }
        }
    }
}
