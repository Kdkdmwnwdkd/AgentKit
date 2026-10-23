#!/usr/bin/env bash
# gate.sh 的自测：门禁本身也要被断言覆盖，否则门禁的 bug 没人会发现。
#
# 做法：在临时目录造出 4 种「仓库状态」副本，分别跑 gate.sh，断言它的退出码与输出。
# 全部只动临时目录，绝不碰真实仓库。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
GATE="$ROOT/scripts/qa/gate.sh"

pass=0; fail=0
ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1 -> $2"; fail=$((fail+1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 造一个最小「仓库」：只需要 scripts/qa/gate.sh 与 scripts/qa/known-broken.txt
mk_repo() {
  local d="$WORK/$1"
  mkdir -p "$d/scripts/qa"
  cp "$GATE" "$d/scripts/qa/gate.sh"
  printf '%s\n' "$2" > "$d/scripts/qa/known-broken.txt"
  echo "$d"
}

# ---------------------------------------------------------------- 场景 1
echo "== 场景 1：清单归零 + 日志全绿 → 应 PASS =="
R=$(mk_repo ok "\
# 注释行，忽略
# run-foo.sh	历史记录
")
cat > "$WORK/ok.log" <<'EOF'
PASS  scripts/qa/run-a.sh
PASS  scripts/qa/run-b.sh
==== 汇总：PASS=48 SKIP=0 XFAIL=0 FAIL=0
EOF
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/ok.log" 2>&1); rc=$?
if [ $rc -eq 0 ]; then ok "退出码 0"
else bad "退出码 0" "实际 $rc：$out"; fi
case "$out" in *"GATE PASS"*) ok "输出 GATE PASS";; *) bad "输出 GATE PASS" "$out";; esac

# ---------------------------------------------------------------- 场景 2
echo "== 场景 2：清单非空 → 应失败并列出条目 =="
R=$(mk_repo broken "\
run-broken-a.sh	编译不过
run-broken-b.sh	签名对不上
")
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/ok.log" 2>&1); rc=$?
if [ $rc -ne 0 ]; then ok "退出码非 0"
else bad "退出码非 0" "实际 0：$out"; fi
case "$out" in *"run-broken-a.sh"*) ok "输出列出条目 a";; *) bad "输出列出条目 a" "$out";; esac
case "$out" in *"run-broken-b.sh"*) ok "输出列出条目 b";; *) bad "输出列出条目 b" "$out";; esac
case "$out" in *"GATE BLOCKED"*) ok "输出 GATE BLOCKED";; *) bad "输出 GATE BLOCKED" "$out";; esac

# ---------------------------------------------------------------- 场景 3
echo "== 场景 3：汇总 FAIL>0 → 应失败 =="
R=$(mk_repo failq "\
# 空清单
")
cat > "$WORK/fail.log" <<'EOF'
PASS  scripts/qa/run-a.sh
FAIL  scripts/qa/run-b.sh（exit=1）
==== 汇总：PASS=47 SKIP=0 XFAIL=0 FAIL=1
EOF
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/fail.log" 2>&1); rc=$?
if [ $rc -ne 0 ]; then ok "退出码非 0"
else bad "退出码非 0" "实际 0：$out"; fi
case "$out" in *"FAIL=1"*) ok "输出提到 FAIL=1";; *) bad "输出提到 FAIL=1" "$out";; esac

# ---------------------------------------------------------------- 场景 4
echo "== 场景 4：出现 XPASS → 应失败（清单该更新了） =="
cat > "$WORK/xpass.log" <<'EOF'
XPASS scripts/qa/run-c.sh（已修复！请从 known-broken.txt 移除）
==== 汇总：PASS=48 SKIP=0 XFAIL=0 FAIL=0
EOF
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/xpass.log" 2>&1); rc=$?
if [ $rc -ne 0 ]; then ok "退出码非 0"
else bad "退出码非 0" "实际 0：$out"; fi
case "$out" in *"XPASS"*) ok "输出提到 XPASS";; *) bad "输出提到 XPASS" "$out";; esac

# ---------------------------------------------------------------- 场景 5
echo "== 场景 5：逃生阀 QA_GATE=warn → 只告警不失败 =="
R=$(mk_repo warn "\
run-still-broken.sh	还没修
")
out=$(cd "$R" && QA_GATE=warn bash scripts/qa/gate.sh "$WORK/ok.log" 2>&1); rc=$?
if [ $rc -eq 0 ]; then ok "退出码 0"
else bad "退出码 0" "实际 $rc：$out"; fi
case "$out" in *"仅告警"*) ok "输出提示仅告警";; *) bad "输出提示仅告警" "$out";; esac

# ---------------------------------------------------------------- 场景 6
echo "== 场景 6：清单文件缺失 → 应失败（不能默默放行） =="
R="$WORK/missing"
mkdir -p "$R/scripts/qa"
cp "$GATE" "$R/scripts/qa/gate.sh"
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/ok.log" 2>&1); rc=$?
if [ $rc -ne 0 ]; then ok "退出码非 0"
else bad "退出码非 0" "实际 0：$out"; fi

# ---------------------------------------------------------------- 场景 7
echo "== 场景 7：真实仓库的清单必须归零 =="
if [ -f "$ROOT/scripts/qa/known-broken.txt" ]; then
  n=$(awk '!/^[[:space:]]*(#|$)/ {print $1}' "$ROOT/scripts/qa/known-broken.txt" | grep -c . || true)
  if [ "${n:-0}" -eq 0 ]; then ok "真实清单为 0 条"
  else bad "真实清单为 0 条" "实际 $n 条"; fi
else
  bad "真实清单文件存在" "scripts/qa/known-broken.txt 不存在"
fi

# ---------------------------------------------------------------- 场景 8
# 门禁 4：runner 数 > 日志里实际跑过的行数 → 说明有 runner 没被 run-all 收录。
# 构造方法：日志只写 1 行 PASS，但仓库里有几十个 run-*.sh，必然对不上。
echo "== 场景 8：runner 数多于日志行数 → 应失败（有 runner 没跑到） =="
LOG8="$WORK/qa8.log"
{
  echo "PASS  scripts/qa/run-fake-one.sh"
  echo "==== 汇总：PASS=1 SKIP=0 XFAIL=0 FAIL=0"
} > "$LOG8"
set +e
OUT8=$(bash "$ROOT/scripts/qa/gate.sh" "$LOG8" 2>&1)
RC8=$?
set -e
if [ "$RC8" -ne 0 ]; then ok "退出码非 0"; else bad "退出码非 0" "实际 $RC8"; fi
if printf '%s' "$OUT8" | grep -q "只跑了"; then ok "输出说明有 runner 没跑到"
else bad "输出说明有 runner 没跑到" "$(printf '%s' "$OUT8" | tail -3)"; fi

# ---------------------------------------------------------------- 场景 9
# 门禁 4 的正向：真实仓库 + 真实日志必须通过（防止门禁 4 自己误伤正常构建）
echo "== 场景 9：真实日志应通过门禁 4 =="
LOG9="$WORK/qa9.log"
{
  for s in $(ls "$ROOT"/scripts/qa/run-*.sh | grep -v 'run-all\.sh$'); do
    echo "PASS  ${s#"$ROOT"/}"
  done
  echo "==== 汇总：PASS=$(ls "$ROOT"/scripts/qa/run-*.sh | grep -vc 'run-all\.sh$') SKIP=0 XFAIL=0 FAIL=0"
} > "$LOG9"
set +e
OUT9=$(bash "$ROOT/scripts/qa/gate.sh" "$LOG9" 2>&1)
RC9=$?
set -e
if [ "$RC9" -eq 0 ]; then ok "全跑时门禁放行"
else bad "全跑时门禁放行" "$(printf '%s' "$OUT9" | grep 'GATE FAIL' | head -2)"; fi

echo
echo "=== 汇总: $pass passed, $fail failed ==="
[ "$fail" -eq 0 ] || exit 1
