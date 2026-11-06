"""R-12 判据的变异测试：把修好的语义逐个改回去，看 harness 是否靶向变红。

判据本身也可能是错的——只有「改坏它必红，且红在对的那条」才算真判据。
"""
import os
import shutil
import subprocess
import sys

SRC = "app/src/main/java/com/example/agentkit"
ID = f"{SRC}/core/terminal/backend/TerminalIdentity.kt"
FAC = f"{SRC}/core/terminal/backend/TerminalBackendFactory.kt"
POOL = f"{SRC}/core/terminal/TerminalSessionPool.kt"
ADB = f"{SRC}/automation/adb/AdbTerminalBackend.kt"
SHELL = f"{SRC}/tools/ShellTool.kt"

MUTS = [
    (
        "M1 uid 2000 不再满足 user 下限（satisfies 退回「只能同档」）",
        ID,
        "Shizuku -> required == User || required == Shizuku",
        "Shizuku -> required == Shizuku",
        ["A2 ", "D2 "],
    ),
    (
        "M2 su 不再满足本地所有档",
        ID,
        "Su -> required != Ssh",
        "Su -> required == Su",
        ["A4 ", "A7 "],
    ),
    (
        "M3 工厂退回字符串相等筛选",
        FAC,
        "it.identity.satisfies(identity)",
        "it.identity == identity",
        ["E1 ", "B1 "],
    ),
    (
        "M4 工厂加回「非 User 一律返空」特例",
        FAC,
        "        val order = if (preferProot) {",
        "        if (identity != TerminalIdentity.User) return emptyList()\n        val order = if (preferProot) {",
        ["E2 ", "B2 "],
    ),
    (
        "M5 会话池把请求值当实际身份固化",
        POOL,
        "val entry = Entry(handle, candidate.identity, backendKey, effectiveAlias, candidate)",
        "val entry = Entry(handle, identity, backendKey, effectiveAlias, candidate)",
        ["D1 ", "E3 "],
    ),
    (
        "M6 run() 退回「身份必须相等」",
        POOL,
        "if (identity != null && !entry.identity.satisfies(identity)) {",
        "if (identity != null && identity != entry.identity) {",
        ["D5 ", "D6 "],
    ),
    (
        "M7 无线调试后端改回自称 User",
        ADB,
        "    override val identity: TerminalIdentity = TerminalIdentity.Shizuku\n\n    override val backendKey: String = TerminalBackend.KEY_ADB\n\n    private val channel",
        "    override val identity: TerminalIdentity = TerminalIdentity.User\n\n    override val backendKey: String = TerminalBackend.KEY_ADB\n\n    private val channel",
        ["C1 "],
    ),
    (
        "M8 describe() 退回枚举名小写",
        ID,
        "Shizuku -> \"uid $uid\"",
        "Shizuku -> wire",
        ["A8 "],
    ),
    (
        "M9 工厂不再追加顺序表之外的后端（su 永远选不到）",
        FAC,
        "        val ordered = order + extra",
        "        val ordered = order",
        ["B8 "],
    ),
    (
        "M10 shell 回执退回直接吐 wire",
        SHELL,
        "it.identity.describe()",
        "it.identity.wire",
        ["E5 "],
    ),
]


def run():
    r = subprocess.run(
        ["bash", "scripts/qa/run-r12-identity-verification.sh"],
        capture_output=True,
        text=True,
    )
    return r.stdout + r.stderr, r.returncode


def main():
    ok = True
    for name, path, old, new, wants in MUTS:
        bak = path + ".mutbak"
        shutil.copy2(path, bak)
        s = open(path, encoding="utf-8").read()
        if old not in s:
            shutil.copy2(bak, path)
            os.remove(bak)
            print(f"{name}: !!! 锚点没找到（变异未生效）")
            ok = False
            continue
        open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))
        out, rc = run()
        shutil.copy2(bak, path)
        os.remove(bak)
        hit = [w for w in wants if w in out]
        targeted = bool(hit) and rc != 0
        print(f"{name}: {'靶向精确' if targeted else '!!! 未命中'}  (对上是 {hit}, rc={rc})")
        if not targeted:
            ok = False
            print("\n".join([l for l in out.splitlines() if "FAIL" in l][:8]))
    print("全部变异均被捕获" if ok else "有变异漏网")
    return 0 if ok else 1


sys.exit(main())
