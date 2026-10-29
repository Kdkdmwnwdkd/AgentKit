#!/usr/bin/env bash
# SSH 远程 shell 后端验证：显式路由、仅密钥登录、纯函数层有没有接对。
#
# 起因：identity=ssh 此前只是枚举占位（「首期无后端」）。SSH 的全部风险在两条纪律里——
# 远端机器**绝不进**本机/容器的自动降级链（本机 shell 起不来时悄悄跑到别的机器上，
# pm/settings 的输出会骗过模型）；档案里**没有密码字段**（BatchMode，密码交互会卡死
# 无 PTY 会话）。所以静态断言锁「路由显式 + 降级链不含 ssh」，行为断言真编真跑纯函数层。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

PROFILES="app/src/main/java/com/example/agentkit/core/terminal/backend/SshProfiles.kt"
BACKEND="app/src/main/java/com/example/agentkit/core/terminal/backend/SshBackend.kt"
IFACE="app/src/main/java/com/example/agentkit/core/terminal/backend/TerminalBackend.kt"
FACTORY="app/src/main/java/com/example/agentkit/core/terminal/backend/TerminalBackendFactory.kt"
POOL="app/src/main/java/com/example/agentkit/core/terminal/TerminalSessionPool.kt"
SHELLTOOL="app/src/main/java/com/example/agentkit/tools/ShellTool.kt"
STORE="app/src/main/java/com/example/agentkit/core/ConfigStore.kt"
VM="app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt"
SCREEN="app/src/main/java/com/example/agentkit/ui/SshChannelSection.kt"
PERM="app/src/main/java/com/example/agentkit/ui/PermissionsScreen.kt"

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

echo "== 静态：结构与纪律 =="

if [ -f "$PROFILES" ] && [ -f "$BACKEND" ]; then pass "SSH 纯函数层与后端存在"; else fail "SSH 源文件缺失"; fi

hasnt "纯函数层不依赖 Android" "$PROFILES" "import android."
hasnt "后端层不依赖 Android" "$BACKEND" "import android."
has "仅密钥登录（BatchMode）" "$PROFILES" "BatchMode=yes"
has "首次指纹自动接受、变更仍拒绝" "$PROFILES" "StrictHostKeyChecking=accept-new"
has "档案里没有密码字段" "$PROFILES" "keyPath: String"

echo "== 静态：显式路由（降级链不含 ssh） =="

has "来源接口有按后端键取实例" "$IFACE" "fun obtainByKey(backendKey: String): TerminalBackend?"
has "池 open 支持显式后端键" "$POOL" "backendKey: String? = null"
has "显式键不可用给出 SSH 专属指引" "$POOL" "仅支持密钥登录"
has "工厂按 provider 键构造" "$FACTORY" "providers.firstOrNull { it.backendKey == backendKey }"
has "shell 工具 ssh 走显式路由" "$SHELLTOOL" "declaredIdentity == TerminalIdentity.Ssh"
has "shell 工具路由用 KEY_SSH" "$SHELLTOOL" "backendKey = TerminalBackend.KEY_SSH"
has "降级链候选顺序不含 ssh" "$FACTORY" "listOf(
                TerminalBackend.KEY_PROOT,
                TerminalBackend.KEY_SHIZUKU,
                TerminalBackend.KEY_ADB,
                TerminalBackend.KEY_USER
            )"
has "SSH 独立池别名" "$FACTORY" 'TerminalBackend.KEY_SSH -> ALIAS_SSH'

echo "== 静态：配置与界面接线 =="

has "配置层有 SSH 四项" "$STORE" "fun setSshProfile(host: String, port: String, user: String, keyPath: String)"
has "视图模型有保存入口" "$VM" "fun saveSshProfile"
has "保存后清可用性缓存" "$VM" "terminalBackendFactory.clearCache()"
has "装配处注入 SSH 提供者" "$VM" "SshBackendProvider("
has "权限中心有 SSH 分区" "$PERM" "SshChannelSection(vm)"
has "分区组件存在" "$SCREEN" "internal fun SshChannelSection"

echo "== 行为：纯函数层真编真跑（有编译器才跑，CI 里由 gradle 真编） =="

if [ -n "${KOTLINC_JAR:-}" ] && [ -f "${KOTLINC_JAR:-}" ]; then
    TMP="$(mktemp -d)"
    SRC="app/src/main/java/com/example/agentkit/core"
    # 行为断言专用最小桩：AgentLogger 真身拖整条配置依赖树，这里只要三个打点函数。
    cat > "$TMP/stubs.kt" <<'EOF'
package com.example.agentkit.core

object AgentLogger {
    fun i(tag: String, msg: String) {}
    fun w(tag: String, msg: String) {}
    fun e(tag: String, msg: String) {}
}
EOF
    cat > "$TMP/check.kt" <<'EOF'
import com.example.agentkit.core.terminal.backend.SshBinaryLocator
import com.example.agentkit.core.terminal.backend.SshBackendProvider
import com.example.agentkit.core.terminal.backend.SshProfile
import com.example.agentkit.core.terminal.backend.SshProfileResult
import com.example.agentkit.core.terminal.backend.TerminalBackend
import com.example.agentkit.core.terminal.backend.TerminalBackendFactory
import com.example.agentkit.core.terminal.backend.TerminalIdentity
import com.example.agentkit.core.terminal.backend.buildSshArgs
import com.example.agentkit.core.terminal.backend.normalizeSshProfile
import java.io.File
var pass = 0; var fail = 0
fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }
fun main() {
// 档案校验
val good = normalizeSshProfile(" 192.168.1.10 ", " 2222 ", "root", " /tmp/k ")
ok(good is SshProfileResult.Ok && good.profile.host == "192.168.1.10" && good.profile.port == 2222, "校验：trim 后合法")
ok(normalizeSshProfile("", "22", "root", "").let { it as? SshProfileResult.Invalid }?.reason?.contains("主机") == true, "校验：空主机拒绝")
ok(normalizeSshProfile("a b", "22", "root", "") is SshProfileResult.Invalid, "校验：主机含空格拒绝")
ok(normalizeSshProfile("h", "0", "root", "") is SshProfileResult.Invalid, "校验：端口 0 拒绝")
ok(normalizeSshProfile("h", "abc", "root", "") is SshProfileResult.Invalid, "校验：端口非数字拒绝")
val def = normalizeSshProfile("h", "", "root", "") as SshProfileResult.Ok
ok(def.profile.port == 22, "校验：空端口按 22")
// argv 拼装
val args = buildSshArgs(SshProfile("h", 2200, "u", "/tmp/k"), "/bin/ssh")
ok(args[0] == "/bin/ssh", "拼装：ssh 路径在首位")
ok(args.containsAll(listOf("-o", "BatchMode=yes")), "拼装：仅密钥登录")
ok(args.containsAll(listOf("-i", "/tmp/k")), "拼装：私钥路径")
val noKey = buildSshArgs(SshProfile("h", 22, "u", ""), "ssh")
ok(!noKey.contains("-i"), "拼装：无密钥时不带 -i")
ok(noKey.contains("-p") && noKey.contains("22"), "拼装：端口进 -p")
ok(noKey.last() == "u@h", "拼装：目标 user@host")
// 别名分池
ok(TerminalBackendFactory.defaultAliasFor(TerminalBackend.KEY_SSH) == "ssh", "别名：SSH 独立占池")
ok(TerminalBackendFactory.defaultAliasFor(TerminalBackend.KEY_PROOT) == "proot", "别名：proot 照旧")
ok(TerminalBackendFactory.defaultAliasFor(TerminalBackend.KEY_USER) == "default", "别名：本机照旧")
// 定位器
val tmp = File.createTempFile("sshbin", "").let { f -> f.delete(); File(f.parentFile, "sshbin").apply { writeText("#!/bin/sh\n"); setExecutable(true) } }
ok(SshBinaryLocator.locate(listOf("/nonexistent/ssh", tmp.absolutePath)) == tmp.absolutePath, "定位：跳过缺失取首个可执行")
ok(SshBinaryLocator.locate(listOf("/nonexistent/ssh")) == null, "定位：全缺失返回 null")
tmp.delete()
// 提供者探测（档案缺失 = 不可用；两条件齐备 = 可用且能构造）
var profile: SshProfile? = null
val provider = SshBackendProvider(sshPathProvider = { "/bin/ssh" }, profileProvider = { profile })
ok(!provider.probe(), "提供者：未配档案不可用")
profile = SshProfile("h", 22, "u", "")
ok(provider.probe(), "提供者：档案就绪即可用")
ok(provider.create()?.backendKey == TerminalBackend.KEY_SSH, "提供者：构造出的后端键为 ssh")
// 工厂显式路由：ssh provider 不进降级链，但 obtainByKey 能取到
val fake = object : com.example.agentkit.core.terminal.backend.TerminalBackendProvider {
    override val identity = TerminalIdentity.User
    override val backendKey = TerminalBackend.KEY_SSH
    override fun probe() = true
    override fun create() = provider.create()
}
val factory = TerminalBackendFactory(listOf(fake))
ok(factory.obtainByKey(TerminalBackend.KEY_SSH)?.backendKey == TerminalBackend.KEY_SSH, "工厂：显式键可取到 SSH")
ok(factory.obtainByKey("no-such-key") == null, "工厂：未知键返回 null")
ok(factory.providersFor(TerminalIdentity.User, true).none { it.backendKey == TerminalBackend.KEY_SSH }, "工厂：SSH 不在降级链")
println("RESULT: PASS=$pass FAIL=$fail")
if (fail > 0) kotlin.system.exitProcess(1)
}
EOF
    STDLIB="$(ls "$KOTLINC_STDLIB" 2>/dev/null || true)"
    COMP_CP="$KOTLINC_JAR${KOTLINC_DEPS:+:$KOTLINC_DEPS}"
    SRCS="$TMP/stubs.kt $SRC/terminal/backend/SshProfiles.kt $SRC/terminal/backend/SshBackend.kt $SRC/terminal/backend/TerminalBackend.kt $SRC/terminal/backend/TerminalIdentity.kt $SRC/terminal/backend/TerminalBackendFactory.kt $SRC/terminal/backend/ProcessBackendBase.kt $TMP/check.kt"
    if java -cp "$COMP_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -cp "$STDLIB${KOTLINC_DEPS:+:$KOTLINC_DEPS}" -d "$TMP" $SRCS >/dev/null 2>&1; then
        OUT=$(java -cp "$TMP:$STDLIB${KOTLINC_DEPS:+:$KOTLINC_DEPS}" CheckKt 2>/dev/null)
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
