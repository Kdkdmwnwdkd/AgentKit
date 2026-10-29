package com.example.agentkit.core.llm.local

/**
 * 资源档位：把「这台机器该用多大的推理参数」收拢成一张表。
 *
 * 背景：线程数、上下文上限、批大小这些参数各自散在设置里，默认值是按「平均机器」
 * 拍的（核数 −2 夹 4~6）。真机反馈反复出现同一类问题——同一份默认值，在旗舰上是
 * 保守，在 4GB 入门机上就是压死进程的那根稻草（参见 [LocalDeviceProfile] 里
 * 「4096 ctx 在 8GB 机器上就是压死进程的那一档」的注释）。
 *
 * 缺的就是这层「探测结果 → 参数」的落点：[LocalDeviceRules.advise] 已经把机型
 * 归到 [DeviceTier]，这里顺着它加一张档位表，把散参数按档收口：
 *
 *   - [ResourceTier.ECO]        入门 / 中低端：压线程、压上下文、建议安全批；
 *   - [ResourceTier.BALANCED]   中高端：维持现行默认，只封顶不抬升；
 *   - [ResourceTier.PERFORMANCE] 旗舰：不设额外限制（设置里给多少用多少）。
 *
 * 用户可以手动锁档（[LocalLlamaSetting.resourceTierLock]）：锁了就完全按档位表来，
 * 自动选档只在 AUTO 下生效。规则层是纯 Kotlin——一旦引到安卓的类，行为断言
 * 就编不过、也跑不了（与 [LocalDeviceRules] 同一条纪律）。
 */
enum class ResourceTier(val label: String) {
    ECO("省电"),
    BALANCED("均衡"),
    PERFORMANCE("性能");

    companion object {
        /** 解析用户锁档；认不出（含 null / "AUTO"）返回 null = 跟随自动选档。 */
        fun parse(text: String?): ResourceTier? =
            text?.trim()?.uppercase()?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

/** 一个档位落下来的具体参数。 */
data class ResourcePlan(
    val tier: ResourceTier,
    /** 档位线程上限。自动档只减不增（min），锁档按表直取。 */
    val threads: Int,
    /** 档位上下文上限（token）。 */
    val contextCap: Int,
    /** 是否建议开安全模式（压批）。只建议、不代开——安全模式是用户面前的东西。 */
    val suggestSafeMode: Boolean,
    /** 进日志和设置页的一句话理由。 */
    val reason: String,
) {
    /** 自动档只减不增：档位比用户设置松时，以用户设置为准。 */
    fun effectiveThreads(userThreads: Int): Int = minOf(userThreads, threads)

    /** 同上：档位上限比用户设置松时不动。 */
    fun capContext(userCtx: Int): Int = minOf(userCtx, contextCap)
}

object ResourcePlanRules {

    /**
     * 档位表本体。改这里的数字就是改产品行为，改完必须同步更新
     * scripts/qa/run-resource-plan-verification.sh 的断言。
     */
    fun specFor(tier: ResourceTier): ResourcePlan = when (tier) {
        ResourceTier.ECO ->
            ResourcePlan(
                tier = tier,
                threads = 4,
                contextCap = 4096,
                suggestSafeMode = true,
                reason = "入门 / 中低端机型：内存与散热余量小，压线程保流畅，上下文封顶 4096",
            )
        ResourceTier.BALANCED ->
            ResourcePlan(
                tier = tier,
                threads = 6,
                contextCap = 8192,
                suggestSafeMode = false,
                reason = "中高端机型：沿用现行默认口径，只对激进设置封顶",
            )
        ResourceTier.PERFORMANCE ->
            ResourcePlan(
                tier = tier,
                threads = 8,
                contextCap = 16384,
                suggestSafeMode = false,
                reason = "旗舰机型：不设额外限制，设置里给多少用多少",
            )
    }

    /** 自动选档：跟着 [DeviceAdvice.tier] 走，不重复发明机型判定。 */
    fun autoTier(advice: DeviceAdvice): ResourceTier = when (advice.tier) {
        DeviceTier.ENTRY, DeviceTier.LOW -> ResourceTier.ECO
        DeviceTier.MID -> ResourceTier.BALANCED
        DeviceTier.HIGH -> ResourceTier.PERFORMANCE
    }

    /**
     * 最终落档：锁档优先，AUTO 按画像。
     *
     * 自动档与锁档的行为差异是故意的：
     *   - AUTO 是「替不懂参数的用户兜底」，只减不增（[ResourcePlan.effectiveThreads] /
     *     [ResourcePlan.capContext] 里的 min）——用户明确调高的值不该被悄悄改掉；
     *   - 锁档是「用户点名要这档」，按表直取，E 档就是 4 线程，不含糊。
     */
    fun resolve(lock: String?, advice: DeviceAdvice): ResourcePlan {
        val locked = ResourceTier.parse(lock)
        val tier = locked ?: autoTier(advice)
        return specFor(tier)
    }
}
