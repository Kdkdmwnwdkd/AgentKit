import com.example.agentkit.core.agent.ApprovalGrouping

/**
 * W-36 harness：审批「同类操作」归组。
 *
 * 真机事故背景：一次授权任务里 ui_tap / ui_click / ui_key / ui_input / ui_screenshot
 * 各弹一次窗，用户点了十几次「允许」，单次等待 28~37 秒。弹窗写着「不再询问同类操作」，
 * 但实现按工具名记账，勾了也只放过那一个工具。
 *
 * 断言目标：同类动作必须归到同一组；不同风险级别不能混组（截图不能顺带放行点击）。
 */
fun main() {
    var pass = 0
    fun check(name: String, cond: Boolean) {
        if (cond) {
            pass++
        } else {
            println("FAIL: $name")
            kotlin.system.exitProcess(1)
        }
    }

    // 1. 界面点按/输入/按键/滑动 = 同一组（用户视角就是「它在动我的屏幕」）
    val uiActions = listOf("ui_tap", "ui_click", "ui_long_press", "ui_swipe", "ui_key", "ui_input")
    val uiGroups = uiActions.map { ApprovalGrouping.groupOf(it) }.distinct()
    check("界面动作同组", uiGroups.size == 1 && uiGroups[0] == ApprovalGrouping.GROUP_UI_ACTION)

    // 2. 截图独立成组：允许点击不等于允许截屏（隐私边界）
    check(
        "截图不并入界面动作",
        ApprovalGrouping.groupOf("ui_screenshot") == ApprovalGrouping.GROUP_UI_SCREENSHOT &&
            ApprovalGrouping.GROUP_UI_SCREENSHOT != ApprovalGrouping.GROUP_UI_ACTION
    )

    // 3. 应用安装/卸载/停止 = 同一组
    check(
        "应用管理同组",
        listOf("app_install", "app_uninstall", "app_stop")
            .map { ApprovalGrouping.groupOf(it) }.distinct()
            .let { it.size == 1 && it[0] == ApprovalGrouping.GROUP_APP_MANAGE }
    )

    // 4. 文件写入与 Git 提交不同组（提交是外发，风险更高）
    check(
        "写文件≠提交",
        ApprovalGrouping.groupOf("write_file") == ApprovalGrouping.groupOf("edit_file") &&
            ApprovalGrouping.groupOf("github_commit") != ApprovalGrouping.groupOf("write_file")
    )

    // 5. 未知工具保持原粒度（不因为归组把陌生工具放大权限）
    check("未知工具单独成组", ApprovalGrouping.groupOf("some_future_tool") == "some_future_tool")

    // 6. 每组都有面向用户的说明，且不为空
    val groups = listOf(
        ApprovalGrouping.GROUP_UI_ACTION,
        ApprovalGrouping.GROUP_UI_SCREENSHOT,
        ApprovalGrouping.GROUP_APP_MANAGE,
        ApprovalGrouping.GROUP_FILE_WRITE,
        ApprovalGrouping.GROUP_GIT_WRITE,
        ApprovalGrouping.GROUP_DEVICE_OUTPUT,
        ApprovalGrouping.GROUP_SHELL
    )
    check("每组有中文说明", groups.all { ApprovalGrouping.labelOf(it).isNotBlank() && it != ApprovalGrouping.labelOf(it) })

    println("KS_APPROVAL_GROUPING_OK $pass/6")
}
