#!/usr/bin/env python3
"""R-14 契约守卫：把「屏幕只拿窄接口」这条架构约束变成可运行断言。

## 为什么不是一个 grep 脚本

R-14 的判据是三组**集合对账**：

  - 接口闭包 vs 屏幕实际使用（多给一个成员、少给一个成员都要红）；
  - 实现类声明的接口祖先闭包 vs 全部契约；
  - 契约成员 vs `override` 声明。

这些都要按接口、按文件、按继承链算。grep 只能数行数，数不出「哪个接口多给了
一个没人用的成员」。而 shell 里写 `expect '字面量'` 又会被 assertion-census 判成
文本断言——那正是 P0 冻结的东西。所以判据逻辑全部放这里（.py），runner 只汇总。

## 输出

每行 `ID PASS/FAIL  说明`；有一条 FAIL 就退出 1。runner 负责把行数汇总成
`N 通过 / M 失败`。每个 ID 都在 scripts/qa/r14-verify/mutate.py 里有对应变异用例。
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.dirname(os.path.abspath(__file__)))))
LIB = os.path.join(ROOT, 'scripts/qa/lib')
UI = os.path.join(ROOT, 'app/src/main/java/com/example/agentkit/ui')
CONTRACTS = os.path.join(UI, 'contract')
VM = os.path.join(UI, 'ChatViewModel.kt')
sys.path.insert(0, LIB)
from r14_contracts import strip_comments  # noqa: E402


def run_json(script, *args):
    out = subprocess.check_output(
        [sys.executable, os.path.join(LIB, script), *args], text=True)
    return json.loads(out)


def ancestors(name, interfaces, acc=None):
    """接口名 → 它和它所有祖先（含自身）。"""
    acc = acc if acc is not None else set()
    if name in acc:
        return acc
    acc.add(name)
    for p in interfaces.get(name, {}).get('parents', []):
        ancestors(p, interfaces, acc)
    return acc


def main():
    C = run_json('r14_contracts.py', CONTRACTS)
    V = run_json('r14_coverage.py')
    interfaces, closure = C['interfaces'], C['closure']
    names = set(interfaces)

    checks = []

    def chk(cid, ok, msg):
        checks.append((cid, bool(ok), msg))

    # ------------------------------------------------------------ [A] 组装根
    # 除 AppScaffold 外，任何屏幕函数都不得以整个 ChatViewModel 为参数。
    # strip_comments 先删注释，免得 KDoc 里「以前是 vm: ChatViewModel」的说明被当成代码。
    out_of_bound = []
    for fn in sorted(os.listdir(UI)):
        if not fn.endswith('.kt') or fn == 'AppScaffold.kt':
            continue
        src = strip_comments(open(os.path.join(UI, fn), encoding='utf-8').read())
        hits = re.findall(r'\b(\w+)\s*:\s*ChatViewModel\b', src)
        if hits:
            out_of_bound.append(f'{fn}:{",".join(sorted(set(hits)))}')
    chk('A1', not out_of_bound,
        '除组装根外没有屏幕拿整个 ChatViewModel' if not out_of_bound
        else '越界参数：' + ' '.join(out_of_bound))

    # A1 的空集不能算通过：还得确认组装根确实持有 ViewModel，否则「把所有参数
    # 都删光」也能让 A1 变绿。
    scaffold = strip_comments(
        open(os.path.join(UI, 'AppScaffold.kt'), encoding='utf-8').read())
    roots = re.findall(r'\b(\w+)\s*:\s*ChatViewModel\b', scaffold)
    chk('A2', len(roots) >= 1,
        f'组装根 AppScaffold 显式持有 ChatViewModel（{len(roots)} 处）' if roots
        else '组装根不再持有 ChatViewModel（A1 的空集不算通过）')

    # ------------------------------------------------------------ [B] 契约本体
    no_stable = sorted(n for n, v in interfaces.items() if not v.get('stable'))
    chk('B1', not no_stable,
        f'{len(interfaces)} 个契约接口全部标注 @Stable' if not no_stable
        else '缺 @Stable：' + ' '.join(no_stable))

    chk('B2', not V['over'],
        '接口闭包无死成员（给出去的都有屏幕用）' if not V['over']
        else '接口过度暴露：' + json.dumps(V['over'], ensure_ascii=False, sort_keys=True))

    chk('B3', not V['under'],
        '屏幕无越界使用（用到的都在契约闭包内）' if not V['under']
        else '屏幕越界使用：' + json.dumps(V['under'], ensure_ascii=False, sort_keys=True))

    # B4 只验继承**图**的合法性，不重算闭包——闭包是同一个解析器算出来的，
    # 拿自己的输出跟自己比恒真，验不出任何东西。
    bad_parent, cycles = [], []
    edges = 0
    for n, v in interfaces.items():
        for p in v['parents']:
            edges += 1
            if p not in interfaces:
                bad_parent.append(f'{n}→{p}')
    for n in interfaces:
        acc = set()
        for p in interfaces[n]['parents']:
            ancestors(p, interfaces, acc)
        if n in acc:
            cycles.append(n)
    b4_bad = bad_parent + ['环:' + c for c in cycles]
    chk('B4', not b4_bad,
        f'继承图合法（{edges} 条边全部指向已定义接口，无环）' if not b4_bad
        else '继承图异常：' + ' '.join(b4_bad))

    used_types = {t for ps in V['params'].values() for _, t in ps}
    unknown = sorted(used_types - names)
    n_screens = len(V['params'])
    chk('B5', not unknown and n_screens >= 17,
        f'{n_screens} 个屏幕参数全部指向已定义契约（{len(used_types)} 种）'
        if not unknown and n_screens >= 17
        else f'未知契约类型 {unknown} / 只登记了 {n_screens} 个屏幕')

    # ------------------------------------------------------------ [C] 实现类对账
    vm = strip_comments(open(VM, encoding='utf-8').read())
    m = re.search(r'class\s+ChatViewModel\s*\([^)]*\)\s*:\s*([^{]+)\{', vm, re.S)
    supertypes = m.group(1) if m else ''
    declared = {n for n in names
                if re.search(r'\b' + re.escape(n) + r'\b', supertypes)}
    covered = set()
    for n in declared:
        covered |= ancestors(n, interfaces)
    missing_ifaces = sorted(names - covered)
    chk('C1', bool(m) and not missing_ifaces,
        f'ChatViewModel 直接实现 {len(declared)} 个契约，祖先闭包覆盖全部 {len(names)} 个'
        if m and not missing_ifaces
        else '有契约没被实现：' + ' '.join(missing_ifaces))

    # 类声明里出现的大写标识符，除 AndroidViewModel 外必须都是已定义契约——
    # 挡住「拼错一个接口名」和「加个幽灵接口凑数」。
    toks = set(re.findall(r'\b([A-Z][A-Za-z_]\w*)\b', supertypes))
    ghost = sorted(toks - names - {'AndroidViewModel'})
    chk('C4', not ghost,
        '类声明里的接口名全部是已定义契约' if not ghost
        else '类声明引用了不存在的接口：' + ' '.join(ghost))

    overrides = set(re.findall(
        r'\boverride\s+(?:suspend\s+)?(?:fun|val|var)\s+([A-Za-z_]\w*)', vm))
    all_members = set()
    for v in closure.values():
        all_members |= set(v)
    missing_ov = sorted(all_members - overrides)
    chk('C2', not missing_ov,
        f'契约闭包 {len(all_members)} 个成员在 ChatViewModel 里都有 override'
        if not missing_ov
        else '契约成员缺 override：' + ' '.join(missing_ov))

    # onCleared 是 AndroidViewModel 的生命周期钩子，本来就不属于任何 UI 契约。
    extra_ov = sorted(overrides - all_members - {'onCleared'})
    chk('C3', not extra_ov,
        'override 成员全部来自契约（仅 onCleared 属父类钩子）'
        if not extra_ov
        else 'override 了契约没声明的成员：' + ' '.join(extra_ov))

    # ------------------------------------------------------------ [D] 依赖方向
    backrefs = []
    for fn in sorted(os.listdir(CONTRACTS)):
        if not fn.endswith('.kt'):
            continue
        src = open(os.path.join(CONTRACTS, fn), encoding='utf-8').read()
        if re.search(r'^\s*import\s+[\w.]+\.ChatViewModel\b', src, re.M):
            backrefs.append(fn)
    chk('D1', not backrefs,
        '契约层不 import ChatViewModel（依赖方向单向）'
        if not backrefs
        else '契约反向依赖实现类：' + ' '.join(backrefs))

    # 输出顺序固定，变异脚本按 ID 断言靶向性
    for cid in ('A1', 'A2', 'B1', 'B2', 'B3', 'B4', 'B5',
                'C1', 'C2', 'C3', 'C4', 'D1'):
        for c, ok, msg in checks:
            if c == cid:
                print(f'{cid} {"PASS" if ok else "FAIL"}  {msg}')
                break
    return 1 if any(not ok for _, ok, _ in checks) else 0


if __name__ == '__main__':
    sys.exit(main())
