package com.example.d750bridge.platform

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.OutputStream

/**
 * 端末ごとに異なる入出力（Android版）。PC版は desktop/ 側に同じAPIの実装がある。
 *  - 保存: メディアストア（Download / ピクチャ / ムービー）
 *  - 検知音・JPEGの部分デコード
 */
enum class SaveFolder(val androidPath: String, val label: String) {
    DOWNLOAD("Download/CamBridge", "Download/CamBridge"),
    RAW_IMPORT("Download/D750Bridge", "Download/D750Bridge"),
    PICTURES("Pictures/CamBridge", "ピクチャ/CamBridge"),
    MOVIES("Movies/CamBridge", "ムービー/CamBridge")
}

/** 書き込み中のファイル。publish で公開、discard で削除。 */
class PendingFile internal constructor(private val resolver: ContentResolver, val uri: Uri) {
    val id: String get() = uri.toString()
    fun openStream(): OutputStream = resolver.openOutputStream(uri, "w") ?: throw IllegalStateException("保存先を開けません")
    fun openFd(): ParcelFileDescriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("保存先を開けません")
    fun publish() { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
    fun discard() { try { resolver.delete(uri, null, null) } catch (_: Exception) {} }
}

object PlatformIo {
    fun create(ctx: Context, folder: SaveFolder, name: String, mime: String): PendingFile {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder.androidPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = when (folder) {
            SaveFolder.PICTURES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            SaveFolder.MOVIES -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(coll, values) ?: throw IllegalStateException("${folder.label} に保存先を作成できません")
        return PendingFile(resolver, uri)
    }

    fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            Thread { Thread.sleep(600); tg.release() }.start()
        } catch (_: Exception) { }
    }

    /** JPEG の一部だけをデコードする。rect は画像の幅・高さから (左, 上, 右, 下) を返す。 */
    fun decodeRegion(jpeg: ByteArray, sample: (Int) -> Int, rect: (Int, Int) -> IntArray): Bitmap? {
        val dec = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false) ?: return null
        try {
            val r = rect(dec.width, dec.height)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample(r[2] - r[0]) }
            return dec.decodeRegion(Rect(r[0], r[1], r[2], r[3]), opts)
        } finally { dec.recycle() }
    }
}
