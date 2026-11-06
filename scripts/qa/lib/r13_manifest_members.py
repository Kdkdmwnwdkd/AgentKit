#!/usr/bin/env python3
"""A3 判据：CapabilityManifest 里不该再出现任何已登记工具名的字面量。

为什么不能只盯「一行一个名字」的旧排版：有人写回 `listOf("read_file", "write_file")`
一样是第二份名单。变异测试 M5 就是这么漏掉的——第一版判据按行首匹配，整行 listOf
不命中，判据绿着，表已经回来了。

只排除注释行：KDoc 里提到工具名是在解释设计，不是名单。
"""
import os
import re
import sys


def main() -> int:
    src, manifest = sys.argv[1], sys.argv[2]
    names = set()
    for dp, _, fs in os.walk(src):
        for fn in fs:
            if fn.endswith(".kt"):
                t = open(os.path.join(dp, fn), encoding="utf-8").read()
                names |= set(re.findall(r'override val name\s*=\s*"([a-z0-9_]+)"', t))

    bad = []
    for i, line in enumerate(open(manifest, encoding="utf-8").read().splitlines(), 1):
        st = line.strip()
        if st.startswith("//") or st.startswith("*") or st.startswith("/*"):
            continue
        for lit in re.findall(r'"([a-z0-9_]+)"', line):
            if lit in names:
                bad.append("%d: %s" % (i, st[:80]))

    if bad:
        print("  FAIL  A3 CapabilityManifest 仍出现已登记工具名（第二份名单）：")
        for b in bad[:5]:
            print("        " + b)
        return 1
    print("  PASS  A3 CapabilityManifest 不再出现任何工具名字面量（组成员从目录切分）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
