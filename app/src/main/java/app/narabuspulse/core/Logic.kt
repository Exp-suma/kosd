package app.narabuspulse.core

import android.content.Context
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Web 版と同じ計算処理（assets/logic.js = Base44 アプリ src/lib の純粋関数）を QuickJS で実行する。
 * 例: Logic.call("weather/outfit", "buildOutfitAdvice", jsonOf("current" to ..., "day" to ...))
 * 計算結果を Web と完全に一致させるため、ロジックは書き直さずに元のコードをそのまま使う。
 */
object Logic {
    private lateinit var appContext: Context
    private var engine: QuickJs? = null
    private val mutex = Mutex()

    fun init(context: Context) { appContext = context.applicationContext }

    private suspend fun js(): QuickJs {
        engine?.let { return it }
        val q = QuickJs.create(Dispatchers.Default)
        val code = appContext.assets.open("logic.js").bufferedReader().use { it.readText() }
        q.evaluate<Any?>(code, filename = "logic.js")
        engine = q
        return q
    }

    private fun lit(s: String): String = kotlinx.serialization.json.Json.encodeToString(String.serializer(), s)

    /** 関数を呼ぶ。args は関数の引数を順に並べたもの。 */
    suspend fun call(module: String, fn: String, vararg args: Any?): JsonElement = mutex.withLock {
        val argsJson = JsonArray(args.map { toJson(it) }).toString()
        val out = js().evaluate<String>("__logic.callJson(${lit(module)}, ${lit(fn)}, ${lit(argsJson)})")
        if (out.isBlank()) JsonNull else Json.parse(out)
    }

    /** 定数（例: weather/municipalities の MUNICIPALITIES）を取り出す。 */
    suspend fun constant(module: String, name: String): JsonElement = mutex.withLock {
        val out = js().evaluate<String>("__logic.constJson(${lit(module)}, ${lit(name)})")
        if (out.isBlank()) JsonNull else Json.parse(out)
    }
}
