#!/usr/bin/env bash
# 资源档位验证：机型画像 → 推理参数的落点有没有接对。
#
# 起因：参数默认值是按「平均机器」拍的，4GB 入门机拿同一套默认值会撞内存墙。
# 档位表的价值全在数字里（4/4096、6/8192、8/16384）和两条纪律里——
# 自动档只减不增、锁档优先于自动。所以这里既查静态（表没被改回乐观值），
# 也真编真跑（规则没有反着写）。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

DIR="app/src/main/java/com/example/agentkit/core/llm/local"
PLAN="$DIR/ResourcePlan.kt"
SETTING="$DIR/LocalLlamaSetting.kt"
CLIENT="$DIR/LocalLlamaClient.kt"
VM="app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt"
SCREEN="app/src/main/java/com/example/agentkit/ui/ModelConfigScreen.kt"

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

if [ -f "$PLAN" ]; then pass "档位表存在"; else fail "档位表缺失：$PLAN"; fi

# 与 LocalDeviceRules 同一条纪律：规则层必须纯 Kotlin，行为断言才编得过、跑得动。
hasnt "规则层不依赖 Android" "$PLAN" "import android."

echo "== 静态：档位表数字（改表必须同步改这里） =="

has "省电档线程 4" "$PLAN" "threads = 4,"
has "省电档上下文封顶 4096" "$PLAN" "contextCap = 4096,"
has "省电档建议安全模式" "$PLAN" "suggestSafeMode = true,"
has "均衡档线程 6" "$PLAN" "threads = 6,"
has "均衡档上下文封顶 8192" "$PLAN" "contextCap = 8192,"
has "性能档不设额外限制" "$PLAN" "contextCap = 16384,"
has "入门档落省电" "$PLAN" "DeviceTier.ENTRY, DeviceTier.LOW -> ResourceTier.ECO"
has "旗舰档落性能" "$PLAN" "DeviceTier.HIGH -> ResourceTier.PERFORMANCE"

echo "== 静态：两条纪律 =="

# 自动档只减不增：用户手动调高的值不被悄悄覆盖。这条最容易被「好心」改成
# 直接用档位值——那就退化成「用户设置形同虚设」，比没有档位表还糟。
has "自动档线程只减不增" "$PLAN" "fun effectiveThreads(userThreads: Int): Int = minOf(userThreads, threads)"
has "自动档上下文只减不增" "$PLAN" "fun capContext(userCtx: Int): Int = minOf(userCtx, contextCap)"
has "锁档优先于自动" "$PLAN" "val locked = ResourceTier.parse(lock)"
has "锁档解析失败回自动" "$PLAN" "val tier = locked ?: autoTier(advice)"
has "安全模式只建议不代开" "$PLAN" "只建议、不代开"

echo "== 静态：接线 =="

has "设置项存在" "$SETTING" "resource_tier_lock"
has "设置默认 AUTO" "$SETTING" "?: \"AUTO\""
has "客户端读档位" "$CLIENT" "ResourcePlanRules.resolve(setting.resourceTierLock"
has "装载用档位线程" "$CLIENT" "engine.acquire(file.absolutePath, nCtx, planThreads, gpu.layers)"
has "CPU 回退也守档位" "$CLIENT" "engine.acquire(file.absolutePath, cpuCtx, planThreads, 0)"
has "视图模型有状态" "$VM" "localResourceTierLock"
has "视图模型有 setter" "$VM" "fun setLocalResourceTierLock"
has "设置页有档位选择" "$SCREEN" "setLocalResourceTierLock"

echo "== 行为：规则真编真跑（有 kotlinc 才跑，CI 里由 gradle 真编） =="

if command -v kotlinc >/dev/null 2>&1; then
    TMP="$(mktemp -d)"
    cat > "$TMP/stubs.kt" <<'EOF'
// 行为断言专用最小桩：只补齐 ResourcePlan.kt 引用的画像类型，判定逻辑一律真实实现。
package com.example.agentkit.core.llm.local

enum class SocVendor(val label: String) { QUALCOMM("高通"), MTK("联发科"), OTHER("其他") }
enum class VulkanMaturity { GOOD, MIXED, POOR, UNKNOWN }
enum class GpuFamily(val label: String, val maturity: VulkanMaturity) { ADRENO("Adreno", VulkanMaturity.GOOD) }
enum class DeviceTier(val label: String) { ENTRY("入门"), LOW("中低端"), MID("中高端"), HIGH("旗舰") }
enum class GpuPath(val label: String) { STABLE("稳"), RISKY("险"), NONE("无") }
enum class GpuMode(val label: String) { AUTO("自动"), FORCE("强制"), OFF("关闭") }
enum class LocalBackend { LOCAL_LLAMA }
data class DeviceAdvice(
    val tier: DeviceTier, val vendor: SocVendor, val gpu: GpuFamily, val gpuModel: String?,
    val gpuPath: GpuPath, val gpuPathReason: String?, val vulkanVersion: String?,
    val npuUsable: Boolean, val npuReason: String?, val preferredPath: LocalBackend,
    val modelBudget: String, val memoryBudgetMb: Int, val notes: List<String>,
)
EOF
    kotlinc "$TMP/stubs.kt" "$PLAN" "$DIR/LocalLlamaSetting.kt" -d "$TMP" >/dev/null 2>&1
    if [ $? -ne 0 ]; then
        fail "规则编译失败（见上）"
    else
        kotlinc "$TMP/stubs.kt" "$PLAN" "$DIR/LocalLlamaSetting.kt" -d "$TMP" >/dev/null 2>&1 # 已编过，这里仅为对称
        cat > "$TMP/check.kt" <<'EOF'
import com.example.agentkit.core.llm.local.*
var pass = 0; var fail = 0
fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }
fun advice(t: DeviceTier) = DeviceAdvice(t, SocVendor.OTHER, GpuFamily.ADRENO, null, GpuPath.STABLE, null, null, false, null, LocalBackend.LOCAL_LLAMA, "1B", 2048, emptyList())
val p = ResourcePlanRules
// 自动选档
ok(p.autoTier(advice(DeviceTier.ENTRY)) == ResourceTier.ECO, "入门 → 省电")
ok(p.autoTier(advice(DeviceTier.LOW)) == ResourceTier.ECO, "中低端 → 省电")
ok(p.autoTier(advice(DeviceTier.MID)) == ResourceTier.BALANCED, "中高端 → 均衡")
ok(p.autoTier(advice(DeviceTier.HIGH)) == ResourceTier.PERFORMANCE, "旗舰 → 性能")
// 只减不增
val eco = p.specFor(ResourceTier.ECO)
ok(eco.effectiveThreads(6) == 4, "自动省电：6 线程压到 4")
ok(eco.effectiveThreads(2) == 2, "自动省电：用户 2 线程不被抬高")
ok(eco.capContext(8192) == 4096, "自动省电：8K 上下文封到 4K")
ok(eco.capContext(2048) == 2048, "自动省电：用户 2K 不被抬高")
val perf = p.specFor(ResourceTier.PERFORMANCE)
ok(perf.effectiveThreads(6) == 6, "自动性能：不碰用户设置")
// 锁档优先，解析失败回自动
val adviceMid = advice(DeviceTier.MID)
ok(p.resolve("ECO", adviceMid).tier == ResourceTier.ECO, "锁省电：中端机也按省电来")
ok(p.resolve("PERFORMANCE", adviceMid).tier == ResourceTier.PERFORMANCE, "锁性能：中端机也按性能来")
ok(p.resolve("AUTO", adviceMid).tier == ResourceTier.BALANCED, "AUTO：跟机型走")
ok(p.resolve("写坏的值", adviceMid).tier == ResourceTier.BALANCED, "配置写坏：回自动")
ok(p.resolve(null, adviceMid).tier == ResourceTier.BALANCED, "未设置：回自动")
// 档位解析
ok(ResourceTier.parse("eco") == ResourceTier.ECO, "大小写不敏感")
ok(ResourceTier.parse("AUTO") == null, "AUTO 不是档位，是跟随")
println("RESULT: PASS=$pass FAIL=$fail")
if (fail > 0) kotlin.system.exitProcess(1)
EOF
        if kotlinc "$TMP/stubs.kt" "$PLAN" "$DIR/LocalLlamaSetting.kt" "$TMP/check.kt" -d "$TMP" -include-runtime >/dev/null 2>&1; then
            java -cp "$TMP/check.jar:$TMP" CheckKt >/dev/null 2>&1 || true
            OUT=$(java -cp "$(ls "$TMP"/*.jar 2>/dev/null | head -1):$TMP" CheckKt 2>/dev/null || java -cp "$TMP" CheckKt 2>/dev/null)
            echo "$OUT"
            echo "$OUT" | grep -q "FAIL=0" && pass "行为断言全过" || fail "行为断言有红"
        else
            fail "行为断言编译失败"
        fi
    fi
    rm -rf "$TMP"
else
    echo "  SKIP  未找到 kotlinc，跳过行为断言（CI 里由 gradle 真编）"
fi

echo "SUMMARY：PASS=$PASS FAIL=$FAIL"
exit $(( FAIL > 0 ? 1 : 0 ))
