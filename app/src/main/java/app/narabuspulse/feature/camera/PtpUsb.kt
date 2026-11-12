package app.narabuspulse.feature.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PTP の操作コード・応答コード（Web: lib/camera/ptp.js）。 */
object Ptp {
    const val GetDeviceInfo = 0x1001; const val OpenSession = 0x1002; const val CloseSession = 0x1003; const val GetObjectHandles = 0x1007; const val GetObjectInfo = 0x1008; const val GetObject = 0x1009
    const val InitiateCapture = 0x100e; const val GetDevicePropDesc = 0x1014; const val GetDevicePropValue = 0x1015; const val SetDevicePropValue = 0x1016; const val GetPartialObject = 0x101b
    const val NikonDeviceReady = 0x90c8; const val NikonAfDrive = 0x90c1; const val NikonStartLiveView = 0x9201; const val NikonEndLiveView = 0x9202; const val NikonGetLiveViewImage = 0x9203
    const val EvObjectAdded = 0x4002
    const val RcOk = 0x2001; const val RcNotSupported = 0x2005; const val RcBusy = 0x2019; const val RcSessionAlreadyOpen = 0x2020
    private val TEXT = mapOf(0x2002 to "カメラが一般エラーを返しました", 0x2003 to "セッションが開かれていません", 0x2004 to "トランザクションIDが不正です", 0x2005 to "このカメラはその操作に対応していません", 0x2006 to "このカメラはそのパラメータに対応していません",
        0x2007 to "転送が途中で終わりました", 0x2009 to "ストレージIDが不正です", 0x200a to "画像のハンドルが不正です", 0x200b to "このカメラはその設定項目に対応していません", 0x200c to "このカメラはその形式に対応していません",
        0x2019 to "カメラが処理中です（少し待ってから再実行してください）", 0x201a to "親オブジェクトが不正です", 0x201d to "設定値の形式が不正です", 0x201e to "カメラがその設定値を受け付けませんでした", 0x2020 to "すでにセッションが開いています", 0x2021 to "処理が中止されました")
    fun text(code: Int) = TEXT[code] ?: "カメラがエラーを返しました（0x${code.toString(16)}）"
}

class PtpError(val code: Int, val operation: Int) : IOException(Ptp.text(code))
class PtpResult(val code: Int, val params: IntArray, val data: ByteArray?)
class PtpEvent(val code: Int, val params: IntArray)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/**
 * USB Host API による PTP（静止画クラス）の送受信（Web: lib/camera/usb.js の WebUSB 版と同じ手順）。
 * コマンド → （データ） → 応答 の順にバルク転送し、イベントは割り込みエンドポイントから読む。
 */
class PtpUsbTransport private constructor(val device: UsbDevice, private val conn: UsbDeviceConnection, private val iface: UsbInterface, private val epIn: UsbEndpoint, private val epOut: UsbEndpoint, private val epEvent: UsbEndpoint?) {
    private val lock = Mutex(); private var tx = 0
    private var pending = ByteArray(0)

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun container(type: Int, code: Int, id: Int, payload: ByteArray): ByteArray = le(12 + payload.size).putInt(12 + payload.size).putShort(type.toShort()).putShort(code.toShort()).putInt(id).put(payload).array()
    private fun send(bytes: ByteArray, timeout: Int) {
        var off = 0
        while (off < bytes.size) { val n = conn.bulkTransfer(epOut, bytes, off, bytes.size - off, timeout); if (n < 0) throw IOException("カメラへ送信できませんでした（USBの接続を確認してください）"); off += n }
        if (bytes.size % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, ByteArray(0), 0, timeout)
    }
    /** コンテナを1つ読む（複数の転送に分かれて届く・次のコンテナが続けて届く、の両方を扱う）。 */
    private fun readContainer(timeout: Int): Triple<Int, Int, ByteArray> {
        val out = ByteArrayOutputStream(); out.write(pending); pending = ByteArray(0)
        val buf = ByteArray(maxOf(16384, epIn.maxPacketSize * 32))
        var need = -1L
        while (true) {
            if (out.size() >= 12 && need < 0) { need = ByteBuffer.wrap(out.toByteArray(), 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL }
            if (need in 12..out.size().toLong()) break
            val n = conn.bulkTransfer(epIn, buf, buf.size, timeout)
            if (n < 0) throw IOException("カメラから応答がありません")
            out.write(buf, 0, n)
            if (need == 0xffffffffL && n < buf.size && n % epIn.maxPacketSize != 0) { need = out.size().toLong(); break }
        }
        val all = out.toByteArray(); val len = need.toInt().coerceIn(12, all.size)
        pending = all.copyOfRange(len, all.size)
        val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        return Triple(bb.getShort(4).toInt() and 0xffff, bb.getShort(6).toInt() and 0xffff, all.copyOfRange(12, len))
    }

    suspend fun transact(op: Int, params: IntArray = IntArray(0), dataOut: ByteArray? = null, timeout: Int = 8000): PtpResult = lock.withLock {
        withContext(Dispatchers.IO) {
            tx += 1; val id = tx
            send(container(1, op, id, le(params.size * 4).apply { params.forEach { putInt(it) } }.array()), timeout)
            if (dataOut != null) send(container(2, op, id, dataOut), timeout)
            var data: ByteArray? = null
            var result: PtpResult? = null
            while (result == null) {
                val (type, code, body) = readContainer(timeout)
                if (type == 2) { data = body; continue }
                if (type != 3) continue
                if (code != Ptp.RcOk) throw PtpError(code, op)
                result = PtpResult(code, IntArray(body.size / 4) { ByteBuffer.wrap(body, it * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int }, data)
            }
            result
        }
    }

    suspend fun readEvent(timeoutMs: Int): PtpEvent? = withContext(Dispatchers.IO) {
        val ep = epEvent ?: run { delay(timeoutMs.toLong()); return@withContext null }
        val buf = ByteArray(maxOf(64, ep.maxPacketSize))
        val n = conn.bulkTransfer(ep, buf, buf.size, timeoutMs)
        if (n < 12) return@withContext null
        val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        PtpEvent(bb.getShort(6).toInt() and 0xffff, IntArray((n - 12) / 4) { bb.getInt(12 + it * 4) })
    }
    suspend fun drainEvents(max: Int, timeoutMs: Int) { repeat(max) { if (readEvent(timeoutMs) == null) return } }

    fun close() { runCatching { conn.releaseInterface(iface) }; runCatching { conn.close() } }

    companion object {
        private const val ACTION = "app.narabuspulse.USB_PERMISSION"
        /** 静止画クラス（PTP）のインターフェースを持つ機器。 */
        fun cameras(context: Context): List<UsbDevice> {
            val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
            return usb.deviceList.values.filter { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE } }
        }
        fun supported(context: Context) = context.packageManager.hasSystemFeature("android.hardware.usb.host")

        private suspend fun permission(context: Context, usb: UsbManager, device: UsbDevice): Boolean {
            if (usb.hasPermission(device)) return true
            val done = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, intent: Intent?) { done.complete(usb.hasPermission(device)) } }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, IntentFilter(ACTION))
            try {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, Intent(ACTION).setPackage(context.packageName), flags))
                return withTimeoutOrNull(60000) { done.await() } ?: false
            } finally { runCatching { context.unregisterReceiver(receiver) } }
        }

        suspend fun open(context: Context, device: UsbDevice): PtpUsbTransport {
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!permission(context, usb, device)) throw IOException("USB機器へのアクセスが許可されませんでした")
            val iface = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE } ?: throw IOException("このUSB機器には PTP（静止画）の通信口がありません。カメラのUSB設定を PTP にしてください")
            var epIn: UsbEndpoint? = null; var epOut: UsbEndpoint? = null; var epEvent: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) { val ep = iface.getEndpoint(i)
                when { ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> epIn = ep; ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK -> epOut = ep; ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN -> epEvent = ep } }
            if (epIn == null || epOut == null) throw IOException("PTP の送受信口が見つかりません")
            val conn = usb.openDevice(device) ?: throw IOException("USB機器を開けませんでした")
            if (!conn.claimInterface(iface, true)) { conn.close(); throw IOException("カメラの通信口を確保できませんでした（他のアプリが使用中の可能性があります）") }
            return PtpUsbTransport(device, conn, iface, epIn, epOut, epEvent)
        }
    }
}

/** PTP カメラの操作（Web: lib/camera/client.js の PtpCamera）。バイト列の解釈は共通ロジック（glue/camera）で行う。 */
class PtpCamera(val transport: PtpUsbTransport) {
    var operations: Set<Int> = emptySet(); var properties: Set<Int> = emptySet(); var events: Set<Int> = emptySet()
    var liveViewRunning = false; private set

    suspend fun openSession(): ByteArray {
        try { transport.transact(Ptp.OpenSession, intArrayOf(1)) } catch (e: PtpError) { if (e.code != Ptp.RcSessionAlreadyOpen) throw e }
        return transport.transact(Ptp.GetDeviceInfo, timeout = 12000).data ?: throw IOException("カメラの情報を取得できませんでした")
    }
    fun supports(op: Int) = op in operations
    suspend fun propDesc(code: Int): ByteArray = transport.transact(Ptp.GetDevicePropDesc, intArrayOf(code)).data ?: ByteArray(0)
    suspend fun setProp(code: Int, encoded: ByteArray) { transport.transact(Ptp.SetDevicePropValue, intArrayOf(code), dataOut = encoded); transport.drainEvents(3, 40) }
    suspend fun afDrive() { if (!supports(Ptp.NikonAfDrive)) throw IOException("このカメラはAF作動（Nikon 0x90C1）の対応を報告していません"); transport.transact(Ptp.NikonAfDrive) }
    suspend fun listHandles(): List<Int> {
        val data = transport.transact(Ptp.GetObjectHandles, intArrayOf(-1, 0, 0), timeout = 15000).data ?: return emptyList()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); val count = bb.int
        return List(minOf(count, (data.size - 4) / 4)) { bb.int }
    }
    suspend fun objectInfo(handle: Int): ByteArray = transport.transact(Ptp.GetObjectInfo, intArrayOf(handle)).data ?: ByteArray(0)

    /** 画像を取り込む（GetPartialObject で分割し、未対応なら GetObject）。 */
    suspend fun download(handle: Int, size: Long, onProgress: (Int?) -> Unit = {}): ByteArray {
        if (size > 120L * 1024 * 1024) throw IOException("画像が大きすぎるため取り込めません（120MB を超えています）。JPEG での撮影をおすすめします。")
        val out = ByteArrayOutputStream(); val chunkSize = 256 * 1024; var offset = 0
        repeat(4000) {
            val chunk = try { transport.transact(Ptp.GetPartialObject, intArrayOf(handle, offset, chunkSize), timeout = 30000).data ?: ByteArray(0) }
            catch (e: PtpError) { if (offset == 0 && e.code == Ptp.RcNotSupported) { onProgress(100); return transport.transact(Ptp.GetObject, intArrayOf(handle), timeout = 60000).data ?: throw IOException("カメラから画像データが返りませんでした") } else throw e }
            if (chunk.isEmpty()) return out.toByteArray()
            out.write(chunk); offset += chunk.size
            onProgress(if (size > 0) minOf(100, (offset * 100L / size).toInt()) else null)
            if ((size > 0 && offset >= size) || chunk.size < chunkSize) return out.toByteArray()
        }
        return out.toByteArray()
    }

    /** シャッターを切り、保存された画像のハンドルを返す。 */
    suspend fun capture(onStatus: (String) -> Unit): Int {
        if (!supports(Ptp.InitiateCapture)) throw IOException("このカメラはリモート撮影（InitiateCapture）に対応していません")
        val useEvents = Ptp.EvObjectAdded in events
        val before = if (!useEvents) { onStatus("撮影前の画像一覧を確認しています"); listHandles().toSet() } else emptySet()
        transport.drainEvents(6, 60)
        onStatus("シャッターを切っています")
        var attempt = 0
        while (true) { attempt++; try { transport.transact(Ptp.InitiateCapture, intArrayOf(-1, 0), timeout = 15000); break } catch (e: PtpError) { if (e.code == Ptp.RcBusy && attempt < 3) { onStatus("カメラの準備を待っています"); delay(1200) } else throw e } }
        onStatus("撮影した画像の保存を待っています")
        var handle = 0
        if (useEvents) {
            val deadline = System.currentTimeMillis() + 40000
            while (handle == 0 && System.currentTimeMillis() < deadline) { val ev = transport.readEvent((deadline - System.currentTimeMillis()).coerceIn(500, 4000).toInt()) ?: continue; if (ev.code == Ptp.EvObjectAdded) handle = ev.params.firstOrNull() ?: 0 }
        } else {
            onStatus("新しい画像を探しています")
            var tries = 0
            while (handle == 0 && tries < 15) { tries++; delay(1200); handle = (listHandles() - before).maxOrNull() ?: 0 }
        }
        if (handle == 0) throw IOException("撮影は実行されましたが、保存された画像を確認できませんでした。カメラの背面で撮影結果を確認してください。")
        return handle
    }

    suspend fun startLiveView() {
        if (!supports(Ptp.NikonStartLiveView)) throw IOException("このカメラはライブビュー操作（Nikon 0x9201）に対応を報告していません")
        runCatching { transport.transact(Ptp.NikonDeviceReady, timeout = 4000) }
        transport.transact(Ptp.NikonStartLiveView); liveViewRunning = true
    }
    /** ライブビューの1コマ（JPEG 部分だけを取り出す）。 */
    suspend fun liveFrame(): ByteArray? {
        if (!liveViewRunning) return null
        val data = transport.transact(Ptp.NikonGetLiveViewImage, timeout = 6000).data ?: return null
        var start = -1; for (i in 0 until data.size - 1) if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) { start = i; break }
        if (start < 0) return null
        var end = -1; for (i in data.size - 2 downTo start + 1) if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) { end = i + 2; break }
        return if (end < 0) null else data.copyOfRange(start, end)
    }
    suspend fun stopLiveView() { if (!liveViewRunning) return; liveViewRunning = false; runCatching { transport.transact(Ptp.NikonEndLiveView, timeout = 6000) } }
    suspend fun close() { runCatching { stopLiveView() }; runCatching { transport.transact(Ptp.CloseSession, timeout = 3000) }; transport.close() }
}
