package com.example.agentkit.core.agent

/**
 * `shell_run` 的「按命令内容决定要不要审批」规则——**纯函数**，无 Android 依赖，可直接单测。
 *
 * ## 要解决的问题
 *
 * `shell_run` 原来的做法是 `needsApproval = true`：不管跑什么，每一条命令都弹一次窗。
 * 实际后果是相反的——
 *  - 用户被 `ls`、`cat`、`pwd` 这类纯读命令反复打断，几次之后就形成「看到弹窗就点允许」的肌肉记忆；
 *  - 等真的出现 `rm -rf /sdcard/Download` 时，那条弹窗和前面十条长得一模一样，拦截形同虚设。
 *
 * 所以判定必须看**命令本身**：只读命令放行，写操作/重定向/命令替换一律询问。
 * 这与 Claude Code 的 `Bash(git status:*)` 授权模型是同一个思路（这里按原理重写，
 * 只读白名单与判定细节都按本项目的执行环境重新定）。
 *
 * ## 为什么要处理这么多边缘情况
 *
 * 「只读」不能只看第一个词：`find . -delete`、`sort -o out.txt`、`tree -o f`、
 * `uniq a.txt b.txt`（第二个参数是输出文件）都是披着只读外衣的写操作。
 * 而 `$(...)`、反引号、`<(...)` 可以在一条「看起来只读」的命令里执行任意东西，
 * 只要出现就必须问。
 */
object ShellCommandRules {

    /**
     * 确认无副作用的命令。
     *
     * 只收「输出到 stdout、不碰文件系统」的：`ls cat head tail wc grep find pwd echo …`。
     * 刻意**不含** `sed`/`awk`（`-i` 会改文件）、`tee`、`xargs`（能拼出任意命令）、
     * `cp`/`mv`/`rm`。
     */
    private val READ_ONLY: Set<String> = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "find", "pwd",
        "echo", "printf", "date", "stat", "file", "du", "df", "sort", "uniq", "diff",
        "cmp", "md5sum", "sha1sum", "sha256sum", "which", "tree", "uname", "whoami",
        "id", "basename", "dirname", "cut", "tr", "nl", "od", "hexdump", "xxd", "rg",
        "jq", "column", "seq", "free", "uptime", "ps", "getprop", "dumpsys",
    )

    /** 这些命令的「记忆粒度」是前两个词（`git status` 与 `git push` 风险完全不同）。 */
    private val TWO_WORD: Set<String> = setOf(
        "git", "npm", "pnpm", "yarn", "pip", "pip3", "cargo", "go", "apt", "apt-get",
        "apk", "pkg", "pm", "cmd", "svc",
    )

    /** `&& | || ; 换行` 都是「一条命令串」，每一段都得单独判定。 */
    private val SPLIT = Regex("\\s*(?:&&|\\|\\||;|\\||\\n)\\s*")

    private val FIND_WRITES = Regex("(^|\\s)-(exec|execdir|ok|okdir|delete|fprint\\w*)\\b")

    /** 命令里出现这些，就可能在「只读」的外壳里执行任意代码。 */
    private val SUBSTITUTION = listOf("\$(", "`", "<(", ">(", "\${")

    /** 拆成独立的命令段（去掉空段）。 */
    fun segments(command: String): List<String> =
        command.trim().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 这条命令要不要弹窗。
     *
     * @return true = 需要用户确认
     */
    fun needsApproval(command: String): Boolean {
        val parts = segments(command)
        if (parts.isEmpty()) return true          // 空命令交给工具自己报错，但不静默放行
        if (SUBSTITUTION.any { command.contains(it) }) return true
        // 重定向：`>` `>>` 写文件；`<` 虽是读，但配合上面的替换判定已经足够可疑，一并问
        if (command.contains('>')) return true
        return parts.any { !isReadOnly(it) }
    }

    /** 单段是否只读（不含管道，管道已在 [segments] 拆开）。 */
    private fun isReadOnly(segment: String): Boolean {
        if ('&' in segment) return false           // 后台执行：可能留下长期进程
        val words = segment.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val head = words.firstOrNull()?.substringAfterLast('/') ?: return false
        if (head !in READ_ONLY) return false
        val args = words.drop(1)
        return when (head) {
            // find 自身的 `-exec`/`-delete`/`-ok` 就是执行/删除
            "find" -> !FIND_WRITES.containsMatchIn(segment)
            // sort -o / --output 写文件（-o 可能黏在组合短选项里，如 `-uo`）
            "sort" -> args.none { it == "-o" || it.startsWith("--output") || (it.startsWith("-") && !it.startsWith("--") && 'o' in it) }
            // uniq 的第二个位置参数是输出文件
            "uniq" -> args.count { !it.startsWith("-") } <= 1
            // tree -o 写文件
            "tree" -> args.none { it == "-o" }
            // dumpsys 本身只读，但 dumpsys 某些服务（如 battery reset）会改状态，保守起来只信常见只读子命令
            "dumpsys" -> args.isEmpty() || args.first() in SAFE_DUMPSYS
            else -> true
        }
    }

    /** `dumpsys` 里确认只读的服务。 */
    private val SAFE_DUMPSYS: Set<String> = setOf(
        "activity", "window", "package", "battery", "meminfo", "cpuinfo", "display",
        "wifi", "telephony.registry", "input", "power", "alarm",
    )

    /**
     * 「总是允许」要记住的前缀（第一段的命令名；[TWO_WORD] 里取前两个词）。
     *
     * 目前只用于日志与后续规则表；判定本身不依赖它——先让「只读放行」跑起来，
     * 用户自定义规则（`git status` → 允许、`rm` → 拒绝）留到下一步接进设置页。
     */
    fun prefixOf(command: String): String {
        val words = segments(command).firstOrNull().orEmpty()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
            .dropWhile { it.contains('=') && !it.startsWith("-") }
        if (words.isEmpty()) return ""
        val first = words[0].substringAfterLast('/')
        return if (first in TWO_WORD && words.size > 1 && !words[1].startsWith("-")) {
            "$first ${words[1]}"
        } else {
            first
        }
    }
}
