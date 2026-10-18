package com.example.agentkit.tools

import android.app.Application
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.ConfigStore
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.build.FileChange
import com.example.agentkit.core.build.GitHubConfig
import com.example.agentkit.core.build.GitHubGitLogic
import com.example.agentkit.core.build.GitHubGitService
import com.example.agentkit.core.text.LineDiffLogic
import com.example.agentkit.core.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * GitHub 提交三件套（W-32）：`github_status` / `github_commit` / `github_log`。
 *
 * 补齐「改完能提交」的闭环——之前 Agent 能改工作区文件，但改完就停在那儿，
 * 用户还得自己去电脑上提交。现在模型可以：看差异 → 提交 → 报 sha。
 *
 * 共用同一份 PAT 配置（「构建」页里填的 owner/repo/PAT），不再让用户配第二遍。
 * 未配置时**如实失败**并指路，不假装成功。
 */
private fun loadConfig(app: Application): GitHubConfig? =
    runCatching { ConfigStore(app).loadGithubConfig() }.getOrNull()

private fun notConfigured(): ToolResult = ToolResult.fail(
    code = "github_not_configured",
    message = "还没有配置 GitHub 仓库",
    hint = "到「构建」页填 owner / repo / PAT（需要该仓库的 Contents 读写权限），" +
        "提交会复用这份配置。"
)

/**
 * 收集工作区文件（字节）。
 *
 * @param only 只取这些相对路径（null = 全部）
 * @return (文件表, 被跳过的文件名列表)；跳过的原因写在调用方文案里（过大/非法路径）
 */
private fun collectWorkspace(
    app: Application,
    only: List<String>?
): Pair<Map<String, ByteArray>, List<String>> {
    val root = WorkspaceStore.dir(app)
    val paths = if (only.isNullOrEmpty()) {
        WorkspaceStore.listTree(app).filter { !it.isDir }.map { it.path }
    } else {
        only.mapNotNull { GitHubGitLogic.normalizePath(it) }
    }
    val files = LinkedHashMap<String, ByteArray>()
    val skipped = ArrayList<String>()
    for (path in paths) {
        if (files.size >= GitHubGitLogic.MAX_FILES) {
            skipped += "$path（超过单次 ${GitHubGitLogic.MAX_FILES} 个文件上限）"
            continue
        }
        val file = File(root, path)
        if (!file.isFile) {
            skipped += "$path（不存在或不是文件）"
            continue
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null) {
            skipped += "$path（读取失败）"
            continue
        }
        if (!GitHubGitLogic.isCommittable(path, bytes)) {
            skipped += "$path（超过 ${GitHubGitLogic.MAX_FILE_BYTES / 1024 / 1024} MB 或路径非法）"
            continue
        }
        files[path] = bytes
    }
    return files to skipped
}

/** 解析 `paths` 参数：逗号或换行分隔。空 = 全部。 */
private fun parsePaths(raw: String?): List<String>? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return text.split(',', '\n', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

/** `branch` 参数：空则 main。 */
private fun branchOf(args: JsonObject): String =
    args["branch"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { "main" } ?: "main"

/**
 * `github_status`：看工作区与远端分支的差异（只读，不产生提交）。
 *
 * 为什么先给 status 而不是直接 commit：
 * 模型需要先知道"要提交什么"才能在 message 里写清楚；用户也需要能在提交前看一眼。
 */
class GitHubStatusTool(private val app: Application) : AgentTool {

    override val name = "github_status"

    override val description =
        "对比工作区文件与 GitHub 仓库指定分支，列出待提交的新增/修改文件（只读，不提交）。" +
            "提交前先用它确认要提交什么。复用「构建」页配置的仓库与 PAT。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("branch", "string", "分支名，默认 main"),
        JsonSchema.prop("paths", "string", "只看这些文件（逗号分隔的相对路径）；留空 = 全部")
    )

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cfg = loadConfig(app) ?: return@withContext notConfigured()
        val branch = branchOf(args)
        val (files, skipped) = collectWorkspace(app, parsePaths(
            args["paths"]?.jsonPrimitive?.contentOrNull
        ))
        if (files.isEmpty()) {
            return@withContext ToolResult.fail(
                code = "empty_workspace",
                message = "工作区没有可提交的文件",
                hint = "先在编辑器里创建/写入文件，或用 write_file 生成内容。"
            )
        }
        GitHubGitService(cfg).status(branch, files).fold(
            onSuccess = { changes ->
                val note = if (skipped.isEmpty()) "" else
                    "\n\n被跳过：\n" + skipped.take(10).joinToString("\n") { "- $it" }
                if (changes.isEmpty()) {
                    ToolResult.ok(
                        code = "up_to_date",
                        message = "工作区与 ${cfg.fullName}@$branch 一致，没有待提交的变更",
                        data = "共检查 ${files.size} 个文件，全部与远端相同。$note"
                    )
                } else {
                    ToolResult.ok(
                        code = "changes_found",
                        message = "${changes.size} 个文件待提交（共检查 ${files.size} 个）",
                        data = GitHubGitLogic.summarizeChanges(changes) + note
                    )
                }
            },
            onFailure = { e ->
                ToolResult.fail(
                    code = "github_status_failed",
                    message = "读取远端失败：${e.message?.take(200)}",
                    hint = "检查 PAT 是否有该仓库 Contents 读权限、分支名是否正确。"
                )
            }
        )
    }
}

/**
 * `github_commit`：把工作区变更提交到分支。
 *
 * 会先自动比对（不传 paths 时提交全部变更），只上传真正变了的文件。
 */
class GitHubCommitTool(private val app: Application) : AgentTool {

    override val name = "github_commit"

    override val description =
        "把工作区的变更提交到 GitHub 仓库指定分支（自动只提交有变化的文件，不会删除远端已有文件）。" +
            "message 必填：第一行写清做了什么，可空一行后写正文。复用「构建」页配置的仓库与 PAT。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("message", "string", "提交信息：第一行是标题（≤72 字），可再空一行写正文"),
        JsonSchema.prop("branch", "string", "分支名，默认 main"),
        JsonSchema.prop("paths", "string", "只提交这些文件（逗号分隔的相对路径）；留空 = 全部变更")
    )

    /** 写操作：必须串行。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cfg = loadConfig(app) ?: return@withContext notConfigured()
        val message = args["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (message.isEmpty()) {
            return@withContext ToolResult.fail(
                code = "missing_param",
                message = "缺少 message",
                hint = "提交信息必填，例如「feat: 增加登录页」，让历史读得懂。"
            )
        }
        val branch = branchOf(args)
        val (files, skipped) = collectWorkspace(app, parsePaths(
            args["paths"]?.jsonPrimitive?.contentOrNull
        ))
        if (files.isEmpty()) {
            return@withContext ToolResult.fail(
                code = "empty_workspace",
                message = "工作区没有可提交的文件",
                hint = "先写入要提交的文件。"
            )
        }

        val service = GitHubGitService(cfg)
        val status = service.status(branch, files)
        val changes: List<FileChange> = status.getOrElse { e ->
            return@withContext ToolResult.fail(
                code = "github_status_failed",
                message = "提交前比对失败：${e.message?.take(200)}",
                hint = "检查 PAT 权限与分支名。"
            )
        }
        if (changes.isEmpty()) {
            return@withContext ToolResult.ok(
                code = "up_to_date",
                message = "没有变更，未创建提交",
                data = "工作区与 ${cfg.fullName}@$branch 已经一致。"
            )
        }

        val commitMessage = GitHubGitLogic.normalizeMessage(message)
        service.commit(branch, commitMessage, changes, files).fold(
            onSuccess = { sha ->
                val note = if (skipped.isEmpty()) "" else
                    "\n\n被跳过：\n" + skipped.take(10).joinToString("\n") { "- $it" }
                ToolResult.ok(
                    code = "committed",
                    message = "已提交 ${changes.size} 个文件到 ${cfg.fullName}@$branch：${sha.take(8)}",
                    data = GitHubGitLogic.summarizeChanges(changes) +
                        "\n\ncommit: $sha" + note
                )
            },
            onFailure = { e ->
                ToolResult.fail(
                    code = "commit_failed",
                    message = "提交失败：${e.message?.take(240)}",
                    hint = "常见原因：PAT 无 Contents 写权限、分支受保护、或远端在此期间有新提交（重试即可）。"
                )
            }
        )
    }
}

/** `github_log`：最近提交（读历史，判断"上次改了什么"）。 */
class GitHubLogTool(private val app: Application) : AgentTool {

    override val name = "github_log"

    override val description =
        "查看 GitHub 仓库指定分支的最近提交（sha / 标题 / 作者 / 时间）。" +
            "用于确认刚才的提交是否成功，或了解最近改动历史。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("branch", "string", "分支名，默认 main"),
        JsonSchema.prop("limit", "integer", "返回几条，默认 10，最多 30")
    )

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cfg = loadConfig(app) ?: return@withContext notConfigured()
        val branch = branchOf(args)
        val limit = args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10
        GitHubGitService(cfg).log(branch, limit).fold(
            onSuccess = { commits ->
                if (commits.isEmpty()) {
                    ToolResult.ok(code = "no_commits", message = "该分支还没有提交", data = "")
                } else {
                    val text = commits.joinToString("\n") { c ->
                        "${c.sha.take(8)}  ${c.date.take(16)}  ${c.author}  ${c.message.take(80)}"
                    }
                    ToolResult.ok(
                        code = "log_ok",
                        message = "${commits.size} 条提交（${cfg.fullName}@$branch）",
                        data = text
                    )
                }
            },
            onFailure = { e ->
                ToolResult.fail(
                    code = "github_log_failed",
                    message = "读取提交历史失败：${e.message?.take(200)}",
                    hint = "检查 PAT 权限与分支名（可能是 master）。"
                )
            }
        )
    }
}

/**
 * `github_diff`（W-34）：看某个文件相对远端分支的**行级**改动。
 *
 * `github_status` 只说"哪些文件变了"，这个工具回答"具体改了哪几行"——
 * 提交前确认、review 自己的改动都靠它。
 *
 * 边界：
 *  - 二进制文件（含 NUL）直接拒绝：diff 出来是乱码，没有意义；
 *  - 本地没有、远端有 → 说明这是删除，而提交工具不支持删除，如实告知；
 *  - 超大改动由 [LineDiffLogic] 退化为整段替换并标注。
 */
class GitHubDiffTool(private val app: Application) : AgentTool {

    override val name = "github_diff"

    override val description =
        "查看工作区某个文件相对 GitHub 分支的具体改动（行级 + 新增 / - 删除，带上下文）。" +
            "提交前用它确认自己到底改了什么。只支持文本文件。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("path", "string", "文件在工作区内的相对路径，如 src/main/App.kt"),
        JsonSchema.prop("branch", "string", "分支名，默认 main"),
        JsonSchema.prop("context", "integer", "改动前后各显示几行上下文，默认 3，最多 10")
    )

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cfg = loadConfig(app) ?: return@withContext notConfigured()
        val rawPath = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val path = GitHubGitLogic.normalizePath(rawPath)
            ?: return@withContext ToolResult.fail(
                code = "invalid_path",
                message = "路径非法：$rawPath",
                hint = "给工作区内的相对路径（不允许 .. 或绝对路径）。"
            )
        val branch = branchOf(args)
        val context = (args["context"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 3)
            .coerceIn(0, 10)

        val localFile = File(WorkspaceStore.dir(app), path)
        val localExists = localFile.isFile
        val localText: String = if (localExists) {
            if (localFile.length() > 1_048_576) {
                return@withContext ToolResult.fail(
                    code = "file_too_large",
                    message = "文件超过 1MB，不做 diff",
                    hint = "用 read_file 分段查看，或只提交不比对。"
                )
            }
            runCatching { localFile.readText() }.getOrDefault("")
        } else ""

        val remote = GitHubGitService(cfg).fileContent(branch, path).getOrElse { e ->
            return@withContext ToolResult.fail(
                code = "github_diff_failed",
                message = "读取远端失败：${e.message?.take(200)}",
                hint = "检查 PAT 权限与分支名（可能是 master）。"
            )
        }

        if (!localExists && remote == null) {
            return@withContext ToolResult.fail(
                code = "not_found",
                message = "本地和远端（${cfg.fullName}@$branch）都没有这个文件",
                hint = "确认路径拼写；新文件请先用 write_file 创建。"
            )
        }
        if (!localExists && remote != null) {
            return@withContext ToolResult.fail(
                code = "local_missing",
                message = "本地没有 $path，但远端有——这是删除操作",
                hint = "本工具不支持删除远端文件（避免误删）。需要删除请到 GitHub 网页操作。"
            )
        }

        // 二进制检测：含 NUL 就拒绝
        if (localText.contains('\u0000') || (remote?.contains('\u0000') == true)) {
            return@withContext ToolResult.fail(
                code = "binary_file",
                message = "二进制文件不支持 diff",
                hint = "只能比对文本文件。"
            )
        }

        val diff = LineDiffLogic.diff(remote.orEmpty(), localText, context)
        if (diff.isEmpty()) {
            return@withContext ToolResult.ok(
                code = "no_diff",
                message = "$path 与 ${cfg.fullName}@$branch 完全一致",
                data = "没有差异。"
            )
        }
        val body = diff.joinToString("\n") { it.render() }
        val kindNote = if (remote == null) "（远端没有该文件，这是新增）" else ""
        ToolResult.ok(
            code = "diff_ok",
            message = "$path：${LineDiffLogic.summary(diff)}$kindNote",
            data = body
        )
    }
}
