package com.example.agentkit.tools

import android.content.Context
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.container.ContainerCommandRunner
import com.example.agentkit.core.container.RootfsManager
import com.example.agentkit.core.container.ToolchainInstaller
import com.example.agentkit.core.llm.primOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 容器运行时管理（B 批）：让 Agent 自己能把容器变成能跑代码的环境。
 *
 * 两个动作：
 * - `action=status`（默认）：报告容器里已有哪些语言运行时、缺什么、包管理器是什么；
 * - `action=install`：安装缺失的运行时（node / python / java，可指定 language 装单个）。
 *
 * ## 为什么需要这个工具
 * 容器默认只有 busybox。用户提「在容器里跑一下这个 Python 脚本」时，
 * Agent 会发现没有 python3 —— 但它没法自己装，只能回来问用户。
 * 有了这个工具，Agent 能自己探测、自己装、自己验证，装完直接干活。
 *
 * ## 安全性
 * 安装动作会**下载并写入容器文件系统**（几百 MB），因此：
 * - `needsApproval = true`：必须经用户确认（会话内可按「设备输出」组整组放行——
 *   它与 shell 同属「在设备上执行外部代码」的语义）；
 * - 能力级标为 DANGEROUS（见 [com.example.agentkit.skill.SkillSecurity]）：
 *   第三方技能不得代为安装运行时。
 *
 * 探测动作只读，标为 SAFE。
 */
class ContainerToolchainTool(
    private val context: Context
) : AgentTool {

    override val name = "container_toolchain"

    override val description =
        "管理容器里的语言运行时。action=status 报告容器已装哪些运行时（node/python/java）以及缺什么；" +
            "action=install 安装缺失的运行时（language 可指定 node/python/java，不填则全装）。" +
            "容器默认只有 busybox，跑 Python/Node/Java 前需要先装。" +
            "不确定有哪些版本可选时，先 action=versions 查候选列表，别猜版本号。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("action", "string", "status（查看，默认）/ versions（列可选版本）/ install（安装）"),
        JsonSchema.prop(
            "language",
            "string",
            "只装指定语言：node / python / java。留空则装齐所有缺失的",
            enum = listOf("node", "python", "java")
        ),
        JsonSchema.prop(
            "version",
            "string",
            "指定版本号，如 18（node）/ 3.11（python）/ 17（java）。留空则装仓库默认版本"
        )
    )

    /** 探测只读；安装是写操作，必须串行。 */
    override val readOnly = false

    /** 安装动作要下载并写入容器，需人工确认。 */
    override val needsApproval = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // 用 JsonSafe 的软转换（primOrNull）而非 kotlinx 的硬转换：
        // 模型下发 `"action": null` 时硬转换会抛 IllegalArgumentException，整轮回答直接崩。
        val action = args["action"]?.primOrNull()?.contentOrNull?.trim()?.lowercase() ?: "status"
        val language = args["language"]?.primOrNull()?.contentOrNull?.trim()?.lowercase()
        // C 批：版本约束（如 node 18 / python 3.11）。留空 = 装仓库默认版本。
        val version = args["version"]?.primOrNull()?.contentOrNull?.trim()

        // 选一个已解压的发行版：优先第一个
        val distro = RootfsManager.listAvailable(context)
            .firstOrNull { RootfsManager.isExtracted(context, it.id) }
            ?: return@withContext ToolResult.fail(
                code = "no_container",
                message = "没有可用的容器（尚无已解压的 rootfs）。",
                hint = "先到「容器」页解压一个发行版，再调用本工具。"
            )

        val rootfs = RootfsManager.rootfsDir(context, distro.id)
        val detection = ToolchainInstaller.detect(rootfs)

        when (action) {
            "status" -> statusResult(distro.id, detection)
            "versions" -> versionsResult(detection, language)
            "install" -> installResult(distro.id, detection, language, version)
            else -> ToolResult.fail(
                code = "bad_action",
                message = "未知 action：$action",
                hint = "action 只能是 status 或 install。"
            )
        }
    }

    /**
     * 列出候选版本（D 批 · 版本矩阵探测）。
     *
     * 为什么要这个：原来只能让用户手打版本号，打完才失败、还得再猜。
     * 这里把"已知存在的版本"列出来，并**明确标注哪些装不上**——
     * `installable=false` 的项会带 note 说明为什么（比如 Debian 官方源没有 node 18）。
     *
     * @param language 限定语言；为空则列全部
     */
    private fun versionsResult(
        detection: ToolchainInstaller.Detection,
        language: String?
    ): ToolResult {
        val pkg = detection.pkgManager ?: return ToolResult.fail(
            code = "no_pkg_manager",
            message = detection.reason ?: "该 rootfs 没有可识别的包管理器，无法判断可用版本。",
            hint = "换一个 Alpine / Debian 系 rootfs。"
        )
        val targets = if (language.isNullOrBlank()) {
            ToolchainInstaller.Lang.entries
        } else {
            listOfNotNull(ToolchainInstaller.Lang.byId(language))
        }
        if (targets.isEmpty()) {
            return ToolResult.fail(
                code = "bad_language",
                message = "未知语言：$language",
                hint = "language 只能是 node / python / java。"
            )
        }
        val body = buildString {
            appendLine("包管理器：${pkg.displayName}")
            detection.distro?.let { d ->
                appendLine("发行版：${d.pkgManager.displayName} ${d.line ?: "（版本未识别）"}")
            }
            appendLine("以下为**候选**版本（编译期内置，不是实时查仓库；以实际安装结果为准）：")
            appendLine("✅ = 官方源就是这个版本   🔄 = 能装，但装到的是发行版默认版本   ⚠️ = 官方源没有，装不到")
            targets.forEach { lang ->
                appendLine()
                appendLine("${lang.displayName}：")
                ToolchainInstaller.knownVersions(lang, pkg, detection.distro).forEach { v ->
                    val mark = when {
                        v.installable && v.exact -> "✅"
                        v.installable -> "🔄"
                        else -> "⚠️"
                    }
                    appendLine("  $mark ${v.version}" + if (v.note.isNotBlank()) "  — ${v.note}" else "")
                }
            }
            appendLine()
            append("安装方式：action=install, language=<lang>, version=<版本号>")
        }
        return ToolResult.ok(code = "versions_listed", message = body)
    }

    /** 报告现状。 */
    private fun statusResult(distroId: String, detection: ToolchainInstaller.Detection): ToolResult {
        val installed = detection.installed.joinToString("、") { it.displayName }
        val missing = ToolchainInstaller.Lang.entries.filter { it !in detection.installed }
            .joinToString("、") { it.displayName }
        val text = buildString {
            appendLine("容器：$distroId")
            appendLine("包管理器：${detection.pkgManager?.displayName ?: "无（${detection.reason ?: "未知"}）"}")
            appendLine("已安装：${installed.ifBlank { "无" }}")
            appendLine("缺失：${missing.ifBlank { "无"}}")
            if (missing.isNotBlank() && detection.pkgManager != null) {
                appendLine("安装命令：${ToolchainInstaller.installMissingCommand(detection)}")
            }
        }
        return ToolResult.ok(code = "toolchain_status", message = "已获取容器运行时状态", data = text.trim())
    }

    /** 执行安装。 */
    private fun installResult(
        distroId: String,
        detection: ToolchainInstaller.Detection,
        language: String?,
        version: String?
    ): ToolResult {
        val pkg = detection.pkgManager ?: return ToolResult.fail(
            code = "no_pkg_manager",
            message = "该容器没有可用的包管理器，无法自动安装运行时。",
            hint = detection.reason ?: "请在容器内手动安装，或换一个带 apk/apt 的 rootfs。"
        )
        val target = if (language.isNullOrBlank()) {
            ToolchainInstaller.Lang.entries.filter { it !in detection.installed }
        } else {
            val lang = ToolchainInstaller.Lang.byId(language)
                ?: return ToolResult.fail(
                    code = "bad_language",
                    message = "未知语言：$language",
                    hint = "language 只能是 node / python / java。"
                )
            if (lang in detection.installed) emptyList() else listOf(lang)
        }
        if (target.isEmpty()) {
            return ToolResult.ok(
                code = "already_installed",
                message = "目标运行时已全部安装，无需操作。",
                data = "已安装：${detection.installed.joinToString("、") { it.displayName }}"
            )
        }

        // C 批：版本约束解析。版本号非法要明确报错，不能静默当成「装默认版」——
        // 用户要 node 18 却装了 node 20，比报错更糟。
        val spec = if (!version.isNullOrBlank()) {
            val lang = target.firstOrNull()
                ?: return ToolResult.fail(
                    code = "bad_version",
                    message = "指定版本时必须同时指定 language。",
                    hint = "version 需要配合 language 使用（如 language=node, version=18）。"
                )
            val parsed = ToolchainInstaller.resolveVersion(lang, version, detection.distro)
                ?: return ToolResult.fail(
                    code = "bad_version",
                    message = "版本号格式非法：$version",
                    hint = "版本号只能由数字和点组成，如 18 / 3.11 / 17。"
                )
            // 官方源里没有这个版本时**直接拒绝**，不降级安装另一个大版本。
            // 用户要 node 18 却装回 node 22，比报错更糟：lockfile 会在运行时炸。
            if (!parsed.availableOn(pkg)) {
                val alternatives = ToolchainInstaller
                    .knownVersions(lang, pkg, detection.distro)
                    .filter { it.installable }
                    .joinToString("、") { it.version }
                return ToolResult.fail(
                    code = "version_unavailable",
                    message = parsed.note.ifBlank { "该发行版的官方源里没有 ${lang.displayName} $version。" },
                    hint = "当前可装的版本：${alternatives.ifBlank { "默认版本" }}。" +
                        "要指定版本可换对应发行版的 rootfs，或参考 NodeSource/pyenv 自行安装。"
                )
            }
            parsed
        } else {
            null
        }

        val command = when {
            // 指定了版本 → 只能装单个语言（多语言 + 多版本无法用一条命令表达）
            spec != null -> ToolchainInstaller.installCommand(target.first(), pkg, spec)
            language.isNullOrBlank() -> ToolchainInstaller.installMissingCommand(detection)
            else -> ToolchainInstaller.installCommand(target.first(), pkg)
        } ?: return ToolResult.fail(
            code = "no_command",
            message = "无法为 ${target.joinToString { it.displayName }} 生成安装命令。",
            hint = "该语言在当前包管理器下没有对应包。"
        )

        val result = ContainerCommandRunner.run(context, distroId, command)
        if (!result.ok) {
            // 失败时别只丢一个 exit code：apk/apt 的报错能区分「没网」「没这个包」
            // 「权限不足」三种最常见的坑，直接说人话比让用户猜省事。
            val diagnosis = diagnoseInstallFailure(result.output)
            return ToolResult.fail(
                code = "install_failed",
                message = "安装失败（exit ${result.exitCode}）。",
                hint = (diagnosis?.let { "$it\n" } ?: "") +
                    "容器内输出：${result.output.take(300)}\n可手动进容器执行：$command"
            )
        }
        val after = ToolchainInstaller.detect(RootfsManager.rootfsDir(context, distroId))
        val specHint = spec?.let { s ->
            if (s.note.isNotBlank()) "（请求 ${s.version}，详见说明）" else "（${s.version}）"
        } ?: ""
        return ToolResult.ok(
            code = "install_ok",
            message = "已安装：${target.joinToString("、") { it.displayName }}$specHint",
            data = "现在容器里有：${after.installed.joinToString("、") { it.displayName }}\n" +
                (spec?.note?.let { "说明：$it\n" } ?: "") +
                "输出：${result.output.take(300)}"
        )
    }

    /**
     * 从 apk / apt 的报错里认出发病原因。
     *
     * 为什么需要：容器装运行时最常见的三种失败是「没网」「仓库里没这个包」「权限不足」，
     * apk/apt 各自的措辞完全不同。只回一句 exit 1，用户只能自己翻输出猜。
     *
     * @return 人话原因；认不出返回 null（不硬编一个可能错的原因）
     */
    private fun diagnoseInstallFailure(output: String): String? {
        val o = output.lowercase()
        fun any(vararg keys: String) = keys.any { it in o }
        return when {
            any(
                "temporary failure resolving", "could not resolve", "network is unreachable",
                "network error", "connection refused", "connection timed out", "dns"
            ) ->
                "容器内没有可用网络（域名解析或连接失败）。安装需要容器能访问软件源，" +
                    "先确认宿主机网络正常再重试。"
            any(
                "no such package", "unable to locate package", "has no installation candidate",
                "couldn't find any package"
            ) ->
                "软件源里没有这个包。先用 action=versions 看当前发行版实际能装哪些版本，别凭记忆填。"
            any("permission denied", "read-only file system") ->
                "容器内写权限不足（安装目录或 /etc 不可写）。检查 rootfs 是否只读解压。"
            any("not enough space", "no space left") ->
                "存储空间不足。容器 rootfs 在应用私有目录，清一些空间再装。"
            else -> null
        }
    }
}
