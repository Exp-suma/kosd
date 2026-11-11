package app.narabuspulse.feature.bus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.ErrorBox
import app.narabuspulse.ui.kit.Loading
import app.narabuspulse.ui.kit.Note
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Web の DataGate：監視データの読み込み中・エラー・未設定を出し分ける。
 * 取得に失敗しても前回のデータがあれば表示を続け、注意書きを出す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusGate(loadingLabel: String = "監視データを読み込み中…", content: @Composable (BusSnapshot) -> Unit) {
    val state = rememberBusData()
    val scope = rememberCoroutineScope()
    val data = state.data
    PullToRefreshBox(
        isRefreshing = state.loading && data != null,
        onRefresh = { scope.launch { BusRepository.refresh(force = true) } },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            data == null && state.error != null -> ErrorBox("データを読み込めませんでした\n${state.error}", onRetry = { scope.launch { BusRepository.refresh(force = true) } })
            data == null -> Loading(text = loadingLabel)
            !data.configured -> EmptyState(
                "監視シートが未設定です",
                description = "設定画面で監視スプレッドシートを登録すると、車両の状態が表示されます。",
            )
            else -> Column {
                if (state.error != null) {
                    Note(
                        "最新のシートを取得できませんでした。最終取得データを表示しています（最終取得 ${BusFormat.dateTime(data.fetchedAt)}・${BusFormat.elapsed(data.fetchedAt)}）",
                        tone = Brand.Warning,
                    )
                }
                content(data)
            }
        }
    }
}
