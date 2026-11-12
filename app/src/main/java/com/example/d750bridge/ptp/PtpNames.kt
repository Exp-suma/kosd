package com.example.d750bridge.ptp

/** ISO 15740 (PTP) 標準コードの名前。ここに無いコード（ベンダー拡張）は名前を付けず16進のみ表示する。 */
object PtpNames {
    val operations: Map<Int, String> = mapOf(
        0x1001 to "GetDeviceInfo",
        0x1002 to "OpenSession",
        0x1003 to "CloseSession",
        0x1004 to "GetStorageIDs",
        0x1005 to "GetStorageInfo",
        0x1006 to "GetNumObjects",
        0x1007 to "GetObjectHandles",
        0x1008 to "GetObjectInfo",
        0x1009 to "GetObject",
        0x100A to "GetThumb",
        0x100B to "DeleteObject",
        0x100E to "InitiateCapture",
        0x1014 to "GetDevicePropDesc",
        0x1015 to "GetDevicePropValue",
        0x1016 to "SetDevicePropValue",
        0x101B to "GetPartialObject",
        0x90C1 to "Nikon_AfDrive",
        0x9201 to "Nikon_StartLiveView",
        0x9202 to "Nikon_EndLiveView",
        0x9203 to "Nikon_GetLiveViewImage",
        0x9205 to "Nikon_ChangeAfArea",
        0x9206 to "Nikon_AfDriveCancel"
    )

    val properties: Map<Int, String> = mapOf(
        0x5001 to "BatteryLevel",
        0x5003 to "ImageSize",
        0x5004 to "CompressionSetting",
        0x5005 to "WhiteBalance",
        0x5007 to "FNumber",
        0x5008 to "FocalLength",
        0x500A to "FocusMode",
        0x500C to "FlashMode",
        0x500D to "ExposureTime",
        0x500E to "ExposureProgramMode",
        0x500F to "ExposureIndex(ISO)",
        0x5010 to "ExposureBiasCompensation",
        0x5011 to "DateTime",
        0x5013 to "StillCaptureMode",
        0xD05D to "Nikon_LiveViewAfArea",
        0xD061 to "Nikon_LiveViewAfFocus"
    )

    fun exposureModeName(v: Int): String = when (v) {
        1 -> "M(マニュアル)"
        2 -> "P(プログラム)"
        3 -> "A(絞り優先)"
        4 -> "S(シャッター優先)"
        else -> "その他"
    }

    /** Canon EOS 拡張（libgphoto2 の定義名）。Nikon と番号が重なるため、ベンダーIDで引き分ける。 */
    val canonOperations: Map<Int, String> = mapOf(
        0x9101 to "Canon_EOS_GetStorageIDs", 0x9103 to "Canon_EOS_GetObjectInfo", 0x9104 to "Canon_EOS_GetObject",
        0x9107 to "Canon_EOS_GetPartialObject", 0x9109 to "Canon_EOS_GetObjectInfoEx", 0x910A to "Canon_EOS_GetThumbEx",
        0x910F to "Canon_EOS_RemoteRelease", 0x9110 to "Canon_EOS_SetDevicePropValueEx", 0x9114 to "Canon_EOS_SetRemoteMode",
        0x9115 to "Canon_EOS_SetEventMode", 0x9116 to "Canon_EOS_GetEvent", 0x911D to "Canon_EOS_KeepDeviceOn",
        0x9127 to "Canon_EOS_RequestDevicePropValue", 0x9128 to "Canon_EOS_RemoteReleaseOn", 0x9129 to "Canon_EOS_RemoteReleaseOff",
        0x9151 to "Canon_EOS_InitiateViewfinder", 0x9152 to "Canon_EOS_TerminateViewfinder", 0x9153 to "Canon_EOS_GetViewFinderData",
        0x9154 to "Canon_EOS_DoAf", 0x9155 to "Canon_EOS_DriveLens", 0x9158 to "Canon_EOS_Zoom", 0x9159 to "Canon_EOS_ZoomPosition",
        0x9160 to "Canon_EOS_AfCancel"
    )

    const val VENDOR_NIKON = 0x0AL
    const val VENDOR_CANON = 0x0BL

    fun op(code: Int, vendor: Long = VENDOR_NIKON): String = when {
        code < 0x9000 -> operations[code]
        vendor == VENDOR_CANON -> canonOperations[code]
        vendor == VENDOR_NIKON -> operations[code]
        else -> null
    } ?: "(ベンダー拡張/未分類)"
    fun prop(code: Int) = properties[code] ?: "(ベンダー拡張/未分類)"
    fun hex(code: Int) = "0x" + code.toString(16).uppercase().padStart(4, '0')

    fun responseName(code: Int): String = when (code) {
        0x2001 -> "OK"
        0x2002 -> "GeneralError"
        0x2003 -> "SessionNotOpen"
        0x2005 -> "OperationNotSupported"
        0x2006 -> "ParameterNotSupported"
        0x2019 -> "DeviceBusy"
        0x201D -> "InvalidParameter"
        0x201E -> "SessionAlreadyOpen"
        0x2020 -> "StoreNotAvailable"
        0x2021 -> "SpecificationByFormatUnsupported"
        0xA001 -> "Nikon_HardwareError"
        0xA002 -> "Nikon_OutOfFocus"
        0xA003 -> "Nikon_ChangeCameraModeFailed"
        0xA004 -> "Nikon_InvalidStatus"
        0xA005 -> "Nikon_SetPropertyNotSupported"
        0xA008 -> "Nikon_ShutterSpeedBulb"
        0xA009 -> "Nikon_MirrorUpSequence"
        0xA00A -> "Nikon_CameraModeNotAdjustFNumber"
        0xA00B -> "Nikon_NotLiveView"
        0xA00C -> "Nikon_MfDriveStepEnd"
        0xA00E -> "Nikon_MfDriveStepInsufficiency"
        0xA102 -> "Canon_NotReady"
        else -> "(未分類)"
    }

    /** 応答コード＋名前＋補足。画面・ログ表示用。 */
    fun describe(r: PtpResult): String = "${hex(r.code)} ${responseName(r.code)}${r.note.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""}"

    private fun le(b: ByteArray, n: Int, signed: Boolean = false): Long? {
        if (b.size < n) return null
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        if (signed) { val shift = 64 - 8 * n; v = (v shl shift) shr shift }
        return v
    }

    fun rawHex(b: ByteArray, max: Int = 16): String =
        b.take(max).joinToString(" ") { "%02X".format(it) } + if (b.size > max) " …" else ""

    /** 標準PTPの型・単位を仮定した解釈。Nikon独自の扱いと違う可能性があるため、必ず生データも併記する。 */
    fun decodeProp(code: Int, d: ByteArray): String = when (code) {
        0x5001 -> le(d, 1)?.let { "電池 $it %" }
        0x5007 -> le(d, 2)?.let { "F%.1f".format(it / 100.0) }
        0x500D -> le(d, 4)?.let { v -> ShutterSpeed.label(v and 0xFFFFFFFFL) }
        0x500F -> le(d, 2)?.let { "ISO $it" }
        0x5010 -> le(d, 2, true)?.let { "%+.1f EV".format(it / 1000.0) }
        0x500E -> le(d, 2)?.let { exposureModeName(it.toInt()) + " (0x%04X)".format(it) }
        0x5004, 0x5013, 0x500A -> le(d, 2)?.let { "値 0x%04X".format(it) }
        else -> null
    } ?: "解釈なし"
}
