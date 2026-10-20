package com.example.agentkit.tools

import com.example.agentkit.core.llm.primOrNull
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/* 校验与结果判定纯函数见 AppManageLogic.kt（独立无依赖文件，harness 直接编译）。 */

/**
 * app_install（W-24）：把工作区里的 APK 交给系统安装器。
 *
 * ## 为什么不是"静默安装"
 * 静默安装需要 Shizuku 的 `pm install`，但 APK 在应用私有目录里，Shizuku 进程读不到
 * （UID 不同）；要先复制到 `/data/local/tmp` —— 那需要额外路径与清理逻辑。
 * 本批先做**零特殊权限**的 Intent 安装：调起系统安装器，用户点一次「安装」。
 * 结果如实返回「已打开安装器，等待用户确认」，**不假装已经装好**。
 *
 * 写操作，需审批。
 */
class AppInstallTool(
    private val context: Context,
    private val workspace: File
) : AgentTool {

    override val name = "app_install"
    override val description =
        "安装工作区里的 APK 文件。会打开系统安装器，需要用户点「安装」确认（不是静默安装）。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("path", "string", "工作区内 APK 的相对路径，例如 downloads/app.apk")
    )

    override val readOnly = false
    override val needsApproval = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args["path"]?.primOrNull()?.contentOrNull.orEmpty()
        AppManageLogic.validateApkPath(path)?.let {
            return@withContext ToolResult.fail(
                code = "bad_path", message = it,
                hint = "给出工作区内 .apk 文件的相对路径；先把 APK 下载到工作区再安装。"
            )
        }
        val file = runCatching {
            File(workspace, path.trim()).let { f ->
                if (f.canonicalPath.startsWith(workspace.canonicalPath)) f else null
            }
        }.getOrNull() ?: return@withContext ToolResult.fail(
            code = "bad_path", message = "非法路径：$path",
            hint = "只能安装工作区内的文件（不允许 .. 穿越）。"
        )
        if (!file.isFile) {
            return@withContext ToolResult.fail(
                code = "not_found", message = "APK 不存在：$path",
                hint = "先用 http_request 或 shell 把 APK 下载到工作区，再调用 app_install。"
            )
        }
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse {
            return@withContext ToolResult.fail(
                code = "provider_error", message = "无法为 APK 生成分享地址：${it.message}",
                hint = "把 APK 放到工作区根目录下再试；持续失败请反馈日志。"
            )
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) {
            return@withContext ToolResult.fail(
                code = "no_installer", message = "没有可用的系统安装器",
                hint = "设备可能禁用了未知来源安装；请到系统设置允许后重试。"
            )
        }
        ToolResult.ok(
            code = "installer_opened",
            message = "已打开系统安装器（${file.length() / 1024 / 1024} MB），等待用户点「安装」确认",
            data = "注意：这是系统安装器的界面，安装结果以用户操作为准；\n" +
                "装完后可用 app_list 确认是否已出现。"
        )
    }
}

/**
 * app_uninstall（W-24）：卸载应用。
 *
 * 路径：Shizuku 可用 → `pm uninstall --user 0 <pkg>`（静默）；否则调起系统卸载确认框。
 * 结果判定一律走 [AppManageLogic.interpretUninstall]（exitCode 不可靠）。
 */
class AppUninstallTool(
    private val context: Context,
    private val shellRunner: ShellCommandRunner = ShellCommandRunner.Unavailable,
    /** 本应用包名：禁止自我卸载（会把自己干掉，且用户无法在界面里恢复）。 */
    private val selfPackage: String = context.packageName
) : AgentTool {

    override val name = "app_uninstall"
    override val description =
        "卸载指定应用（包名）。有调试通道时静默卸载；否则打开系统卸载确认框，需要用户点「确定」。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("package", "string", "要卸载的应用包名，例如 com.example.app")
    )

    override val readOnly = false
    override val needsApproval = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val pkg = args["package"]?.primOrNull()?.contentOrNull.orEmpty()
        AppManageLogic.validatePackageName(pkg)?.let {
            return@withContext ToolResult.fail(
                code = "bad_package", message = it,
                hint = "用 app_list 查到准确包名（形如 com.example.app）后再卸载。"
            )
        }
        val target = pkg.trim()
        if (target == selfPackage) {
            return@withContext ToolResult.fail(
                code = "self_uninstall_refused",
                message = "不能卸载 AgentKit 自己",
                hint = "如果要移除本应用，请在系统设置 → 应用里手动卸载。"
            )
        }

        // 1. 优先静默卸载（调试通道）
        val outcome = shellRunner.exec("pm uninstall --user 0 $target")
        if (outcome != null) {
            // ShellOutcome 只有合并输出（stdout+stderr），关键词判定对合并流同样有效
            val (ok, note) = AppManageLogic.interpretUninstall(
                outcome.exitCode, outcome.output, ""
            )
            return@withContext if (ok) {
                ToolResult.ok(
                    code = "uninstalled",
                    message = "已卸载 $target（调试通道）"
                )
            } else {
                ToolResult.fail(
                    code = "uninstall_failed", message = "$target：$note",
                    hint = "若是系统/设备管理员应用则无法卸载；可改用系统设置手动处理。"
                )
            }
        }

        // 2. 降级：系统卸载确认框（用户点确定）
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$target")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) {
            return@withContext ToolResult.fail(
                code = "no_uninstaller", message = "无法打开系统卸载界面",
                hint = "请到系统设置 → 应用 → $target 手动卸载。"
            )
        }
        ToolResult.ok(
            code = "confirm_opened",
            message = "已打开系统卸载确认框，等待用户点「确定」",
            data = "卸载结果以用户操作为准；可用 app_list 确认是否已消失。"
        )
    }
}
