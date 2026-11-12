package app.narabuspulse.feature.notify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAlert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Session
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.obj
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.push.PushStatusCard
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val FIELDS = listOf(
    "name", "vehicle_number", "vehicle_type", "route", "garage", "area_name", "stop_name", "radius_m", "notify_start", "notify_route",
    "notify_area", "notify_end", "notify_missing", "notify_stop", "notify_pass", "notify_exit", "notify_destination", "notify_resume",
    "quiet_from", "quiet_to", "enabled", "lat", "lon",
)
private val NUMERIC = setOf("radius_m", "quiet_from", "quiet_to", "lat", "lon")

/** Web の pickFields：保存に必要な項目だけを取り出す。 */
fun pickWatchFields(watch: JsonElement, patch: Map<String, Any?> = emptyMap()): JsonObject {
    val o = watch.obj ?: JsonObject(emptyMap())
    val out = LinkedHashMap<String, JsonElement>()
    o["id"]?.let { out["id"] = it }
    FIELDS.forEach { k ->
        val v = o[k] ?: return@forEach
        if (v is JsonNull) return@forEach
        if (k in NUMERIC && (v as? JsonPrimitive)?.content == "") return@forEach
        out[k] = v
    }
    patch.forEach { (k, v) -> out[k] = app.narabuspulse.core.toJson(v) }
    return JsonObject(out)
}

@Composable
fun PageHeading(title: String, description: String) {
    Column {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 車両通知（Web: src/routes/_authed/vehicle-notify.index.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleNotifyScreen() {
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val query = rememberLoad("vn-overview") { Api.rpc("notify/vehicle-fns#getVehicleNotifyOverview", jsonOf()) }
    var editing by remember { mutableStateOf<JsonElement?>(null) }
    var adding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val data = query.data
    val watches = data.items("watches")

    fun act(block: suspend () -> Unit) {
        busy = true; error = ""
        scope.launch { try { block() } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage() } finally { busy = false } }
    }

    ScreenList {
        item {
            PageHeading("車両通知", "奈良交通・エヌシーバスの全車両から、車番や地点を指定して監視できます。判定はサーバーで行い、位置情報が古い場合は通知しません。")
            SmallButton("監視を追加", { editing = null; adding = true }, icon = Icons.Outlined.AddAlert, primary = true, modifier = Modifier.padding(top = 6.dp))
        }
        item { VehicleNotifyNav("/vehicle-notify") }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                StatTile("監視中の車両", watches.size.toString(), Modifier.weight(1f), "件", Color(0xFF2F6FB0))
                StatTile("運行中の監視対象", (data.int("fleet.running") ?: 0).toString(), Modifier.weight(1f), "台", Brand.Success)
                StatTile("未読の通知", (data.int("unread") ?: 0).toString(), Modifier.weight(1f), "件", if ((data.int("unread") ?: 0) > 0) Brand.Warning else Brand.MutedFg)
                StatTile("登録端末", (data.int("pushDevices") ?: 0).toString(), Modifier.weight(1f), "台")
                StatTile("監視できる車両", (data.int("fleet.total") ?: 0).toString(), Modifier.weight(1f), "台")
            }
        }
        if (message.isNotBlank()) item { Text(message, fontSize = 12.sp, color = Brand.Success) }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (query.error != null) item { Text("情報を読み込めませんでした: ${query.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        data.str("fleet.error")?.takeIf { it.isNotBlank() }?.let { e -> item { Text("車両位置を取得できませんでした（通知判定はサーバー側で継続します）: $e", fontSize = 12.sp, color = Brand.Warning) } }
        if (query.loading && data == null) item { Text("監視の状況を確認しています…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (adding || editing != null) item {
            WatchForm(editing, onSubmit = { payload ->
                act {
                    try {
                        Api.rpc("predict/fns#saveAppearanceWatch", payload)
                        message = if (payload["id"] != null && payload["id"] !is JsonNull) "監視の条件を更新しました" else "監視を追加しました"
                        editing = null; adding = false; query.reload()
                    } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "保存できませんでした" } }
                }
            }, onCancel = { editing = null; adding = false }, busy = busy)
        }
        item { NotifyVehicleSearch(onRegistered = { query.reload() }) }
        item {
            NotifyWatchList(
                watches,
                onEdit = { editing = it; adding = false },
                onToggle = { w -> act { Api.rpc("predict/fns#saveAppearanceWatch", pickWatchFields(w, mapOf("enabled" to (w.str("enabled") == "false")))); query.reload() } },
                onDelete = { w -> confirm.ask("「${w.str("name")}」の監視を解除します。よろしいですか？") { act { Api.rpc("predict/fns#deleteAppearanceWatch", jsonOf("id" to w.str("id"))); message = "監視を解除しました"; query.reload() } } },
                busy = busy,
                onAdd = { editing = null; adding = true },
            )
        }
        item { NotifyLatestList(data.items("notifications"), data.int("unread") ?: 0) }
        item { NotifyChannelSettings(data.objAt("prefs"), data.int("pushDevices") ?: 0, onSaved = { query.reload() }) }
        item { PushStatusCard() }
        if (Session.isAdmin) item { NotifyAdminPanel(data.objAt("admin"), onUpdated = { query.reload() }) }
    }
}
