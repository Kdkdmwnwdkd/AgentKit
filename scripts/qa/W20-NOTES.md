# W-20 批次记录：精确编辑 + 网页搜索

> 依据：《四家对标-全功能面缺口清单》#55（精确编辑）与 #53（网页搜索），均为 P1；
> 四家（Coomi/RikkaHub/Operit/AiCode）均有对应能力，AgentKit 此前空白。

## 交付

| 文件 | 作用 |
|---|---|
| `tools/EditFileLogic.kt` | **纯函数**：edit_file 决策（唯一性/出现次数/行号/上下文回显），无 IO 无 Android |
| `tools/BuiltInTools.kt` | `EditFileTool`（委托纯函数）+ `atomicWriteText`（临时文件 + rename）；`WebSearchTool` 拆出后不再内联解析 |
| `tools/WebSearchParsing.kt` | **纯函数**：DuckDuckGo HTML 端点解析（uddg 还原、实体还原、去重、截断） |
| `tools/WebSearchTool.kt` | `web_search`：POST html.duckduckgo.com，返回「标题/链接/摘要」清单（≤8 条） |
| `ui/ChatViewModel.kt` | 注册两个新工具 |
| `scripts/qa/EditWebSearchVerification.kt` + `run-edit-websearch-verification.sh` | harness（纯 JVM，不加载 Android） |

## 关键决策

- **edit_file 先校验后写**：空 old / old==new / 找不到（stale_not_found）/ 多处不唯一
  （not_unique 带行号）四种情况全部拒绝并给 hint，绝不写坏文件。对齐 AiCode 的
  「精确编辑 + STALE_FILE」思路，净室重写。
- **原子写**：同目录 `.akitmp` 临时文件 + rename，写一半崩溃不损坏原文件。
- **上下文回显**：返回替换点前后各 3 行（首替换行加 `→`），模型可自证改对位置。
- **web_search 走 DuckDuckGo HTML 端点**：无需 API Key。不引 jsoup（离线 CI 不加
  新依赖），两条正则净室解析；uddg 跳转包装还原为真实 URL；按 URL 去重、摘要截断
  300 字。失败分 `http_error` / `no_results` / `network_error` 三种稳定码。
- **纯函数与 IO 分离**：两个工具的核心逻辑都在独立无依赖文件里，harness 直接编译
  运行（不拉 Android 类），同一份真源服务生产与测试。
- **本地小模型暴露面不变**：新工具默认不进 `LOCAL_CORE_TOOLS`（云端全量可见）。

## 验证

- harness **22/22**（edit_file 决策 11 + 边界 3 + web_search 解析 8）。
  harness 抓到 1 个真 bug：`"".lines()` 返回 `[""]` 非空列表，空文本上下文回显不走
  占位分支——已修（`text.isEmpty()` 提前返回）。
- CI：见当批 run（推送后回填）。
- APK 反验：`EditFileLogic` / `WebSearchParsing` / `EditFileTool` / `WebSearchTool` /
  `edit_file` / `web_search` / `edit_ok` / `search_ok` / `not_unique` / `stale_not_found`
  + 文案「替换前后内容相同」「没有搜到」入池（见 verify-apk-features.py W20 组）。
