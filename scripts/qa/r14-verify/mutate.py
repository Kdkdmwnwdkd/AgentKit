#!/usr/bin/env python3
"""R-14 守卫的变异测试：把修好的契约结构逐个改坏，看守卫是否靶向变红。

判据自己也可能是摆设——只有「改坏必红、红在对的那组判据、恢复必绿」才算真判据。
每组改的是**源码**，跑的是**正式 runner**（不是简化版），所以验的是 CI 上真正会跑的
那条链路。

为什么多数变异只该红一条：判据之间若互相串味（加个死成员就把组装根判据也搞红），
说明边界没切干净，出事时定位不到地方。所以每组都带 wants（必须命中）与
forbidden（不该被牵连），少数语义上必然连带的（闭包变小 → 覆盖判据一起红）显式放宽。
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.dirname(os.path.abspath(__file__)))))
UI = 'app/src/main/java/com/beichen/polaris/ui'
C = UI + '/contract'
VM = UI + '/ChatViewModel.kt'
RUNNER = 'scripts/qa/run-r14-contract-verification.sh'

# (名字, [(文件, old, new, 期望出现次数), ...], 必须命中的 FAIL 判据, 不该红的判据)
MUTS = [
    (
        'M1  收藏页改回整个 ViewModel（越界参数）',
        [(UI + '/BookmarkScreen.kt',
          'fun BookmarkScreen(vm: BookmarksContract) {',
          'fun BookmarkScreen(vm: ChatViewModel) {', 1)],
        ['A1'],
        ['A2', 'B1', 'B3', 'B4', 'C1', 'C2', 'C3', 'C4', 'D1'],
    ),
    (
        'M2  组装根不再持有 ViewModel（A1 空集不能算绿）',
        [(UI + '/AppScaffold.kt', '    vm: ChatViewModel,', '    vm: Any,', 2)],
        ['A2'],
        ['A1', 'B1', 'B2', 'B3', 'B4', 'B5', 'C1', 'C2', 'C3', 'C4', 'D1'],
    ),
    (
        'M3  BookmarksContract 去掉 @Stable',
        [(C + '/ContentContracts.kt',
          '@Stable\ninterface BookmarksContract {',
          'interface BookmarksContract {', 1)],
        ['B1'],
        ['A1', 'A2', 'B2', 'B3', 'B4', 'B5', 'C1', 'C2', 'C3', 'C4', 'D1'],
    ),
    (
        'M4  接口留一个没人用的成员（过度暴露）',
        [(UI + '/BookmarkScreen.kt',
          '                vm.clearBookmarks()',
          '                // vm.clearBookmarks()', 1)],
        ['B2'],
        ['A1', 'A2', 'B1', 'B3', 'B4', 'B5', 'C1', 'C2', 'C3', 'C4', 'D1'],
    ),
    (
        'M5  屏幕调用契约没给的成员（越界使用）',
        [(UI + '/BookmarkScreen.kt',
          '                vm.clearBookmarks()',
          '                vm.clearBookmarks()\n                vm.ghostCall()', 1)],
        ['B3'],
        ['A1', 'A2', 'B1', 'B2', 'B4', 'B5', 'C1', 'C2', 'C3', 'C4', 'D1'],
    ),
    (
        'M6  组合接口断一条继承边（闭包变小）',
        [(C + '/ChatContracts.kt',
          '    ChatStream, ChatTurnState, ChatTurnActions, ChatApprovalActions,',
          '    GhostStream, ChatTurnState, ChatTurnActions, ChatApprovalActions,', 1)],
        ['B4'],
        # 断继承边会让闭包少掉 ChatStream：B2/B3（覆盖对账）和 C1（祖先闭包不再
        # 覆盖该契约）必然一起红，这是同一处结构损伤的多个侧面，不算串味。
        ['A1', 'A2', 'B1', 'B5', 'C2', 'C3', 'C4', 'D1'],
    ),
    (
        'M7  屏幕指向不存在的契约',
        [(UI + '/BookmarkScreen.kt',
          'fun BookmarkScreen(vm: BookmarksContract) {',
          'fun BookmarkScreen(vm: GhostBookmarksContract) {', 1)],
        ['B5'],
        ['A1', 'A2', 'B1', 'B3', 'B4', 'C1', 'C2', 'C3', 'C4', 'D1'],
    ),
    (
        'M8  ChatViewModel 少实现一个契约',
        [(VM,
          '    BookmarksContract, LogsContract, MemoryBankContract, PromptLibraryContract,',
          '    LogsContract, MemoryBankContract, PromptLibraryContract,', 1)],
        ['C1'],
        ['A1', 'A2', 'B1', 'B2', 'B3', 'B4', 'B5', 'C2', 'C3', 'C4', 'D1'],
    ),
    (
        'M9  契约成员丢了 override',
        [(VM, '    override fun clearBookmarks()', '    fun clearBookmarks()', 1)],
        ['C2'],
        ['A1', 'A2', 'B1', 'B2', 'B3', 'B4', 'B5', 'C1', 'C3', 'C4', 'D1'],
    ),
    (
        'M10 多出一个契约没声明的 override',
        [(VM, '    override fun clearBookmarks()',
          '    override fun ghostOverride() {}\n\n    override fun clearBookmarks()', 1)],
        ['C3'],
        ['A1', 'A2', 'B1', 'B2', 'B3', 'B4', 'B5', 'C1', 'C2', 'C4', 'D1'],
    ),
    (
        'M11 类声明里写错接口名（幽灵接口）',
        [(VM,
          '    BookmarksContract, LogsContract, MemoryBankContract, PromptLibraryContract,',
          '    BookmarksContrct, LogsContract, MemoryBankContract, PromptLibraryContract,', 1)],
        ['C4'],
        ['A1', 'A2', 'B1', 'B2', 'B3', 'B4', 'B5', 'C2', 'C3', 'D1'],
    ),
    (
        'M12 契约层反向 import 实现类',
        [(C + '/ContentContracts.kt',
          'package com.beichen.polaris.ui.contract\n\nimport android.net.Uri',
          'package com.beichen.polaris.ui.contract\n\n'
          'import com.beichen.polaris.ui.ChatViewModel\nimport android.net.Uri', 1)],
        ['D1'],
        ['A1', 'A2', 'B1', 'B2', 'B3', 'B4', 'B5', 'C1', 'C2', 'C3', 'C4'],
    ),
]


def run():
    r = subprocess.run(['bash', RUNNER], capture_output=True, text=True, cwd=ROOT)
    return r.stdout + r.stderr, r.returncode


def red_ids(out):
    # 含 G0（守卫整体崩溃的兜底判据）：崩溃时若抓不到它，会误读成「判据没红」，
    # 而真相是「一条判据都没跑」。
    return set(re.findall(r'^([A-Z][0-9]+) FAIL', out, re.M))


def main():
    base, brc = run()
    if brc != 0 or red_ids(base):
        print('!!! 基线不绿，变异测试无意义：')
        print(base)
        return 1

    ok = True
    for name, edits, wants, forbidden in MUTS:
        originals = {}
        applied = True
        try:
            # 先整体校验锚点，避免「改了一半才发现第 2 个锚点找不到」留下脏源码
            for path, old, new, cnt in edits:
                if path not in originals:
                    originals[path] = open(path, encoding='utf-8').read()
                seen = originals[path].count(old)
                if seen != cnt:
                    print(f'{name}: !!! 锚点出现 {seen} 次，期望 {cnt}（变异未生效）')
                    applied = False
                    break
            if applied:
                for path, old, new, cnt in edits:
                    s = open(path, encoding='utf-8').read()
                    open(path, 'w', encoding='utf-8').write(s.replace(old, new))
        finally:
            # 无论中途出什么错都先恢复，再决定是否继续
            pass

        if not applied:
            for path, orig in originals.items():
                open(path, 'w', encoding='utf-8').write(orig)
            ok = False
            continue

        out, rc = run()
        for path, orig in originals.items():
            open(path, 'w', encoding='utf-8').write(orig)
        back, brc2 = run()

        red = red_ids(out)
        hit = [w for w in wants if w in red]
        spill = [x for x in forbidden if x in red]
        targeted = bool(hit) and not spill and rc != 0
        restored = brc2 == 0 and not red_ids(back)
        good = targeted and restored
        print(f'{name}: {"靶向精确且恢复全绿" if good else "!!! 未达标"}'
              f'  (红={sorted(red)}, 命中={hit}, 串味={spill}, rc={rc})')
        if not good:
            ok = False
            # 先对行列表切片再 join；早先写成 join(...)[:8] 只截出前 8 个字符，
            # 诊断里只剩「B2 FAIL 」，看不出到底为什么红。
            lines = [l for l in out.splitlines() if 'FAIL' in l][:8]
            print('\n'.join(lines))

    print('全部 %d 组变异均被捕获且恢复全绿' % len(MUTS) if ok else '有变异漏网')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
