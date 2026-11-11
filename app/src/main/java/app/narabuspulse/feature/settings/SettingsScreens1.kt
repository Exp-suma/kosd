package app.narabuspulse.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.Net
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private val TABS = listOf("/settings" to "基本設定", "/settings/notify" to "通知", "/settings/monitor" to "システム状態", "/settings/performance" to "パフォーマンス", "/settings/offline" to "オフライン", "/settings/data" to "データ管理", "/settings/advanced" to "詳細設定")

/** 設定のタブと見出し（Web: settings.jsx のレイアウト） */
@Composable
fun SettingsHeader(current: String) {
    val nav = LocalNav.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("設定", "監視の接続先、システムの稼働状況、動作の調整、保存データの管理をまとめています。変更はすぐにこの端末の動作へ反映されます。")
        ChipRow(TABS, current, { if (it != current) nav.go(it) })
    }
}

@Composable
fun SettingsCard(title: String, desc: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Panel(title = title, subtitle = desc, action = action) { content() }
}

@Composable
fun ToggleRow(label: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold); desc?.let { Muted(it, 11) } }
        Switch(checked, onChange, enabled = enabled)
    }
}

/** 基本設定（Web: settings.index.jsx） */
@Composable
fun BasicSettingsScreen() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var spreadsheetId by remember { mutableStateOf("") }
    var mainSheet by remember { mutableStateOf("") }
    var logSheet by remember { mutableStateOf("ログ") }
    var stale by remember { mutableStateOf("15") }
    var cacheMin by remember { mutableStateOf("3") }
    var ready by remember { mutableStateOf(false) }
    var sheets by remember { mutableStateOf(listOf<String>()) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val gas = produceState("") { value = Logic.constant("gas/functions", "GAS_FUNCTIONS_CODE").text }.value
    LaunchedEffect(Unit) {
        runCatching { Api.rpc("server-fns#getBusConfig", JsonNull) }.getOrNull()?.takeIf { it.obj != null }?.let { c ->
            spreadsheetId = c.str("spreadsheetId").orEmpty(); mainSheet = c.str("mainSheetName").orEmpty(); logSheet = c.str("logSheetName") ?: "ログ"
            stale = (c.int("staleMinutes") ?: 15).toString(); cacheMin = (c.int("routeOrderCacheMinutes") ?: 3).toString()
        }
        ready = true
    }
    fun run(block: suspend () -> Unit) {
        busy = true; error = ""; message = ""
        scope.launch { try { block() } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage() } finally { busy = false } }
    }
    ScreenList {
        item { SettingsHeader("/settings") }
        item {
            SettingsCard("📄 スプレッドシート", "監視対象のスプレッドシートを指定します。監視処理そのものはApps Script側のトリガーが行うため、ここでは変更しません。") {
                OutlinedTextField(spreadsheetId, { spreadsheetId = it }, label = { Text("スプレッドシートのURL または ID", fontSize = 12.sp) }, placeholder = { Text("https://docs.google.com/spreadsheets/d/...", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (sheets.isNotEmpty()) {
                    SelectField(listOf("" to "先頭シートを使用") + sheets.map { it to it }, mainSheet, { mainSheet = it }, label = "メインシート名", modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    SelectField(sheets.map { it to it }, logSheet, { logSheet = it }, label = "ログシート名", modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else {
                    OutlinedTextField(mainSheet, { mainSheet = it }, label = { Text("メインシート名（空なら先頭シート）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(logSheet, { logSheet = it }, label = { Text("ログシート名", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(stale, { stale = it.filter(Char::isDigit) }, label = { Text("「要確認」とする経過分数", fontSize = 12.sp) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cacheMin, { cacheMin = it.filter(Char::isDigit) }, label = { Text("停留所一覧のキャッシュ有効期限（分）", fontSize = 12.sp) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Muted("この時間内は、同じ車両・同じ日の停留所一覧をバスロケーションシステムから取り直しません。", 10)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SmallButton("接続を確認", {
                        run {
                            val r = Api.rpc("server-fns#listSheetTitles", jsonOf("spreadsheetId" to spreadsheetId))
                            val err = r.str("error")
                            if (err != null) { error = err; sheets = emptyList() } else { sheets = r.list("sheets").map { it.text }; message = "シートを読み取れました。使用するシート名を選んでください。" }
                        }
                    }, enabled = !busy && spreadsheetId.isNotBlank())
                    SmallButton("設定を保存", {
                        run {
                            Api.rpc("server-fns#saveBusConfig", jsonOf("spreadsheetId" to spreadsheetId, "mainSheetName" to mainSheet, "logSheetName" to logSheet, "staleMinutes" to (stale.toIntOrNull() ?: 15), "routeOrderCacheMinutes" to (cacheMin.toIntOrNull() ?: 3)))
                            app.narabuspulse.feature.bus.BusRepository.invalidate()
                            message = "設定を保存しました。各画面に反映されます。"
                        }
                    }, enabled = !busy && spreadsheetId.isNotBlank(), primary = true)
                }
                if (message.isNotBlank()) Text(message, fontSize = 12.sp, color = Brand.Success)
                if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = Brand.Destructive)
                if (!ready) Muted("読み込み中…", 11)
            }
        }
        item {
            SettingsCard("Apps Script 追加関数（任意）", "既存のApps Scriptプロジェクトへ貼り付けると、同じ集計をHTML Service側からも取得できます。読み取り専用で、奈良交通APIは呼び出しません。この画面のダッシュボードは貼り付け不要で動作します。", action = {
                SmallButton(if (copied) "コピーしました" else "コードをコピー", { clipboard.setText(AnnotatedString(gas)); copied = true }, icon = Icons.Outlined.ContentCopy)
            }) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(gas, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp))
                }
                Muted("既存の5分間隔トリガーには触れないでください。", 10)
            }
        }
    }
}

private data class Tune(val key: String, val group: String, val label: String, val unit: String, val min: Double, val max: Double, val step: Double, val factor: Double, val critical: Boolean)

@Composable
private fun rememberTune(): List<Tune> = produceState(emptyList<Tune>()) {
    value = Logic.constant("monitor/settings", "TUNE").items().map {
        Tune(it.str("key").orEmpty(), it.str("group").orEmpty(), it.str("label").orEmpty(), it.str("unit").orEmpty(), it.dbl("min") ?: 0.0, it.dbl("max") ?: 0.0, it.dbl("step") ?: 1.0, it.dbl("factor") ?: 1.0, it.bool("critical") == true)
    }
}.value

/** 項目ごとの数値設定（Web: TuningGroup.jsx + NumberField.jsx） */
@Composable
fun TuningGroup(group: String, keys: List<String>? = null, confirmCritical: Boolean = false) {
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val all = rememberTune()
    val resolved by Net.resolved.collectAsState()
    val overrides by Net.overrides.collectAsState()
    all.filter { it.group == group && (keys == null || it.key in keys) }.forEach { d ->
        val cur = resolved.dbl(d.key)
        val display = cur?.let { if (d.factor == 1000.0) it / 1000 else it }
        val dMin = if (d.factor == 1000.0) d.min / 1000 else d.min
        val dMax = if (d.factor == 1000.0) d.max / 1000 else d.max
        NumberField(d.label, d.unit, display, dMin, dMax, overrides.obj?.containsKey(d.key) == true,
            onApply = { v ->
                val internal = v * d.factor
                val apply = { scope.launch { Net.setSetting(d.key, internal) }; Unit }
                if (confirmCritical && d.critical) confirm.ask("動作に大きく影響する設定です\n「${d.label}」を ${fmtNum(v)} ${d.unit} に変更します。適用しますか？") { apply() } else apply()
            },
            onRevert = { scope.launch { Net.clearSetting(d.key) } })
    }
}

@Composable
private fun NumberField(label: String, unit: String, value: Double?, min: Double, max: Double, overridden: Boolean, onApply: (Double) -> Unit, onRevert: () -> Unit) {
    var draft by remember(value) { mutableStateOf(value?.let { fmtNum(it) }.orEmpty()) }
    var error by remember(value) { mutableStateOf("") }
    fun commit() {
        val n = draft.toDoubleOrNull()
        if (n == null) { error = "数値を入力してください"; draft = value?.let { fmtNum(it) }.orEmpty(); return }
        if (n < min || n > max) { error = "${fmtNum(min)}〜${fmtNum(max)}${unit}の範囲で入力してください"; return }
        error = ""; if (n != value) onApply(n)
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (overridden) TextButton(onClick = onRevert) { Text("↶ モードの値に戻す", fontSize = 10.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, singleLine = true, modifier = Modifier.width(140.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }))
            Text(" $unit", fontSize = 12.sp)
            SmallButton("適用", { commit() }, modifier = Modifier.padding(start = 6.dp))
        }
        if (error.isNotBlank()) Text(error, fontSize = 10.sp, color = Brand.Destructive)
    }
}

private val PROFILES = listOf(
    Triple("low", "低速通信モード", "通信量をできるだけ抑えます。必要なデータだけを取得し、地図・レーダーは低解像度、更新間隔は長め、キャッシュを優先します。"),
    Triple("fast", "高速通信モード", "最新データと更新頻度を優先します。GPS・車両一覧・レーダーを早い間隔で取得し、レーダーは高解像度・全コマで表示します。"),
    Triple("auto", "自動最適化モード", "通信速度・遅延・失敗率を定期的に測り、回線に合わせて取得方法を自動で切り替えます。"),
)
private val POINTS = mapOf("low" to listOf("必要なデータだけ取得", "レーダーは低解像度・コマ間引き", "更新間隔を延長しキャッシュ優先"), "fast" to listOf("GPS・車両一覧を早い間隔で更新", "レーダーは高解像度・全コマ", "表示に必要なデータを並列取得"), "auto" to listOf("速度・遅延・失敗率を定期計測", "回線に合わせて自動的に切替", "頻繁な切替は自動で抑制"))
private val APPLIED = mapOf("low" to "通信量を抑え、必要なデータだけを取得します。", "fast" to "最新データと更新頻度を優先して取得します。", "auto" to "回線の速さに合わせて取得方法を自動で切り替えます。")

fun formatSpeed(kbps: Double?): String = when { kbps == null || kbps <= 0 -> "—"; kbps >= 1000 -> (kbps / 1000).let { if (it >= 10) "${Math.round(it)}Mbps" else "%.1fMbps".format(it) }; else -> "${Math.round(kbps)}kbps" }

/** 通信モード（Web: components/net/ModeSettings.jsx + ModeSelector.jsx） */
@Composable
fun ModeSettings() {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val plan by Net.plan.collectAsState()
    val r by Net.resolved.collectAsState()
    val st by Net.status.collectAsState()
    SettingsCard("📶 通信モード", "アプリ全体のデータ取得方法を切り替えます。選択はこの端末に保存され、次回起動時も同じモードで動作します。切り替えるとすぐに反映され、再読み込みは不要です。") {
        Text("現在：${plan.label}${if (plan.raw.bool("degraded") == true) "（回線が遅いため取得に時間がかかる場合があります）" else ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        PROFILES.forEach { (k, l, d) ->
            val active = plan.profile == k
            Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                if (!active) { Net.setProfile(k); scope.launch { snackbar.showSnackbar("${l}に切り替えました。${APPLIED[k]}") } }
            }, shape = MaterialTheme.shapes.medium, color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(10.dp)) {
                    Text("${if (active) "✓ " else ""}$l", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Muted(d, 11)
                    POINTS[k].orEmpty().forEach { Muted("・$it", 10) }
                }
            }
        }
        KeyValue("自動更新", "${(r.long("intervalMs") ?: 0) / 1000}秒"); KeyValue("GPS更新", "${(r.long("gpsMs") ?: 0) / 1000}秒")
        KeyValue("同時取得", "${r.int("maxConcurrent") ?: 0}件"); KeyValue("運行履歴", "${r.int("logDays") ?: 0}日分")
        KeyValue("レーダー", if (r.str("radarDetail") == "low") "低解像度（粗いタイル）" else "高解像度")
        KeyValue("アニメーション", r.int("radarFrames")?.takeIf { it > 0 }?.let { "${it}コマに間引き" } ?: "全コマ")
        KeyValue("地図タイル", if (r.bool("tiles") != false) "表示" else "停止（キャッシュのみ）"); KeyValue("失敗時の再試行", "${r.int("retry") ?: 0}回")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("実測：${formatSpeed(st.kbps)}（${plan.raw.str("effectiveLabel") ?: "—"}）　遅延：${st.latencyMs?.let { "${it}ms" } ?: "—"}${st.lastError?.let { "　$it" } ?: ""}", 11)
            SmallButton(if (st.measuring) "測定中…" else "今すぐ測定", { scope.launch { Net.measureNow() } }, enabled = !st.measuring, icon = Icons.Outlined.NetworkCheck, modifier = Modifier.padding(start = 6.dp))
        }
        Text("⚠ 通信モードはデータの取得方法・量・優先度を変えるだけで、携帯電話会社の通信制限や回線速度そのものを解除するものではありません。高速通信モードでも回線が遅い場合は取得に時間がかかります。", fontSize = 10.sp, color = Brand.Warning)
    }
}

private val OPTIMIZATIONS = listOf(
    Triple(true, "同じAPIへの重複リクエストを防止", "画面ごとに同じデータを取りに行っても、1本の取得にまとめます。"),
    Triple(true, "同一データの同時取得を1つにまとめる", "取得中の停留所座標は、同じ処理を共有します。"),
    Triple(true, "キャッシュがある場合は優先的に利用", "保存済みの車両情報・停留所座標・レーダー時刻を先に表示します。"),
    Triple(true, "必要なデータだけ取得", "レイヤーのON/OFF、取得日数、表示件数で通信量を調整します。"),
    Triple(false, "差分取得", "取得期間を日数で絞る範囲まで対応しています（サーバー側に差分APIはありません）。"),
    Triple(true, "不要なDBアクセスを削減", "停留所座標は端末内で共有し、同じ読み取りを繰り返しません。"),
    Triple(true, "画面再描画による不要な通信を防止", "取得済みデータは有効期間内で再利用し、再取得しません。"),
)

private fun dirSize(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0)
private fun kb(b: Long): String = if (b >= 1_048_576) "%.1fMB".format(b / 1_048_576.0) else "${maxOf(1, b / 1024)}KB"

/** パフォーマンス設定（Web: settings.performance.jsx） */
@Composable
fun PerformanceSettingsScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val confirm = rememberConfirmer()
    val r by Net.resolved.collectAsState()
    val plan by Net.plan.collectAsState()
    val st by Net.status.collectAsState()
    var cacheVersion by remember { mutableStateOf(0) }
    val sizes = produceState(Triple(0L, 0L, 0L), cacheVersion) {
        value = withContext(Dispatchers.IO) { val c = context.cacheDir; Triple(dirSize(File(c, "osm")), dirSize(File(c, "image_cache")), dirSize(c)) }
    }.value
    ScreenList {
        item { SettingsHeader("/settings/performance") }
        item { ModeSettings() }
        item {
            SettingsCard("更新間隔の個別調整", "通信モードの値に対して、項目ごとに上書きできます。空欄のままにした項目はモードの値を使います。変更はすぐ反映され、この端末に保存されます。") {
                TuningGroup("performance", listOf("intervalMs", "gpsMs", "stopsMs", "radarMs", "snowMs", "cacheTtlMin", "maxConcurrent", "logDays"), confirmCritical = true)
            }
        }
        item {
            SettingsCard("✨ 通信と描画の動作", "通信量と端末負荷に影響する切り替えです。") {
                ToggleRow("バックグラウンド通信を許可", "OFFのときは、画面を表示していない間はGPSを更新しません。", r.bool("background") == true, { v -> scope.launch { Net.setSetting("background", v) } })
                ToggleRow("先読み取得", "レーダーの次のコマのタイルを先に読み込みます。", r.bool("prefetch") != false, { v -> scope.launch { Net.setSetting("prefetch", v) } })
                ToggleRow("画面描画の最適化", "アニメーションとトランジションを停止して描画負荷を下げます。", r.bool("renderOptimize") == true, { v -> scope.launch { Net.setSetting("renderOptimize", v) } })
            }
        }
        item {
            SettingsCard("通信の最適化（実装状況）", "すでに動作している最適化と、一部のみ対応している項目です。") {
                OPTIMIZATIONS.forEach { (ok, l, n) -> Row(Modifier.padding(vertical = 3.dp)) { Text(if (ok) "✓" else "－", color = if (ok) Brand.Success else Brand.Warning, modifier = Modifier.width(18.dp)); Column { Text(l, fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted(n, 10) } } }
            }
        }
        item {
            SettingsCard("💾 キャッシュ管理状況", "端末に保存している一時データの量と利用状況です。") {
                KeyValue("地図タイルのキャッシュ", kb(sizes.first)); KeyValue("画像のキャッシュ", kb(sizes.second)); KeyValue("一時データの合計（概算）", kb(sizes.third))
                KeyValue("キャッシュの有効期限", "${r.int("cacheTtlMin") ?: 5}分")
                SmallButton("キャッシュを削除", {
                    confirm.ask("キャッシュを削除しますか？\n端末に保存した一時データを削除し、次回表示時に取り直します。\nDBの運行記録・位置履歴・気象データは削除されません。") {
                        scope.launch { withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }; app.narabuspulse.feature.bus.BusRepository.invalidate(); cacheVersion++ }
                    }
                }, icon = Icons.Outlined.Delete)
                Muted("削除するのは、画面表示用に端末へ保存した一時データ（地図タイル・画像・車両情報）だけです。GPS位置履歴・運行記録・気象データなどDBに保存された本データは削除しません。", 10)
            }
        }
        item {
            SettingsCard("自動最適化", "現在の判断と、429・522 が発生したときの待ち方です。") {
                KeyValue("実測速度", formatSpeed(st.kbps)); KeyValue("回線の区分", plan.raw.str("effectiveLabel") ?: "—"); KeyValue("選択中のモード", plan.label)
                KeyValue("直近の失敗", if (st.attempts > 0) "${st.failures}回（${st.attempts}回中）" else "—"); KeyValue("直近の応答時間", st.latencyMs?.let { "${it}ms" } ?: "—")
                KeyValue("最終測定", if (st.measuredAt > 0) BusFormat.dateTime(st.measuredAt) else "—"); KeyValue("同時通信数（現在）", "${r.int("maxConcurrent") ?: 0}件")
                KeyValue("429の最大待機", "${(r.long("retry429Ms") ?: 0) / 1000}秒"); KeyValue("522の最大待機", "${(r.long("retry522Ms") ?: 0) / 1000}秒")
                listOf("HTTP 429 は Retry-After を最優先し、指定が無い場合は指数バックオフ（最大${(r.long("retry429Ms") ?: 0) / 1000}秒）で待ちます。", "HTTP 522・接続障害は、間隔を段階的に延ばして再接続します（最大${(r.long("retry522Ms") ?: 0) / 1000}秒）。連続失敗時は測定間隔も空けます。",
                    "待ち時間には±15%のゆらぎ（ジッター）を加え、再試行の集中を避けます。", "自動最適化モードは、実測速度・遅延・失敗率から取得方法を切り替えます。モードを手動で選んでいる間は、その設定を優先します。").forEach { Muted("・$it", 10) }
                Muted("このモードはアプリ内部の取得方法（間隔・同時数・データ量・優先度）を変えるもので、携帯電話会社の通信速度制限そのものを解除するものではありません。実測速度と取得の優先度は別の指標として表示しています。", 10)
            }
        }
    }
}

private val LOG_TOGGLES = listOf(Triple("logApi", "API通信ログを保存", "呼び出し回数・応答時間・HTTPステータスを記録します。"), Triple("logGps", "GPS取得ログを保存", "GPS取得の成否と所要時間を記録します。"), Triple("logDb", "DBアクセスログを保存", "エンティティごとの読み書き回数を記録します。"), Triple("logError", "エラーログを保存", "429・522・タイムアウトなどの発生を記録します。"))
private val LOG_LEVELS = listOf("minimal" to "最小（回数のみ）", "standard" to "標準（回数と所要時間）", "detailed" to "詳細（1件ずつ記録）")

/** 詳細設定（Web: settings.advanced.jsx） */
@Composable
fun AdvancedSettingsScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val confirm = rememberConfirmer()
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var detailed by remember { mutableStateOf(prefs.getString("nara-settings-mode", "normal") == "detailed") }
    val r by Net.resolved.collectAsState()
    val history by Net.history.collectAsState()
    val tune = rememberTune()
    fun fmt(key: String?, v: JsonElement?): String {
        val s = (v as? JsonPrimitive)?.content
        if (s == "reset" || s == "default" || s == "individual") return "初期値"
        if (s == "true" || s == "false") return if (s == "true") "ON" else "OFF"
        val d = tune.firstOrNull { it.key == key }
        val n = s?.toDoubleOrNull()
        if (d != null && n != null) return "${fmtNum(if (d.factor == 1000.0) n / 1000 else n)}${d.unit}"
        return s ?: "—"
    }
    ScreenList {
        item { SettingsHeader("/settings/advanced") }
        item {
            SettingsCard("詳細設定モード", "通常の設定では表示しない、システム管理向けの項目です。変更は実際の取得処理に反映され、入力範囲を外れた値は保存しません。", action = {
                Row { SmallButton("通常", { detailed = false; prefs.edit().putString("nara-settings-mode", "normal").apply() }, primary = !detailed); SmallButton("詳細", { detailed = true; prefs.edit().putString("nara-settings-mode", "detailed").apply() }, primary = detailed, modifier = Modifier.padding(start = 4.dp)) }
            }) {
                Text("現在のモード：${if (detailed) "詳細設定" else "通常設定"}。" + if (detailed) " 項目を変更すると即座に反映され、変更前の値は下の変更履歴に残ります。" else " 「詳細」に切り替えると、API・GPS・DB・キャッシュ・ログの細かな設定を変更できます。", fontSize = 12.sp)
            }
        }
        if (detailed) {
            item { SettingsCard("API設定", "通信1回ごとの待ち時間と再試行の扱いです。値を小さくしすぎると取得に失敗しやすくなります。") { TuningGroup("api", confirmCritical = true) } }
            item {
                SettingsCard("GPS設定", "GPSの取得間隔（パフォーマンス設定と共通）と、失敗したときの動きです。") {
                    TuningGroup("gps")
                    Muted("車両ごとの取得優先度は、全車両を1回の呼び出しでまとめて取得しているため設定できません（車両単位の振り分けは未対応）。運行終了の判定は収集処理側（サーバー）の固定ルールで行っています。", 10)
                }
            }
            item {
                SettingsCard("DB設定", "重複した設定を作らないため、値は上の各グループと共通です。ここでは現在の値と、固定で動作している内容を確認できます。") {
                    KeyValue("entity_list 呼び出し管理", "${r.int("queuePerMinute") ?: 0}回/分"); KeyValue("DBアクセスの同時実行数", "${r.int("maxConcurrent") ?: 0}件")
                    KeyValue("リクエストの最小間隔", "${r.int("minGapMs") ?: 0}ms"); KeyValue("読み込みキャッシュ時間", "${r.int("cacheTtlMin") ?: 0}分")
                    KeyValue("データ取得件数（履歴日数）", "${r.int("logDays") ?: 0}日分"); KeyValue("ページング（上限件数）", "1回300〜2000件")
                    listOf("重複データ保存の防止：位置履歴は同じ停留所の連続記録を保存しません（サーバー側で判定）。", "DB書き込みの実行間隔：位置履歴・運行記録は観測時にまとめて書き込みます（個別の間隔設定は未対応）。", "値を変更する場合は、上の「API設定」「パフォーマンス設定」から行ってください。").forEach { Muted("・$it", 10) }
                }
            }
            item { SettingsCard("キャッシュ設定", "端末内に保存する一時データの扱いです。") { TuningGroup("cache") } }
            item {
                SettingsCard("ログ設定", "システム状態モニターに表示する記録の範囲です。") {
                    TuningGroup("log")
                    LOG_TOGGLES.forEach { (k, l, d) -> ToggleRow(l, d, r.bool(k) != false, { v -> scope.launch { Net.setSetting(k, v) } }) }
                    SelectField(LOG_LEVELS, r.str("logLevel") ?: "standard", { v -> scope.launch { Net.setSetting("logLevel", v) } }, label = "ログの詳細度")
                }
            }
            item {
                SettingsCard("設定の変更履歴", "変更した項目と、変更前・変更後の値です（この端末の直近40件）。設定の変更でデータが削除されることはありません。", action = {
                    Row {
                        SmallButton("初期設定に戻す", { confirm.ask("個別設定を初期値に戻しますか？\n通信モードが決めた値に戻します。保存済みの運行記録・GPS履歴・気象データは削除されません。") { scope.launch { Net.resetSettings() } } }, icon = Icons.Outlined.Restore)
                        TextButton(onClick = { scope.launch { Net.clearHistory() } }) { Text("履歴を消す", fontSize = 11.sp) }
                    }
                }) {
                    if (history.isEmpty()) Muted("まだ変更はありません。", 12)
                    else DataTable(listOf(Col("日時", 128.dp), Col("項目", 170.dp), Col("変更前", 90.dp), Col("変更後", 90.dp)), history.map { h ->
                        val key = h.str("key")
                        listOf(BusFormat.dateTime(h.long("at")), if (key == "*") "すべての項目" else tune.firstOrNull { it.key == key }?.label ?: key.orEmpty(), fmt(key, h.obj?.get("before")), fmt(key, h.obj?.get("after")))
                    }, boldFirst = false)
                }
            }
            item {
                SettingsCard("未対応の項目") {
                    listOf("APIごとに異なるリクエスト間隔：現在はアプリ全体で1つの最小間隔として適用しています。", "車両ごとのGPS取得優先度：全車両をまとめて取得するため未対応です。", "運行終了判定時間・DB書き込みの実行間隔：サーバー側の固定ルールです。").forEach { Muted("・$it", 11) }
                }
            }
        }
    }
}

