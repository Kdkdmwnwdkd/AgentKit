package com.example.agentkit.core.terminal.backend

/**
 * 终端后端工厂（T07，来源：zafiro B1 · libterm `createRuntimeManager`，净室重写）。
 *
 * 职责：
 *  - **降级链** [getHighestAvailable]：proot → shizuku → 无线调试 → 本机 user
 *    （W-53 插入 ADB、W-56 插入 Shizuku 持久后端，见 [providersFor] 里的排序理由）；
 *  - [getUserPreferred]：本机 user；
 *  - [obtain]：会话池使用的按身份取后端入口；
 *  - **可用性缓存 + [clearCache]**：proot 探测要碰文件系统，缓存判定；
 *    **权限/可用性变化后调用 [clearCache] 即可让下一次判定重新探测**。
 *
 * 设计说明（与 zafiro 的差异，已在实现注释中标注）：
 *  - 缓存的是「**可用性判定**」而非后端**实例**——一个后端 = 一条独立 shell 进程，
 *    不能被多个会话共享，故 [obtain] 每次都构造**新实例**，只在探测这一步做缓存；
 *  - `Su` / `Shizuku` / `Ssh` 仍未实现：本类对它们直接返回 null（不可用），
 *    **不引入任何新依赖**（无 libsu / shizuku / ssh 库）。Shizuku 未做成后端的原因
 *    见 [TerminalBackend.KEY_SHIZUKU] 的注释。
 *
 * 零 Android 依赖：Android 探测全部经由注入的 [TerminalBackendProvider]，
 * 本类可在 JVM harness 里用假 provider 直接验证降级链与缓存语义。
 */
class TerminalBackendFactory(
    private val providers: List<TerminalBackendProvider>
) : TerminalBackendSource {

    /**
     * 可用性缓存：backendKey → 是否可用。仅在 [probe] 结果上缓存，
     * [clearCache] 后清空，下次 [obtain] 会重新探测。
     */
    private val availability = LinkedHashMap<String, Boolean>()

    /**
     * 按身份取后端。
     *
     * @param identity 声明身份；非 [TerminalIdentity.User] 一律返回 null（未实现）
     * @param preferProot true = proot 优先，不可用回落本机 user；false = 仅本机 user
     * @return 新构造的后端实例；无可用后端返回 null
     */
    override fun obtain(identity: TerminalIdentity, preferProot: Boolean): TerminalBackend? {
        // 按候选顺序逐级尝试（顺序来自 providersFor，单一真源）
        for (provider in providersFor(identity, preferProot)) {
            if (isAvailable(provider)) {
                val created = runCatching { provider.create() }.getOrNull()
                if (created != null) return created
            }
        }
        return null
    }

    /**
     * W-55：按候选顺序构造**全部**可用后端实例（供会话池「逐个 start，失败回落」）。
     *
     * 顺序与 [obtain] 严格一致（都来自 [providersFor]），只是不在第一个成功处停下——
     * 因为「能构造」不等于「能启动」（rootfs 可能刚被删、配对可能刚失效）。
     */
    override fun obtainCandidates(
        identity: TerminalIdentity,
        preferProot: Boolean
    ): List<TerminalBackend> = providersFor(identity, preferProot).mapNotNull { provider ->
        if (!isAvailable(provider)) return@mapNotNull null
        runCatching { provider.create() }.getOrNull()
    }

    /**
     * 按（身份, proot 优先）给出**有序候选提供者**，顺序与 [obtain] 完全一致（单一真源）。
     *
     * 供需要「逐级可用性快照」的上层复用（例如统一的
     * [com.example.agentkit.core.capability.shellCapabilitySnapshot]）：上层据此拿到
     * proot → user 的候选序列，再用 [isAvailable] 求值即可，**不重复实现降级顺序与探测**。
     *
     * @param identity 声明身份；非 [TerminalIdentity.User] 返回空（首期未实现）
     * @param preferProot true = `[proot, shizuku, adb, user]`；false = `[user]`
     */
    fun providersFor(identity: TerminalIdentity, preferProot: Boolean): List<TerminalBackendProvider> {
        if (identity != TerminalIdentity.User) {
            // Su / Shizuku / Ssh 首期只留接口不实现——显式返回空，绝不引入依赖
            return emptyList()
        }
        // 顺序按「能力」而非「速度」排：proot 有完整 Linux 工具链（apk/pacman）；
        // W-56 起 Shizuku 也成了池里的后端（uid 2000 + 持久 shell），走 binder、探测微秒级；
        // 无线调试同样是 uid 2000，但要不依赖第三方 App 时才有优势，故排在 Shizuku 之后；
        // 本机 user 只有 App 身份，最快的排最后——shell 工具的痛点是权限不足，不是慢。
        val order = if (preferProot) {
            listOf(
                TerminalBackend.KEY_PROOT,
                TerminalBackend.KEY_SHIZUKU,
                TerminalBackend.KEY_ADB,
                TerminalBackend.KEY_USER
            )
        } else {
            // W-57：`preferProot=false` 原先是「只给本机 user」——那时还没有 shizuku / adb 后端，
            // 这个 else 等价于「唯一选项」。现在语义应当是「**别用容器**」，而不是「只能用 App 身份」：
            // pm / settings / appops 这类命令在容器里永远做不了，跳过容器后仍该优先 uid 2000。
            listOf(
                TerminalBackend.KEY_SHIZUKU,
                TerminalBackend.KEY_ADB,
                TerminalBackend.KEY_USER
            )
        }
        return order.mapNotNull { key -> providers.firstOrNull { it.backendKey == key && it.identity == identity } }
    }

    /**
     * 最高可用后端：按 [providersFor] 的顺序取第一个能造出来的（proot → shizuku → 无线调试 → 本机 user）。
     *
     * 仅返回单条实例（供不经过会话池的直接调用点使用）。**它不做 start 试探**——
     * 「能构造」不等于「能启动」，需要带回落的场景请走 `TerminalSessionPool.open()`（W-55）。
     */
    fun getHighestAvailable(): TerminalBackend? =
        obtain(TerminalIdentity.User, preferProot = true)

    /** 用户偏好（本机 user）后端。 */
    fun getUserPreferred(): TerminalBackend? =
        obtain(TerminalIdentity.User, preferProot = false)

    /**
     * 可用性查询（带缓存）：命中缓存直接返回；未命中则 [TerminalBackendProvider.probe]
     * 一次并缓存（probe 抛异常按不可用处理，留日志由 provider 侧负责）。
     */
    fun isAvailable(provider: TerminalBackendProvider): Boolean =
        availability.getOrPut(provider.backendKey) {
            runCatching { provider.probe() }.getOrDefault(false)
        }

    /**
     * 清空可用性缓存：权限/可用性变化（如刚授予 Shizuku、刚解压 rootfs）后调用，
     * 让下一次 [obtain] / [getHighestAvailable] 重新探测。
     */
    fun clearCache() {
        availability.clear()
    }

    companion object {
        /** 默认别名（本机）/ proot 别名——**分池不串味**：两种后端各占独立别名，不互相复用。 */
        const val ALIAS_DEFAULT = "default"

        /** proot 会话的默认别名。 */
        const val ALIAS_PROOT = "proot"

        /**
         * 按后端键取默认别名：proot 用 [ALIAS_PROOT]，其余（本机 user 等）用 [ALIAS_DEFAULT]。
         * 纯函数：同样 key 两次调用结果相等。
         */
        fun defaultAliasFor(backendKey: String): String =
            if (backendKey == TerminalBackend.KEY_PROOT) ALIAS_PROOT else ALIAS_DEFAULT
    }
}
