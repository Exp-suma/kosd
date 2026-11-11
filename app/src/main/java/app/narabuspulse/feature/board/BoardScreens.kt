package app.narabuspulse.feature.board

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.objAt
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.predict.MiniHeader
import app.narabuspulse.feature.predict.PURPLE
import app.narabuspulse.nav.LocalNav
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val CATEGORY_OPTIONS = listOf("sighting" to "車両目撃情報", "vehicle_info" to "車両情報", "new_vehicle" to "新車・導入情報", "retire" to "廃車・除籍情報", "transfer" to "転属・配置変更", "operation" to "運用情報", "route" to "ダイヤ・路線情報", "photo" to "撮影情報", "other" to "その他")
private val CATEGORY_COLOR = mapOf("sighting" to INFO, "vehicle_info" to Brand.Success, "new_vehicle" to PURPLE, "retire" to Brand.Destructive, "transfer" to Brand.Orange, "operation" to Brand.Warning, "photo" to PURPLE)
val VERIFY_LABEL = mapOf("unverified" to "未確認", "reported" to "投稿者による報告", "agreed" to "複数投稿で一致", "confirmed" to "管理者確認済み", "conflict" to "情報が矛盾", "stale" to "古い情報", "retracted" to "取り消し")
private val VERIFY_COLOR = mapOf("reported" to INFO, "agreed" to PURPLE, "confirmed" to Brand.Success, "conflict" to Brand.Destructive, "stale" to Brand.Warning)
private val SORTS = listOf("new" to "新着順", "popular" to "人気順", "comments" to "コメント順")
private val JST = ZoneId.of("Asia/Tokyo")
private val DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(JST)

@Composable fun VerifyBadge(status: String?) = Tag(VERIFY_LABEL[status ?: "reported"] ?: "未確認", VERIFY_COLOR[status ?: "reported"] ?: Brand.MutedFg)
@Composable fun CategoryBadge(c: String?) = Tag(CATEGORY_OPTIONS.firstOrNull { it.first == c }?.second ?: "その他", CATEGORY_COLOR[c] ?: Brand.MutedFg)
@Composable fun LinkBadge(link: String?) { when (link) { "linked" -> Tag("車両登録済み", Brand.Success); "unregistered" -> Tag("未登録車両", Brand.Warning); else -> {} } }

fun fmtDateTime(ms: Long?): String = if (ms == null || ms == 0L) "未設定" else DTF.format(Instant.ofEpochMilli(ms))
fun fmtRelative(ms: Long?): String {
    if (ms == null || ms == 0L) return "—"
    val d = System.currentTimeMillis() - ms
    return when { d < 60_000 -> "たった今"; d < 3_600_000 -> "${d / 60_000}分前"; d < 86_400_000 -> "${d / 3_600_000}時間前"; d < 30 * 86_400_000L -> "${d / 86_400_000}日前"; else -> fmtDateTime(ms).take(10) }
}
fun excerpt(t: String?, n: Int = 110): String { val s = (t ?: "").replace(Regex("\\s+"), " ").trim(); return if (s.length > n) s.take(n) + "…" else s }

/** 未ログインでも投稿者を区別する端末キー（Web: lib/board/client.js の guestKey）。 */
fun guestKey(context: Context): String {
    val p = context.getSharedPreferences("board", Context.MODE_PRIVATE)
    var k = p.getString("nara_board_guest_key", null)
    if (k.isNullOrBlank()) { k = java.lang.Long.toString((Math.random() * 1e16).toLong(), 36) + java.lang.Long.toString(System.currentTimeMillis(), 36); p.edit().putString("nara_board_guest_key", k).apply() }
    return k.filter { it.isLetterOrDigit() }.take(40)
}

private fun imagesOf(post: JsonElement?): List<String> = runCatching { (Json.parseToJsonElement(post.str("images") ?: "[]") as JsonArray).mapNotNull { it.str("file_uri") } }.getOrDefault(emptyList())

@Composable
fun rememberSignedUrls(uris: List<String>): Map<String, String> = produceState(emptyMap<String, String>(), uris.joinToString("|")) {
    if (uris.isEmpty()) return@produceState
    value = runCatching { Api.rpc("board/server-fns#getBoardImageUrls", jsonOf("uris" to uris)).obj?.mapValues { it.value.text }.orEmpty() }.getOrDefault(emptyMap())
}.value

/** みんなの掲示板（Web: board.index.jsx） */
@Composable
fun BoardScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("new") }
    var category by remember { mutableStateOf("all") }
    var vehicle by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<JsonElement>()) }
    var cursor by remember { mutableStateOf<JsonElement?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    fun filters() = jsonOf("sort" to sort, "category" to category, "q" to q, "vehicle" to vehicle)
    LaunchedEffect(q, sort, category, vehicle) {
        loading = true; error = ""
        delay(300)
        try { val r = Api.rpc("board/server-fns#getBoardFeed", filters()); items = r.items("items"); cursor = (r as? JsonObject)?.get("nextCursor")?.takeIf { it !is kotlinx.serialization.json.JsonNull }; hasMore = r.bool("hasMore") == true }
        catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "読み込めませんでした" } } finally { loading = false }
    }
    val thumbs = rememberSignedUrls(items.mapNotNull { imagesOf(it).firstOrNull() }.take(24))
    ScreenList {
        item {
            MiniHeader("みんなの掲示板", "車両の目撃情報・形式・所属などの情報を、みんなで蓄積する掲示板です。投稿はAIが解析して車両一覧に紐付けますが、管理者が確認するまで確定情報にはなりません。") {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallButton("新規投稿", { nav.go("/board/new") }, icon = Icons.Outlined.EditNote, primary = true)
                    SmallButton("車両一覧", { nav.go("/vehicle-db") })
                }
            }
        }
        item {
            Panel {
                OutlinedTextField(q, { q = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("タイトル・本文・車両番号で検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SelectField(SORTS, sort, { sort = it })
                    SelectField(listOf("all" to "すべてのカテゴリー") + CATEGORY_OPTIONS, category, { category = it })
                }
                OutlinedTextField(vehicle, { vehicle = it }, placeholder = { Text("車両番号で絞り込み", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        if (error.isNotBlank()) item { ErrorText(error) }
        if (loading && items.isEmpty()) item { Muted("読み込み中…", 12) }
        if (!loading && error.isBlank() && items.isEmpty()) item { Row(verticalAlignment = Alignment.CenterVertically) { Muted("条件に合う投稿がありません。", 12); TextButton(onClick = { nav.go("/board/new") }) { Text("最初の投稿を書く", fontSize = 12.sp) } } }
        items(items.size) { i -> PostCard(items[i], imagesOf(items[i]).firstOrNull()?.let { thumbs[it] }) }
        if (hasMore) item {
            SmallButton(if (loading) "読み込み中…" else "さらに読み込む", {
                val c = cursor ?: return@SmallButton
                loading = true
                scope.launch {
                    try {
                        val data = (filters() + ("cursor" to c)).let { JsonObject(it) }
                        val r = Api.rpc("board/server-fns#getBoardFeed", data); items = items + r.items("items"); cursor = (r as? JsonObject)?.get("nextCursor")?.takeIf { it !is kotlinx.serialization.json.JsonNull }; hasMore = r.bool("hasMore") == true
                    } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "読み込めませんでした" } } finally { loading = false }
                }
            }, enabled = !loading)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostCard(post: JsonElement, thumb: String?) {
    val nav = LocalNav.current
    Panel(modifier = Modifier.clickable { nav.go("/board/${post.str("id")}") }) {
        Row {
            if (thumb != null) AsyncImage(thumb, null, Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)).padding(end = 8.dp), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(post.str("category")); VerifyBadge(post.str("verify_status"))
                    Muted("${fmtRelative(post.long("at"))}・${post.str("author_name")?.ifBlank { null } ?: "ゲスト"}", 10)
                }
                Text(post.str("title").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Muted(excerpt(post.str("body"), 96), 11)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    post.str("vehicle_number")?.takeIf { it.isNotBlank() }?.let { Tag(it, Brand.Orange) }
                    post.str("location_name")?.takeIf { it.isNotBlank() }?.let { Muted("📍 ${excerpt(it, 18)}", 10) }
                    Muted("♥ ${post.int("like_count") ?: 0}　💬 ${post.int("comment_count") ?: 0}", 10)
                }
            }
        }
    }
}

/** 新規投稿（Web: board.new.jsx） */
@Composable
fun BoardNewScreen(vehicle: String?) {
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    ScreenList {
        item { MiniHeader("新規投稿", "分かる範囲だけで構いません。投稿するとAIが車両番号や営業所などを抽出し、車両一覧との紐付けを行います。") }
        item {
            PostForm(null, vehicle?.take(40)?.let { mapOf("vehicle_number" to it) } ?: emptyMap(), onDone = { r ->
                scope.launch { snackbar.showSnackbar("投稿しました。AIが情報を整理しています。") }
                val id = r.str("id"); if (id != null) nav.go("/board/$id") else nav.go("/board")
            }, onCancel = { nav.go("/board") })
        }
    }
}

private val TEXT_FIELDS = listOf("garage" to "営業所", "vehicle_type" to "車両形式", "registration_number" to "登録番号", "route" to "路線・系統", "destination" to "行先", "location_name" to "目撃場所")
private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/** 投稿フォーム（Web: PostForm.jsx） */
@Composable
fun PostForm(post: JsonElement?, initial: Map<String, String>, onDone: (JsonElement) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys = listOf("title", "body", "category", "vehicle_number", "vehicle_type", "registration_number", "garage", "route", "destination", "location_name", "source", "extra")
    val form = remember { mutableStateMapOf<String, String>().apply { keys.forEach { put(it, post.str(it) ?: initial[it] ?: if (it == "category") "sighting" else "") } } }
    var sighted by remember { mutableStateOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(post.long("sighted_at") ?: System.currentTimeMillis()), JST).format(LOCAL)) }
    var images by remember { mutableStateOf(imagesOf(post)) }
    var lat by remember { mutableStateOf(post.dbl("lat")) }
    var lon by remember { mutableStateOf(post.dbl("lon")) }
    var busy by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        val pick = uris.take(3 - images.size); if (pick.isEmpty()) return@rememberLauncherForActivityResult
        uploading = true; error = ""
        scope.launch {
            try {
                val out = pick.map { u ->
                    val mime = context.contentResolver.getType(u) ?: "image/jpeg"
                    if (!mime.startsWith("image/")) throw IllegalArgumentException("画像ファイルを選んでください")
                    val bytes = context.contentResolver.openInputStream(u)!!.use { it.readBytes() }
                    if (bytes.size > 8 * 1024 * 1024) throw IllegalArgumentException("画像は1枚8MBまでです")
                    Api.uploadPrivateFile("board.${mime.substringAfter('/')}", mime, bytes)
                }
                images = (images + out).take(3)
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.message ?: "画像をアップロードできませんでした" } finally { uploading = false }
        }
    }
    val locate = rememberLocationRequest { loc -> if (loc == null) error = "現在地を取得できませんでした" else { lat = loc.lat; lon = loc.lon } }
    @Composable fun F(k: String, label: String, ph: String = "", lines: Int = 1) = OutlinedTextField(form[k].orEmpty(), { form[k] = it }, label = { Text(label, fontSize = 12.sp) }, placeholder = { Text(ph, fontSize = 12.sp) }, singleLine = lines == 1, minLines = lines, modifier = Modifier.fillMaxWidth())
    Panel {
        F("title", "タイトル（必須）", "例: 12-5006を○○営業所付近で目撃")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectField(CATEGORY_OPTIONS, form["category"].orEmpty(), { form["category"] = it }, label = "カテゴリー")
            OutlinedTextField(form["vehicle_number"].orEmpty(), { form["vehicle_number"] = it }, label = { Text("車両番号", fontSize = 12.sp) }, placeholder = { Text("12-5006") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        F("body", "本文（必須）", "分かる範囲で構いません。目撃場所・日時・系統など、確認できたことを書いてください。", 5)
        TEXT_FIELDS.forEach { (k, l) -> F(k, l) }
        OutlinedTextField(sighted, { sighted = it }, label = { Text("目撃日時（YYYY-MM-DDTHH:mm）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        F("source", "情報源", "現地確認 / 公式発表 / SNS など")
        F("extra", "補足情報", "", 2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            SmallButton(if (uploading) "アップロード中…" else "写真を追加（最大3枚）", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !uploading && images.size < 3, icon = Icons.Outlined.CameraAlt)
            Muted("${images.size}枚", 11)
            SmallButton(if (lat != null) "現在地を設定済み" else "現在地を添付", { locate() }, icon = Icons.Outlined.MyLocation)
        }
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = Brand.Destructive)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            SmallButton("キャンセル", onCancel)
            SmallButton(if (busy) "送信中…" else if (post != null) "更新する" else "投稿する", {
                if (form["title"].isNullOrBlank() || form["body"].isNullOrBlank()) { error = "タイトルと本文を入力してください"; return@SmallButton }
                busy = true; error = ""
                scope.launch {
                    try {
                        val ms = runCatching { LocalDateTime.parse(sighted.take(16), LOCAL).atZone(JST).toInstant().toEpochMilli() }.getOrNull()
                        val payload = form.toMap().mapValues { it.value as Any? } + mapOf("sighted_at" to ms, "lat" to lat, "lon" to lon, "images" to images, "guestKey" to guestKey(context)) + (post?.str("id")?.let { mapOf("id" to it) } ?: emptyMap())
                        val r = Api.rpc(if (post != null) "board/server-fns#updateBoardPost" else "board/server-fns#createBoardPost", jsonOf(*payload.entries.map { it.key to it.value }.toTypedArray()))
                        onDone(r)
                    } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "投稿できませんでした" } } finally { busy = false }
                }
            }, enabled = !busy && !uploading, primary = true)
        }
        Muted("投稿された内容はAIが車両番号などを抽出し、車両一覧への紐付けを行います。抽出結果は「投稿者による報告」として扱われ、管理者が確認するまで確定情報にはなりません。", 10)
    }
}

/** 投稿の詳細（Web: board.$postId.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoardPostScreen(postId: String) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val gk = remember { guestKey(context) }
    val q = rememberLoad("board-post", postId) { Api.rpc("board/server-fns#getBoardPost", jsonOf("id" to postId, "guestKey" to gk)) }
    var editing by remember { mutableStateOf(false) }
    val post = q.data?.objAt("post")
    val imgs = imagesOf(post)
    val urls = rememberSignedUrls(imgs)
    ScreenList {
        if (q.loading && q.data == null) item { Muted("読み込み中…", 12) }
        q.error?.let { item { ErrorText(it) } }
        if (q.data != null && post == null) item {
            Column { Muted(if (q.data.bool("deleted") == true) "この投稿は削除されています（AI検索の対象外です）。" else "投稿が見つかりません。", 12); TextButton(onClick = { nav.go("/board") }) { Text("掲示板へ戻る") } }
        }
        if (post != null) {
            item { MiniHeader(post.str("title").orEmpty(), "${fmtDateTime(post.long("at"))} 投稿・${post.str("author_name")?.ifBlank { null } ?: "ゲスト"}") { TextButton(onClick = { nav.go("/board") }) { Text("← 掲示板", fontSize = 12.sp) } } }
            item {
                Panel {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CategoryBadge(post.str("category")); VerifyBadge(post.str("verify_status")); LinkBadge(post.str("vehicle_link"))
                        if (post.str("status") == "hidden") Tag("管理者により非公開", Brand.Destructive)
                    }
                    Text(post.str("body").orEmpty(), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    if (imgs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        imgs.forEach { u -> urls[u]?.let { url -> AsyncImage(url, null, Modifier.height(160.dp).clip(RoundedCornerShape(6.dp)).clickable { runCatching { uri.openUri(url) } }, contentScale = ContentScale.Fit) } }
                    }
                    KeyValue("投稿日時", fmtDateTime(post.long("at")))
                    KeyValue("目撃日時", post.long("sighted_at")?.let { fmtDateTime(it) } ?: "記載なし")
                    KeyValue("車両番号", post.str("vehicle_number")?.ifBlank { null } ?: "記載なし")
                    KeyValue("車両形式", post.str("vehicle_type")?.ifBlank { null } ?: "未確認")
                    KeyValue("登録番号", post.str("registration_number")?.ifBlank { null } ?: "未確認")
                    KeyValue("営業所", post.str("garage")?.ifBlank { null } ?: "未確認")
                    KeyValue("路線・系統", post.str("route")?.ifBlank { null } ?: "記載なし")
                    KeyValue("行先", post.str("destination")?.ifBlank { null } ?: "記載なし")
                    KeyValue("目撃場所", post.str("location_name")?.ifBlank { null } ?: "記載なし")
                    KeyValue("情報源", post.str("source")?.ifBlank { null } ?: "記載なし")
                    post.str("extra")?.takeIf { it.isNotBlank() }?.let { Muted("補足: $it", 11) }
                    PostActions(post, q.data.list("mine").map { it.text }, q.data.str("viewer.key"), onChanged = { q.reload() }, onEdit = { editing = true })
                }
            }
            if (editing) item {
                Column { Text("投稿を編集", fontSize = 15.sp, fontWeight = FontWeight.Bold); PostForm(post, emptyMap(), onDone = { editing = false; q.reload() }, onCancel = { editing = false }) }
            } else item { CommentSection(post.str("id").orEmpty(), q.data.items("comments")) { q.reload() } }
            val v = q.data.objAt("vehicle")
            item {
                if (v != null) Panel(title = "🚌 関連車両") {
                    Text(v.str("vehicle_number").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { nav.go("/vehicle-db/${android.net.Uri.encode(v.str("vehicle_number").orEmpty())}") })
                    Muted("${v.str("garage")?.let { "営業所: $it" } ?: "営業所: 未確認"}・投稿${v.int("post_count") ?: 0}件", 11)
                    VehicleFacts(q.data.items("facts").take(6))
                } else if (!post.str("vehicle_number").isNullOrBlank()) Muted("${post.str("vehicle_number")} は車両一覧に未登録です。管理者が同期・確認すると紐付けされます。推測で車両を新規登録することはありません。", 11)
            }
            post.str("location_name")?.takeIf { it.isNotBlank() }?.let { loc ->
                item {
                    Panel(title = "📍 目撃場所") {
                        Text(loc, fontSize = 12.sp)
                        val la = post.dbl("lat"); val lo = post.dbl("lon")
                        if (la != null && lo != null) TextButton(onClick = { runCatching { uri.openUri("https://www.google.com/maps?q=$la,$lo") } }) { Text("Googleマップで開く", fontSize = 12.sp) }
                    }
                }
            }
            val related = q.data.items("related")
            if (related.isNotEmpty()) item {
                Panel(title = "この車両のほかの投稿") {
                    related.forEach { p -> Column(Modifier.fillMaxWidth().clickable { nav.go("/board/${p.str("id")}") }.padding(vertical = 4.dp)) { Text(p.str("title").orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary); Muted(fmtDateTime(p.long("at")), 10) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostActions(post: JsonElement, mine: List<String>, viewerKey: String?, onChanged: () -> Unit, onEdit: () -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val active = remember { mutableStateMapOf("like" to ("like" in mine), "save" to ("save" in mine), "report" to ("report" in mine)) }
    val counts = remember { mutableStateMapOf("like" to (post.int("like_count") ?: 0), "save" to (post.int("save_count") ?: 0), "report" to (post.int("report_count") ?: 0)) }
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val owner = !viewerKey.isNullOrBlank() && viewerKey == post.str("author_key")
    fun toggle(type: String) {
        if (busy.isNotEmpty()) return
        busy = type; message = ""
        scope.launch {
            try {
                val r = Api.rpc("board/server-fns#toggleBoardReaction", jsonOf("postId" to post.str("id"), "type" to type, "guestKey" to guestKey(context)))
                active[type] = r.bool("active") == true; counts[type] = r.int("${type}_count") ?: counts[type] ?: 0
                onChanged()
            } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "操作できませんでした" } } finally { busy = "" }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        SmallButton("いいね ${counts["like"]}", { toggle("like") }, enabled = busy.isEmpty(), icon = Icons.Outlined.FavoriteBorder, primary = active["like"] == true)
        SmallButton(if (active["save"] == true) "保存済み" else "保存", { toggle("save") }, enabled = busy.isEmpty(), icon = if (active["save"] == true) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder)
        SmallButton("共有", {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, post.str("title")); putExtra(Intent.EXTRA_TEXT, "${post.str("title")}\n${app.narabuspulse.core.Config.BASE_URL}/board/${post.str("id")}") }
            runCatching { context.startActivity(Intent.createChooser(send, "共有")) }.onFailure { message = "共有できませんでした" }
        }, icon = Icons.Outlined.Share)
        SmallButton("通報 ${counts["report"]?.takeIf { it > 0 } ?: ""}", { toggle("report") }, enabled = busy.isEmpty(), icon = Icons.Outlined.Flag)
        if (owner) {
            SmallButton("編集", onEdit, icon = Icons.Outlined.Edit)
            SmallButton("削除", {
                confirm.ask("この投稿を削除しますか？削除するとAIの検索対象からも外れます。") {
                    busy = "delete"
                    scope.launch {
                        try { Api.rpc("board/server-fns#deleteBoardPost", jsonOf("id" to post.str("id"), "guestKey" to guestKey(context))); nav.go("/board") }
                        catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "削除できませんでした" }; busy = "" }
                    }
                }
            }, enabled = busy != "delete", icon = Icons.Outlined.Delete)
        }
    }
    if (message.isNotBlank()) Muted(message, 11)
}

@Composable
private fun CommentSection(postId: String, comments: List<JsonElement>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val byParent = comments.groupBy { it.str("parent_comment_id").orEmpty() }
    val roots = byParent[""].orEmpty()
    Panel(title = "💬 コメント（${comments.size}）") {
        if (roots.isEmpty()) Muted("まだコメントはありません。", 12)
        roots.forEach { c ->
            CommentHead(c) { replyTo = c }
            Text(c.str("body").orEmpty(), fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            byParent[c.str("id")].orEmpty().forEach { r ->
                Column(Modifier.padding(start = 16.dp)) { CommentHead(r, reply = true) { replyTo = c }; Text(r.str("body").orEmpty(), fontSize = 12.sp) }
            }
            RowDivider()
        }
        replyTo?.let { r -> Row(verticalAlignment = Alignment.CenterVertically) { Muted("${r.str("author_name")?.ifBlank { null } ?: "ゲスト"} さんのコメントに返信", 11); TextButton(onClick = { replyTo = null }) { Text("解除", fontSize = 11.sp) } } }
        OutlinedTextField(body, { body = it.take(800) }, placeholder = { Text("コメントを書く（投稿者の方針に沿った内容でお願いします）", fontSize = 12.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        SmallButton(if (busy) "送信中…" else if (replyTo != null) "返信する" else "コメントする", {
            if (body.isBlank()) return@SmallButton
            busy = true; error = ""
            scope.launch {
                try { Api.rpc("board/server-fns#addBoardComment", jsonOf("postId" to postId, "body" to body.trim(), "parentId" to replyTo?.str("id"), "guestKey" to guestKey(context))); body = ""; replyTo = null; onChanged() }
                catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "コメントできませんでした" } } finally { busy = false }
            }
        }, enabled = !busy && body.isNotBlank(), primary = true, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun CommentHead(c: JsonElement, reply: Boolean = false, onReply: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${if (reply) "↳ " else ""}${c.str("author_name")?.ifBlank { null } ?: "ゲスト"}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Muted("  ${fmtDateTime(c.long("at"))}", 10)
        TextButton(onClick = onReply) { Text("返信", fontSize = 11.sp) }
    }
}

private val FIELD_LABEL = mapOf("garage" to "営業所", "vehicle_type" to "車両形式", "registration_number" to "登録番号", "manufacturer" to "メーカー", "route" to "路線・系統")

/** 投稿から抽出した車両情報（Web: VehicleFacts.jsx） */
@Composable
fun VehicleFacts(facts: List<JsonElement>) {
    val nav = LocalNav.current
    if (facts.isEmpty()) { Muted("この車両について、確認できる投稿情報はまだありません。", 11); return }
    facts.groupBy { it.str("field_name").orEmpty() }.forEach { (field, rows) ->
        val conflicting = rows.map { it.str("field_value") }.toSet().size > 1
        Column(Modifier.padding(vertical = 4.dp)) {
            Row { Text(FIELD_LABEL[field] ?: field, fontSize = 11.sp, fontWeight = FontWeight.Bold); if (conflicting) Text("・情報が矛盾しています（両方を表示）", fontSize = 10.sp, color = Brand.Destructive) }
            rows.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(f.str("field_value").orEmpty(), fontSize = 12.sp); VerifyBadge(f.str("confidence")); Muted(fmtRelative(f.long("updated_at")), 10)
                    f.str("source_post_id")?.takeIf { it.isNotBlank() }?.let { id -> Text("根拠の投稿", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { nav.go("/board/$id") }) }
                }
            }
        }
    }
}

