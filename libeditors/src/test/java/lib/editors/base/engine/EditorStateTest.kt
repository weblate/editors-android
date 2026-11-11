package lib.editors.base.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTest {

    // The fields every state of the engine has (engine.js getState)
    private fun state(extra: String = "") = """
        {"cursor": {"page": 1, "x1": 10, "y1": 20, "x2": 10, "y2": 25},
         "selection": [{"page": 0, "points": [0, 0, 5, 0, 5, 5, 0, 5]}],
         "drawings": [], "dirtyPages": {"0": true, "2": true}, "layoutChanged": false,
         "layoutPending": true, "currentPage": -1, "modified": true,
         "interface": {"Bold": true, "PrAlign": 2, "CanUndo": true}, "error": null$extra}
    """.trimIndent()

    @Test
    fun `parses cursor, selection, pages and toolbar`() {
        val parsed = EditorState.parse(state())
        assertEquals(Cursor(1, 10.0, 20.0, 10.0, 25.0), parsed.cursor)
        assertEquals(1, parsed.selection.size)
        assertEquals(setOf(0, 2), parsed.dirtyPages)
        assertTrue(parsed.layoutPending)
        assertTrue(parsed.modified)
        assertTrue(parsed.toolbar.bold)
        assertFalse(parsed.toolbar.italic)
        assertTrue(parsed.toolbar.canUndo)
        assertNull(parsed.error)
        assertNull(parsed.sheet)
    }

    @Test
    fun `parses spelling`() {
        val parsed = EditorState.parse(state(""", "spelling": {"pending": true, "missing": [1031, 1036], "word": {"word": "wrng", "variants": ["wrong"]}}"""))
        val spelling = parsed.spelling!!
        assertTrue(spelling.pending)
        assertEquals(listOf(1031, 1036), spelling.missing)
        assertEquals(Misspelling("wrng", listOf("wrong")), spelling.word)
    }

    @Test
    fun `variants are null while suggestions are looked up`() {
        val parsed = EditorState.parse(state(""", "spelling": {"pending": false, "missing": [], "word": {"word": "wrng", "variants": null}}"""))
        assertNull(parsed.spelling!!.word!!.variants)
    }

    @Test
    fun `parses the search and links`() {
        val parsed = EditorState.parse(state(""", "search": {"count": 3, "current": 1}, "link": {"url": "https://example.org", "anchor": ""}"""))
        assertEquals(SearchState(3, 1, -1), parsed.search)
        assertEquals("https://example.org", parsed.link!!.url)
    }

    @Test
    fun `parses the co-editing session`() {
        val parsed = EditorState.parse(state(""", "coEditing": {"state": 2, "reconnecting": true, "disconnected": false,
            "participants": [{"id": "u1", "name": "Ann", "color": "#a273e5", "view": false, "me": true},
                             {"id": "u2", "name": "Ben", "color": "#3367d6", "view": false, "me": false},
                             {"id": "u3", "name": "Cy", "color": "#55aa00", "view": true, "me": false}],
            "cursors": [{"user": "u2", "name": "Ben", "color": "#3367d6", "page": 0, "x1": 15, "y1": 36, "x2": 15, "y2": 44}]}"""))
        val session = parsed.coEditing!!
        assertTrue(session.reconnecting)
        assertFalse(session.disconnected)
        assertEquals(3, session.participants.size)
        assertEquals(listOf("Ben"), session.coEditors.map { it.name })
        assertEquals(ForeignCursor("u2", "Ben", "#3367d6", 0, 15.0, 36.0, 15.0, 44.0), session.cursors.single())
        assertTrue(session.selections.isEmpty())
    }

    @Test
    fun `parses others' selections in spreadsheets`() {
        val parsed = EditorState.parse(state(""", "coEditing": {"state": 2, "participants": [], "cursors": [],
            "selections": [{"user": "u2", "name": "Ben", "color": "#3367d6", "editing": true, "ranges": [[1, 2, 3, 4], [5, 5, 5, 5]]}]}"""))
        assertEquals(
            ForeignSelection("u2", "Ben", "#3367d6", true, listOf(CellRange(1, 2, 3, 4), CellRange(5, 5, 5, 5))),
            parsed.coEditing!!.selections.single()
        )
    }

    @Test
    fun `parses the PDF editor's state`() {
        val parsed = EditorState.parse(state(""", "pdf": {"ink": true, "annotation": {"id": "12", "type": 8}, "textSelected": false},
            "navigate": {"page": 3, "y": 120.5}"""))
        assertEquals(PdfState(ink = true, annotation = 8, textSelected = false), parsed.pdf)
        assertEquals(Place(3, 120.5), parsed.navigate)
        val textBox = EditorState.parse(state(""", "pdf": {"ink": false, "annotation": null, "textSelected": false, "textBox": true}"""))
        assertEquals(PdfState(ink = false, annotation = null, textSelected = false, textBox = true), textBox.pdf)
        val framed = EditorState.parse(state(""", "textFrame": {"page": 2, "points": [1, 2, 3, 2, 3, 4, 1, 4]}"""))
        assertEquals(2, framed.textFrame!!.page)
        assertEquals(listOf(1.0, 2.0, 3.0, 2.0, 3.0, 4.0, 1.0, 4.0), framed.textFrame!!.points.toList())
        assertNull(textBox.textFrame)
        assertNull(EditorState.parse(state("")).pdf)
    }

    @Test
    fun `no session without co-editing`() {
        assertNull(EditorState.parse(state(""", "coEditing": null""")).coEditing)
    }

    @Test
    fun `parses paragraph spacing and direction`() {
        val format = EditorState.parse(state(""", "format": {"LineRule": 2, "Line": 8.5, "SpacingBefore": -1, "Rtl": true}""")).format
        assertEquals(2, format.lineRule)
        assertEquals(8.5, format.line, 0.0)
        assertEquals(-1.0, format.spacingBefore, 0.0)
        assertTrue(format.rtl)
        assertFalse(EditorState.parse(state(""", "format": {}""")).format.rtl)
    }

    @Test
    fun `parses advanced paragraph settings`() {
        val format = EditorState.parse(state(""", "format": {"KeepNext": true, "WidowControl": true, "PageBreakBefore": false, "FirstLine": -6.35}""")).format
        assertEquals(setOf(ParagraphOption.KEEP_NEXT, ParagraphOption.WIDOW_CONTROL), format.paragraphOptions)
        assertEquals(-6.35, format.firstLine, 0.0)
        assertTrue(EditorState.parse(state(""", "format": {}""")).format.paragraphOptions.isEmpty())
    }

    @Test
    fun `parses theme colors and resolves them`() {
        val theme = (0 until 60).map { "%06X".format(it) }
        val json = """{"theme": [${theme.joinToString { "\"$it\"" }}], "standard": ["C00000", "FF0000"]}"""
        val colors = ThemeColors.parse(json)
        assertEquals(60, colors.theme.size)
        assertEquals(listOf("C00000", "FF0000"), colors.standard)
        assertEquals("theme:26", ThemeColors.themeColor(4, 2))
        assertEquals("00001A", colors.hexOf("theme:26"))
        assertEquals("123456", colors.hexOf("123456"))
        assertNull(colors.hexOf(""))
        assertNull(colors.hexOf(null))
        assertNull(ThemeColors.parse("""{"theme": [], "standard": []}""").hexOf("theme:3"))
    }

    @Test
    fun `writes note numbers in their formats`() {
        assertEquals("12", NoteFormat.DECIMAL.format(12))
        assertEquals("c", NoteFormat.LOWER_LETTER.format(3))
        assertEquals("AA", NoteFormat.UPPER_LETTER.format(27))
        assertEquals("xiv", NoteFormat.LOWER_ROMAN.format(14))
        assertEquals("MCMXCIX", NoteFormat.UPPER_ROMAN.format(1999))
    }

    @Test
    fun `reads the paragraph's background color`() {
        assertEquals("FFCC00", EditorState.parse(state(""", "format": {"Shading": "FFCC00"}""")).format.shading)
    }

    @Test
    fun `reads the text's language`() {
        val language = EditorState.parse(state(""", "format": {"Lang": 1031, "LangTag": "de-DE"}""")).format
        assertEquals(1031 to "de-DE", language.language to language.languageTag)
    }

    @Test(expected = org.json.JSONException::class)
    fun `fails for something that isn't a state`() {
        EditorState.parse("undefined")
    }
}
