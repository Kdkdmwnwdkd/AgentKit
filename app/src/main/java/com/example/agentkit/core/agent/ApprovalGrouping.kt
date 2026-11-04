package com.example.agentkit.core.agent

/**
 * W-36：审批「同类操作」归组。
 *
 * 背景（真机日志）：一次「打开 Shizuku 点授权」的任务里，模型连续调用了
 * ui_tap / ui_click / ui_key / ui_input / ui_screenshot，每个工具都弹一次窗，
 * 用户被迫点了十几次「允许」，单次等待 28~37 秒，任务体验彻底崩掉。
 *
 * 根因：`sessionAllowed` 按**工具名**记账，弹窗上写的却是「本次会话不再询问**同类**操作」——
 * 勾了只放过 ui_key，ui_tap 照样再问。用户以为失效，其实是粒度错了。
 *
 * 本文件把「同类」定义成**动作语义**而非工具名：界面点按类、界面输入类、应用管理类、
 * 文件写入类、Git 写入类、设备输出类。纯函数、零 Android 依赖，可在 JVM harness 断言。
 */
object ApprovalGrouping {

    /** 归组键（持久化/比较用，不要直接展示给用户）。 */
    const val GROUP_UI_ACTION = "ui_action"
    const val GROUP_UI_SCREENSHOT = "ui_screenshot"
    const val GROUP_APP_MANAGE = "app_manage"
    const val GROUP_FILE_WRITE = "file_write"
    const val GROUP_GIT_WRITE = "git_write"
    const val GROUP_GIT_READ = "git_read"
    const val GROUP_DEVICE_OUTPUT = "device_output"
    const val GROUP_SHELL = "shell"

    /**
     * 工具名 → 归组键。未列出的工具按自身名字单独成组（保持原粒度）。
     *
     * **回退到自身名字的代价要说清**：那样 [labelOf] 只能原样返回工具名，弹窗上就会出现
     * 「本次会话不再询问 github_status」这种半句英文。所以凡是 `needsApproval = true`
     * 的工具，都该在这里有归宿，或者至少给它一个 [labelOf] 认识的中文说法。
     */
    fun groupOf(toolName: String): String = when (toolName) {
        // 屏幕操作：点、长按、滑、按键、输入、按语义点 —— 对用户是同一件事
        "ui_tap", "ui_click", "ui_long_press", "ui_swipe", "ui_key", "ui_input" -> GROUP_UI_ACTION
        // 读屏：截图与界面树拿到的是同一类东西（屏幕内容）。分成两档会让用户勾了
        // 「不再询问屏幕截图」之后，仍被 ui_tree 拦下——他自己看不出这是「同一件事」。
        "ui_screenshot", "ui_tree" -> GROUP_UI_SCREENSHOT
        "app_install", "app_uninstall", "app_stop" -> GROUP_APP_MANAGE
        "write_file", "edit_file" -> GROUP_FILE_WRITE
        "github_commit" -> GROUP_GIT_WRITE
        // 只读但要带 PAT 访远端，风险与提交不同档，别合并
        "github_status" -> GROUP_GIT_READ
        "speak", "send_notification" -> GROUP_DEVICE_OUTPUT
        "shell_run", "shell_background" -> GROUP_SHELL
        // B 批：容器运行时安装——同为「在设备上执行外部代码 / 下载写入」语义
        "container_toolchain" -> GROUP_SHELL
        else -> toolName
    }

    /** 归组键 → 弹窗里给用户看的说明（「本次会话不再询问<label>」）。 */
    fun labelOf(group: String): String = when (group) {
        GROUP_UI_ACTION -> "界面点击与输入"
        GROUP_UI_SCREENSHOT -> "屏幕截图与界面读取"
        GROUP_APP_MANAGE -> "应用安装/卸载/停止"
        GROUP_FILE_WRITE -> "工作区文件写入"
        GROUP_GIT_WRITE -> "GitHub 提交"
        GROUP_GIT_READ -> "GitHub 状态查询"
        GROUP_DEVICE_OUTPUT -> "朗读与通知"
        GROUP_SHELL -> "shell 命令"
        else -> group
    }
}
