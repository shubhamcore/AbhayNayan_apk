package `in`.abhaynayan.app.services

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import `in`.abhaynayan.app.interop.ReportDocument
import `in`.abhaynayan.app.interop.ReportSection
import `in`.abhaynayan.app.interop.Tone
import java.io.File

/**
 * Renders a [ReportDocument] to an A4 PDF in the app's private cache (`cache/reports/`), shared through a
 * FileProvider. Only the latest report is kept; it contains no photo or nickname. Call off the main thread.
 */
object PdfReportWriter {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val LABEL_W = 165f

    private val NAVY = Color.rgb(0x12, 0x36, 0x42)
    private val TEAL = Color.rgb(0x0A, 0x6F, 0x6B)
    private val BODY = Color.rgb(0x3E, 0x56, 0x5C)
    private val MUTED = Color.rgb(0x5A, 0x6F, 0x74)
    private val LINE = Color.rgb(0xD5, 0xE2, 0xE3)

    fun reportsDir(context: Context): File = File(context.cacheDir, "reports")

    fun clear(context: Context) { reportsDir(context).listFiles()?.forEach { it.delete() } }

    fun write(context: Context, doc: ReportDocument, recordId: String): File {
        val dir = reportsDir(context).apply { mkdirs() }
        clear(context)
        val file = File(dir, "AbhayNayan-report-${recordId.filter { it.isLetterOrDigit() || it == '-' }.take(40)}.pdf")
        val pdf = PdfDocument()
        try {
            Renderer(pdf).render(doc)
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return file
    }

    private class Renderer(private val pdf: PdfDocument) {
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private lateinit var canvas: Canvas
        private var y = 0f

        private val title = paint(19f, NAVY, bold = true)
        private val subtitle = paint(9.5f, Color.WHITE)
        private val heading = paint(12f, TEAL, bold = true)
        private val label = paint(9.5f, MUTED)
        private val value = paint(10.5f, NAVY, bold = true)
        private val body = paint(10.5f, BODY)
        private val small = paint(8.5f, MUTED)

        private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        private fun layout(text: String, p: TextPaint, width: Float): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()

        private fun newPage() {
            page?.let { pdf.finishPage(it) }
            pageNumber++
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            page = p
            canvas = p.canvas
            y = MARGIN
            if (pageNumber > 1) {
                canvas.drawText("AbhayNayan · Screening report (continued) · page $pageNumber", MARGIN, y, small)
                y += 16f
            }
        }

        private fun ensure(height: Float) { if (y + height > PAGE_H - MARGIN - 20f) newPage() }

        private fun draw(l: StaticLayout, x: Float) {
            canvas.save(); canvas.translate(x, y); l.draw(canvas); canvas.restore()
        }

        fun render(doc: ReportDocument) {
            newPage()
            // Header band
            val sub = layout(doc.subtitle, subtitle, CONTENT_W - 24f)
            val bandH = 46f + sub.height
            canvas.drawRoundRect(RectF(MARGIN, y, PAGE_W - MARGIN, y + bandH), 10f, 10f, Paint().apply { color = NAVY })
            canvas.drawText(doc.title, MARGIN + 12f, y + 28f, title.apply { color = Color.WHITE })
            y += 38f
            draw(sub, MARGIN + 12f)
            y += sub.height + 18f
            title.color = NAVY

            doc.sections.forEach { section(it) }

            ensure(30f)
            val footer = layout(doc.footer, small, CONTENT_W)
            draw(footer, MARGIN)
            page?.let { pdf.finishPage(it) }
            page = null
        }

        private fun section(s: ReportSection) {
            val (bg, border, textColor) = when (s.tone) {
                Tone.NORMAL -> Triple(Color.WHITE, LINE, BODY)
                Tone.DEMO -> Triple(Color.rgb(0xF0, 0xEA, 0xFA), Color.rgb(0x5B, 0x3E, 0x8E), Color.rgb(0x5B, 0x3E, 0x8E))
                Tone.WARNING -> Triple(Color.rgb(0xFF, 0xF4, 0xDF), Color.rgb(0xE9, 0xD7, 0xAE), Color.rgb(0x6A, 0x4B, 0x16))
                Tone.URGENT -> Triple(Color.rgb(0xFD, 0xEC, 0xEA), Color.rgb(0x8A, 0x1F, 0x17), Color.rgb(0x8A, 0x1F, 0x17))
            }
            val inner = CONTENT_W - 24f
            val head = layout(s.heading, heading.apply { color = if (s.tone == Tone.NORMAL) TEAL else textColor }, inner)
            val rows = s.rows.map { (k, v) -> layout(k, label, LABEL_W - 8f) to layout(v, value, inner - LABEL_W) }
            val paras = s.paragraphs.map { layout(it, body.apply { color = textColor }, inner) }
            val rowsH = rows.sumOf { (a, b) -> (maxOf(a.height, b.height) + 6).toDouble() }.toFloat()
            val parasH = paras.sumOf { (it.height + 5).toDouble() }.toFloat()
            val total = 14f + head.height + 6f + rowsH + parasH + 8f

            if (total <= PAGE_H - 2 * MARGIN - 40f) {
                ensure(total)
                canvas.drawRoundRect(RectF(MARGIN, y, PAGE_W - MARGIN, y + total), 8f, 8f, Paint().apply { color = bg })
                canvas.drawRoundRect(RectF(MARGIN, y, PAGE_W - MARGIN, y + total), 8f, 8f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = border })
            }
            // Very long sections (e.g. counselling) flow across pages without a box.
            y += 10f
            ensure(head.height.toFloat()); draw(head, MARGIN + 12f); y += head.height + 6f
            rows.forEach { (a, b) ->
                val h = maxOf(a.height, b.height).toFloat()
                ensure(h); draw(a, MARGIN + 12f); draw(b, MARGIN + 12f + LABEL_W); y += h + 6f
            }
            paras.forEach { p -> ensure(p.height.toFloat()); draw(p, MARGIN + 12f); y += p.height + 5f }
            y += 12f
            body.color = BODY
        }
    }
}
