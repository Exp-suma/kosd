package app.narabuspulse.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * サーバーの応答はそのまま JsonElement で扱い、画面ごとに必要な項目を取り出す。
 * （300 以上のサーバー関数それぞれにデータクラスを作らずに済むようにするため）
 */
object Json {
    val format = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parse(text: String): JsonElement = format.parseToJsonElement(text)
}

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray

/** "a.b.c" のようなドット区切りで入れ子を辿る。 */
fun JsonElement?.at(path: String): JsonElement? {
    var cur: JsonElement? = this
    for (key in path.split('.')) {
        cur = when (cur) {
            is JsonObject -> cur[key]
            is JsonArray -> key.toIntOrNull()?.let { cur.getOrNull(it) }
            else -> null
        }
        if (cur == null || cur is JsonNull) return null
    }
    return cur
}

fun JsonElement?.str(path: String): String? {
    val v = at(path) as? JsonPrimitive ?: return null
    return if (v is JsonNull) null else v.content
}

fun JsonElement?.long(path: String): Long? {
    val v = at(path) as? JsonPrimitive ?: return null
    return v.longOrNull ?: v.doubleOrNull?.toLong() ?: v.content.toLongOrNull()
}

fun JsonElement?.int(path: String): Int? = long(path)?.toInt()

fun JsonElement?.dbl(path: String): Double? {
    val v = at(path) as? JsonPrimitive ?: return null
    return v.doubleOrNull ?: v.content.toDoubleOrNull()
}

fun JsonElement?.bool(path: String): Boolean? {
    val v = at(path) as? JsonPrimitive ?: return null
    return v.booleanOrNull ?: when (v.content) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }
}

fun JsonElement?.list(path: String): List<JsonElement> = (at(path) as? JsonArray)?.toList() ?: emptyList()

fun JsonElement?.objAt(path: String): JsonObject? = at(path) as? JsonObject

/** 配列の中身（配列そのもの・{items: []}・{records: []} などを吸収する）。 */
fun JsonElement?.items(vararg keys: String): List<JsonElement> {
    if (this is JsonArray) return this.toList()
    val o = this as? JsonObject ?: return emptyList()
    for (k in keys.ifEmpty { arrayOf("items", "records", "rows", "list", "data", "results") }) {
        val v = o[k]
        if (v is JsonArray) return v.toList()
    }
    return emptyList()
}

// ---- 送信データの組み立て ----

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.mapNotNull { (k, v) -> if (v == null) null else k to toJson(v) }.toMap())

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    is Array<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}
