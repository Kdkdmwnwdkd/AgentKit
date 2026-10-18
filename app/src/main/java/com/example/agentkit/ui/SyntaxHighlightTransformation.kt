package com.example.agentkit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.example.agentkit.core.text.SyntaxHighlighter

/**
 * 语法高亮变换（W-27）：只改**显示**，不改文本本身——保存进文件的仍是原文，
 * 复制出去也不会带颜色。词法规则在 [SyntaxHighlighter]（纯函数，harness 可测）。
 *
 * @param language 由文件扩展名决定（[com.example.agentkit.core.workspace.WorkspacePaths.languageOf]）；
 *                 null = 纯文本，不做任何着色。
 */
class SyntaxHighlightTransformation(
    private val language: String?,
    private val keywordColor: Color,
    private val stringColor: Color,
    private val commentColor: Color,
    private val numberColor: Color
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val spans = SyntaxHighlighter.spans(text.text, language)
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(text)
        for (span in spans) {
            val end = span.end.coerceAtMost(text.length)
            if (span.start >= end) continue
            val color = when (span.kind) {
                SyntaxHighlighter.Kind.KEYWORD -> keywordColor
                SyntaxHighlighter.Kind.STRING -> stringColor
                SyntaxHighlighter.Kind.COMMENT -> commentColor
                SyntaxHighlighter.Kind.NUMBER -> numberColor
            }
            builder.addStyle(SpanStyle(color = color), span.start, end)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
