package app.narabuspulse.ui.kit

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** 確認ダイアログ（Web の window.confirm）。ask(message) { 実行する処理 } で使う。 */
class Confirmer {
    internal var message by mutableStateOf<String?>(null)
    internal var action: (() -> Unit)? = null

    fun ask(message: String, action: () -> Unit) {
        this.message = message
        this.action = action
    }
}

@Composable
fun rememberConfirmer(): Confirmer {
    val c = remember { Confirmer() }
    val msg = c.message
    if (msg != null) {
        AlertDialog(
            onDismissRequest = { c.message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { c.message = null; c.action?.invoke() }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { c.message = null }) { Text("キャンセル") } },
        )
    }
    return c
}
