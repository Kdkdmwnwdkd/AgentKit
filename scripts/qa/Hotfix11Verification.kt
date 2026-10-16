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
        // list() 自 hotfix21（P1-5 用量统计）起改为命名参数构造（顺带汇总 token），
        // 置顶键的传递断言随之更新为命名形式
        assertContains("K1", store, "pinned = rec.pinned")
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
        // hotfix22 起分享实现收敛到 ShareActions.kt，ChatScreen 只留委托（Q6 再验共用）
        assertContains("L2", chat, "private fun shareMessage(context: Context, text: String) = shareText(context, text, \"分享消息\")")
        val share = readSource("ui/ShareActions.kt")
        assertContains("L2", share, "Intent.ACTION_SEND")
        assertContains("L2", share, "Intent.createChooser")
        assertContains("L2", share, "type = \"text/plain\"")
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

/**
 * M 区：功能五 P1-2「TTS 朗读」——对标 RikkaHub ChatMessageActions(tts) /
 * Operit ChatArea onSpeakMessage；AgentKit 用系统 TextToSpeech，零新增依赖。
 */
private fun testTtsReading() {
    println("— M 区：TTS 朗读（功能五 P1-2） —")
    val chat = readSource("ui/ChatScreen.kt")

    check("M1 控制器用系统 TextToSpeech（零新增依赖）") {
        assertContains("M1", chat, "internal class TtsController(context: Context)")
        assertContains("M1", chat, "TextToSpeech(appContext)")
    }
    check("M2 异步初始化有 ready 守卫，speak 返回布尔（未就绪可提示，不静默失败）") {
        assertContains("M2", chat, "private var ready = false")
        assertContains("M2", chat, "fun speak(text: String): Boolean")
        assertContains("M2", chat, "if (!ready || text.isBlank()) return false")
    }
    check("M3 生命周期纪律：DisposableEffect 离开即 shutdown（防系统绑定泄漏）") {
        assertContains("M3", chat, "DisposableEffect(Unit) { onDispose { tts.shutdown() } }")
        assertContains("M3", chat, "fun shutdown()")
    }
    check("M4 中文优先且回落不阻断（无中文引擎也不炸）") {
        assertContains("M4", chat, "runCatching { tts?.language = Locale.CHINA }")
    }
    check("M5 朗读/停止同按钮切换，文案与颜色随状态反转") {
        assertContains("M5", chat, "if (speaking) \"停止朗读\" else \"朗读\"")
        assertContains("M5", chat, "speakingIndex == index")
    }
    check("M6 未就绪给可读提示（不是点了没反应）") {
        assertContains("M6", chat, "朗读器未就绪，请稍后再试")
    }
    check("M7 运行中不给朗读入口（避免与流式输出抢注意力）") {
        assertContains("M7", chat, "val showSpeak = !running && item.text.isNotBlank()")
    }
}

/**
 * N 区：功能五 P1-4「输入栏文件附件（文本 / PDF / DOCX）」——对标 RikkaHub
 * 多模态输入（README 明示 images, text, PDF, Docx）。此前 AgentKit 只做了图片，
 * 用户点名「别跟我说只能发照片，不能发文件」。
 */
private fun testFileAttachment() {
    println("— N 区：文件附件（功能五 P1-4） —")
    val vm = readSource("ui/ChatViewModel.kt")
    val chat = readSource("ui/ChatScreen.kt")

    check("N1 文件附件类型与结果类型齐备（三成功路径 + 一失败路径）") {
        assertContains("N1", vm, "data class PendingFile(val name: String, val text: String)")
        assertContains("N1", vm, "sealed interface FileAttachResult")
        assertContains("N1", vm, "data class Text(val name: String, val chars: Int)")
        assertContains("N1", vm, "data class Images(val name: String, val pages: Int)")
        assertContains("N1", vm, "data class Failed(val reason: String)")
    }
    check("N2 按类型分派：PDF 转图 / DOCX 抽文本 / 文本类直读 / 其它明确拒绝") {
        assertContains("N2", vm, "ext == \"pdf\" -> {")
        assertContains("N2", vm, "ext == \"docx\" -> {")
        assertContains("N2", vm, "ext in TEXT_EXTENSIONS -> {")
        assertContains("N2", vm, "暂不支持 .\$ext 文件")
    }
    check("N3 PDF 渲染：前 N 页上限 + 铺白底（透明页不铺白会成黑图）") {
        assertContains("N3", vm, "const val MAX_PDF_PAGES = 5")
        assertContains("N3", vm, "PdfRenderer(parcel)")
        assertContains("N3", vm, "bmp.eraseColor(0xFFFFFFFF.toInt())")
    }
    check("N4 DOCX 解包抽 <w:t> 文本 + 段落换行 + 实体解码（纯系统 zip，零依赖）") {
        assertContains("N4", vm, "word/document.xml")
        assertContains("N4", vm, "Regex(\"<w:t[^>]*>(.*?)</w:t>\"")
        assertContains("N4", vm, "replace(\"</w:p>\", \"\\n\")")
        assertContains("N4", vm, "decodeXmlEntities")
    }
    check("N5 发送时文件正文以「[附件：文件名]」块拼进消息（模型能引用来源）") {
        assertContains("N5", vm, "val fileBlock = files.joinToString")
        assertContains("N5", vm, "[附件：")
    }
    check("N6 文件有大小上限（防超长文件撑爆上下文）") {
        assertContains("N6", vm, "const val MAX_FILE_CHARS = 60_000")
        assertContains("N6", vm, "text.take(MAX_FILE_CHARS)")
    }
    check("N7 UI：「＋」改为「图片 / 文件」二选一（不再直通相册）") {
        assertContains("N7", chat, "private fun AttachPickerDialog(")
        assertContains("N7", chat, "onPickAttach = { showAttachMenu = true }")
        assertContains("N7", chat, "pickFile.launch(arrayOf(\"*/*\"))")
        assertContains("N7", chat, "ActivityResultContracts.OpenDocument()")
    }
    check("N8 文件 chip 行可移除，且文件名截断留出「×」位置") {
        assertContains("N8", chat, "private fun AttachedFilesRow(")
        assertContains("N8", chat, "widthIn(max = 180.dp)")
        assertContains("N8", chat, "onRemoveFile = { vm.removePendingFile(it) }")
    }
}

/**
 * O 区：功能五 P1-6「模型选择器增强」——对标 RikkaHub `ModelListSheet`
 * （收藏区 / 打开定位当前项 / 能力标签）。
 */
private fun testModelPickerEnhancements() {
    println("— O 区：模型选择器增强（功能五 P1-6） —")
    val cfg = readSource("ui/ModelConfigScreen.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val store = readSource("core/ConfigStore.kt")

    check("O1 收藏持久化：ConfigStore 存取 + 逗号分隔单键（不为一处引入 JSON）") {
        assertContains("O1", store, "fun loadFavoriteModels()")
        assertContains("O1", store, "fun saveFavoriteModels(list: List<String>)")
        assertContains("O1", store, "KEY_FAVORITE_MODELS = \"favorite_models\"")
    }
    check("O2 VM 暴露收藏流 + 切换即落盘") {
        assertContains("O2", vm, "val favoriteModels: StateFlow<List<String>>")
        assertContains("O2", vm, "fun toggleFavoriteModel(name: String)")
        assertContains("O2", vm, "configStore.saveFavoriteModels(next)")
    }
    check("O3 收藏优先排序（稳定排序，收藏组内保持端点原序）") {
        assertContains("O3", cfg, "base.sortedByDescending { it in favorites }")
    }
    check("O4 打开即定位当前模型（列表拉取完成后滚动过去）") {
        assertContains("O4", cfg, "listState.scrollToItem(idx)")
        assertContains("O4", cfg, "visible.indexOf(config.model)")
    }
    check("O5 能力标签是规则推断（新模型命名规律一致即自动命中，不发版维护）") {
        assertContains("O5", cfg, "private fun modelTags(id: String): List<String>")
        assertContains("O5", cfg, "\"视觉\"")
        assertContains("O5", cfg, "\"推理\"")
    }
    check("O6 星标与整行点击是两个独立热区（点星不会顺带选中模型）") {
        val row = cfg.substringAfter("private fun ModelPickerRow(")
        assertContains("O6", row, "onToggleFavorite")
        assertContains("O6", row, ".clickable(onClick = onPick)")
    }
    check("O7 计数文案含收藏数（用户一眼知道收藏了几个）") {
        assertContains("O7", cfg, "收藏 \${favorites.count { it in models }} 个")
    }
}

/**
 * P 区：功能五 P1-5「Token 用量统计」——对标 RikkaHub / Coomi 的用量可见性。
 *
 * 断言的链路完整性：客户端解析（三家）→ commit 事件上传（AssistantMessage.usage）
 * → 落盘（MsgRecord / 摘要）→ VM 汇总（整轮挂最终气泡 + 会话累计流）→ UI（顶部条 +
 * 气泡小字 + 抽屉行）。
 */
private fun testTokenUsage() {
    println("— P 区：Token 用量统计（功能五 P1-5） —")
    val contract = readSource("core/AgentContract.kt")
    val parsers = readSource("core/llm/TokenUsageParsers.kt")
    val openai = readSource("core/LLMClient.kt")
    val claude = readSource("core/llm/ClaudeClient.kt")
    val gemini = readSource("core/llm/GeminiClient.kt")
    val conv = readSource("core/ConversationStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val chat = readSource("ui/ChatScreen.kt")
    val scaffold = readSource("ui/AppScaffold.kt")

    check("P1 核心层 TokenUsage（输入/输出/合计 + sum 汇总）") {
        assertContains("P1", contract, "data class TokenUsage(")
        assertContains("P1", contract, "val promptTokens: Long = 0")
        assertContains("P1", contract, "val completionTokens: Long = 0")
        assertContains("P1", contract, "fun sum(usages: List<TokenUsage?>): TokenUsage")
    }
    check("P2 AssistantMessage 挂 usage 字段（默认 null，旧构造点零改动）") {
        assertContains("P2", contract, "val usage: TokenUsage? = null")
    }
    check("P3 三家 usage 纯解析函数（零依赖，harness 可断言）") {
        assertContains("P3", parsers, "fun parseOpenAiUsage(root: JsonObject): TokenUsage?")
        assertContains("P3", parsers, "fun parseClaudeUsage(root: JsonObject): TokenUsage?")
        assertContains("P3", parsers, "fun parseGeminiUsage(root: JsonObject): TokenUsage?")
        // 缺失返回 null 而非 0：「服务端没给」与「服务端说 0」是两件事
        assertContains("P3", parsers, "?: return null")
    }
    check("P4 OpenAI 非流式响应解析 usage") {
        val fn = openai.substringAfter("private fun parseResponse(text: String): AssistantMessage")
        assertContains("P4", fn, "AssistantMessage(content, toolCalls, parseOpenAiUsage(root))")
    }
    check("P5 OpenAI 流式：请求带 include_usage + 末片 usage 捕获") {
        assertContains("P5", openai, "putJsonObject(\"stream_options\") { put(\"include_usage\", true) }")
        val fn = openai.substringAfter("override fun streamChat(")
        assertContains("P5", fn, "var usage: com.example.agentkit.core.TokenUsage? = null")
        assertContains("P5", fn, "parseOpenAiUsage(obj)?.let { usage = it }")
        assertContains("P5", fn, "AssistantMessage(text.toString(), accumulator.build(), usage)")
    }
    check("P6 Claude 非流式响应解析 usage（input/output_tokens）") {
        val fn = claude.substringAfter("private fun parseResponse(text: String): AssistantMessage")
        assertContains("P6", fn, "AssistantMessage(content.toString(), toolCalls, parseClaudeUsage(root))")
    }
    check("P7 Gemini 非流式响应解析 usage（usageMetadata）") {
        val fn = gemini.substringAfter("private fun parseResponse(text: String): AssistantMessage")
        assertContains("P7", fn, "AssistantMessage(content.toString(), toolCalls, parseGeminiUsage(root))")
    }
    check("P8 落盘字段带默认值（旧 conversations.json 反序列化不炸）") {
        assertContains("P8", conv, "val promptTokens: Long = 0")
        assertContains("P8", conv, "val completionTokens: Long = 0")
        // 摘要与记录两处都有（抽屉行 + 会话记录）
        assertTrue("P8", conv.split("val promptTokens: Long = 0").size >= 3)
    }
    check("P9 映射双向保留 usage（写入 toMsgRecord / 读取 toUiItem + 轮次渲染）") {
        val write = conv.substringAfter("internal fun UiItem.toMsgRecord()")
        assertContains("P9", write, "promptTokens = usage?.promptTokens ?: 0L")
        val read = conv.substringAfter("internal fun MsgRecord.toUiItem()")
        assertContains("P9", read, "TokenUsage(promptTokens, completionTokens)")
        val turns = conv.substringAfter("internal fun ConversationRecord.toUiItems()")
        assertContains("P9", turns, "usage = if (sel.promptTokens > 0 || sel.completionTokens > 0)")
    }
    check("P10 摘要统计对全部变体求和（废弃变体也真实花过钱）") {
        val fn = conv.substringAfter("fun list(): List<ConversationSummary>")
        assertContains("P10", fn, "rec.turns.flatMap { it.variants }")
        assertContains("P10", fn, "promptTokens = usage.promptTokens")
        assertContains("P10", fn, "completionTokens = usage.completionTokens")
    }
    check("P11 VM：整轮 LLM 往返用量汇总挂最终回答（中间轮用量不丢）") {
        // 不按 outcome.onSuccess 切块——send/regenerate 两个同名块，取串会错位；
        // 直接断言 send 路径独有的三行
        assertContains("P11", vm, "val turnUsage = TokenUsage.sum(")
        assertContains("P11", vm, "val lastAnswer = history.filterIsInstance<AssistantMessage>()")
        assertContains("P11", vm, "append(msg, if (msg === lastAnswer) turnUsage else null)")
    }
    check("P12 VM：regenerate 新变体带用量 + append 支持 usage 参数") {
        assertContains("P12", vm, "private fun append(msg: ChatMessage, usage: TokenUsage? = null)")
        assertContains("P12", vm, "is AssistantMessage -> UiItem.Assistant(msg.content, usage = usage)")
        val regen = vm.substringAfter("fun regenerate()")
        assertContains("P12", regen, "usage = TokenUsage.sum(")
    }
    check("P13 VM：会话累计用量派生流（切会话随 _messages 重载自动切换）") {
        assertContains("P13", vm, "val sessionUsage: StateFlow<TokenUsage> = _messages")
        assertContains("P13", vm, "TokenUsage.sum(items.filterIsInstance<UiItem.Assistant>().mapNotNull { it.usage })")
        assertContains("P13", vm, ".stateIn(viewModelScope, SharingStarted.Eagerly, TokenUsage.EMPTY)")
    }
    check("P14 ChatScreen：会话用量条 + 气泡用量小字 + formatTokens") {
        assertContains("P14", chat, "val sessionUsage by vm.sessionUsage.collectAsStateWithLifecycle()")
        assertContains("P14", chat, "if (sessionUsage.totalTokens > 0) {")
        assertContains("P14", chat, "UsageSummaryBar(usage = sessionUsage)")
        assertContains("P14", chat, "private fun UsageSummaryBar(usage: TokenUsage)")
        assertContains("P14", chat, "internal fun formatTokens(n: Long): String")
        assertContains("P14", chat, "usage = item.usage")
        assertContains("P14", chat, "\"↑ \${formatTokens(usage.promptTokens)} · \"")
    }
    check("P15 抽屉会话行缀 tokens（有数据才显示，旧会话不摆 0）") {
        val row = scaffold.substringAfter("fun ConversationRow(")
        assertContains("P15", row, "formatTokens(conversation.promptTokens + conversation.completionTokens)")
        assertContains("P15", row, "} tok\"")
    }
}

/**
 * Q 区：功能五 P1-4a「会话导出 Markdown」——对标 RikkaHub Export.kt / Coomi 导出。
 *
 * 断言的链路完整性：纯函数序列化（core）→ VM 只读入口 → 抽屉长按菜单 →
 * 系统分享面板（与 hotfix17 消息分享共用 shareText）。
 */
private fun testConversationExport() {
    println("— Q 区：会话导出 Markdown（功能五 P1-4a） —")
    val md = readSource("core/ConversationMarkdown.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val share = readSource("ui/ShareActions.kt")
    val chat = readSource("ui/ChatScreen.kt")
    val scaffold = readSource("ui/AppScaffold.kt")

    check("Q1 core 纯函数 conversationToMarkdown（零 IO，harness 可断言）") {
        assertContains("Q1", md, "fun conversationToMarkdown(record: ConversationRecord): String")
        assertContains("Q1", md, "private const val MAX_CONTENT_CHARS = 20_000")
    }
    check("Q2 导出件自描述：标题 + 元信息头（时间/条数/token 用量）") {
        assertContains("Q2", md, "appendLine(\"# \${record.title}\")")
        assertContains("Q2", md, "导出时间：")
        assertContains("Q2", md, "消息条数：")
        assertContains("Q2", md, "Token 用量：")
    }
    check("Q3 按轮组织 + 只导选中变体 + 多变体标注（废弃变体不进导出件）") {
        assertContains("Q3", md, "## 第 \${index + 1} 轮")
        assertContains("Q3", md, "turn.variants.getOrNull(turn.selectedIndex)")
        assertContains("Q3", md, "*（变体 \${turn.selectedIndex + 1}/\${turn.variants.size}）*")
    }
    check("Q4 工具卡折叠为引用块 + 代码块，超长截断带注记") {
        assertContains("Q4", md, "> 🔧 工具 `\${rec.toolName")
        assertContains("Q4", md, "appendLine(\"```\")")
        assertContains("Q4", md, "…（已截断，原文共 \$length 字）")
    }
    check("Q5 VM 只读导出入口（空会话返空串，UI 据此不弹面板）") {
        assertContains("Q5", vm, "fun conversationMarkdown(convId: String): String")
        assertContains("Q5", vm, "if (rec.messages.isEmpty()) return \"\"")
        assertContains("Q5", vm, "import com.example.agentkit.core.conversationToMarkdown")
    }
    check("Q6 分享机制共用（消息分享与会话导出同一 shareText，不复制粘贴）") {
        assertContains("Q6", share, "internal fun shareText(context: Context, text: String, chooserTitle: String)")
        assertContains("Q6", share, "Intent.createChooser(send, chooserTitle)")
        assertContains("Q6", chat, "private fun shareMessage(context: Context, text: String) = shareText(context, text, \"分享消息\")")
    }
    check("Q7 抽屉长按菜单导出项 + AppScaffold 两处接线（宽屏/窄屏不漂移）") {
        assertContains("Q7", scaffold, "title = \"导出 Markdown\"")
        assertContains("Q7", scaffold, "onExport = { menuConvId = null; onExportConversation(id) }")
        assertContains("Q7", scaffold, "onExportConversation = { id ->")
        assertContains("Q7", scaffold, "shareText(ctx, vm.conversationMarkdown(id), \"导出会话\")")
        assertTrue("Q7", scaffold.split("onExportConversation = { id ->").size >= 3)
        assertContains("Q7", scaffold, "val ctx = LocalContext.current")
    }
}

/**
 * R 区：功能五 P1-4b「全局备份/恢复」——对标 RikkaHub Backup.kt（ZIP + manifest 信封）
 * 与 Coomi data.ts 导出；净室重写。
 *
 * 断言的链路完整性：core 数据契约 + ZIP 纯函数（版本门槛/条数校验/白名单）→
 * 三个 store 的公开重灌 API（不搬 raw prefs）→ VM 导出/恢复入口（SAF Uri）→
 * 设置页双闸门（确认对话框 + 文件选择器）。
 */
private fun testBackupRestore() {
    println("— R 区：全局备份/恢复（功能五 P1-4b） —")
    val models = readSource("core/BackupModels.kt")
    val archive = readSource("core/BackupArchive.kt")
    val convStore = readSource("core/ConversationStore.kt")
    val memStore = readSource("memory/MemoryStore.kt")
    val cfgStore = readSource("core/ConfigStore.kt")
    val vm = readSource("ui/ChatViewModel.kt")
    val settings = readSource("ui/SettingsScreen.kt")

    check("R1 core 数据契约：manifest 信封 + ConfigBackup（profilesJson 存原文，恢复与 loadProfiles 共用解码）") {
        assertContains("R1", models, "data class BackupManifest(")
        assertContains("R1", models, "val version: Int")
        assertContains("R1", models, "val conversationCount: Int")
        assertContains("R1", models, "data class ConfigBackup(")
        assertContains("R1", models, "val profilesJson: String? = null")
        assertContains("R1", models, "@Serializable")
    }
    check("R2 ZIP 纯函数：白名单 entry + 版本门槛 + 条数一致性 + zip 炸弹上限") {
        assertContains("R2", archive, "object BackupArchive")
        assertContains("R2", archive, "const val VERSION = 1")
        assertContains("R2", archive, "manifest.version > VERSION")
        assertContains("R2", archive, "entry.name in ENTRY_WHITELIST")
        assertContains("R2", archive, "MAX_TOTAL_BYTES")
        assertContains("R2", archive, "备份条目数与清单不符")
        // 纯函数零 Android 依赖：harness 断言 file 里无 android import
        assertTrue("R2", !archive.contains("import android."))
    }
    check("R3 ConversationStore 重灌：replaceAll 过 migrate（老备份件迁移不丢历史）+ 预约落盘") {
        assertContains("R3", convStore, "fun replaceAll(list: List<ConversationRecord>)")
        assertContains("R3", convStore, "items.addAll(list.map { migrate(it) })")
        assertContains("R3", convStore, "fun snapshot(): List<ConversationRecord>")
    }
    check("R4 MemoryStore 重灌：restoreAll = replaceAll + persist（恢复后进程被杀也不丢）") {
        assertContains("R4", memStore, "fun restoreAll(list: List<MemoryItem>)")
        assertContains("R4", memStore, "fun snapshot(): List<MemoryItem>")
    }
    check("R5 ConfigStore 恢复经公开 API（saveProfiles/saveFavoriteModels/media key/github，不搬 raw prefs）") {
        assertContains("R5", cfgStore, "fun restoreFrom(backup: ConfigBackup)")
        assertContains("R5", cfgStore, "fun loadAllMediaKeys(): Map<String, String>")
        assertContains("R5", cfgStore, "saveProfiles(profiles, backup.activeProfileId")
        assertContains("R5", cfgStore, "saveFavoriteModels(backup.favoriteModels)")
        assertContains("R5", cfgStore, "saveGithubConfig(")
    }
    check("R6 VM 导出入口：快照 → BackupArchive.create → SAF OutputStream，IO 全离主线程") {
        assertContains("R6", vm, "fun exportBackupTo(uri: Uri)")
        assertContains("R6", vm, "conversationStore.snapshot()")
        assertContains("R6", vm, "memoryStore.snapshot()")
        assertContains("R6", vm, "BackupArchive.create(")
        assertContains("R6", vm, "app.contentResolver.openOutputStream(uri)")
        assertContains("R6", vm, "viewModelScope.launch(Dispatchers.IO)")
    }
    check("R7 VM 恢复入口：信封校验先行（坏包不碰生产数据）→ 重灌 → UI 状态全量重载") {
        assertContains("R7", vm, "fun restoreBackupFrom(uri: Uri)")
        assertContains("R7", vm, "BackupArchive.read(bytes)")
        assertContains("R7", vm, "conversationStore.replaceAll(conversations)")
        assertContains("R7", vm, "memoryStore.restoreAll(memories)")
        assertContains("R7", vm, "configStore.restoreFrom(it)")
        assertContains("R7", vm, "loadConversation(conversationStore.ensureNotEmpty())")
        assertContains("R7", vm, "fun consumeBackupState()")
    }
    check("R8 设置页双闸门：恢复先 danger 确认对话框，再开 SAF 文件选择器") {
        assertContains("R8", settings, "ActivityResultContracts.CreateDocument(\"application/zip\")")
        assertContains("R8", settings, "ActivityResultContracts.OpenDocument()")
        assertContains("R8", settings, "var confirmRestore by remember { mutableStateOf(false) }")
        assertContains("R8", settings, "title = \"恢复备份\"")
        assertContains("R8", settings, "danger = true")
        assertContains("R8", settings, "importLauncher.launch(arrayOf(\"application/zip\"))")
        assertContains("R8", settings, "title = \"备份与恢复\"")
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
    testTtsReading()
    println()
    testFileAttachment()
    println()
    testModelPickerEnhancements()
    println()
    testTokenUsage()
    println()
    testConversationExport()
    println()
    testBackupRestore()
    println()
    println("== 汇总 ==")
    println("PASS: $passed  FAIL: ${failures.size}  TOTAL: ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED -> $it") }
        throw AssertionError("HOTFIX11_VERIFICATION: FAILED")
    }
    println("HOTFIX11_VERIFICATION: ALL PASS")
}
