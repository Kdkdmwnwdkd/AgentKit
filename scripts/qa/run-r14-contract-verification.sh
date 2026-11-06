#!/usr/bin/env bash
# R-14「屏幕只拿窄接口」架构守卫。
#
# [修的是什么] 修前 18 个屏幕的函数签名都是 `vm: ChatViewModel`。那个类有 173 个
# public 成员：收藏页在语法上能调 send()、能删会话，编译器不拦；任何屏幕都拖得动
# 整个 ViewModel 的初始化链，想给它写个只喂假数据的预览等于先把 App 装起来。
# 现在按领域切成 30 个窄接口，屏幕只声明自己用到的那一个，AppScaffold 是唯一
# 保留整个 ViewModel 的组装根。
#
# [为什么判据写在 Python 里] 判据是「接口闭包 vs 屏幕实际使用」「实现类 vs 契约」
# 的集合对账，要按接口、按文件、按继承链算。shell 里写 `expect '字面量'` 既算不出
# 集合，又会被 assertion-census 判成文本断言（P0 冻结的东西）。lib/r14_guard.py
# 做结构化比较，本脚本只汇总行数、给 run-all 一个能识别的结果行。
#
# [断言清单] A 组装根 / B 契约本体 / C 实现类对账 / D 依赖方向，共 12 条。
# 每条都有对应变异用例（scripts/qa/r14-verify/mutate.py）：改坏必红、恢复必绿。
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)

echo "== R-14 屏幕窄接口契约守卫 =="
echo ""

OUT=$(python3 "$ROOT/scripts/qa/lib/r14_guard.py" 2>&1)
rc=$?
echo "$OUT"

p=$(printf '%s\n' "$OUT" | grep -cE '^[A-D][0-9]+ PASS' || true)
f=$(printf '%s\n' "$OUT" | grep -cE '^[A-D][0-9]+ FAIL' || true)
p=${p:-0}; f=${f:-0}

# 守卫自己崩了 / 一条判据都没产出：不能算「0 失败」通过。
# 这一条是 R-19 的教训——进程中途退出时 grep 全不命中，默认「没失败」会让整段静默消失。
if [ "$p" -eq 0 ]; then
  echo "G0 FAIL  守卫没有产出任何 PASS 判据（rc=$rc，解析器可能崩了）"
  f=$((f + 1))
fi

echo ""
echo "$p 通过 / $f 失败"
[ "$f" -eq 0 ] && [ "$rc" -eq 0 ] || exit 1
