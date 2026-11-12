package com.example.d750bridge.timelapse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.d750bridge.platform.PendingFile
import java.io.File
import kotlin.math.max

/**
 * JPEGのコマ列から H.264 / MP4 のタイムラプス動画を作る（端末内のハードウェアエンコーダーを使用）。
 * 入力は YUV420 Flexible（Image API）に自前で変換して渡すので、各コマの表示時刻を正確に指定できる。
 */
object TimelapseEncoder {
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val MAX_SIDE = 1920

    /** 動画の大きさ: 最初のコマに合わせ、長辺1920以内・16の倍数に丸める。 */
    fun outputSize(first: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(first.path, o)
        require(o.outWidth > 0 && o.outHeight > 0) { "コマ画像を読めません: ${first.name}" }
        val s = minOf(1f, MAX_SIDE.toFloat() / max(o.outWidth, o.outHeight))
        val w = ((o.outWidth * s).toInt() / 16 * 16).coerceAtLeast(16)
        val h = ((o.outHeight * s).toInt() / 16 * 16).coerceAtLeast(16)
        return w to h
    }

    /** 中央を基準に、はみ出しを切り取って w×h に収める（縦横比を崩さない）。 */
    private fun loadFrame(f: File, w: Int, h: Int, reuse: Bitmap): Bitmap? {
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val s = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix().apply {
            postScale(s, s)
            postTranslate((w - src.width * s) / 2f, (h - src.height * s) / 2f)
        }
        reuse.eraseColor(0xFF000000.toInt())
        Canvas(reuse).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return reuse
    }

    /** ARGB → YUV420（BT.601、限定範囲）。plane の rowStride / pixelStride に従って書き込む。 */
    private fun fillYuv(img: Image, bmp: Bitmap, px: IntArray) {
        val w = bmp.width; val h = bmp.height
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        for (j in 0 until h) {
            val rowOff = j * w
            for (i in 0 until w) {
                val c = px[rowOff + i]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                yb.put(j * yRow + i * yPix, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    ub.put((j / 2) * uRow + (i / 2) * uPix, u.coerceIn(0, 255).toByte())
                    vb.put((j / 2) * vRow + (i / 2) * vPix, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    /**
     * files の順に1コマずつ、fps のタイムラプス動画として fd へ書き出す。
     * onProgress は 0..1。キャンセルは isCancelled で確認する。
     */
    fun encode(files: List<File>, fps: Int, target: PendingFile, onProgress: (Float) -> Unit, isCancelled: () -> Boolean = { false }) {
        target.openFd().use { pfd -> encodeTo(files, fps, pfd.fileDescriptor, onProgress, isCancelled) }
    }

    private fun encodeTo(files: List<File>, fps: Int, fd: java.io.FileDescriptor, onProgress: (Float) -> Unit, isCancelled: () -> Boolean) {
        require(files.size >= 2) { "コマが2枚以上必要です" }
        val (w, h) = outputSize(files.first())
        val bitRate = (w.toLong() * h * fps / 6).coerceIn(2_000_000L, 40_000_000L).toInt()
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var next = 0
            var inputDone = false
            var outputDone = false
            val frameUs = 1_000_000L / fps
            while (!outputDone) {
                if (isCancelled()) throw InterruptedException("キャンセルされました")
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        if (next >= files.size) {
                            codec.queueInputBuffer(ii, 0, 0, next * frameUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val img = codec.getInputImage(ii) ?: error("エンコーダーの入力画像を取得できません")
                            val bmp = loadFrame(files[next], w, h, frame)
                            if (bmp != null) fillYuv(img, bmp, px)   // 読めないコマは直前の内容のまま（黒）で埋める
                            codec.queueInputBuffer(ii, 0, w * h * 3 / 2, next * frameUs, 0)
                            next++
                            onProgress(next.toFloat() / files.size)
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxing = true
                    }
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (buf != null && info.size > 0 && muxing) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            frame.recycle()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxing) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }
}
