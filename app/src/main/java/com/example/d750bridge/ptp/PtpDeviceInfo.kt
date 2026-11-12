package com.example.d750bridge.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** カメラが GetDeviceInfo で「自己申告」した内容。申告＝実際に動作する保証ではない。 */
data class PtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operations: List<Int>,
    val events: List<Int>,
    val properties: List<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String
) {
    fun declares(op: Int) = operations.contains(op)
    fun declaresProp(p: Int) = properties.contains(p)

    companion object {
        fun parse(data: ByteArray): PtpDeviceInfo {
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            fun u16() = b.short.toInt() and 0xFFFF
            fun u32() = b.int.toLong() and 0xFFFFFFFFL
            fun str(): String {
                val n = b.get().toInt() and 0xFF
                if (n == 0) return ""
                val sb = StringBuilder()
                repeat(n) { val c = u16(); if (c != 0) sb.append(c.toChar()) }
                return sb.toString()
            }
            fun arr(): List<Int> {
                val n = u32().toInt()
                require(n in 0..4096) { "配列長が不正: $n" }
                return List(n) { u16() }
            }
            return PtpDeviceInfo(
                standardVersion = u16(),
                vendorExtensionId = u32(),
                vendorExtensionVersion = u16(),
                vendorExtensionDesc = str(),
                functionalMode = u16(),
                operations = arr(),
                events = arr(),
                properties = arr(),
                // CaptureFormats / ImageFormats は今回は使わないが、読み進めるため消費する
                manufacturer = run { arr(); arr(); str() },
                model = str(),
                deviceVersion = str(),
                serialNumber = str()
            )
        }
    }
}
