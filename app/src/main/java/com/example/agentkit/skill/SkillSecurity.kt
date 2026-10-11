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
        // —— 敏感：网络 / 通知 / 剪贴板 / 写文件 ——
        "http_request" to Capability.PRIVILEGED,
        "clipboard" to Capability.PRIVILEGED,
        "send_notification" to Capability.PRIVILEGED,
        "write_file" to Capability.PRIVILEGED,
        // —— L9 记忆库：写/删是 DANGEROUS ——
        // memory_write / memory_forget 会永久改变长期人格状态（即使技能被卸载，写入的记忆仍残留，
        // 等价于「持久投毒」）。imported 的能力上限恰好是 PRIVILEGED，若此处定 PRIVILEGED，
        // 第三方导入技能就能随意写/删记忆。因此提升为 DANGEROUS，交给全局能力门拦住 imported/remote。
        // 只读检索不改变状态，保持 SAFE。
        "memory_write" to Capability.DANGEROUS,
        "memory_forget" to Capability.DANGEROUS,
        "memory_search" to Capability.SAFE,
        "memory_list" to Capability.SAFE,
        // —— 安全：只读沙箱文件 ——
        "read_file" to Capability.SAFE,
    )

    /** 查某工具的能力分级；未知工具按 SAFE 处理（fail-safe，避免误拦内置能力演进）。 */
    fun cap(toolName: String): Capability = table[toolName] ?: Capability.SAFE

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
 * 由 skill 的 trust 字段解析执行策略（fail-safe：未知/缺省一律当 PACKAGED 可信）。
 * 这是「契约化只读」的落地：第三方 skill 即便声明了 ui_tap，只要 trust 不是 packaged，
 * 就会被 maxCapability 这道门拦在 SAFE/PRIVILEGED 之内。
 */
fun policyFor(trust: String?): SkillPolicy = when ((trust ?: "packaged").lowercase()) {
    "remote" -> SkillPolicy(allowScript = false, maxCapability = Capability.SAFE)
    "imported" -> SkillPolicy(allowScript = false, maxCapability = Capability.PRIVILEGED)
    else -> SkillPolicy(allowScript = true, maxCapability = Capability.DANGEROUS) // packaged
}

/**
 * 全局能力门：在 manifest 白名单之后二次校验。
 * @return null 表示放行；非 null 为拦截原因（直接作为工具返回文本）。
 */
fun checkCapabilityGate(toolName: String, trust: String?): String? {
    val policy = policyFor(trust)
    val cap = CapabilityRegistry.cap(toolName)
    if (cap.ordinal > policy.maxCapability.ordinal) {
        return "⛔ 能力越权：技能来源「${trust ?: "packaged"}」最多允许 ${policy.maxCapability.name} 级工具，" +
            "无权调用 $toolName（${cap.name}）。"
    }
    return null
}
