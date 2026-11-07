#!/usr/bin/env python3
"""从遥测流水算度量表里的指标（P5-1）。

## 为什么需要这个脚本

`AgentKit-架构方案-v1.md` 的「六、度量」里，有三项至今写着「未知」：

| 指标 | 现状 | 目标 |
|---|---|---|
| 确定性错误平均白等 | 未知（无埋点） | <1s |
| 每回合会话写入量 | 未知（无埋点） | <1MB |
| 崩溃后写操作重放次数 | 未知（无埋点） | 0 |

「无埋点」是一半原因，另一半是**没有消费者**：埋了不读，等于没埋
（R7 的 `DiagnosticsExporter.ndjsonOf` 写好两个月零调用就是这么烂掉的）。
这个脚本是那三项指标的唯一消费者。

## 数据来源

App 在 `filesDir/logs/turns-<yyyyMMdd>.ndjson` 落流水，一行一个 JSON：

- `turn.*` / `prompt.plan` / `llm.*` / `tool.*` / `step.close`：回合事件（`TurnEvent`）
- `store.write`：会话落盘计量
- `store.repair`：崩溃恢复补回的事件数

取法：App 里「导出诊断包」会把它拷成 `polaris-events-<stamp>.ndjson`，
或者 `adb pull /data/data/<pkg>/files/logs/`。

## 用法

    python3 scripts/qa/trace-metrics.py <file.ndjson> [--json]
    python3 scripts/qa/trace-metrics.py <file> --max-deterministic-ms 1000
    python3 scripts/qa/trace-metrics.py --selftest

退出码：0 = 无超限；1 = 有指标超过给定阈值（未给阈值时不判）；2 = 用法错误。
"""

import json
import os
import sys

# 确定性错误：重试一百次也不会变的那些。取值来自 ErrorNormalizer.FailureClass
# 的小写名（见 RealAgentLoop.failureCodeOf）——不在这里另造词汇表，
# 否则「什么样算确定性错误」会出现两个互相矛盾的答案。
DETERMINISTIC = {"auth", "quota", "bad_request"}

# 这些码是「这次调用没走完」而不是「供应商给了确定答复」，不计入白等统计。
NOT_A_VERDICT = {"cancelled"}


def load(path):
    """读 ndjson。坏行（半截 JSON，进程被杀时必然出现）跳过并在结果里计数。"""
    events, bad = [], 0
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                bad += 1
    return events, bad


def pct(values, q):
    """最近秩分位数。样本少（十来个回合）时插值法会给出没人经历过的数。"""
    if not values:
        return 0
    s = sorted(values)
    idx = min(len(s) - 1, max(0, int(round(q * (len(s) - 1)))))
    return s[idx]


def metrics(events):
    """把事件流过一遍，算出四项指标。"""
    turns = {}          # turnId -> {"start": ts, "end": ts, "ms": elapsed, "status": str}
    order = []          # 回合开始的先后（归属 store.write 用）
    det_wait = []       # 确定性错误的单次耗时（ms）
    det_retried = 0     # 其中「还发生了重试」的条数（>1 说明白等了多次）
    writes = []         # 每回合写入字节
    coalesced = []      # 每次写合并了几次变更
    repaired = 0        # 补回的事件总数

    for e in events:
        ev = e.get("ev")
        if ev == "turn.start":
            tid = e.get("turn")
            turns[tid] = {"start": e.get("ts", 0), "end": None, "ms": 0, "status": "?"}
            order.append(tid)
        elif ev == "turn.end":
            tid = e.get("turn")
            t = turns.setdefault(tid, {"start": 0, "end": None, "ms": 0, "status": "?"})
            t["end"] = e.get("ts", 0)
            t["ms"] = e.get("ms", 0)
            t["status"] = e.get("status", "?")
        elif ev == "llm.end":
            code = (e.get("code") or "").lower()
            if not e.get("ok") and code in DETERMINISTIC:
                det_wait.append(e.get("dur", 0))
                if e.get("attempt", 1) > 1:
                    det_retried += 1
        elif ev == "store.write":
            writes.append((e.get("ts", 0), e.get("bytes", 0)))
            if e.get("coalesced", 0) > 0:
                coalesced.append(e.get("coalesced"))
        elif ev == "store.repair":
            repaired += e.get("n", 0)

    # store.write 归到「回合」：取**最后一个已经开始的**回合（turn.start <= ts）。
    #
    # 旧规则要求 ts 同时落在 [turn.start, turn.end] 内，于是**回合结束之后的收尾落盘
    # 全被丢掉**——而落盘是去抖延迟写的（消息变更后 1~2 秒才写），真机上它恰恰发生在
    # turn.end 之后。后果是「每回合会话写入量」恒为 0，目标 <1MB 恒成立：
    # 指标看起来达标，其实一次都没量到。真机实测（2026-11-04 模拟器）：
    # 一次回合两次落盘 58600 + 59744 字节，旧规则算出 0.0KB。
    #
    # 回合开始**之前**的写（任何回合都没开始）不归属任何回合，单独统计成「回合外落盘」
    # 并打印出来——不能静默丢：丢了就等于没量。
    per_turn = {tid: 0 for tid in turns}
    outside_bytes = 0
    outside_count = 0
    for ts, nbytes in writes:
        owner = None
        for tid in order:
            if turns[tid]["start"] <= ts:
                owner = tid
            else:
                break
        if owner is not None:
            per_turn[owner] = per_turn.get(owner, 0) + nbytes
        else:
            outside_bytes += nbytes
            outside_count += 1

    turn_ms = [t["ms"] for t in turns.values() if t["end"] is not None]
    steps = [e.get("steps") for e in events if e.get("ev") == "turn.end" and "steps" in e]

    return {
        "turns": len(turns),
        "steps_p50": pct(steps, 0.5),
        "steps_p95": pct(steps, 0.95),
        "turn_ms_p50": pct(turn_ms, 0.5),
        "turn_ms_p95": pct(turn_ms, 0.95),
        "det_count": len(det_wait),
        "det_wait_ms_avg": int(sum(det_wait) / len(det_wait)) if det_wait else 0,
        "det_wait_ms_max": max(det_wait) if det_wait else 0,
        "det_retried": det_retried,
        "bytes_per_turn_avg": int(sum(per_turn.values()) / len(per_turn)) if per_turn else 0,
        "bytes_per_turn_max": max(per_turn.values()) if per_turn else 0,
        "write_coalesce_avg": round(sum(coalesced) / len(coalesced), 1) if coalesced else 0,
        "outside_write_bytes": outside_bytes,
        "outside_write_count": outside_count,
        "repaired": repaired,
    }


def human(m, bad=0):
    def s(ms):
        return f"{ms / 1000:.1f}s"

    def mb(n):
        return f"{n / 1024 / 1024:.2f}MB" if n >= 1024 * 1024 else f"{n / 1024:.1f}KB"

    lines = [
        f"回合 {m['turns']} 个 · 步数 p50={m['steps_p50']} p95={m['steps_p95']}"
        f" · 耗时 p50={s(m['turn_ms_p50'])} p95={s(m['turn_ms_p95'])}",
        f"确定性错误白等：{m['det_count']} 次，均值 {s(m['det_wait_ms_avg'])}"
        f"（峰值 {s(m['det_wait_ms_max'])}，目标 <1s）"
        + (f"，其中 {m['det_retried']} 次还发生了重试 ← 不该重试的错误被重试了"
           if m["det_retried"] else ""),
        f"每回合会话写入：均值 {mb(m['bytes_per_turn_avg'])} / 峰值 {mb(m['bytes_per_turn_max'])}"
        + (f"（另有回合外落盘 {m['outside_write_count']} 次 / {mb(m['outside_write_bytes'])}，"
           f"发生在任何回合开始之前，不计入每回合均值）"
           if m.get("outside_write_count") else "")
        + f"（目标 <1MB）· 去抖平均合并 {m['write_coalesce_avg']} 次变更/写",
        f"崩溃恢复重放：{m['repaired']} 次（目标 0）",
    ]
    if bad:
        lines.append(f"（跳过 {bad} 行坏 JSON——进程被杀时的半截行，正常现象）")
    return "\n".join(lines)


# —— 自测：内置一小段有已知答案的事件，防止「脚本自己算错了但没人发现」 ——
def selftest():
    ev = [
        {"ev": "turn.start", "ts": 1000, "turn": "t1", "ms": 0},
        # 一次确定性的 401，白等 38.4s 且重试过 3 次
        {"ev": "llm.end", "ts": 1200, "turn": "t1", "ok": False, "code": "auth", "dur": 38400, "attempt": 3},
        # 一次 429 退避后成功：不该计入白等
        {"ev": "llm.end", "ts": 3000, "turn": "t1", "ok": False, "code": "rate_limit", "dur": 10000, "attempt": 1},
        {"ev": "store.write", "ts": 4000, "store": "conversations", "bytes": 2 * 1024 * 1024, "coalesced": 7},
        {"ev": "turn.end", "ts": 5000, "turn": "t1", "ms": 4000, "status": "completed", "steps": 3},
        # 回合**之后**的收尾落盘：落盘是去抖延迟写的，真机上它恰恰在 turn.end 之后。
        # 旧规则要求 ts 落在回合窗口内，于是这一次被丢掉——指标恒为 0，目标恒成立。
        {"ev": "store.write", "ts": 5500, "store": "conversations", "bytes": 1024, "coalesced": 1},
        # 任何回合**开始之前**的落盘（启动时把恢复的历史写回去）：不归属任何回合，
        # 但必须看得见，不能静默丢。
        {"ev": "store.write", "ts": 500, "store": "conversations", "bytes": 2048, "coalesced": 1},
        {"ev": "store.repair", "ts": 6000, "store": "journal", "n": 2},
    ]
    m = metrics(ev)
    checks = [
        ("回合数", m["turns"], 1),
        ("确定性白等次数", m["det_count"], 1),
        ("确定性白等均值", m["det_wait_ms_avg"], 38400),
        ("确定性错误被重试的次数", m["det_retried"], 1),
        # 回合内的 2MB + 回合后的收尾 1KB（旧的「必须落在回合窗口内」规则会丢掉后者）
        ("回合写入字节", m["bytes_per_turn_avg"], 2 * 1024 * 1024 + 1024),
        ("回合外落盘次数", m["outside_write_count"], 1),
        ("回合外落盘字节", m["outside_write_bytes"], 2048),
        ("去抖合并均值", m["write_coalesce_avg"], 3.0),
        ("重放总数", m["repaired"], 2),
    ]
    ok = True
    for name, got, want in checks:
        flag = "✅" if got == want else "❌"
        if got != want:
            ok = False
        print(f"{flag} {name}：{got}（期望 {want}）")
    # 429 不计入、坏行跳过
    print(("✅" if m["det_count"] == 1 else "❌") + " 429 未计入确定性白等")
    print(human(m, bad=1))
    return 0 if ok else 1


def main(argv):
    if "--selftest" in argv:
        return selftest()
    args = [a for a in argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 2
    if not os.path.exists(args[0]):
        print(f"❌ 文件不存在：{args[0]}", file=sys.stderr)
        return 2
    events, bad = load(args[0])
    if not events:
        print(f"❌ 没有任何事件（文件 {args[0]}）", file=sys.stderr)
        return 2
    m = metrics(events)

    limits = {}
    for a in argv[1:]:
        if a.startswith("--max-deterministic-ms="):
            limits["det"] = int(a.split("=", 1)[1])
        elif a.startswith("--max-turn-bytes="):
            limits["bytes"] = int(a.split("=", 1)[1])
        elif a.startswith("--max-repair="):
            limits["repair"] = int(a.split("=", 1)[1])

    if "--json" in argv:
        print(json.dumps(m, ensure_ascii=False))
    else:
        print(human(m, bad))

    over = []
    if "det" in limits and m["det_wait_ms_avg"] > limits["det"]:
        over.append(f"确定性错误白等 {m['det_wait_ms_avg']}ms > {limits['det']}ms")
    if "bytes" in limits and m["bytes_per_turn_max"] > limits["bytes"]:
        over.append(f"每回合写入 {m['bytes_per_turn_max']}B > {limits['bytes']}B")
    if "repair" in limits and m["repaired"] > limits["repair"]:
        over.append(f"重放 {m['repaired']} 次 > {limits['repair']}")
    if over:
        for o in over:
            print(f"❌ 超限：{o}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
