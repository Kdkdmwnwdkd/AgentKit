#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""留存代码体检：找出「只有定义、没有任何使用」的顶层声明。

用法：
    python3 scripts/dead-code-scan.py          # 结论 + 前 20 条
    python3 scripts/dead-code-scan.py --detail # 全部零引用声明

判定方式：统计每个顶层声明的名字在全部 .kt / .xml / .kts / .json 里出现的**总次数**
（词边界匹配），定义处本身算 1 次。次数 <= 1 即「只有定义」。

已知盲区（所以它只用来找线索，删之前必须人看一眼）：
  - 被注释提到也会算一次 → 会漏判（`ToolExposurePolicy` 当初就是这样躲过去的）；
  - Android 组件（Activity/Service/Receiver）由 Manifest 引用，名字会出现，通常不会误报；
  - 反射、序列化注解、Compose 的隐式调用不在统计范围内；
  - 同名声明分散在多个文件时，只记录第一次出现的位置。

退出码：0 = 没有零引用声明；1 = 有（可用于 CI 提醒，不建议做成硬门禁）。
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_ROOTS = ["app/src/main/java", "app/src/test/java", "app/src/androidTest/java"]
# 除源码外还要扫的地方：资源、Manifest、构建脚本、QA harness
EXTRA_ROOTS = ["app/src/main", "scripts", "app/build.gradle.kts", "build.gradle.kts"]
TEXT_EXTS = (".kt", ".xml", ".json", ".kts", ".pro", ".sh")

DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public |internal |private |abstract |open |sealed |data |enum |annotation |value |inline |const )*"
    r"(class|object|interface|typealias)\s+([A-Z]\w+)",
    re.M,
)
VAL = re.compile(
    r"^(?:public |internal |private |const |override )*(?:val|var)\s+([A-Z]\w+)\s*[:=]", re.M
)


def walk(base, exts):
    if os.path.isfile(base):
        yield base
        return
    for dirpath, _, files in os.walk(base):
        if "/build/" in dirpath or "/.git/" in dirpath or "/llama.cpp/" in dirpath:
            continue
        for name in files:
            if name.endswith(exts):
                yield os.path.join(dirpath, name)


def main():
    detail = "--detail" in sys.argv
    kt_files, texts = [], {}
    for rel in SOURCE_ROOTS:
        base = os.path.join(ROOT, rel)
        if os.path.isdir(base):
            kt_files += list(walk(base, (".kt",)))
    for rel in EXTRA_ROOTS:
        base = os.path.join(ROOT, rel)
        if os.path.exists(base):
            for path in walk(base, TEXT_EXTS):
                try:
                    texts[path] = open(path, encoding="utf-8", errors="ignore").read()
                except OSError:
                    pass

    owner, lines = {}, {}
    for path in kt_files:
        try:
            src = open(path, encoding="utf-8", errors="ignore").read()
        except OSError:
            continue
        lines[path] = src.count("\n") + 1
        for match in DECL.finditer(src):
            owner.setdefault(match.group(2), path)
        for match in VAL.finditer(src):
            owner.setdefault(match.group(1), path)

    hits = defaultdict(int)
    for src in texts.values():
        for word in re.findall(r"\b([A-Za-z_]\w{2,})\b", src):
            hits[word] += 1

    dead = [(name, path, lines[path]) for name, path in owner.items() if hits[name] <= 1]
    dead.sort(key=lambda row: -row[2])

    print(f"扫描 {len(kt_files)} 个 kt 文件 / {len(owner)} 个顶层声明")
    print(f"零引用声明：{len(dead)} 个\n")
    if not dead:
        return 0
    shown = dead if detail else dead[:20]
    for name, path, total in shown:
        print(f"{total:5d} 行文件  {name:38s} {os.path.relpath(path, ROOT)}")
    if not detail and len(dead) > len(shown):
        print(f"\n（还有 {len(dead) - len(shown)} 个，加 --detail 看全部）")
    print("\n注意：被注释提到也算一次，删除前请确认调用点。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
