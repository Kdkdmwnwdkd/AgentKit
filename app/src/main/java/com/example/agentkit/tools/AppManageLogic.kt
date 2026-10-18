package com.example.agentkit.tools

/**
 * 软件安装/卸载的校验与结果判定纯函数（W-24，缺口清单 #65）。
 * 无 Android 依赖，QA harness 可直接验证。
 *
 * ## 为什么"能不能卸"要单独判
 * Shizuku 的 `pm uninstall --user 0` 对**系统应用**会返回 DELETE_FAILED_INTERNAL_ERROR
 * 或 `Failure DELETE_FAILED_...`，exitCode 可能仍是 0。只看 exitCode 会误报成功。
 */
object AppManageLogic {

    /** 包名合法性：至少两段、只允许字母数字下划线点、首字符字母。 */
    fun validatePackageName(raw: String): String? {
        val pkg = raw.trim()
        if (pkg.isEmpty()) return "包名为空"
        if (!pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
            return "非法包名：$pkg（应形如 com.example.app）"
        }
        return null
    }

    /** APK 相对路径校验；返回错误文案，null 表示通过。 */
    fun validateApkPath(raw: String): String? {
        val path = raw.trim()
        if (path.isEmpty()) return "路径为空"
        if (!path.lowercase().endsWith(".apk")) return "只支持 .apk 文件"
        if (path.contains("..")) return "非法路径（不允许 ..）"
        return null
    }

    /**
     * 解析 `pm uninstall` 的输出：注意 **exitCode 不可靠**（Failure 时也可能为 0）。
     * 返回 (是否成功, 给模型的说明)。
     */
    fun interpretUninstall(exitCode: Int, stdout: String, stderr: String): Pair<Boolean, String> {
        val out = stdout.trim()
        val err = stderr.trim()
        val all = "$out\n$err"
        return when {
            out.contains("Success", ignoreCase = true) -> true to "已卸载"
            all.contains("DELETE_FAILED_INTERNAL_ERROR", ignoreCase = true) ->
                false to "系统拒绝卸载（多为系统应用或设备管理员应用）"
            all.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER", ignoreCase = true) ->
                false to "设备管理员应用无法卸载，需先在系统设置里取消设备管理"
            all.contains("not installed", ignoreCase = true) ->
                false to "该应用未安装"
            all.contains("Failure", ignoreCase = true) ->
                false to "卸载失败：${err.ifBlank { out }.take(200)}"
            exitCode != 0 ->
                false to "卸载失败（exit=$exitCode）：${err.ifBlank { out }.take(200)}"
            else -> false to "卸载结果不明（输出：${all.take(200)}）"
        }
    }
}
