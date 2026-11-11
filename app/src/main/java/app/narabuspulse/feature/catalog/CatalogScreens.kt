package app.narabuspulse.feature.catalog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.Logic
import app.narabuspulse.core.Session
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.board.fmtDateTime
import app.narabuspulse.feature.fleet.compassLabel
import app.narabuspulse.feature.fleet.fleetAge
import app.narabuspulse.feature.fleet.fleetClock
import app.narabuspulse.feature.predict.MiniHeader
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

private val STEP_OPTIONS = listOf("ノンステップ", "ワンステップ", "ツーステップ")
private val YEAR_MODES = listOf("any" to "指定なし", "single" to "単年", "range" to "範囲", "unknown" to "年式不明")
private val SORTS = listOf("number" to "車番順", "year_asc" to "年式が古い順", "year_desc" to "年式が新しい順", "updated" to "更新が新しい順")
private val PHOTO_TYPES = listOf("front" to "前面", "side" to "側面", "rear" to "後部", "interior" to "車内", "other" to "その他")
private val SOURCE_TYPES = listOf("official" to "公式", "document" to "資料", "photo" to "写真", "user" to "ユーザー投稿", "other" to "その他")
private val CONFIDENCES = listOf("official" to "公式確認済み", "verified" to "資料で確認済み", "user" to "ユーザー投稿", "estimated" to "推定", "unknown" to "不明")
val EDITABLE_FIELDS = listOf("vehicle_number" to "車番", "manufacturer" to "メーカー", "vehicle_type" to "型式", "model_year" to "年式", "garage" to "所属営業所", "vehicle_category" to "車両区分", "step_count" to "ステップ数", "note" to "その他備考")

/** 車番の照合キー（Web: lib/catalog/favoriteKey.js）。 */
fun favoriteKey(v: String?): String = java.text.Normalizer.normalize(v ?: "", java.text.Normalizer.Form.NFKC).replace(Regex("[\\s\\-－]"), "").uppercase()
private fun dash(v: String?) = v?.takeIf { it.isNotBlank() } ?: "不明"
fun displayField(v: JsonElement?, key: String): String {
    val s = v.str(key)?.takeIf { it.isNotBlank() } ?: return "不明"
    return if (key == "model_year") "${s}年" else s
}
private fun fmtValue(key: String?, v: String?): String = if (v.isNullOrBlank()) "空" else if (key == "model_year") "${v}年" else v

/** 非公開ファイル（file_uri）を表示用の一時URLにする（Web: usePhotoUrls）。 */
@Composable
fun rememberPhotoUrls(uris: List<String?>): (String?) -> String {
    val list = uris.filterNotNull().filter { it.isNotBlank() && !it.startsWith("http") }.distinct().take(40)
    val urls = produceState(emptyMap<String, String>(), list.joinToString("|")) {
        if (list.isEmpty()) return@produceState
        value = coroutineScope { list.map { u -> async { u to runCatching { Api.signedUrl(u) }.getOrDefault("") } }.awaitAll().toMap() }
    }.value
    return { u -> if (u.isNullOrBlank()) "" else if (u.startsWith("http")) u else urls[u].orEmpty() }
}

/** お気に入り（Web: listCatalogFavorites） */
class CatalogFavorites(private val rows: List<JsonElement>) {
    private val keys = rows.mapNotNull { it.str("key") }.toSet()
    private val codes = rows.mapNotNull { it.str("car_code") }.toSet()
    fun has(v: JsonElement): Boolean = (v.str("car_code")?.let { it in codes } == true) || favoriteKey(v.str("vehicle_number")) in keys
}

/** 車両図鑑（Web: components/catalog/VehicleCatalogPage.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleCatalogScreen() {
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val f = remember { mutableStateMapOf("number" to "", "model" to "", "manufacturer" to "", "yearMode" to "any", "year" to "", "yearFrom" to "", "yearTo" to "", "garage" to "", "category" to "", "step" to "", "sort" to "number", "limit" to "24") }
    var applied by remember { mutableStateOf(f.toMap()) }
    var reloadKey by remember { mutableStateOf(0) }
    val facets = rememberLoad("catalog-facets", reloadKey) { Api.rpc("catalog/fns#getCatalogFacets", JsonNull) }
    val favQ = rememberLoad("catalog-favs") { Api.rpc("catalog/fns#listCatalogFavorites", jsonOf()) }
    val favorites = CatalogFavorites(favQ.data.items("items"))
    var view by remember { mutableStateOf("card") }
    var items by remember { mutableStateOf(listOf<JsonElement>()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var cursor by remember { mutableStateOf<JsonElement?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var filtersOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var favBusy by remember { mutableStateOf("") }
    var csvOpen by remember { mutableStateOf(false) }
    LaunchedEffect(f.toMap()) { delay(350); applied = f.toMap() }
    fun params(extra: Map<String, Any?> = emptyMap()): JsonObject {
        val m = LinkedHashMap<String, Any?>()
        applied.forEach { (k, v) -> if (v.isNotBlank()) m[k] = if (k in setOf("year", "yearFrom", "yearTo", "limit")) v.toIntOrNull() else v }
        return jsonOf(*(m + extra).filterValues { it != null }.entries.map { it.key to it.value }.toTypedArray())
    }
    LaunchedEffect(applied, reloadKey) {
        loading = true; error = ""
        try { val r = Api.rpc("catalog/fns#listCatalog", params()); items = r.items("items"); total = r.int("total"); cursor = (r as? JsonObject)?.get("nextCursor")?.takeIf { it !is JsonNull }; hasMore = r.bool("hasMore") == true }
        catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "車両一覧を読み込めませんでした" } } finally { loading = false }
    }
    fun toggleFavorite(v: JsonElement) {
        val key = favoriteKey(v.str("vehicle_number")); favBusy = key
        scope.launch {
            try {
                val r = Api.rpc("catalog/fns#toggleCatalogFavorite", jsonOf("vehicleNumber" to v.str("vehicle_number"), "carCode" to v.str("car_code")?.ifBlank { null }))
                snackbar.showSnackbar(if (r.bool("favorite") == true) "${v.str("vehicle_number")} をお気に入りに登録しました" else "${v.str("vehicle_number")} をお気に入りから解除しました")
                favQ.reload()
            } catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "お気に入りを更新できませんでした" }) } finally { favBusy = "" }
        }
    }
    val photoUrl = rememberPhotoUrls(items.map { it.str("photo_uri") })
    ScreenList {
        item {
            MiniHeader("車両図鑑", "奈良交通の車両を車番単位で管理する共同編集データベースです。ログイン中のユーザーは誰でも登録・編集・写真追加ができます。") {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("車両を登録", { creating = true }, icon = Icons.Outlined.Add, primary = true)
                    SmallButton("CSV取込", { csvOpen = true }, icon = Icons.Outlined.UploadFile)
                    SmallButton("全車両マップ", { nav.go("/all-vehicles") })
                }
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth().clickable { filtersOpen = !filtersOpen }, verticalAlignment = Alignment.CenterVertically) { Text("検索条件", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(if (filtersOpen) "▴" else "▾") }
                OutlinedTextField(f["number"].orEmpty(), { f["number"] = it }, label = { Text("車番（完全・部分・数字）", fontSize = 12.sp) }, placeholder = { Text("例 奈良200か214 / 214", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (filtersOpen) {
                    OutlinedTextField(f["model"].orEmpty(), { f["model"] = it }, label = { Text("型式", fontSize = 12.sp) }, placeholder = { Text("例 KL-LV280", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        SelectField(listOf("" to "すべて") + facets.data.list("manufacturers").map { it.text to it.text }, f["manufacturer"].orEmpty(), { f["manufacturer"] = it }, label = "メーカー")
                        SelectField(listOf("" to "すべて") + facets.data.list("garages").map { it.text to it.text }, f["garage"].orEmpty(), { f["garage"] = it }, label = "営業所")
                        SelectField(listOf("" to "すべて") + STEP_OPTIONS.map { it to it }, f["step"].orEmpty(), { f["step"] = it }, label = "ステップ数")
                        SelectField(listOf("" to "すべて") + facets.data.list("categories").map { it.text to it.text }, f["category"].orEmpty(), { f["category"] = it }, label = "車両区分")
                        SelectField(YEAR_MODES, f["yearMode"].orEmpty(), { f["yearMode"] = it }, label = "年式")
                        SelectField(SORTS, f["sort"].orEmpty(), { f["sort"] = it }, label = "並び順")
                    }
                    when (f["yearMode"]) {
                        "single" -> OutlinedTextField(f["year"].orEmpty(), { f["year"] = it.filter(Char::isDigit).take(4) }, label = { Text("年式", fontSize = 12.sp) }, placeholder = { Text("1999") }, singleLine = true)
                        "range" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(f["yearFrom"].orEmpty(), { f["yearFrom"] = it.filter(Char::isDigit).take(4) }, placeholder = { Text("2000") }, singleLine = true, modifier = Modifier.weight(1f))
                            Text(" 〜 "); OutlinedTextField(f["yearTo"].orEmpty(), { f["yearTo"] = it.filter(Char::isDigit).take(4) }, placeholder = { Text("2005") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        else -> {}
                    }
                    TextButton(onClick = { val lim = f["limit"]; listOf("number", "model", "manufacturer", "year", "yearFrom", "yearTo", "garage", "category", "step").forEach { f[it] = "" }; f["yearMode"] = "any"; f["sort"] = "number"; f["limit"] = lim ?: "24" }) { Text("条件をクリア", fontSize = 12.sp) }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (loading && items.isEmpty()) "読み込み中…" else "${total ?: items.size} 台が該当", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SelectField(listOf("12", "24", "48", "96").map { it to "${it}件表示" }, f["limit"].orEmpty(), { f["limit"] = it })
                IconButton(onClick = { view = "card" }) { Icon(Icons.Outlined.GridView, "カード表示", tint = if (view == "card") MaterialTheme.colorScheme.primary else Brand.MutedFg) }
                IconButton(onClick = { view = "list" }) { Icon(Icons.Outlined.ViewList, "一覧表示", tint = if (view == "list") MaterialTheme.colorScheme.primary else Brand.MutedFg) }
            }
        }
        if (error.isNotBlank()) item { Column { ErrorText(error); SmallButton("再試行", { reloadKey++ }) } }
        if (items.isEmpty() && !loading && error.isBlank()) item { Muted("条件に一致する車両はありません。条件を変えるか、新しい車両を登録してください。", 12) }
        items(items.size) { i ->
            val v = items[i]
            if (view == "card") CatalogCard(v, photoUrl(v.str("photo_uri")), favorites.has(v), favBusy == favoriteKey(v.str("vehicle_number"))) { toggleFavorite(v) }
            else CatalogRow(v, favorites.has(v)) { toggleFavorite(v) }
        }
        if (hasMore) item {
            SmallButton(if (loading) "読み込み中…" else "さらに表示", {
                val c = cursor ?: return@SmallButton
                loading = true
                scope.launch {
                    try { val r = Api.rpc("catalog/fns#listCatalog", params(mapOf("cursor" to c))); items = items + r.items("items"); cursor = (r as? JsonObject)?.get("nextCursor")?.takeIf { it !is JsonNull }; hasMore = r.bool("hasMore") == true }
                    catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "車両一覧を読み込めませんでした" } } finally { loading = false }
                }
            }, enabled = !loading)
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, confirmButton = {}, title = { Text("車両を登録") }, text = {
        Column(Modifier.horizontalScroll(rememberScrollState(), enabled = false)) {
            Muted("車番が同じ車両が既に登録されている場合は、新規登録せず既存の車両ページを案内します。", 11)
            VehicleEditForm(null, saving, requireReason = false) { fields, reason ->
                saving = true
                scope.launch {
                    try {
                        val r = Api.rpc("catalog/fns#createCatalogEntry", jsonOf("fields" to fields, "reason" to reason))
                        when {
                            r.bool("created") == true -> { snackbar.showSnackbar("${r.str("vehicle.vehicle_number")} を登録しました"); creating = false; reloadKey++ }
                            r.objAt("existing") != null -> { creating = false; nav.go("/vehicle-catalog/${android.net.Uri.encode(r.str("existing.vehicle_number").orEmpty())}"); snackbar.showSnackbar("${r.str("message").orEmpty()}。既存の車両ページを開きます") }
                            else -> { snackbar.showSnackbar(r.str("message") ?: "既に登録されています"); creating = false }
                        }
                    } catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "登録できませんでした" }) } finally { saving = false }
                }
            }
        }
    })
    if (csvOpen) CsvImportDialog({ csvOpen = false }) { reloadKey++ }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCard(v: JsonElement, photo: String, fav: Boolean, favBusy: Boolean, onFav: () -> Unit) {
    val nav = LocalNav.current
    val num = v.str("vehicle_number").orEmpty()
    Panel {
        Row {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                if (photo.isNotBlank()) AsyncImage(photo, null, Modifier.size(84.dp), contentScale = ContentScale.Crop)
                else Icon(Icons.Outlined.DirectionsBus, null, tint = Brand.MutedFg, modifier = Modifier.size(36.dp))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(num, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).clickable { nav.go("/vehicle-catalog/${android.net.Uri.encode(num)}") })
                    IconButton(onClick = onFav, enabled = !favBusy, modifier = Modifier.size(32.dp)) { Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, "お気に入り", tint = if (fav) Brand.Warning else Brand.MutedFg) }
                }
                Muted("${dash(v.str("manufacturer"))} ${dash(v.str("vehicle_type"))}・${v.str("model_year")?.let { "${it}年" } ?: "不明"}", 11)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Tag(dash(v.str("garage")), Brand.MutedFg); Tag(dash(v.str("vehicle_category")), Brand.MutedFg); Tag(dash(v.str("step_count")), Brand.MutedFg)
                    if (!v.str("photo_uri").isNullOrBlank()) Tag("📷 写真あり", INFO)
                }
                v.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
                v.str("updated_by_name")?.takeIf { it.isNotBlank() }?.let { Muted("最終更新 $it", 10) }
            }
        }
        CatalogActions(v)
    }
}

@Composable
private fun CatalogRow(v: JsonElement, fav: Boolean, onFav: () -> Unit) {
    val nav = LocalNav.current
    val num = v.str("vehicle_number").orEmpty()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(num, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).clickable { nav.go("/vehicle-catalog/${android.net.Uri.encode(num)}") })
            IconButton(onClick = onFav, modifier = Modifier.size(28.dp)) { Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, "お気に入り", tint = if (fav) Brand.Warning else Brand.MutedFg) }
        }
        Muted("${dash(v.str("manufacturer"))}／${dash(v.str("vehicle_type"))}／${v.str("model_year")?.let { "${it}年" } ?: "不明"}／${dash(v.str("garage"))}／${dash(v.str("vehicle_category"))}／${dash(v.str("step_count"))}", 11)
        CatalogActions(v)
    }
}

@Composable
private fun CatalogActions(v: JsonElement) {
    val nav = LocalNav.current
    val num = v.str("vehicle_number").orEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { nav.go("/vehicle-catalog/${android.net.Uri.encode(num)}") }) { Text("詳細", fontSize = 11.sp) }
        TextButton(onClick = { nav.go("/all-vehicles?focus=${android.net.Uri.encode(num)}") }) { Text("📍 地図", fontSize = 11.sp) }
        v.str("car_code")?.takeIf { it.isNotBlank() }?.let { c -> TextButton(onClick = { nav.go("/fleet/$c") }) { Text("追跡", fontSize = 11.sp) } }
    }
}

/** 車両の登録・編集フォーム（Web: VehicleEditForm.jsx） */
@Composable
fun VehicleEditForm(initial: JsonElement?, busy: Boolean, requireReason: Boolean, onSubmit: (JsonObject, String) -> Unit) {
    val facets = rememberLoad("catalog-facets-form") { Api.rpc("catalog/fns#getCatalogFacets", JsonNull) }.data
    val values = remember { mutableStateMapOf<String, String>().apply { EDITABLE_FIELDS.forEach { (k, _) -> put(k, initial.str(k).orEmpty()) } } }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val categories = facets.list("categories").map { it.text }.filter { it.isNotBlank() }.let { l -> values["step_count"].orEmpty().let { s -> if (s.isBlank()) l else l.filter { it.contains(s) } } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(values["vehicle_number"].orEmpty(), { values["vehicle_number"] = it }, label = { Text("車番", fontSize = 12.sp) }, placeholder = { Text("例 奈良200か30", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SelectField(listOf("" to "未選択") + facets.list("manufacturers").map { it.text to it.text }, values["manufacturer"].orEmpty(), { values["manufacturer"] = it }, label = "メーカー")
        OutlinedTextField(values["vehicle_type"].orEmpty(), { values["vehicle_type"] = it }, label = { Text("型式", fontSize = 12.sp) }, placeholder = { Text("例 KC-RH4JEAA", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(values["model_year"].orEmpty(), { values["model_year"] = it }, label = { Text("年式", fontSize = 12.sp) }, placeholder = { Text("例 1999", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SelectField(listOf("" to "未選択") + facets.list("garages").map { it.text to it.text }, values["garage"].orEmpty(), { values["garage"] = it }, label = "所属営業所")
        SelectField(listOf("" to "未選択") + STEP_OPTIONS.map { it to it }, values["step_count"].orEmpty(), { values["step_count"] = it }, label = "ステップ数")
        OutlinedTextField(values["vehicle_category"].orEmpty(), { values["vehicle_category"] = it }, label = { Text("車両区分", fontSize = 12.sp) }, placeholder = { Text(values["step_count"]?.takeIf { it.isNotBlank() }?.let { "例 中型$it" } ?: "例 中型ノンステップ", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (categories.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { categories.take(12).forEach { c -> Tag(c, INFO, Modifier.clickable { values["vehicle_category"] = c }) } }
        OutlinedTextField(values["note"].orEmpty(), { values["note"] = it }, label = { Text("その他備考", fontSize = 12.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        if (requireReason) OutlinedTextField(reason, { reason = it }, label = { Text("編集理由（必須）", fontSize = 12.sp) }, placeholder = { Text("例 現地で確認", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        SmallButton(if (busy) "保存中…" else "保存する", {
            error = ""
            if (values["vehicle_number"].isNullOrBlank()) { error = "車番を入力してください"; return@SmallButton }
            if (requireReason && reason.isBlank()) { error = "編集理由を入力してください"; return@SmallButton }
            val year = values["model_year"].orEmpty().filter(Char::isDigit).toIntOrNull()?.takeIf { it in 1900..2100 }
            fun t(k: String) = values[k]?.trim()?.ifBlank { null }
            onSubmit(jsonOf("vehicle_number" to t("vehicle_number"), "manufacturer" to t("manufacturer"), "vehicle_type" to t("vehicle_type"), "model_year" to year, "garage" to t("garage"), "vehicle_category" to t("vehicle_category"), "step_count" to t("step_count"), "note" to t("note")), reason.trim())
        }, enabled = !busy, primary = true)
    }
}

/** CSVで複数台を登録（Web: CsvImportDialog.jsx） */
@Composable
private fun CsvImportDialog(onClose: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(listOf<JsonElement>()) }
    var invalid by remember { mutableStateOf(listOf<String>()) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    val headers = produceState(listOf("車番", "メーカー", "型式", "年式", "所属営業所", "車両区分", "ステップ数", "その他備考")) { Logic.constant("catalog/csv", "TEMPLATE_HEADERS").items().map { it.text }.takeIf { it.isNotEmpty() }?.let { value = it } }.value
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = ""
        scope.launch {
            try {
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val grid = Logic.call("catalog/csv", "parseCsv", text)
                val r = Logic.call("catalog/csv", "buildCsvRows", grid)
                r.str("error")?.let { rows = emptyList(); invalid = emptyList(); name = ""; error = it; return@launch }
                rows = r.list("rows"); invalid = r.list("invalid").map { it.text }; name = uri.lastPathSegment ?: "CSV"; progress = 0
            } catch (e: Throwable) { if (e is CancellationException) throw e; rows = emptyList(); error = "CSVを読み取れませんでした" } finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("CSVから車両を登録") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Muted("列は「${headers.joinToString("・")}」。車番の列があれば、他の列が無くても登録できます。既に同じ車番の車両がある場合は登録しません。", 11)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("CSVを選ぶ", { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }, enabled = !busy, icon = Icons.Outlined.UploadFile)
                SmallButton("見本CSVを保存", { scope.launch { runCatching { snackbar.showSnackbar("${Export.csv(context, "vehicle-template.csv", headers, emptyList())} に保存しました") } } })
            }
            if (name.isNotBlank()) Muted("$name　取込対象 ${rows.size}台${if (invalid.isNotEmpty()) "／車番なし ${invalid.size}行（${invalid.take(6).joinToString(", ")}行目）" else ""}", 11)
            if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
            if (busy && progress > 0) Muted("$progress / ${rows.size} 台を登録しました…", 11)
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (rows.isEmpty() || busy) return@TextButton
            busy = true; error = ""
            scope.launch {
                var created = 0; var skipped = 0
                try {
                    rows.chunked(200).forEachIndexed { i, chunk ->
                        val r = Api.rpc("catalog/fns#importCatalogCsv", jsonOf("rows" to JsonArray(chunk), "reason" to "CSV取込"))
                        created += r.int("created") ?: 0; skipped += r.int("skipped") ?: 0; progress = minOf((i + 1) * 200, rows.size)
                    }
                    snackbar.showSnackbar(listOfNotNull("${created}台を登録しました", if (skipped > 0) "${skipped}台は登録済みのためスキップ" else null, if (invalid.isNotEmpty()) "車番なしの${invalid.size}行は除外" else null).joinToString("／"))
                    onImported(); onClose()
                } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "取込に失敗しました" }; onImported() } finally { busy = false }
            }
        }, enabled = !busy && rows.isNotEmpty()) { Text("${rows.size}台を取り込む") }
    }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("閉じる") } })
}

/** 車両詳細（Web: vehicle-catalog.$fleetNumber.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogDetailScreen(number: String) {
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val q = rememberLoad("catalog-entry", number) { Api.rpc("catalog/fns#getCatalogEntry", jsonOf("number" to number)) }
    val favQ = rememberLoad("catalog-favs") { Api.rpc("catalog/fns#listCatalogFavorites", jsonOf()) }
    var editing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var favOn by remember { mutableStateOf<Boolean?>(null) }
    var favBusy by remember { mutableStateOf(false) }
    val v = q.data?.objAt("vehicle")
    ScreenList {
        if (q.loading && q.data == null) item { Muted("読み込み中…", 12) }
        if (q.error != null && q.data == null) item { ErrorText(q.error!!) }
        if (q.data != null && q.data.bool("found") == false) item { Column { Muted("$number は車両図鑑に登録されていません。", 12); TextButton(onClick = { nav.go("/vehicle-catalog") }) { Text("車両図鑑で登録する") } } }
        if (v != null) {
            val num = v.str("vehicle_number").orEmpty()
            val code = v.str("car_code")?.ifBlank { null }
            val favorited = favOn ?: CatalogFavorites(favQ.data.items("items")).has(v)
            val canDelete = Session.isAdmin || (v.str("created_by_id")?.isNotBlank() == true && v.str("created_by_id") == Session.user.value.str("id"))
            item { MiniHeader(num, "${displayField(v, "manufacturer")} ${displayField(v, "vehicle_type")}・${displayField(v, "model_year")}／${displayField(v, "garage")}／${displayField(v, "step_count")}") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("← 図鑑", { nav.go("/vehicle-catalog") })
                    SmallButton("全車両マップで表示", { nav.go("/all-vehicles?focus=${android.net.Uri.encode(num)}") })
                    if (code != null) { SmallButton("この車両を追跡", { nav.go("/fleet/$code") }); SmallButton("運行履歴を見る", { nav.go("/fleet/$code?tab=runs") }) }
                    SmallButton(if (favorited) "お気に入り済み" else "お気に入り", {
                        favBusy = true; favOn = !favorited
                        scope.launch {
                            try { val r = Api.rpc("catalog/fns#toggleCatalogFavorite", jsonOf("vehicleNumber" to num, "carCode" to code)); favOn = r.bool("favorite") == true; snackbar.showSnackbar(if (favOn == true) "$num をお気に入りに登録しました" else "$num をお気に入りから解除しました"); favQ.reload() }
                            catch (e: Throwable) { if (e is CancellationException) throw e; favOn = favorited; snackbar.showSnackbar(e.userMessage().ifBlank { "お気に入りを更新できませんでした" }) } finally { favBusy = false }
                        }
                    }, enabled = !favBusy, icon = if (favorited) Icons.Filled.Star else Icons.Outlined.StarOutline)
                    SmallButton("編集", { editing = true }, icon = Icons.Outlined.Edit, primary = true)
                    if (canDelete) SmallButton("削除", {
                        confirm.ask("$num を削除しますか？\nこの車両の図鑑情報と、紐づく写真・編集履歴・出典がすべて削除されます。元に戻せません。") {
                            scope.launch {
                                try { val r = Api.rpc("catalog/fns#deleteCatalogEntry", jsonOf("id" to v.str("id"))); snackbar.showSnackbar("${r.str("vehicle_number") ?: num} を削除しました"); nav.go("/vehicle-catalog") }
                                catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "削除できませんでした" }) }
                            }
                        }
                    }, icon = Icons.Outlined.Delete)
                }
            }
            item {
                Panel(title = "車両情報") {
                    EDITABLE_FIELDS.forEach { (k, l) -> KeyValue(l, displayField(v, k)) }
                    v.str("updated_by_name")?.takeIf { it.isNotBlank() }?.let { Muted("最終更新: $it", 10) }
                }
            }
            item { LiveStatusPanel(v) }
            item { PhotoSection(v.str("id").orEmpty(), q.data.items("photos")) { q.reload() } }
            item { HistorySection(q.data.items("history")) { q.reload() } }
            item { SourceSection(v.str("id").orEmpty(), q.data.items("sources")) { q.reload() } }
            if (editing) item {
                AlertDialog(onDismissRequest = { editing = false }, confirmButton = {}, title = { Text("$num の情報を編集") }, text = {
                    Column {
                        Muted("変更は誰でも保存できます。保存すると編集履歴に記録され、あとから元に戻せます。", 11)
                        VehicleEditForm(v, saving, requireReason = true) { fields, reason ->
                            saving = true
                            scope.launch {
                                try { val r = Api.rpc("catalog/fns#updateCatalogEntry", jsonOf("id" to v.str("id"), "changes" to fields, "reason" to reason)); snackbar.showSnackbar(r.int("updated")?.takeIf { it > 0 }?.let { "${it}項目を更新しました" } ?: "変更はありません"); editing = false; q.reload() }
                                catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "保存できませんでした" }) } finally { saving = false }
                            }
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun LiveStatusPanel(v: JsonElement) {
    val nav = LocalNav.current
    val num = v.str("vehicle_number").orEmpty()
    val q = rememberLoad("catalog-live", num) { Api.rpc("catalog/fns#getCatalogLiveStatus", jsonOf("number" to num, "carCode" to v.str("car_code").orEmpty(), "plateNo" to num)) }
    val live = q.data?.objAt("vehicle"); val last = q.data?.objAt("lastKnown")
    Panel(title = "🧭 現在の運行状況") {
        if (q.loading && q.data == null) Muted("取得中…", 11)
        if (!q.loading && live != null) {
            KeyValue("運行状態", if (live.bool("stale") == true) "位置情報が古い" else "運行中")
            KeyValue("系統", live.str("route")?.ifBlank { null } ?: "取得不可"); KeyValue("行先", live.str("destination")?.ifBlank { null } ?: "取得不可")
            KeyValue("現在位置付近", live.str("stop")?.ifBlank { null } ?: "取得不可"); KeyValue("進行方向", compassLabel(live.dbl("direction")))
            val at = live.long("at"); KeyValue("最終更新", if (at != null) "${fleetClock(at)}（${fleetAge(at)}）" else "取得不可")
            Row {
                v.str("car_code")?.takeIf { it.isNotBlank() }?.let { c -> TextButton(onClick = { nav.go("/fleet/$c") }) { Text("地図で現在位置を見る", fontSize = 11.sp) } }
                TextButton(onClick = { nav.go("/all-vehicles?focus=${android.net.Uri.encode(num)}") }) { Text("📍 全車両マップで表示", fontSize = 11.sp) }
            }
        }
        if (!q.loading && live == null && q.data != null) {
            Text("現在位置を取得できません", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Muted("バスロケに現在表示されていない車両です。位置を推測して表示することはしません。", 11)
            last?.long("at")?.let { at -> Muted("最終確認: ${fleetClock(at)}（${fleetAge(at)}）${last.str("stop_name")?.takeIf { it.isNotBlank() }?.let { "・$it 付近" } ?: ""}${last.str("route")?.takeIf { it.isNotBlank() }?.let { "・系統 $it" } ?: ""}", 11) }
        }
        q.error?.let { Text(it, fontSize = 11.sp, color = Brand.Destructive) }
    }
}

@Composable
private fun PhotoSection(vehicleId: String, photos: List<JsonElement>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val getUrl = rememberPhotoUrls(photos.map { it.str("image_url") })
    var file by remember { mutableStateOf<android.net.Uri?>(null) }
    var type by remember { mutableStateOf("side") }
    var description by remember { mutableStateOf("") }
    var takenAt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { file = it }
    Panel(title = "📷 写真（${photos.size}枚）") {
        if (photos.isEmpty()) Muted("まだ写真はありません。", 12)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { p ->
                Column(Modifier.width(180.dp)) {
                    val url = getUrl(p.str("image_url"))
                    if (url.isNotBlank()) AsyncImage(url, p.str("description") ?: "車両写真", Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Muted("読み込み中…", 10) }
                    Text(PHOTO_TYPES.firstOrNull { it.first == p.str("image_type") }?.second ?: "写真", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    p.str("description")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
                    Muted("${p.str("photographed_at")?.takeIf { it.isNotBlank() }?.let { "$it 撮影" } ?: "撮影日不明"}${p.str("uploader_name")?.takeIf { it.isNotBlank() }?.let { "／$it" } ?: ""}", 10)
                    val id = p.str("id").orEmpty()
                    if (pendingDelete == id) Row {
                        TextButton(onClick = {
                            busy = true
                            scope.launch {
                                try { Api.rpc("catalog/fns#deleteCatalogPhoto", jsonOf("id" to id)); pendingDelete = null; snackbar.showSnackbar("写真を削除しました"); onChanged() }
                                catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "写真を削除できませんでした" }) } finally { busy = false }
                            }
                        }, enabled = !busy) { Text("削除する", fontSize = 11.sp, color = Brand.Destructive) }
                        TextButton(onClick = { pendingDelete = null }) { Text("やめる", fontSize = 11.sp) }
                    } else TextButton(onClick = { pendingDelete = id }) { Text("🗑 削除", fontSize = 11.sp) }
                }
            }
        }
        Text("写真を追加（複数枚は1枚ずつ登録できます）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (file != null) "写真を選択済み" else "写真を選ぶ", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, icon = Icons.Outlined.CameraAlt)
            SelectField(PHOTO_TYPES, type, { type = it })
            DateField(takenAt, { takenAt = it }, label = if (takenAt.isBlank()) "撮影日" else null)
        }
        OutlinedTextField(description, { description = it }, placeholder = { Text("説明", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SmallButton(if (busy) "登録中…" else "登録する", {
            val u = file ?: return@SmallButton
            busy = true
            scope.launch {
                try {
                    val mime = context.contentResolver.getType(u) ?: "image/jpeg"
                    val bytes = context.contentResolver.openInputStream(u)!!.use { it.readBytes() }
                    val uri = Api.uploadPrivateFile("vehicle.${mime.substringAfter('/')}", mime, bytes)
                    Api.rpc("catalog/fns#addCatalogPhoto", jsonOf("vehicleId" to vehicleId, "fileUri" to uri, "imageType" to type, "description" to description.trim(), "photographedAt" to takenAt))
                    file = null; description = ""; takenAt = ""; snackbar.showSnackbar("写真を登録しました"); onChanged()
                } catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "写真を登録できませんでした" }) } finally { busy = false }
            }
        }, enabled = file != null && !busy, primary = true, modifier = Modifier.padding(top = 4.dp))
        Muted("写真は非公開で保存され、アプリ内でのみ表示されます。", 10)
    }
}

private val OPERATION = mapOf("create" to ("新規登録" to Brand.Success), "update" to ("編集" to INFO), "restore" to ("復元" to Brand.Warning))

@Composable
private fun HistorySection(history: List<JsonElement>, onRestored: () -> Unit) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val restorable = EDITABLE_FIELDS.map { it.first }.toSet()
    Panel(title = "🕘 編集履歴（${history.size}件）", subtitle = "誰でも編集できるため、すべての変更を記録しています。履歴の削除はできません。") {
        if (history.isEmpty()) Muted("まだ編集履歴はありません。", 12)
        history.forEachIndexed { i, row ->
            if (i > 0) RowDivider()
            val field = row.str("changed_field"); val id = row.str("id").orEmpty()
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (l, c) = OPERATION[row.str("operation_type")] ?: (row.str("operation_type").orEmpty() to Brand.MutedFg)
                    Tag(l, c); Text(row.str("changed_field_label") ?: field.orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted(fmtDateTime(row.long("changed_at")), 10)
                }
                Text("${fmtValue(field, row.str("old_value"))} → ${fmtValue(field, row.str("new_value"))}", fontSize = 12.sp)
                Muted("${row.str("changed_by_name") ?: "ユーザー"}${row.str("change_reason")?.takeIf { it.isNotBlank() }?.let { "／理由: $it" } ?: ""}", 10)
                if (row.str("operation_type") != "create" && field in restorable) {
                    if (pending == id) {
                        Muted("「${row.str("changed_field_label")}」を「${fmtValue(field, row.str("old_value"))}」に戻します。よろしいですか？", 11)
                        Row {
                            TextButton(onClick = {
                                busy = true
                                scope.launch {
                                    try { val r = Api.rpc("catalog/fns#restoreCatalogEntry", jsonOf("historyId" to id, "reason" to "編集履歴から復元")); snackbar.showSnackbar(if (r.bool("restored") == true) "以前の値に戻しました" else r.str("message") ?: "変更はありません"); pending = null; onRestored() }
                                    catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "復元できませんでした" }) } finally { busy = false }
                                }
                            }, enabled = !busy) { Text("復元する", fontSize = 11.sp) }
                            TextButton(onClick = { pending = null }) { Text("やめる", fontSize = 11.sp) }
                        }
                    } else TextButton(onClick = { pending = id }) { Text("↶ この変更を元に戻す", fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun SourceSection(vehicleId: String, sources: List<JsonElement>, onChanged: () -> Unit) {
    val uri = LocalUriHandler.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("user") }
    var verifiedAt by remember { mutableStateOf("") }
    var confidence by remember { mutableStateOf("user") }
    var busy by remember { mutableStateOf(false) }
    Panel(title = "出典・資料（${sources.size}件）", action = { TextButton(onClick = { open = !open }) { Text("＋ 出典を追加", fontSize = 11.sp) } }) {
        if (sources.isEmpty()) Muted("出典は未登録です。", 12)
        sources.forEach { s ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text("${s.str("source_name").orEmpty()}（${SOURCE_TYPES.firstOrNull { it.first == s.str("source_type") }?.second ?: "その他"}${s.str("verified_at")?.takeIf { it.isNotBlank() }?.let { "・${it}確認" } ?: ""}）", fontSize = 12.sp)
                s.str("source_url")?.takeIf { it.isNotBlank() }?.let { u -> Text("リンク", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { runCatching { uri.openUri(u) } }) }
                s.str("notes")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
            }
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(name, { name = it }, placeholder = { Text("出典の名称（例 現地撮影）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, placeholder = { Text("出典URL（任意）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectField(SOURCE_TYPES, type, { type = it }); SelectField(CONFIDENCES, confidence, { confidence = it }); DateField(verifiedAt, { verifiedAt = it }, label = if (verifiedAt.isBlank()) "確認日" else null)
            }
            SmallButton(if (busy) "追加中…" else "追加する", {
                busy = true
                scope.launch {
                    try { Api.rpc("catalog/fns#addCatalogSource", jsonOf("vehicleId" to vehicleId, "sourceName" to name.trim(), "sourceUrl" to url.trim(), "sourceType" to type, "verifiedAt" to verifiedAt, "confidence" to confidence)); name = ""; url = ""; open = false; snackbar.showSnackbar("出典を追加しました"); onChanged() }
                    catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "追加できませんでした" }) } finally { busy = false }
                }
            }, enabled = !busy && name.isNotBlank(), primary = true)
        }
    }
}
