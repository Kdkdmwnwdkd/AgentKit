#!/usr/bin/env bash
# W-RT9 长对话全档保留——QA 验证
# 行为（真编真跑纯层）：裁剪语义 / 分页边界 / 导出含归档渲染。
#   纯层只用 TurnRecord 构造（TurnRecord(user=rec)），不碰序列化（沙箱无 kotlinx）。
# 静态：存储层接线（裁剪→归档、删除联动、VM 导出带归档）。
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
PROJ="$DIR/../.."
CORE="$PROJ/app/src/main/java/com/example/agentkit/core"
pass=0; fail=0
has() { if grep -qF -- "$3" "$2" 2>/dev/null; then pass=$((pass+1)); echo "  PASS  $1"; else fail=$((fail+1)); echo "  FAIL  $1（缺：$3）"; fi }
check() { if grep -q "$2" "$1" 2>/dev/null; then pass=$((pass+1)); echo "  PASS  $3"; else fail=$((fail+1)); echo "  FAIL  $3"; fi }

echo "== 静态：接线与边界 =="
ARCH="$CORE/ConversationArchive.kt"
STORE="$CORE/ConversationStore.kt"
MD="$CORE/ConversationMarkdown.kt"
VM="$PROJ/app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt"
has "裁剪纯函数存在" "$ARCH" "fun evictOldest"
has "分段读纯函数存在" "$ARCH" "fun page"
has "存储层裁剪带出参" "$STORE" "evicted: MutableList<TurnRecord>"
has "超限逐轮进归档" "$STORE" "while (out.size > MAX_MESSAGES) evicted.add(out.removeAt(0))"
has "追加落归档" "$STORE" "appendArchive(convId, evicted)"
has "归档写失败不崩" "$STORE" "runCatching"
has "删除联动清归档" "$STORE" "archiveFile(convId).delete()"
has "归档可分段读" "$STORE" "fun archivedTurns("
has "导出带归档参数" "$MD" "archivedTurns: List<TurnRecord> = emptyList()"
has "VM 导出传归档" "$VM" "conversationToMarkdown(rec, conversationStore.archivedTurns(convId))"
# 归档纪律：归档是加菜——写失败绝不能拖垮聊天主流程（appendArchive 整体 runCatching 包住）
if grep -A3 'private fun appendArchive' "$STORE" | grep -q 'runCatching'; then pass=$((pass+1)); echo "  PASS  归档追加整体容错"; else fail=$((fail+1)); echo "  FAIL  归档追加整体容错"; fi

echo "== 行为：裁剪与分页真编真跑 =="
KOTLINC="/tmp/ktcheck/kcomp.jar"
if [ ! -f "$KOTLINC" ]; then
  echo "  SKIP  沙箱无编译器（CI 由 gradle 真编）"; echo "RESULT: PASS=$pass FAIL=$fail"; exit 0
fi
WORK="$(mktemp -d)"
cp "$CORE/ConversationArchive.kt" "$WORK/Archive.kt"
cp "$CORE/ConversationBranch.kt" "$WORK/Branch.kt"
cp "$CORE/ConversationMarkdown.kt" "$WORK/Markdown.kt"
# 沙箱缺 kotlinx-serialization jar：给 @Serializable 一个最小替身（只当标记用）；
# 模型类形状从真源复制（字段漂移会立刻在真机 gradle 编译暴露——沙箱替身只服务纯逻辑断言）。
cat > "$WORK/ser-stub.kt" <<'EOF'
package kotlinx.serialization

@Target(AnnotationTarget.CLASS)
annotation class Serializable
EOF
cat > "$WORK/models-stub.kt" <<'EOF'
package com.example.agentkit.core

internal const val ROLE_USER = "user"
internal const val ROLE_ASSISTANT = "assistant"
internal const val ROLE_TOOL = "tool"

data class MsgRecord(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val isError: Boolean = false,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val reasoning: String = "",
    val timeMs: Long = 0,
    val modelId: String = ""
)

data class ConversationRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<MsgRecord> = emptyList(),
    val turns: List<TurnRecord> = emptyList(),
    val pinned: Boolean = false,
    val workspaceDir: String = ""
)

data class TokenUsage(val promptTokens: Long = 0, val completionTokens: Long = 0) {
    val totalTokens: Long get() = promptTokens + completionTokens
    companion object {
        fun sum(usages: List<TokenUsage?>): TokenUsage = TokenUsage(
            promptTokens = usages.sumOf { it?.promptTokens ?: 0L },
            completionTokens = usages.sumOf { it?.completionTokens ?: 0L }
        )
    }
}
EOF
cat > "$WORK/check.kt" <<'EOF'
import com.example.agentkit.core.ConversationArchive
import com.example.agentkit.core.MsgRecord
import com.example.agentkit.core.TurnRecord

fun rec(text: String) = MsgRecord(id = text, role = "user", content = text)
fun turn(label: String) = TurnRecord(user = rec(label))
var pass = 0; var fail = 0
fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) { pass++; println("  PASS  $name") } else { fail++; println("  FAIL  $name（实得 $actual，应得 $expected）") }
}
fun labels(turns: List<TurnRecord>) = turns.map { it.user.content }
fun contains(name: String, haystack: String, needle: String) {
    if (haystack.contains(needle)) { pass++; println("  PASS  $name") } else { fail++; println("  FAIL  $name（缺：$needle）") }
}

fun main() {
    // —— 裁剪语义 ——
    val five = (1..5).map { turn("T${it}") }
    eq("等于上限不裁", labels(ConversationArchive.evictOldest(five, 5).evicted), emptyList<String>())
    eq("等于上限原样返回", labels(ConversationArchive.evictOldest(five, 5).kept), listOf("T1","T2","T3","T4","T5"))
    val e73 = ConversationArchive.evictOldest(five, 3)
    eq("超限裁掉最旧两轮", labels(e73.evicted), listOf("T1","T2"))
    eq("超限保留最新三轮", labels(e73.kept), listOf("T3","T4","T5"))
    eq("上限 1 只留最新", labels(ConversationArchive.evictOldest(five, 1).kept), listOf("T5"))
    eq("零上限全进归档", ConversationArchive.evictOldest(five, 0).evicted.size, 5)
    eq("空轮不炸", ConversationArchive.evictOldest(emptyList<TurnRecord>(), 3).evicted.size, 0)

    // —— 分页边界 ——
    val arch = (1..10).map { turn("A${it}") }
    eq("正常一页", labels(ConversationArchive.page(arch, 2, 3)), listOf("A3","A4","A5"))
    eq("末页不越界", labels(ConversationArchive.page(arch, 8, 5)), listOf("A9","A10"))
    eq("offset 越界夹空页", labels(ConversationArchive.page(arch, 99, 3)), emptyList<String>())
    eq("负 offset 夹到头", labels(ConversationArchive.page(arch, -5, 2)), listOf("A1","A2"))
    eq("limit 0 空页", labels(ConversationArchive.page(arch, 0, 0)), emptyList<String>())
    eq("负 limit 空页", labels(ConversationArchive.page(arch, 0, -1)), emptyList<String>())
    eq("空归档不炸", labels(ConversationArchive.page(emptyList(), 0, 5)), emptyList<String>())

    // —— 导出含归档渲染（纯文本断言）——
    val sel = rec("回答")
    val md = com.example.agentkit.core.conversationToMarkdown(
        com.example.agentkit.core.ConversationRecord(
            id = "c1", title = "导出测试", createdAt = 0, updatedAt = 0,
            turns = listOf(turn("当前第一轮")).map { it.copy(variants = listOf(sel)) }
        ),
        listOf(turn("归档轮甲"), turn("归档轮乙"))
    )
    contains("导出含归档说明", md, "归档历史")
    contains("轮号连续含归档", md, "## 第 1/3 轮")
    contains("当前轮号正确", md, "## 第 3/3 轮")
    contains("归档轮内容在", md, "归档轮甲")
    // 无归档时输出不得多出说明行（旧版行为不变；标题避开「归档」二字防自坑）
    val md0 = com.example.agentkit.core.conversationToMarkdown(
        com.example.agentkit.core.ConversationRecord(
            id = "c2", title = "老对话", createdAt = 0, updatedAt = 0,
            turns = listOf(turn(" solo")).map { it.copy(variants = listOf(sel)) }
        )
    )
    if (!md0.contains("归档")) { pass++; println("  PASS  无归档输出不变") } else { fail++; println("  FAIL  无归档输出不该出现归档字样") }

    println("RESULT: PASS=${pass} FAIL=${fail}")
    if (fail > 0) kotlin.system.exitProcess(1)
}
EOF
# 模板字面量里 ${} 已按纪律写 ${it} 形式——heredoc 内的 Kotlin 字符串模板照常
KTOUT="$WORK/out"; mkdir -p "$KTOUT"
JARS="/tmp/kstdlib.jar:/tmp/ktcheck/coroutines.jar:/tmp/ktcheck/trove.jar:/tmp/ktcheck/annotations.jar"
if java -cp "$KOTLINC:$JARS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -cp "$JARS" -d "$KTOUT" "$WORK/ser-stub.kt" "$WORK/models-stub.kt" "$WORK/Branch.kt" \
    "$WORK/Archive.kt" "$WORK/Markdown.kt" "$WORK/check.kt" > "$WORK/compile.log" 2>&1; then
  BEHAVIOR=$(java -cp "$KTOUT:$JARS" CheckKt 2>&1)
  echo "$BEHAVIOR"
  BP=$(echo "$BEHAVIOR" | grep -o 'PASS=[0-9]*' | tail -1 | cut -d= -f2)
  BF=$(echo "$BEHAVIOR" | grep -o 'FAIL=[0-9]*' | tail -1 | cut -d= -f2)
  pass=$((pass+BP)); fail=$((fail+BF))
else
  echo "  FAIL  纯层编译不过"; grep -a 'error' "$WORK/compile.log" | head -10; fail=$((fail+1))
fi

echo "RESULT: PASS=$pass FAIL=$fail"
[ "$fail" -gt 0 ] && exit 1
exit 0
