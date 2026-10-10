package app.kairo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.kairo.android.ui.theme.GeistMono

/** Markdown ligero (títulos, listas, casillas, negritas, cursivas y código) para notas y respuestas. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = MaterialTheme.colorScheme.onSurface) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        markdown.trim().lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Unit
                line.startsWith("### ") -> Text(inline(line.drop(4), codeBg), style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.padding(top = 6.dp))
                line.startsWith("## ") -> Text(inline(line.drop(3), codeBg), style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.padding(top = 8.dp))
                line.startsWith("# ") -> Text(inline(line.drop(2), codeBg), style = MaterialTheme.typography.headlineSmall, color = color, modifier = Modifier.padding(top = 8.dp))
                Regex("^\\s*[-*] \\[( |x|X)] ").containsMatchIn(line) -> {
                    val done = line.contains("[x]", ignoreCase = true)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            if (done) androidx.compose.material.icons.Icons.Rounded.CheckBox else androidx.compose.material.icons.Icons.Rounded.CheckBoxOutlineBlank,
                            null, tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(inline(line.substringAfter("] "), codeBg), style = style, color = if (done) color.copy(alpha = 0.6f) else color)
                    }
                }
                Regex("^\\s*[-*•] ").containsMatchIn(line) -> Row {
                    Text("•", style = style, color = MaterialTheme.colorScheme.primary)
                    Text(" ", modifier = Modifier.width(8.dp))
                    Text(inline(line.trimStart().drop(2), codeBg), style = style, color = color)
                }
                Regex("^\\s*\\d+\\. ").containsMatchIn(line) -> Row {
                    val n = line.trimStart().substringBefore(". ")
                    Text("$n.", style = style.copy(fontFamily = GeistMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ", modifier = Modifier.width(6.dp))
                    Text(inline(line.trimStart().substringAfter(". "), codeBg), style = style, color = color)
                }
                else -> Text(inline(line, codeBg), style = style, color = color)
            }
        }
    }
}

private fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) && text.indexOf("**", i + 2) > 0 -> {
                val end = text.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            text[i] == '`' && text.indexOf('`', i + 1) > 0 -> {
                val end = text.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = GeistMono, background = codeBg)) { append(" " + text.substring(i + 1, end) + " ") }
                i = end + 1
            }
            (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != ' ' && text.indexOf(text[i], i + 1) > 0 -> {
                val end = text.indexOf(text[i], i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/** Primera(s) línea(s) de una nota sin marcas de markdown, para vistas previas. */
fun plainPreview(content: String, title: String = "", maxLines: Int = 2): String = content.lineSequence()
    .map { it.trim().replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^[-*•]\\s+(\\[( |x|X)]\\s+)?"), "").replace("**", "").replace("`", "").replace(Regex("(?<![A-Za-z0-9])[*_]([^*_]+)[*_]"), "$1").trim() }
    .filter { it.isNotBlank() && !it.equals(title.trim(), ignoreCase = true) }
    .take(maxLines)
    .joinToString("  ·  ")
