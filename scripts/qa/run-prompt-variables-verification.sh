#!/usr/bin/env bash
# 提示词变量验证：{{变量}} 占位的收集与填槽有没有接对。
#
# 起因：模板类提示词（排查问题 / 规划手机任务）尾部留着让用户手动补的口子。
# 变量功能的全部价值在三条语义里——收集按首次出现顺序去重、缺值不做半填、
# 非占位（单边花括号/空名）原样保留不猜语义。所以这里既查静态（接线没断），
# 也真编真跑（纯函数，规则没有反着写）。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

VARS="app/src/main/java/com/example/agentkit/core/PromptVariables.kt"
LIB="app/src/main/java/com/example/agentkit/core/PromptLibrary.kt"
SCREEN="app/src/main/java/com/example/agentkit/ui/PromptLibraryScreen.kt"
CHAT="app/src/main/java/com/example/agentkit/ui/ChatScreen.kt"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

has() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then pass "$name"; else fail "$name（缺：$needle）"; fi
}

hasnt() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then fail "$name（不该出现：$needle）"; else pass "$name"; fi
}

echo "== 静态：结构 =="

if [ -f "$VARS" ]; then pass "变量规则存在"; else fail "变量规则缺失：$VARS"; fi

# 与 PromptLibrary / ApprovalTier / ResourcePlan 同一条纪律：规则层必须纯 Kotlin。
hasnt "规则层不依赖 Android" "$VARS" "import android."

echo "== 静态：三条语义（改语义必须同步改这里） =="

has "收集：按占位捕获变量名" "$VARS" "fun extractPromptVariables(content: String): List<String>"
has "收集：按首次出现顺序去重" "$VARS" ".distinct()"
has "填槽：缺值不做半填" "$VARS" "if (missing.isNotEmpty()) return PromptFillResult.Missing(missing)"
has "填槽：占位全部出现处替换" "$VARS" "PROMPT_VARIABLE.replace(content)"
has "填槽：空白值按缺值处理" "$VARS" 'values[it].isNullOrBlank()'

echo "== 静态：接线 =="

has "选择器带填槽弹窗" "$SCREEN" "fun PromptFillDialog"
has "弹窗确认走纯函数填值" "$SCREEN" "com.example.agentkit.core.fillPromptVariables"
has "聊天页有无变量直插路径" "$CHAT" "extractPromptVariables(item.content).isEmpty()"
has "聊天页有填槽分流" "$CHAT" "fillPromptItem"
has "填好仍走原插入入口" "$SCREEN" "onConfirm: (String) -> Unit"

echo "== 行为：规则真编真跑（有 kotlinc 才跑，CI 里由 gradle 真编） =="

if [ -n "${KOTLINC_JAR:-}" ] && [ -f "${KOTLINC_JAR:-}" ]; then
    TMP="$(mktemp -d)"
    cat > "$TMP/check.kt" <<'EOF'
import com.example.agentkit.core.extractPromptVariables
import com.example.agentkit.core.fillPromptVariables
import com.example.agentkit.core.PromptFillResult
var pass = 0; var fail = 0
fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }
fun main() {
// 收集
ok(extractPromptVariables("排查 {{问题}} 与 {{场景}}") == listOf("问题", "场景"), "收集：两个变量按序")
ok(extractPromptVariables("{{a}} 和 {{a}} 再来一次 {{a}}") == listOf("a"), "收集：同名去重")
ok(extractPromptVariables("{{ 名称 }} 带空格等价") == listOf("名称"), "收集：占位两侧空白容忍")
ok(extractPromptVariables("{{}} 和 {{ }} 都不算") .isEmpty(), "收集：空名不算变量")
ok(extractPromptVariables("单边 {{abc 和 {{a{b}} 不算") .isEmpty(), "收集：坏占位不猜语义")
ok(extractPromptVariables("没有变量").isEmpty(), "收集：普通文案零变量")
// 填槽
val r1 = fillPromptVariables("排查 {{问题}}：{{问题}} 复现", mapOf("问题" to "闪退"))
ok(r1 is PromptFillResult.Ok && r1.content == "排查 闪退：闪退 复现", "填槽：全部出现处替换")
val r2 = fillPromptVariables("{{a}} 与 {{b}}", mapOf("a" to "甲"))
ok(r2 is PromptFillResult.Missing && r2.names == listOf("b"), "填槽：缺值列名不做半填")
val r3 = fillPromptVariables("{{a}} 与 {{b}}", mapOf("a" to "甲", "b" to "  "))
ok(r3 is PromptFillResult.Missing, "填槽：空白值按缺值")
val r4 = fillPromptVariables("单边 {{abc 保留", mapOf("abc" to "x"))
ok(r4 is PromptFillResult.Ok && r4.content == "单边 {{abc 保留", "填槽：非占位原样保留")
println("RESULT: PASS=$pass FAIL=$fail")
if (fail > 0) kotlin.system.exitProcess(1)
}
EOF
    STDLIB="$(ls "$KOTLINC_STDLIB" 2>/dev/null || true)"
    # 编译器自身可能还缺 coroutines 等包：KOTLINC_DEPS 传冒号分隔的补充 jar（可选）
    COMP_CP="$KOTLINC_JAR${KOTLINC_DEPS:+:$KOTLINC_DEPS}"
    if java -cp "$COMP_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -cp "$STDLIB" -d "$TMP" "$VARS" "$TMP/check.kt" >/dev/null 2>&1; then
        OUT=$(java -cp "$TMP:$STDLIB" CheckKt 2>/dev/null)
        echo "$OUT"
        echo "$OUT" | grep -q "FAIL=0" && pass "行为断言全过" || fail "行为断言有红"
    else
        fail "行为断言编译失败"
    fi
    rm -rf "$TMP"
else
    echo "  SKIP  未设 KOTLINC_JAR，跳过行为断言（CI 里由 gradle 真编）"
fi

echo
echo "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
