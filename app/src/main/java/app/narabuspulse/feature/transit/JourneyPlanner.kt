package app.narabuspulse.feature.transit

import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.ui.kit.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * 統合乗換案内（奈良交通バス＋鉄道・他社交通機関）。
 *
 * 経路の組み立ては共通ロジック（assets/logic.js の glue/journey）が行い、ここでは
 * 「次に必要な問い合わせ」をまとめて並列に実行して結果を渡す、を終わるまで繰り返す。
 * バス直行・バス同士の乗換・バス⇄鉄道の乗継を 1 つの候補一覧にして返す。
 */
object JourneyPlanner {
    private const val MAX_ROUNDS = 12
    private const val PARALLEL = 4

    suspend fun search(query: JsonObject, onProgress: (String) -> Unit = {}): JsonElement {
        val res = LinkedHashMap<String, JsonElement>()
        val gate = Semaphore(PARALLEL)
        repeat(MAX_ROUNDS) {
            val step = Logic.call("glue/journey", "step", jsonOf("query" to query, "res" to JsonObject(res)))
            if (step.bool("done") == true) return step.objAt("result") ?: JsonNull
            val requests = step.items("requests")
            if (requests.isEmpty()) return JsonNull
            onProgress(step.str("progress") ?: "検索しています")
            coroutineScope {
                requests.map { r ->
                    async {
                        val id = r.str("id").orEmpty()
                        id to gate.withPermit {
                            try { Api.rpc(r.str("fn").orEmpty(), (r as? JsonObject)?.get("data")).takeIf { it !is JsonNull } ?: jsonOf("__error" to "empty") }
                            catch (e: CancellationException) { throw e }
                            catch (e: Throwable) { jsonOf("__error" to e.userMessage().ifBlank { "取得できませんでした" }) }
                        }
                    }
                }.awaitAll()
            }.forEach { (id, value) -> res[id] = value }
        }
        return JsonNull
    }
}
