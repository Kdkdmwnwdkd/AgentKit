package com.example.agentkit.tools

import com.example.agentkit.core.llm.primOrNull
import android.app.Application
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.job.JobLogic
import com.example.agentkit.core.job.JobRunner
import com.example.agentkit.core.job.JobStatus
import com.example.agentkit.core.job.JobStore
import com.example.agentkit.core.workspace.WorkspacePaths
import com.example.agentkit.core.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 后台命令两件套（W-33）：`shell_background` 启动、`shell_jobs` 查状态/取消。
 *
 * ## 什么时候该用
 * 会跑几十秒以上的命令（`find /`、`tar`、`du`、批量重命名、proot 里的编译）。
 * 前台 `shell_run` 会一直阻塞到命令结束——长命令会占掉整轮工具预算，还可能触发超时。
 *
 * ## 与 `shell_run` 的区别（工具描述里也写了）
 *  - 后台任务**不继承终端会话**：没有 cwd 记忆、没有 export 的环境变量、不走 proot 容器；
 *  - 只有本机 `sh`（uid = 应用）。需要容器环境的长命令只能在前台跑。
 */
class ShellBackgroundTool(
    private val app: Application,
    private val runner: JobRunner
) : AgentTool {

    override val name = "shell_background"

    override val description =
        "在后台启动一条 shell 命令，立即返回任务 id（不阻塞）。命令跑完会发通知，" +
            "用 shell_jobs 查输出。适合几十秒以上的长命令。" +
            "注意：后台任务走本机 sh，不继承 shell_run 的会话（无 cwd/环境变量/容器）。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("command", "string", "要执行的命令原文（非交互；不要用需要输入的命令）"),
        JsonSchema.prop("cwd", "string", "工作目录（工作区内的相对路径）；留空 = 工作区根")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val command = args["command"]?.primOrNull()?.contentOrNull.orEmpty()
        if (JobLogic.normalizeCommand(command) == null) {
            return@withContext ToolResult.fail(
                code = "invalid_command",
                message = "命令为空或过长",
                hint = "给一条完整的非交互命令，例如 `find . -name '*.log' | wc -l`。"
            )
        }
        val cwdArg = args["cwd"]?.primOrNull()?.contentOrNull?.trim().orEmpty()
        val root = WorkspaceStore.dir(app)
        val cwd: File = if (cwdArg.isEmpty()) {
            root
        } else {
            val rel = WorkspacePaths.safeRelPath(cwdArg)
                ?: return@withContext ToolResult.fail(
                    code = "invalid_cwd",
                    message = "工作目录非法：$cwdArg",
                    hint = "只能指定工作区内的相对路径（不允许 .. 或绝对路径）。"
                )
            val dir = File(root, rel)
            if (!dir.isDirectory) {
                return@withContext ToolResult.fail(
                    code = "cwd_not_found",
                    message = "目录不存在：$cwdArg",
                    hint = "先用 list_files 或编辑器确认目录存在。"
                )
            }
            dir
        }

        runner.start(command, cwd).fold(
            onSuccess = { job ->
                ToolResult.ok(
                    code = "job_started",
                    message = "已在后台启动 ${job.id}",
                    data = "命令：${job.command.take(200)}\n" +
                        "目录：${job.cwd}\n" +
                        "日志：${job.logPath}\n\n" +
                        "用 shell_jobs id=${job.id} 查看进度与输出；跑完会发通知。"
                )
            },
            onFailure = { e ->
                ToolResult.fail(
                    code = "job_start_failed",
                    message = "启动失败：${e.message?.take(200)}",
                    hint = "确认命令语法正确；本机 sh 不支持的命令请改用 shell_run（proot）。"
                )
            }
        )
    }
}

/**
 * `shell_jobs`：列出后台任务 / 查单个任务的输出 / 取消任务。
 */
class ShellJobsTool(
    private val store: JobStore,
    private val runner: JobRunner
) : AgentTool {

    override val name = "shell_jobs"

    override val description =
        "查看后台任务：不带 id 列出最近任务；带 id 返回状态与日志尾部。" +
            "传 cancel=true 终止某个正在运行的任务。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("id", "string", "任务 id（如 j1a2b3c）；留空 = 列出最近 5 个"),
        JsonSchema.prop("cancel", "boolean", "true = 终止该任务（需同时给 id）"),
        JsonSchema.prop("lines", "integer", "返回日志的最后多少行，默认 80，最多 500")
    )

    /** 查询为主，但 cancel 有副作用；保守标成写操作（串行执行）。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val id = args["id"]?.primOrNull()?.contentOrNull?.trim().orEmpty()
        val cancel = args["cancel"]?.primOrNull()?.contentOrNull == "true"
        val lines = args["lines"]?.primOrNull()?.contentOrNull?.toIntOrNull()
            ?: JobLogic.DEFAULT_TAIL_LINES
        val now = System.currentTimeMillis()

        if (id.isEmpty()) {
            val jobs = store.list().take(5)
            if (jobs.isEmpty()) {
                return@withContext ToolResult.ok(
                    code = "no_jobs",
                    message = "还没有后台任务",
                    data = "用 shell_background 启动一条长命令。"
                )
            }
            ToolResult.ok(
                code = "jobs_listed",
                message = "最近 ${jobs.size} 个后台任务",
                data = jobs.joinToString("\n\n") { JobLogic.summaryLine(it, now) }
            )
        } else {
            val job = store.get(id)
                ?: return@withContext ToolResult.fail(
                    code = "job_not_found",
                    message = "没有这个任务：$id",
                    hint = "不带 id 调用 shell_jobs 可以看现有任务列表。"
                )
            if (cancel) {
                if (job.finished) {
                    return@withContext ToolResult.ok(
                        code = "already_finished",
                        message = "${job.id} 已经结束（${JobLogic.statusText(job, now)}）",
                        data = ""
                    )
                }
                val ok = runner.cancel(job.id)
                val latest = store.get(job.id) ?: job
                return@withContext if (ok) {
                    ToolResult.ok(
                        code = "job_cancelled",
                        message = "已取消 ${job.id}",
                        data = JobLogic.statusText(latest, now)
                    )
                } else {
                    ToolResult.fail(
                        code = "cancel_failed",
                        message = "取消失败（任务可能刚结束）",
                        hint = "重新查询一次状态。"
                    )
                }
            }

            val log = JobLogic.tailLines(store.logText(job.id), lines)
            val status = JobLogic.statusText(job, now)
            val runningHint = if (job.status == JobStatus.RUNNING) {
                "\n\n（任务仍在运行，输出会继续增长）"
            } else {
                ""
            }
            ToolResult.ok(
                code = if (job.status == JobStatus.RUNNING) "job_running" else "job_finished",
                message = "${job.id}：$status",
                data = "命令：${job.command.take(200)}\n\n--- 输出（末尾）---\n$log$runningHint"
            )
        }
    }
}
