package com.example.d750bridge.orbis

import android.content.Context
import android.graphics.Bitmap
import com.example.d750bridge.AppLog
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.Closeable

// Android版のOCR（ML Kit）。PC版は desktop/ 側に同じAPIのTesseract実装がある。

/**
 * ナンバー認識（ML Kit 日本語テキスト認識。端末内で動作、ネットワーク不要）。
 * 専用のナンバープレート検出モデルは使っていない。切り出した車両画像の文字認識結果から、ナンバーの形式に合う部分を探す。
 * 読取信頼度はOCRの確信度ではなく、「形式の完全さ」と「同じ読み取りが複数回出たか」からの目安。
 */
class PlateOcr(context: Context) : Closeable {
    private val recognizer = try {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    } catch (e: Throwable) {
        AppLog.e("OCRの初期化に失敗: ${e.javaClass.simpleName}: ${e.message}"); null
    }
    val available: Boolean get() = recognizer != null

    /** bmp 全体を認識して、ナンバー形式に合う文字列を返す。バックグラウンドスレッドで呼ぶこと。 */
    fun read(bmp: Bitmap): PlateRead {
        val r = recognizer ?: return PlateRead(null, 0, "")
        return try {
            val res = Tasks.await(r.process(InputImage.fromBitmap(bmp, 0)))
            val raw = res.text
            val p = PlateParser.parse(raw)
            if (p == null) PlateRead(null, 0, raw) else PlateRead(p.first, if (p.second) 70 else 40, raw)
        } catch (e: Throwable) {
            AppLog.w("OCR失敗: ${e.javaClass.simpleName}: ${e.message}")
            PlateRead(null, 0, "")
        }
    }

    override fun close() { try { recognizer?.close() } catch (_: Throwable) {} }
}

