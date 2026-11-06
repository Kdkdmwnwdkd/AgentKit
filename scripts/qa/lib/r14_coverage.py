#!/usr/bin/env python3
"""R-14 使用覆盖：每个契约成员是否真被使用、每个屏幕是否只拿到它用的。

两个方向都要看：
  - `closure - used`（过度暴露）：接口给了，没有任何屏幕用 → 接口在长胖。
  - `used - closure`（越界使用）：屏幕用了接口没给的 → 编译应该已经拦下；
    静态再报一次是为了在「编译不过」时能直接指出是哪个文件哪个成员。

参数名不写死 `vm`：ChatScreen 的权限参数叫 `permission`，
按 `名字: 契约类型` 识别，避免只认 `vm:` 的盲区。
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, os.path.join(ROOT, 'scripts/qa/lib'))
UI = os.path.join(ROOT, 'app/src/main/java/com/example/agentkit/ui')
CONTRACTS = os.path.join(UI, 'contract')


def load():
    out = subprocess.check_output([sys.executable,
                                   os.path.join(ROOT, 'scripts/qa/lib/r14_contracts.py'),
                                   CONTRACTS])
    d = json.loads(out)
    return d['interfaces'], d['closure']


# 复用 r14_contracts 的字符扫描版：正则版 `/*...*/` 在遇到 KDoc 里出现的
# `/*` 示例或含斜杠的字符串时会跨段吞代码——实测把 ChatScreen 的
# `vm.send()` 整段吃掉，导致整批成员被误判成「没人用」。
from r14_contracts import strip_comments  # noqa: E402


def main():
    interfaces, closure = load()
    names = set(interfaces)

    # 文件 → [(参数名, 契约类型)]；同文件里可能有两个契约参数（ChatScreen 的 vm + permission）
    params = {}
    for fn in sorted(os.listdir(UI)):
        if not fn.endswith('.kt'):
            continue
        src = strip_comments(open(os.path.join(UI, fn), encoding='utf-8').read())
        found = []
        for m in re.finditer(r'(\w+)\s*:\s*(' + '|'.join(sorted(names)) + r')\b', src):
            found.append((m.group(1), m.group(2)))
        if found:
            params[fn] = sorted(set(found))

    def ancestors(name, acc=None):
        acc = acc if acc is not None else set()
        if name in acc:
            return acc
        acc.add(name)
        for p in interfaces.get(name, {}).get('parents', []):
            ancestors(p, acc)
        return acc

    over, under, per_iface = {}, {}, {}
    for fn, ps in params.items():
        src = strip_comments(open(os.path.join(UI, fn), encoding='utf-8').read())
        for pname, itype in ps:
            # `vm::send` 与 `vm.send()` 一样是使用：方法引用在 Compose 回调里很常见，
            # 只认点号会把整批「通过 :: 传下去」的成员误判成死成员。
            used = set(re.findall(r'\b' + re.escape(pname) + r'(?:\.|::)([A-Za-z_]\w*)', src))
            allowed = set(closure[itype])
            under[(fn, itype)] = sorted(used - allowed)
            # 屏幕声明的是组合接口时，成员使用要沿继承链回填给每个子接口——
            # 否则 ChatStream 这类「只被组合接口引用」的接口会被判成整条死光。
            for sub in ancestors(itype):
                # 契约里写了未定义的 parent 时，ancestors 会把它收进来（图本身允许
                # 出现悬空边，交给 guard 的 B4 报可读诊断），但 closure 里没有这个 key。
                # 早先这里直接 closure[sub] 会 KeyError，把整条守卫链弄崩——
                # 表现是「一条判据都没输出」，比「B4 报红」难查得多。
                per_iface.setdefault(sub, set()).update(used & set(closure.get(sub, ())))

    # 过度暴露按接口聚合后算：同一接口被多个屏幕共用时，只要有一个用了就不算死。
    dead = {k: sorted(set(closure[k]) - per_iface.get(k, set())) for k in names
            if closure[k] and set(closure[k]) - per_iface.get(k, set())}
    over = dead

    print(json.dumps({
        'params': {k: v for k, v in params.items()},
        'over': {k: sorted(v) for k, v in over.items() if v},
        'under': {f'{k[0]}::{k[1]}': v for k, v in under.items() if v},
        'dead': dead,
    }, ensure_ascii=False, indent=1, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
