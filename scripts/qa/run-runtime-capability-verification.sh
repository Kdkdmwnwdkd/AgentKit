#!/usr/bin/env bash
# 运行层能力契约 harness（W-RT1 合入版）。
#
# 背景：runtime/idle-unload-w1 分支被摘进 main 时改了三处（详见
# docs/运行层第一块-接线说明.md 第七节），这三处全是「看不见的坑」，
# 真机上不跑几千次碰不到，只能靠断言锁住：
#   1. 注册表必须叫 CapabilityHost——仓库里已有一个 skill.CapabilityRegistry
#      （工具权限 fail-closed 真相源），同名不同义会让 grep / 自动导入互相误伤；
#   2. 启动期等待必须有超时——原实现是无超时 while 轮询，谁 start() 抛异常
#      又把状态留在 STARTING，后来所有调用就永久挂住（死锁，不是变慢）；
#   3. 空闲阈值必须是 30 分钟——3B 冷加载实测约 14 秒，5 分钟一卸等于拿首字
#      延迟换 2GB 内存，划不来。
#
# 静态锁接线，行为真编真跑（有 stdlib + coroutines 才跑，CI 里由 gradle 真编）。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
ROOT=$(pwd)

RT="$ROOT/app/src/main/java/com/example/agentkit/runtime"
CAP="$RT/AgentCapability.kt"
LLM="$RT/LocalLlmRuntime.kt"
POLICY="$RT/IdleUnloadPolicy.kt"
APP="$ROOT/app/src/main/java/com/example/agentkit/AgentKitApplication.kt"
VM="$ROOT/app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt"

pass=0
fail=0
failed=""

ok()  { pass=$((pass + 1)); echo "  PASS  $1"; }
bad() { fail=$((fail + 1)); failed="$failed|$1"; echo "  FAIL  $1"; }

# 文件里必须出现某段字面量
expect() {
  local name="$1" file="$2" needle="$3"
  if [ -f "$file" ] && grep -qF -- "$needle" "$file"; then ok "$name"; else bad "$name（缺：$needle）"; fi
}

# 文件里必须不出现某段字面量（防回退）
reject() {
  local name="$1" file="$2" needle="$3"
  if [ -f "$file" ] && grep -qF -- "$needle" "$file"; then bad "$name（不该有：$needle）"; else ok "$name"; fi
}

echo "== 静态：文件与契约 =="
for f in "$CAP" "$LLM" "$POLICY"; do
  [ -f "$f" ] && ok "存在 $(basename "$f")" || bad "缺失 $f"
done

expect "接口声明 start"            "$CAP" 'suspend fun start()'
expect "接口声明 stop"             "$CAP" 'suspend fun stop()'
expect "接口声明 state"            "$CAP" 'val state: StateFlow<CapabilityState>'
expect "接口有使用心跳 onUse"      "$CAP" 'fun onUse()'
expect "状态含 FAILED"             "$CAP" 'FAILED'

echo
echo "== 静态：注册表命名（与 skill.CapabilityRegistry 不撞车） =="
expect "注册表叫 CapabilityHost"   "$CAP" 'object CapabilityHost'
if [ -d "$RT" ] && grep -rq 'object CapabilityRegistry' "$RT" 2>/dev/null; then
  bad "runtime 包内仍有同名 CapabilityRegistry"
else
  ok "runtime 包内无同名 CapabilityRegistry"
fi
expect "本地模型运行时用 CapabilityHost 注册" "$LLM" 'CapabilityHost.register(this)'
reject "本地模型运行时不再引用旧名"            "$LLM" 'CapabilityRegistry.register'

echo
echo "== 静态：启动期必须有上限（不许无超时死等） =="
expect "声明了等待上限常量"        "$CAP" 'START_WAIT_TIMEOUT_MS'
expect "用 withTimeoutOrNull 兜住" "$CAP" 'withTimeoutOrNull(START_WAIT_TIMEOUT_MS)'
reject "没有裸的 while 轮询"       "$CAP" 'while (state.value == CapabilityState.STARTING) {'
expect "启动失败包装成有类型异常"  "$CAP" 'CapabilityStartException'
expect "异常里带能力 id"           "$CAP" 'val capabilityId: String'
expect "依赖递归拉起"              "$CAP" 'for (dep in capability.dependsOn) startIfNeeded(dep)'
expect "启动后核对状态"            "$CAP" 'start 必须把状态置为 READY'

echo
echo "== 静态：空闲阈值 30 分钟 =="
expect "默认 30 分钟"              "$LLM" 'const val DEFAULT_IDLE_MINUTES = 30'
expect "策略层同步 30 分钟"        "$POLICY" 'DEFAULT_IDLE_TIMEOUT_MS: Long = 30 * 60_000L'
reject "没有残留 5 分钟默认值"      "$LLM" 'DEFAULT_IDLE_MINUTES = 5'

echo
echo "== 静态：接线与两道闸门 =="
expect "Application 启动运行层"    "$APP" 'LocalLlmRuntime.init(this)'
expect "provider 工厂包了装饰器"   "$VM" 'wrapLocalLlm(LocalLlamaClient('
expect "释放前问能否安全释放"      "$LLM" 'override val isSafeToRelease: Boolean'
expect "在途计数 +1"               "$LLM" 'inFlight.incrementAndGet()'
expect "在途计数 -1"               "$LLM" 'inFlight.decrementAndGet()'
expect "流式记账不丢（onCompletion）" "$LLM" '.onCompletion { LocalLlmRuntime.endUse() }'
expect "chat 走 try/finally"       "$LLM" '} finally {'
expect "释放日志说清代价"          "$LLM" '下次对话会自动重载'
expect "stop 取消事件协程"         "$LLM" 'eventJob?.cancel()'

echo
echo "== 行为：契约真编真跑 =="
KOTLINC=$(command -v kotlinc 2>/dev/null || true)
if [ -z "$KOTLINC" ]; then
  echo "  SKIP  本机无 kotlinc，行为断言交 CI（静态断言已全跑）"
else
  # 两套编译方式自适应：
  #  - 完整 kotlinc（CI 装的那个）：-include-runtime 打自包含 jar，最简单；
  #  - 精简 kotlinc（本沙箱这种没有 lib/ 的）：-include-runtime 会报
  #    "Couldn't find kotlin-stdlib"，改显式 -cp stdlib:coroutines，输出到目录。
  #    两种都要能跑，否则本地检查会因为「环境缺库」被误判成代码红。
  find_jar() { ls -1 $1 2>/dev/null | head -1; }
  STDLIB=$(find_jar "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*.jar")
  [ -n "$STDLIB" ] || STDLIB=$(find_jar "/tmp/khome/lib/kotlin-stdlib.jar")
  CORO=$(find_jar "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/*/kotlinx-coroutines-core-jvm-1.8.1.jar")
  [ -n "$CORO" ] || CORO=$(find_jar "/tmp/kc/kotlinx-coroutines-core-jvm-*.jar")

  TMP=$(mktemp -d)
  cat > "$TMP/check.kt" <<'EOF'
package qa

import com.example.agentkit.runtime.AgentCapability
import com.example.agentkit.runtime.CapabilityHost
import com.example.agentkit.runtime.CapabilityStartException
import com.example.agentkit.runtime.CapabilityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

var passN = 0
var failN = 0
fun ok(c: Boolean, n: String) {
    if (c) { passN++; println("  PASS  $n") } else { failN++; println("  FAIL  $n") }
}

/** 假能力：把「启动几次 / 什么顺序 / 什么状态」全部显式化。 */
class Fake(
    override val id: String,
    override val dependsOn: List<String> = emptyList(),
    private val boom: Boolean = false,
    private val endState: CapabilityState = CapabilityState.READY,
    private val shared: MutableList<String>? = null,
    private val anotherBoom: java.lang.Runnable? = null,
) : AgentCapability {
    override val displayName: String = id
    private val s = MutableStateFlow(CapabilityState.REGISTERED)
    override val state: StateFlow<CapabilityState> = s
    var starts = 0

    override suspend fun start() {
        starts++
        shared?.add(id)
        if (boom) {
            s.value = CapabilityState.STARTING
            anotherBoom?.run()
            throw IllegalStateException("boom-on-purpose")
        }
        s.value = endState
    }

    override suspend fun stop() { s.value = CapabilityState.STOPPED }
}

fun main() = runBlocking {
    // 1. 幂等：连调两次只启动一次
    val a = Fake("t-idem")
    CapabilityHost.register(a)
    CapabilityHost.startIfNeeded("t-idem")
    CapabilityHost.startIfNeeded("t-idem")
    ok(a.starts == 1, "已就绪再调只返回，不重复启动")
    ok(a.state.value == CapabilityState.READY, "启动后状态为 READY")

    // 2. 依赖先于自己启动
    val order = mutableListOf<String>()
    val base = Fake("t-base", shared = order)
    val child = Fake("t-child", dependsOn = listOf("t-base"), shared = order)
    CapabilityHost.register(base)
    CapabilityHost.register(child)
    CapabilityHost.startIfNeeded("t-child")
    ok(order == listOf("t-base", "t-child"), "依赖先启动（实际顺序 $order）")

    // 3. start 抛异常 → 有类型异常，带能力 id，且不吞
    val boom = Fake("t-boom", boom = true)
    CapabilityHost.register(boom)
    var caught: CapabilityStartException? = null
    try { CapabilityHost.startIfNeeded("t-boom") } catch (e: CapabilityStartException) { caught = e }
    ok(caught != null, "start 抛异常时抛 CapabilityStartException")
    ok(caught?.capabilityId == "t-boom", "异常能定位到能力 id")
    ok(caught?.cause?.message == "boom-on-purpose", "原始异常保留在 cause 里")

    // 4. start 返回但没置 READY → 判失败（防「假装启动成功」）
    val lazyCap = Fake("t-lazy", endState = CapabilityState.REGISTERED)
    CapabilityHost.register(lazyCap)
    var lazyCaught = false
    try { CapabilityHost.startIfNeeded("t-lazy") } catch (e: CapabilityStartException) { lazyCaught = true }
    ok(lazyCaught, "start 返回但状态未就绪 → 判失败")

    // 5. 未注册 → 明确报错，不是静默
    var missing = false
    try { CapabilityHost.startIfNeeded("t-nope") } catch (e: IllegalStateException) { missing = true }
    ok(missing, "未注册能力明确报错")

    // 6. 停掉后能重新启动（幂等 stop 之后的可用性）
    val again = Fake("t-again")
    CapabilityHost.register(again)
    CapabilityHost.startIfNeeded("t-again")
    again.stop()
    CapabilityHost.startIfNeeded("t-again")
    ok(again.starts == 2, "stop 后可重新启动")

    // 7. 重复注册必须被拒（防止两个实例抢同一个 id）
    var dup = false
    try { CapabilityHost.register(Fake("t-idem")) } catch (e: IllegalArgumentException) { dup = true }
    ok(dup, "重复 id 注册被拒")

    println("RESULT: PASS=$passN FAIL=$failN")
    if (failN > 0) kotlin.system.exitProcess(1)
}
EOF

  compiled=0
  if [ -n "$STDLIB" ] && [ -n "$CORO" ]; then
    if "$KOTLINC" "$CAP" "$TMP/check.kt" -cp "$STDLIB:$CORO" -d "$TMP/out" >"$TMP/kc.log" 2>&1; then
      compiled=1
      OUT=$(java -cp "$TMP/out:$STDLIB:$CORO" qa.CheckKt 2>"$TMP/run.err")
    fi
  else
    # 完整 kotlinc：自包含 jar
    if "$KOTLINC" "$CAP" "$TMP/check.kt" -d "$TMP/check.jar" -include-runtime >"$TMP/kc.log" 2>&1; then
      compiled=1
      OUT=$(java -jar "$TMP/check.jar" 2>"$TMP/run.err")
    fi
  fi

  if [ "$compiled" -eq 0 ]; then
    echo "  SKIP  本机 kotlinc 缺 stdlib/coroutines，行为断言交 CI（静态断言已全跑）"
    echo "        编译器输出前 3 行："
    head -3 "$TMP/kc.log" 2>/dev/null | sed 's/^/        /'
  else
    echo "$OUT"
    # 编译成功但一行输出都没有 = JVM 静默崩（stderr 被吞过，这里补打）
    if ! echo "$OUT" | grep -q "RESULT:"; then
      bad "行为断言无输出（JVM 崩了），stderr：$(head -2 "$TMP/run.err" 2>/dev/null | tr '\n' ' ')"
    elif echo "$OUT" | grep -q "FAIL=0"; then
      ok "行为断言全过"
    else
      bad "行为断言有红"
    fi
  fi
  rm -rf "$TMP"
fi

echo
if [ "$fail" -eq 0 ]; then
  # 汇总行必须带 FAIL=0：run-all.sh 靠这个串识别「跑完且全过」
  echo "RESULT: PASS=$pass FAIL=0"
  exit 0
fi
echo "RESULT: PASS=$pass FAIL=$fail$failed"
exit 1
