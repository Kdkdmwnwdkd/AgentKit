#!/usr/bin/env python3
"""R-14 契约解析器：把 `ui/contract/*.kt` 变成可断言的结构。

输出 JSON：
  {
    "interfaces": {"BookmarksContract": {"members": [...], "parents": [...], "stable": true}, ...},
    "closure":    {"BookmarksContract": [成员闭包], ...}
  }

`stable` 是接口声明紧邻上方是否标了 `@Stable`——守卫要据此断言「所有窄接口都是稳定契约」，
而这不能靠 grep 数 `@Stable` 的总数（那样删一个、在别处补一个就能蒙混）。

为什么要它而不是 grep：成员数、继承闭包、死成员判定都要「按接口」算。
grep 数 `fun ` 会把 KDoc 里的示例、把参数里的 lambda 算进去；而组合接口
（`interface X : A, B`）没有花括号体，用文本匹配根本找不到它的成员。
"""
import json
import os
import re
import sys


def strip_comments(src: str) -> str:
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif src.startswith('"', i):
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return ''.join(out)


DECL = re.compile(r'^\s{4}(?:override\s+)?(suspend\s+)?(?:fun|val|var)\s+([A-Za-z_]\w*)')


def parse_file(path: str):
    raw = open(path, encoding='utf-8').read()
    src = strip_comments(raw)
    lines = src.split('\n')
    res = {}
    i = 0
    while i < len(lines):
        m = re.match(r'\s*interface\s+([A-Za-z_]\w*)\s*(.*)$', lines[i])
        if not m:
            i += 1
            continue
        name, header = m.group(1), m.group(2)
        # 接口声明紧邻上方的注解行。KDoc 已被 strip_comments 删掉，所以从
        # interface 行往上只会遇到注解或空行，不会把文档里的 @param 算进来。
        anns, k = [], i - 1
        while k >= 0:
            t = lines[k].strip()
            if t.startswith('@'):
                anns.append(t)
                k -= 1
            elif t == '' and anns:
                k -= 1
            else:
                break
        stable = any(a.startswith('@Stable') for a in anns)
        # 继承列表可能跨行（组合接口通常这么写）：一路吃到 `{` 或空行/下一条声明
        j = i
        while '{' not in header and j + 1 < len(lines):
            nxt = lines[j + 1].strip()
            if nxt == '' or nxt.startswith('interface') or nxt.startswith('@'):
                break
            j += 1
            header += ' ' + nxt
        parents = []
        if ':' in header:
            seg = header.split(':', 1)[1].split('{', 1)[0]
            parents = [p.strip() for p in seg.split(',') if p.strip()]
        members = []
        if '{' in header:
            depth, k = 1, j + 1
            body = []
            while k < len(lines) and depth > 0:
                line = lines[k]
                if depth == 1 and re.match(r'^\s*\}\s*$', line):
                    break
                depth += line.count('{') - line.count('}')
                body.append(line)
                k += 1
            for line in body:
                dm = DECL.match(line)
                if dm:
                    members.append(dm.group(2))
            i = k
        else:
            i = j
        res[name] = {'members': members, 'parents': parents, 'stable': stable}
        i += 1
    return res


def main(contract_dir: str) -> int:
    interfaces = {}
    for fn in sorted(os.listdir(contract_dir)):
        if fn.endswith('.kt'):
            interfaces.update(parse_file(os.path.join(contract_dir, fn)))

    closure = {}

    def close(name, seen=None):
        seen = seen or set()
        if name in seen:
            return set()
        seen.add(name)
        info = interfaces.get(name)
        if not info:
            return set()
        acc = set(info['members'])
        for p in info['parents']:
            acc |= close(p, seen)
        return acc

    for name in interfaces:
        closure[name] = sorted(close(name))

    print(json.dumps({'interfaces': interfaces, 'closure': closure},
                     ensure_ascii=False, indent=1, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
