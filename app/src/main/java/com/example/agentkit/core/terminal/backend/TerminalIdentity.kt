package com.example.agentkit.core.terminal.backend

/**
 * 终端「身份」维度（T07，来源：zafiro B1/B2 · libterm `TerminalIdentity`）。
 *
 * 身份 = 这条终端会话**以谁的权限**执行命令。**对外契约保留全量**
 * （User / Su / Shizuku / Ssh），为将来扩展留位；首期只有 [User] 有实现——
 * proot 容器与本机 `/system/bin/sh` 都以 App 的 uid 运行，`Su` / `Shizuku` / `Ssh`
 * 仅保留枚举位（工厂对它们返回「不可用」，**不为此引入任何依赖**）。
 *
 * ## 安全语义（身份固化）
 * 身份在**会话开辟时固化**（写入会话元数据）。
 * 后续再对同一别名传**冲突身份**时显式报错，绝不静默复用一个身份不符的会话
 * （用户以为提权了，命令其实仍以旧身份执行）——见
 * [com.example.agentkit.core.terminal.TerminalSessionPool.open]。
 */
enum class TerminalIdentity {

    /** 本机 App 用户身份（无提权）：proot 容器与 `/system/bin/sh` 均走此身份。 */
    User,

    /** root（su）：首期**只留接口不实现**，后端工厂返回不可用。 */
    Su,

    /**
     * Shizuku（ADB 级权限）。
     *
     * **仍未作为「身份」实现**：池里拿 uid 2000 的方式是「identity 用 User、候选链选到
     * shizuku / adb 后端」（见 `TerminalBackendFactory.providersFor`），而不是传本值——
     * 传它会让 `providersFor` 返回空。本值只用于对外语义与日志展示。
     */
    Shizuku,

    /** SSH 远端：首期**只留接口不实现**，后端工厂返回不可用。 */
    Ssh;

    /** 对外线格式（工具 schema 的 enum 取值）：枚举名小写。 */
    val wire: String get() = name.lowercase()

    companion object {
        /**
         * 线格式 → 枚举（大小写不敏感）。
         *
         * @return 匹配的枚举；空白或未知取值返回 null（调用方按「身份不支持」显式报错，
         *   不静默回落到 User）。
         */
        fun fromWire(s: String?): TerminalIdentity? {
            if (s.isNullOrBlank()) return null
            val t = s.trim().lowercase()
            return entries.firstOrNull { it.wire == t }
        }
    }
}
