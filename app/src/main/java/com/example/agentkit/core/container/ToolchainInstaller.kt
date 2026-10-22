package com.example.agentkit.core.container

import java.io.File

/**
 * 容器运行时一键安装（B 批）。
 *
 * ## 要解决什么
 * 容器（proot + Alpine/Debian rootfs）默认是**极简**的：只有 busybox 一套基础工具。
 * 用户想在里面跑 `npm install`、`python script.py`、`javac` 时什么都找不到，
 * 得自己敲 `apk add nodejs python3 openjdk17`——而手机上的容器没有桌面体验，
 * 这道坎足以让人放弃用容器做开发。
 *
 * 对标 Coomi / AiCode 的「容器开箱即用」：进容器就有完整的语言运行时。
 *
 * ## 设计取舍
 * **不预置运行时镜像**。打包 node+python+JRE 进 APK 会让包体增加数百 MB，
 * 且 apk 仓库里现成的包更干净。改为**按需一键装**：
 * - [detect] 先探测容器里已有什么（不重复装、不覆盖用户改过的版本）；
 * - [installCommand] 生成对应的安装命令（Alpine 用 apk，Debian 用 apt）。
 *
 * ## 包管理器识别
 * 不靠发行版名字猜，靠**文件系统事实**：
 * - `<rootfs>/sbin/apk` 或 `<rootfs>/bin/apk` 存在 → Alpine 系（apk）
 * - `<rootfs>/usr/bin/apt-get` 存在 → Debian 系（apt）
 * - 都没有 → 不支持自动安装（返回 null，UI 明说原因，不瞎试）
 *
 * 纯文件判断 + 纯字符串拼接，**零 Android 依赖**，可在 JVM harness 直接断言。
 */
object ToolchainInstaller {

    /** 可安装的语言运行时。 */
    enum class Lang(
        val id: String,
        val displayName: String,
        /** 探测「已安装」用的可执行文件名（按优先级取第一个存在的）。 */
        val probeBinaries: List<String>,
        /** Alpine (apk) 包名。 */
        val apkPackages: List<String>,
        /** Debian (apt) 包名。 */
        val aptPackages: List<String>,
        /** 安装后用于自检的命令（`--version`）。 */
        val verifyCommand: String
    ) {
        NODE(
            id = "node",
            displayName = "Node.js",
            probeBinaries = listOf("node", "nodejs"),
            apkPackages = listOf("nodejs", "npm"),
            aptPackages = listOf("nodejs", "npm"),
            verifyCommand = "node --version"
        ),
        PYTHON(
            id = "python",
            displayName = "Python 3",
            probeBinaries = listOf("python3", "python"),
            apkPackages = listOf("python3", "py3-pip"),
            aptPackages = listOf("python3", "python3-pip"),
            verifyCommand = "python3 --version"
        ),
        JAVA(
            id = "java",
            displayName = "Java (JRE + JDK)",
            probeBinaries = listOf("java", "javac"),
            apkPackages = listOf("openjdk17", "openjdk17-jdk"),
            aptPackages = listOf("default-jdk"),
            verifyCommand = "java -version"
        );

        companion object {
            fun byId(id: String): Lang? = entries.firstOrNull { it.id == id }
        }
    }

    /** 支持的包管理器。 */
    enum class PkgManager(val id: String, val displayName: String) {
        APK("apk", "Alpine (apk)"),
        APT("apt", "Debian (apt)")
    }

    /** 探测结果。 */
    data class Detection(
        val pkgManager: PkgManager?,
        /** 已安装的语言运行时。 */
        val installed: Set<Lang>,
        /** rootfs 里没找到包管理器时的原因（供 UI 展示）。 */
        val reason: String? = null
    )

    /**
     * 版本约束（C 批）。
     *
     * 用户要装运行时时，常常不是「随便给我个 node」——而是「我要 node 18」
     *（因为某个项目 lockfile 卡在 18）或「python 要 3.11」（依赖某些新语法）。
     *
     * 不同发行版、不同版本的**包名不一样**，这是这里要解决的核心问题：
     * - Alpine 的 node 版本是分包的：`nodejs`（当前版）/ `nodejs18` / `nodejs20`；
     * - Debian 的 node 版本在第三方源（NodeSource），官方源只有 `nodejs`；
     * - Java 的版本包名直接带号：`openjdk17` / `openjdk21` / `default-jdk`。
     *
     * 所以约束要**按包管理器分别解析**，不能一套包名走天下。
     */
    data class VersionSpec(
        /** 版本号，如 "18" / "3.11" / "17"。 */
        val version: String,
        /** 该语言在 Alpine 下对应的包名。 */
        val apkPackages: List<String>,
        /** 该语言在 Debian 下对应的包名。 */
        val aptPackages: List<String>,
        /** 解析说明（供 UI 展示「你要的版本装不上，退而求其次装了哪个」）。 */
        val note: String = ""
    )

    /**
     * 解析版本约束（C 批）。
     *
     * @param lang 目标语言
     * @param version 用户指定的版本号；空串表示不指定
     * @return 解析结果；版本号非法（如含字母、空格）返回 null
     */
    fun resolveVersion(lang: Lang, version: String): VersionSpec? {
        val v = version.trim()
        if (v.isEmpty()) return null
        // 只接受数字与点（如 18 / 3.11）——版本号会拼进 shell 命令，
        // 必须严格白名单，不能让 `18; rm -rf /` 这类东西混进来。
        if (!v.matches(Regex("^[0-9]+(\\.[0-9]+)*$"))) return null

        return when (lang) {
            Lang.NODE -> {
                // Alpine：nodejs18 / nodejs20 这类分版本包；Debian 官方源只有 nodejs
                val major = v.substringBefore('.')
                VersionSpec(
                    version = v,
                    apkPackages = listOf("nodejs$major", "npm"),
                    aptPackages = listOf("nodejs", "npm"),
                    note = "Debian 官方源只提供默认版本 nodejs；如需 node $major 请在容器内配置 NodeSource。"
                )
            }
            Lang.PYTHON -> {
                // Alpine 的 python3 是主版本线；Debian 用完整版本号做包名
                val major = v.substringBefore('.')
                VersionSpec(
                    version = v,
                    apkPackages = listOf("python$major", "py$major-pip"),
                    aptPackages = listOf("python$v", "python$v-pip"),
                    note = "若仓库没有 python $v 的包，安装会失败，可改用默认版本。"
                )
            }
            Lang.JAVA -> {
                // Java 的版本直接编码在包名里（openjdk17 / openjdk21）
                val major = v.substringBefore('.')
                VersionSpec(
                    version = v,
                    apkPackages = listOf("openjdk$major", "openjdk$major-jdk"),
                    aptPackages = listOf("openjdk-$major-jdk"),
                    note = "Debian 下需 openjdk-$major-jdk 包；仓库缺失时请换用 default-jdk。"
                )
            }
        }
    }

    /**
     * 探测 rootfs 里已有的运行时与包管理器。
     *
     * @param rootfs 已解压的 rootfs 目录
     */
    fun detect(rootfs: File): Detection {
        val pkg = detectPkgManager(rootfs)
        val installed = Lang.entries.filter { lang ->
            lang.probeBinaries.any { binaryExists(rootfs, it) }
        }.toSet()
        val reason = when {
            pkg != null -> null
            else -> "未识别到包管理器（apk / apt-get 都不存在），该 rootfs 无法自动安装运行时"
        }
        return Detection(pkg, installed, reason)
    }

    /** 识别包管理器（看二进制是否存在，不猜发行版名）。 */
    fun detectPkgManager(rootfs: File): PkgManager? = when {
        binaryExists(rootfs, "apk") -> PkgManager.APK
        binaryExists(rootfs, "apt-get") -> PkgManager.APT
        else -> null
    }

    /**
     * 生成安装命令。
     *
     * Alpine：`apk add --no-cache nodejs npm`
     * Debian：`apt-get update && apt-get install -y nodejs npm`
     *
     * ## 为什么 Alpine 加 `--no-cache`
     * apk 默认会缓存索引与包文件。在手机存储上，缓存一个 nodejs + npm 缓存能吃掉
     * 数百 MB 且用户根本不会去清。`--no-cache` 装完即走。
     *
     * ## 为什么 Debian 必须先 update
     * Debian 系不 update 直接 install 会用陈旧索引报「包不可用」。
     *
     * @param spec 可选的版本约束（C 批）；非 null 时用它的包名覆盖默认包名
     * @return 安装命令；包管理器不支持或该语言无对应包时返回 null
     */
    fun installCommand(
        lang: Lang,
        pkgManager: PkgManager,
        spec: VersionSpec? = null
    ): String? {
        val packages = when (pkgManager) {
            PkgManager.APK -> spec?.apkPackages ?: lang.apkPackages
            PkgManager.APT -> spec?.aptPackages ?: lang.aptPackages
        }
        if (packages.isEmpty()) return null
        return when (pkgManager) {
            PkgManager.APK -> "apk add --no-cache ${packages.joinToString(" ")}"
            PkgManager.APT -> "apt-get update && apt-get install -y ${packages.joinToString(" ")}"
        }
    }

    /**
     * 生成「装完所有缺失运行时」的命令。
     *
     * @return 命令；无缺失返回 null（无需安装）
     */
    fun installMissingCommand(detection: Detection): String? {
        val pkg = detection.pkgManager ?: return null
        val missing = Lang.entries.filter { it !in detection.installed }
        if (missing.isEmpty()) return null
        // 一次装齐：Alpine 合并包名；Debian 分两次 apt（update 必须在前）
        return when (pkg) {
            PkgManager.APK -> {
                val all = missing.flatMap { it.apkPackages }.distinct()
                "apk add --no-cache ${all.joinToString(" ")}"
            }
            PkgManager.APT -> {
                val all = missing.flatMap { it.aptPackages }.distinct()
                "apt-get update && apt-get install -y ${all.joinToString(" ")}"
            }
        }
    }

    /** 二进制是否存在（查 rootfs 下常见 bin 目录）。 */
    private fun binaryExists(rootfs: File, name: String): Boolean =
        BIN_DIRS.any { dir -> File(rootfs, "$dir/$name").exists() }

    /** rootfs 内的可执行文件目录（覆盖 Alpine 与 Debian 布局）。 */
    private val BIN_DIRS = listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin")
}
