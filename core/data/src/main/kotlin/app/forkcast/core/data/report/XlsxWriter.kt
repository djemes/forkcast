@file:Suppress("MaxLineLength") // Open XML namespaces, content types and the style sheet are long fixed strings.

package app.forkcast.core.data.report

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A cell: text, or a number (written as a number, so spreadsheet apps can sum it). Null is an empty cell. */
sealed interface Cell {
    data class Text(val value: String) : Cell
    data class Number(val value: Double) : Cell
}

data class Sheet(val name: String, val rows: List<List<Cell?>>)

/**
 * The smallest valid .xlsx (Office Open XML spreadsheet) for the meal report (owner, 2026-10-29):
 * a zip of a few XML parts, one worksheet per [Sheet], text as inline strings, the first row
 * bold as headers. No library: Apache POI is far too big for a phone, and this is all the report
 * needs. Opens in Excel, Google Sheets, LibreOffice and Numbers.
 */
object XlsxWriter {

    fun write(out: OutputStream, sheets: List<Sheet>) {
        require(sheets.isNotEmpty())
        ZipOutputStream(out).use { zip ->
            fun part(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.encodeToByteArray())
                zip.closeEntry()
            }
            part("[Content_Types].xml", contentTypes(sheets.size))
            part("_rels/.rels", ROOT_RELS)
            part("xl/workbook.xml", workbook(sheets))
            part("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            part("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, sheet -> part("xl/worksheets/sheet${i + 1}.xml", worksheet(sheet)) }
        }
    }

    /** "A", "B", … "Z", "AA" for the 0-based column [i]. */
    fun column(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                // XML 1.0 can't hold most control characters; a food name never needs them.
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> append(c)
            }
        }
    }

    private fun worksheet(sheet: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        // The header row stays in view while scrolling.
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, cell ->
                val ref = column(c) + (r + 1)
                val style = if (r == 0) """ s="1"""" else ""
                when (cell) {
                    is Cell.Text -> append("""<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">${escape(cell.value)}</t></is></c>""")
                    is Cell.Number -> if (cell.value.isFinite()) append("""<c r="$ref"$style><v>${number(cell.value)}</v></c>""")
                    null -> Unit
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /** Plain decimal, never "1.0E-4", at most 2 decimals (the report rounds for people). */
    private fun number(v: Double): String = java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun contentTypes(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..n) append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        append("</Types>")
    }

    private fun workbook(sheets: List<Sheet>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, s ->
            // Sheet names: at most 31 characters, none of : \ / ? * [ ].
            val name = escape(s.name.replace(Regex("""[:\\/?*\[\]]"""), " ").take(31))
            append("""<sheet name="$name" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..n) append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        append("""<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    /** Style 0: normal; style 1: bold (the header row). */
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>"""
}
