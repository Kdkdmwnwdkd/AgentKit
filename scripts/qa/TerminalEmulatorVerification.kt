#!/usr/bin/env kotlin
/**
 * 终端仿真器 harness：[com.beichen.polaris.terminal.TerminalEmulator]。
 *
 * ## 为什么这个文件值得先补
 *
 * 1040 行、零 Android 依赖、**此前一条断言都没有**。它吃的是 pty 字节流——shell 提示符、
 * `ls --color`、vim、htop、`top` 的每一次刷新都从这里过。解析错一个字节，用户看到的是
 * 「终端里一堆乱码」或「颜色全错」，而这类 bug 不会抛异常、不会进日志，只会被当成
 * 「这个 App 的终端不好用」。
 *
 * 断言分十段：打印与换行 / 宽度与编码 / SGR / 光标 / 擦除编辑 / 备用屏 / 历史容量 /
 * 畸形输入 / 并发 / 缩放。每条都调真函数、看真输出，没有一条读源码字符串。
 *
 * 运行：bash scripts/qa/run-terminal-emulator-verification.sh
 */

import com.beichen.polaris.terminal.ANSI_16
import com.beichen.polaris.terminal.COLOR_DEFAULT_BG
import com.beichen.polaris.terminal.COLOR_DEFAULT_FG
import com.beichen.polaris.terminal.TerminalEmulator
import com.beichen.polaris.terminal.XTERM_256

private var pass = 0
private val fails = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
        println("  ✅ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    } else {
        fails += name
        println("  ❌ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "实际=${esc(actual)} 期望=${esc(expected)}")

/** 把控制字符显示成人能看的形式（断言失败时看不出来是哪个字节就白写了）。 */
private fun esc(v: Any?): String = when (v) {
    null -> "null"
    is String -> v.replace("\u001b", "ESC").replace("\n", "\\n").let {
        if (it.length > 60) it.take(60) + "…" else it
    }
    else -> v.toString()
}

/** 造一台指定尺寸的终端。 */
private fun emu(cols: Int = 20, rows: Int = 5) = TerminalEmulator(cols, rows)

/** 喂字符串（走真实 UTF-8 字节路径，而不是 char 直通）。 */
private fun TerminalEmulator.feed(s: String) = append(s.toByteArray(Charsets.UTF_8))

/** 某一行去掉尾部空白后的文本。 */
private fun TerminalEmulator.line(r: Int): String = getScreenText().lines()[r].trimEnd()

/** 全部屏幕行（去尾部空白）。 */
private fun TerminalEmulator.screen(): List<String> = getScreenText().lines().map { it.trimEnd() }

private const val ESC = "\u001b"

fun main() {
    println("=== 终端仿真器自检（真跑解析器，不读源码）===")

    // ---------- 1. 打印与换行 ----------
    println("\n[1] 打印与换行")
    run {
        val t = emu(cols = 10)
        t.feed("abc")
        eq("文本原样落屏", t.line(0), "abc")
        eq("光标列 = 已写字符数", t.cursorCol, 3)
        eq("光标行不动", t.cursorRow, 0)
        eq("行宽恒等于列数（行尾补空格）", t.getScreenText().lines()[0].length, 10)

        t.feed("\rX")
        eq("CR 回到行首并覆盖", t.line(0), "Xbc")

        t.feed("\r\n")
        eq("CRLF：光标到下一行行首", t.cursorRow to t.cursorCol, 1 to 0)

        val t2 = emu(cols = 5, rows = 3)
        t2.feed("abcdefg")
        eq("超过列宽自动换行", t2.screen().take(2), listOf("abcde", "fg"))
        // 标记打在「被截断的那一行」上（xterm LINEWRAPPED 语义）：内容续到下一行的是第 0 行。
        check("溢出那一行带 continued 标记", t2.snapshot().lines[0].continued)
        check("承接内容的那行不带标记", !t2.snapshot().lines[1].continued)

        val t3 = emu(cols = 5, rows = 2)
        t3.feed("11111\r\n22222\r\n33333")
        eq("触底换行进滚动历史", t3.scrollbackLines, 1)
        eq("最旧一行进了历史且内容正确", t3.snapshot().lines.first().text().trimEnd(), "11111")
        eq("屏幕上剩两行", t3.screen(), listOf("22222", "33333"))
    }

    // ---------- 2. 宽度与编码 ----------
    println("\n[2] 宽度与编码（CJK / 跨 chunk / 组合字符）")
    run {
        val t = emu(cols = 10)
        t.feed("中a")
        eq("CJK 占两列：a 落在第 3 列", t.cursorCol, 3)
        eq("CJK 不重复出字（宽字符右半格是空文本）", t.line(0), "中a")
        eq("第 1 格是宽字符的右半格占位", t.snapshot().lines[0].cells[1].char, "")

        // 一个汉字的 UTF-8 是 3 字节；按字节拆三次喂，必须拼回一个字
        val t2 = emu(cols = 10)
        val bytes = "中".toByteArray(Charsets.UTF_8)
        eq("前提：汉字是 3 字节", bytes.size, 3)
        bytes.forEach { b -> t2.append(b.toInt()) }
        eq("UTF-8 跨 chunk 拼接", t2.line(0), "中")
        eq("拼接后光标按宽度前进 2", t2.cursorCol, 2)

        val t3 = emu(cols = 10)
        t3.feed("e\u0301x")   // e + 组合重音 + x
        eq("组合字符不占列", t3.cursorCol, 2)
        eq("组合字符附加到左邻格", t3.snapshot().lines[0].cells[0].combining, "\u0301")
        eq("单元格文本仍是一个字母", t3.snapshot().lines[0].cells[0].char, "e")

        val t4 = emu(cols = 2)
        t4.feed("a中")
        eq("宽字符放不进最后一列时先换行", t4.screen().take(2), listOf("a", "中"))
    }

    // ---------- 3. SGR ----------
    println("\n[3] SGR：颜色与属性")
    run {
        val t = emu(cols = 10)
        t.feed("${ESC}[31mR")
        eq("31 = 标准红", t.snapshot().lines[0].cells[0].fg, ANSI_16[1])
        t.feed("${ESC}[44mB")
        eq("44 = 标准蓝背景", t.snapshot().lines[0].cells[1].bg, ANSI_16[4])
        t.feed("${ESC}[0mN")
        eq("0 = 全部复位（前景）", t.snapshot().lines[0].cells[2].fg, COLOR_DEFAULT_FG)
        eq("0 = 全部复位（背景）", t.snapshot().lines[0].cells[2].bg, COLOR_DEFAULT_BG)

        t.feed("${ESC}[1;4mU")
        val u = t.snapshot().lines[0].cells[3]
        check("1;4 同时置粗体与下划线", u.bold && u.underline)
        t.feed("${ESC}[0;7mV")
        check("7 = 反显", t.snapshot().lines[0].cells[4].inverse)
        t.feed("${ESC}[0;92mW")
        eq("92 = 亮绿（调色板 10）", t.snapshot().lines[0].cells[5].fg, ANSI_16[10])
        t.feed("${ESC}[0;38;5;196mX")
        eq("38;5;196 = 256 色表第 196 项", t.snapshot().lines[0].cells[6].fg, XTERM_256[196])
        t.feed("${ESC}[0;38;2;10;20;30mY")
        eq("38;2;r;g;b = 真彩", t.snapshot().lines[0].cells[7].fg, 0xFF0A141E.toInt())
        t.feed("${ESC}[0;2mZ")
        check("2 = 暗淡", t.snapshot().lines[0].cells[8].dim)
    }

    // ---------- 4. 光标 ----------
    println("\n[4] 光标移动")
    run {
        val t = emu(cols = 10, rows = 5)
        t.feed("${ESC}[2;3HX")
        eq("CUP 2;3 = 行列均为 1 基", t.line(1).trimStart(), "X")
        eq("CUP 后打印落在该处", t.cursorRow to t.cursorCol, 1 to 3)

        t.feed("${ESC}[99A")
        eq("CUU 超界收敛到首行", t.cursorRow, 0)
        t.feed("${ESC}[99B")
        eq("CUD 超界收敛到末行", t.cursorRow, 4)
        t.feed("${ESC}[99C")
        eq("CUF 超界收敛到末列", t.cursorCol, 9)
        t.feed("${ESC}[99D")
        eq("CUB 超界收敛到首列", t.cursorCol, 0)

        t.feed("${ESC}[5G")
        eq("CHA(G) 5 = 第 4 列", t.cursorCol, 4)
        t.feed("${ESC}[3d")
        eq("VPA(d) 3 = 第 2 行", t.cursorRow, 2)

        t.feed("${ESC}[1;1H${ESC}[s${ESC}[4;4H${ESC}[u")
        eq("s/u 保存并恢复光标", t.cursorRow to t.cursorCol, 0 to 0)

        val t2 = emu(cols = 20)
        t2.feed("\tX")
        eq("制表符跳到下一个 8 的倍数", t2.cursorCol, 9)
    }

    // ---------- 5. 擦除与编辑 ----------
    println("\n[5] 擦除与编辑")
    run {
        val t = emu(cols = 10, rows = 3)
        t.feed("abc${ESC}[2J")
        eq("ED 2 清空整屏", t.screen(), listOf("", "", ""))
        eq("ED 2 不动光标", t.cursorCol, 3)

        val t2 = emu(cols = 10, rows = 3)
        t2.feed("abc${ESC}[1;4H${ESC}[0J")
        eq("ED 0 只清光标之后", t2.line(0), "abc")

        val t3 = emu(cols = 10, rows = 3)
        t3.feed("abcdef${ESC}[1;3H${ESC}[1K")
        eq("EL 1 清到光标格（含）", t3.line(0), "   def")
        t3.feed("${ESC}[0K")
        eq("EL 0 清光标到行尾（只剩前两格空白）", t3.line(0), "")
        t3.feed("${ESC}[1;1Hxyz${ESC}[2K")
        eq("EL 2 清整行", t3.line(0), "")

        val t4 = emu(cols = 10)
        t4.feed("abcdef${ESC}[1;2H${ESC}[3X")
        eq("ECH 擦掉 3 格、右侧不动", t4.line(0), "a   ef")

        val t5 = emu(cols = 10)
        t5.feed("abcdef${ESC}[1;2H${ESC}[2P")
        eq("DCH 删 2 字符、右侧左移", t5.line(0), "adef")

        val t6 = emu(cols = 10)
        t6.feed("abcdef${ESC}[1;2H${ESC}[2@")
        eq("ICH 插 2 空格、右侧右移", t6.line(0), "a  bcdef")

        val t7 = emu(cols = 6, rows = 3)
        t7.feed("aaa\r\nbbb\r\nccc${ESC}[2;1H${ESC}[L")
        eq("IL 在光标行插入空行、下面下移", t7.screen(), listOf("aaa", "", "bbb"))
        t7.feed("${ESC}[M")
        eq("DL 删除光标行、下面上移", t7.screen(), listOf("aaa", "bbb", ""))

        val t8 = emu(cols = 6, rows = 2)
        t8.feed("aaa\r\nbbb\r\nccc")           // ccc 触发一次滚动，历史里有 1 行
        eq("前提：已有滚动历史", t8.scrollbackLines, 1)
        t8.feed("${ESC}[3J")
        eq("ED 3 连滚动历史一起清", t8.scrollbackLines, 0)
    }

    // ---------- 6. 备用屏 ----------
    println("\n[6] 备用屏（vim / htop 靠它）")
    run {
        val t = emu(cols = 10, rows = 3)
        t.feed("main")
        t.feed("${ESC}[?1049h")
        eq("进入 1049 后屏幕是空的", t.screen(), listOf("", "", ""))
        t.feed("vi")
        eq("备用屏里可正常写", t.screen()[0], "vi")
        t.feed("${ESC}[?1049l")
        eq("退出 1049 恢复主屏内容", t.screen()[0], "main")
        eq("备用屏内容不污染主屏", t.scrollbackLines, 0)
    }

    // ---------- 7. 历史容量 ----------
    println("\n[7] 滚动历史上限")
    run {
        val t = emu(cols = 4, rows = 2)
        for (i in 0 until 2005) t.feed("$i\r\n")
        eq("历史上限 = MAX_SCROLLBACK(2000)", t.scrollbackLines, 2000)
        // 2005 行里滚掉 2004 行，容量 2000 → 丢掉最旧的 4 行，队首是第 4 行（内容 "4"）
        eq("超限丢最旧 4 行，队首是 \"4\"", t.snapshot().lines.first().text().trimEnd(), "4")
        eq("历史 + 屏幕 = 2000 + rows", t.snapshot().lines.size, 2002)
    }

    // ---------- 8. 畸形输入 ----------
    println("\n[8] 畸形输入：不崩、不疯、不吞后续")
    run {
        val rep = emu(cols = 10, rows = 3)
        rep.feed("Z${ESC}[3b")
        eq("REP(b) 重复上一个字符", rep.line(0), "ZZZZ")

        val t = emu(cols = 10, rows = 3)
        val t0 = System.nanoTime()
        t.feed("${ESC}[999999b")
        val ms = (System.nanoTime() - t0) / 1_000_000
        check("REP 超大参数不拖死（<500ms）", ms < 500, "${ms}ms")
        check("REP 有重复上限（不会真的写 999999 个字符）", t.scrollbackLines <= 1024 / 10 + 1, "历史 ${t.scrollbackLines} 行")

        val t2 = emu(cols = 10)
        t2.feed("A${ESC}[1;2qB")   // 未实现的终结字节 'q'
        eq("未实现的控制序列被忽略、后续照常打印", t2.line(0), "AB")

        val t3 = emu(cols = 10)
        t3.feed("${ESC}]0;窗口标题\u0007A")
        eq("OSC 标题不落屏", t3.line(0), "A")

        val t4 = emu(cols = 10)
        t4.feed("${ESC}Pq#0;stuff${ESC}\\A")
        eq("DCS 串整体忽略", t4.line(0), "A")

        val t5 = emu(cols = 10)
        t5.feed("X${ESC}[3")
        t5.feed("1mY")
        eq("截断的 CSI 在下一 chunk 续上", t5.line(0), "XY")
        eq("续上后属性生效", t5.snapshot().lines[0].cells[1].fg, ANSI_16[1])

        // 固定种子的随机字节：只要能不抛异常跑完，就说明状态机没有「进得去出不来」的分支
        val rnd = java.util.Random(20261104)
        val t6 = emu(cols = 16, rows = 4)
        var crashed: String? = null
        try {
            repeat(400) {
                val buf = ByteArray(64) { rnd.nextInt(256).toByte() }
                t6.append(buf)
                t6.snapshot()
            }
        } catch (e: Throwable) {
            crashed = e.toString()
        }
        check("随机字节 25KB 不抛异常", crashed == null, crashed ?: "ok")
        eq("随机字节后快照行数恒为 历史+rows", t6.snapshot().lines.size, t6.scrollbackLines + 4)
    }

    // ---------- 9. 并发 ----------
    println("\n[9] 并发：读写线程互见完整状态")
    run {
        val t = emu(cols = 40, rows = 10)
        var errors = 0
        val writer = Thread {
            repeat(2000) { i ->
                t.append("line $i ${ESC}[32mok${ESC}[0m\r\n".toByteArray())
            }
        }
        val reader = Thread {
            repeat(2000) {
                // 只在一个快照内部做一致性检查：跨两次调用读 scrollbackLines 会与写入线程
                // 抢跑，那种「不一致」是断言自己的竞态，不是被测对象的
                val snap = t.snapshot()
                if (snap.lines.size < 10 || snap.lines.size > 2010) errors++
                if (snap.cursorCol !in 0..39 || snap.cursorRow !in 0..9) errors++
            }
        }
        writer.start(); reader.start()
        writer.join(); reader.join()
        eq("并发读写下没有看到半写状态", errors, 0)
        check("并发写后历史确实增长了", t.scrollbackLines > 100, "${t.scrollbackLines} 行")
    }

    // ---------- 10. 缩放 ----------
    println("\n[10] 缩放（旋转屏幕 / 键盘弹出）")
    run {
        val t = emu(cols = 10, rows = 3)
        t.feed("0123456789\r\nsecond")
        t.resize(5, 3)
        eq("缩小列宽后内容被截断", t.screen().take(2), listOf("01234", "secon"))
        eq("光标收敛到新列宽内（旧列 10 → 4）", t.cursorCol, 4)

        val t2 = emu(cols = 6, rows = 5)
        t2.feed("a\r\nb\r\nc\r\nd\r\ne")
        t2.resize(6, 2)
        eq("缩小行高保留最新两行", t2.screen(), listOf("d", "e"))
        eq("光标收敛到新行数内", t2.cursorRow, 1)
    }

    // ---------- 结果 ----------
    println("\n=== 结果：$pass 项通过 / " + if (fails.isEmpty()) "0 失败" else "${fails.size} 条断言失败" + " ===")
    if (fails.isNotEmpty()) {
        fails.forEach { println("  失败：$it") }
        kotlin.system.exitProcess(1)
    }
}
