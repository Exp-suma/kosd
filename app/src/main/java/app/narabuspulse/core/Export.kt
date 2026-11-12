package app.narabuspulse.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** ファイルの書き出し（Web のダウンロードに相当。端末の「ダウンロード」フォルダに保存する）。 */
object Export {
    private fun escapeCell(v: Any?): String {
        val t = v?.toString().orEmpty()
        return if (Regex("[\",\\n\\r]").containsMatchIn(t)) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }

    /** Web の downloadCsv と同じ形式（UTF-8 BOM 付き・CRLF）で保存する。保存先の表示名を返す。 */
    suspend fun csv(context: Context, fileName: String, headers: List<String>, rows: List<List<Any?>>): String {
        val text = "﻿" + (listOf(headers.joinToString(",") { escapeCell(it) }) + rows.map { r -> r.joinToString(",") { escapeCell(it) } }).joinToString("\r\n")
        return save(context, fileName, "text/csv", text.toByteArray(Charsets.UTF_8))
    }

    suspend fun text(context: Context, fileName: String, mime: String, text: String): String =
        save(context, fileName, mime, text.toByteArray(Charsets.UTF_8))

    suspend fun save(context: Context, fileName: String, mime: String, bytes: ByteArray): String = saveStream(context, fileName, mime) { it.write(bytes) }

    /** 大きなファイル向け：出力先へ直接書き込む（メモリに全体を載せない）。 */
    suspend fun saveStream(context: Context, fileName: String, mime: String, write: (java.io.OutputStream) -> Unit): String = withContext(Dispatchers.IO) {
        val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("保存できませんでした")
            context.contentResolver.openOutputStream(uri)?.use(write) ?: throw java.io.IOException("保存できませんでした")
        } else {
            // Android 9 以前はアプリ専用のダウンロードフォルダへ（権限不要）
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            File(dir, safe).outputStream().use(write)
        }
        "ダウンロード/$safe"
    }
}

/** 表を A4 の PDF にする（Web の PlanSheetExport：html2canvas + jsPDF の代わり）。 */
object PdfTable {
    suspend fun save(context: Context, fileName: String, title: String, subtitle: String, header: List<String>, rows: List<List<String>>): String {
        val bytes = kotlinx.coroutines.withContext(Dispatchers.Default) {
            val doc = android.graphics.pdf.PdfDocument()
            val pageW = 595; val pageH = 842; val margin = 24f
            val titlePaint = android.graphics.Paint().apply { textSize = 14f; isFakeBoldText = true; isAntiAlias = true }
            val subPaint = android.graphics.Paint().apply { textSize = 8f; color = android.graphics.Color.DKGRAY; isAntiAlias = true }
            val cellPaint = android.graphics.Paint().apply { textSize = 7.5f; isAntiAlias = true }
            val headPaint = android.graphics.Paint().apply { textSize = 7.5f; isFakeBoldText = true; isAntiAlias = true }
            val line = android.graphics.Paint().apply { color = android.graphics.Color.LTGRAY; strokeWidth = 0.5f }
            val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 1)
            val colW = (pageW - margin * 2) / cols
            val rowH = 13f
            fun clip(t: String, p: android.graphics.Paint): String {
                var s = t
                while (s.isNotEmpty() && p.measureText(s) > colW - 4) s = s.dropLast(1)
                return if (s.length < t.length && s.isNotEmpty()) s.dropLast(1) + "…" else s
            }
            var pageNo = 0
            var i = 0
            while (i < rows.size || pageNo == 0) {
                pageNo++
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                val c = page.canvas
                var y = margin + 14f
                if (pageNo == 1) {
                    c.drawText(title, margin, y, titlePaint); y += 12f
                    c.drawText(subtitle, margin, y, subPaint); y += 12f
                }
                header.forEachIndexed { k, h -> c.drawText(clip(h, headPaint), margin + colW * k + 2, y, headPaint) }
                y += 4f; c.drawLine(margin, y, pageW - margin, y, line); y += rowH - 3
                while (i < rows.size && y < pageH - margin) {
                    rows[i].forEachIndexed { k, v -> c.drawText(clip(v, cellPaint), margin + colW * k + 2, y, cellPaint) }
                    c.drawLine(margin, y + 3, pageW - margin, y + 3, line)
                    y += rowH; i++
                }
                doc.finishPage(page)
                if (rows.isEmpty()) break
            }
            val out = java.io.ByteArrayOutputStream()
            doc.writeTo(out); doc.close()
            out.toByteArray()
        }
        return Export.save(context, fileName, "application/pdf", bytes)
    }
}
