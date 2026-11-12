package com.example.d750bridge.ptp

import android.net.Network
import com.example.d750bridge.AppLog
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpException(message: String) : IOException(message)

/** TCP接続そのものが失敗した（その番地にカメラがいない／ポートが閉じている）。ハンドシェイク以降の失敗とは区別する。 */
class PtpConnectException(message: String, cause: Throwable?) : IOException(message, cause)

/** note: 応答コード以外の補足（HTTP系ドライバーのエラー内容など）。 */
data class PtpResult(val code: Int, val params: List<Int>, val data: ByteArray, val note: String = "")

data class InitResult(val connectionNumber: Int, val cameraName: String)

/**
 * PTP/IP クライアント（Nikon / Canon 共通のトランスポート）。
 * パケット構造は PTP/IP の公開情報（gphoto.org の PTP/IP 文書）に基づく。
 * メーカー固有の操作は camera/NikonDriver・CanonDriver 側で組み立てる。
 */
class PtpIpClient(
    private val network: Network,
    private val host: String,
    private val port: Int = 15740,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 8_000
) : Closeable {

    companion object {
        const val T_INIT_CMD_REQ = 1
        const val T_INIT_CMD_ACK = 2
        const val T_INIT_EVT_REQ = 3
        const val T_INIT_EVT_ACK = 4
        const val T_INIT_FAIL = 5
        const val T_CMD_REQ = 6
        const val T_CMD_RESP = 7
        const val T_START_DATA = 9
        const val T_DATA = 10
        const val T_END_DATA = 12
        const val T_PING = 13
        const val T_PONG = 14

        const val RC_OK = 0x2001
        const val RC_SESSION_ALREADY_OPEN = 0x201E
        const val RC_DEVICE_BUSY = 0x2019

        const val T_EVENT = 8
        const val EV_OBJECT_ADDED = 0x4002
        const val EV_CAPTURE_COMPLETE = 0x400D

        const val OP_GET_DEVICE_INFO = 0x1001
        const val OP_OPEN_SESSION = 0x1002
        const val OP_CLOSE_SESSION = 0x1003
        const val OP_GET_STORAGE_IDS = 0x1004
        const val OP_GET_OBJECT_HANDLES = 0x1007
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009
        const val OP_GET_THUMB = 0x100A
        const val OP_INITIATE_CAPTURE = 0x100E
        const val OP_GET_DEVICE_PROP_DESC = 0x1014
        const val OP_GET_DEVICE_PROP_VALUE = 0x1015
        const val OP_SET_DEVICE_PROP_VALUE = 0x1016
        const val OP_GET_PARTIAL_OBJECT = 0x101B
        const val NIKON_AF_DRIVE = 0x90C1
        const val NIKON_DEVICE_READY = 0x90C8
        const val NIKON_GET_LARGE_THUMB = 0x90C4
        const val NIKON_START_LIVE_VIEW = 0x9201
        const val NIKON_END_LIVE_VIEW = 0x9202
        const val NIKON_GET_LIVE_VIEW_IMAGE = 0x9203
        const val NIKON_CHANGE_AF_AREA = 0x9205
        const val NIKON_AF_DRIVE_CANCEL = 0x9206
        const val NIKON_MF_DRIVE = 0x9204

        /** Nikon WMU が提示する GUID と同じ値（airmtp の記載による）。ワイヤ上は 00 11 22 ... FF */
        val DEFAULT_GUID = ByteArray(16) { (it * 0x11).toByte() }
        private val LE = ByteOrder.LITTLE_ENDIAN
        private const val MAX_PACKET = 96 * 1024 * 1024   // Canon の GetObject（RAW 1枚）を1回で受けるため
    }

    private var cmd: Socket? = null
    private var evt: Socket? = null
    private var cmdIn: InputStream? = null
    private var txId = 0
    @Volatile private var drainThread: Thread? = null

    /** イベント(ObjectAdded / CaptureComplete 等)を受け取るコールバック。イベントスレッドから呼ばれる。 */
    @Volatile var onEvent: ((code: Int, params: List<Int>) -> Unit)? = null

    private fun newSocket(): Socket = Socket().also {
        it.receiveBufferSize = 512 * 1024   // connect 前に設定する必要がある（ライブビューJPEGの受信高速化）
        it.sendBufferSize = 64 * 1024
        network.bindSocket(it)
    }

    /** initAckTimeoutMs: 最初の応答(Init_Command_Ack)の待ち時間。Canonはカメラ画面で許可されるまで応答を保留するため長く待つ。 */
    fun connect(guid: ByteArray = DEFAULT_GUID, name: String = "D750Bridge", initAckTimeoutMs: Int = readTimeoutMs): InitResult {
        AppLog.i("TCP接続開始 $host:$port（カメラWi-Fiネットワークに束縛）")
        val c = newSocket().also { cmd = it }
        try {
            c.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            throw PtpConnectException("$host:$port にTCP接続できません（${e.javaClass.simpleName}: ${e.message}）", e)
        }
        c.soTimeout = readTimeoutMs
        c.tcpNoDelay = true
        cmdIn = BufferedInputStream(c.getInputStream(), 128 * 1024)
        AppLog.i("TCP接続成功（コマンド）")

        val nameBytes = (name + "\u0000").toByteArray(Charsets.UTF_16LE)
        val req = ByteBuffer.allocate(16 + nameBytes.size + 4).order(LE)
        req.put(guid); req.put(nameBytes); req.putInt(0x00010000)
        send(c, T_INIT_CMD_REQ, req.array())

        c.soTimeout = initAckTimeoutMs
        val ack = try {
            recv(c)
        } catch (e: java.net.SocketTimeoutException) {
            throw PtpException("カメラが接続要求に${initAckTimeoutMs / 1000}秒以内に応答しません（Init_Command_Ack なし）。カメラ画面の接続確認・ペアリングの許可待ちの可能性があります")
        }
        c.soTimeout = readTimeoutMs
        if (ack.type == T_INIT_FAIL) throw PtpException("Init_Fail（コマンド接続拒否） reason=${failReason(ack.payload)}")
        if (ack.type != T_INIT_CMD_ACK) throw PtpException("想定外の応答 type=${ack.type}（Init_Command_Ack を期待）")
        val b = ByteBuffer.wrap(ack.payload).order(LE)
        val connNum = b.int
        b.position(b.position() + 16) // camera GUID
        val camName = readUtf16z(b)
        AppLog.i("Init_Command_Ack: connectionNumber=$connNum cameraName='$camName'")

        val e = newSocket().also { evt = it }
        e.connect(InetSocketAddress(host, port), connectTimeoutMs)
        e.soTimeout = readTimeoutMs
        e.tcpNoDelay = true
        send(e, T_INIT_EVT_REQ, ByteBuffer.allocate(4).order(LE).putInt(connNum).array())
        val eack = recv(e)
        if (eack.type == T_INIT_FAIL) throw PtpException("Init_Fail（イベント接続拒否） reason=${failReason(eack.payload)}")
        if (eack.type != T_INIT_EVT_ACK) throw PtpException("想定外の応答 type=${eack.type}（Init_Event_Ack を期待）")
        AppLog.i("Init_Event_Ack 受信（イベント接続成立）")
        startEventDrain(e)
        return InitResult(connNum, camName)
    }

    /**
     * イベントソケットを読み続ける。読まないとカメラ側の送信が詰まる場合があり、
     * また CaptureComplete を連写の同期に使える。
     */
    private fun startEventDrain(e: Socket) {
        val ins = BufferedInputStream(e.getInputStream())
        val t = Thread {
            try {
                e.soTimeout = 0
                while (!e.isClosed) {
                    val h = ByteBuffer.wrap(readFully(ins, 8)).order(LE)
                    val len = h.int; val type = h.int
                    if (len < 8 || len > 1024 * 1024) break
                    val payload = readFully(ins, len - 8)
                    if (type == T_PING) {
                        try { send(e, T_PONG, ByteArray(0)) } catch (_: Exception) {}
                    } else if (type == T_EVENT && payload.size >= 6) {
                        val b = ByteBuffer.wrap(payload).order(LE)
                        val code = b.short.toInt() and 0xFFFF
                        b.int // transaction id
                        val ps = ArrayList<Int>()
                        while (b.remaining() >= 4) ps.add(b.int)
                        AppLog.v { "EVENT ${PtpNames.hex(code)} params=$ps" }
                        onEvent?.invoke(code, ps)
                    }
                }
            } catch (_: Exception) {
                // close() 時の例外は正常
            }
        }
        t.isDaemon = true
        t.name = "ptp-event-drain"
        drainThread = t
        t.start()
    }

    /** セッションID: まず Init_Command_Ack の値、拒否されたら 1 で再試行（airmtp の挙動に倣う） */
    /** セッション外の GetDeviceInfo の後、OpenSession をトランザクション0から始める（Canon EOS Utility の順序）。 */
    fun resetTransactionId() { txId = 0 }

    fun openSession(preferredSessionId: Int) {
        var r = transact(OP_OPEN_SESSION, preferredSessionId)
        if (r.code != RC_OK && r.code != RC_SESSION_ALREADY_OPEN && preferredSessionId != 1) {
            AppLog.w("OpenSession(id=$preferredSessionId) 失敗 code=${PtpNames.hex(r.code)}。id=1 で再試行")
            r = transact(OP_OPEN_SESSION, 1)
        }
        if (r.code != RC_OK && r.code != RC_SESSION_ALREADY_OPEN) {
            throw PtpException("OpenSession 失敗 responseCode=${PtpNames.hex(r.code)}")
        }
        AppLog.i("OpenSession 成功 responseCode=${PtpNames.hex(r.code)}")
    }

    fun getDeviceInfo(): PtpDeviceInfo {
        val r = transact(OP_GET_DEVICE_INFO)
        if (r.code != RC_OK) throw PtpException("GetDeviceInfo 失敗 responseCode=${PtpNames.hex(r.code)}")
        AppLog.i("GetDeviceInfo: データ ${r.data.size} バイト受信")
        return PtpDeviceInfo.parse(r.data)
    }

    fun closeSession() {
        try {
            val r = transact(OP_CLOSE_SESSION)
            AppLog.i("CloseSession responseCode=${PtpNames.hex(r.code)}")
        } catch (e: Exception) {
            AppLog.w("CloseSession 失敗: ${e.message}")
        }
    }

    fun transact(opCode: Int, vararg params: Int): PtpResult = transactInternal(opCode, null, params, true)

    /** ライブビューなど高頻度の操作用。INFOログを出さない（ログ出力がボトルネックだったため）。 */
    fun transactQuiet(opCode: Int, vararg params: Int): PtpResult = transactInternal(opCode, null, params, false)

    /** PTP/IP data-out transaction (SetDevicePropValue など)。 */
    fun transactWithData(opCode: Int, dataOut: ByteArray, vararg params: Int): PtpResult =
        transactInternal(opCode, dataOut, params, true)

    /** data-out の高頻度版（ログを出さない）。 */
    fun transactWithDataQuiet(opCode: Int, dataOut: ByteArray, vararg params: Int): PtpResult =
        transactInternal(opCode, dataOut, params, false)

    private fun transactInternal(opCode: Int, dataOut: ByteArray?, params: IntArray, log: Boolean): PtpResult {
        val c = cmd ?: throw PtpException("コマンド接続が未確立")
        val tx = txId++
        val p = ByteBuffer.allocate(4 + 2 + 4 + params.size * 4).order(LE)
        p.putInt(if (dataOut == null) 1 else 2)
        p.putShort(opCode.toShort())
        p.putInt(tx)
        params.forEach { p.putInt(it) }
        if (log) AppLog.i("→ ${PtpNames.hex(opCode)} ${PtpNames.op(opCode)} tx=$tx")
        send(c, T_CMD_REQ, p.array())
        if (dataOut != null) {
            val start = ByteBuffer.allocate(12).order(LE).putInt(tx).putLong(dataOut.size.toLong()).array()
            send(c, T_START_DATA, start)
            var offset = 0
            while (offset < dataOut.size) {
                val n = minOf(1024 * 1024, dataOut.size - offset)
                val chunk = ByteBuffer.allocate(4 + n).order(LE).putInt(tx).put(dataOut, offset, n).array()
                send(c, if (offset + n == dataOut.size) T_END_DATA else T_DATA, chunk)
                offset += n
            }
        }
        var data = ByteArrayOutputStream()
        while (true) {
            val pkt = recv(c)
            when (pkt.type) {
                T_START_DATA -> if (pkt.payload.size >= 12) {
                    val total = ByteBuffer.wrap(pkt.payload).order(LE).getLong(4)
                    if (total in 1..MAX_PACKET.toLong()) data = ByteArrayOutputStream(total.toInt())
                }
                T_DATA, T_END_DATA -> if (pkt.payload.size >= 4) data.write(pkt.payload, 4, pkt.payload.size - 4)
                T_PING -> send(c, T_PONG, ByteArray(0))
                T_CMD_RESP -> {
                    val b = ByteBuffer.wrap(pkt.payload).order(LE)
                    val code = b.short.toInt() and 0xFFFF
                    val rtx = b.int
                    val rp = ArrayList<Int>()
                    while (b.remaining() >= 4) rp.add(b.int)
                    if (rtx != tx) AppLog.w("トランザクションID不一致 送信=$tx 応答=$rtx")
                    if (log || code != RC_OK) AppLog.i("← response ${PtpNames.hex(code)} params=${rp.size} data=${data.size()}B")
                    return PtpResult(code, rp, data.toByteArray())
                }
                T_INIT_FAIL -> throw PtpException("Init_Fail を受信 reason=${failReason(pkt.payload)}")
                else -> throw PtpException("想定外のパケット type=${pkt.type}")
            }
        }
    }


    // ---- 読み取り専用の操作（カメラの状態は変えない） ----
    fun getDevicePropValue(propCode: Int): PtpResult = transact(OP_GET_DEVICE_PROP_VALUE, propCode)

    fun setDevicePropValue(propCode: Int, value: ByteArray): PtpResult =
        transactWithData(OP_SET_DEVICE_PROP_VALUE, value, propCode)

    fun getStorageIds(): List<Int> {
        val r = transact(OP_GET_STORAGE_IDS)
        if (r.code != RC_OK) throw PtpException("GetStorageIDs 失敗 ${PtpNames.hex(r.code)}")
        val b = ByteBuffer.wrap(r.data).order(LE)
        val n = b.int
        require(n in 0..64) { "ストレージ数が不正: $n" }
        return List(n) { b.int }
    }

    fun getObjectHandles(storageId: Int): List<Int> {
        val r = transact(OP_GET_OBJECT_HANDLES, storageId, 0, 0)
        if (r.code != RC_OK) throw PtpException("GetObjectHandles 失敗 ${PtpNames.hex(r.code)}")
        val b = ByteBuffer.wrap(r.data).order(LE)
        if (b.remaining() < 4) throw PtpException("ハンドル一覧が短すぎます")
        val n = b.int
        require(n in 0..1_000_000 && b.remaining() >= n * 4) { "オブジェクト数/データ長が不正: $n" }
        return List(n) { b.int }
    }

    data class ObjectInfo(val storageId: Int, val format: Int, val size: Long, val fileName: String)
    data class PropertyDescriptor(
        val code: Int, val dataType: Int, val writable: Boolean, val current: ByteArray, val allowedValues: List<ByteArray>,
        /** Range形式(form=1)のとき (最小, 最大, 刻み)。Enumeration形式のときは null */
        val range: Triple<ByteArray, ByteArray, ByteArray>? = null
    )

    fun getPropertyDescriptor(propCode: Int): PropertyDescriptor {
        val r = transact(OP_GET_DEVICE_PROP_DESC, propCode)
        if (r.code != RC_OK) throw PtpException("GetDevicePropDesc ${PtpNames.hex(propCode)} 失敗 ${PtpNames.hex(r.code)}")
        val b = ByteBuffer.wrap(r.data).order(LE)
        if (b.remaining() < 5) throw PtpException("DevicePropDescが短すぎます")
        val code = b.short.toInt() and 0xFFFF
        val type = b.short.toInt() and 0xFFFF
        val writable = (b.get().toInt() and 0xFF) != 0
        fun value(): ByteArray {
            val n = when (type) { 0x0001, 0x0002 -> 1; 0x0003, 0x0004 -> 2; 0x0005, 0x0006 -> 4; 0x0007, 0x0008 -> 8; else -> throw PtpException("未対応のPTPプロパティ型 0x${type.toString(16)}") }
            if (b.remaining() < n) throw PtpException("DevicePropDesc値が途中で終了")
            return ByteArray(n).also { b.get(it) }
        }
        value() // factory default
        val current = value()
        if (!b.hasRemaining()) return PropertyDescriptor(code, type, writable, current, emptyList())
        val form = b.get().toInt() and 0xFF
        if (form == 1) {
            val mn = value(); val mx = value(); val st = value()
            return PropertyDescriptor(code, type, writable, current, emptyList(), Triple(mn, mx, st))
        }
        val allowed = if (form == 2 && b.remaining() >= 2) {
            val count = b.short.toInt() and 0xFFFF
            require(count <= 1024) { "プロパティ選択肢が多すぎます" }
            List(count) { value() }
        } else emptyList()
        return PropertyDescriptor(code, type, writable, current, allowed)
    }

    fun getObjectInfo(handle: Int): ObjectInfo {
        val r = transact(OP_GET_OBJECT_INFO, handle)
        if (r.code != RC_OK) throw PtpException("GetObjectInfo(${handle.toUInt()}) 失敗 ${PtpNames.hex(r.code)}")
        val b = ByteBuffer.wrap(r.data).order(LE)
        fun u16() = b.short.toInt() and 0xFFFF
        fun u32() = b.int.toLong() and 0xFFFFFFFFL
        if (b.remaining() < 53) throw PtpException("ObjectInfoデータが短すぎます")
        val storage = u32().toInt(); val format = u16(); u16()
        val size = u32()
        u16(); u32(); u32(); u32(); u32(); u32(); u32(); u32(); u16(); u32(); u32()
        val chars = b.get().toInt() and 0xFF
        val name = if (chars <= 0) "IMG_${handle.toUInt()}" else {
            val bytes = minOf(chars - 1, b.remaining() / 2) * 2
            val raw = ByteArray(bytes); b.get(raw)
            if (b.remaining() >= 2) b.short
            String(raw, Charsets.UTF_16LE)
        }
        return ObjectInfo(storage, format, size, name.ifBlank { "IMG_${handle.toUInt()}" })
    }

    /** 1回あたり最大chunkSizeだけ取得。全ファイルをメモリに保持しない。 */
    fun getPartialObject(handle: Int, offset: Int, chunkSize: Int): ByteArray {
        val r = transact(OP_GET_PARTIAL_OBJECT, handle, offset, chunkSize)
        if (r.code != RC_OK) throw PtpException("GetPartialObject 失敗 offset=$offset ${PtpNames.hex(r.code)}")
        return r.data
    }

    /** サムネイル(JPEG)。非対応形式などで失敗した場合は例外にせず null（セッションを壊さない）。 */
    fun getThumb(handle: Int): ByteArray? =
        transactQuiet(OP_GET_THUMB, handle).let { if (it.code == RC_OK && it.data.isNotEmpty()) it.data else null }

    fun getLargeThumb(handle: Int): ByteArray? =
        transactQuiet(NIKON_GET_LARGE_THUMB, handle).let { if (it.code == RC_OK && it.data.isNotEmpty()) it.data else null }

    fun startLiveView(): PtpResult = transact(NIKON_START_LIVE_VIEW)
    fun endLiveView(): PtpResult = transact(NIKON_END_LIVE_VIEW)
    /** 応答コードごと返す（DeviceBusy のときは呼び出し側で短く待って再試行する）。 */
    fun getLiveViewImageRaw(): PtpResult = transactQuiet(NIKON_GET_LIVE_VIEW_IMAGE)
    fun deviceReady(): PtpResult = transactQuiet(NIKON_DEVICE_READY)
    fun autofocus(): PtpResult = transact(NIKON_AF_DRIVE)
    fun cancelAutofocus(): PtpResult = transact(NIKON_AF_DRIVE_CANCEL)
    fun changeAfArea(x: Int, y: Int): PtpResult = transact(NIKON_CHANGE_AF_AREA, x, y)

    /**
     * Nikon MfDrive (0x9204): フォーカスモーターを直接駆動する。param1=方向(1か2)、param2=ステップ数。
     * 1と2のどちらが Near/Far かは機種・レンズ依存のため、ここでは決め打ちしない（呼び出し側が実機で校正して決める）。
     * ライブビュー中の動作・応答コードはD750実機で未確認。
     */
    fun mfDrive(directionFlag: Int, steps: Int): PtpResult = transact(NIKON_MF_DRIVE, directionFlag, steps)

    /** 全ストレージの全オブジェクト数（ハンドル一覧の件数） */
    fun countObjects(): Int {
        val r = transact(OP_GET_OBJECT_HANDLES, -1, 0, 0) // StorageID=0xFFFFFFFF, 全形式, 全階層
        if (r.code != RC_OK) throw PtpException("GetObjectHandles 失敗 ${PtpNames.hex(r.code)}")
        val b = ByteBuffer.wrap(r.data).order(LE)
        val n = b.int
        require(n in 0..1_000_000) { "オブジェクト数が不正: $n" }
        return n
    }

    // ---- 撮影（シャッターが切れる。呼び出し側で明示的な確認を取ること） ----
    /** DeviceBusy の再試行で高頻度に呼ばれるため quiet。結果ログは呼び出し側で出す。 */
    fun initiateCapture(): PtpResult = transactQuiet(OP_INITIATE_CAPTURE, 0, 0)

    /** 別スレッドから呼んで、ブロック中の読み取りを即座に中断する（連写停止・LV停止用）。 */
    fun abort() {
        try { cmd?.close() } catch (_: Exception) {}
        try { evt?.close() } catch (_: Exception) {}
    }

    override fun close() {
        abort()
        cmd = null; evt = null; cmdIn = null
    }

    // ---- 低レベル ----
    private class Pkt(val type: Int, val payload: ByteArray)

    private fun send(s: Socket, type: Int, payload: ByteArray) {
        val buf = ByteBuffer.allocate(8 + payload.size).order(LE)
        buf.putInt(8 + payload.size); buf.putInt(type); buf.put(payload)
        AppLog.v { "  TX type=$type len=${8 + payload.size} ${hex(buf.array())}" }
        s.getOutputStream().apply { write(buf.array()); flush() }
    }

    private fun recv(s: Socket): Pkt {
        val ins = if (s === cmd) (cmdIn ?: s.getInputStream()) else s.getInputStream()
        val h = ByteBuffer.wrap(readFully(ins, 8)).order(LE)
        val len = h.int
        val type = h.int
        if (len < 8 || len > MAX_PACKET) throw PtpException("パケット長が不正: $len")
        val payload = readFully(ins, len - 8)
        AppLog.v { "  RX type=$type len=$len ${hex(payload)}" }
        return Pkt(type, payload)
    }

    private fun readFully(ins: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(out, off, n - off)
            if (r < 0) throw PtpException("接続が相手側から閉じられた（読み取り途中 $off/$n バイト）")
            off += r
        }
        return out
    }

    private fun readUtf16z(b: ByteBuffer): String {
        val sb = StringBuilder()
        while (b.remaining() >= 2) {
            val ch = b.short.toInt()
            if (ch == 0) break
            sb.append(ch.toChar())
        }
        return sb.toString()
    }

    /** PTP/IP Init_Fail の理由（1=接続元を拒否 / 2=使用中 / 3=理由不明）。 */
    private fun failReason(payload: ByteArray): String {
        if (payload.size < 4) return "不明"
        val v = ByteBuffer.wrap(payload).order(LE).int
        val name = when (v) { 1 -> "接続元を拒否（未登録の機器）"; 2 -> "カメラが使用中（他の機器と接続中）"; 3 -> "理由不明"; else -> "未分類" }
        return "0x" + Integer.toHexString(v) + " " + name
    }

    private fun hex(b: ByteArray, max: Int = 48): String {
        val s = b.take(max).joinToString(" ") { "%02X".format(it) }
        return if (b.size > max) "$s …" else s
    }
}
