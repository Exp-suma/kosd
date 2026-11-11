package app.narabuspulse.ui.kit

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.narabuspulse.core.Api
import app.narabuspulse.core.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/** 画面全体で使うスナックバー（操作結果の通知）。 */
val LocalSnackbar = compositionLocalOf { SnackbarHostState() }

fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message ?: "エラーが発生しました"
    is IOException -> "通信できませんでした。電波状況を確認してください。"
    else -> message ?: "エラーが発生しました"
}

@Stable
class LoadState<T> internal constructor() {
    var data by mutableStateOf<T?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var loading by mutableStateOf(true)
        internal set
    internal var version by mutableIntStateOf(0)

    /** 再読み込み（表示中のデータは残したまま更新する）。 */
    fun reload() { version++ }
}

/** suspend 関数の結果を読み込む。keys が変わると読み直す。 */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): LoadState<T> {
    val state = remember(*keys) { LoadState<T>() }
    LaunchedEffect(state, state.version) {
        state.loading = true
        state.error = null
        try {
            state.data = block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state.error = e.userMessage()
        } finally {
            state.loading = false
        }
    }
    return state
}

/** サーバー関数 1 つを呼んで表示する場合の近道。 */
@Composable
fun rememberRpc(fn: String, data: JsonElement? = null, vararg keys: Any?): LoadState<JsonElement> =
    rememberLoad(fn, data?.toString(), *keys) { Api.rpc(fn, data) }

/** 読み込み中・エラー・本体の出し分け（引っ張って更新つき）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LoadContent(
    state: LoadState<T>,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val data = state.data
    PullToRefreshBox(isRefreshing = state.loading && data != null, onRefresh = { state.reload() }, modifier = modifier) {
        when {
            data != null -> content(data)
            state.error != null -> ErrorBox(state.error!!, onRetry = { state.reload() })
            else -> Loading()
        }
    }
}

/** ボタン操作など、1 回きりの処理を実行して結果をスナックバーに出す。 */
class Action(private val scope: CoroutineScope, private val snackbar: SnackbarHostState) {
    var running by mutableStateOf(false)
        private set

    fun run(success: String? = null, onDone: (suspend () -> Unit)? = null, block: suspend () -> Unit) {
        if (running) return
        scope.launch {
            running = true
            try {
                block()
                if (success != null) snackbar.showSnackbar(success)
                onDone?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                snackbar.showSnackbar(e.userMessage())
            } finally {
                running = false
            }
        }
    }
}

@Composable
fun rememberAction(): Action {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    return remember(scope, snackbar) { Action(scope, snackbar) }
}
