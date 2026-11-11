package lib.editors.gbase.ui.activities

import android.content.Context
import lib.editors.base.engine.Dictionaries
import lib.editors.resources.R as Resources
import lib.toolkit.base.managers.utils.EditorDictionaries

/**
 * Choosing the text's or the document's language in a panel (see [PanelHost.choose]): the
 * languages there are dictionaries for, by their names in the device's language; the ones on the
 * device first.
 */
internal class LanguageChooser(private val context: Context, private val panels: PanelHost) {

    /**
     * [current] the text's or document's language (LCID), then [onBack] (null: the panel closes);
     * [onLanguage] gets the dictionary and its language.
     */
    fun choose(
        dictionaries: Dictionaries, current: Int, onBack: (() -> Unit)?, title: Int = Resources.string.editors_format_language,
        onLanguage: (name: String, lcid: Int) -> Unit
    ) {
        val all = dictionaries.all()
        if (all.isEmpty()) return
        val names = all.keys.map { it to EditorDictionaries.languageName(it) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
        val index = names.withIndex().associate { it.value.first to it.index }
        val installed = dictionaries.installed().mapNotNull { index[it] }.sorted()
        panels.choose(
            context.getString(title), names.map { it.second },
            dictionaries.nameFor(current)?.let { index[it] } ?: -1, onBack,
            searchHint = context.getString(Resources.string.editors_format_language_find),
            sections = listOf(PanelHost.Section(context.getString(Resources.string.editors_format_languages_installed), installed)),
            allTitle = context.getString(Resources.string.editors_format_languages_all)
        ) { chosen ->
            val name = names[chosen].first
            onLanguage(name, all.getValue(name))
        }
    }
}
