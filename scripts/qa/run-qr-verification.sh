#!/usr/bin/env bash
# 二维码导入导出验证：自写编码器 × 现成解码库对拍 + 负载层行为断言。
#
# 起因：零依赖纪律下二维码编码器自写。编码器最怕「自己看着像、真机扫不出」，
# 所以行为断言的考官用现成解码库真解——解不出/解错即红，不给自嗨断言留活路。
# 负载层纪律：Key 绝不进二维码（负载只装非敏感字段），坏输入宽容拒收不抛。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

QR="app/src/main/java/com/example/agentkit/core/qr/QrCode.kt"
CFG="app/src/main/java/com/example/agentkit/core/qr/ConfigQr.kt"

PASS=0; FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }
has() { if grep -qF -- "$3" "$2" 2>/dev/null; then pass "$1"; else fail "$1（缺：$3）"; fi; }

echo "== 静态：纪律与边界 =="

has "负载层不含密钥字段" "$CFG" "data class ConfigPayload"
if grep -q 'apiKey' "$QR" "$CFG" 2>/dev/null; then fail "二维码层零密钥引用"; else pass "二维码层零密钥引用"; fi
has "超容量如实抛出不截断" "$QR" "class QrTooLongException"
has "坏前缀宽容拒收" "$CFG" "if (!raw.startsWith(PREFIX)) return null"
has "坏编码宽容拒收" "$CFG" "IllegalArgumentException"
SCREEN="app/src/main/java/com/example/agentkit/ui/ModelConfigScreen.kt"
has "设置页有二维码入口" "$SCREEN" "配置二维码"
has "导出弹窗渲染矩阵" "$SCREEN" "QrImage(matrix = matrix"
has "导入只回填非敏感字段" "$SCREEN" "baseUrl = parsed.baseUrl"
has "导入失败给出提示" "$SCREEN" "这不是本 App 的配置码"
if grep -q 'parsed.apiKey\|apiKey = parsed' "$SCREEN"; then fail "导入不携带 Key"; else pass "导入不携带 Key"; fi

echo "== 行为：编码器 × 解码库真解对拍 =="

TMP=$(mktemp -d)
cat > "$TMP/check.kt" <<'EOF'
import com.example.agentkit.core.qr.ConfigQr
import com.example.agentkit.core.qr.QrCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap

var pass = 0; var fail = 0
fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }

/** 自写矩阵 → 解码库真解。解不出返回 null。 */
fun decode(m: QrCode.Matrix): String? = try {
    val w = m.size; val h = m.size
    val px = IntArray(w * h) { i -> if (m[i / w, i % w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    val source = RGBLuminanceSource(w, h, px)
    val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
    hints[DecodeHintType.PURE_BARCODE] = true
    hints[DecodeHintType.TRY_HARDER] = true
    QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints).text
} catch (e: Exception) { null }

fun roundTrip(text: String, label: String, level: Int = 1) {
    val decoded = try { decode(QrCode.encode(text, level)) } catch (e: Exception) { "THROW:${e.message}" }
    ok(decoded == text, "对拍：$label（v=${try { QrCode.encode(text, level).size.let { (it - 17) / 4 } } catch (e: Exception) { "?" }}, ${levelName(level)}）")
}
fun levelName(l: Int) = if (l == 0) "L" else "M"

fun main() {
// 短文本（v1 最小码）
roundTrip("HELLO", "纯 ASCII 短文本")
// 典型配置负载（中文配置名）
val cfg = ConfigQr.export("openai", "https://api.deepseek.com/v1", "deepseek-chat", "深海")
ok(cfg.startsWith(ConfigQr.PREFIX), "负载：导出带标准前缀")
roundTrip(cfg, "典型配置负载（中文）")
roundTrip(cfg, "同负载 L 档", 0)
// 版本进阶：逼近 v9 边界的大负载
val mid = "A".repeat(180)
roundTrip(ConfigQr.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
    .encodeToString(mid.toByteArray()), "大负载 180 字节（v8-v9 段）")
// 10 版本边界：16 位计数头（v10+ 才是 16 位），大负载走 L 档
val big = "agentkit://config?v=1&d=" + java.util.Base64.getUrlEncoder().withoutPadding()
    .encodeToString("u=https://api.example.com/v1\nm=model-x\nn=n\n".toByteArray() + "B".repeat(230).toByteArray())
roundTrip(big, "大负载 250+ 字节（v10 边界，16 位计数头）", 0)
// M 档大负载（260 字节内，贴容量上限）
val mid2 = "agentkit://config?v=1&d=" + java.util.Base64.getUrlEncoder().withoutPadding()
    .encodeToString("u=https://api.example.com/v1\nm=model-x\nn=n\n".toByteArray() + "C".repeat(180).toByteArray())
roundTrip(mid2, "大负载 210 字节（M 档贴上限）", 1)
// 超容量如实抛
var threw = false
try { QrCode.encode("x".repeat(2000)) } catch (e: QrCode.QrTooLongException) { threw = true }
ok(threw, "超容量抛 QrTooLong 不截断")
// 负载层：往返
val p = ConfigQr.import(ConfigQr.export("claude", "https://api.anthropic.com", "claude-sonnet-4", "工作号"))
ok(p != null && p.providerId == "claude" && p.model == "claude-sonnet-4" && p.name == "工作号",
   "负载：导出导入往返无损")
// 负载层：坏输入宽容拒收
ok(ConfigQr.import("随便什么文本") == null, "负载：非本格式拒收")
ok(ConfigQr.import(ConfigQr.PREFIX + "!!!not-base64!!!") == null, "负载：坏编码拒收")
val broken = ConfigQr.export("openai", "https://x", "m", "n")
ok(ConfigQr.import(broken.substring(0, broken.length / 2)) == null, "负载：重度截断拒收")
ok(ConfigQr.import(ConfigQr.export("", "", "", "")) == null, "负载：空关键字段拒收")
println("RESULT: PASS=$pass FAIL=$fail")
if (fail > 0) kotlin.system.exitProcess(1)
}
EOF

# 2026-10-30 修（CI 抓出）：原实现硬编码沙箱专用路径（/tmp/ktcheck、/tmp/zxing-core.jar），
# CI 上没有这些文件 → 解码考官起不来。改为双环境自适应：
#   编译器：优先沙箱 jar 布局，否则用 PATH 上的 kotlinc；
#   考官 jar：现成就用，没有则从 Maven Central 现取；取不到才跳过行为段（静态断言仍有效）。
ZXING="/tmp/zxing-core.jar"
if [ ! -f "$ZXING" ]; then
    ZXING="$TMP/zxing-core-3.5.3.jar"
    curl -sfL --max-time 60 -o "$ZXING" \
        "https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar" || ZXING=""
fi
KCOMPILE="/tmp/ktcheck/kcomp.jar"
if [ -f "$KCOMPILE" ]; then
    # 沙箱：K2JVMCompiler 直调 + 沙箱桩 jar
    RUN_CMD=(java -cp "/tmp/ktcheck/kcomp.jar:/tmp/kstdlib.jar:/tmp/ktcheck/coroutines.jar:/tmp/ktcheck/trove.jar:/tmp/ktcheck/annotations.jar:$ZXING" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler)
    RUN_CP="$TMP:/tmp/kstdlib.jar:$ZXING"
elif command -v kotlinc >/dev/null 2>&1; then
    # CI / 本机：kotlinc 命令 + 其自带 stdlib
    KLIB="$(dirname "$(dirname "$(command -v kotlinc)")")/lib/kotlin-stdlib.jar"
    RUN_CMD=(kotlinc -cp "$ZXING")
    RUN_CP="$TMP:$KLIB:$ZXING"
else
    RUN_CMD=()
fi
if [ -n "${RUN_CMD[*]:-}" ] && [ -n "$ZXING" ]; then
    if "${RUN_CMD[@]}" -d "$TMP" "$QR" "$CFG" "$TMP/check.kt" > "$TMP/compile.log" 2>&1; then
        OUT=$(java -cp "$RUN_CP" CheckKt 2>&1)
        echo "$OUT"
        echo "$OUT" | grep -q "FAIL=0" && pass "对拍与负载断言全过" || fail "对拍有红"
    else
        echo "编译失败："; grep -a 'error' "$TMP/compile.log" | head -8
        fail "二维码层编译"
    fi
else
    echo "  SKIP  无 kotlinc / 无解码考官，跳过行为对拍（CI 由 gradle 真编）"
fi
rm -rf "$TMP"

echo
echo "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
