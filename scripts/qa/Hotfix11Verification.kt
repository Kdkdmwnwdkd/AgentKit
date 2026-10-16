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
        assertContains("G12", chat, "internal fun hostOf(baseUrl: String): String")
        assertContains("G12", chat, "removePrefix(\"https://\")")
    }
}

/**
 * H 区：功能四 P0-1「消息一键复制」——四家参照（RikkaHub ChatMessageActions /
 * Operit ChatArea）均把「复制」作为消息级一等操作；AgentKit 此前只有
 * SelectionContainer（需手动选字），气泡上没有复制入口。
 */
private fun testMessageCopy() {
    println("— H 区：消息一键复制（功能四 P0-1） —")
    val chat = readSource("ui/ChatScreen.kt")

    check("H1 助手气泡操作行带复制入口（showCopy 参数贯通）") {
        assertContains("H1", chat, "showCopy = showCopy")
        assertContains("H1", chat, "onCopy = { copyMessage(ctx, item.text) }")
    }
    check("H2 助手行复制位次在「重新生成」之前（对齐 RikkaHub 顺序）") {
        val block = chat.substringAfter("private fun AssistantActions(")
        val copyAt = block.indexOf("text = \"复制\"")
        val regenAt = block.indexOf("text = \"重新生成\"")
        assertTrue(
            "H2 复制应排在重新生成之前（copy=$copyAt regen=$regenAt）",
            copyAt >= 0 && regenAt > copyAt
        )
    }
    check("H3 用户气泡操作行（UserMessageActions）带复制 + 重新发问") {
        assertContains("H3", chat, "private fun UserMessageActions(")
        assertContains("H3", chat, "UserMessageActions(")
        assertContains("H3", chat, "onCopy = { copyMessage(ctx, item.text) }")
    }
    check("H4 复制工具函数：写剪贴板 + Toast 反馈（静默成功不可接受）") {
        assertContains("H4", chat, "private fun copyMessage(context: Context, text: String)")
        assertContains("H4", chat, "ClipData.newPlainText")
        assertContains("H4", chat, "Toast.makeText")
    }
    check("H5 运行中隐藏操作行（避免点到旧轮）") {
        assertContains("H5", chat, "val showCopy = !running")
        assertContains("H5", chat, "if (item is UiItem.User && !running)")
    }
}

/**
 * I 区：功能四 P0-2「输入栏图片附件」——多模态请求侧早已支持
 * （LLMClient.toApiMessage: UserMessage.images → image_url part），缺的是输入端通路。
 */
private fun testImageAttachment() {
    println("— I 区：输入栏图片附件（功能四 P0-2） —")
    val chat = readSource("ui/ChatScreen.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val llm = readSource("core/LLMClient.kt")

    check("I1 待发图片状态流存在（pendingImages）") {
        assertContains("I1", vm, "val pendingImages: StateFlow<List<String>>")
    }
    check("I2 选图落盘走 IO 线程（不在主线程编码）") {
        assertContains("I2", vm, "suspend fun attachImageFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO)")
    }
    check("I3 附件压缩：长边 ≤ 1024 + JPEG 质量 80（控制请求体积）") {
        assertContains("I3", vm, "const val MAX_EDGE = 1024")
        assertContains("I3", vm, "Bitmap.CompressFormat.JPEG, 80")
    }
    check("I4 输入栏渲染缩略图行 + 可移除（UI 在 ChatScreen，API 在 ViewModel）") {
        assertContains("I4", chat, "AttachedImagesRow(images, onRemoveImage)")
        assertContains("I4", vm, "fun removePendingImage(url: String)")
    }
    check("I5 发送时带走附件并清空输入区") {
        val body = vm.substringAfter("fun send()")
        assertContains("I5", body, "val images = _pendingImages.value")
        assertContains("I5", body, "_pendingImages.value = emptyList()")
        assertContains("I5", body, "loop.run(fullInput, images)")
    }
    check("I6 多模态请求侧原本就支持（本次不是新造轮子）") {
        assertContains("I6", llm, "put(\"type\", \"image_url\")")
    }
}

/**
 * J 区：功能四 P0-3「提供商列表一级入口」——对标 Coomi `ProvidersView.vue` /
 * RikkaHub `SettingPage`：把所有配置档案列成可点选的清单，当前生效项高亮，
 * 点一下即切换生效；AgentKit 此前只有一行横向芯片条（多档案被挤出屏幕）。
 */
private fun testProviderListEntry() {
    println("— J 区：提供商列表一级入口（功能四 P0-3） —")
    val cfg = readSource("ui/ModelConfigScreen.kt")

    check("J1 模型配置页顶部有 ProviderPickerSection 区块") {
        assertContains("J1", cfg, "ProviderPickerSection(")
    }
    check("J2 列表列出全部档案，当前项 selected 高亮 + 对勾") {
        assertContains("J2", cfg, "profiles.forEach { profile ->")
        assertContains("J2", cfg, "selected = isActive")
        assertContains("J2", cfg, "Icons.Outlined.Check")
    }
    check("J3 行显示「模型名 · 域名」，未设置时给可读兜底") {
        assertContains("J3", cfg, "未设置模型名")
        assertContains("J3", cfg, "hostOf(profile.config.baseUrl)")
    }
    check("J4 点选即 switchProfile 生效 + Toast 反馈") {
        val body = cfg.substringAfter("ProviderPickerSection(")
        assertContains("J4", body, "vm.switchProfile(id)")
        assertContains("J4", body, "Toast.makeText")
    }
    check("J5 新建提供商入口（VM 自动命名兜底）") {
        assertContains("J5", cfg, "＋ 新建提供商")
        assertContains("J5", cfg, "vm.addProfile(null)")
    }
}

/**
 * K 区：功能五 P1-3「会话置顶 + 会话搜索」——对标 RikkaHub `ConversationList.kt`
 * （pin/unpin 菜单 + pinned_chats 分组）与 HistoryPage 搜索。
 */
private fun testConversationPinAndSearch() {
    println("— K 区：会话置顶 + 会话搜索（功能五 P1-3） —")
    val store = readSource("core/ConversationStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val scaffold = readSource("ui/AppScaffold.kt")

    check("K1 存储层：Record/Summary 带 pinned 字段（默认 false，旧文件兼容）") {
        assertContains("K1", store, "val pinned: Boolean = false")
        assertContains("K1", store, "it.messages.size, it.pinned")
    }
    check("K2 list() 排序契约：置顶优先 → 组内按时间倒序") {
        assertContains("K2", store, "compareByDescending<ConversationSummary> { it.pinned }")
    }
    check("K3 setPinned 只改排序键，不动 updatedAt（防伪装成刚聊过）") {
        val fn = store.substringAfter("fun setPinned(")
        assertContains("K3", fn, "copy(pinned = pinned)")
        assertTrue(
            "K3 不应更新 updatedAt",
            !fn.substringBefore("schedulePersist").contains("updatedAt =")
        )
    }
    check("K4 VM 暴露 setConversationPinned 并刷新摘要列表") {
        val fn = vm.substringAfter("fun setConversationPinned(")
        assertContains("K4", fn, "conversationStore.setPinned(id, pinned)")
        assertContains("K4", fn, "refreshConversations()")
    }
    check("K5 抽屉搜索：关键词过滤全部会话（不受最近 8 条上限）") {
        assertContains("K5", scaffold, "var convQuery by remember")
        assertContains("K5", scaffold, "conversations.filter { it.title.contains(convQuery.trim(), ignoreCase = true) }")
        assertContains("K5", scaffold, "没有匹配的对话")
    }
    check("K6 长按菜单有置顶项，文案随状态反转 + 说明后果") {
        assertContains("K6", scaffold, "onTogglePin = {")
        assertContains("K6", scaffold, "if (pinned) \"取消置顶\" else \"置顶对话\"")
        assertContains("K6", scaffold, "onSetPinned(id, conv?.pinned != true)")
    }
    check("K7 会话行有置顶可视标记（实心书签）") {
        assertContains("K7", scaffold, "if (conversation.pinned)")
        assertContains("K7", scaffold, "Icons.Filled.Bookmark")
    }
}

/**
 * L 区：功能五 P1-1「消息更多操作（分享 / 从此处删除）」——对标 RikkaHub
 * MessageSheetAction（Share/Delete）与 Operit ChatArea 消息菜单。
 */
private fun testMessageMoreActions() {
    println("— L 区：消息更多操作（功能五 P1-1） —")
    val chat = readSource("ui/ChatScreen.kt")
    val vm = readSource("ui/ChatViewModel.kt")

    check("L1 操作行有「⋯」更多入口（助手/用户两行都有）") {
        assertContains("L1", chat, "showMore = showMore")
        assertContains("L1", chat, "text = \"⋯\"")
        assertContains("L1", chat, "onMore = { menuTarget = item.text to item.turnIndex }")
    }
    check("L2 分享走系统分享面板（ACTION_SEND + createChooser，零额外权限）") {
        assertContains("L2", chat, "private fun shareMessage(context: Context, text: String)")
        assertContains("L2", chat, "Intent.ACTION_SEND")
        assertContains("L2", chat, "Intent.createChooser")
    }
    check("L3 消息操作弹窗存在，删除项文案写明「及之后的全部内容」") {
        assertContains("L3", chat, "private fun MessageActionsDialog(")
        assertContains("L3", chat, "删除这条及之后的全部内容")
    }
    check("L4 「从此处删除」有 danger 二次确认（不可逆操作统一拦截）") {
        assertContains("L4", chat, "title = \"从此处删除\"")
        assertContains("L4", chat, "danger = true")
    }
    check("L5 VM deleteFromTurn：截断语义（保留到上一轮），非删单条") {
        val fn = vm.substringAfter("fun deleteFromTurn(")
        assertContains("L5", fn, "conversationStore.truncateAfter(_activeConversationId.value, turnIndex - 1)")
        assertContains("L5", fn, "refreshConversations()")
    }
    check("L6 VM 删除有运行中守卫（在跑的时候不许改历史）") {
        val fn = vm.substringAfter("fun deleteFromTurn(")
        assertTrue("L6 应有 isRunning 守卫", fn.substringBefore("truncateAfter").contains("isRunning.value"))
    }
    check("L7 草稿/失败气泡不给更多入口（无轮次可操作）") {
        assertContains("L7", chat, "val showMore = !running && item.turnIndex >= 0")
    }
}

fun main() {
    println("== hotfix11 模型快速切换 + 功能四/五 验证 ==")
    testModelQuickSwitch()
    println()
    testMessageCopy()
    println()
    testImageAttachment()
    println()
    testProviderListEntry()
    println()
    testConversationPinAndSearch()
    println()
    testMessageMoreActions()
    println()
    println("== 汇总 ==")
    println("PASS: $passed  FAIL: ${failures.size}  TOTAL: ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED -> $it") }
        throw AssertionError("HOTFIX11_VERIFICATION: FAILED")
    }
    println("HOTFIX11_VERIFICATION: ALL PASS")
}
