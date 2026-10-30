package app.forkcast.ui.report

import app.forkcast.ui.format.ClockFormat
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
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
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What the report includes besides the meals. Over [MealReport.SUMMARY_AFTER_DAYS] days the PDF
 * is a summary, with every meal after it only when [detail] is on (owner, 2026-10-29); the
 * spreadsheet always has every row.
 */
data class ReportOptions(
    val from: LocalDate,
    val to: LocalDate,
    val format: ReportFormat,
    val weight: Boolean,
    val water: Boolean,
    val detail: Boolean = false,
) {
    val summary: Boolean get() = format == ReportFormat.PDF && MealReport.summaryByDefault(from, to)
    val meals: Boolean get() = !summary || detail
}

enum class ReportFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    SPREADSHEET("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

/**
 * The meal report's two files (owner, 2026-10-29), drawn from the same [MealReport.Report]: a
 * printable PDF (Android's PdfDocument, A4, no library) and a spreadsheet ([XlsxWriter]). Words
 * come from [res] (the app's language), amounts from [f] (the user's units); grams and mg stay
 * as they are. No photos and nothing about how an AI estimate was made, only that it is one.
 * `onProgress` gets 0..1 as days are written; it may throw to cancel.
 */
@Suppress("TooManyFunctions") // Two files and their parts, from one report.
class MealReportFiles(private val res: Resources, private val f: UnitFormatter, private val locale: Locale, is24Hour: Boolean = false) {

    private val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    private val shortDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val monthFormat = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)
    private val timeFormat = ClockFormat.formatter(locale, is24Hour)
    /** Weights in the summary keep their decimal ("173.0"), so a column lines up. */
    private val oneDecimal = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }

    fun write(out: OutputStream, report: MealReport.Report, options: ReportOptions, onProgress: (Float) -> Unit = {}) = when (options.format) {
        ReportFormat.PDF -> pdf(out, report, options, onProgress)
        ReportFormat.SPREADSHEET -> XlsxWriter.write(out, sheets(report, options, onProgress))
    }

    // ---- Spreadsheet: one row per item, and one per day.

    @Suppress("CyclomaticComplexMethod") // Two tables written column by column; the branches are optional cells.
    fun sheets(report: MealReport.Report, options: ReportOptions, onProgress: (Float) -> Unit = {}): List<Sheet> {
        val nutrients = MealReport.KEY_NUTRIENTS
        val energyHeader = res.getString(R.string.report_col_with_unit, res.getString(R.string.n_energy), f.energyUnitLabel)
        val progress = Progress(report.days.size * 2, onProgress)
        val items = buildList {
            add(
                listOf(
                    R.string.report_col_date, R.string.report_col_time, R.string.report_col_meal, R.string.report_col_food,
                    R.string.report_col_amount, R.string.report_col_grams,
                ).map { Cell.Text(res.getString(it)) } + Cell.Text(energyHeader) + nutrients.map { Cell.Text(nutrientHeader(it)) } +
                    Cell.Text(res.getString(R.string.report_col_estimate)),
            )
            for (d in report.days) {
                for (i in d.meals.flatMap { it.items }) {
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
                progress.step()
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
                progress.step()
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

    // ---- PDF: A4 pages; a summary for long ranges, then (or only) a day at a time.

    private fun pdf(out: OutputStream, report: MealReport.Report, options: ReportOptions, onProgress: (Float) -> Unit) {
        val doc = PdfDocument()
        val page = PdfPager(doc, res, locale)
        try {
            val progress = Progress(report.days.size * ((if (options.summary) 1 else 0) + (if (options.meals) 1 else 0)), onProgress)
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
            if (options.summary) {
                summary(page, report, options, progress)
                if (options.meals) {
                    page.breakPage()
                    page.text(res.getString(R.string.report_every_meal), size = 14f, bold = true)
                    page.space(4f)
                }
            }
            if (options.meals) days(page, report, options, progress)
            page.finish()
            doc.writeTo(out)
        } finally {
            // Canceled midway: a page is still open, and PdfDocument won't close with one.
            page.abandon()
            doc.close()
            out.close()
        }
    }

    @Suppress("CyclomaticComplexMethod") // The page read top to bottom; the branches are optional lines.
    private fun days(page: PdfPager, report: MealReport.Report, options: ReportOptions, progress: Progress) {
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
            progress.step()
        }
    }

    /** Weight over time, then monthly and weekly averages, then a line per day: totals against the targets. */
    private fun summary(page: PdfPager, report: MealReport.Report, options: ReportOptions, progress: Progress) {
        val weights = report.days.mapNotNull { d -> d.weightKg?.let { d.date to f.weightValue(it) } }
        if (options.weight && weights.size >= 2) {
            page.keepTogether(CHART_HEIGHT + 40f)
            page.text(withUnit(R.string.report_weight, f.weightUnitLabel), size = 12f, bold = true)
            page.lineChart(weights, report.from, report.to, { oneDecimal.format(it) }, { shortDate.format(it) }, CHART_HEIGHT)
            page.space(12f)
        }
        val periodHeader = listOf(res.getString(R.string.report_col_days_logged)) + valueHeaders(options)
        page.keepTogether(80f)
        page.text(res.getString(R.string.report_monthly), size = 12f, bold = true)
        page.text(res.getString(R.string.report_averages_note), size = 8.5f, gray = true)
        page.table(
            listOf(res.getString(R.string.report_col_month)) + periodHeader,
            columns(options, periods = true),
            MealReport.months(report).map { listOf(monthFormat.format(it.start), it.loggedDays.toString()) + periodValues(it, options) },
        )
        page.space(12f)
        page.keepTogether(80f)
        page.text(res.getString(R.string.report_weekly), size = 12f, bold = true)
        page.table(
            listOf(res.getString(R.string.report_col_week)) + periodHeader,
            columns(options, periods = true),
            MealReport.weeks(report, WeekFields.of(locale).firstDayOfWeek).map {
                listOf(shortDate.format(it.start), it.loggedDays.toString()) + periodValues(it, options)
            },
        )
        page.space(12f)
        page.keepTogether(80f)
        page.text(res.getString(R.string.report_daily), size = 12f, bold = true)
        page.table(
            listOf(res.getString(R.string.report_col_date)) + valueHeaders(options),
            columns(options, periods = false),
            report.days.map { d ->
                val logged = d.meals.isNotEmpty()
                val day = MealReport.Period(d.date, d.date, if (logged) 1 else 0, d.kcal.takeIf { logged }, d.targetKcal, d.nutrients, d.waterMl, d.weightKg)
                listOf(shortDate.format(d.date)) + periodValues(day, options)
            },
            onRow = progress::step,
        )
    }

    private fun valueHeaders(options: ReportOptions): List<String> =
        listOf(
            res.getString(R.string.report_col_with_unit, res.getString(R.string.n_energy), f.energyUnitLabel),
            res.getString(R.string.report_col_target_energy, f.energyUnitLabel),
        ) + SUMMARY_NUTRIENTS.map { nutrientHeader(it) } +
            (if (options.water) listOf(withUnit(R.string.report_water, f.waterUnitLabel)) else emptyList()) +
            (if (options.weight) listOf(withUnit(R.string.report_weight, f.weightUnitLabel)) else emptyList())

    private fun periodValues(p: MealReport.Period, options: ReportOptions): List<String> =
        listOf(p.kcal?.let { f.energyNumber(it) }.orNone(), p.targetKcal?.let { f.energyNumber(it) }.orNone()) +
            SUMMARY_NUTRIENTS.map { k -> p.nutrients[k]?.let { f.number(it) }.orNone() } +
            (if (options.water) listOf(p.waterMl?.let { f.number(f.waterValue(it)) }.orNone()) else emptyList()) +
            (if (options.weight) listOf(p.weightKg?.let { oneDecimal.format(f.weightValue(it)) }.orNone()) else emptyList())

    /** Column widths, relative: the first (date, week, month) wider; periods add "Days logged". */
    private fun columns(options: ReportOptions, periods: Boolean): List<Float> =
        listOf(1.6f) + (if (periods) listOf(1f) else emptyList()) + List(2 + SUMMARY_NUTRIENTS.size) { 1f } +
            (if (options.water) listOf(1f) else emptyList()) + (if (options.weight) listOf(1f) else emptyList())

    private fun String?.orNone() = this ?: "–"

    private fun grams(g: Double) = UnitFormatter.trimNumber(g, if (g < 10) 1 else 0) + " g"

    private fun nutrientLine(k: NutrientKey, value: Double, target: Double?): String {
        val unit = if (k.unit == NutrientUnit.MG) "mg" else "g"
        val v = UnitFormatter.trimNumber(value, 0)
        val amount = target?.let { "$v / ${UnitFormatter.trimNumber(it, 0)} $unit" } ?: "$v $unit"
        return res.getString(R.string.report_nutrient_amount, res.getString(nutrientNameRes(k)), amount)
    }

    /** Counts days written and reports the share done. */
    private class Progress(private val total: Int, private val onProgress: (Float) -> Unit) {
        private var done = 0

        fun step() {
            done++
            if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
        }
    }

    private companion object {
        /** The summary's columns: energy and target, then these (fewer than a day's, so the table fits A4). */
        val SUMMARY_NUTRIENTS = listOf(NutrientKey.PROTEIN_G, NutrientKey.CARBS_G, NutrientKey.FAT_G, NutrientKey.FIBER_G, NutrientKey.SODIUM_MG)
        const val CHART_HEIGHT = 150f
    }
}

/** Lays text down A4 pages (595 × 842 pt, 40 pt margins), starting a new page when one is full, with page numbers. */
@Suppress("TooManyFunctions") // Small drawing steps: text, rules, tables, a chart.
private class PdfPager(private val doc: PdfDocument, private val res: Resources, private val locale: Locale) {
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

    fun breakPage() {
        newPage()
    }

    fun space(points: Float) {
        y += points
    }

    fun rule() {
        val p = current()
        p.canvas.drawLine(MARGIN, y, WIDTH - MARGIN, y, Paint().apply { color = LIGHT; strokeWidth = 0.5f })
        y += 6f
    }

    private fun paint(size: Float, bold: Boolean = false, gray: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = if (gray) GRAY else BLACK
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(text: String, paint: TextPaint, width: Int, alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, hyphenate: Boolean = false) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1)).setAlignment(alignment)
            .setHyphenationFrequency(if (hyphenate) Layout.HYPHENATION_FREQUENCY_FULL else Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    fun text(text: String, size: Float, bold: Boolean = false, gray: Boolean = false, indent: Float = 0f) {
        val layout = layout(text, paint(size, bold, gray), (WIDTH - 2 * MARGIN - indent).toInt())
        if (page == null || y + layout.height > HEIGHT - MARGIN) newPage()
        val canvas = current().canvas
        canvas.save()
        canvas.translate(MARGIN + indent, y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height + size * 0.35f
    }

    /**
     * A table across the page: [header] (wrapped, repeated at the top of each new page), then one
     * line per row, every other one shaded; columns after the first are right-aligned numbers.
     */
    fun table(header: List<String>, weights: List<Float>, rows: List<List<String>>, onRow: () -> Unit = {}) {
        val total = weights.sum()
        val widths = weights.map { (WIDTH - 2 * MARGIN) * it / total }
        // Narrow columns: long words ("Carboidratos", "registrados") are hyphenated in the report's language.
        val headerPaint = paint(TABLE_TEXT, bold = true).apply { textLocale = locale }
        val headers = header.mapIndexed { i, h ->
            layout(h, headerPaint, (widths[i] - CELL_PAD * 2).toInt(), if (i == 0) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE, hyphenate = true)
        }
        val headerHeight = headers.maxOf { it.height } + CELL_PAD * 2
        fun drawHeader() {
            if (page == null || y + headerHeight + ROW > HEIGHT - MARGIN) newPage()
            val canvas = current().canvas
            var x = MARGIN
            headers.forEachIndexed { i, l ->
                canvas.save()
                canvas.translate(x + CELL_PAD, y + CELL_PAD)
                l.draw(canvas)
                canvas.restore()
                x += widths[i]
            }
            y += headerHeight
            canvas.drawLine(MARGIN, y, WIDTH - MARGIN, y, Paint().apply { color = GRAY; strokeWidth = 0.5f })
        }
        drawHeader()
        val cellPaint = paint(TABLE_TEXT)
        val shade = Paint().apply { color = SHADE }
        rows.forEachIndexed { r, row ->
            if (y + ROW > HEIGHT - MARGIN) {
                newPage()
                drawHeader()
            }
            val canvas = current().canvas
            if (r % 2 == 1) canvas.drawRect(MARGIN, y, WIDTH - MARGIN, y + ROW, shade)
            var x = MARGIN
            val baseline = y + ROW - (ROW - TABLE_TEXT) / 2 - 1f
            row.forEachIndexed { i, cell ->
                val text = TextUtils.ellipsize(cell, cellPaint, widths[i] - CELL_PAD * 2, TextUtils.TruncateAt.END).toString()
                val left = if (i == 0) x + CELL_PAD else x + widths[i] - CELL_PAD - cellPaint.measureText(text)
                canvas.drawText(text, left, baseline, cellPaint)
                x += widths[i]
            }
            y += ROW
            onRow()
        }
        y += 4f
    }

    /** A line through [points] (date, value) across [from]..[to], with the lowest and highest value and the two end dates. */
    @Suppress("LongParameterList") // One chart, drawn in place.
    fun lineChart(points: List<Pair<LocalDate, Double>>, from: LocalDate, to: LocalDate, value: (Double) -> String, date: (LocalDate) -> String, height: Float) {
        if (page == null || y + height + 20f > HEIGHT - MARGIN) newPage()
        val canvas = current().canvas
        val labelPaint = paint(TABLE_TEXT, gray = true)
        val lo = points.minOf { it.second }
        val hi = points.maxOf { it.second }
        val pad = ((hi - lo) * 0.1).coerceAtLeast(0.5)
        val min = lo - pad
        val max = hi + pad
        val left = MARGIN + AXIS
        val right = WIDTH - MARGIN
        val top = y + 6f
        val bottom = y + height
        val span = (to.toEpochDay() - from.toEpochDay()).coerceAtLeast(1).toFloat()
        fun px(d: LocalDate) = left + (right - left) * (d.toEpochDay() - from.toEpochDay()) / span
        fun py(v: Double) = (bottom - (bottom - top) * ((v - min) / (max - min))).toFloat()
        val grid = Paint().apply { color = LIGHT; strokeWidth = 0.5f }
        canvas.drawLine(left, bottom, right, bottom, grid)
        canvas.drawLine(left, py(hi), right, py(hi), grid)
        canvas.drawLine(left, py(lo), right, py(lo), grid)
        canvas.drawText(value(hi), MARGIN, py(hi) + TABLE_TEXT / 3, labelPaint)
        canvas.drawText(value(lo), MARGIN, py(lo) + TABLE_TEXT / 3, labelPaint)
        val line = Path()
        points.forEachIndexed { i, (d, v) -> if (i == 0) line.moveTo(px(d), py(v)) else line.lineTo(px(d), py(v)) }
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PINE; strokeWidth = 1.5f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND })
        if (points.size <= DOTS_UP_TO) {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PINE }
            points.forEach { (d, v) -> canvas.drawCircle(px(d), py(v), 2f, dot) }
        }
        canvas.drawText(date(from), left, bottom + 12f, labelPaint)
        val end = date(to)
        canvas.drawText(end, right - labelPaint.measureText(end), bottom + 12f, labelPaint)
        y = bottom + 20f
    }

    fun finish() {
        page?.let { finishPage(it) } ?: run { newPage(); page?.let { finishPage(it) } }
        page = null
    }

    /** Ends a page left open by a stop midway, so the document can close; a no-op after [finish]. */
    fun abandon() {
        page?.let { doc.finishPage(it) }
        page = null
    }

    private companion object {
        const val WIDTH = 595
        const val HEIGHT = 842
        const val MARGIN = 40f
        const val TABLE_TEXT = 7.5f
        const val ROW = 12f
        const val CELL_PAD = 2.5f
        const val AXIS = 34f
        const val DOTS_UP_TO = 40
        const val BLACK = 0xFF14201B.toInt()
        const val GRAY = 0xFF5E6B65.toInt()
        const val LIGHT = 0xFFC9D3CE.toInt()
        const val SHADE = 0xFFF1F4F2.toInt()
        const val PINE = 0xFF0F7B5F.toInt()
    }
}
