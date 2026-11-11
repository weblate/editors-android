package lib.editors.base.engine

import org.json.JSONObject

/** Cursor line on a page, in millimeters (slanted for rotated text). */
data class Cursor(val page: Int, val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/** Selected area on a page: four corners (x1, y1 ... x4, y4) in millimeters. */
class SelectionArea(val page: Int, val points: DoubleArray)

/**
 * A selected picture or shape: its frame (four corners, clockwise from the top left, in page
 * millimeters), where its rotation handle is (null if it can't rotate) and whether it can be
 * moved and resized. While it is dragged ([tracking]), the frame is where it would go.
 */
class DrawingFrame(
    val page: Int,
    val points: DoubleArray,
    val rotateHandle: DoubleArray?,
    val canMove: Boolean,
    val canResize: Boolean,
    val tracking: Boolean
)

/** Column and row boundaries of a sheet in millimeters from its top left corner (n + 1 for n columns). */
class SheetGeometry(val cols: DoubleArray, val rows: DoubleArray) {
    val colCount get() = cols.size - 1
    val rowCount get() = rows.size - 1
    val widthMm get() = cols.last()
    val heightMm get() = rows.last()

    fun columnAt(xMm: Double): Int = indexAt(cols, xMm)

    fun rowAt(yMm: Double): Int = indexAt(rows, yMm)

    // The column or row whose end is after the position
    private fun indexAt(bounds: DoubleArray, position: Double): Int {
        if (bounds.size < 2) return 0
        var low = 0
        var high = bounds.size - 2
        while (low < high) {
            val middle = (low + high) / 2
            if (bounds[middle + 1] <= position) low = middle + 1 else high = middle
        }
        return low
    }
}

class Sheet(val name: String, val hidden: Boolean)

/** Format of the active cell. Colors as "RRGGBB"; [verticalAlign] 0 bottom, 1 center, 4 top. */
data class CellFormat(
    val numFormat: String = "General",
    /** Asc.c_oAscNumFormatType: 0 general, 1 number, 4 currency, 5 date, 8 percent, 10 text, ... */
    val numFormatType: Int = 0,
    /** The cell style's name ("Normal"). */
    val style: String = "",
    val fontName: String = "",
    val fontSize: Double = 0.0,
    val strikeout: Boolean = false,
    val color: String = "",
    val fill: String = "",
    val wrap: Boolean = false,
    val merged: Boolean = false,
    val verticalAlign: Int = 0,
    val border: Boolean = false,
    /** The text's angle (see [EditorDocument.setTextAngle]). */
    val angle: Int = 0,
    /** Right to left. */
    val rtl: Boolean = false,
    /** As the toolbar has them ([ToolbarState]; not in the engine's cell format). */
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val alignment: Alignment? = null
)

/**
 * Spreadsheets: the selection (columns c1..c2, rows r1..r2), the active cell, its content for
 * editing ([text]: value or formula) and the sheets. [geometry] is set when it changed.
 */
class SheetState(
    val c1: Int, val r1: Int, val c2: Int, val r2: Int,
    val col: Int, val row: Int,
    /** The active cell's name, like "B2". */
    val name: String,
    val text: String,
    val sheets: List<Sheet>,
    /** Frames of all pictures, shapes and charts on the sheet (taps on them go to [EditorDocument.touchDrawing]). */
    val objects: List<DoubleArray>,
    /** Index of the active sheet. */
    val active: Int,
    val geometry: SheetGeometry?,
    /** Frozen panes: columns before [frozenCol] and rows before [frozenRow] stay in place (0: none). */
    val frozenCol: Int = 0,
    val frozenRow: Int = 0,
    /** sdkjs refused the last command with this error code (e.g. shifting cells of a table), or null. */
    val warning: Int? = null,
    val cellFormat: CellFormat = CellFormat()
) {
    val isSingleCell get() = c1 == c2 && r1 == r2
}

/** Paragraph alignment, with sdkjs' values. */
enum class Alignment(val value: Int) {
    RIGHT(0), LEFT(1), CENTER(2), JUSTIFY(3);

    companion object {
        fun of(value: Int?): Alignment? = entries.firstOrNull { it.value == value }
    }
}

/**
 * Formatting at the cursor or of the selection, for the formatting panel. Colors as "RRGGBB"
 * ("" for automatic or none); spacing in mm; [list] -1 none, 0 bullets, 1 numbers (documents);
 * [style] the paragraph style (documents).
 */
data class TextFormat(
    val fontName: String = "",
    val fontSize: Double = 0.0,
    val strikeout: Boolean = false,
    /** 0 baseline, 1 subscript, 2 superscript. */
    val baseline: Int = 0,
    val color: String = "",
    val highlight: String = "",
    /** 0 at least, 1 multiple, 2 exactly (see [EditorDocument.setLineSpacing]). */
    val lineRule: Int = 1,
    val line: Double = 1.0,
    val spacingBefore: Double = 0.0,
    val spacingAfter: Double = 0.0,
    val list: Int = -1,
    val style: String = "",
    /** The paragraph is right to left. */
    val rtl: Boolean = false,
    /** Text documents: the advanced settings that are on (see [EditorDocument.setParagraphOption]). */
    val paragraphOptions: Set<ParagraphOption> = emptySet(),
    /** Text documents: the first line's indent in mm (negative: hanging). */
    val firstLine: Double = 0.0,
    /** Text documents: all caps, small caps. */
    val caps: Boolean = false,
    val smallCaps: Boolean = false,
    /** Text documents: the paragraph's background color ("RRGGBB", "" for none). */
    val shading: String = "",
    /** The text's language (LCID, 0 for several) and its tag ("de-DE", "" if unknown). */
    val language: Int = 0,
    val languageTag: String = "",
    /** As the toolbar has them ([ToolbarState]; not in the engine's format). */
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val alignment: Alignment? = null
)

/** Text documents' advanced paragraph settings, as web-apps offers them; [key] is the state's and the engine's. */
enum class ParagraphOption(val key: String, val command: String) {
    /** No space between paragraphs of the same style. */
    CONTEXTUAL_SPACING("ContextualSpacing", "contextualSpacing"),
    PAGE_BREAK_BEFORE("PageBreakBefore", "pageBreakBefore"),
    /** Orphan control. */
    WIDOW_CONTROL("WidowControl", "widowControl"),
    KEEP_LINES("KeepLines", "keepLines"),
    KEEP_NEXT("KeepNext", "keepNext"),
    SUPPRESS_LINE_NUMBERS("SuppressLineNumbers", "suppressLineNumbers")
}

/** Find and replace: the number of matches, the current one (0-based, -1 for none) and, after replace all, how many were replaced (-1 otherwise). */
data class SearchState(val count: Int, val current: Int, val replaced: Int)

/** The hyperlink at the cursor or in the active cell: a web address ([url]) or a place in the document ([anchor]). */
data class Link(val url: String, val anchor: String)

/** A tracked change: [type] is Asc.c_oAscRevisionsChangeType (1 text added, 2 text removed, 3/4 paragraph added/removed, 5+ formatting). */
data class TrackedChange(val type: Int, val user: String, val date: String, val text: String)

/** Documents: where the cursor is (table, header or footer, footnote) and track changes. */
data class DocumentStructure(
    val inTable: Boolean = false,
    val inHeaderFooter: Boolean = false,
    val differentFirst: Boolean = false,
    val differentOddEven: Boolean = false,
    val inNote: Boolean = false,
    val trackChanges: Boolean = false,
    /** The change at the cursor, after going to the next or previous one. */
    val change: TrackedChange? = null
)

/** Page size, orientation and margins of the current section, in mm, and its columns. */
/** Text documents: footnotes' or endnotes' settings, as web-apps' note settings have them. */
data class NoteSettings(
    val position: NotePosition,
    val format: NoteFormat,
    /** The first note's number. */
    val start: Int,
    val restart: NoteRestart
)

/** Where notes go: footnotes at the page's bottom or below the text, endnotes at a section's or the document's end. */
enum class NotePosition(val key: String) {
    PAGE_BOTTOM("pageBottom"), BENEATH_TEXT("beneathText"), SECTION_END("sectEnd"), DOCUMENT_END("docEnd");

    companion object {
        val FOOTNOTES = listOf(PAGE_BOTTOM, BENEATH_TEXT)
        val ENDNOTES = listOf(SECTION_END, DOCUMENT_END)
    }
}

/** Notes' numbers: 1, 2, 3; a, b, c; A, B, C; i, ii, iii; I, II, III. */
enum class NoteFormat(val key: String) {
    DECIMAL("decimal"), LOWER_LETTER("lowerLetter"), UPPER_LETTER("upperLetter"), LOWER_ROMAN("lowerRoman"), UPPER_ROMAN("upperRoman");

    /** The [number]th (from 1) as this format writes it. */
    fun format(number: Int): String = when (this) {
        DECIMAL -> number.toString()
        LOWER_LETTER -> letters(number)
        UPPER_LETTER -> letters(number).uppercase()
        LOWER_ROMAN -> roman(number).lowercase()
        UPPER_ROMAN -> roman(number)
    }

    private companion object {
        // a ... z, then aa ... zz, as Word counts
        fun letters(number: Int): String {
            if (number < 1) return number.toString()
            val letter = 'a' + (number - 1) % 26
            return letter.toString().repeat((number - 1) / 26 + 1)
        }

        fun roman(number: Int): String {
            if (number < 1) return number.toString()
            var rest = number
            val result = StringBuilder()
            for ((value, digits) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
                50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
                while (rest >= value) {
                    result.append(digits)
                    rest -= value
                }
            }
            return result.toString()
        }
    }
}

/** When notes' numbers start again: never, in each section, on each page (footnotes). */
enum class NoteRestart(val key: String) { CONTINUOUS("continuous"), EACH_SECTION("eachSect"), EACH_PAGE("eachPage") }

/** Footnotes to endnotes, endnotes to footnotes, or both swapped. */
enum class NoteConversion(val key: String) { TO_ENDNOTES("toEndnotes"), TO_FOOTNOTES("toFootnotes"), SWAP("swap") }

data class PageSetup(
    val width: Double, val height: Double, val landscape: Boolean,
    val left: Double, val top: Double, val right: Double, val bottom: Double,
    val columns: Int
)

/** A heading of the document outline, at [level] (0 for the top). */
data class OutlineItem(val index: Int, val text: String, val level: Int)

/** A color scheme: its name and colors ("RRGGBB": dark 1, light 1, dark 2, light 2, accents 1 to 6, links). */
data class ColorScheme(val name: String, val colors: List<String>)

/** The color schemes to choose from and the current one's index, -1 for none of them. */
data class ColorSchemes(val schemes: List<ColorScheme>, val current: Int)

/**
 * The colors of the palettes, "RRGGBB". [theme] has 6 for each of the theme's 10 colors
 * (background 1, text 1, background 2, text 2, accents 1 to 6): the color, then lighter and
 * darker ones; empty without a theme (PDFs). The commands take the Nth as [themeColor] N, which
 * follows the document's color scheme, and others as "RRGGBB".
 */
data class ThemeColors(val theme: List<String>, val standard: List<String>) {

    /** The "RRGGBB" of a color of the commands; null for none or a theme color not in [theme]. */
    fun hexOf(color: String?): String? {
        if (color.isNullOrEmpty()) return null
        if (!color.startsWith(THEME_PREFIX)) return color
        return color.removePrefix(THEME_PREFIX).toIntOrNull()?.let { theme.getOrNull(it) }
    }

    companion object {
        const val THEME_COLUMNS = 10
        const val THEME_SHADES = 6
        private const val THEME_PREFIX = "theme:"

        /** The theme's color [column] (see [theme]) in [shade] 0 to 5, as the commands take it. */
        fun themeColor(column: Int, shade: Int = 0) = "$THEME_PREFIX${column * THEME_SHADES + shade}"

        fun parse(json: String): ThemeColors {
            val root = JSONObject(json)
            fun list(name: String) = root.optJSONArray(name)?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
            return ThemeColors(list("theme"), list("standard"))
        }
    }
}

/** Spreadsheets: the active sheet's [gridlines], its pages' orientation and size (mm). */
data class SheetSetup(val gridlines: Boolean, val landscape: Boolean, val width: Double, val height: Double)

data class Statistics(val pages: Int, val words: Int, val paragraphs: Int, val symbols: Int, val symbolsWithSpaces: Int)

/**
 * How a slide appears in a slideshow: [type] is Asc.c_oAscSlideTransitionTypes (0 none, 1 fade,
 * 2 push, 3 wipe, 4 split, 5 uncover, 6 cover, 7 clock, 8 zoom, 9 morph), [option] its
 * Asc.c_oAscSlideTransitionParams (direction and so on); the next slide comes on a tap
 * ([onClick]) and/or after [afterMs] ([advanceAfter]).
 */
data class SlideTransition(
    val type: Int, val option: Int, val durationMs: Int,
    val onClick: Boolean, val advanceAfter: Boolean, val afterMs: Int
)

data class SlideInfo(val hidden: Boolean, val layout: String, val transition: SlideTransition?, val notes: String)

/** A slide layout; [index] is for [EditorDocument.addSlide] and [EditorDocument.setLayout]. */
data class SlideLayout(val index: Int, val name: String)

/**
 * The selected chart: [type] is Asc.c_oAscChartTypeSettings, [legend] Asc.c_oAscChartLegendShowSettings
 * (0 none, 1 left, 2 top, 3 right, 4 bottom). The titles' texts: [titleText], and with [axes]
 * [horizontalTitle] and [verticalTitle]. In spreadsheets the data is in cells ([dataInCells]), [range]
 * ("Sheet1!$A$1:$C$5").
 */
data class ChartState(
    val type: Int, val title: Boolean, val legend: Int, val labels: Boolean, val gridlines: Boolean, val dataInCells: Boolean,
    val titleText: String = "", val axes: Boolean = false, val horizontalTitle: String = "", val verticalTitle: String = "",
    val range: String = "",
    /** Its style, 1 to [styles] (the type's count), null if none of them. */
    val style: Int? = null, val styles: Int = 0,
    val layout: ChartLayout? = null,
    /** The value and the category axis; null without (pies). */
    val valueAxis: ChartAxis? = null, val categoryAxis: ChartAxis? = null
)

/**
 * A chart's layout, with sdkjs' values: [title] Asc.c_oAscChartTitleShowSettings, [legend]
 * c_oAscChartLegendShowSettings, [horizontalTitle] c_oAscChartHorAxisLabelShowSettings,
 * [verticalTitle] c_oAscChartVertAxisLabelShowSettings, gridlines c_oAscGridLinesSettings,
 * [dataLabels] c_oAscChartDataLabelsPos; null without the axis.
 */
data class ChartLayout(
    val title: Int, val legend: Int, val horizontalTitle: Int?, val verticalTitle: Int?,
    val horizontalGridlines: Int?, val verticalGridlines: Int?, val dataLabels: Int
)

/**
 * A chart axis, with sdkjs' values: [min] and [max] null for automatic, [units]
 * Asc.c_oAscValAxUnits, [crosses] c_oAscCrossesRule (at [crossesValue]), ticks c_oAscTickMark,
 * [labels] c_oAscTickLabelsPos. Value axes have [min], [max] and [units], category axes
 * [position] (c_oAscLabelsPosition).
 */
data class ChartAxis(
    val min: Double?, val max: Double?, val units: Int?, val crosses: Int, val crossesValue: Double?,
    val majorTicks: Int, val minorTicks: Int, val labels: Int, val reverse: Boolean, val position: Int?
)

/** A picture of a choice ([EditorDocument.previews]): [id] applies it; its size in mm. */
data class Preview(val id: String, val name: String, val widthMm: Double, val heightMm: Double)

/** The fill and outline of a selected drawing as "RRGGBB": "" for none, null for other fills (pictures, gradients). */
data class DrawingColors(
    val fill: String?,
    val outline: String?,
    /** The direction of a shape's text; null for drawings without text (pictures, charts). */
    val textDirection: ShapeTextDirection? = null,
    /** The outline's width in mm (null: not given) and dash. */
    val outlineWidthMm: Double? = null,
    val outlineDash: OutlineDash = OutlineDash.SOLID,
    /** The fill's opacity in percent. */
    val opacity: Int = 100,
    /** The outer shadow; null for drawings without shadows (charts). */
    val shadow: ShapeShadow? = null,
    /** Charts: the part fills and outlines go to ([EditorDocument.setChartPart]); null for other drawings. */
    val chartPart: ChartPart? = null
)

/** The part of a chart its fill, outline and opacity go to. */
enum class ChartPart(val value: String) { CHART("chart"), PLOT_AREA("plotArea") }

/**
 * Outer shadows as web-apps offers them, by where they fall; [value] is sdkjs' preset (the
 * shadow's alignment, so the opposite corner), [CUSTOM] one of none of them.
 */
enum class ShapeShadow(val value: String) {
    NONE(""), TOP_LEFT("br"), TOP("b"), TOP_RIGHT("bl"), LEFT("r"), CENTER("ctr"), RIGHT("l"),
    BOTTOM_LEFT("tr"), BOTTOM("t"), BOTTOM_RIGHT("tl"), CUSTOM("custom")
}

/** Outlines' dashes as web-apps offers them; [value] is Asc.c_oDashType. */
enum class OutlineDash(val value: Int) {
    SOLID(6), SYS_DOT(10), SYS_DASH(7), DASH(0), DASH_DOT(1), LONG_DASH(3), LONG_DASH_DOT(4), LONG_DASH_DOT_DOT(5)
}

/** A chart's data: a value per category ([categories]) for each series ([series]); null for none. */
data class ChartData(val series: List<String>, val categories: List<String>, val values: List<List<Double?>>)

data class CommentReply(val text: String, val user: String, val time: Long)

/**
 * A comment with its replies. Where it is: [page] and [x], [y] (mm) in documents and
 * presentations (page -1: on the whole presentation), [sheet], [col], [row] in spreadsheets.
 * [quote] is the commented text (the cell's name in spreadsheets).
 */
data class Comment(
    val id: String, val text: String, val user: String, val time: Long, val quote: String, val solved: Boolean,
    val replies: List<CommentReply>,
    val page: Int = -1, val x: Double = 0.0, val y: Double = 0.0,
    val sheet: Int = -1, val col: Int = -1, val row: Int = -1
)

data class FormListItem(val text: String, val value: String)

/**
 * A form field: [type] text, complex, checkBox, radio, comboBox, dropDownList, picture,
 * signature or date; [text] its content ("" while it shows its [placeholder]); where it is on
 * [page] (mm).
 */
data class FormField(
    val id: String, val key: String, val type: String, val placeholder: String, val required: Boolean,
    val empty: Boolean, val text: String, val role: String, val help: String,
    val checked: Boolean = false, val items: List<FormListItem> = emptyList(), val dateFormat: String = "",
    val page: Int = -1, val x: Double = 0.0, val y: Double = 0.0, val width: Double = 0.0, val height: Double = 0.0
) {
    companion object {
        internal fun parse(json: JSONObject): FormField {
            val items = json.optJSONArray("items") ?: org.json.JSONArray()
            return FormField(
                json.getString("id"), json.optString("key"), json.optString("type"), json.optString("placeholder"),
                json.optBoolean("required"), json.optBoolean("empty"), json.optString("text"), json.optString("role"),
                json.optString("help"), json.optBoolean("checked"),
                (0 until items.length()).map { FormListItem(items.getJSONObject(it).optString("text"), items.getJSONObject(it).optString("value")) },
                json.optString("dateFormat"), json.optInt("page", -1),
                json.optDouble("x", 0.0), json.optDouble("y", 0.0), json.optDouble("width", 0.0), json.optDouble("height", 0.0)
            )
        }
    }
}

/** Documents with form fields: the one at the cursor, whether only fields take input and whether the required ones are filled. */
data class FormsState(val current: FormField?, val filling: Boolean, val allRequiredFilled: Boolean)

/**
 * Spell checking: [pending] requests for [EditorDocument.checkSpelling], languages (LCIDs) without
 * a dictionary, and the misspelled word at the cursor.
 */
data class SpellingState(val pending: Boolean, val missing: List<Int>, val word: Misspelling?)

/** A misspelled word; [variants] is null while suggestions are looked up. */
data class Misspelling(val word: String, val variants: List<String>?)

/** A role of a form with roles (oform): who fills which fields. [color] as "RRGGBB". */
data class FormRole(val name: String, val color: String, val fieldCount: Int)

/**
 * Co-editing: the connection (sdkjs blocks editing while it [reconnecting]; after it is
 * [disconnected] the document is only viewed), who is in the session and the others' cursors
 * (spreadsheets: [selections]).
 */
data class CoEditingState(
    val reconnecting: Boolean,
    val disconnected: Boolean,
    val participants: List<Participant>,
    val cursors: List<ForeignCursor>,
    val selections: List<ForeignSelection> = emptyList()
) {
    /** The others editing the document (without oneself and viewers). */
    val coEditors: List<Participant> get() = participants.filter { !it.me && !it.view }
}

/** Someone in a co-editing session; [color] as "#RRGGBB". Each window of a person is a participant. */
data class Participant(val id: String, val name: String, val color: String, val view: Boolean, val me: Boolean)

/** Someone else's cursor, a line on a page (millimeters). */
data class ForeignCursor(
    val user: String, val name: String, val color: String,
    val page: Int, val x1: Double, val y1: Double, val x2: Double, val y2: Double
)

/** Spreadsheets: the cells someone else selected on the active sheet, [editing] one of them. */
data class ForeignSelection(val user: String, val name: String, val color: String, val editing: Boolean, val ranges: List<CellRange>)

/** Cells from column [c1], row [r1] to column [c2], row [r2] (indexes). */
data class CellRange(val c1: Int, val r1: Int, val c2: Int, val r2: Int)

/** Marking up text of a PDF. */
enum class PdfMarkupKind(val value: String) { HIGHLIGHT("highlight"), UNDERLINE("underline"), STRIKEOUT("strikeout") }

/**
 * The PDF editor's state: freehand drawing is on ([ink]); the selected annotation ([annotation]
 * its sdkjs type, null for none); text of the page is selected ([textSelected], to mark it up);
 * a text box or text in it is ([textBox], formatted as in presentations); the text of the current
 * page can be made editable ([canEditPage], [EditorDocument.editPage]); pictures, shapes or text
 * boxes are selected ([drawing]: filled, outlined and arranged as in the other editors).
 */
data class PdfState(
    val ink: Boolean,
    val annotation: Int?,
    val textSelected: Boolean,
    val textBox: Boolean = false,
    val canEditPage: Boolean = false,
    val drawing: Boolean = false
)

/** A place in the document to show: a link or an entry of the contents was followed. */
data class Place(val page: Int, val yMm: Double)

data class ToolbarState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val alignment: Alignment? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

/**
 * The editor's view state after a command: where to draw the cursor and selection,
 * which pages to draw again and the toolbar state.
 */
class EditorState(
    val cursor: Cursor?,
    val selection: List<SelectionArea>,
    /** Selected pictures and shapes. */
    val drawings: List<DrawingFrame>,
    val dirtyPages: Set<Int>,
    val layoutChanged: Boolean,
    /** Pages are still to be laid out: call [EditorDocument.tick] until this is false. */
    val layoutPending: Boolean,
    /** Presentations: the current slide (-1 for documents). */
    val currentPage: Int,
    /** Changed since opening or the last save. */
    val modified: Boolean,
    val toolbar: ToolbarState,
    /** The exception sdkjs threw in the command, if any (the state is still valid). */
    val error: String? = null,
    /** Spreadsheets: selection, active cell and sheets. */
    val sheet: SheetState? = null,
    val format: TextFormat = TextFormat(),
    /** The search, while find and replace is open. */
    val search: SearchState? = null,
    val link: Link? = null,
    /** A web address to open, after following a link (reported once). */
    val openUrl: String? = null,
    /** Documents only. */
    val structure: DocumentStructure? = null,
    val chart: ChartState? = null,
    /** Documents with form fields. */
    val forms: FormsState? = null,
    /** Documents and presentations. */
    val spelling: SpellingState? = null,
    /** While co-editing. */
    val coEditing: CoEditingState? = null,
    /** PDFs in sdkjs' PDF editor. */
    val pdf: PdfState? = null,
    /** A place to show, after following a link or an entry of the contents (reported once). */
    val navigate: Place? = null,
    /** The comments sdkjs shows: of the commented text tapped, of the selected PDF annotation. */
    val shownComments: List<String> = emptyList(),
    /** Documents: laid out for the screen ([EditorDocument.setMobileView]). */
    val mobileView: Boolean = false,
    /** PDFs: the frame of the text box whose text is edited (outlined, without handles). */
    val textFrame: SelectionArea? = null
) {
    companion object {
        /** Throws [org.json.JSONException] if [json] isn't a state (the engine failed). */
        fun parse(json: String): EditorState {
            val root = JSONObject(json)
            val cursor = root.optJSONObject("cursor")?.let {
                Cursor(it.getInt("page"), it.getDouble("x1"), it.getDouble("y1"), it.getDouble("x2"), it.getDouble("y2"))
            }
            fun areas(name: String): List<SelectionArea> {
                val json = root.getJSONArray(name)
                return (0 until json.length()).map { index ->
                    val area = json.getJSONObject(index)
                    val points = area.getJSONArray("points")
                    SelectionArea(area.getInt("page"), DoubleArray(points.length()) { points.getDouble(it) })
                }
            }
            val dirtyPages = root.getJSONObject("dirtyPages").keys().asSequence().map { it.toInt() }.toSet()
            val ui = root.getJSONObject("interface")
            val toolbar = ToolbarState(
                bold = ui.optBoolean("Bold"),
                italic = ui.optBoolean("Italic"),
                underline = ui.optBoolean("Underline"),
                alignment = if (ui.has("PrAlign") && !ui.isNull("PrAlign")) Alignment.of(ui.getInt("PrAlign")) else null,
                canUndo = ui.optBoolean("CanUndo"),
                canRedo = ui.optBoolean("CanRedo")
            )
            return EditorState(
                cursor, areas("selection"), drawings(root.getJSONArray("drawings")), dirtyPages, root.getBoolean("layoutChanged"),
                root.getBoolean("layoutPending"), root.getInt("currentPage"), root.getBoolean("modified"), toolbar,
                root.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                root.optJSONObject("sheet")?.let { parseSheet(it) },
                root.optJSONObject("format")?.let { parseFormat(it) } ?: TextFormat(),
                root.optJSONObject("search")?.let { SearchState(it.optInt("count"), it.optInt("current", -1), it.optInt("replaced", -1)) },
                root.optJSONObject("link")?.let { Link(it.optString("url"), it.optString("anchor")) },
                root.optString("openUrl").takeIf { it.isNotEmpty() && it != "null" },
                root.optJSONObject("structure")?.let { parseStructure(it) },
                forms = root.optJSONObject("forms")?.let {
                    FormsState(it.optJSONObject("current")?.let { field -> FormField.parse(field) }, it.optBoolean("filling"), it.optBoolean("allRequiredFilled"))
                },
                spelling = root.optJSONObject("spelling")?.let { json ->
                    val missing = json.optJSONArray("missing") ?: org.json.JSONArray()
                    SpellingState(
                        json.optBoolean("pending"),
                        (0 until missing.length()).map { missing.getInt(it) },
                        json.optJSONObject("word")?.let { word ->
                            val variants = word.optJSONArray("variants")
                            Misspelling(word.getString("word"), variants?.let { array -> (0 until array.length()).map { array.getString(it) } })
                        }
                    )
                },
                coEditing = root.optJSONObject("coEditing")?.let { parseCoEditing(it) },
                pdf = root.optJSONObject("pdf")?.let {
                    PdfState(it.optBoolean("ink"), it.optJSONObject("annotation")?.optInt("type"), it.optBoolean("textSelected"), it.optBoolean("textBox"),
                        it.optBoolean("canEditPage"), it.optBoolean("drawing"))
                },
                navigate = root.optJSONObject("navigate")?.let { Place(it.optInt("page"), it.optDouble("y")) },
                shownComments = root.optJSONArray("shownComments")?.let { ids -> (0 until ids.length()).map { ids.getString(it) } }.orEmpty(),
                mobileView = root.optBoolean("mobileView"),
                textFrame = root.optJSONObject("textFrame")?.let { area ->
                    val points = area.getJSONArray("points")
                    SelectionArea(area.getInt("page"), DoubleArray(points.length()) { points.getDouble(it) })
                },
                chart = root.optJSONObject("chart")?.let {
                    ChartState(
                        it.optInt("type"), it.optBoolean("title"), it.optInt("legend"), it.optBoolean("labels"),
                        it.optBoolean("gridlines"), it.optBoolean("dataInCells"),
                        it.optString("titleText"), it.optBoolean("axes"), it.optString("horizontalTitle"),
                        it.optString("verticalTitle"), it.optString("range"),
                        it.optIntOrNull("style"), it.optInt("styles"),
                        it.optJSONObject("layout")?.let { l ->
                            ChartLayout(
                                l.optInt("title"), l.optInt("legend"), l.optIntOrNull("horizontalTitle"), l.optIntOrNull("verticalTitle"),
                                l.optIntOrNull("horizontalGridlines"), l.optIntOrNull("verticalGridlines"), l.optInt("dataLabels")
                            )
                        },
                        it.optJSONObject("valueAxis")?.let(::parseAxis), it.optJSONObject("categoryAxis")?.let(::parseAxis)
                    )
                }
            )
        }

        private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)

        private fun JSONObject.optDoubleOrNull(name: String): Double? = if (isNull(name)) null else optDouble(name)

        private fun parseAxis(json: JSONObject) = ChartAxis(
            json.optDoubleOrNull("min"), json.optDoubleOrNull("max"), json.optIntOrNull("units"), json.optInt("crosses"),
            json.optDoubleOrNull("crossesValue"), json.optInt("majorTicks"), json.optInt("minorTicks"), json.optInt("labels"),
            json.optBoolean("reverse"), json.optIntOrNull("position")
        )

        internal fun parseCoEditing(json: JSONObject): CoEditingState {
            val participants = json.optJSONArray("participants") ?: org.json.JSONArray()
            val cursors = json.optJSONArray("cursors") ?: org.json.JSONArray()
            val selections = json.optJSONArray("selections") ?: org.json.JSONArray()
            return CoEditingState(
                json.optBoolean("reconnecting"),
                json.optBoolean("disconnected"),
                (0 until participants.length()).map { index ->
                    val user = participants.getJSONObject(index)
                    Participant(user.optString("id"), user.optString("name"), user.optString("color"), user.optBoolean("view"), user.optBoolean("me"))
                },
                (0 until cursors.length()).map { index ->
                    val cursor = cursors.getJSONObject(index)
                    ForeignCursor(
                        cursor.optString("user"), cursor.optString("name"), cursor.optString("color"), cursor.getInt("page"),
                        cursor.getDouble("x1"), cursor.getDouble("y1"), cursor.getDouble("x2"), cursor.getDouble("y2")
                    )
                },
                (0 until selections.length()).map { index ->
                    val selection = selections.getJSONObject(index)
                    val ranges = selection.optJSONArray("ranges") ?: org.json.JSONArray()
                    ForeignSelection(
                        selection.optString("user"), selection.optString("name"), selection.optString("color"), selection.optBoolean("editing"),
                        (0 until ranges.length()).map { i ->
                            val range = ranges.getJSONArray(i)
                            CellRange(range.getInt(0), range.getInt(1), range.getInt(2), range.getInt(3))
                        }
                    )
                }
            )
        }

        private fun parseStructure(json: JSONObject) = DocumentStructure(
            inTable = json.optBoolean("inTable"),
            inHeaderFooter = json.optBoolean("inHeaderFooter"),
            differentFirst = json.optBoolean("differentFirst"),
            differentOddEven = json.optBoolean("differentOddEven"),
            inNote = json.optBoolean("inNote"),
            trackChanges = json.optBoolean("trackChanges"),
            change = json.optJSONObject("change")?.let {
                TrackedChange(it.optInt("type"), it.optString("user"), it.optString("date"), it.optString("text"))
            }
        )

        private fun parseFormat(json: JSONObject) = TextFormat(
            fontName = json.optString("FontName"),
            fontSize = json.optDouble("FontSize", 0.0),
            strikeout = json.optBoolean("Strikeout"),
            baseline = json.optInt("VertAlign"),
            color = json.optString("Color"),
            highlight = json.optString("Highlight"),
            lineRule = json.optInt("LineRule", 1),
            line = json.optDouble("Line", 1.0),
            spacingBefore = json.optDouble("SpacingBefore", 0.0),
            spacingAfter = json.optDouble("SpacingAfter", 0.0),
            list = json.optInt("List", -1),
            style = json.optString("Style"),
            rtl = json.optBoolean("Rtl"),
            paragraphOptions = ParagraphOption.entries.filter { json.optBoolean(it.key) }.toSet(),
            firstLine = json.optDouble("FirstLine", 0.0),
            caps = json.optBoolean("Caps"),
            smallCaps = json.optBoolean("SmallCaps"),
            shading = json.optString("Shading"),
            language = json.optInt("Lang"),
            languageTag = json.optString("LangTag")
        )



        private fun doubles(array: org.json.JSONArray): DoubleArray = DoubleArray(array.length()) { array.getDouble(it) }

        private fun drawings(json: org.json.JSONArray): List<DrawingFrame> = (0 until json.length()).map { index ->
            val drawing = json.getJSONObject(index)
            DrawingFrame(
                drawing.getInt("page"),
                doubles(drawing.getJSONArray("points")),
                drawing.optJSONArray("rotate")?.let { doubles(it) },
                drawing.optBoolean("canMove", true),
                drawing.optBoolean("canResize", true),
                drawing.optBoolean("tracking")
            )
        }

        private fun parseSheet(json: JSONObject): SheetState {
            val sheetsJson = json.getJSONArray("sheets")
            val sheets = (0 until sheetsJson.length()).map {
                val sheet = sheetsJson.getJSONObject(it)
                Sheet(sheet.getString("name"), sheet.getBoolean("hidden"))
            }
            val geometry = json.optJSONObject("geometry")?.let { geometryJson ->
                fun bounds(name: String): DoubleArray {
                    val array = geometryJson.getJSONArray(name)
                    return DoubleArray(array.length()) { array.getDouble(it) }
                }
                SheetGeometry(bounds("cols"), bounds("rows"))
            }
            val objectsJson = json.optJSONArray("objects") ?: org.json.JSONArray()
            val objects = (0 until objectsJson.length()).map { doubles(objectsJson.getJSONArray(it)) }
            return SheetState(
                json.getInt("c1"), json.getInt("r1"), json.getInt("c2"), json.getInt("r2"),
                json.getInt("col"), json.getInt("row"), json.getString("name"), json.getString("text"),
                sheets, objects, json.getInt("active"), geometry,
                json.optInt("frozenCol"), json.optInt("frozenRow"),
                if (json.isNull("warning") || !json.has("warning")) null else json.getInt("warning"),
                json.optJSONObject("cellFormat")?.let {
                    CellFormat(
                        it.optString("numFormat", "General"), it.optInt("numFormatType"), it.optString("style"), it.optString("fontName"),
                        it.optDouble("fontSize", 0.0), it.optBoolean("strikeout"), it.optString("color"), it.optString("fill"),
                        it.optBoolean("wrap"), it.optBoolean("merged"), it.optInt("verticalAlign"), it.optBoolean("border"),
                        it.optInt("angle"), it.optBoolean("rtl")
                    )
                } ?: CellFormat()
            )
        }
    }
}
