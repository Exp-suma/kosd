package com.example.d750bridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.d750bridge.platform.appVersionName
import com.example.d750bridge.platform.rememberShareText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.d750bridge.AppLog
import com.example.d750bridge.MainViewModel
import com.example.d750bridge.ptp.PtpNames
import com.example.d750bridge.ui.components.CamCard
import com.example.d750bridge.ui.components.CamIcons
import com.example.d750bridge.ui.components.ExpandableCard
import com.example.d750bridge.ui.components.GhostButton
import com.example.d750bridge.ui.components.KeyValue
import com.example.d750bridge.ui.components.PrimaryButton
import com.example.d750bridge.ui.components.SettingSwitch
import com.example.d750bridge.ui.theme.Cam
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(vm: MainViewModel, listState: LazyListState) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val share = rememberShareText()
    var verbose by remember { mutableStateOf(AppLog.verbose) }
    var confirmCapture by remember { mutableStateOf(false) }
    val version = appVersionName()

    if (confirmCapture) AlertDialog(
        onDismissRequest = { confirmCapture = false },
        title = { Text("テスト撮影") },
        text = { Text("カメラのシャッターが実際に切れます（1枚、メモリーカードに記録されます）。実行しますか？") },
        confirmButton = { TextButton({ confirmCapture = false; vm.testCapture() }) { Text("撮影する") } },
        dismissButton = { TextButton({ confirmCapture = false }) { Text("やめる") } },
        containerColor = Cam.Surface2
    )

    LazyColumn(
        state = listState, modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("診断", style = MaterialTheme.typography.headlineMedium, color = Cam.Text)
                Text("カメラの自己申告・手動操作・ログ", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
            }
        }
        item {
            CamCard {
                Text("接続中のカメラ", style = MaterialTheme.typography.titleSmall, color = Cam.Text)
                val cam = ui.camera
                KeyValue("機種", cam?.title ?: "未接続")
                KeyValue("方式", cam?.protocol ?: "—")
                KeyValue("アドレス", cam?.host ?: "—")
                KeyValue("ファーム", cam?.firmware?.ifBlank { "—" } ?: "—")
                KeyValue("状態", ui.probeStatus)
                ui.deviceInfo?.let { KeyValue("PTP拡張", it.vendorExtensionDesc.ifBlank { "—" }) }
            }
        }
        item {
            ExpandableCard(CamIcons.Log, "カメラの状態（読み取りのみ）", if (ui.readouts.isEmpty()) "全プロパティ・ストレージ情報を読み取る" else "${ui.readouts.size}件") {
                PrimaryButton("読み取る", vm::readDiagnostics, enabled = ui.connected, loading = ui.readoutsBusy, height = 46.dp)
                Text("※値は標準PTPの型・単位を仮定した解釈です。[生:…]が実際のデータです。", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
                if (ui.readouts.isNotEmpty()) MonoBox(ui.readouts.joinToString("\n"))
            }
        }
        ui.deviceInfo?.let { info ->
            item {
                ExpandableCard(CamIcons.Chip, "カメラの自己申告（GetDeviceInfo）", "操作 ${info.operations.size}件 / プロパティ ${info.properties.size}件") {
                    Text("カメラが申告した一覧です。実際の対応状況は各機能の応答コードで確認してください。", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
                    MonoBox("対応操作:\n" + info.operations.joinToString("\n") { "${PtpNames.hex(it)} ${PtpNames.op(it, info.vendorExtensionId)}" })
                    MonoBox("対応プロパティ:\n" + info.properties.joinToString("\n") { "${PtpNames.hex(it)} ${PtpNames.prop(it)}" })
                }
            }
        }
        item {
            ExpandableCard(CamIcons.Tune, "手動操作", "数値で設定・AF・テスト撮影") { ManualControls(vm, ui.connected, ui.settingStatus, ui.afStatus, ui.captureStatus) { confirmCapture = true } }
        }
        item {
            ExpandableCard(CamIcons.Log, "診断ログ", "通信ログの表示・共有", initiallyExpanded = false) {
                SettingSwitch("パケット詳細ログ", verbose, { verbose = it; vm.setVerboseLog(it) }, "通常はOFF。ONにするとライブビューが重くなります")
                GhostButton("ログを共有", {
                    share("ログを共有", AppLog.dump())
                }, Modifier.fillMaxWidth(), icon = CamIcons.Log)
                LogView()
            }
        }
        item {
            Text("CamBridge ${version ?: ""} · Nikon / Canon / Sony / LUMIX", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelSmall, color = Cam.TextFaint)
        }
    }
}

@Composable
private fun MonoBox(text: String) {
    Text(
        text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cam.Bg.copy(alpha = 0.6f)).padding(10.dp),
        fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Cam.TextDim, lineHeight = 14.sp
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cam.Accent, unfocusedBorderColor = Cam.Outline, focusedLabelColor = Cam.Accent, cursorColor = Cam.Accent,
    focusedContainerColor = Cam.Bg, unfocusedContainerColor = Cam.Bg
)

@Composable
private fun ManualControls(vm: MainViewModel, connected: Boolean, setting: String, af: String, capture: String, onTestCapture: () -> Unit) {
    var shutter by remember { mutableStateOf("1/250") }
    var iso by remember { mutableStateOf("400") }
    var ap by remember { mutableStateOf("2.8") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(shutter, { shutter = it }, Modifier.weight(1f), label = { Text("シャッター（1/250, 0.5, 2\"）") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
        GhostButton("設定", { vm.setShutterText(shutter) }, enabled = connected)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(iso, { iso = it }, Modifier.weight(1f), label = { Text("ISO（0=AUTO）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), colors = fieldColors())
        GhostButton("設定", { iso.toLongOrNull()?.let(vm::setIso) }, enabled = connected)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(ap, { ap = it }, Modifier.weight(1f), label = { Text("絞り（例: 2.8）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp), colors = fieldColors())
        GhostButton("設定", { vm.setApertureText(ap) }, enabled = connected)
    }
    Text("入力値がカメラの選択肢に無い場合は最寄りの値に丸め、その旨を表示します。結果: $setting", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("AF開始", vm::autofocus, Modifier.weight(1f), enabled = connected, icon = CamIcons.Focus)
        GhostButton("AFキャンセル", vm::cancelAutofocus, Modifier.weight(1f), enabled = connected)
    }
    Text("AF: $af", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("テスト撮影（画像数で確認）", onTestCapture, Modifier.weight(1f), enabled = connected, icon = CamIcons.Camera)
        GhostButton("値を読み直す", vm::refreshExposure, enabled = connected)
    }
    Text("撮影: $capture", style = MaterialTheme.typography.bodySmall, color = Cam.TextDim)
}

/** ログ表示。0.7秒ごとにだけ取得し、ライブビュー中の大量ログで再描画が連鎖しないようにする。 */
@Composable
private fun LogView() {
    val lines by produceState(initialValue = emptyList<String>()) {
        while (true) {
            value = AppLog.snapshot(200).asReversed()
            delay(700)
        }
    }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(12.dp)).background(Cam.Bg.copy(alpha = 0.7f))) {
        LazyColumn(state = state, contentPadding = PaddingValues(10.dp)) {
            items(lines) { l ->
                val c = when { l.contains("[ERROR]") -> Cam.Err; l.contains("[WARN]") -> Cam.Warn; else -> Cam.TextDim }
                Text(l, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = c, lineHeight = 13.sp)
            }
        }
        AnimatedVisibility(lines.isEmpty(), Modifier.align(Alignment.Center)) { Text("ログなし", color = Cam.TextFaint) }
    }
}
