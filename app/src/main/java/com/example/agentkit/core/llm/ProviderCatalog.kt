package com.example.agentkit.core.llm

/**
 * hotfix34：供应商目录（全量适配，数据驱动）。
 *
 * 需求原话：「你问我哪家的模型，为什么一定要哪家的？你不能说全部都弄吗？
 * 现在大市面上比较出名的都弄一下，或者说全部适配一下，你只要适配一个模型
 * 的话，那怎么行」。
 *
 * 设计要点（净室重写，零新增依赖）：
 *  1. **协议只分三类**：`openai`（含全部 OpenAI 兼容端点，占市面绝大多数）、
 *     `claude`、`gemini`。任何新供应商只要归入某一类，[ProviderManager] 自动
 *     就能造出可用的客户端——**新增供应商 = 加一条数据，零代码**。
 *  2. **未收录的 id 一律按 openai 兼容兜底**（[styleOf]）——用户填自定义端点、
 *     或将来出现的新厂，都会被正确当作 OpenAI 兼容处理，不需要发版适配。
 *  3. 目录只是**预填默认值**（baseUrl / 默认模型 / 常用模型清单），全部可在
 *     模型页修改——供应商改域名、换模型名都不会把用户卡死。
 *  4. 本地部署（Ollama / LM Studio / vLLM / llama.cpp）也是 OpenAI 兼容，
 *     标 [requiresApiKey] = false：Key 留空不再触发「未填 Key」拦截。
 *
 * 收录标准：国内一线厂 + 海外一线厂 + 主流聚合/推理平台 + 本地运行时 +
 * 三家原生协议官方端点。共 30+ 家（见 [all]）。
 */
data class LlmProvider(
    /** 稳定 id（落盘进配置，不可随意改；老 id 由 [ProviderCatalog] 保证仍可解析）。 */
    val id: String,
    /** 界面显示名。 */
    val label: String,
    /** 分组（UI 分段展示用）。 */
    val group: ProviderGroup,
    /** 协议风格：openai / claude / gemini / local-llama（决定用哪个客户端解析）。 */
    val style: String,
    /** 默认 Base URL（可被用户覆盖）。 */
    val defaultBaseUrl: String,
    /** 默认模型名（可被用户覆盖）。 */
    val defaultModel: String,
    /** 常用模型清单（模型选择弹窗的预置兜底 + 提示）。 */
    val popularModels: List<String> = emptyList(),
    /** 是否必须填 API Key（本地部署为 false）。 */
    val requiresApiKey: Boolean = true,
    /** 一句话说明（UI 辅助文案）。 */
    val note: String = ""
)

/** 供应商分组（仅影响 UI 分段展示顺序）。 */
enum class ProviderGroup(val label: String) {
    COMMON("常用"),
    DOMESTIC("国内厂商"),
    OVERSEAS("海外厂商"),
    AGGREGATOR("聚合与推理平台"),
    LOCAL("本地部署"),
    NATIVE("原生协议")
}

object ProviderCatalog {

    /** 协议风格常量（与 [ProviderManager] 注册名一致）。 */
    const val STYLE_OPENAI = "openai"
    const val STYLE_CLAUDE = "claude"
    const val STYLE_GEMINI = "gemini"
    const val STYLE_LOCAL_LLAMA = "local-llama"

    /** 全量供应商目录（顺序即 UI 展示顺序，组内保持业务直觉排序）。 */
    val all: List<LlmProvider> = listOf(
        // —— 常用（精选，进模型页常驻 chip）——
        LlmProvider(
            id = STYLE_OPENAI, label = "OpenAI 兼容", group = ProviderGroup.COMMON,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            popularModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "o4-mini"),
            note = "任何 OpenAI 兼容端点（自建/中转/新厂）都选这个"
        ),
        LlmProvider(
            id = "deepseek", label = "DeepSeek", group = ProviderGroup.COMMON,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            popularModels = listOf("deepseek-chat", "deepseek-reasoner"),
            note = "深度求索官方；reasoner 支持思维链"
        ),
        LlmProvider(
            id = "moonshot", label = "Kimi 月之暗面", group = ProviderGroup.COMMON,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "kimi-k2-0905-preview",
            popularModels = listOf("kimi-k2-0905-preview", "moonshot-v1-128k", "moonshot-v1-32k"),
            note = "长上下文见长"
        ),
        LlmProvider(
            id = "zhipu", label = "智谱 GLM", group = ProviderGroup.COMMON,
            style = STYLE_OPENAI, defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4.6",
            popularModels = listOf("glm-4.6", "glm-4.5", "glm-4.5-air", "glm-4-plus"),
            note = "清华系；glm-4.5+ 系列支持思维链"
        ),
        LlmProvider(
            id = "qwen", label = "通义千问", group = ProviderGroup.COMMON,
            style = STYLE_OPENAI, defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            popularModels = listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen3-max"),
            note = "阿里云百炼兼容模式"
        ),
        LlmProvider(
            id = STYLE_CLAUDE, label = "Claude", group = ProviderGroup.COMMON,
            style = STYLE_CLAUDE, defaultBaseUrl = "https://api.anthropic.com",
            defaultModel = "claude-sonnet-4-5",
            popularModels = listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5"),
            note = "Anthropic 原生协议（含 extended thinking）"
        ),
        LlmProvider(
            id = STYLE_GEMINI, label = "Gemini", group = ProviderGroup.COMMON,
            style = STYLE_GEMINI, defaultBaseUrl = "https://generativelanguage.googleapis.com",
            defaultModel = "gemini-2.5-pro",
            popularModels = listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"),
            note = "Google 原生协议（含 thought 思考块）"
        ),

        // —— 国内厂商 ——
        LlmProvider(
            id = "doubao", label = "火山方舟·豆包", group = ProviderGroup.DOMESTIC,
            style = STYLE_OPENAI, defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultModel = "doubao-seed-1-6-250615",
            popularModels = listOf("doubao-seed-1-6-250615", "doubao-1-5-pro-32k-250115"),
            note = "字节豆包；模型名需填方舟推理接入点或官方模型 id"
        ),
        LlmProvider(
            id = "ernie", label = "百度文心·千帆", group = ProviderGroup.DOMESTIC,
            style = STYLE_OPENAI, defaultBaseUrl = "https://qianfan.baidubce.com/v2",
            defaultModel = "ernie-4.5-turbo-128k",
            popularModels = listOf("ernie-4.5-turbo-128k", "ernie-4.0-8k", "ernie-speed-128k"),
            note = "千帆 v2 OpenAI 兼容端点"
        ),
        LlmProvider(
            id = "hunyuan", label = "腾讯混元", group = ProviderGroup.DOMESTIC,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            defaultModel = "hunyuan-turbos-latest",
            popularModels = listOf("hunyuan-turbos-latest", "hunyuan-turbo-latest", "hunyuan-pro"),
            note = "腾讯云混元"
        ),
        LlmProvider(
            id = "spark", label = "讯飞星火", group = ProviderGroup.DOMESTIC,
            style = STYLE_OPENAI, defaultBaseUrl = "https://spark-api-open.xf-yun.com/v1",
            defaultModel = "4.0Ultra",
            popularModels = listOf("4.0Ultra", "generalv3.5", "lite"),
            note = "星火 OpenAI 兼容端点"
        ),
        LlmProvider(
            id = "minimax", label = "MiniMax", group = ProviderGroup.DOMESTIC,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.minimax.chat/v1",
            defaultModel = "MiniMax-M2",
            popularModels = listOf("MiniMax-M2", "abab6.5s-chat"),
            note = "海螺系大模型"
        ),
        LlmProvider(
            id = "stepfun", label = "阶跃星辰 Step", group = ProviderGroup.DOMESTIC,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.stepfun.com/v1",
            defaultModel = "step-2-16k",
            popularModels = listOf("step-2-16k", "step-1-8k", "step-1v-8k"),
            note = "Step 系列（含多模态）"
        ),
        LlmProvider(
            id = "baichuan", label = "百川智能", group = ProviderGroup.DOMESTIC,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.baichuan-ai.com/v1",
            defaultModel = "Baichuan4",
            popularModels = listOf("Baichuan4", "Baichuan3-Turbo"),
            note = ""
        ),
        LlmProvider(
            id = "yi", label = "零一万物 Yi", group = ProviderGroup.DOMESTIC,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.lingyiwanwu.com/v1",
            defaultModel = "yi-lightning",
            popularModels = listOf("yi-lightning", "yi-large", "yi-medium"),
            note = ""
        ),
        LlmProvider(
            id = "sensetime", label = "商汤日日新", group = ProviderGroup.DOMESTIC,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.sensenova.cn/compatible-mode/v1",
            defaultModel = "SenseChat-5",
            popularModels = listOf("SenseChat-5", "SenseChat-Turbo"),
            note = "商汤兼容模式端点"
        ),

        // —— 海外厂商 ——
        LlmProvider(
            id = "xai", label = "xAI Grok", group = ProviderGroup.OVERSEAS,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.x.ai/v1",
            defaultModel = "grok-4",
            popularModels = listOf("grok-4", "grok-3", "grok-3-mini"),
            note = ""
        ),
        LlmProvider(
            id = "mistral", label = "Mistral", group = ProviderGroup.OVERSEAS,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-large-latest",
            popularModels = listOf("mistral-large-latest", "mistral-medium-latest", "codestral-latest"),
            note = ""
        ),
        LlmProvider(
            id = "perplexity", label = "Perplexity", group = ProviderGroup.OVERSEAS,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.perplexity.ai",
            defaultModel = "sonar-pro",
            popularModels = listOf("sonar-pro", "sonar", "sonar-reasoning"),
            note = "联网检索见长"
        ),
        LlmProvider(
            id = "groq", label = "Groq", group = ProviderGroup.OVERSEAS,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
            popularModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen-2.5-32b"),
            note = "LPU 极速推理"
        ),
        LlmProvider(
            id = "together", label = "Together AI", group = ProviderGroup.OVERSEAS,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.together.xyz/v1",
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            popularModels = listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Qwen/Qwen2.5-72B-Instruct-Turbo"),
            note = ""
        ),
        LlmProvider(
            id = "fireworks", label = "Fireworks", group = ProviderGroup.OVERSEAS,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.fireworks.ai/inference/v1",
            defaultModel = "accounts/fireworks/models/llama-v3p3-70b-instruct",
            popularModels = listOf("accounts/fireworks/models/llama-v3p3-70b-instruct"),
            note = ""
        ),
        LlmProvider(
            id = "deepinfra", label = "DeepInfra", group = ProviderGroup.OVERSEAS,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.deepinfra.com/v1/openai",
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct",
            popularModels = listOf("meta-llama/Llama-3.3-70B-Instruct", "Qwen/Qwen2.5-72B-Instruct"),
            note = ""
        ),
        LlmProvider(
            id = "cerebras", label = "Cerebras", group = ProviderGroup.OVERSEAS,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.cerebras.ai/v1",
            defaultModel = "llama-3.3-70b",
            popularModels = listOf("llama-3.3-70b", "llama3.1-8b", "qwen-3-32b"),
            note = "晶圆级芯片，超低延迟"
        ),
        LlmProvider(
            id = "github-models", label = "GitHub Models", group = ProviderGroup.OVERSEAS,
            style = STYLE_OPENAI, defaultBaseUrl = "https://models.github.ai/inference",
            defaultModel = "openai/gpt-4o-mini",
            popularModels = listOf("openai/gpt-4o", "openai/gpt-4o-mini", "meta/Llama-3.3-70B-Instruct"),
            note = "用 GitHub PAT 当 Key"
        ),

        // —— 聚合与推理平台 ——
        LlmProvider(
            id = "openrouter", label = "OpenRouter", group = ProviderGroup.AGGREGATOR,
            style = STYLE_OPENAI, defaultBaseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openai/gpt-4o-mini",
            popularModels = listOf(
                "openai/gpt-4o-mini", "anthropic/claude-sonnet-4.5", "google/gemini-2.5-pro",
                "deepseek/deepseek-chat", "x-ai/grok-4"
            ),
            note = "一家 Key 打通全部厂商"
        ),
        LlmProvider(
            id = "siliconflow", label = "硅基流动", group = ProviderGroup.AGGREGATOR,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen3-8B",
            popularModels = listOf(
                "Qwen/Qwen3-8B", "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1",
                "Pro/zai-org/GLM-4.5"
            ),
            note = "国内聚合平台，价格友好"
        ),
        LlmProvider(
            id = "modelscope", label = "魔搭 ModelScope", group = ProviderGroup.AGGREGATOR,
            style = STYLE_OPENAI, defaultBaseUrl = "https://api-inference.modelscope.cn/v1",
            defaultModel = "Qwen/Qwen3-8B",
            popularModels = listOf("Qwen/Qwen3-8B", "deepseek-ai/DeepSeek-V3.1"),
            note = "阿里达摩院开源社区推理"
        ),
        LlmProvider(
            id = "volcengine-ark", label = "火山方舟（兼容旧名）", group = ProviderGroup.AGGREGATOR,
            style = STYLE_OPENAI, defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultModel = "doubao-seed-1-6-250615",
            popularModels = listOf("doubao-seed-1-6-250615"),
            note = "与「火山方舟·豆包」同源，保留老配置兼容"
        ),
        LlmProvider(
            id = "dashscope", label = "百炼 DashScope（兼容旧名）", group = ProviderGroup.AGGREGATOR,
            style = STYLE_OPENAI, defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            popularModels = listOf("qwen-max", "qwen-plus"),
            note = "与「通义千问」同源，保留老配置兼容"
        ),

        // —— 本地部署（无需 Key）——
        LlmProvider(
            id = "ollama", label = "Ollama", group = ProviderGroup.LOCAL,
            style = STYLE_OPENAI, defaultBaseUrl = "http://localhost:11434/v1",
            defaultModel = "llama3.2",
            popularModels = listOf("llama3.2", "qwen2.5", "deepseek-r1", "gemma3"),
            requiresApiKey = false,
            note = "本机 11434 端口；Key 任意（留空即可）"
        ),
        LlmProvider(
            id = "lmstudio", label = "LM Studio", group = ProviderGroup.LOCAL,
            style = STYLE_OPENAI, defaultBaseUrl = "http://localhost:1234/v1",
            defaultModel = "local-model",
            popularModels = emptyList(),
            requiresApiKey = false,
            note = "本机 1234 端口，模型名以 LM Studio 加载的为准"
        ),
        LlmProvider(
            id = "vllm", label = "vLLM / SGLang", group = ProviderGroup.LOCAL,
            style = STYLE_OPENAI, defaultBaseUrl = "http://localhost:8000/v1",
            defaultModel = "local-model",
            popularModels = emptyList(),
            requiresApiKey = false,
            note = "自建推理服务，默认 8000 端口"
        ),
        LlmProvider(
            id = STYLE_LOCAL_LLAMA, label = "本地模型（内置）", group = ProviderGroup.LOCAL,
            style = STYLE_LOCAL_LLAMA, defaultBaseUrl = "",
            defaultModel = "",
            popularModels = emptyList(),
            requiresApiKey = false,
            note = "App 内置 llama.cpp，模型由「本地模型」页签管理"
        )
    )

    /** id → 供应商。 */
    fun byId(id: String): LlmProvider? = all.firstOrNull { it.id == id }

    /**
     * id → 协议风格。**未收录的 id 一律按 OpenAI 兼容兜底**——这是「全量适配」的
     * 关键：用户填自定义端点、或用将来新出现的厂，都不会因为「不在目录里」而报
     * 「未知供应商」。
     */
    fun styleOf(id: String): String = byId(id)?.style ?: STYLE_OPENAI

    fun defaultUrl(id: String): String = byId(id)?.defaultBaseUrl ?: ""

    fun defaultModel(id: String): String = byId(id)?.defaultModel ?: ""

    fun popularModels(id: String): List<String> = byId(id)?.popularModels ?: emptyList()

    fun requiresApiKey(id: String): Boolean = byId(id)?.requiresApiKey ?: true

    fun labelOf(id: String): String = byId(id)?.label ?: id

    /** 是否本地部署（不需要 Key）。 */
    fun isLocal(id: String): Boolean = byId(id)?.group == ProviderGroup.LOCAL
        || id == STYLE_LOCAL_LLAMA

    /**
     * id → 「去这里创建 API Key」的控制台地址（P1，2026-10-21）。
     *
     * 真机反馈里最常见的一句是「Key 去哪弄」——用户卡在第一步，而这一步和产品能力无关。
     * 这里把地址做成数据，模型页在 Key 为空时直接给一条可点的入口，省掉一次跳出搜索。
     *
     * 约定：
     *  - 本地部署（Ollama / LM Studio / vLLM / 内置 llama.cpp）**没有控制台**，返回空串；
     *  - 未收录的自定义 id 返回空串，UI 据此不渲染入口（宁可不显示，也不给错链接）；
     *  - 只放「创建 Key」的直达页，不放首页——首页还要用户自己找三层菜单。
     */
    private val CONSOLE_URLS: Map<String, String> = mapOf(
        // 常用
        "openai" to "https://platform.openai.com/api-keys",
        "deepseek" to "https://platform.deepseek.com/api_keys",
        "moonshot" to "https://platform.moonshot.cn/console/api-keys",
        "zhipu" to "https://bigmodel.cn/usercenter/apikeys",
        "qwen" to "https://bailian.console.aliyun.com/?apiKey=1",
        "claude" to "https://console.anthropic.com/settings/keys",
        "gemini" to "https://aistudio.google.com/app/apikey",
        // 国内
        "doubao" to "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
        "ernie" to "https://console.bce.baidu.com/qianfan/ais/console/applicationConsole/application",
        "hunyuan" to "https://console.cloud.tencent.com/hunyuan/api-key",
        "spark" to "https://console.xfyun.cn/app/myapp",
        "minimax" to "https://platform.minimaxi.com/user-center/basic-information/interface-key",
        "stepfun" to "https://platform.stepfun.com/interface-key",
        "baichuan" to "https://platform.baichuan-ai.com/console/apikey",
        "yi" to "https://platform.lingyiwanwu.com/apikeys",
        "sensetime" to "https://platform.sensenova.cn/",
        // 海外
        "xai" to "https://console.x.ai/",
        "mistral" to "https://console.mistral.ai/api-keys/",
        "perplexity" to "https://www.perplexity.ai/settings/api",
        "groq" to "https://console.groq.com/keys",
        "together" to "https://api.together.ai/settings/api-keys",
        "fireworks" to "https://fireworks.ai/account/api-keys",
        "deepinfra" to "https://deepinfra.com/dash/api_keys",
        "cerebras" to "https://cloud.cerebras.ai/",
        "github-models" to "https://github.com/settings/tokens",
        // 聚合
        "openrouter" to "https://openrouter.ai/settings/keys",
        "siliconflow" to "https://cloud.siliconflow.cn/account/ak",
        "modelscope" to "https://modelscope.cn/my/myaccesstoken",
        "volcengine-ark" to "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
        "dashscope" to "https://bailian.console.aliyun.com/?apiKey=1"
    )

    /** id → Key 创建页；无控制台（本地部署）或未收录时返回空串。 */
    fun consoleUrlOf(id: String): String = CONSOLE_URLS[id].orEmpty()
}
