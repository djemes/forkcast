package app.forkcast.core.data.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class XlsxWriterTest {

    private fun parts(bytes: ByteArray): Map<String, String> = ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes().decodeToString() }
    }

    @Test fun writesTheWorkbookPartsAndOneSheetEach() {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(
            out,
            listOf(
                Sheet("Items", listOf(listOf(Cell.Text("Food"), Cell.Text("kcal")), listOf(Cell.Text("Rice & beans <1>"), Cell.Number(205.456)))),
                Sheet("Daily totals", listOf(listOf(Cell.Text("Date")))),
            ),
        )
        val p = parts(out.toByteArray())
        assertThat(p.keys).containsAtLeast(
            "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
            "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml",
        )
        assertThat(p.getValue("xl/workbook.xml")).contains("""<sheet name="Daily totals" sheetId="2" r:id="rId2"/>""")
        val sheet1 = p.getValue("xl/worksheets/sheet1.xml")
        // Text is escaped, numbers are numbers (rounded to 2 decimals), the header is bold.
        assertThat(sheet1).contains("Rice &amp; beans &lt;1&gt;")
        assertThat(sheet1).contains("""<c r="B2"><v>205.46</v></c>""")
        assertThat(sheet1).contains("""<c r="A1" t="inlineStr" s="1">""")
    }

    @Test fun columnNames() {
        assertThat(XlsxWriter.column(0)).isEqualTo("A")
        assertThat(XlsxWriter.column(25)).isEqualTo("Z")
        assertThat(XlsxWriter.column(26)).isEqualTo("AA")
        assertThat(XlsxWriter.column(27)).isEqualTo("AB")
    }

    @Test fun controlCharactersAreDropped() {
        assertThat(XlsxWriter.escape("a\u0001b\tc")).isEqualTo("ab\tc")
    }
}
