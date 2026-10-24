#!/usr/bin/env python3
"""极简 uiautomator 辅助：按文本/描述点击、断言、等待。

为什么不用现成框架：这条冒烟要能在「只有 adb 的服务器」和「用户自己的手机」上跑，
不引入 Appium/UiAutomator2 那套（要装 apk、起 server、配端口）。
uiautomator dump + input tap 足够覆盖「点得到、看得见」这类断言。

用法：
  ADB=adb ui.py tap "设置"            # 按 text
  ADB=adb ui.py tap-desc "打开菜单"    # 按 content-desc
  ADB=adb ui.py tap-ratio 0.09 0.16   # 按屏幕比例（无文本的图标按钮）
  ADB=adb ui.py has "已就绪"           # 存在则退出码 0
  ADB=adb ui.py wait "已就绪" 60       # 最多等 60 秒
  ADB=adb ui.py texts                 # 打印当前所有可见文本
"""
import os
import re
import subprocess
import sys
import time

ADB = os.environ.get("ADB", "adb")


def adb(*args, timeout=60):
    try:
        r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
        return r.stdout
    except subprocess.TimeoutExpired:
        return ""


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui-smoke.xml")
    return adb("shell", "cat", "/sdcard/ui-smoke.xml")


def nodes(xml):
    out = []
    for m in re.finditer(r"<node[^>]*>", xml):
        s = m.group(0)
        text = re.search(r'text="([^"]*)"', s)
        desc = re.search(r'content-desc="([^"]*)"', s)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if not bounds:
            continue
        x1, y1, x2, y2 = map(int, bounds.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        out.append({
            "text": text.group(1) if text else "",
            "desc": desc.group(1) if desc else "",
            "center": ((x1 + x2) // 2, (y1 + y2) // 2),
            "bounds": (x1, y1, x2, y2),
        })
    return out


def find(xml, key, by="text"):
    """先精确匹配，再退化为包含匹配。

    「对话」这种短词如果用包含匹配，会先命中「最近对话」，点下去就跳错地方。
    """
    ns = nodes(xml)
    for n in ns:
        if n[by] == key:
            return n
    for n in ns:
        if key in n[by]:
            return n
    return None


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]

    if cmd == "texts":
        for n in nodes(dump()):
            if n["text"]:
                print(n["text"])
        return 0

    if cmd in ("tap", "tap-desc", "has", "wait"):
        key = sys.argv[2]
        by = "desc" if cmd == "tap-desc" else "text"
        if cmd == "wait":
            deadline = time.time() + float(sys.argv[3] if len(sys.argv) > 3 else 30)
            while time.time() < deadline:
                if find(dump(), key, by):
                    return 0
                time.sleep(2)
            return 1
        n = find(dump(), key, by)
        if not n:
            return 1
        if cmd == "has":
            return 0
        tap(*n["center"])
        return 0

    if cmd == "tap-ratio":
        rx, ry = float(sys.argv[2]), float(sys.argv[3])
        size = adb("shell", "wm", "size")
        m = re.search(r"(\d+)x(\d+)", size)
        if not m:
            return 1
        w, h = int(m.group(1)), int(m.group(2))
        tap(int(w * rx), int(h * ry))
        return 0

    if cmd == "scroll-to":
        # 在列表里向下找文本，最多滚 6 次
        key = sys.argv[2]
        size = adb("shell", "wm", "size")
        m = re.search(r"(\d+)x(\d+)", size)
        w, h = int(m.group(1)), int(m.group(2))
        for _ in range(10):
            n = find(dump(), key)
            if n:
                tap(*n["center"])
                return 0
            # 距离要够、时长要够：短促的 swipe 容易被当成"轻扫"后回弹，
            # 表现是滚了 6 次设置页还在原地。
            adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.85)),
                str(w // 2), str(int(h * 0.20)), "400")
            time.sleep(0.6)
        return 1

    print(f"未知命令: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
