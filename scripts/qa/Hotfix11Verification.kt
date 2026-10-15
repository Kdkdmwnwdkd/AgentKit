/**
 * hotfix11「对话页模型快速切换」QA 源码级验证 harness
 * （离线，无 JUnit，纯 main() + 手写断言；形态学 CapturePathVerification 的 C 区）。
 *
 * 背景（用户真机录屏反馈）：「保存了好多个模型，要换的时候不给换，都不知道现在用哪个」。
 * 根因：对话页无当前模型展示、无切换入口——唯一途径是深埋三层的横向档案 chip 条
 * （抽屉 → 模型配置 → 顶部切换条），多了还被横向滚动挤出屏，且切换无任何反馈。
 *
 * G 区断言（全部源码扫描，不加载 Android 类）：
 *  - G1-G5：对话页常驻「当前模型」指示条（配置名 + 模型名 · 域名）、未配置引导态；
 *  - G6-G9：切换弹窗（AkModalHost 退场 + 全档案列表 + 当前项对勾 + 点选即时生效 +
 *    Toast 反馈 + 「管理模型配置」入口）；
 *  - G10-G12：VM 侧根基——switchProfile 同步 _config（即时生效）+ 落盘（重启不丢失），
 *    hostOf 域名提取存在。
 *
 * 运行方式见 scripts/qa/run-hotfix11-verification.sh。
 */

import java.io.File

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private fun assertContains(name: String, haystack: String, needle: String) {
    if (!haystack.contains(needle)) {
        throw AssertionError("$name: 缺少 <$needle>")
    }
}

/* ------------------------------ 源码读取（纯文本扫描） ------------------------------ */

private const val APP_SRC = "/workspace/AgentKit/app/src/main/java/com/example/agentkit"

private fun readSource(rel: String): String = File(APP_SRC, rel).readText()

/* =============================== G 区：模型快速切换 =============================== */

private fun testModelQuickSwitch() {
    println("— G 区：对话页当前模型指示条 + 快速切换（hotfix11） —")
    val chat = readSource("ui/ChatScreen.kt")
    val vm = readSource("ui/ChatViewModel.kt")

    check("G1 ChatScreen 定义 ModelIndicatorBar（当前模型指示条）") {
        assertContains("G1", chat, "private fun ModelIndicatorBar(")
    }
    check("G2 指示条在 ChatScreen 主体接线（PageTopBar 之后常驻）") {
        // 主体接线点：onAction 块之后紧跟 ModelIndicatorBar(
        assertContains("G2", chat, "onAction = { vm.newConversation() }")
        val anchor = chat.indexOf("onAction = { vm.newConversation() }")
        val after = chat.substring(anchor)
        assertContains("G2", after, "ModelIndicatorBar(")
    }
    check("G3 对话页消费 profiles / activeProfileId 状态流") {
        assertContains("G3", chat, "val profiles by vm.profiles.collectAsStateWithLifecycle()")
        assertContains("G3", chat, "val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()")
    }
    check("G4 指示条展示当前配置名 + 模型名 + 域名") {
        assertContains("G4", chat, "profileName = activeProfile?.name")
        assertContains("G4", chat, "modelName = config.model")
        assertContains("G4", chat, "host = hostOf(config.baseUrl)")
    }
    check("G5 未配置态点击走引导（onOpenModel），已配置态开切换弹窗") {
        assertContains("G5", chat, "if (modelReady) showModelSwitch = true else onOpenModel()")
    }
    check("G5b 未配置文案可见（模型未配置 / 点此前往配置模型）") {
        assertContains("G5b", chat, "模型未配置")
        assertContains("G5b", chat, "点此前往配置模型")
    }
    check("G6 ModelSwitchDialog 定义 + AkModalHost 承载退场") {
        assertContains("G6", chat, "private fun ModelSwitchDialog(")
        assertContains("G6", chat, "AkModalHost(target = if (showModelSwitch) true else null)")
    }
    check("G7 点选即 vm.switchProfile 即时生效 + Toast 可见反馈") {
        assertContains("G7", chat, "vm.switchProfile(id)")
        assertContains("G7", chat, "Toast.makeText(ctx, \"已切换到「\$name」\", Toast.LENGTH_SHORT).show()")
    }
    check("G8 弹窗列出全部档案，当前项 selected 高亮 + Check 对勾") {
        assertContains("G8", chat, "items(profiles, key = { it.id })")
        assertContains("G8", chat, "selected = isActive")
        assertContains("G8", chat, "Icons.Outlined.Check")
    }
    check("G9 「管理模型配置」入口进模型配置页") {
        assertContains("G9", chat, "text = \"管理模型配置\"")
        assertContains("G9", chat, "onManage = {")
        // onManage 必须先关弹窗再跳配置页（否则弹窗退场动画期间跳页，视觉闪断）
        assertContains("G9", chat, "showModelSwitch = false")
    }
    check("G10 VM.switchProfile 同步 _config（切换即生效的根基）") {
        val fn = vm.substringAfter("fun switchProfile(id: String)")
        assertContains("G10", fn, "_config.value = target.config")
    }
    check("G11 VM.switchProfile 落盘 saveProfiles（重启不丢失）") {
        val fn = vm.substringAfter("fun switchProfile(id: String)")
        assertContains("G11", fn, "configStore.saveProfiles(_profiles.value, id)")
    }
    check("G12 hostOf 域名提取函数存在（去 scheme / 去路径）") {
        assertContains("G12", chat, "private fun hostOf(baseUrl: String): String")
        assertContains("G12", chat, "removePrefix(\"https://\")")
    }
}

fun main() {
    println("== hotfix11 模型快速切换验证 ==")
    testModelQuickSwitch()
    println()
    println("== 汇总 ==")
    println("PASS: $passed  FAIL: ${failures.size}  TOTAL: ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED -> $it") }
        throw AssertionError("HOTFIX11_VERIFICATION: FAILED")
    }
    println("HOTFIX11_VERIFICATION: ALL PASS")
}
