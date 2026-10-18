package com.example.agentkit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.agentkit.core.text.SyntaxHighlighter

/**
 * Markdown 渲染层（Compose）：把 [MarkdownParser] 产出的纯 AST 画成对话气泡内容。
 *
 * 与解析器严格分层——本文件只管「AST → 视觉」，不含任何解析逻辑；解析在同包的 [MarkdownParser]。
 *
 * 安全：渲染只消费 AST，不解析、不执行 HTML；`<script>` 等标签在解析阶段已落为 [MdSpan.Plain]，
 * 这里当普通文本画出，天然无注入面。
 *
 * 代码块：`surface-1` 底 + 圆角 + 等宽字体 + 语言标签 + 横向滚动（长行不撑破气泡）+ 复制按钮。
 * 长链接 / 长行由外层气泡宽度 clamp 与文本软换行共同约束。
 *
 * @param text 原始 Markdown 文本（可能处于流式不完整状态，解析器对半截标记健壮）。
 * @param modifier 外部布局修饰（气泡内边距等）。
 * @param color 正文颜色；默认取主题 `onSurface`。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val baseColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    // 同一文本只在内容变化时重新解析（流式下文本逐帧变化，这里保证非流式重组的稳定）
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(AgentSpacing.space2))
            MarkdownBlock(block, baseColor)
        }
    }
}

/** 单块分发：块级 AST → 视觉。递归渲染列表的嵌套子列表。 */
@Composable
private fun MarkdownBlock(block: MdBlock, color: Color) {
    when (block) {
        is MdBlock.Heading -> HeadingBlock(block, color)
        is MdBlock.Paragraph -> Text(
            text = inlineSpans(block.content),
            style = MaterialTheme.typography.bodyLarge,
            color = color
        )
        is MdBlock.CodeBlock -> CodeBlockView(block)
        is MdBlock.BulletList -> ListBlock(block.items, ordered = false, color = color)
        is MdBlock.OrderedList -> ListBlock(block.items, ordered = true, color = color)
        is MdBlock.Quote -> QuoteBlock(block)
        MdBlock.Divider -> DividerView()
    }
}

/** 标题：1~3 级映射到主题排版；一律加粗以强化层级（正文气泡内不做过大字号）。 */
@Composable
private fun HeadingBlock(block: MdBlock.Heading, color: Color) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }
    Text(
        text = inlineSpans(block.content),
        style = style.copy(fontWeight = FontWeight.Bold),
        color = color
    )
}

/** 代码块：等宽 + surface-1 底 + 语言标签 + 横向滚动 + 复制。 */
@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val clipboard = LocalClipboardManager.current
    val machine = machineText.copy(color = MaterialTheme.colorScheme.onSurface)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LocalAkColors.surface1)
            .padding(AgentSpacing.space3)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.language.ifBlank { "代码" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { clipboard.setText(AnnotatedString(block.code)) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(AgentSpacing.space2))
        // 横向滚动：超长代码行不换行、不撑破气泡（垂直方向由宿主 LazyColumn 承担）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            // W-30：语法高亮——复用编辑器同一个词法器，模型给的代码块也能着色。
            // 只改显示：复制按钮拿到的仍是 block.code 原文。
            val keywordColor = MaterialTheme.colorScheme.primary
            val stringColor = MaterialTheme.colorScheme.tertiary
            val commentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            val numberColor = MaterialTheme.colorScheme.secondary
            Text(
                text = remember(
                    block.code, block.language,
                    keywordColor, stringColor, commentColor, numberColor
                ) {
                    highlightCode(
                        code = block.code.ifEmpty { " " },
                        language = block.language,
                        keyword = keywordColor,
                        string = stringColor,
                        comment = commentColor,
                        number = numberColor
                    )
                },
                style = machine,
                softWrap = false
            )
        }
    }
}

/** 列表：项目符号 / 序号 + 正文；嵌套子列表缩进一级递归渲染。 */
@Composable
private fun ListBlock(items: List<MdListItem>, ordered: Boolean, color: Color) {
    Column(Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(AgentSpacing.space1))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = inlineSpans(item.content),
                        style = MaterialTheme.typography.bodyLarge,
                        color = color
                    )
                    val sub = item.subList
                    if (sub != null) {
                        Spacer(Modifier.height(AgentSpacing.space1))
                        MarkdownBlock(sub, color)
                    }
                }
            }
        }
    }
}

/** 引用块：左侧 primary 竖条 + surface-1 底 + 次级文字。 */
@Composable
private fun QuoteBlock(block: MdBlock.Quote) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(LocalAkColors.surface1)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = inlineSpans(block.content),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(AgentSpacing.space3)
        )
    }
}

/** 分割线：1dp outline 水平线。 */
@Composable
private fun DividerView() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = AgentSpacing.space1)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

/** 把行内 AST 拍平成 [AnnotatedString]（样式走 SpanStyle，语义色从主题取）。 */
@Composable
private fun inlineSpans(spans: List<MdSpan>): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = LocalAkColors.surface1
    return buildAnnotatedString { appendSpans(spans, primary, codeBg) }
}

/** 递归追加：粗体可含斜体等嵌套，逐层 `withStyle` 叠加。 */
private fun AnnotatedString.Builder.appendSpans(
    spans: List<MdSpan>,
    primary: Color,
    codeBg: Color
) {
    for (span in spans) {
        when (span) {
            is MdSpan.Plain -> append(span.text)
            is MdSpan.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
            ) { append(span.text) }
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendSpans(span.children, primary, codeBg)
            }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendSpans(span.children, primary, codeBg)
            }
            is MdSpan.Link -> withStyle(
                SpanStyle(color = primary, textDecoration = TextDecoration.Underline)
            ) { appendSpans(span.children, primary, codeBg) }
        }
    }
}

/**
 * 把代码文本转成带颜色的 [AnnotatedString]（W-30）。
 *
 * 语言别名先归一再查词法器；不认识的语言返回纯文本——不猜、不硬套关键字。
 * 颜色从 MaterialTheme 取（跟随主题/深色模式），不在词法器里写死色值。
 */
private fun highlightCode(
    code: String,
    language: String,
    keyword: Color,
    string: Color,
    comment: Color,
    number: Color
): AnnotatedString {
    val normalized = SyntaxHighlighter.normalizeLanguage(language) ?: return AnnotatedString(code)
    return buildAnnotatedString {
        append(code)
        SyntaxHighlighter.spans(code, normalized).forEach { span ->
            // 词法器保证有序不重叠，但边界仍做一次夹取：渲染层不该因越界崩
            val start = span.start.coerceIn(0, code.length)
            val end = span.end.coerceIn(start, code.length)
            if (end <= start) return@forEach
            addStyle(
                SpanStyle(color = when (span.kind) {
                    SyntaxHighlighter.Kind.KEYWORD -> keyword
                    SyntaxHighlighter.Kind.STRING -> string
                    SyntaxHighlighter.Kind.COMMENT -> comment
                    SyntaxHighlighter.Kind.NUMBER -> number
                }),
                start,
                end
            )
        }
    }
}
