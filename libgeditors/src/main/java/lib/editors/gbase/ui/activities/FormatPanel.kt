package lib.editors.gbase.ui.activities

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import lib.editors.base.engine.Alignment
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.FillTarget
import lib.editors.base.engine.ListKind
import lib.editors.base.engine.ParagraphOption
import lib.editors.base.engine.TextFormat
import lib.editors.resources.R as Resources
import lib.toolkit.base.managers.utils.EditorDictionaries

/**
 * The formatting panel: font, size, styles, colors and paragraph settings of the text at the
 * cursor or the selection, in a panel (see [PanelHost]) that leaves the document usable; the
 * selected pictures, shapes and charts first ([ShapePanel]), then their text's settings; in
 * presentations without a selected one, the slide's background, design, layout and transition;
 * with the cursor in a table, the table's rows, columns, cells and style. It shows the current
 * formatting (see [update]) and sends changes through [edit].
 */
internal class FormatPanel(
    context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    /** Fonts (all and the document's), from the engine (on its thread). */
    private val loadFonts: (onLoaded: (fonts: List<String>, documentFonts: List<String>) -> Unit) -> Unit,
    /** Paragraph settings: documents and presentations. */
    private val withParagraphs: Boolean,
    /** Highlighting text: not in PDFs, whose editor marks text up instead. */
    private val withHighlights: Boolean,
    private val panels: PanelHost,
    private val colorPicker: ColorPicker,
    /** The selected drawings' section; null without one (the text's settings only). */
    private val shapes: ShapePanel? = null,
    /** Text documents: the paragraph style by pictures of the styles (the current one, back to this panel). */
    private val chooseStyle: ((current: String, onBack: () -> Unit) -> Unit)? = null,
    /** Presentations: the slide's settings. */
    private val slide: SlideSettings? = null,
    /** Documents and presentations: the table's style (pictures of the styles, back to this panel); null without tables. */
    private val chooseTableStyle: ((onBack: () -> Unit) -> Unit)? = null,
    /** All kinds of fills (see [FillPanel]) of slide backgrounds; null without them. */
    private val chooseFill: ((target: FillTarget, onBack: () -> Unit) -> Unit)? = null,
    /** List presets by their pictures; null without them. */
    private val chooseList: ((kind: ListKind, onBack: () -> Unit) -> Unit)? = null,
    /** Text documents: numbering restarted or continued, the advanced paragraph settings. */
    private val withNumberingRestart: Boolean = false,
    /** All caps and small caps (documents and presentations). */
    private val withCaps: Boolean = false,
    private val withAdvanced: Boolean = false,
    /** Text documents: the paragraphs' background color. */
    private val withShading: Boolean = false,
    /** Documents and presentations: the text's language (the current one, back to this panel); null without it. */
    private val chooseLanguage: ((current: Int, onBack: () -> Unit) -> Unit)? = null
) : PanelBuilder<TextFormat>(context) {

    /**
     * The current slide's background color (null: another kind), its layout, design and
     * transition (back to this panel).
     */
    class SlideSettings(
        val loadBackground: (onLoaded: (String?) -> Unit) -> Unit,
        val chooseLayout: (onBack: () -> Unit) -> Unit,
        val chooseDesign: (onBack: () -> Unit) -> Unit,
        val chooseTransition: (onBack: () -> Unit) -> Unit
    )

    private var dialog: PanelHost.Panel? = null
    private var format = TextFormat()
    private var fonts: List<String> = emptyList()
    private var documentFonts: List<String> = emptyList()
    private val fontChooser = FontChooser(context, panels)

    // A picture, shape or chart is selected (not text in it): highlights and paragraph settings
    // don't apply to it (sdkjs would apply them to the paragraph it is anchored in)
    private var drawingSelected = false
    // The drawings the panel shows (null: none); without text, the panel has no text settings
    private var shown: ShapePanel.Selected? = null
    private val textOnly = mutableListOf<View>()
    // Text at the cursor or selected (not only a slide)
    private var hasText = true
    private val textSections = mutableListOf<View>()
    private var paragraphSection: View? = null
    private var slideSection: View? = null
    private var tableSection: View? = null
    private var inTable = false
    private var markBackground: (String?) -> Unit = {}
    private val colorRows = mutableListOf<ColorPicker.Row>()
    // The panel shows its sections (not settings opened in its place), the main one open
    private var showsSections = false
    private var shownMain = 0

    fun show() = open()

    // Again after the panels opened from it, and when a drawing is selected or no more
    private fun open() {
        loadFonts { loadedFonts, loadedDocumentFonts ->
            fonts = loadedFonts
            documentFonts = loadedDocumentFonts
        }
        val shapes = shapes
        if (drawingSelected && shapes != null) shapes.load(::build) else build(null)
    }

    private fun build(drawings: ShapePanel.Selected?) {
        shown = drawings
        clearViews()
        shownMain = mainSection()
        openSections(shownMain)
        val content = content(drawings)
        dialog = panels.show(context.getString(Resources.string.editors_format), content, modal = false) { dialog = null }
        showsSections = true
        update(format, drawingSelected, hasText, inTable)
        loadBackground()
        // The theme's colors with the current color scheme
        colorPicker.load { colors -> colorRows.forEach { it.setColors(colors) } }
    }

    private fun clearViews() {
        updaters.clear()
        textOnly.clear()
        textSections.clear()
        colorRows.clear()
        paragraphSection = null
        slideSection = null
        tableSection = null
    }

    // What the panel is mostly for: the selected drawings, the table at the cursor, the slide
    // (nothing selected on it) or the text
    private fun mainSection(): Int = shown?.title ?: when {
        inTable && chooseTableStyle != null -> Resources.string.editors_doc_table_menu
        slide != null && !drawingSelected && !hasText -> Resources.string.editors_slide
        else -> Resources.string.editors_format_text
    }

    /**
     * The formatting after a command, [drawing] if a drawing is selected, [text] if there is
     * text at the cursor or selected, [table] if the cursor is in a table; updates the panel if
     * it is open.
     */
    fun update(newFormat: TextFormat, drawing: Boolean = false, text: Boolean = true, table: Boolean = false) {
        val drawingChanged = drawing != drawingSelected
        format = newFormat
        drawingSelected = drawing
        hasText = text
        inTable = table
        if (dialog == null) return
        // The drawings' section came or went, or another one is the main one: the panel anew
        if (drawingChanged && shapes != null || showsSections && shown == null && mainSection() != shownMain) {
            open()
            return
        }
        tableSection?.isVisible = table
        updaters.forEach { it(newFormat) }
        textOnly.forEach { it.isVisible = !drawing }
        textSections.forEach { it.isVisible = shown?.hasText ?: (drawing || text) }
        paragraphSection?.isVisible = !drawing && text
        slideSection?.isVisible = !drawing
        if (drawingChanged) loadBackground()
    }

    // The current slide's background
    private fun loadBackground() {
        if (!drawingSelected) slide?.loadBackground { markBackground(it) }
    }

    private fun content(drawings: ShapePanel.Selected?): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(8), dp(16), dp(24))

        // The selected pictures, shapes or charts
        val shapes = shapes
        if (drawings != null && shapes != null) section(drawings.title) { shapes.addTo(this, drawings, ::open, colorRows) }

        // The slide, in presentations when no picture or shape is selected
        if (slide != null) {
            slideSection = section(Resources.string.editors_slide) {
                caption(Resources.string.editors_slide_background)
                val background = colorRow(Resources.string.editors_slide_background, none = null) { color -> color?.let { edit { setSlideBackground(it) } } }
                if (chooseFill != null) action(Resources.string.editors_fill_more) { chooseFill.invoke(FillTarget.SLIDE, ::open) }
                action(Resources.string.editors_slide_design) { slide.chooseDesign(::open) }
                action(Resources.string.editors_slides_layout) { slide.chooseLayout(::open) }
                action(Resources.string.editors_slides_transition) { slide.chooseTransition(::open) }
                markBackground = background::mark
            }
        }

        // The table at the cursor: what one opens the panel for there, so first
        if (chooseTableStyle != null) tableSection = section(Resources.string.editors_doc_table_menu) { tableSettings(chooseTableStyle) }

        textSections += section(Resources.string.editors_format_text) { textSettings() }
        if (withParagraphs) paragraphSection = section(Resources.string.editors_format_paragraph) { paragraphSettings() }
    }

    private fun LinearLayout.tableSettings(chooseStyle: (onBack: () -> Unit) -> Unit) {
        fun table(name: String) = edit { tableAction(name) }
        scrollRow {
            group {
                icon(Resources.drawable.ic_editor_table_row_above, Resources.string.editors_doc_row_above) { table("rowAbove") }
                icon(Resources.drawable.ic_editor_table_row_below, Resources.string.editors_doc_row_below) { table("rowBelow") }
                icon(Resources.drawable.ic_editor_table_column_left, Resources.string.editors_doc_column_left) { table("columnLeft") }
                icon(Resources.drawable.ic_editor_table_column_right, Resources.string.editors_doc_column_right) { table("columnRight") }
            }
            group {
                icon(Resources.drawable.ic_editor_table_delete_row, Resources.string.editors_doc_delete_row) { table("deleteRow") }
                icon(Resources.drawable.ic_editor_table_delete_column, Resources.string.editors_doc_delete_column) { table("deleteColumn") }
            }
        }
        action(Resources.string.editors_table_style) { chooseStyle(::open) }
        scrollRow {
            button(context.getString(Resources.string.editors_doc_merge_cells)) { table("merge") }
            button(context.getString(Resources.string.editors_doc_split_cell)) { table("split") }
            button(context.getString(Resources.string.editors_doc_delete_table)) { table("deleteTable") }
        }
    }

    private fun LinearLayout.textSettings() {
        row {
            selector(Resources.string.editors_format_font, { it.fontName.ifEmpty { "—" } }) { chooseFont() }
            // Not without a size (text of different sizes)
            button("−") { if (format.fontSize > 1) edit { setFontSize(format.fontSize - 1) } }.contentDescription = context.getString(Resources.string.editors_format_size_down)
            val size = TextView(context).apply { gravity = Gravity.CENTER; minWidth = dp(40) }.also { addView(it) }
            updaters += {
                size.text = if (it.fontSize > 0) trimNumber(it.fontSize) else "—"
                size.contentDescription = context.getString(Resources.string.editors_format_size) + " " + size.text
            }
            button("+") { if (format.fontSize > 0) edit { setFontSize(format.fontSize + 1) } }.contentDescription = context.getString(Resources.string.editors_format_size_up)
        }
        scrollRow {
            // As in the toolbar
            group {
                icon(Resources.drawable.ic_editor_bold, Resources.string.editors_bold, { it.bold }) { edit { setBold(!format.bold) } }
                icon(Resources.drawable.ic_editor_italic, Resources.string.editors_italic, { it.italic }) { edit { setItalic(!format.italic) } }
                icon(Resources.drawable.ic_editor_underline, Resources.string.editors_underline, { it.underline }) { edit { setUnderline(!format.underline) } }
            }
            group {
                icon(Resources.drawable.ic_editor_strikeout, Resources.string.editors_format_strikeout, { it.strikeout }) { edit { setStrikeout(!format.strikeout) } }
                icon(Resources.drawable.ic_editor_subscript, Resources.string.editors_format_subscript, { it.baseline == 1 }) {
                    edit { setBaseline(if (format.baseline == 1) 0 else 1) }
                }
                icon(Resources.drawable.ic_editor_superscript, Resources.string.editors_format_superscript, { it.baseline == 2 }) {
                    edit { setBaseline(if (format.baseline == 2) 0 else 2) }
                }
            }
            group {
                icon(Resources.drawable.ic_editor_clear_format, Resources.string.editors_format_clear) { edit { clearFormatting() } }
            }
        }
        // The paragraph's alignment, as in the toolbar (not of selected drawings)
        val alignments = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            scrollRow {
                group {
                    for ((alignment, icon, name) in ALIGNMENTS) icon(icon, name, { it.alignment == alignment }) { edit { setAlignment(alignment) } }
                }
            }
        }
        addView(alignments)
        textOnly += alignments
        if (withCaps) {
            scrollRow {
                choice(context.getString(Resources.string.editors_format_all_caps), { it.caps }) { edit { setCaps(!format.caps) } }
                choice(context.getString(Resources.string.editors_format_small_caps), { it.smallCaps }) { edit { setSmallCaps(!format.smallCaps) } }
            }
        }
        // Spell checking goes by it
        if (chooseLanguage != null) {
            action(Resources.string.editors_format_language, { it.languageTag.ifEmpty { null }?.let { tag -> EditorDictionaries.languageName(tag) } ?: "—" }) {
                chooseLanguage.invoke(format.language, ::open)
            }
        }
        caption(Resources.string.editors_format_color)
        val textColor = colorRow(Resources.string.editors_format_color, Resources.string.editors_color_automatic) { color -> edit { setTextColor(color) } }
        updaters += { textColor.mark(it.color) }
        if (withHighlights) {
            val highlights = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                caption(Resources.string.editors_format_highlight)
                colors(HIGHLIGHTS, { it.highlight }) { color -> edit { setHighlight(color) } }
            }
            addView(highlights)
            textOnly += highlights
        }
    }

    // The style; lists (each with its styles beside it), indents and the direction in one row;
    // the spacing in its own panel
    private fun LinearLayout.paragraphSettings() {
        if (chooseStyle != null) {
            row {
                selector(Resources.string.editors_format_style, { it.style.ifEmpty { "—" }.let { name -> EditorTranslations.styleName(context, name) } }) {
                    chooseStyle.invoke(format.style, ::open)
                }
            }
        }
        scrollRow {
            group {
                icon(Resources.drawable.ic_editor_bullets, Resources.string.editors_format_bullets, { it.list == 0 }) {
                    edit { setList(0, if (format.list == 0) -1 else 1) }
                }
                if (chooseList != null) listStyles(Resources.string.editors_list_bullet_styles) { chooseList.invoke(ListKind.BULLETS, ::open) }
            }
            group {
                icon(Resources.drawable.ic_editor_numbering, Resources.string.editors_format_numbers, { it.list == 1 }) {
                    edit { setList(1, if (format.list == 1) -1 else 1) }
                }
                if (chooseList != null) listStyles(Resources.string.editors_list_numbering_styles) { chooseList.invoke(ListKind.NUMBERS, ::open) }
            }
            group {
                icon(Resources.drawable.ic_editor_indent_less, Resources.string.editors_format_indent_less) { edit { indent(false) } }
                icon(Resources.drawable.ic_editor_indent_more, Resources.string.editors_format_indent_more) { edit { indent(true) } }
            }
            group {
                icon(Resources.drawable.ic_editor_text_ltr, Resources.string.editors_format_ltr, { !it.rtl }) { edit { setRtl(false) } }
                icon(Resources.drawable.ic_editor_text_rtl, Resources.string.editors_format_rtl, { it.rtl }) { edit { setRtl(true) } }
            }
        }
        if (withNumberingRestart) {
            // In a numbered list
            val numbering = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            numbering.scrollRow {
                button(context.getString(Resources.string.editors_list_restart)) { edit { restartNumbering() } }
                button(context.getString(Resources.string.editors_list_continue)) { edit { continueNumbering() } }
            }
            addView(numbering)
            updaters += { numbering.isVisible = it.list == 1 }
        }
        if (withShading) {
            caption(Resources.string.editors_format_paragraph_background)
            val shading = colorRow(Resources.string.editors_format_paragraph_background, Resources.string.editors_color_none) { color ->
                edit { setParagraphShading(color) }
            }
            updaters += { shading.mark(it.shading) }
        }
        action(Resources.string.editors_format_spacing, ::lineSpacingText) {
            openSettings(Resources.string.editors_format_spacing) { spacingSettings() }
        }
        if (withAdvanced) {
            action(Resources.string.editors_format_advanced) {
                openSettings(Resources.string.editors_format_advanced) { advancedSettings() }
            }
        }
    }

    // Text documents: the first line's indent and what keeps paragraphs together, as web-apps
    // offers them
    private fun LinearLayout.advancedSettings() {
        caption(Resources.string.editors_format_first_line)
        stepper(Resources.string.editors_format_first_line, { centimeters(it.firstLine) }, { stepFirstLine(up = false) }, { stepFirstLine(up = true) })
        for ((option, label) in PARAGRAPH_OPTIONS) {
            switchRow(label, { option in it.paragraphOptions }) { on -> edit { setParagraphOption(option, on) } }
        }
    }

    private fun centimeters(mm: Double) = context.getString(Resources.string.editors_format_cm_value, trimNumber(Math.round(mm * 10) / 100.0))

    // By a quarter centimeter, hanging (negative) too
    private fun stepFirstLine(up: Boolean) {
        val mm = step(format.firstLine, INDENT_STEP_MM, up).coerceIn(-MAX_INDENT_MM, MAX_INDENT_MM)
        edit { setFirstLineIndent(mm) }
    }

    // A narrow button beside a list's that opens its styles
    private fun LinearLayout.listStyles(name: Int, onClick: () -> Unit) {
        icon(Resources.drawable.ic_editor_dropdown, name, onClick = onClick).apply {
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(48))
        }
    }

    // Line spacing: a multiple of the line, or at least or exactly a height in points; the space
    // before and after paragraphs
    private fun LinearLayout.spacingSettings() {
        caption(Resources.string.editors_format_line_spacing)
        scrollRow {
            choice(context.getString(Resources.string.editors_format_line_multiple), { it.lineRule == LINE_MULTIPLE }) {
                if (format.lineRule != LINE_MULTIPLE) edit { setLineSpacing(LINE_MULTIPLE, 1.0) }
            }
            for (rule in listOf(LINE_AT_LEAST, LINE_EXACTLY)) {
                val label = if (rule == LINE_AT_LEAST) Resources.string.editors_format_line_at_least else Resources.string.editors_format_line_exactly
                choice(context.getString(label), { it.lineRule == rule }) {
                    if (format.lineRule != rule) edit { setLineSpacing(rule, DEFAULT_LINE_POINTS * MM_PER_POINT) }
                }
            }
        }
        stepper(Resources.string.editors_format_line_spacing, ::lineSpacingText, { stepLineSpacing(up = false) }, { stepLineSpacing(up = true) })
        caption(Resources.string.editors_format_space_before)
        stepper(Resources.string.editors_format_space_before, { pointsText(it.spacingBefore) }, { stepSpacing(before = true, up = false) }, { stepSpacing(before = true, up = true) })
        caption(Resources.string.editors_format_space_after)
        stepper(Resources.string.editors_format_space_after, { pointsText(it.spacingAfter) }, { stepSpacing(before = false, up = false) }, { stepSpacing(before = false, up = true) })
    }

    // Settings of the text in the panel's place ([build]), back to the panel; they show the
    // current formatting as the panel does
    private fun openSettings(title: Int, build: LinearLayout.() -> Unit) {
        clearViews()
        showsSections = false
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(24))
            build()
        }
        dialog = panels.show(context.getString(title), content, ::open) { dialog = null }
        updaters.forEach { it(format) }
    }

    private fun lineSpacingText(format: TextFormat) =
        if (format.lineRule == LINE_MULTIPLE) trimNumber(format.line) else pointsText(format.line)

    // To the next step: multiples by a quarter line, heights by a point
    private fun stepLineSpacing(up: Boolean) {
        if (format.lineRule == LINE_MULTIPLE) {
            val value = step(format.line, LINE_STEP, up).coerceIn(LINE_STEP, MAX_LINE_MULTIPLE)
            edit { setLineSpacing(LINE_MULTIPLE, value) }
        } else {
            val points = step(format.line / MM_PER_POINT, 1.0, up).coerceIn(1.0, MAX_POINTS)
            edit { setLineSpacing(format.lineRule, points * MM_PER_POINT) }
        }
    }

    // Space before or after paragraphs, by points (automatic, -1, counts as none)
    private fun stepSpacing(before: Boolean, up: Boolean) {
        val current = maxOf(0.0, if (before) format.spacingBefore else format.spacingAfter) / MM_PER_POINT
        val mm = step(current, 1.0, up).coerceIn(0.0, MAX_POINTS) * MM_PER_POINT
        val spacingBefore = if (before) mm else maxOf(0.0, format.spacingBefore)
        val spacingAfter = if (before) maxOf(0.0, format.spacingAfter) else mm
        edit { setParagraphSpacing(spacingBefore, spacingAfter) }
    }

    // The next multiple of size above or below value. sdkjs keeps heights in its own units, so
    // a value comes back a little off (12 points as 11.95): up to a tenth of a step counts as on it.
    private fun step(value: Double, size: Double, up: Boolean): Double =
        if (up) (Math.floor(value / size + STEP_TOLERANCE) + 1) * size else (Math.ceil(value / size - STEP_TOLERANCE) - 1) * size

    private fun pointsText(mm: Double): String {
        val points = Math.round(maxOf(0.0, mm) / MM_PER_POINT * 2) / 2.0
        return context.getString(Resources.string.editors_format_points_value, trimNumber(points))
    }

    // In the panel's place, then back to it
    private fun chooseFont() = fontChooser.choose(fonts, documentFonts, format.fontName, ::open) { name -> edit { setFontName(name) } }

    // ---------------------------------------------------------------------------------
    // Views (the others in PanelBuilder)

    // A row of colors, all of them in the panel's place (see ColorPicker)
    private fun LinearLayout.colorRow(title: Int, none: Int?, onColor: (String?) -> Unit): ColorPicker.Row =
        colorPicker.Row(title, none, ::open, onColor).also {
            addView(it.view)
            colorRows += it
        }

    private fun trimNumber(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

    private companion object {
        const val MM_PER_POINT = 25.4 / 72
        // Word's highlight colors, as web-apps offers them; "" is none
        val HIGHLIGHTS = listOf(
            "", "FFFF00", "00FF00", "00FFFF", "FF00FF", "0000FF", "FF0000", "000080", "008080",
            "008000", "800080", "800000", "808000", "808080", "C0C0C0", "000000"
        )
        // EditorDocument.setLineSpacing's rules
        const val LINE_AT_LEAST = 0
        const val LINE_MULTIPLE = 1
        const val LINE_EXACTLY = 2
        const val LINE_STEP = 0.25
        const val MAX_LINE_MULTIPLE = 10.0
        const val DEFAULT_LINE_POINTS = 12.0
        const val MAX_POINTS = 1584.0
        const val STEP_TOLERANCE = 0.1
        val ALIGNMENTS = listOf(
            Triple(Alignment.LEFT, Resources.drawable.ic_editor_align_left, Resources.string.editors_align_left),
            Triple(Alignment.CENTER, Resources.drawable.ic_editor_align_center, Resources.string.editors_align_center),
            Triple(Alignment.RIGHT, Resources.drawable.ic_editor_align_right, Resources.string.editors_align_right)
        )
        const val INDENT_STEP_MM = 2.5
        const val MAX_INDENT_MM = 100.0
        val PARAGRAPH_OPTIONS = listOf(
            ParagraphOption.CONTEXTUAL_SPACING to Resources.string.editors_format_contextual_spacing,
            ParagraphOption.PAGE_BREAK_BEFORE to Resources.string.editors_format_page_break_before,
            ParagraphOption.WIDOW_CONTROL to Resources.string.editors_format_orphan_control,
            ParagraphOption.KEEP_LINES to Resources.string.editors_format_keep_lines,
            ParagraphOption.KEEP_NEXT to Resources.string.editors_format_keep_next,
            ParagraphOption.SUPPRESS_LINE_NUMBERS to Resources.string.editors_format_suppress_line_numbers
        )
    }
}
