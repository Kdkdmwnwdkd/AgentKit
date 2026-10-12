# 05 · MCP 客户端架构

> 对应九步顺序的第 7 步。目标：让 AgentKit 能挂载任意 [MCP](https://modelcontextprotocol.io) server，把"工具生态"从内置的几个扩展到整个社区——这是能力边界的数量级跃升，且**官方 SDK 是 MIT，可深度使用**。

## 参考项目

| 项目 | 许可证 | 参考什么 | 可用度 |
|---|---|---|---|
| modelcontextprotocol/kotlin-sdk（★1467） | **MIT** | MCP 的 Kotlin 多平台官方实现 | ✅ 直接依赖 |
| AAswordman/Operit | LGPL-3.0 | MCP 模块与 Agent 工具链的衔接方式 | ❌ 架构参考 |
| TensorHub-ORG/Coomi | Apache-2.0 | SKILL/MCP 生态的用户引导设计 | ⚠️ 交互思路可鉴 |
| niki914/zafiro | MIT | 同类应用中的 MCP 集成形态 | ✅ 可深度参考 |

## 它怎么做的（架构思想）

### MCP 协议本质

MCP = **JSON-RPC 2.0 的会话协议**，客户端/服务器对等，三种原语：

- **Tools**：`tools/list` 发现、`tools/call` 执行——对 AgentKit 就是「动态工具」
- **Resources**：`resources/list` / `resources/read`——文件式上下文（日志、数据库内容）
- **Prompts**：`prompts/list` / `prompts/get`——可复用的提示词模板

传输两种：**stdio**（ spawn 子进程，stdin/stdout 走换行分隔 JSON）与 **Streamable HTTP / SSE**（远端 server）。自用场景 **stdio 是主战场**：`npx -y @modelcontextprotocol/server-filesystem /path` 这样的命令直接在容器/终端里跑——**03 号文档的容器是 MCP stdio server 的天然宿主**，两条线在这里汇合。

### Operit 的 MCP 结构（架构参考）

- `mcp/` 包独立成模块，配置（server 名 + 启动命令 + 环境变量）落 DataStore
- 每个 server 一个 Manager：负责 initialize 握手、能力协商、崩溃重连
- **工具桥接层**：MCP 的 tool 描述（JSON Schema）转成 Agent 的 `AgentTool`，注入 ToolRegistry——**Agent 无感知**，就像调内置工具一样调远程工具
- **权限收口**：MCP server 本质是任意代码执行，首次连接必须弹用户确认，工具调用走现有审批流

### kotlin-sdk（MIT，直接用）

官方 Kotlin SDK 提供 Client 实现 + stdio/SSE transport，Ktor 底座。我们**只用它的 client 侧**：`Client(info)` → `connect(StdioClientTransport(process))` → `listTools()` / `callTool()`。协议细节（初始化握手、能力协商、分页）全部交给 SDK，自己不碰 JSON-RPC 帧。

## 我们的实现

### 模块位置

```
mcp/
├── McpServerConfig.kt      // @Serializable：name/command/args/env/transport/trusted
├── McpManager.kt           // 单例：server 生命周期（连接/重连/断开）
├── McpToolBridge.kt        // MCP tool → AgentTool 桥接（核心）
└── ui/McpScreen.kt         // 抽屉新目的地：server 列表/添加/日志
```

### 配置模型

```kotlin
@Serializable data class McpServerConfig(
    val name: String,                        // "filesystem" / "github" ...
    val transport: Transport = Transport.STDIO,
    val command: String,                     // "npx" / "node" / "/data/data/<pkg>/files/bin/proot"...
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val trusted: Boolean = false             // 用户确认过才为 true
)
```

### McpToolBridge（桥接设计，本步的核心）

```kotlin
class McpToolBridge(
    private val serverName: String,
    private val client: Client
) : AgentTool {
    override val name: String get() = "mcp_${serverName}_${toolDef.name}"
    override val description: String get() = toolDef.description ?: ""
    override val parameters: ToolSchema get() = toolDef.inputSchema.toToolSchema()

    override suspend fun execute(args: JsonObject): ToolResult = try {
        val result = client.callTool(name = toolDef.name, arguments = args)
        ToolResult.success(result.content.joinToString("\n") { it.toString() })
    } catch (e: Exception) {
        ToolResult.error("MCP 工具执行失败：${e.message}")
    }
}
```

桥接规则：

1. **命名空间隔离**：`mcp_<server>_<tool>`，防与内置工具重名（内置工具保持裸名，兼容现有系统提示词）
2. **Schema 转换**：MCP 的 JSON Schema 直接映射现有 `ToolSchema`（`properties/required/enum` 已有现成结构，`BuiltInTools.kt` 用的就是它）
3. **结果扁平化**：MCP 的 content block 数组 → 字符串；图片 block 转 `image_url` data URI 喂回多模态通道（现有 `UserMessage.images` 通道直接复用）
4. **失败不扩散**：单个 MCP 工具异常只让该次调用失败，**不拖垮 AgentLoop**（`AgentLoop.kt` 的工具异常处理已有容错，确认它 catch 范围覆盖）

### 生命周期与现有架构的衔接

- `McpManager` 单例持 `Map<String, Client>`；ChatViewModel **每轮对话前**调 `mcpManager.activeTools()` 拿到全部桥接工具，拼进现有 `tools: List<AgentTool>` 参数——**AgentLoop 签名零改动**
- 连接时机：App 启动时连 trusted 的 server；未 trusted 的不自动连，McpScreen 里点「连接」弹确认框（说明"该 server 可执行任意命令"）后才连
- 崩溃重连：读进程退出码，非零退出 → 通知 McpScreen 红点，**不自动重启**（自用优先，避免惊动）
- 容器联动：server 的 `command` 支持写 `proot ... /bin/sh -c "node server.js"`，即 MCP server 跑在 03 的容器里——McpScreen 的命令编辑框给「容器内运行」快捷模板

### 与 SKILL 系统的关系（重要边界）

现有 `skill/` 包（SkillProtocol/SkillExecutor）是**自家 SKILL 协议**（JSON 声明 + Kotlin 脚本 + 安全分级）。MCP 接入后两者定位：

| | 自家 SKILL | MCP |
|---|---|---|
| 分发 | App 内置 + 本地导入 | 命令/URL 现场拉 |
| 安全模型 | SkillSecurity 分级（已有） | 用户信任确认 |
| 执行 | Kotlin 脚本沙箱 | 任意代码（诚实告知） |
| 定位 | 受控能力 | 生态扩展 |

**不合并、不互转**：两套并存，Agent 同时看到两类工具。UI 上 SKILL 走现有 SkillsScreen，MCP 走新 McpScreen。

## 注意事项

1. **SDK 体积**：kotlin-sdk 带 Ktor，方法数涨一波。**只引 client 模块**，若 SDK 分模块发布就只依赖 client + serialization；否则评估把协议层按规范自写（200 行的 JSON-RPC 帧处理，作为 plan B）。
2. **npx 在 Android 不存在**：大部分官方 MCP server 的示例命令是 `npx`/`uvx`。真实路径是**容器里跑 node/python**（03 落地后），所以 MCP 这步**必须排在第 4、5 步之后**——九步顺序里它在第 7 位就是这个原因。
3. **stdio 传输的进程模型**：server 子进程的 stdin/stdout 是管道，**stderr 必须重定向到日志文件**，否则 server 的调试输出会污染协议流（JSON-RPC 就崩在这）。
4. **工具数量爆炸**：挂 5 个 server 可能涌现 50+ 工具，全塞进系统提示词会爆 context。做**按需披露**：默认只注入工具名+一句话描述，Agent 用 `describe_tool` 元工具查详情（或简单点：单轮最多注入 20 个，轮换）。
5. **超时**：MCP 调用可能几分钟（server 下载模型等）。`callTool` 给 5 分钟超时 + 进度通知（MCP 协议有 progress 通知，SDK 支持的话透传到 ChatScreen 的工具卡）。
6. **安全文案**：连接确认弹窗必须直白——"该服务器可以读取你的文件并执行命令，只连接你信任的服务器"。这是自用应用的底线，也是将来开源的颜面。
