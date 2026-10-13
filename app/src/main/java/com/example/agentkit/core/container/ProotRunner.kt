package com.example.agentkit.core.container

import android.content.Context
import android.system.Os
import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.ProcessEnv
import java.io.File
import java.io.FileOutputStream

/**
 * proot 运行时管理：把随包分发的 proot 独立可执行文件从 assets 提取到可写且可执行的
 * 路径，并拼出启动容器的**完整调用描述**（[ProotInvocation]）。
 *
 * 【GPL 合规】proot 是 GPL-2.0 授权的独立可执行文件，我们 **不** 把它静态链接进自己的
 * .so（那会触发 GPL 对整体代码的传染），而是作为独立程序随 APK 的
 * `assets/bin/arm64-v8a/proot` 分发，运行时提取后 `execve`。随包附带的
 * `assets/3rdparty/proot/COPYING`（GPLv3 全文）与 `SOURCE_OFFER.txt`（源码要约）即
 * 履行 GPL 的分发义务。
 *
 * 运行时若 proot 缺失（未执行 `scripts/build-proot.sh` 或未放入 assets），[isAvailable]
 * 返回 false，调用方据此显示降级文案，而不是静默崩溃。
 */
object ProotRunner {

    private const val TAG = "ProotRunner"

    /** assets 内 proot 二进制（按 ABI 分目录，当前仅 arm64-v8a）。 */
    const val PROOT_ASSET = "bin/arm64-v8a/proot"

    /** proot 落位目录（相对 `filesDir`）——抽成常量，便于 D1 改造时只改一处。 */
    const val PROOT_BIN_DIR = "bin"

    /** proot 落位文件名——抽成常量，便于 D1 改造时只改一处。 */
    const val PROOT_BIN_NAME = "proot"

    /** proot 相对 `filesDir` 的完整路径。 */
    const val PROOT_RELATIVE_PATH = "$PROOT_BIN_DIR/$PROOT_BIN_NAME"

    /** guest 内默认 shell（login shell，会加载 `/etc/profile`）。 */
    const val GUEST_SHELL = "/bin/sh"

    /** guest 内默认 HOME。 */
    const val GUEST_HOME = "/root"

    /** guest 视角的临时目录（覆盖宿主指向 App cache 的 TMPDIR）。 */
    const val GUEST_TMP = "/tmp"

    /** guest 内 git 全局配置路径（避免容器内 git 找不到全局配置 / 报 dubious ownership）。 */
    const val GIT_CONFIG_GLOBAL = "/root/.gitconfig"

    /**
     * guest PATH：显式列出 `/usr/local/bin`（非 login shell 不加载 `/etc/profile`，
     * 缺它会导致 `/usr/local/bin` 下的自建命令在容器内找不到）。
     */
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** 位置参数传命令时的 `$0`（纯标识位，仅用于报错信息可读性）。 */
    const val COMMAND_ARGV0 = "agentkit"

    /**
     * 位置参数传命令的脚本体：`cd -- "$1" && eval "$2"`。
     *
     * 命令**不拼进这个字符串**，而是作为 `$2` 由位置参数传入，因此命令里的空格、
     * 引号、通配符（`echo "*.vue"`）都按字面传给 `eval`，不会被二次转义。
     */
    const val COMMAND_SCRIPT = "cd -- \"\$1\" && eval \"\$2\""

    /** 提取后的宿主路径：`<filesDir>/bin/proot`。 */
    fun binaryFile(context: Context): File = File(context.filesDir, PROOT_RELATIVE_PATH)

    /** proot 是否已就绪（文件存在且可执行）。 */
    fun isAvailable(context: Context): Boolean {
        val f = binaryFile(context)
        return f.exists() && f.canExecute()
    }

    /**
     * 确保 proot 二进制就位：首次从 assets 提取并 `chmod 0700`（Android 对 filesDir
     * 内的文件默认无执行位）。已存在且可执行则直接复用。
     *
     * ## ⚠️ 风险说明（T09 / D1：proot 落位，必须在真机上验证）
     *
     * 当前落位：`assets/bin/arm64-v8a/proot` → `<filesDir>/bin/proot` + `chmod 0700`。
     *
     * **Android 10+（API 29+）对应用私有目录施加 W^X（Write XOR Execute）限制**，
     * 且部分 ROM / SELinux 策略会拒绝从 `filesDir` `execve` 三方可执行文件。一旦被拦：
     * - 表象：`proot` 进程瞬间退出（退出码非 0），logcat **没有**清晰的 permission denied，
     *   很容易误判成「rootfs 坏了 / ptrace 受限」；
     * - 判定方法：见 `docs/` 中 T09 真机验证清单，核心是 adb 里直接
     *   `run-as <pkg> <filesDir>/bin/proot --version` 看退出码与 logcat 的
     *   `avc: denied { execute }` 行；
     * - **若确认被拦，改造方案**：把 proot 改名为 `libproot.so` 放进
     *   `app/src/main/jniLibs/<abi>/`，运行时从 `applicationInfo.nativeLibraryDir`
     *   取路径；同时在 `app/build.gradle.kts` 的 packaging 里设
     *   `useLegacyPackaging=true`（否则 `.so` 不解压，`execve` 拿不到文件）。
     *   **targetSdk 保持 34 不变**——参照仓库为了绕过该限制把 targetSdk 锁在 28，
     *   代价是上不了 Google Play，我们不接受这个代价。
     *
     * @return 就绪的 proot 文件；任何失败返回 null（调用方降级）。
     */
    fun ensureBinary(context: Context, force: Boolean = false): File? {
        val out = binaryFile(context)
        if (out.exists() && out.canExecute() && !force) return out
        runCatching {
            context.assets.open(PROOT_ASSET).use { ais ->
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos -> ais.copyTo(fos) }
            }
            // 0700：仅本应用可读可执行（proot 内含完整路径重写逻辑，最小权限够用）
            Os.chmod(out.absolutePath, 0b111000000)
        }.onFailure { e ->
            AgentLogger.e(TAG, "proot 二进制提取/赋权失败：${e.message}")
            return null
        }
        if (!out.canExecute()) {
            AgentLogger.w(
                TAG,
                "proot 已落位但不可执行（${out.absolutePath}）：" +
                    "可能是 Android 10+ W^X/SELinux 限制，见 ensureBinary 的 KDoc 改造方案"
            )
            return null
        }
        return out
    }

    /**
     * proot 的「绑定 + 根目录 + 工作目录」选项（**不含 proot 自身路径与 guest shell**）。
     *
     * - `-r <rootfs>`：把解压后的 rootfs 作为容器根；
     * - `-b /dev -b /proc -b /sys`：透传 Android 的三个伪文件系统（容器里 `ps`/挂载视图靠它）；
     * - `-b /sdcard`：把外存挂进容器，便于和宿主交换文件；
     * - `-w <workDir>`：guest 初始工作目录（如 `/root`）；
     * - `-L`：fake_id0 扩展，让容器内 uid 映射成 0（非 root 安卓机上也能 `apt`/构建）。
     *
     * **注意**：返回值**不含 proot 自身路径**，只是选项片段；直接当 argv 用会让所有选项
     * 错位一位（`-r` 被当成 argv[0]）。需要完整调用请用 [buildInvocation]。
     *
     * @param rootfs   已解压的 rootfs 宿主路径
     * @param workDir  guest 内初始工作目录（相对 guest 根，如 `/root`）
     */
    fun prootOptions(rootfs: File, workDir: String): List<String> = buildList {
        add("-r"); add(rootfs.absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        add("-b"); add("/sdcard")
        add("-w"); add(workDir)
        add("-L")
    }

    /**
     * 容器内的环境变量（guest 视角，区别于宿主 TerminalSession 的 buildEnv）。
     *
     * 补齐项（T09 / D2，原理参照 aicode `LinuxContainerEngine` 的 env 段，净室重写）：
     * - `PROOT_TMP_DIR=<rootfs>/tmp`：**宿主路径**（proot 在 chroot 前用），Android 无 `/tmp`，
     *   缺它 proot 会在宿主根建临时文件失败；
     * - `TMPDIR=/tmp`：**guest 视角**，覆盖宿主指向 App cache 的值——容器内 `/data` 未挂载，
     *   沿用宿主 TMPDIR 会让 `mktemp` / `dpkg` 直接失败；
     * - `PATH` 显式含 `/usr/local/bin`（见 [GUEST_PATH]）；
     * - `GIT_CONFIG_GLOBAL=<guest 路径>`：避免容器内 git 报 dubious ownership / 找不到全局配置；
     * - `CI=true` / `NO_COLOR=1` / `PAGER=cat`（T10 / rikkahub A13）：非交互三件套，
     *   抑制确认提示（`apt install -y`）、分页器（`git log`）、ANSI 颜色转义残留。
     *
     * @param rootfs      已解压的 rootfs 宿主路径；传 null 则不带 `PROOT_TMP_DIR`
     * @param packageName 宿主包名（容器内脚本据此识别调用方）
     */
    fun guestEnvMap(
        rootfs: File?,
        packageName: String,
        nonInteractive: Boolean = true
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = GUEST_HOME
        env["PATH"] = GUEST_PATH
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["ANDROID_PACKAGE"] = packageName
        env["TMPDIR"] = GUEST_TMP
        env["GIT_CONFIG_GLOBAL"] = GIT_CONFIG_GLOBAL
        if (rootfs != null) {
            // 宿主绝对路径：proot 自身的临时目录（在 -r chroot 生效之前就要可用）
            env["PROOT_TMP_DIR"] = File(rootfs, "tmp").absolutePath
        }
        if (nonInteractive) {
            env["CI"] = "true"
            env["NO_COLOR"] = "1"
            env["PAGER"] = "cat"
        }
        return env
    }

    /**
     * [guestEnvMap] 的列表出口（`KEY=VALUE`），供需要 `List<String>` 的调用点使用。
     *
     * @param rootfs 传 null 则不带 `PROOT_TMP_DIR`
     */
    fun guestEnv(rootfs: File?, packageName: String): List<String> =
        ProcessEnv.toEnvList(guestEnvMap(rootfs, packageName))

    /**
     * 构造完整的 proot 调用描述（T09 / C4+C5 的核心出口）。
     *
     * **argv[0] 必须是 proot 二进制自身**（否则所有选项错位一位），末位是 guest shell。
     *
     * `envArray` = `System.getenv()` + 自定义 env 的**并集**（自定义覆盖同名键）：
     * PTY `forkpty` 后子进程不继承 JVM 的 `System.getenv()`，只喂自定义 env 会让 proot
     * 因缺 `ANDROID_ROOT` / `LD_LIBRARY_PATH` 瞬间退出**且日志无任何报错**。
     *
     * @param prootBinary 已就绪的 proot 二进制（[ensureBinary] 的返回值）
     * @param rootfs      已解压的 rootfs 宿主路径
     * @param workDir     guest 内初始工作目录
     * @param packageName 宿主包名
     */
    fun buildInvocation(
        prootBinary: File,
        rootfs: File,
        workDir: String,
        packageName: String
    ): ProotInvocation {
        // 尽力确保 guest /tmp 与宿主侧 proot 临时目录存在（失败不影响调用，仅日志）
        runCatching { File(rootfs, "tmp").mkdirs() }
            .onFailure { AgentLogger.w(TAG, "创建 <rootfs>/tmp 失败：${it.message}") }

        val executable = prootBinary.absolutePath
        val argv = buildList {
            add(executable) // argv[0] == proot 自身路径
            addAll(prootOptions(rootfs, workDir))
            add(GUEST_SHELL) // guest shell
        }
        val envMap = guestEnvMap(rootfs, packageName)
        return ProotInvocation(
            executable = executable,
            argv = argv,
            envMap = envMap,
            envArray = ProcessEnv.mergeHostEnv(envMap)
        )
    }

    /**
     * [buildInvocation] 的 Context 便捷重载：路径与包名都从 [context] 取。
     *
     * @return 调用描述；proot 未就位（未提取 / 不可执行）时返回 null，由调用方降级。
     */
    fun buildInvocation(
        context: Context,
        rootfs: File,
        workDir: String = GUEST_HOME
    ): ProotInvocation? {
        val bin = binaryFile(context)
        if (!bin.exists() || !bin.canExecute()) {
            AgentLogger.w(
                TAG,
                "proot 未就位（${bin.absolutePath}）：请先 ensureBinary；" +
                    "若存在但不可执行，见 ensureBinary 的 KDoc（Android 10+ W^X 风险）"
            )
            return null
        }
        return buildInvocation(bin, rootfs, workDir, context.packageName)
    }

    /**
     * 构造 guest 内「一次性命令」的 argv（T10 / rikkahub A13：**位置参数传命令**）。
     *
     * 形态（7 项 argv，命令以 `$2` 传入，不拼进脚本字符串）：
     * ```
     * /bin/sh -l -c 'cd -- "$1" && eval "$2"' agentkit "$cwd" "$cmd"
     * ```
     *
     * **为什么安全**：`$cmd` 是独立 argv 元素，shell 只对它做一次参数展开——
     * `eval` 看到的就是命令原始文本。像 `echo "*.vue"` 这类含空格 / 引号 / 通配符的
     * 命令，引号与通配符都由**容器内 shell 自己**按字面解析一次，**不会**被我们二次
     * 转义（拼进脚本字符串则需要再套一层转义，极易写错）。
     * `-l` 是 login shell，会加载 `/etc/profile`，使 `/usr/local/bin` 等路径生效。
     *
     * @param cwd     guest 内工作目录（作为 `$1`）
     * @param command 要执行的命令原文（作为 `$2`，**原样传入，不做转义**）
     * @param shell   guest shell 路径，默认 [GUEST_SHELL]
     */
    fun buildCommandArgv(
        cwd: String,
        command: String,
        shell: String = GUEST_SHELL
    ): List<String> = listOf(
        shell,
        "-l",
        "-c",
        COMMAND_SCRIPT,
        COMMAND_ARGV0, // $0
        cwd,           // $1
        command        // $2
    )

    /**
     * POSIX 单引号强引用：把任意字符串包成 shell 里的**单个字面量参数**。
     * `'` → `'\''`（闭合 + 转义单引号 + 重新打开）。
     *
     * 仅在「必须把 argv 退化成一整行文本写进 pty stdin」时使用（见
     * [ContainerSession.runCommand]）；有 argv 通道时优先用 [buildCommandArgv]。
     */
    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /** [buildCommandArgv] 的文本出口：argv → 可直接写进 pty stdin 的一行命令。 */
    fun toCommandLine(cwd: String, command: String, shell: String = GUEST_SHELL): String =
        buildCommandArgv(cwd, command, shell).joinToString(" ") { shellQuote(it) }
}

/**
 * proot 的完整调用描述：可执行文件 + 完整 argv + 环境变量（Map / 数组两种出口）。
 *
 * - [executable]：proot 二进制绝对路径（供 `ProcessBuilder` 用）；
 * - [argv]：**完整 argv，`argv[0] == executable`**，末位是 guest shell `/bin/sh`
 *   （供 `execve` / JNI `createSubprocessArgv` 用）；
 * - [envMap]：自定义环境变量（供 `ProcessBuilder.environment()` 用，DEBUG 可读性最好）；
 * - [envArray]：`System.getenv()` + [envMap] 的并集，同名键 [envMap] 胜出
 *   （供 PTY / `execve` 用——这是 proot 不静默退出的关键）。
 */
data class ProotInvocation(
    val executable: String,
    val argv: List<String>,
    val envMap: Map<String, String>,
    val envArray: Array<String>
) {

    /**
     * 自检：返回所有不满足不变量的描述（**空列表 = 通过**）。
     *
     * 项目无 JUnit 依赖（离线仓库缺 junit 包），验收走「纯函数 + 日志自检」：
     * 调用方（[ContainerSession.create]）把结果打进 [AgentLogger]，真机 / 日志一查即知。
     */
    fun validate(): List<String> = buildList {
        if (argv.isEmpty()) {
            add("argv 为空")
        } else {
            if (argv[0] != executable) add("argv[0]=${argv[0]} != executable=$executable（选项会错位一位）")
            if (argv.last() != ProotRunner.GUEST_SHELL) {
                add("argv 末位=${argv.last()} 不是 guest shell(${ProotRunner.GUEST_SHELL})")
            }
        }
        if (envArray.isEmpty()) {
            add("envArray 为空：PTY 不继承父进程环境，proot 会因缺 ANDROID_ROOT 等静默退出")
        } else {
            val arr = envArray.map { it.substringBefore('=') }.toSet()
            for (key in listOf("PATH", "TMPDIR", "PROOT_TMP_DIR", "CI", "NO_COLOR", "PAGER")) {
                if (key !in arr) add("envArray 缺少 $key")
            }
            if (!arr.contains("ANDROID_ROOT") && !arr.contains("ANDROID_DATA")) {
                add("envArray 未包含任何宿主 Android 变量（未与 System.getenv() 合并？）")
            }
        }
    }

    /** 一行摘要（供日志；避免直接打 envArray 把宿主环境全量写进日志）。 */
    fun describe(): String =
        "executable=$executable, argv=${argv.joinToString(" ")}, " +
            "envMap=${envMap.size} 项, envArray=${envArray.size} 项"

    // 手动实现：data class 默认的 equals/hashCode 对 Array 走引用比较，toString 会打出对象地址
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProotInvocation) return false
        return executable == other.executable &&
            argv == other.argv &&
            envMap == other.envMap &&
            envArray.contentEquals(other.envArray)
    }

    override fun hashCode(): Int {
        var result = executable.hashCode()
        result = 31 * result + argv.hashCode()
        result = 31 * result + envMap.hashCode()
        result = 31 * result + envArray.contentHashCode()
        return result
    }

    override fun toString(): String = "ProotInvocation(${describe()})"
}
