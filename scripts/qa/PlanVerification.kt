import com.example.agentkit.tools.PlanLogic

/**
 * W-25 harness：plan_propose 的解析与校验纯函数（不加载 Android）。
 *
 * 模型给的步骤列表格式很脏：带 `1.` `2)` `-` 前缀、空行、整段用分号写。
 * 这里验证清洗是否彻底、校验是否挡住退化情况（空计划、超长、步骤爆炸）。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

fun main() {
    println("=== W-25 plan_propose 解析与校验 ===")

    check("多行：去序号前缀（1. / 2) / 3、）") {
        val steps = PlanLogic.parseSteps(
            """
            1. 读取 config.json
            2) 修改 timeout 字段
            3、保存并复核
            """.trimIndent()
        )
        assertTrue("3 步", steps.size == 3)
        assertTrue("第一步", steps[0] == "读取 config.json")
        assertTrue("第二步", steps[1] == "修改 timeout 字段")
        assertTrue("第三步", steps[2] == "保存并复核")
    }

    check("项目符号：- / * / • 都去掉") {
        val steps = PlanLogic.parseSteps("- 打开设置\n* 关闭通知\n• 返回首页")
        assertTrue("3 步", steps.size == 3)
        assertTrue("无 -", steps[0] == "打开设置")
        assertTrue("无 *", steps[1] == "关闭通知")
        assertTrue("无 •", steps[2] == "返回首页")
    }

    check("单行分号分隔也能拆") {
        val steps = PlanLogic.parseSteps("读取文件；修改字段；保存")
        assertTrue("3 步", steps.size == 3)
        assertTrue("内容对", steps[1] == "修改字段")
    }

    check("空行/纯空白被丢弃") {
        val steps = PlanLogic.parseSteps("第一步\n\n   \n第二步\n")
        assertTrue("2 步", steps.size == 2)
    }

    check("重复步骤去重（保留首次顺序）") {
        val steps = PlanLogic.parseSteps("打开微信\n打开设置\n打开微信")
        assertTrue("2 步", steps.size == 2)
        assertTrue("顺序对", steps[0] == "打开微信" && steps[1] == "打开设置")
    }

    check("无前缀的纯文本原样保留") {
        val steps = PlanLogic.parseSteps("读取文件\n写入结果")
        assertTrue("2 步", steps.size == 2)
        assertTrue("原样", steps[0] == "读取文件")
    }

    check("10. 这种两位数序号也能去掉（不能只删一位）") {
        assertTrue("10.", PlanLogic.stripBullet("10. 最后一步") == "最后一步")
        assertTrue("12、", PlanLogic.stripBullet("12、收尾") == "收尾")
    }

    check("校验：正常计划通过") {
        assertTrue("正常", PlanLogic.validate("整理下载目录", listOf("列出文件", "按类型分组", "删除重复")) == null)
    }

    check("校验：空摘要 / 超长摘要 拒绝") {
        assertTrue("空摘要", PlanLogic.validate("", listOf("做点什么")) != null)
        assertTrue("空白摘要", PlanLogic.validate("   ", listOf("做点什么")) != null)
        assertTrue("超长摘要", PlanLogic.validate("啊".repeat(201), listOf("做点什么")) != null)
    }

    check("校验：空步骤 / 超过 20 步 拒绝") {
        assertTrue("空步骤", PlanLogic.validate("做点什么", emptyList()) != null)
        val many = (1..21).map { "步骤$it" }
        assertTrue("21 步拒绝", PlanLogic.validate("大任务", many) != null)
        val ok20 = (1..20).map { "步骤$it" }
        assertTrue("20 步通过", PlanLogic.validate("大任务", ok20) == null)
    }

    check("校验：单步超 200 字拒绝") {
        assertTrue("超长步骤", PlanLogic.validate("任务", listOf("啊".repeat(201))) != null)
        assertTrue("200 字通过", PlanLogic.validate("任务", listOf("啊".repeat(200))) == null)
    }

    check("空输入 → 空列表（不炸）") {
        assertTrue("空串", PlanLogic.parseSteps("").isEmpty())
        assertTrue("纯空白", PlanLogic.parseSteps("   \n  \n").isEmpty())
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
