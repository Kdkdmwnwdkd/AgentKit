#!/usr/bin/env python3
"""APK dex 反验（W-9 起）——解析 CI 产出的 classes*.dex 字符串池，
断言各批次（W-9/W-12 截图通道 / W-10 上下文预算）的代码字面量确实打进包内。

两种判据模式（脚本如实标注，不用一种模式冒充另一种）：
  pool 池条目精确匹配：needle 是编译期常量本体（方法名 / 内联 const / 模板静态段 /
      类型描述符），必须在 dex 字符串池中存在同名条目。防止标识符子串假阳性
      （例：裸搜 "a11y" 会命中 $a11yServicesEnabled$delegate 这类无关标识符）。
  bytes 原始字节子串：needle 是更长字面量的片段（文案 / 系统提示词分段），
      在 dex 拼接 blob 中出现即证明该文本随所属常量进包。

反证（bytes）：修前旧描述整串（单体常量，编译期已拼接）不再出现——防换错包/旧码残留。

用法: python3 verify-w9-apk.py [apk路径]
"""
import sys, zipfile, re, os

APK = sys.argv[1] if len(sys.argv) > 1 else "/tmp/w9-apk/app-debug.apk"


def read_uleb128(data, off):
    result, shift = 0, 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def dex_string_pool(dex):
    """解析单个 dex 的字符串池，返回 set[str]。非 dex 内容返回空集。"""
    if len(dex) < 0x40 or dex[:4] != b"dex\n":
        return set()
    n = int.from_bytes(dex[0x38:0x3C], "little")
    ids_off = int.from_bytes(dex[0x3C:0x40], "little")
    out = set()
    for i in range(n):
        p = ids_off + i * 4
        if p + 4 > len(dex):
            break
        data_off = int.from_bytes(dex[p:p + 4], "little")
        if data_off >= len(dex):
            continue
        try:
            _, q = read_uleb128(dex, data_off)
            end = dex.index(b"\x00", q)
            raw = dex[q:end]
            # MUTF-8：字符 U+0000 编码为 0xC0 0x80（overlong），先还原再按 UTF-8 解
            out.add(raw.replace(b"\xc0\x80", b"\x00").decode("utf-8"))
        except (ValueError, UnicodeDecodeError, IndexError):
            continue
    return out


# ---- 判据表 ----------------------------------------------------------------

POOL_GROUPS = {
    "W9-A 截图双通道（pool 池条目精确匹配）": [
        ("无障碍截屏服务方法名", "takeScreenshotViaA11y"),
        ("路由纯函数名", "canUseA11yScreenshot"),
        ("meta 模板含 via=a11y（完整静态段）", ',"type":"png","via":"a11y"}'),
        ("UiScreenshotTool 类型描述符", "Lcom/example/agentkit/automation/UiScreenshotTool;"),
        ("AgentAccessibilityService 类型描述符", "Lcom/example/agentkit/automation/AgentAccessibilityService;"),
    ],
    "W18 兜底/检索/计算（pool 池条目精确匹配）": [
        ("会话检索工具类", "Lcom/example/agentkit/tools/ConversationSearchTool;"),
        ("计算器工具类", "Lcom/example/agentkit/tools/CalculatorTool;"),
        ("会话检索工具名", "conversation_search"),
        ("计算器工具名", "calculator"),
        ("片段提取纯函数", "snippetAround"),
        ("input 文本转义", "escapeInputText"),
    ],
    "W17 定时任务（pool 池条目精确匹配）": [
        ("Workflow 模型", "Lcom/example/agentkit/workflow/Workflow;"),
        ("Workflow 存储", "Lcom/example/agentkit/workflow/WorkflowStore;"),
        ("巡检 Worker", "Lcom/example/agentkit/workflow/WorkflowWorker;"),
        ("到期判定纯函数", "isWorkflowDue"),
        ("workflow_create", "workflow_create"),
        ("workflow_list", "workflow_list"),
        ("workflow_delete", "workflow_delete"),
        ("workflow_toggle", "workflow_toggle"),
    ],
    "W16 TTS/记忆（pool 池条目精确匹配）": [
        ("TTS 封装", "Lcom/example/agentkit/audio/TextSpeaker;"),
        ("自动记忆抽取", "Lcom/example/agentkit/memory/MemoryAutoCapture;"),
        ("朗读工具", "Lcom/example/agentkit/tools/SpeakTool;"),
        ("speak 工具名", "speak"),
        ("BM25 打分方法", "bm25Scores"),
    ],
    "W15 浏览器五件套（pool 池条目精确匹配）": [
        ("WebView 宿主", "Lcom/example/agentkit/browser/BrowserHost;"),
        ("浏览器操作封装", "Lcom/example/agentkit/browser/WebViewBrowser;"),
        ("web_open", "web_open"),
        ("web_snapshot", "web_snapshot"),
        ("web_click", "web_click"),
        ("web_type", "web_type"),
        ("web_eval", "web_eval"),
    ],
    "W14 MCP/追问/清单（pool 池条目精确匹配）": [
        ("MCP 工具适配器", "Lcom/example/agentkit/core/mcp/McpToolAdapter;"),
        ("MCP 服务器存储", "Lcom/example/agentkit/core/mcp/McpServerStore;"),
        ("MCP 工具名前缀函数", "mcpWireName"),
        ("提问中转站", "Lcom/example/agentkit/core/agent/AskUserBroker;"),
        ("任务清单存储", "Lcom/example/agentkit/core/agent/TodoStore;"),
        ("ask_user 工具", "ask_user"),
        ("todo_write 工具", "todo_write"),
        ("todo_read 工具", "todo_read"),
    ],
    "W13 调试通道 B（pool 池条目精确匹配）": [
        ("ShizukuShell 类描述符", "Lcom/example/agentkit/automation/shizuku/ShizukuShell;"),
        ("提权路由纯函数", "shouldUsePrivilegedShell"),
        ("提权结果类型", "Lcom/example/agentkit/tools/PrivilegedShellOut;"),
        ("调试通道入口动作", "handleShizukuAction"),
        ("安装态探测", "isInstalled"),
        ("四态路由纯函数", "uiState"),
    ],
    "W10 上下文预算治理（pool 池条目精确匹配）": [
        ("ContextBudget 类描述符", "Lcom/example/agentkit/core/agent/ContextBudget;"),
        ("请求视图构建方法", "buildRequestMessages"),
        ("通道预算函数", "budgetForProvider"),
        ("图片裁剪函数", "pruneImages"),
        ("预算压缩函数", "fitToBudget"),
        ("W11 结果上限函数", "toolResultLimitChars"),
    ],
    "W19 稳定性收尾（pool 池条目精确匹配）": [
        ("诊断包导出器", "Lcom/example/agentkit/core/DiagnosticsExporter;"),
        ("MCP 注册就绪信号字段", "mcpReady"),
        ("MCP 超时失败码", "mcp_timeout"),
    ],
    "W20 精确编辑+网页搜索（pool 池条目精确匹配）": [
        ("edit 决策纯函数", "Lcom/example/agentkit/tools/EditFileLogic;"),
        ("edit 工具类", "Lcom/example/agentkit/tools/EditFileTool;"),
        ("edit 工具名", "edit_file"),
        ("edit 成功码", "edit_ok"),
        ("唯一性失败码", "not_unique"),
        ("过期未找到失败码", "stale_not_found"),
        ("DDG 解析纯函数", "Lcom/example/agentkit/tools/WebSearchParsing;"),
        ("web_search 工具类", "Lcom/example/agentkit/tools/WebSearchTool;"),
        ("web_search 工具名", "web_search"),
        ("search 成功码", "search_ok"),
        ("原子写扩展函数", "atomicWriteText"),
        ("上下文回显纯函数", "contextAround"),
    ],
    "W22 Bing 主引擎修复（pool 池条目精确匹配）": [
        ("Bing 解析纯函数", "parseBing"),
        ("Bing 跳转还原", "realUrlBing"),
        ("Bing 端点常量值", "https://cn.bing.com/search"),
    ],
    "W33 后台命令（pool 池条目精确匹配）": [
        ("任务模型", "Lcom/example/agentkit/core/job/JobInfo;"),
        ("任务状态常量", "Lcom/example/agentkit/core/job/JobStatus;"),
        ("任务逻辑纯函数", "Lcom/example/agentkit/core/job/JobLogic;"),
        ("任务存储", "Lcom/example/agentkit/core/job/JobStore;"),
        ("任务执行器", "Lcom/example/agentkit/core/job/JobRunner;"),
        ("任务通知器", "Lcom/example/agentkit/core/job/JobNotifier;"),
        ("后台工具类", "Lcom/example/agentkit/tools/ShellBackgroundTool;"),
        ("查询工具类", "Lcom/example/agentkit/tools/ShellJobsTool;"),
        ("后台工具名", "shell_background"),
        ("查询工具名", "shell_jobs"),
        ("启动成功码", "job_started"),
        ("完成状态码", "job_finished"),
        ("取消状态码", "job_cancelled"),
        ("尾行函数", "tailLines"),
        ("重启对账函数", "reconcileStatus"),
    ],
    "W32 GitHub 提交（pool 池条目精确匹配）": [
        ("提交逻辑纯函数", "Lcom/example/agentkit/core/build/GitHubGitLogic;"),
        ("提交服务", "Lcom/example/agentkit/core/build/GitHubGitService;"),
        ("变更模型", "Lcom/example/agentkit/core/build/FileChange;"),
        ("状态工具类", "Lcom/example/agentkit/tools/GitHubStatusTool;"),
        ("提交工具类", "Lcom/example/agentkit/tools/GitHubCommitTool;"),
        ("历史工具类", "Lcom/example/agentkit/tools/GitHubLogTool;"),
        ("状态工具名", "github_status"),
        ("提交工具名", "github_commit"),
        ("历史工具名", "github_log"),
        ("blob sha 函数", "blobSha"),
        ("路径规范化函数", "normalizePath"),
        ("提交成功码", "committed"),
        ("有待提交码", "changes_found"),
    ],
    "W31 并行子代理（pool 池条目精确匹配）": [
        ("子代理逻辑纯函数", "Lcom/example/agentkit/core/agent/SubAgentLogic;"),
        ("子代理执行器", "Lcom/example/agentkit/core/agent/SubAgentRunner;"),
        ("子代理工具类", "Lcom/example/agentkit/tools/SubAgentTool;"),
        ("工具名", "spawn_agents"),
        ("成功码", "subagents_done"),
        ("全失败码", "subagents_failed"),
        ("只读白名单字段", "READ_ONLY_TOOLS"),
        ("任务校验函数", "parseTasks"),
        ("结论提取函数", "extractConclusion"),
        ("并发归一函数", "clampConcurrency"),
    ],
    "W30 聊天体验批（pool 池条目精确匹配）": [
        ("时间格式化纯函数", "Lcom/example/agentkit/core/text/ChatTime;"),
        ("精确时间函数", "precise"),
        ("毫秒归一函数", "normalizeToMillis"),
        ("收藏存储类", "Lcom/example/agentkit/core/BookmarkStore;"),
        ("收藏条目类", "Lcom/example/agentkit/core/Bookmark;"),
        ("收藏落盘函数", "removeByContent"),
        ("翻译逻辑纯函数", "Lcom/example/agentkit/core/text/TranslateLogic;"),
        ("译文清洗函数", "stripWrappingQuotes"),
        ("语音清洗纯函数", "Lcom/example/agentkit/core/text/SpeechLogic;"),
        ("语言别名归一函数", "normalizeLanguage"),
        ("收藏页组件", "Lcom/example/agentkit/ui/BookmarkScreenKt;"),
        ("收藏操作入口", "toggleBookmark"),
        ("翻译操作入口", "translateMessage"),
    ],
    "W29 自定义参数 + 余额（pool 池条目精确匹配）": [
        ("请求头解析纯函数", "Lcom/example/agentkit/core/text/HeaderParsing;"),
        ("余额解析纯函数", "Lcom/example/agentkit/core/llm/BalanceParsing;"),
        ("余额服务", "Lcom/example/agentkit/core/llm/BalanceService;"),
        ("端点映射函数", "endpointOf"),
        ("头解析方法", "parseHeaderLines"),
        ("请求体校验方法", "validateCustomBody"),
    ],
    "W28 @提及文件 + 工作区绑定（pool 池条目精确匹配）": [
        ("提及解析纯函数", "Lcom/example/agentkit/core/text/MentionLogic;"),
        ("末尾提及检测", "activeQueryAtEnd"),
        ("子序列匹配", "isSubsequence"),
        ("提及替换方法", "replaceTailWith"),
        ("工作区绑定字段", "workspaceDir"),
        ("绑定设置方法", "setWorkspaceDir"),
        ("文件列表方法", "workspacePaths"),
    ],
    "W27 文件树 + 语法高亮 + 工作区统一（pool 池条目精确匹配）": [
        ("路径规则纯函数", "Lcom/example/agentkit/core/workspace/WorkspacePaths;"),
        ("相对路径校验", "safeRelPath"),
        ("语言识别", "languageOf"),
        ("树列举方法", "listTree"),
        ("高亮词法器", "Lcom/example/agentkit/core/text/SyntaxHighlighter;"),
        ("高亮片段类型", "Lcom/example/agentkit/core/text/SyntaxHighlighter$Kind;"),
        ("工作区目录名", "agent_workspace"),
    ],
    "W26 任务模型 + 备份 Key 池（pool 池条目精确匹配）": [
        ("标题清洗纯函数", "Lcom/example/agentkit/core/agent/TitleLogic;"),
        ("标题值得生成判定", "worthGenerating"),
        ("引号剥离函数", "stripWrappingQuotes"),
        ("Key 池导出函数", "exportAll"),
        ("Key 池导入函数", "importAll"),
        ("备份池成员名", "keypool.json"),
        ("任务模型配置键", "task_profile_id"),
        ("任务模型设置方法", "setTaskProfile"),
    ],
    "W25 Plan 模式（pool 池条目精确匹配）": [
        ("计划中转站", "Lcom/example/agentkit/core/agent/PlanBroker;"),
        ("计划工具类", "Lcom/example/agentkit/tools/PlanTool;"),
        ("计划解析纯函数", "Lcom/example/agentkit/tools/PlanLogic;"),
        ("计划工具名", "plan_propose"),
        ("批准码", "plan_approved"),
        ("拒绝码", "plan_rejected"),
        ("超时未批准码", "plan_timeout"),
        ("步骤清洗函数", "stripBullet"),
        ("步骤解析函数", "parseSteps"),
    ],
    "W24 OCR + 软件管理（pool 池条目精确匹配）": [
        ("OCR 工具类", "Lcom/example/agentkit/automation/OcrTool;"),
        ("OCR 工具名", "ocr_image"),
        ("OCR 成功码", "ocr_ok"),
        ("ML Kit 中文识别器类", "Lcom/google/mlkit/vision/text/chinese/ChineseTextRecognizerOptions;"),
        ("软件管理纯函数", "Lcom/example/agentkit/tools/AppManageLogic;"),
        ("安装工具类", "Lcom/example/agentkit/tools/AppInstallTool;"),
        ("卸载工具类", "Lcom/example/agentkit/tools/AppUninstallTool;"),
        ("安装工具名", "app_install"),
        ("卸载工具名", "app_uninstall"),
        ("安装器已打开码", "installer_opened"),
        ("卸载结果判定函数", "interpretUninstall"),
    ],
    "W23 load_skill + 本地模型修复（pool 池条目精确匹配）": [
        ("load_skill 工具类", "Lcom/example/agentkit/tools/LoadSkillTool;"),
        ("技能校验纯函数", "Lcom/example/agentkit/tools/LoadSkillLogic;"),
        ("load_skill 工具名", "load_skill"),
        ("安装成功码", "skill_installed"),
        ("卸载成功码", "skill_uninstalled"),
        ("体积上限常量名", "MAX_ZIP_BYTES"),
        ("SSRF 判定函数", "isPrivateHost"),
        ("HF 国内镜像域名", "https://hf-mirror.com/"),
    ],
    "W21 多 Key 轮询（pool 池条目精确匹配）": [
        ("Key 池决策纯函数", "Lcom/example/agentkit/core/llm/KeyPoolPolicy;"),
        ("Key 池运行时", "Lcom/example/agentkit/core/llm/KeyPoolRuntime;"),
        ("Key 池持久化", "Lcom/example/agentkit/core/llm/KeyPoolStore;"),
        ("是否值得切换判定", "worthFailover"),
        ("选 Key 纯函数", "pickUsable"),
        ("最早恢复时间函数", "earliestRecoveryMs"),
        ("冷却时长函数", "cooldownMs"),
        ("备用 Key UI 模型", "BackupKeyUi"),
        ("主 Key 槽位 id", "primary"),
    ],
}

# 反向判据：这些符号必须**不在**池中（删除/替换成功的证据，防旧码残留）
ABSENT_POOL = [
    ("旧条数压缩方法 maybeCompact 已移除", "maybeCompact"),
    ("屏幕捕获服务已移除", "Lcom/example/agentkit/automation/ScreenCaptureService;"),
    ("屏幕捕获桥已移除", "Lcom/example/agentkit/automation/ScreenshotBridge;"),
    ("屏幕捕获清理策略已移除", "ScreenCaptureStartPolicy"),
    ("屏幕捕获清理追踪已移除", "ScreenCaptureTrace"),
    ("旧捕获失败码已移除", "capture_not_enabled"),
]

BYTES_GROUPS = {
    "W9-A 文案（描述 / 失败指引，长常量片段，bytes 子串）": [
        ("描述：已授予无障碍即可使用", "已授予无障碍权限即可使用"),
        ("指引：明确无需屏幕捕捉权限", "无需屏幕捕捉权限"),
    ],
    "W18 文案（bytes 子串）": [
        ("兜底提示", "已用调试通道 input 完成"),
    ],
    "W17 文案（bytes 子串）": [
        ("页面标题", "定时任务"),
        ("立即运行按钮", "立即运行"),
        ("通知渠道名", "到点提醒你执行设定好的任务"),
    ],
    "W15 文案（bytes 子串）": [
        ("快照小节标题", "可交互元素"),
        ("不可用提示", "后台浏览器不可用"),
    ],
    "W14 文案（bytes 子串）": [
        ("提问卡片标题", "需要你确认"),
        ("MCP 保存按钮", "保存为常用服务器"),
        ("MCP 我的服务器", "我的服务器"),
    ],
    "W13 权限页文案（bytes 子串）": [
        ("调试通道授权项标题", "调试通道（Shizuku）"),
        ("未运行引导", "通过无线调试启动"),
        ("后台执行说明", "shell uid 2000"),
        ("已装未启动引导", "已安装未启动"),
        ("官方包名候选", "moe.shizuku.privileged.api"),
        ("未安装走官方发布页", "RikkaApps/Shizuku/releases"),
        ("设置向导标题", "调试通道怎么开"),
        ("魅族必开项", "Flyme 支付保护"),
        ("打开 Shizuku 按钮", "打开 Shizuku"),
    ],
    "W10 图片裁剪文案（bytes 子串）": [
        ("旧图省略标记", "早前截图已从上下文省略"),
    ],
    "W9-B 系统提示分工段（长常量片段，bytes 子串）": [
        ("分工段标题", "工具分工：ui_tree 拿界面结构与坐标"),
        ("禁每步截图", "不要每个步骤都截图"),
        ("截图看视觉", "ui_screenshot 用于「看见」视觉内容"),
    ],
    "W19 文案（bytes 子串）": [
        ("诊断包已生成提示", "诊断包已生成（配置已脱敏）"),
        ("分享面板主题", "AgentKit 诊断包"),
        ("分享选择器标题", "分享诊断包"),
        ("通知权限引导标题", "通知权限未开启"),
        ("通知引导说明", "定时任务到点靠通知提醒你"),
        ("去开启通知按钮", "去开启通知"),
        ("MCP 超时文案", "执行超时（60 秒）"),
    ],
    "W20 文案（bytes 子串）": [
        ("替换无变化提示", "替换前后内容相同"),
        ("无搜索结果提示", "没有搜到"),
        ("唯一性提示（多处出现）", "在文件中出现"),
        ("过期提示（重新读取）", "文件中找不到 old_string"),
    ],
    "W21 文案（bytes 子串）": [
        ("设置页卡片标题", "备用 Key"),
        ("限流切换日志段", "被限流（"),
        ("全部不可用提示", "把 Key 都不可用"),
        ("未配备用 Key 提示", "未配备用 Key"),
        ("冷却恢复提示段", "最早"),
    ],
    "W22 文案（bytes 子串）": [
        ("双引擎全挂提示", "搜索服务不可达"),
        ("Bing 引擎名", "Bing"),
    ],
    "W33 文案（bytes 子串）": [
        ("后台启动提示", "已在后台启动"),
        ("中断提示", "结果未知"),
        ("通知标题", "命令完成"),
        ("通知渠道名", "后台命令"),
        ("运行中提示", "运行中（已 "),
        ("省略提示", "行已省略"),
        ("回到底部按钮", "回到底部"),
    ],
    "W32 文案（bytes 子串）": [
        ("未配置提示", "还没有配置 GitHub 仓库"),
        ("提交成功提示", "已提交 "),
        ("跳过说明", "被跳过："),
        ("一致提示", "没有待提交的变更"),
        ("权限提示", "Contents 读写权限"),
    ],
    "W31 文案（bytes 子串）": [
        ("汇总标题", "子代理结果"),
        ("任务上限提示", "一次最多派"),
        ("空任务提示", "没有可执行的子任务"),
        ("只读约束", "只做只读操作"),
        ("汇总省略提示", "汇总已达长度上限"),
    ],
    "W30 文案（bytes 子串）": [
        ("译文块标题", "译文"),
        ("收藏菜单项", "取消收藏"),
        ("收藏空态标题", "还没有收藏"),
        ("清空收藏确认", "清空收藏"),
        ("语音输入按钮", "语音输入"),
        ("语音未识别提示", "没听清，再说一次试试"),
        ("无语音服务提示", "本机没有可用的语音识别服务"),
        ("收藏页说明", "删掉会话也不会丢"),
    ],
    "W29 文案（bytes 子串）": [
        ("高级参数入口", "自定义请求头"),
        ("查余额按钮", "查余额"),
        ("不支持提示", "未提供余额接口"),
        ("请求体标签", "自定义请求体"),
    ],
    "W28 文案（bytes 子串）": [
        ("提及注入标题", "被提及的文件"),
        ("菜单项", "绑定项目目录"),
        ("项目规则段", "项目规则"),
        ("绑定说明", "会作为项目规则注入"),
    ],
    "W27 文案（bytes 子串）": [
        ("工作区卡片说明", "与 Agent 工具同一个目录"),
        ("树折叠提示", "点目录折叠"),
        ("迁移日志", "工作区已迁移"),
    ],
    "W26 文案（bytes 子串）": [
        ("标题生成器提示", "你是会话标题生成器"),
        ("设置页任务模型标题", "任务模型"),
        ("跟随主配置选项", "跟随主配置"),
        ("备份恢复日志", "个档案的备用 Key 池"),
    ],
    "W25 文案（bytes 子串）": [
        ("计划卡片标题", "计划待确认"),
        ("批准按钮", "批准执行"),
        ("拒绝按钮", "不要执行"),
        ("模式名", "先计划后执行"),
        ("超时安全提示", "不得执行破坏性步骤"),
    ],
    "W24 文案（bytes 子串）": [
        ("安装器提示", "已打开系统安装器"),
        ("自我卸载保护", "不能卸载 AgentKit 自己"),
        ("OCR 无文字提示", "识别完成，但画面里没有文字"),
        ("卸载确认提示", "已打开系统卸载确认框"),
    ],
    "W23 文案（bytes 子串）": [
        ("本地服务无需 Key 说明", "本地服务不需要 API Key"),
        ("一键切内置按钮", "改用 App 内置模型"),
        ("本地模型页签直达", "打开「本地模型」页签"),
        ("技能包安全提示", "技能包必须来自公网 https"),
    ],
}

OLD_DESCRIPTION = (
    "截取当前手机屏幕画面，并以图片形式返回。Agent 可据此“看见”屏幕内容"
    "（图标、图片、验证码、游戏画面等 UI 树无法描述的区域），用于理解界面、"
    "定位元素、判断状态。返回图片会自动附到下一轮对话供视觉模型分析。"
    "需要先在「自动化」页开启屏幕捕获授权。"
)


def main():
    if not os.path.exists(APK):
        print(f"FATAL: APK 不存在: {APK}")
        sys.exit(2)
    zf = zipfile.ZipFile(APK)
    names = zf.namelist()
    dexes = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", n))
    print(f"APK: {APK} ({os.path.getsize(APK):,} bytes, sha256 见下载记录)")
    print(f"条目数: {len(names)} | dex: {len(dexes)} 个")
    for req in ("AndroidManifest.xml", "resources.arsc"):
        if req not in names:
            print(f"FATAL: 缺少 {req}（不是合法 APK）")
            sys.exit(2)

    pool = set()
    blob = b""
    for d in dexes:
        raw = zf.read(d)
        blob += raw
        pool |= dex_string_pool(raw)
    print(f"dex 合计: {len(blob):,} bytes | 字符串池条目: {len(pool):,} 个\n")

    total_fail = 0
    for group, items in POOL_GROUPS.items():
        print(f"== {group} ==")
        for label, needle in items:
            hit = needle in pool
            print(f"  {'PASS' if hit else 'FAIL'}  {label}  <{needle[:60]}>")
            if not hit:
                total_fail += 1
        print()

    for group, items in BYTES_GROUPS.items():
        print(f"== {group} ==")
        for label, needle in items:
            hit = needle.encode("utf-8") in blob
            print(f"  {'PASS' if hit else 'FAIL'}  {label}  <{needle[:60]}>")
            if not hit:
                total_fail += 1
        print()

    for label, symbol in ABSENT_POOL:
        absent = symbol not in pool
        print(f"  {'PASS' if absent else 'FAIL'}  [反证] {label}  <{symbol}>")
        if not absent:
            total_fail += 1
    print()

    gone = OLD_DESCRIPTION.encode("utf-8") not in blob
    print(f"  {'PASS' if gone else 'FAIL'}  [反证] 修前旧描述整串已从包内消失")
    if not gone:
        total_fail += 1

    # —— 内置资产：一键安装用的 Shizuku APK（W-13；CI 构建时下载，不进仓库）——
    print("== W13 内置资产 ==")
    try:
        size = zf.getinfo("assets/shizuku.apk").file_size
        ok = size > 1_000_000
        print(f"  {'PASS' if ok else 'FAIL'}  assets/shizuku.apk 存在且体积合理  <{size:,} bytes>")
        if not ok:
            total_fail += 1
    except KeyError:
        print("  FAIL  assets/shizuku.apk 不在包内（CI 的 Fetch Shizuku APK 步骤可能失败）")
        total_fail += 1
    print()

    print(f"\n=== 反验汇总: {'全部通过' if total_fail == 0 else str(total_fail) + ' 项失败'} ===")
    sys.exit(0 if total_fail == 0 else 1)


main()
