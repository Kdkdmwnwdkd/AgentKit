#!/usr/bin/env python3
"""APK dex 反验（W-9 起）——解析 CI 产出的 classes*.dex 字符串池，
断言各批次（W-9/W-12 截图通道 / W-10 上下文预算）的代码字面量确实打进包内。

两种判据模式（脚本如实标注，不用一种模式冒充另一种）：
  pool 池条目精确匹配：needle 是编译期常量本体（方法名 / 内联 const / 模板静态段 /
      类型描述符），必须在 dex 字符串池中存在同名条目。防止标识符子串假阳性
      （例：裸搜 "a11y" 会命中 $a11yServicesEnabled$delegate 这类无关标识符）。
  bytes 原始字节子串：needle 是更长字面量的片段（文案 / 系统提示词分段），
      在 dex 拼接 blob 中出现即证明该文本随所属常量进包。

反证（bytes）：修前旧描述整串（单体常量，编译期已拼接）不再出现——防换错包/旧码残留。

用法: python3 verify-w9-apk.py [apk路径]
"""
import sys, zipfile, re, os

APK = sys.argv[1] if len(sys.argv) > 1 else "/tmp/w9-apk/app-debug.apk"


def read_uleb128(data, off):
    result, shift = 0, 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def dex_string_pool(dex):
    """解析单个 dex 的字符串池，返回 set[str]。非 dex 内容返回空集。"""
    if len(dex) < 0x40 or dex[:4] != b"dex\n":
        return set()
    n = int.from_bytes(dex[0x38:0x3C], "little")
    ids_off = int.from_bytes(dex[0x3C:0x40], "little")
    out = set()
    for i in range(n):
        p = ids_off + i * 4
        if p + 4 > len(dex):
            break
        data_off = int.from_bytes(dex[p:p + 4], "little")
        if data_off >= len(dex):
            continue
        try:
            _, q = read_uleb128(dex, data_off)
            end = dex.index(b"\x00", q)
            raw = dex[q:end]
            # MUTF-8：字符 U+0000 编码为 0xC0 0x80（overlong），先还原再按 UTF-8 解
            out.add(raw.replace(b"\xc0\x80", b"\x00").decode("utf-8"))
        except (ValueError, UnicodeDecodeError, IndexError):
            continue
    return out


# ---- 判据表 ----------------------------------------------------------------

POOL_GROUPS = {
    "W9-A 截图双通道（pool 池条目精确匹配）": [
        ("无障碍截屏服务方法名", "takeScreenshotViaA11y"),
        ("路由纯函数名", "canUseA11yScreenshot"),
        ("meta 模板含 via=a11y（完整静态段）", ',"type":"png","via":"a11y"}'),
        ("UiScreenshotTool 类型描述符", "Lcom/example/agentkit/automation/UiScreenshotTool;"),
        ("AgentAccessibilityService 类型描述符", "Lcom/example/agentkit/automation/AgentAccessibilityService;"),
    ],
    "W13 调试通道 B（pool 池条目精确匹配）": [
        ("ShizukuShell 类描述符", "Lcom/example/agentkit/automation/shizuku/ShizukuShell;"),
        ("提权路由纯函数", "shouldUsePrivilegedShell"),
        ("提权结果类型", "Lcom/example/agentkit/tools/PrivilegedShellOut;"),
        ("调试通道入口动作", "handleShizukuAction"),
    ],
    "W10 上下文预算治理（pool 池条目精确匹配）": [
        ("ContextBudget 类描述符", "Lcom/example/agentkit/core/agent/ContextBudget;"),
        ("请求视图构建方法", "buildRequestMessages"),
        ("通道预算函数", "budgetForProvider"),
        ("图片裁剪函数", "pruneImages"),
        ("预算压缩函数", "fitToBudget"),
        ("W11 结果上限函数", "toolResultLimitChars"),
    ],
}

# 反向判据：这些符号必须**不在**池中（删除/替换成功的证据，防旧码残留）
ABSENT_POOL = [
    ("旧条数压缩方法 maybeCompact 已移除", "maybeCompact"),
    ("屏幕捕获服务已移除", "Lcom/example/agentkit/automation/ScreenCaptureService;"),
    ("屏幕捕获桥已移除", "Lcom/example/agentkit/automation/ScreenshotBridge;"),
    ("屏幕捕获清理策略已移除", "ScreenCaptureStartPolicy"),
    ("屏幕捕获清理追踪已移除", "ScreenCaptureTrace"),
    ("旧捕获失败码已移除", "capture_not_enabled"),
]

BYTES_GROUPS = {
    "W9-A 文案（描述 / 失败指引，长常量片段，bytes 子串）": [
        ("描述：已授予无障碍即可使用", "已授予无障碍权限即可使用"),
        ("指引：明确无需屏幕捕捉权限", "无需屏幕捕捉权限"),
    ],
    "W13 权限页文案（bytes 子串）": [
        ("调试通道授权项标题", "调试通道（Shizuku）"),
        ("未运行引导", "通过无线调试启动"),
        ("后台执行说明", "shell uid 2000"),
    ],
    "W10 图片裁剪文案（bytes 子串）": [
        ("旧图省略标记", "早前截图已从上下文省略"),
    ],
    "W9-B 系统提示分工段（长常量片段，bytes 子串）": [
        ("分工段标题", "工具分工：ui_tree 拿界面结构与坐标"),
        ("禁每步截图", "不要每个步骤都截图"),
        ("截图看视觉", "ui_screenshot 用于「看见」视觉内容"),
    ],
}

OLD_DESCRIPTION = (
    "截取当前手机屏幕画面，并以图片形式返回。Agent 可据此“看见”屏幕内容"
    "（图标、图片、验证码、游戏画面等 UI 树无法描述的区域），用于理解界面、"
    "定位元素、判断状态。返回图片会自动附到下一轮对话供视觉模型分析。"
    "需要先在「自动化」页开启屏幕捕获授权。"
)


def main():
    if not os.path.exists(APK):
        print(f"FATAL: APK 不存在: {APK}")
        sys.exit(2)
    zf = zipfile.ZipFile(APK)
    names = zf.namelist()
    dexes = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", n))
    print(f"APK: {APK} ({os.path.getsize(APK):,} bytes, sha256 见下载记录)")
    print(f"条目数: {len(names)} | dex: {len(dexes)} 个")
    for req in ("AndroidManifest.xml", "resources.arsc"):
        if req not in names:
            print(f"FATAL: 缺少 {req}（不是合法 APK）")
            sys.exit(2)

    pool = set()
    blob = b""
    for d in dexes:
        raw = zf.read(d)
        blob += raw
        pool |= dex_string_pool(raw)
    print(f"dex 合计: {len(blob):,} bytes | 字符串池条目: {len(pool):,} 个\n")

    total_fail = 0
    for group, items in POOL_GROUPS.items():
        print(f"== {group} ==")
        for label, needle in items:
            hit = needle in pool
            print(f"  {'PASS' if hit else 'FAIL'}  {label}  <{needle[:60]}>")
            if not hit:
                total_fail += 1
        print()

    for group, items in BYTES_GROUPS.items():
        print(f"== {group} ==")
        for label, needle in items:
            hit = needle.encode("utf-8") in blob
            print(f"  {'PASS' if hit else 'FAIL'}  {label}  <{needle[:60]}>")
            if not hit:
                total_fail += 1
        print()

    for label, symbol in ABSENT_POOL:
        absent = symbol not in pool
        print(f"  {'PASS' if absent else 'FAIL'}  [反证] {label}  <{symbol}>")
        if not absent:
            total_fail += 1
    print()

    gone = OLD_DESCRIPTION.encode("utf-8") not in blob
    print(f"  {'PASS' if gone else 'FAIL'}  [反证] 修前旧描述整串已从包内消失")
    if not gone:
        total_fail += 1

    print(f"\n=== 反验汇总: {'全部通过' if total_fail == 0 else str(total_fail) + ' 项失败'} ===")
    sys.exit(0 if total_fail == 0 else 1)


main()
