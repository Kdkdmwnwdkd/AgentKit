#!/usr/bin/env bash
# 全量 harness 运行器（W-39）。
#
# 用法：
#   bash scripts/qa/run-all.sh [apk路径]
#
# 三类结果，语义严格区分：
#   PASS  跑完且断言全过
#   SKIP  环境不具备（例如缺 AGP 编译产物），**什么都没验证**，不算通过
#   XFAIL 已知失效（测试源码与主代码脱节，见 known-broken.txt），照跑但不阻塞
#   FAIL  真跑了但断言失败，或跑完没有通过标记
#
# 退出码：只有 FAIL 才非 0（SKIP / XFAIL 不阻塞 CI/本地检查）。
set -uo pipefail
# 断言脚本按「仓库根」为 CWD 写（要读 app/src/main/... 与 scripts/qa/...），
# 所以这里必须停在仓库根，不能停在本目录——否则 read() 全找不到文件。
cd "$(dirname "$0")/../.."

APK="${1:-}"
# 每个脚本的完整输出落到 qa-logs/，CI 作为 artifact 上传——
# 失败时只看 tail 无法定位（编译器内部错误的关键行在前面）。
LOG_DIR="${QA_LOG_DIR:-qa-logs}"
mkdir -p "$LOG_DIR"
pass=0; skip=0; fail=0; xfail=0; failed=""

# 编译产物陈旧检查：35 个 harness 拿 app/build/tmp/kotlin-classes/debug 当 classpath。
# 源码改了没重编时，它们会报 `unresolved reference: Xxx`——看着像代码错，实际是产物过期。
# 这类误诊已经发生过一次（CapabilityManifest 明明在源码里），排查时间比修问题本身还长。
# 一次拦在源头，并在日志末尾留 ENV STALE 标记，让 gate.sh 把整轮结论判为不可信。
#
# 注意 source 失败不能静默：检查器自己失效时，表现是「一切正常」——这比检查报红难发现得多
# （自测第一次跑就是这么过的：临时仓库里没复制 lib/，检查器压根没定义，却一声不响）。
if ! source scripts/qa/lib/deps.sh 2>/dev/null; then
  echo "WARN  缺少 scripts/qa/lib/deps.sh —— 跳过产物陈旧检查"
  echo "      → 若本轮出现「找不到符号」，先怀疑是 class 产物过期，而不是代码坏了"
fi
STALE_SRC=$(qa_classes_stale "$(pwd)" 2>/dev/null || true)
if [ -n "${STALE_SRC:-}" ]; then
  echo "WARN  编译产物陈旧：$STALE_SRC 比所有 .class 都新"
  echo "      → 依赖 classpath 的 harness 会报假的「找不到符号」，那是产物过期，不是代码错"
  echo "      → 修法：./gradlew :app:compileDebugKotlin（CI 上建议加 --no-daemon）"
fi

# 已知失效清单：这些断言源码已与主代码脱节，编译就过不了。
# 它们照跑（避免假装不存在），但归为 XFAIL，不阻塞；修好后从清单删掉即可。
BROKEN_FILE="scripts/qa/known-broken.txt"
is_known_broken() {
    [ -f "$BROKEN_FILE" ] || return 1
    grep -qvE '^\s*(#|$)' "$BROKEN_FILE" || return 1
    awk '!/^[[:space:]]*(#|$)/ {print $1}' "$BROKEN_FILE" | grep -qx "$(basename "$1")"
}

# 被测 runner 集合。默认仓库内的 run-*.sh；自测（run-runall-verification.sh）时
# 指向临时目录里的假 runner——否则「判定逻辑本身」没有任何断言覆盖：
# run-all 把失败的脚本判成 PASS，后续 gate.sh 读到的就是假日志，全链绿得理直气壮。
RUNNER_GLOB="${QA_RUNNER_GLOB:-scripts/qa/run-*.sh}"

# 「跑了 0 条断言」的确切特征：通过数 0 与失败数 0 同时出现，**且出现在结果行里**。
#
# 为什么不用「输出里有没有 0 失败」判断：那正是正常全绿的说法（91 个日志里有 26 个
# 含 `0 failed`）。第一版按「出现 0 计数就判红」写，离线回放直接误伤 26 个。
# 双零才行——左边界还必须排除数字，否则 `20 通过 / 0 失败` 会被当成 0 通过。
#
# 为什么还要求上下文词：第一版上线后立刻把自己的自测判红了——自测里有个用例名叫
# 「场景 6：0 通过 / 0 失败（空跑）」。检测器把**描述这个现象的文字**当成了现象本身。
# 要求同行含「结果 / 汇总 / TOTAL / SUMMARY」之类的词，这类讨论文字就不会误中；
# 代价是漏掉不带任何汇总前缀的裸计数——宁可漏检，不可误杀（误杀会让整轮红）。
zero_assertions() {
  local ctx='结果|汇总|RESULT|SUMMARY|TOTAL|统计|小计'
  echo "$1" | grep -qE "($ctx).*(^|[^0-9])0 *通过 */ *0 *失败" && return 0
  echo "$1" | grep -qiE "($ctx).*(^|[^0-9])0 *passed.*(^|[^0-9])0 *failed" && return 0
  echo "$1" | grep -qE 'PASS[:=] *0([^0-9]|$).*FAIL[:=] *0([^0-9]|$)' && return 0
  return 1
}

for s in $RUNNER_GLOB; do
  [ -f "$s" ] || continue
  [ "$(basename "$s")" = "run-all.sh" ] && continue
  case "$s" in
    *apk-verification*)
      if [ -z "$APK" ]; then
        echo "SKIP  $(basename "$s")（未传 APK 路径，静态反验未执行）"
        skip=$((skip + 1)); continue
      fi
      out=$(bash "$s" "$APK" 2>&1); rc=$?
      ;;
    *)
      out=$(bash "$s" 2>&1); rc=$?
      ;;
  esac

  echo "$out" > "$LOG_DIR/$(basename "$s" .sh).log"

  if [ $rc -eq 3 ] && echo "$out" | grep -qi "skip"; then
    skip=$((skip + 1))
    echo "SKIP  $(basename "$s")"
  elif [ $rc -eq 3 ]; then
    # exit 3 是约定的「环境不具备」码，但**脚本自己没说明理由**时不能当跳过：
    # 真实错误恰好返回 3（某些编译器/工具会）会被这里静默吞掉，表现是「少了一个 PASS」，
    # 而门禁 5 只卡总数——新增一个失败 runner 时总数不变，谁也看不出来。
    if is_known_broken "$s"; then
      xfail=$((xfail + 1))
      echo "XFAIL $s（已知失效，见 known-broken.txt）"
    else
      fail=$((fail + 1)); failed="$failed $s"
      echo "FAIL  $s（exit=3 但输出里没有任何 SKIP 说明，无法确认是环境跳过）"
      echo "$out" | tail -8
    fi
  elif [ $rc -ne 0 ]; then
    if is_known_broken "$s"; then
      xfail=$((xfail + 1))
      echo "XFAIL $s（已知失效，见 known-broken.txt）"
    else
      fail=$((fail + 1)); failed="$failed $s"
      echo "FAIL  $s（exit=$rc） 完整日志：$LOG_DIR/$(basename "$s" .sh).log"
      echo "$out" | tail -12
    fi
  # 先判「空跑」再判通过标记：`0 通过 / 0 失败` 同时含 `0 失败`，会被下一行的
  # 通过标记规则收下——结果是「一条断言都没跑」计成 PASS。空集合满足全称命题，
  # 这是本项目反复踩的同一个坑，必须在计数层堵住。
  elif zero_assertions "$out"; then
    if is_known_broken "$s"; then
      xfail=$((xfail + 1))
      echo "XFAIL $s（已知失效，见 known-broken.txt）"
    else
      fail=$((fail + 1)); failed="$failed $s"
      echo "FAIL  $s（输出显示 0 条断言：跑了但什么都没验证） 完整日志：$LOG_DIR/$(basename "$s" .sh).log"
      echo "$out" | tail -8
    fi
  # W-47：补 "FAIL: 0" —— retry/t01 这类 runner 的汇总是 "PASS: 9  FAIL: 0  TOTAL: 9"，
  # 旧模式只认 "FAIL=0"，于是明明全绿的脚本被判「无通过标记」。
  elif echo "$out" | grep -qE "0 failed|FAIL=0|FAIL: 0|0 失败|_OK [0-9]|全部通过"; then
    if is_known_broken "$s"; then
      echo "XPASS $s（已修复！请从 known-broken.txt 移除）"
    fi
    pass=$((pass + 1))
    echo "PASS  $s"
  else
    if is_known_broken "$s"; then
      xfail=$((xfail + 1))
      echo "XFAIL $s（已知失效，见 known-broken.txt）"
    else
      fail=$((fail + 1)); failed="$failed $s"
      echo "FAIL  $s（无通过标记） 完整日志：$LOG_DIR/$(basename "$s" .sh).log"
      echo "$out" | tail -12
    fi
  fi
done

echo
echo "==== 汇总：PASS=$pass SKIP=$skip XFAIL=$xfail FAIL=$fail"
# 留给 gate.sh 读的机器标记：产物陈旧时本轮所有依赖 classpath 的结论都不可信，
# 哪怕 PASS 数够、FAIL=0 也一样。不放在汇总行里是为了不破坏那行的既有格式解析。
if [ -n "${STALE_SRC:-}" ]; then
  echo "ENV STALE classes 比源码旧（$STALE_SRC）"
fi
if [ $fail -ne 0 ]; then
  echo "失败清单:$failed"
  exit 1
fi
