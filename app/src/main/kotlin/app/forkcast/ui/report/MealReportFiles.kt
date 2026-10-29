package app.forkcast.ui.report

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.forkcast.R
import app.forkcast.core.data.report.Cell
import app.forkcast.core.data.report.Sheet
import app.forkcast.core.data.report.XlsxWriter
import app.forkcast.core.domain.MealReport
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.NutrientUnit
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.today.nutrientNameRes
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the report includes besides the meals. */
data class ReportOptions(val from: LocalDate, val to: LocalDate, val format: ReportFormat, val weight: Boolean, val water: Boolean)

enum class ReportFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    SPREADSHEET("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

/**
 * The meal report's two files (owner, 2026-10-29), drawn from the same [MealReport.Report]: a
 * printable PDF (Android's PdfDocument, A4, no library) and a spreadsheet ([XlsxWriter]). Words
 * come from [res] (the app's language), amounts from [f] (the user's units); grams and mg stay
 * as they are. No photos and nothing about how an AI estimate was made, only that it is one.
 */
class MealReportFiles(private val res: Resources, private val f: UnitFormatter, private val locale: Locale) {

    private val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    private val shortDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    fun write(out: OutputStream, report: MealReport.Report, options: ReportOptions) = when (options.format) {
        ReportFormat.PDF -> pdf(out, report, options)
        ReportFormat.SPREADSHEET -> XlsxWriter.write(out, sheets(report, options))
    }

    // ---- Spreadsheet: one row per item, and one per day.

    @Suppress("CyclomaticComplexMethod") // Two tables written column by column; the branches are optional cells.
    fun sheets(report: MealReport.Report, options: ReportOptions): List<Sheet> {
        val nutrients = MealReport.KEY_NUTRIENTS
        val energyHeader = res.getString(R.string.report_col_with_unit, res.getString(R.string.n_energy), f.energyUnitLabel)
        val items = buildList {
            add(
                listOf(
                    R.string.report_col_date, R.string.report_col_time, R.string.report_col_meal, R.string.report_col_food,
                    R.string.report_col_amount, R.string.report_col_grams,
                ).map { Cell.Text(res.getString(it)) } + Cell.Text(energyHeader) + nutrients.map { Cell.Text(nutrientHeader(it)) } +
                    Cell.Text(res.getString(R.string.report_col_estimate)),
            )
            for (i in report.items) {
                add(
                    listOf(
                        Cell.Text(i.date.toString()),
                        i.minute?.let { Cell.Text(LocalTime.of(it / 60, it % 60).toString()) },
                        Cell.Text(i.meal),
                        Cell.Text(i.name),
                        Cell.Text(amount(i)),
                        i.grams?.let { Cell.Number(it) },
                        Cell.Number(f.energyValue(i.kcal)),
                    ) + nutrients.map { k -> i.nutrients[k]?.let { Cell.Number(it) } } +
                        (if (i.estimate) Cell.Text(res.getString(R.string.report_yes)) else null),
                )
            }
        }
        val days = buildList {
            add(
                listOf(Cell.Text(res.getString(R.string.report_col_date)), Cell.Text(energyHeader)) +
                    Cell.Text(res.getString(R.string.report_col_target_energy, f.energyUnitLabel)) +
                    nutrients.map { Cell.Text(nutrientHeader(it)) } +
                    (if (options.water) listOf(Cell.Text(withUnit(R.string.report_water, f.waterUnitLabel))) else emptyList()) +
                    (if (options.weight) listOf(Cell.Text(withUnit(R.string.report_weight, f.weightUnitLabel))) else emptyList()),
            )
            for (d in report.days) {
                add(
                    listOf(Cell.Text(d.date.toString()), Cell.Number(f.energyValue(d.kcal)), d.targetKcal?.let { Cell.Number(f.energyValue(it)) }) +
                        nutrients.map { k -> d.nutrients[k]?.let { Cell.Number(it) } } +
                        (if (options.water) listOf(d.waterMl?.let { Cell.Number(f.waterValue(it)) }) else emptyList()) +
                        (if (options.weight) listOf(d.weightKg?.let { Cell.Number(f.weightValue(it)) }) else emptyList()),
                )
            }
        }
        return listOf(Sheet(res.getString(R.string.report_sheet_items), items), Sheet(res.getString(R.string.report_sheet_days), days))
    }

    private fun withUnit(name: Int, unit: String) = res.getString(R.string.report_col_with_unit, res.getString(name), unit)

    private fun nutrientHeader(k: NutrientKey) =
        res.getString(R.string.report_col_with_unit, res.getString(nutrientNameRes(k)), if (k.unit == NutrientUnit.MG) "mg" else "g")

    /** "1 cup", "1.5 × 1 medium", "180 g" (Forkcast's own labels in the app's language). */
    private fun amount(i: MealReport.Item): String {
        val label = when (i.servingLabel) {
            "serving" -> res.getString(R.string.serving_label_serving)
            "meal" -> res.getString(R.string.serving_label_meal)
            else -> i.servingLabel
        }
        val qty = UnitFormatter.trimNumber(i.quantity, 2)
        return when {
            i.servingLabel in setOf("g", "oz", "ml", "fl oz") -> "$qty $label"
            i.quantity == 1.0 -> label
            else -> "$qty × $label"
        }
    }

    // ---- PDF: A4 pages, a day at a time.

    @Suppress("CyclomaticComplexMethod") // The page read top to bottom; the branches are optional lines.
    private fun pdf(out: OutputStream, report: MealReport.Report, options: ReportOptions) {
        val doc = PdfDocument()
        val page = PdfPager(doc, res)
        page.text(res.getString(R.string.report_title), size = 20f, bold = true)
        page.text(res.getString(R.string.report_range, shortDate.format(report.from), shortDate.format(report.to)), size = 12f)
        page.text(res.getString(R.string.report_note), size = 9f, gray = true)
        val logged = report.days.filter { it.meals.isNotEmpty() }
        if (logged.isNotEmpty()) {
            page.text(
                res.getString(R.string.report_summary, logged.size, report.days.size, f.energy(logged.sumOf { it.kcal } / logged.size)),
                size = 11f,
            )
        }
        page.space(10f)
        for (d in report.days) {
            page.keepTogether(60f)
            page.rule()
            val total = d.targetKcal?.let { res.getString(R.string.report_day_total_target, f.energy(d.kcal), f.energy(it)) } ?: f.energy(d.kcal)
            page.text("${dayFormat.format(d.date)} · $total", size = 12f, bold = true)
            if (d.meals.isEmpty()) {
                page.text(res.getString(R.string.report_nothing_logged), size = 10f, gray = true)
            } else {
                // A nutrient no logged food reported is left out, not shown as 0 (the spreadsheet leaves its cell empty).
                val known = MealReport.KEY_NUTRIENTS.mapNotNull { k -> d.nutrients[k]?.let { nutrientLine(k, it, d.targets[k]) } }
                page.text(known.joinToString(" · "), size = 9f, gray = true)
            }
            val extras = listOfNotNull(
                d.waterMl?.takeIf { options.water }?.let { res.getString(R.string.report_water_line, f.waterLiters(it)) },
                d.weightKg?.takeIf { options.weight }?.let { res.getString(R.string.report_weight_line, f.weight(it)) },
            )
            if (extras.isNotEmpty()) page.text(extras.joinToString(" · "), size = 9f, gray = true)
            for (m in d.meals) {
                page.keepTogether(30f)
                page.text("${m.name} · ${f.energy(m.kcal)}", size = 10.5f, bold = true, indent = 8f)
                for (i in m.items) {
                    val time = i.minute?.let { timeFormat.format(LocalTime.of(it / 60, it % 60)) + "  " }.orEmpty()
                    val macros = listOf(NutrientKey.PROTEIN_G, NutrientKey.CARBS_G, NutrientKey.FAT_G)
                        .joinToString(" · ") { k -> res.getString(R.string.report_nutrient_amount, res.getString(nutrientNameRes(k)), grams(i.nutrients[k] ?: 0.0)) }
                    val estimate = if (i.estimate) " · " + res.getString(R.string.report_estimate) else ""
                    val grams = i.grams?.takeIf { i.servingLabel !in setOf("g", "ml", "fl oz") }?.let { " (${f.grams(it)})" }.orEmpty()
                    page.text("$time${i.name} · ${amount(i)}$grams · ${f.energy(i.kcal)} · $macros$estimate", size = 9.5f, indent = 16f)
                }
            }
            page.space(6f)
        }
        page.finish()
        doc.writeTo(out)
        doc.close()
        out.close()
    }

    private fun grams(g: Double) = UnitFormatter.trimNumber(g, if (g < 10) 1 else 0) + " g"

    private fun nutrientLine(k: NutrientKey, value: Double, target: Double?): String {
        val unit = if (k.unit == NutrientUnit.MG) "mg" else "g"
        val v = UnitFormatter.trimNumber(value, 0)
        val amount = target?.let { "$v / ${UnitFormatter.trimNumber(it, 0)} $unit" } ?: "$v $unit"
        return res.getString(R.string.report_nutrient_amount, res.getString(nutrientNameRes(k)), amount)
    }
}

/** Lays text down A4 pages (595 × 842 pt, 40 pt margins), starting a new page when one is full, with page numbers. */
private class PdfPager(private val doc: PdfDocument, private val res: Resources) {
    private var number = 0
    private var page: PdfDocument.Page? = null
    private var y = 0f

    private fun current(): PdfDocument.Page = page ?: newPage()

    private fun newPage(): PdfDocument.Page {
        page?.let { finishPage(it) }
        number++
        return doc.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, number).create()).also { page = it; y = MARGIN }
    }

    private fun finishPage(p: PdfDocument.Page) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = GRAY }
        val label = res.getString(R.string.report_page, number)
        p.canvas.drawText(label, WIDTH - MARGIN - paint.measureText(label), HEIGHT - MARGIN / 2, paint)
        doc.finishPage(p)
    }

    /** Starts a new page unless [height] more points fit, so a heading isn't left alone at the bottom. */
    fun keepTogether(height: Float) {
        if (page != null && y + height > HEIGHT - MARGIN) newPage()
    }

    fun space(points: Float) {
        y += points
    }

    fun rule() {
        val p = current()
        p.canvas.drawLine(MARGIN, y, WIDTH - MARGIN, y, Paint().apply { color = LIGHT; strokeWidth = 0.5f })
        y += 6f
    }

    fun text(text: String, size: Float, bold: Boolean = false, gray: Boolean = false, indent: Float = 0f) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = if (gray) GRAY else BLACK
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = (WIDTH - 2 * MARGIN - indent).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        if (page == null || y + layout.height > HEIGHT - MARGIN) newPage()
        val canvas = current().canvas
        canvas.save()
        canvas.translate(MARGIN + indent, y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height + size * 0.35f
    }

    fun finish() {
        page?.let { finishPage(it) } ?: run { newPage(); page?.let { finishPage(it) } }
        page = null
    }

    private companion object {
        const val WIDTH = 595
        const val HEIGHT = 842
        const val MARGIN = 40f
        const val BLACK = 0xFF14201B.toInt()
        const val GRAY = 0xFF5E6B65.toInt()
        const val LIGHT = 0xFFC9D3CE.toInt()
    }
}
