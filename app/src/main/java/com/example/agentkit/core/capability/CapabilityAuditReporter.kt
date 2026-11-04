package com.example.agentkit.core.capability

import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.AgentTool

/**
 * 能力清单自检的**唯一调用点**（P3-1 / P5-1）。
 *
 * ## 为什么从 ViewModel 搬出来
 *
 * 它原本是 `ChatViewModel` 里的一个私有函数，理由是「启动时顺手跑一下」。
 * 那个理由不成立：自检要读的是**工具注册表**与**清单表**，两样都在 core 层，
 * 与 UI 一点关系没有。留在 ViewModel 里只有两个后果——UI 文件继续膨胀，
 * 以及「想在别处（比如子代理启动、未来的 CLI）做同样的自检」时无处可调。
 *
 * ## 为什么只 warn 不抛
 *
 * 定位是**留痕不是门槛**：偏差不影响工具可用性（落进 `misc` 的工具照样能被调用），
 * 拦启动只会把一个「组名不好看」的问题升级成「App 打不开」。但省掉了
 * 「等模型开错组、再回来看为什么」那一整圈。
 *
 * 调用时机在 MCP 注册**之前**：MCP 工具是运行时动态来的，名字不受清单约束，
 * 让它们参与审计只会制造固定噪声（见 `CapabilityGroup.dynamic`）。
 */
object CapabilityAuditReporter {

    /**
     * 跑一次自检，有偏差就记一条 WARN。
     *
     * @return 问题清单（空 = 全部对得上）。返回值给 harness 与调试页用，
     *   生产调用点可以忽略——它已经在日志里了。
     */
    fun report(tools: List<AgentTool>): List<String> = runCatching {
        val audit = CapabilityManifest.audit(tools)
        if (audit.ok) return emptyList()
        AgentLogger.w(
            "Capability",
            "能力清单有 ${audit.problems.size} 处不同步：\n" + audit.problems.joinToString("\n")
        )
        audit.problems
    }.getOrDefault(emptyList())
}
