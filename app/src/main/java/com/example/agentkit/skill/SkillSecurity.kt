package com.example.agentkit.skill

/**
 * Skill 安全内核（L5「契约化只读」的硬约束层，净室原创）。
 *
 * 背景：原 [SkillRuntimeBridge] 只校验 skill 自己 manifest 里声明的 `tools` 白名单，
 * 等于「自己证明自己」——一个恶意 skill 只需在 skill.json 写 `"tools":["ui_tap","ui_screenshot"]`
 * 就能拿到全部高危权限（因为 registry 注册了所有工具）。本文件补一层**全局能力门**：
 * 所有工具按危险度分级，skill 按其信任等级被赋予能力上限，越权调用即使写在白名单里也被拦。
 *
 * 设计原则（思路借鉴 RikkaHub 的 skill 权限分级 + Coomi 的 tool capability，净室重写）：
 *  - 不依赖第三方 skill 的「自证白名单」，而是用应用内置的 [CapabilityRegistry] 作为唯一真相源。
 *  - 信任等级默认 PACKAGED（打包进 APK，可信全开）；IMPORTED/REMOTE 收紧，且默认禁止脚本执行。
 */

/** 工具能力分级：决定哪些工具能被哪一级信任的 skill 调用。 */
enum class Capability {
    /** 只读沙箱资源，几乎无副作用（如 read_file）。 */
    SAFE,
    /** 涉及网络/通知/剪贴板/写文件等，可能外发数据或产生副作用。 */
    PRIVILEGED,
    /** 触碰系统高危权限（无障碍操控、录屏、设备感知），极危险。 */
    DANGEROUS
}

/**
 * 工具 → 能力分级的全局唯一真相源。集中在此定义，skill 的 manifest 白名单无法改变它。
 */
object CapabilityRegistry {
    private val table: Map<String, Capability> = mapOf(
        // —— 危险：设备操控 / 感知（L1–L4 高危权限工具）——
        "ui_tree" to Capability.DANGEROUS,
        "ui_tap" to Capability.DANGEROUS,
        "ui_long_press" to Capability.DANGEROUS,
        "ui_swipe" to Capability.DANGEROUS,
        "ui_input" to Capability.DANGEROUS,
        "ui_key" to Capability.DANGEROUS,
        "ui_click" to Capability.DANGEROUS,
        "ui_screenshot" to Capability.DANGEROUS,
        "device_notifications" to Capability.DANGEROUS,
        "device_foreground_app" to Capability.DANGEROUS,
        // —— 危险：命令 / 安装 / 仓库写 ——
        "shell_run" to Capability.DANGEROUS,
        "shell_background" to Capability.DANGEROUS,
        // B 批：容器运行时安装——会下载并写入容器文件系统，语义等同「在设备上装东西」
        "container_toolchain" to Capability.DANGEROUS,
        "app_install" to Capability.DANGEROUS,
        "app_uninstall" to Capability.DANGEROUS,
        "app_stop" to Capability.DANGEROUS,
        "github_commit" to Capability.DANGEROUS,
        // —— 危险：浏览器写操作（可提交表单 / 执行页面脚本）——
        "web_click" to Capability.DANGEROUS,
        "web_type" to Capability.DANGEROUS,
        "web_eval" to Capability.DANGEROUS,
        // —— L9 记忆库：写/删是 DANGEROUS ——
        // memory_write / memory_forget 会永久改变长期人格状态（即使技能被卸载，写入的记忆仍残留，
        // 等价于「持久投毒」）。imported 的能力上限恰好是 PRIVILEGED，若此处定 PRIVILEGED，
        // 第三方导入技能就能随意写/删记忆。因此提升为 DANGEROUS，交给全局能力门拦住 imported/remote。
        // 只读检索不改变状态，保持 SAFE。
        "memory_write" to Capability.DANGEROUS,
        "memory_forget" to Capability.DANGEROUS,
        "memory_search" to Capability.SAFE,
        "memory_list" to Capability.SAFE,
        // —— 敏感：网络 / 通知 / 剪贴板 / 写文件 / 应用启动 ——
        "http_request" to Capability.PRIVILEGED,
        "clipboard" to Capability.PRIVILEGED,
        "send_notification" to Capability.PRIVILEGED,
        "write_file" to Capability.PRIVILEGED,
        "edit_file" to Capability.PRIVILEGED,
        "app_launch" to Capability.PRIVILEGED,
        "github_clone" to Capability.PRIVILEGED,
        "ocr_image" to Capability.PRIVILEGED,
        "speak" to Capability.PRIVILEGED,
        "todo_write" to Capability.PRIVILEGED,
        "use_skill" to Capability.PRIVILEGED,
        "spawn_agents" to Capability.PRIVILEGED,
        "shell_close" to Capability.PRIVILEGED,
        "web_open" to Capability.PRIVILEGED,
        "web_search" to Capability.PRIVILEGED,
        "web_snapshot" to Capability.PRIVILEGED,
        "workflow_create" to Capability.PRIVILEGED,
        "workflow_delete" to Capability.PRIVILEGED,
        "workflow_toggle" to Capability.PRIVILEGED,
        // —— 安全：只读 / 本地纯计算 / 咨询 ——
        "read_file" to Capability.SAFE,
        "app_list" to Capability.SAFE,
        "ask_user" to Capability.SAFE,
        "calculator" to Capability.SAFE,
        "conversation_search" to Capability.SAFE,
        "github_status" to Capability.SAFE,
        "github_log" to Capability.SAFE,
        "github_diff" to Capability.SAFE,
        "load_skill" to Capability.SAFE,
        "plan_propose" to Capability.SAFE,
        "shell_jobs" to Capability.SAFE,
        "todo_read" to Capability.SAFE,
        "workflow_list" to Capability.SAFE,
    )

    /**
     * 查某工具的能力分级。
     *
     * **未知工具按 DANGEROUS 处理**：这是安全侧默认（fail-closed）。
     * 旧实现按 SAFE 处理，等于「新工具只要忘了登记就自动放行」——`shell_run` /
     * `edit_file` / `github_commit` 都曾因此被 imported 技能当成安全工具。
     * 新增工具必须显式登记到 [table]，harness 会扫描全部工具名守住这条。
     */
    fun cap(toolName: String): Capability = table[toolName] ?: Capability.DANGEROUS

    /** 返回全部已知危险工具名（供 UI / 审计展示）。 */
    fun dangerousTools(): List<String> = table.filterValues { it == Capability.DANGEROUS }.keys.toList()
}

/** Skill 信任等级：来源越不可控，权限越收紧。 */
enum class TrustLevel {
    /** 打包进 APK，由开发者审计过，默认可信（允许脚本 + 全部能力）。 */
    PACKAGED,
    /** 用户/生态导入到本地（未来支持），需显式授权才允许脚本。 */
    IMPORTED,
    /** 远程/第三方动态加载（如 CocoLoop），默认只给提示词形态 + 安全能力。 */
    REMOTE
}

/** 针对某信任等级的执行策略。 */
data class SkillPolicy(
    /** 是否允许执行脚本（main.js）。false 时强制回退 prompt 形态。 */
    val allowScript: Boolean,
    /** 允许调用的工具能力上限；高于此级的工具一律拦截。 */
    val maxCapability: Capability
)

/**
 * 由 skill 的 trust 字段解析执行策略。
 *
 * **B 批：未知/缺省 trust 一律按最严处理（fail-closed）。**
 *
 * 旧实现是 `else -> packaged`——只有精确命中 "remote"/"imported" 才收紧，
 * 任何其它字符串（含拼错的 "Packaged"、含空格的 " packaged"、manifest 里
 * 手写的 "trusted"）都落进 else，拿到**最高信任**：允许执行脚本 +
 * DANGEROUS 级工具全部放行。这与 A 批能力表 fail-closed 的原则直接矛盾——
 * 攻击者只要让 trust 字段「不是那两个精确值」，就自动拿到最宽权限。
 *
 * 现在改为**显式白名单**：只有精确的 "packaged" 才给最高信任；
 * "imported"/"remote" 按来源收紧；其余（含 null、空串、未知值）按
 * [TrustLevel.REMOTE] 处理——最严的一档。
 *
 * 大小写与首尾空格**归一化后再比对**（`trim().lowercase()`）：trust 是
 * manifest 里的自由文本，"Packaged" / "PACKAGED" 表达的显然是同一个意图，
 * 不该因为大小写不同就被降级——那会逼着 skill 作者写出精确小写，
 * 也让「未知值从严」这条规则被大小写噪声淹没。
 *
 * 为什么 null 也要收紧：`SkillManifest.trust` 的默认值是 ""，
 * 但 manifest 是可被构造/反序列化的对象，缺省值不能当作「已审计」的证据。
 * 真正的 packaged 技能由 [com.example.agentkit.skill.SkillImportRepository]
 * 依据**本地授权记录**写入（id 在 authorizedIds 里才写 packaged），
 * 以及 assets 内置技能在 [com.example.agentkit.skill.SkillLoader] 显式标注。
 */
fun policyFor(trust: String?): SkillPolicy = when (trust?.trim()?.lowercase()) {
    "packaged" -> SkillPolicy(allowScript = true, maxCapability = Capability.DANGEROUS)
    "imported" -> SkillPolicy(allowScript = false, maxCapability = Capability.PRIVILEGED)
    "remote" -> SkillPolicy(allowScript = false, maxCapability = Capability.SAFE)
    // 未知 / 缺省 / 空串：最严一档（与 remote 同级），并显式标注为未知
    else -> SkillPolicy(allowScript = false, maxCapability = Capability.SAFE)
}

/** 供 UI / 日志展示的信任等级标签：未知值不再伪装成 packaged。 */
fun trustLabel(trust: String?): String = when (trust?.trim()?.lowercase()) {
    "packaged" -> "打包可信"
    "imported" -> "本地导入"
    "remote" -> "远程来源"
    else -> "来源未知（按最严策略处理）"
}

/**
 * 全局能力门：在 manifest 白名单之后二次校验。
 * @return null 表示放行；非 null 为拦截原因（直接作为工具返回文本）。
 */
fun checkCapabilityGate(toolName: String, trust: String?): String? {
    val policy = policyFor(trust)
    val cap = CapabilityRegistry.cap(toolName)
    if (cap.ordinal > policy.maxCapability.ordinal) {
        return "⛔ 能力越权：技能来源「${trustLabel(trust)}」最多允许 ${policy.maxCapability.name} 级工具，" +
            "无权调用 $toolName（${cap.name}）。"
    }
    return null
}

/**
 * 技能 manifest 的工具白名单判定（A 批新增，fail-closed）。
 *
 * 旧实现是 `if (skill.tools.isNotEmpty() && name !in skill.tools) 拒绝`——
 * 白名单为空等于「不限制」，一个不写 tools 的恶意包反而能调用全部工具。
 * 现在空表只允许「不调用任何工具」（提示词形态仍可正常工作）；
 * 需要全量的技能必须显式写 `["*"]`，并在安装/授权时单独提示。
 */
fun isToolAllowedByWhitelist(declared: List<String>, toolName: String): Boolean =
    declared.contains("*") || toolName in declared
