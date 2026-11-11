package app.narabuspulse.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** **太字** と `コード` を装飾した文字列にする。 */
fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    var last = 0
    re.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groups[1]!!.value) }
            else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x14000000))) { append(m.groups[2]!!.value) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** AI の回答などの簡易 Markdown 表示（見出し・箇条書き・番号・表・コード・太字）。Web の components/chat/Markdown と同じ用途。 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, fontSize: Int = 13) {
    val lines = text.replace("\r\n", "\n").split("\n")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        var inCode = false
        val code = StringBuilder()
        lines.forEach { raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    Text(code.toString().trimEnd(), fontSize = (fontSize - 1).sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(8.dp))
                    code.clear()
                }
                inCode = !inCode
                return@forEach
            }
            if (inCode) { code.append(raw).append('\n'); return@forEach }
            val t = line.trimStart()
            when {
                t.isEmpty() -> {}
                t.startsWith("|") && t.replace("|", "").replace("-", "").replace(":", "").isBlank() -> {}
                t.startsWith("|") -> Text(inlineMarkdown(t.trim('|').split("|").joinToString("　") { it.trim() }), fontSize = (fontSize - 1).sp)
                t.startsWith("### ") -> Text(inlineMarkdown(t.removePrefix("### ")), fontSize = (fontSize + 1).sp, fontWeight = FontWeight.Bold)
                t.startsWith("## ") -> Text(inlineMarkdown(t.removePrefix("## ")), fontSize = (fontSize + 2).sp, fontWeight = FontWeight.Bold)
                t.startsWith("# ") -> Text(inlineMarkdown(t.removePrefix("# ")), fontSize = (fontSize + 3).sp, fontWeight = FontWeight.Bold)
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("・") -> Row(Modifier.padding(start = ((line.length - t.length) * 4).dp)) {
                    Text("•", fontSize = fontSize.sp, modifier = Modifier.width(14.dp)); Text(inlineMarkdown(t.drop(if (t.startsWith("・")) 1 else 2)), fontSize = fontSize.sp)
                }
                Regex("^\\d+[.)] ").containsMatchIn(t) -> Row {
                    val n = t.substringBefore(' ')
                    Text(n, fontSize = fontSize.sp, modifier = Modifier.width(22.dp)); Text(inlineMarkdown(t.substringAfter(' ')), fontSize = fontSize.sp)
                }
                t.startsWith("> ") -> Text(inlineMarkdown(t.removePrefix("> ")), fontSize = fontSize.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                else -> Text(inlineMarkdown(t), fontSize = fontSize.sp)
            }
        }
        if (inCode && code.isNotEmpty()) Text(code.toString(), fontSize = (fontSize - 1).sp, fontFamily = FontFamily.Monospace)
    }
}
