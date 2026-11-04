package lib.editors.base.engine

import org.json.JSONObject

/** Page size in millimeters. */
data class PageSize(val widthMm: Double, val heightMm: Double)

/**
 * A document open in the editor engine. Not thread-safe: call it on [EditorEngine.dispatcher] only.
 *
 * Editing commands return the editor's state afterwards (see engine.js in editors-android/engine).
 */
interface EditorDocument : PagedDocument {

    /**
     * Saves the document with its edits to [path] in the format of [extension] ("docx", "odt",
     * "pdf", ...; see [WRITABLE_FORMATS]). Returns 0, the converter's error code or -1 for a
     * format the engine can't write. Editing continues afterwards.
     */
    fun save(path: String, extension: String): Int

    /**
     * The password to save the document with from now on ("" for none): files of Office (docx,
     * xlsx, pptx, ...) and ODF formats are encrypted with it. The document counts as modified
     * until it is saved.
     */
    fun setPassword(password: String)

    /** Runs JavaScript in sdkjs and returns its result if it is a string. */
    fun execute(script: String): String

    /**
     * Presentations: the designs ([previews] "themes", [setTheme]), converted from sdkjs' once,
     * which takes a moment the first time. Returns their count.
     */
    fun prepareThemes(): Int = 0

    fun state(): EditorState = EditorState.parse(execute("eoEngine.getState()"))

    /**
     * Touch at page coordinates in millimeters; [clickCount] 2 selects a word. Pictures and shapes
     * are selected, moved, resized and rotated by touching them and their handles (see
     * [EditorState.drawings]); [addToSelection] adds one to the selected ones, like Ctrl+click.
     * [extend] extends the selection to the point, like Shift+click.
     */
    fun touch(
        action: TouchAction, page: Int, xMm: Double, yMm: Double, clickCount: Int = 1,
        addToSelection: Boolean = false, extend: Boolean = false
    ): EditorState =
        command("eoEngine.mouse(${action.value}, $page, $xMm, $yMm, $clickCount, $addToSelection, $extend)")

    /** Spreadsheets: touch on pictures, shapes and charts, at sheet millimeters (see [touch]). */
    fun touchDrawing(action: TouchAction, xMm: Double, yMm: Double, clickCount: Int = 1, addToSelection: Boolean = false): EditorState =
        command("eoEngine.mouseDrawing(${action.value}, $xMm, $yMm, $clickCount, $addToSelection)")

    /** Directory that pictures to insert are copied into (see [insertImage]). */
    fun imagesDirectory(): String = execute("eoEngine.getImagesDirectory()")

    /** Inserts the picture [name] in [imagesDirectory], [width] x [height] pixels, at the cursor. */
    fun insertImage(name: String, width: Int, height: Int): EditorState =
        command("eoEngine.insertImage(${JSONObject.quote(name)}, $width, $height)")

    /** Inserts a shape by its preset name ("rect", "ellipse", "rightArrow", "line", ...), selected. */
    fun insertShape(preset: String): EditorState = command("eoEngine.insertShape(${JSONObject.quote(preset)})")

    /** A text box with the cursor in it; in presentations centered on ([xMm], [yMm]) of slide [page]. */
    fun insertTextBox(page: Int, xMm: Double, yMm: Double): EditorState = command("eoEngine.insertTextBox($page, $xMm, $yMm)")

    /** Fill of the selected shapes, "RRGGBB" or a theme color (see [ThemeColors]); null for none. */
    fun setShapeFill(color: String?): EditorState = command("eoEngine.setShapeFill(${JSONObject.quote(color.orEmpty())})")

    /** Outline of the selected shapes, "RRGGBB" or a theme color (see [ThemeColors]; [color] null for none), [widthMm] wide. */
    fun setShapeOutline(color: String?, widthMm: Double = 0.75, dash: OutlineDash = OutlineDash.SOLID): EditorState =
        command("eoEngine.setShapeOutline(${JSONObject.quote(color.orEmpty())}, $widthMm, ${dash.value})")

    /** Charts: the part the fill, outline and opacity commands go to (the whole chart at first). */
    fun setChartPart(part: ChartPart): EditorState = command("eoEngine.setChartPart(${JSONObject.quote(part.value)})")

    /** The opacity of the selected drawings' fill, in percent. */
    fun setShapeOpacity(percent: Int): EditorState = command("eoEngine.setShapeOpacity($percent)")

    /** The selected shapes' and pictures' outer shadow ([ShapeShadow.NONE] removes it). */
    fun setShapeShadow(shadow: ShapeShadow): EditorState = command("eoEngine.setShapeShadow(${JSONObject.quote(shadow.value)})")

    /** The direction of the selected shapes' text. */
    fun setTextDirection(direction: ShapeTextDirection): EditorState = command("eoEngine.setTextVert(${direction.value})")

    /** The fill of the selected shapes or the current slide's background. */
    fun setFill(target: FillTarget, fill: ShapeFill): EditorState =
        command("eoEngine.setFill(${JSONObject.quote(target.value)}, ${fill.toJson()})")

    /** The fill of the first selected shape or of the current slide's background; null without one. */
    fun fill(target: FillTarget): ShapeFill? = ShapeFill.parse(execute("eoEngine.getFill(${JSONObject.quote(target.value)})"))

    fun fillResources(): FillResources = FillResources.parse(execute("eoEngine.getFillResources()"))

    /** Z-order of the selected pictures and shapes. */
    fun arrange(order: DrawingOrder): EditorState = command("eoEngine.arrange(${order.value})")

    /** Groups the selected pictures and shapes, or ungroups the selected group. */
    fun group(group: Boolean): EditorState = command("eoEngine.group($group)")

    /** Selects from the start to the end point, in page millimeters (selection handles). */
    fun select(startPage: Int, startXMm: Double, startYMm: Double, endPage: Int, endXMm: Double, endYMm: Double): EditorState =
        command("eoEngine.select($startPage, $startXMm, $startYMm, $endPage, $endXMm, $endYMm)")

    /** Key with its web key code (8 backspace, 13 enter, 37-40 arrows, 46 delete). */
    fun keyDown(keyCode: Int, shift: Boolean = false, ctrl: Boolean = false): EditorState =
        command("eoEngine.keyDown($keyCode, $shift, $ctrl)")

    fun enterText(text: String): EditorState = command("eoEngine.enterText(${JSONObject.quote(text)})")

    /** Text from the keyboard: replaces the text being composed, if any. */
    fun commitText(text: String): EditorState = command("eoEngine.commitText(${JSONObject.quote(text)})")

    /** Text the keyboard is still composing; replaced by the next call until [finishComposingText]. */
    fun setComposingText(text: String): EditorState = command("eoEngine.setComposingText(${JSONObject.quote(text)})")

    fun finishComposingText(): EditorState = command("eoEngine.finishComposingText()")

    fun setBold(value: Boolean): EditorState = command("eoEngine.setBold($value)")

    fun setItalic(value: Boolean): EditorState = command("eoEngine.setItalic($value)")

    fun setUnderline(value: Boolean): EditorState = command("eoEngine.setUnderline($value)")

    fun setAlignment(alignment: Alignment): EditorState = command("eoEngine.setAlign(${alignment.value})")

    /** The selection as plain text and in sdkjs' internal format (keeps formatting). */
    fun copy(): ClipboardContent = JSONObject(execute("eoEngine.copy()")).let {
        ClipboardContent(it.getString("text"), it.getString("internal"))
    }

    fun cut(): EditorState = command("eoEngine.cut()")

    /** Pastes [internal] if given (from [copy] in this app), [text] otherwise. */
    fun paste(text: String, internal: String? = null): EditorState =
        command("eoEngine.paste(${JSONObject.quote(text)}, ${JSONObject.quote(internal.orEmpty())})")

    fun selectAll(): EditorState = command("eoEngine.selectAll()")

    /**
     * Continues work sdkjs left for later, like laying out the pages after the first ones,
     * for up to [budgetMs]. Commands between ticks run without waiting for the whole layout.
     */
    fun tick(budgetMs: Int): EditorState = command("eoEngine.tick($budgetMs)")

    /** Presentations: adds a slide after the current one, with its layout, and goes to it. */
    fun addSlide(): EditorState = command("eoEngine.addSlide()")

    /** Presentations: deletes the current slide. */
    fun deleteSlide(): EditorState = command("eoEngine.deleteSlide()")

    /**
     * Spreadsheets: selects the cells from ([c1], [r1]) to ([c2], [r2]); ([c1], [r1]) is the
     * active cell. [enterText] is entered into the active cell first, if given (see [setCellText]).
     */
    fun selectCells(c1: Int, r1: Int, c2: Int, r2: Int, enterText: String? = null): EditorState =
        command("${enterScript(enterText)}eoEngine.selectCells($c1, $r1, $c2, $r2)")

    /** Spreadsheets: the active cell's new content (a value or a formula starting with "="); empty clears it. */
    fun setCellText(text: String): EditorState = command(enterScript(text))

    /** Spreadsheets: clears the content of the selected cells. */
    fun clearCells(): EditorState = command("eoEngine.clearCells()")

    /** Spreadsheets: shows another sheet, after entering [enterText] into the active cell, if given. */
    fun setActiveSheet(index: Int, enterText: String? = null): EditorState =
        command("${enterScript(enterText)}eoEngine.setActiveSheet($index)")

    // ---------------------------------------------------------------------------------
    // Text and paragraph formatting (state: EditorState.format)

    /** Font names the engine has, sorted. */
    fun fonts(): List<String> = jsonStrings(execute("eoEngine.getFonts()"))

    /** The fonts the document uses, sorted. */
    fun documentFonts(): List<String> = jsonStrings(execute("eoEngine.getDocumentFonts()"))

    /** Text documents: paragraph style names. */
    fun styles(): List<String> = jsonStrings(execute("eoEngine.getStyles()"))

    /**
     * The names the pictures of the paragraph styles ([previews] "paragraphStyles") or of the cell
     * styles ("cellStyles") show, by style name.
     */
    fun setStyleLabels(labels: Map<String, String>) {
        execute("eoEngine.setStyleLabels(${JSONObject.quote(JSONObject(labels).toString())})")
    }

    fun setFontName(name: String): EditorState = asyncCommand("eoEngine.setFontName(${JSONObject.quote(name)})")

    fun setFontSize(points: Double): EditorState = command("eoEngine.setFontSize($points)")

    fun setStrikeout(value: Boolean): EditorState = command("eoEngine.setStrikeout($value)")

    /** Text documents: all caps. */
    fun setCaps(value: Boolean): EditorState = command("eoEngine.setCaps($value)")

    /** Text documents: small caps. */
    fun setSmallCaps(value: Boolean): EditorState = command("eoEngine.setSmallCaps($value)")

    /** Documents and presentations: the text's language (LCID), which spell checking goes by. */
    fun setTextLanguage(lcid: Int): EditorState = command("eoEngine.setTextLanguage($lcid)")

    /** Documents and presentations: the document's default language (LCID, 0 if unknown). */
    fun documentLanguage(): Int = execute("String(eoEngine.getDocumentLanguage())").toIntOrNull() ?: 0

    /**
     * Documents and presentations: the document's language (LCID), its default and that of all its
     * text. False if others edit parts of it (co-editing): nothing changed then.
     */
    fun setDocumentLanguage(lcid: Int): Boolean = execute("String(eoEngine.setDocumentLanguage($lcid))") == "true"

    /** 0 baseline, 1 subscript, 2 superscript. */
    fun setBaseline(value: Int): EditorState = command("eoEngine.setBaseline($value)")

    /** Text color, "RRGGBB" or a theme color (see [ThemeColors]); null for automatic. */
    fun setTextColor(color: String?): EditorState = command("eoEngine.setTextColor(${JSONObject.quote(color.orEmpty())})")

    /** Highlight as "RRGGBB"; null for none. */
    fun setHighlight(color: String?): EditorState = command("eoEngine.setHighlight(${JSONObject.quote(color.orEmpty())})")

    fun clearFormatting(): EditorState = command("eoEngine.clearFormatting()")

    /** Text documents: the paragraphs' background color, as "RRGGBB" or "theme:N"; null for none. */
    fun setParagraphShading(color: String?): EditorState = command("eoEngine.setParagraphShading(${JSONObject.quote(color.orEmpty())})")

    /**
     * Lists: [type] 0 bullets, 1 numbers; [subtype] for bullets 1 dot, 2 circle, 3 square; for
     * numbers 1 "1.", 2 "1)", 3 "I.", 4 "A.", 5 "a)", 6 "a.", 7 "i."; -1 removes the list.
     */
    fun setList(type: Int, subtype: Int): EditorState = asyncCommand("eoEngine.setList($type, $subtype)")

    /**
     * A list preset of [kind] by its index in the previews "list-<kind>" ([previews]); only
     * documents have [ListKind.MULTILEVEL].
     */
    fun setListPreset(kind: ListKind, index: Int): EditorState = asyncCommand("eoEngine.setListPreset(${JSONObject.quote(kind.value)}, $index)")

    /** The list presets of the paragraph at the cursor, by kind: their index, -1 for none of them. */
    fun listPresets(): Map<ListKind, Int> {
        val json = JSONObject(execute("eoEngine.getListPresets()"))
        return ListKind.entries.associateWith { json.optInt(it.value, -1) }
    }

    /** Text documents: the current list's numbering from 1 again. */
    fun restartNumbering(): EditorState = command("eoEngine.restartNumbering()")

    /** Text documents: the current list's numbering on from the list before it. */
    fun continueNumbering(): EditorState = command("eoEngine.continueNumbering()")

    fun indent(increase: Boolean): EditorState = command("eoEngine.indent($increase)")

    /** [rule] 0 at least, 1 multiple ([value] e.g. 1.15), 2 exactly ([value] in mm). */
    fun setLineSpacing(rule: Int, value: Double): EditorState = command("eoEngine.setLineSpacing($rule, $value)")

    /** Space before and after paragraphs in mm. */
    fun setParagraphSpacing(beforeMm: Double, afterMm: Double): EditorState =
        command("eoEngine.setParagraphSpacing($beforeMm, $afterMm)")

    /** The paragraphs' (spreadsheets: the cells') direction: right to left or left to right. */
    fun setRtl(rtl: Boolean): EditorState = command("eoEngine.setRtl($rtl)")

    /** Text documents: turns an advanced paragraph setting on or off. */
    fun setParagraphOption(option: ParagraphOption, on: Boolean): EditorState =
        command("eoEngine.setParagraphOption(${JSONObject.quote(option.command)}, $on)")

    /** Text documents: the first line's indent in mm (negative: hanging). */
    fun setFirstLineIndent(mm: Double): EditorState = command("eoEngine.setFirstLineIndent($mm)")

    /** Text documents: applies a paragraph style by name (see [styles]). */
    fun setStyle(name: String): EditorState = asyncCommand("eoEngine.setStyle(${JSONObject.quote(name)})")

    // ---------------------------------------------------------------------------------
    // Spreadsheets: cell formats, rows and columns, sheets (state: EditorState.sheet)

    /** A cell fill, "RRGGBB" or a theme color (see [ThemeColors]); null for none. */
    fun setCellFill(color: String?): EditorState = command("eoEngine.setCellFill(${JSONObject.quote(color.orEmpty())})")

    /**
     * The cells' text angle in degrees: 0 horizontal, 45 and -45 tilted up and down, 90 rotated
     * up, -90 rotated down, [VERTICAL_TEXT_ANGLE] stacked letters.
     */
    fun setTextAngle(angle: Int): EditorState = command("eoEngine.setTextAngle($angle)")

    /** A number format code: "General", "0.00", "0.00%", "#,##0.00 €", "dd.mm.yyyy", "@" (text). */
    fun setNumberFormat(format: String): EditorState = command("eoEngine.setNumberFormat(${JSONObject.quote(format)})")

    /** A number format by type, for the document's locale: 0 general, 1 number, 4 currency, 5 date, 7 time, 8 percent, 10 text. */
    fun setNumberFormatType(type: Int): EditorState = command("eoEngine.setNumberFormatType($type)")

    fun changeDecimals(more: Boolean): EditorState = command("eoEngine.changeDecimals($more)")

    /** Cell style names, the document's own first (their pictures: [previews] "cellStyles"). */
    fun cellStyles(): List<String> = jsonStrings(execute("eoEngine.getCellStyles()"))

    /** Applies a cell style by name (see [cellStyles]). */
    fun setCellStyle(name: String): EditorState = command("eoEngine.setCellStyle(${JSONObject.quote(name)})")

    /** Borders of the selected cells: "all", "outer", "bottom" or "none". */
    fun setBorders(kind: String): EditorState = command("eoEngine.setBorders(${JSONObject.quote(kind)})")

    fun setWrap(value: Boolean): EditorState = command("eoEngine.setWrap($value)")

    fun setMerge(value: Boolean): EditorState = command("eoEngine.setMerge($value)")

    /** 0 bottom, 1 center, 4 top. */
    fun setVerticalAlign(value: Int): EditorState = command("eoEngine.setVerticalAlign($value)")

    fun selectRows(r1: Int, r2: Int, enterText: String? = null): EditorState = command("${enterScript(enterText)}eoEngine.selectRows($r1, $r2)")

    fun selectColumns(c1: Int, c2: Int, enterText: String? = null): EditorState =
        command("${enterScript(enterText)}eoEngine.selectColumns($c1, $c2)")

    /** Inserts at the selection: 1 cells shifted right, 2 cells shifted down, 3 columns, 4 rows. */
    fun insertCells(option: Int): EditorState = command("eoEngine.insertCells($option)")

    /** Deletes the selection: 1 cells shifted left, 2 cells shifted up, 3 columns, 4 rows. */
    fun deleteCells(option: Int): EditorState = command("eoEngine.deleteCells($option)")

    fun hideRows(hide: Boolean): EditorState = command("eoEngine.hideRows($hide)")

    fun hideColumns(hide: Boolean): EditorState = command("eoEngine.hideColumns($hide)")

    fun autoFitColumns(): EditorState = command("eoEngine.autoFitColumns()")

    fun setColumnWidth(mm: Double): EditorState = command("eoEngine.setColumnWidth($mm)")

    fun setRowHeight(mm: Double): EditorState = command("eoEngine.setRowHeight($mm)")

    /** Freezes the columns before [col] and the rows before [row]; 0, 0 unfreezes. */
    fun freezePanes(col: Int, row: Int): EditorState = command("eoEngine.freezePanes($col, $row)")

    /** Fills the selection from its filled cells, as dragging Excel's fill handle does. */
    fun fillSelection(): EditorState = command("eoEngine.fillSelection()")

    fun addSheet(name: String): EditorState = command("eoEngine.addSheet(${JSONObject.quote(name)})")

    fun renameSheet(name: String): EditorState = command("eoEngine.renameSheet(${JSONObject.quote(name)})")

    fun deleteSheet(): EditorState = command("eoEngine.deleteSheet()")

    /** Moves the active sheet to before the sheet at [index]. */
    fun moveSheet(index: Int): EditorState = command("eoEngine.moveSheet($index)")

    fun hideSheet(): EditorState = command("eoEngine.hideSheet()")

    fun showSheet(index: Int): EditorState = command("eoEngine.showSheet($index)")

    /** Spreadsheet function names, for autocomplete. */
    fun functions(): List<String> = jsonStrings(execute("eoEngine.getFunctions()"))

    // ---------------------------------------------------------------------------------
    // Find and replace, links (all editors)

    /**
     * Selects the next (or previous) match of [text] from the cursor; spreadsheets search all
     * sheets, and [wholeWords] means whole cells there. The state's search has the matches.
     */
    fun find(text: String, matchCase: Boolean, wholeWords: Boolean, forward: Boolean = true): EditorState =
        command("eoEngine.find(${JSONObject.quote(text)}, $matchCase, $wholeWords, $forward)")

    /** Replaces the current match and selects the next one, or replaces all matches ([all]). */
    fun replace(text: String, replacement: String, matchCase: Boolean, wholeWords: Boolean, all: Boolean): EditorState =
        command("eoEngine.replace(${JSONObject.quote(text)}, ${JSONObject.quote(replacement)}, $matchCase, $wholeWords, $all)")

    fun endFind(): EditorState = command("eoEngine.endFind()")

    // ---------------------------------------------------------------------------------
    // Forms (documents with form fields)

    fun forms(): List<FormField> {
        val json = org.json.JSONArray(execute("eoEngine.getForms()"))
        return (0 until json.length()).map { FormField.parse(json.getJSONObject(it)) }
    }

    /** Filling: only the fields of [role] ("" for the form's first role) take input. */
    fun setFillMode(fill: Boolean, role: String = ""): EditorState = command("eoEngine.setFillMode($fill, ${JSONObject.quote(role)})")

    /** The value of a field: text, checked, a list item's value or a date (yyyy-MM-dd). */
    fun setFormValue(field: FormField, value: Any): EditorState {
        val json = when (value) {
            is Boolean -> value.toString()
            else -> JSONObject.quote(if (field.type == "date") "${value}T00:00:00Z" else value.toString())
        }
        // Lists and text are applied once their fonts are checked (a promise)
        return asyncCommand("eoEngine.setFormValue(${JSONObject.quote(field.id)}, ${JSONObject.quote(field.type)}, $json)")
    }

    /** A picture field gets a picture from the images directory (see [imagesDirectory]). */
    fun setFormPicture(field: FormField, name: String, width: Int, height: Int): EditorState {
        val picture = JSONObject().put("name", name).put("width", width).put("height", height)
        return asyncCommand("eoEngine.setFormValue(${JSONObject.quote(field.id)}, ${JSONObject.quote(field.type)}, $picture)")
    }

    /** next, previous, nextRequired or clear (all fields). */
    fun formAction(action: String): EditorState = command("eoEngine.formAction(${JSONObject.quote(action)})")

    fun formRoles(): List<FormRole> {
        val json = org.json.JSONArray(execute("eoEngine.getFormRoles()"))
        return (0 until json.length()).map {
            val role = json.getJSONObject(it)
            FormRole(role.optString("name"), role.optString("color"), role.optInt("fieldCount"))
        }
    }

    // ---------------------------------------------------------------------------------
    // Comments (all editors)

    /** The author of new comments and replies. */
    fun setCommentUser(id: String, name: String) {
        execute("eoEngine.setCommentUser(${JSONObject.quote(id)}, ${JSONObject.quote(name)})")
    }

    fun comments(): List<Comment> {
        val json = org.json.JSONArray(execute("eoEngine.getComments()"))
        return (0 until json.length()).map { i ->
            val comment = json.getJSONObject(i)
            val replies = comment.optJSONArray("replies") ?: org.json.JSONArray()
            Comment(
                comment.getString("id"), comment.optString("text"), comment.optString("user"),
                comment.optString("time").toLongOrNull() ?: 0L, comment.optString("quote"), comment.optBoolean("solved"),
                (0 until replies.length()).map { r ->
                    val reply = replies.getJSONObject(r)
                    CommentReply(reply.optString("text"), reply.optString("user"), reply.optString("time").toLongOrNull() ?: 0L)
                },
                comment.optInt("page", -1), comment.optDouble("x", 0.0), comment.optDouble("y", 0.0),
                comment.optInt("sheet", -1), comment.optInt("col", -1), comment.optInt("row", -1)
            )
        }
    }

    /** On the selection (documents: or the word at the cursor; spreadsheets: the active cell; presentations: the slide). */
    fun addComment(text: String): EditorState = command("eoEngine.addComment(${JSONObject.quote(text)})")

    fun replyComment(id: String, text: String): EditorState =
        command("eoEngine.replyComment(${JSONObject.quote(id)}, ${JSONObject.quote(text)})")

    fun resolveComment(id: String, solved: Boolean): EditorState = command("eoEngine.resolveComment(${JSONObject.quote(id)}, $solved)")

    fun deleteComment(id: String): EditorState = command("eoEngine.deleteComment(${JSONObject.quote(id)})")

    /** Shows the comment: selects its text, cell or slide. */
    fun gotoComment(id: String): EditorState = command("eoEngine.gotoComment(${JSONObject.quote(id)})")

    /** How a txt or csv file was read (guessed or chosen); null for other files. */
    val textOptions: TextOptions? get() = TextOptions.parse(execute("JSON.stringify(eoEngine.textOptions || null)"))

    // ---------------------------------------------------------------------------------
    // PDFs: sdkjs' PDF editor (pdf.js in editors-android/engine), for PDFs other than forms of
    // the document editor

    /** The engine opened the file in sdkjs' PDF editor: the commands below apply. */
    val isPdf: Boolean get() = execute("String(eoEngine.kind === 'pdf')") == "true"

    override fun fileOutline(): List<PageOutlineItem> {
        if (!isPdf) return emptyList()
        val json = org.json.JSONArray(execute("eoEngine.getFileOutline()"))
        return (0 until json.length()).map {
            val item = json.getJSONObject(it)
            PageOutlineItem(item.optString("title"), item.optInt("level", 1), item.optInt("page"), item.optDouble("y").toFloat())
        }
    }

    /** The page the app shows: searching starts there, text boxes go there. */
    fun setCurrentPage(page: Int) {
        execute("eoEngine.setCurrentPage($page)")
    }

    /** Marks up the selected text, in the color as "RRGGBB". */
    fun markup(kind: PdfMarkupKind, color: String): EditorState =
        command("eoEngine.markup(${JSONObject.quote(kind.value)}, ${JSONObject.quote(color)})")

    /** Freehand drawing (touches draw) with a pen of the color as "RRGGBB" and the width, until [stopInk]. */
    fun startInk(color: String, widthMm: Double): EditorState = command("eoEngine.startInk(${JSONObject.quote(color)}, $widthMm)")

    fun stopInk(): EditorState = command("eoEngine.stopInk()")

    /** A text box (free text annotation) centered on ([xMm], [yMm]) of [page], with the cursor in it. */
    fun addFreeText(page: Int, xMm: Double, yMm: Double): EditorState = command("eoEngine.addFreeText($page, $xMm, $yMm)")

    /** Whether the text of [page] can be made editable ([editPage]): not if it is already, or the page was added. */
    fun canEditPage(page: Int): Boolean = execute("String(eoEngine.canEditPage($page))") == "true"

    /**
     * Makes the text, shapes and pictures of [page] editable, as text boxes, shapes and pictures
     * (the web PDF editor's "Edit text"), in one undo step. Their text is formatted as in
     * presentations ([PdfState.textBox]).
     */
    fun editPage(page: Int): EditorState = command("eoEngine.editPage($page)")

    /**
     * While co-editing, [editPage] waits for the page's pictures to be uploaded: their names in
     * [imagesDirectory] (empty if none wait). [finishImageUploads] goes on with the names the
     * Document Server gave them, in the same order.
     */
    fun imageUploads(): List<String> = jsonStrings(execute("eoEngine.imageUploads()"))

    fun finishImageUploads(names: List<String>): EditorState =
        command("eoEngine.finishImageUploads(${JSONObject.quote(org.json.JSONArray(names).toString())})")

    /** Removes the selected annotations, pictures and shapes. */
    fun deleteSelection(): EditorState = command("eoEngine.deleteSelection()")

    /**
     * Puts a picture of the images directory ([name], [width] x [height] pixels) on [page], at
     * ([xMm], [yMm]) with the size [widthMm] x [heightMm]: a drawn signature. It is selected.
     */
    fun placeImage(name: String, width: Int, height: Int, page: Int, xMm: Double, yMm: Double, widthMm: Double, heightMm: Double): EditorState =
        command("eoEngine.placeImage(${JSONObject.quote(name)}, $width, $height, $page, $xMm, $yMm, $widthMm, $heightMm)")

    // ---------------------------------------------------------------------------------
    // Charts (all editors)

    /** A chart of the type (Asc.c_oAscChartTypeSettings): with sample data, or in spreadsheets from the selected cells. */
    fun insertChart(type: Int): EditorState = command("eoEngine.insertChart($type)")

    fun setChartType(type: Int): EditorState = command("eoEngine.setChartType($type)")

    /**
     * SmartArt of the type (Asc.c_oAscSmartArtTypes) from the definition [fileName] (without .bin)
     * the engine has in sdkjs/common/SmartArts/SmartArtData.
     */
    fun insertSmartArt(type: Int, fileName: String): EditorState =
        asyncCommand("eoEngine.insertSmartArt($type, ${JSONObject.quote(fileName)})")

    /** The selected chart's title, legend position (Asc.c_oAscChartLegendShowSettings), value labels and gridlines. */
    fun setChartElements(title: Boolean, legend: Int, labels: Boolean, gridlines: Boolean): EditorState =
        command("eoEngine.setChartElements($title, $legend, $labels, $gridlines)")

    /** The texts of the selected chart's title and its axes' titles; "" removes one. */
    fun setChartTitles(title: String, horizontal: String, vertical: String): EditorState =
        command("eoEngine.setChartTitles(${JSONObject.quote(title)}, ${JSONObject.quote(horizontal)}, ${JSONObject.quote(vertical)})")

    /**
     * Spreadsheets: the cells the selected chart shows ("Sheet1!$A$1:$C$5"). [onError] gets the
     * error (Asc.c_oAscError.ID: an invalid range, too much data) if it isn't taken, on the
     * engine's thread.
     */
    fun setChartRange(range: String, onError: (Int) -> Unit): EditorState {
        val error = execute("try { String(eoEngine.setChartRange(${JSONObject.quote(range)})); } catch (e) { eoEngine.reportError(e); '0'; }")
            .toIntOrNull() ?: 0
        if (error != 0) onError(error)
        return state()
    }

    /** Spreadsheets: the selected chart's series from rows instead of columns, or the other way round. */
    fun switchChartRowCol(): EditorState = command("eoEngine.switchChartRowCol()")

    /**
     * Spreadsheets: choosing the selected chart's cells on the sheet. [startChartRange] remembers the
     * chart while cells are selected; [endChartRange] selects it again and, with [apply], gives it
     * the selected cells (see [setChartRange]).
     */
    fun startChartRange(): EditorState = command("eoEngine.startChartRange()")

    fun endChartRange(apply: Boolean, onError: (Int) -> Unit): EditorState {
        val error = execute("try { String(eoEngine.endChartRange($apply)); } catch (e) { eoEngine.reportError(e); '0'; }")
            .toIntOrNull() ?: 0
        if (error != 0) onError(error)
        return state()
    }

    /** The selected chart's style, 1 to [ChartState.styles]. */
    fun setChartStyle(style: Int): EditorState = command("eoEngine.setChartStyle($style)")

    /** Settings of the selected chart's layout to change: names and values as in [ChartLayout]. */
    fun setChartLayout(values: Map<String, Int>): EditorState = command("eoEngine.setChartLayout(${JSONObject(values)})")

    /**
     * Settings of the selected chart's value or category axis ([value]) to change: names and
     * values as in [ChartAxis], null for automatic minimum and maximum.
     */
    fun setChartAxis(value: Boolean, values: Map<String, Any?>): EditorState {
        val json = JSONObject()
        values.forEach { (name, v) -> json.put(name, v ?: JSONObject.NULL) }
        return command("eoEngine.setChartAxis(${JSONObject.quote(if (value) "value" else "category")}, $json)")
    }

    /**
     * Pictures of choices: [kind] chartStyles (the selected chart's type in its styles),
     * tableStyles, layouts (presentations). [renderPreview] draws those listed last.
     */
    fun previews(kind: String): List<Preview> {
        val json = org.json.JSONArray(execute("eoEngine.previews(${JSONObject.quote(kind)})"))
        return (0 until json.length()).map {
            val item = json.getJSONObject(it)
            Preview(item.optString("id"), item.optString("name"), item.optDouble("width"), item.optDouble("height"))
        }
    }

    /** Draws the [index]th of the [previews] listed last into [bitmap], at its size. */
    fun renderPreview(index: Int, bitmap: android.graphics.Bitmap): Boolean =
        renderTile(-1 - index, bitmap.width, bitmap.height, 0, 0, bitmap)

    /** The fill and outline of the selected picture, shape or chart, or null. */
    fun drawingColors(): DrawingColors? {
        val json = execute("eoEngine.drawingColors()")
        if (json == "null") return null
        val root = JSONObject(json)
        fun color(name: String) = if (root.isNull(name)) null else root.optString(name)
        val vert = if (root.isNull("vert")) null else root.optInt("vert")
        return DrawingColors(
            color("fill"), color("outline"), ShapeTextDirection.entries.firstOrNull { it.value == vert },
            outlineWidthMm = if (root.isNull("outlineWidth")) null else root.optDouble("outlineWidth"),
            outlineDash = OutlineDash.entries.firstOrNull { it.value == root.optInt("outlineDash", OutlineDash.SOLID.value) } ?: OutlineDash.SOLID,
            opacity = root.optInt("opacity", 100),
            shadow = if (root.isNull("shadow")) null else ShapeShadow.entries.firstOrNull { it.value == root.optString("shadow") } ?: ShapeShadow.CUSTOM,
            chartPart = if (root.isNull("chartPart")) null else ChartPart.entries.firstOrNull { it.value == root.optString("chartPart") }
        )
    }

    /** The selected chart's data (documents and presentations), or null. */
    fun chartData(): ChartData? {
        val json = execute("eoEngine.getChartData()")
        if (json == "null") return null
        val root = JSONObject(json)
        fun strings(name: String) = root.getJSONArray(name).let { array -> (0 until array.length()).map { array.optString(it) } }
        val values = root.getJSONArray("values")
        return ChartData(
            strings("series"), strings("categories"),
            (0 until values.length()).map { s ->
                val row = values.getJSONArray(s)
                (0 until row.length()).map { if (row.isNull(it)) null else row.getDouble(it) }
            }
        )
    }

    fun setChartData(data: ChartData): EditorState {
        val json = JSONObject()
            .put("series", org.json.JSONArray(data.series))
            .put("categories", org.json.JSONArray(data.categories))
            .put("values", org.json.JSONArray(data.values.map { row -> org.json.JSONArray(row.map { it ?: JSONObject.NULL }) }))
        return command("eoEngine.setChartData($json)")
    }

    // ---------------------------------------------------------------------------------
    // Presentations: slides (the commands act on the current slide)

    fun slides(): List<SlideInfo> {
        val json = org.json.JSONArray(execute("eoEngine.getSlides()"))
        return (0 until json.length()).map { i ->
            val slide = json.getJSONObject(i)
            SlideInfo(
                slide.optBoolean("hidden"), slide.optString("layout"),
                slide.optJSONObject("transition")?.let {
                    SlideTransition(
                        it.optInt("type"), it.optInt("option"), it.optInt("duration"),
                        it.optBoolean("onClick", true), it.optBoolean("after"), it.optInt("afterDuration")
                    )
                },
                slide.optString("notes")
            )
        }
    }

    fun gotoSlide(index: Int): EditorState = command("eoEngine.gotoSlide($index)")

    fun duplicateSlide(): EditorState = command("eoEngine.duplicateSlide()")

    /**
     * The selected pictures and shapes, dragged off the current slide, go to [slide], whose top
     * is [offsetMm] below the current slide's top as the slides are laid out.
     */
    fun moveToSlide(slide: Int, offsetMm: Double): EditorState = command("eoEngine.moveToSlide($slide, $offsetMm)")

    /** Moves the current slide to [position]. */
    fun moveSlide(position: Int): EditorState = command("eoEngine.moveSlide($position)")

    /** Hidden slides are left out of slideshows. */
    fun hideSlide(hide: Boolean): EditorState = command("eoEngine.hideSlide($hide)")

    fun layouts(): List<SlideLayout> {
        val json = org.json.JSONArray(execute("eoEngine.getLayouts()"))
        return (0 until json.length()).map { SlideLayout(json.getJSONObject(it).getInt("index"), json.getJSONObject(it).getString("name")) }
    }

    /** A slide with the layout after the current one. */
    fun addSlide(layoutIndex: Int): EditorState = command("eoEngine.addSlideWithLayout($layoutIndex)")

    fun setLayout(layoutIndex: Int): EditorState = command("eoEngine.setLayout($layoutIndex)")

    /** The design of all slides ([previews] "themes" after [prepareThemes]). */
    fun setTheme(index: Int): EditorState = command("eoEngine.setTheme($index)")

    /** The current slide's background color, "RRGGBB" or a theme color (see [ThemeColors]). */
    fun setSlideBackground(hex: String): EditorState = command("eoEngine.setSlideBackground(${JSONObject.quote(hex)})")

    /** The current slide's background color "RRGGBB", or null for other backgrounds (pictures, from its layout). */
    fun slideBackground(): String? =
        runCatching { org.json.JSONTokener(execute("eoEngine.getSlideBackground()")).nextValue() as? String }.getOrNull()

    /** Replaces the speaker notes of the current slide (lines separated by \n). */
    fun setNotes(text: String): EditorState = command("eoEngine.setNotes(${JSONObject.quote(text)})")

    /** The transition of the current slide, or of all slides ([all]); see [SlideTransition]. */
    fun setTransition(type: Int, option: Int, durationMs: Int, all: Boolean): EditorState =
        command("eoEngine.setTransition($type, $option, $durationMs, $all)")

    /** How the current slide (or [all]) goes on in a slideshow: on a tap, and after [afterMs] if [after]. */
    fun setSlideAdvance(onClick: Boolean, after: Boolean, afterMs: Int, all: Boolean): EditorState =
        command("eoEngine.setSlideAdvance($onClick, $after, $afterMs, $all)")

    // ---------------------------------------------------------------------------------
    // Document structure (documents)

    /** A table of [styleId] (see [previews] "tableStyles"), or the document's default style. */
    fun insertTable(cols: Int, rows: Int, styleId: String? = null): EditorState =
        command("eoEngine.insertTable($cols, $rows, ${styleId?.let { JSONObject.quote(it) } ?: "null"})")

    /** The style of the table at the cursor (see [previews] "tableStyles"). */
    fun setTableStyle(styleId: String): EditorState = command("eoEngine.setTableStyle(${JSONObject.quote(styleId)})")

    /** On the table at the cursor: rowAbove, rowBelow, columnLeft, columnRight, deleteRow, deleteColumn, deleteTable, merge, split, select. */
    fun tableAction(action: String): EditorState = command("eoEngine.tableAction(${JSONObject.quote(action)})")

    /** page, column, line, sectionNextPage or sectionContinuous. */
    fun insertBreak(kind: String): EditorState = command("eoEngine.insertBreak(${JSONObject.quote(kind)})")

    /** Edits the header (or footer) of [page]. */
    fun editHeaderFooter(page: Int, footer: Boolean): EditorState = command("eoEngine.editHeaderFooter($page, $footer)")

    /** Back to the text from a header or footer. */
    fun endHeaderFooter(): EditorState = command("eoEngine.endHeaderFooter()")

    /** While editing a header or footer: differentFirst or differentOddEven. */
    fun setHeaderFooterOption(option: String, value: Boolean): EditorState =
        command("eoEngine.setHeaderFooterOption(${JSONObject.quote(option)}, $value)")

    /** A page number in the header ([where] 1), footer (2) or at the cursor (-1); [align] 0 right, 1 left, 2 center. */
    fun insertPageNumber(where: Int, align: Int): EditorState = command("eoEngine.insertPageNumber($where, $align)")

    fun pageSetup(): PageSetup {
        val json = JSONObject(execute("eoEngine.getPageSetup()"))
        return PageSetup(
            json.getDouble("width"), json.getDouble("height"), json.getBoolean("landscape"),
            json.getDouble("left"), json.getDouble("top"), json.getDouble("right"), json.getDouble("bottom"),
            json.optInt("columns", 1)
        )
    }

    /** Changes the given parts: the size as portrait (width < height), orientation, margins (all four) and columns. */
    fun setPageSetup(
        width: Double? = null, height: Double? = null, landscape: Boolean? = null,
        margins: DoubleArray? = null, columns: Int? = null
    ): EditorState {
        val setup = JSONObject()
        if (width != null && height != null) setup.put("width", width).put("height", height)
        if (landscape != null) setup.put("landscape", landscape)
        if (margins != null) setup.put("left", margins[0]).put("top", margins[1]).put("right", margins[2]).put("bottom", margins[3])
        if (columns != null) setup.put("columns", columns)
        return command("eoEngine.setPageSetup($setup)")
    }

    /** A footnote (or endnote) at the cursor; editing goes on in it. */
    fun addNote(endnote: Boolean): EditorState = command(if (endnote) "eoEngine.addEndnote()" else "eoEngine.addFootnote()")

    /**
     * Documents: mobile view ([on]), the text laid out on pages of [widthMm] by [heightMm] (the
     * screen's), its letters scaled by [scale], without headers and footers; or the document's
     * pages. PDFs and printing always have the document's pages. The text at [page], [xMm],
     * [yMm] (before) is where [EditorState.navigate] says afterwards.
     */
    fun setMobileView(
        on: Boolean, widthMm: Double = 0.0, heightMm: Double = 0.0, scale: Double = 1.0, page: Int = -1, xMm: Double = 0.0, yMm: Double = 0.0
    ): EditorState = command("eoEngine.setMobileView($on, $widthMm, $heightMm, $scale, $page, $xMm, $yMm)")

    /** Text documents: footnotes' or ([endnote]) endnotes' settings. */
    fun noteSettings(endnote: Boolean): NoteSettings {
        val json = JSONObject(execute("eoEngine.getNoteSettings($endnote)"))
        return NoteSettings(
            NotePosition.entries.first { it.key == json.getString("pos") },
            NoteFormat.entries.firstOrNull { it.key == json.getString("format") } ?: NoteFormat.DECIMAL,
            json.optInt("start", 1),
            NoteRestart.entries.firstOrNull { it.key == json.getString("restart") } ?: NoteRestart.CONTINUOUS
        )
    }

    /** Sets footnotes' or ([endnote]) endnotes' settings, for the whole document. */
    fun setNoteSettings(endnote: Boolean, settings: NoteSettings): EditorState {
        val json = JSONObject().put("pos", settings.position.key).put("format", settings.format.key)
            .put("start", settings.start).put("restart", settings.restart.key)
        return command("eoEngine.setNoteSettings($endnote, $json)")
    }

    /** Converts all footnotes, all endnotes, or both. */
    fun convertNotes(conversion: NoteConversion): EditorState = command("eoEngine.convertNotes(\"${conversion.key}\")")

    /** Removes all footnotes, all endnotes, or both. */
    fun removeNotes(footnotes: Boolean, endnotes: Boolean): EditorState = command("eoEngine.removeNotes($footnotes, $endnotes)")

    /** Back to the text from a footnote or endnote. */
    fun endNoteEditing(): EditorState = command("eoEngine.endNoteEditing()")

    fun addTableOfContents(): EditorState = command("eoEngine.addTableOfContents()")

    fun updateTableOfContents(): EditorState = command("eoEngine.updateTableOfContents()")

    fun outline(): List<OutlineItem> {
        val json = org.json.JSONArray(execute("eoEngine.getOutline()"))
        return (0 until json.length()).map {
            val item = json.getJSONObject(it)
            OutlineItem(item.getInt("index"), item.getString("text"), item.getInt("level"))
        }
    }

    /** Moves the cursor to the heading (its [OutlineItem.index]). */
    fun gotoOutline(index: Int): EditorState = command("eoEngine.gotoOutline($index)")

    fun statistics(): Statistics {
        val json = JSONObject(execute("eoEngine.getStatistics()"))
        return Statistics(json.getInt("pages"), json.getInt("words"), json.getInt("paragraphs"), json.getInt("symbols"), json.getInt("symbolsWithSpaces"))
    }

    /** The theme's color schemes (built-in and the document's) and the current one. */
    fun colorSchemes(): ColorSchemes {
        val json = JSONObject(execute("eoEngine.getColorSchemes()"))
        val schemes = json.getJSONArray("schemes")
        return ColorSchemes((0 until schemes.length()).map { i ->
            val scheme = schemes.getJSONObject(i)
            val colors = scheme.getJSONArray("colors")
            ColorScheme(scheme.getString("name"), (0 until colors.length()).map { colors.getString(it) })
        }, json.getInt("current"))
    }

    fun setColorScheme(index: Int): EditorState = command("eoEngine.setColorScheme($index)")

    /** The colors to choose from: the theme's (with the current color scheme) and the standard ones. */
    fun themeColors(): ThemeColors = ThemeColors.parse(execute("eoEngine.getThemeColors()"))

    /** Presentations: the slides' size. */
    fun slideSize(): PageSize {
        val json = JSONObject(execute("eoEngine.getSlideSize()"))
        return PageSize(json.getDouble("width"), json.getDouble("height"))
    }

    /** Presentations: 16:9 ([wide]) or 4:3 slides. */
    fun setSlideSize(wide: Boolean): EditorState = command("eoEngine.setSlideSize($wide)")

    /** Spreadsheets: the active sheet's gridlines and its pages' orientation and size. */
    fun sheetSetup(): SheetSetup {
        val json = JSONObject(execute("eoEngine.getSheetSetup()"))
        return SheetSetup(json.getBoolean("gridlines"), json.getBoolean("landscape"), json.getDouble("width"), json.getDouble("height"))
    }

    fun setGridlines(show: Boolean): EditorState = command("eoEngine.setGridlines($show)")

    /** Spreadsheets: the active sheet's pages' orientation, or size (as portrait, mm). */
    fun setSheetPage(landscape: Boolean? = null, width: Double? = null, height: Double? = null): EditorState {
        val setup = JSONObject()
        if (landscape != null) setup.put("landscape", landscape)
        if (width != null && height != null) setup.put("width", width).put("height", height)
        return command("eoEngine.setSheetPage($setup)")
    }

    fun setTrackChanges(value: Boolean): EditorState = command("eoEngine.setTrackChanges($value)")

    /** next, previous, accept, reject (the change at the cursor, then the next one), acceptAll, rejectAll. */
    fun reviewAction(action: String): EditorState = command("eoEngine.reviewAction(${JSONObject.quote(action)})")

    /** How tracked changes are shown: 0 marked up, 1 final, 2 original. */
    fun setReviewMode(mode: Int): EditorState = command("eoEngine.setReviewMode($mode)")

    /**
     * Follows the link at the cursor (the state's link): places in the document are shown
     * here; a web address comes back as the state's openUrl.
     */
    fun followLink(): EditorState = command("eoEngine.followLink()")

    fun undo(): EditorState = command("eoEngine.undo()")

    fun redo(): EditorState = command("eoEngine.redo()")

    // One command, so that the state reports the change of the entered cell too
    private fun enterScript(text: String?): String = when {
        text == null -> ""
        text.isEmpty() -> "eoEngine.clearCells(); "
        else -> "eoEngine.setCellText(${JSONObject.quote(text)}); "
    }

    override fun textBlocks(page: Int): List<TextBlock> {
        val json = org.json.JSONArray(execute("eoEngine.textBlocks($page)").ifEmpty { "[]" })
        return (0 until json.length()).map { index ->
            val block = json.getJSONObject(index)
            TextBlock(block.getString("text"), block.getDouble("left"), block.getDouble("top"), block.getDouble("right"), block.getDouble("bottom"))
        }
    }

    // ---------------------------------------------------------------------------------
    // Co-editing through a Document Server (see CoEditingConnection)

    /** Joins the session of an editor configuration (JSON: document, editorConfig, token, ...). */
    fun coOpen(config: String): EditorState = command("eoEngine.coOpen(${JSONObject.quote(config)})")

    /**
     * What sdkjs sends: [{type: "connect", options} | {type: "emit", name, data} | {type: "disconnect"} |
     * {type: "auth", auth: {token, session}} | {type: "reconnection", name, value} | {type: "fetch", url, name}].
     */
    fun coTakeOutbox(): String = execute("eoEngine.coTakeOutbox()").ifEmpty { "[]" }

    /** An event of the socket (connect, message, disconnect, connect_error), its arguments as a JSON array. */
    fun coReceive(name: String, args: String): EditorState =
        command("eoEngine.coReceive(${JSONObject.quote(name)}, ${JSONObject.quote(args)})")

    /** A file the server converted, downloaded for its URL (the document's Editor.bin). */
    fun coSetFile(url: String, path: String) {
        execute("eoEngine.coSetFile(${JSONObject.quote(url)}, ${JSONObject.quote(path)})")
    }

    /** sdkjs' timers while co-editing: layout, sending changes, applying the others'. */
    fun coTick(budgetMs: Int): EditorState = command("eoEngine.tick($budgetMs); eoEngine.coTick()")

    /**
     * Before leaving the session: sends the changes not sent yet (they go out with the outbox,
     * see [coTakeOutbox]). True while some still wait for the server's answer.
     */
    fun coFlush(): Boolean = execute("String(eoEngine.coFlush())") == "true"

    /** A picture of the document (a fetch of [coTakeOutbox]) was downloaded into [imagesDirectory]. */
    fun coImageLoaded(name: String): EditorState = command("eoEngine.coImageLoaded(${JSONObject.quote(name)})")

    /** Where to upload a picture to insert: the document's id on the server and the session's token. */
    fun coUploadInfo(): Pair<String, String> = JSONObject(execute("eoEngine.coUploadInfo()")).let {
        it.optString("documentId") to it.optString("token")
    }

    /** Names and URLs of uploaded pictures, as the server answers an upload ({"media/name.png": url}). */
    fun coAddUrls(urls: String) {
        execute("eoEngine.coAddUrls(${JSONObject.quote(urls)})")
    }

    /**
     * Whether the engine started again on the server's version of the document since the last
     * call: pages, languages and translations are to be set again.
     */
    fun coTakeReloaded(): Boolean = execute("String(eoEngine.coTakeReloaded())") == "true"

    /** Texts sdkjs puts into documents (table of contents, headers, ...), by their English text. */
    fun setTranslations(translations: Map<String, String>) {
        execute("eoEngine.setTranslations(${JSONObject(translations)})")
    }

    // ---------------------------------------------------------------------------------
    // Spell checking (documents and presentations)

    /** The languages (LCIDs) there are dictionaries for; checking starts again with them. */
    fun setSpellLanguages(languages: List<Int>): EditorState =
        command("eoEngine.setSpellLanguages(${org.json.JSONArray(languages)})")

    /**
     * Answers sdkjs' spelling requests with [checker], for at most [budgetMs] while more come
     * (sdkjs checks a few paragraphs at a time). [SpellingState.pending] of the state tells
     * whether to call this again.
     */
    fun checkSpelling(checker: SpellChecker, budgetMs: Int): EditorState {
        val end = android.os.SystemClock.uptimeMillis() + budgetMs
        do {
            val requests = execute("eoEngine.takeSpellRequests()")
            if (requests.isEmpty() || requests == "[]") break
            val results = checker.answer(requests)
            execute("try { eoEngine.spellResults(${JSONObject.quote(results)}); } catch (e) { eoEngine.reportError(e); }")
        } while (android.os.SystemClock.uptimeMillis() < end)
        return state()
    }

    /** Spell checking on or off (the red underlines). */
    fun setSpellCheck(on: Boolean): EditorState = command("eoEngine.setSpellCheck($on)")

    /** Replaces the misspelled word at the cursor ([SpellingState.word]) with [variant]. */
    fun replaceMisspelled(variant: String): EditorState = command("eoEngine.replaceMisspelled(${JSONObject.quote(variant)})")

    /** The misspelled word at the cursor isn't marked any more: here, or [all] in the document. */
    fun ignoreMisspelled(all: Boolean): EditorState = command("eoEngine.ignoreMisspelled($all)")

    // Commands sdkjs finishes in a promise (lists, styles, loading fonts): the state is read
    // in a second script, after V8 has run the promise
    private fun asyncCommand(script: String): EditorState {
        execute("try { $script; } catch (e) { eoEngine.reportError(e); }")
        return state()
    }

    private fun jsonStrings(json: String): List<String> =
        runCatching { org.json.JSONArray(json).let { array -> List(array.length()) { array.getString(it) } } }.getOrDefault(emptyList())

    private fun command(script: String): EditorState =
        EditorState.parse(execute("try { $script; } catch (e) { eoEngine.reportError(e); } eoEngine.getState()"))
}

/** Copied content: [internal] is empty when the selection can't be copied in sdkjs' format. */
data class ClipboardContent(val text: String, val internal: String)

/** Formats documents can be saved in, by extension; others (doc, ...) can only be opened. */
val WRITABLE_FORMATS = setOf(
    "docx", "docm", "dotx", "dotm", "docxf", "oform", "odt", "ott", "rtf", "txt", "html", "epub", "fb2",
    "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp",
    "xlsx", "xlsm", "xltx", "xltm", "ods", "ots", "csv",
    "pdf"
)

/** Z-order changes, with sdkjs' values (c_oAscDrawingLayerType). */
enum class DrawingOrder(val value: Int) { TO_FRONT(0), FORWARD(1), TO_BACK(2), BACKWARD(3) }

/** The direction of a shape's text (AscFormat.nVertTT*). */
enum class ShapeTextDirection(val value: Int) { HORIZONTAL(1), ROTATED_DOWN(3), ROTATED_UP(4) }

/** [EditorDocument.setTextAngle]'s angle for letters stacked from top to bottom. */
const val VERTICAL_TEXT_ANGLE = 255

enum class TouchAction(val value: Int) { DOWN(0), MOVE(1), UP(2) }
