# W-15 批次记录：浏览器五件套

> 依据：《四家对照-我们差什么》缺口 #2（Operit 30+ browser_* / AiCode BrowserTool）。
> 本批只做最小可用五件套，不做 30 个（够覆盖「登录→点→填→看结果」闭环）。

## 交付

| 文件 | 作用 |
|---|---|
| `browser/BrowserHost.kt` | 不可见 WebView 宿主；MainActivity attach/release；去 UA 的 "wv" 标记；自管 destroyed 标志 |
| `browser/WebViewBrowser.kt` | open / snapshot / click / type / eval / screenshot（全部切主线程） |
| `tools/BrowserTools.kt` | web_open / web_snapshot / web_click / web_type / web_eval |
| `MainActivity.kt` | onCreate 注入、onDestroy 释放 |
| `ui/ChatViewModel.kt` | 注册五个工具 |

## 关键实现

- **等待加载**：`onPageFinished` + 可配置超时（默认 20s，3s–60s 夹取）；超时仍继续（很多页面永不 finished）。
- **快照面向模型**：标题 + URL + ≤80 个可见可交互元素（序号/标签/文本/#id/name/type）+ 1200 字正文摘要，不返回整页 HTML。
- **点击三路匹配**：`querySelector` → 文本精确 → 文本包含；未命中返回 `web_not_found` 并提示先 snapshot。
- **输入**：用原生 `value` setter + `input`/`change` 事件，React/Vue 受控组件也能收到（直接赋值会被框架忽略）。
- **JS 返回值**：`evaluateJavascript` 返回 JSON 编码字符串，做最小解码（引号/转义/`\uXXXX`）。
- **不可用即明说**：App 被系统回收后返回「后台浏览器不可用」，绝不假装成功。

## 验证

- CI run 183 **失败**（真实 API 误用）：`WebView` 没有 `isDestroyed`（那是 Activity/Fragment 的 API）；
  修法 = 自管 `@Volatile destroyed` 标志。另修 `@SuppressLint` 挂在表达式上（注解目标不合法）。
- CI run 184（`565e1d6`）**success**。
- APK 反验全绿：`BrowserHost` / `WebViewBrowser` / `web_open|snapshot|click|type|eval` 入池；
  文案「可交互元素」「后台浏览器不可用」在包内；W-9~W-14 判据回归通过。
- 产物：30,127,193 B，sha256 `505e4a75…7b19b`。

## 已知边界

- 只支持 http/https（`about:`/`file:` 不开放，避免读本地文件）。
- 无多标签/无网络请求拦截（Operit 有 `browser_tabs`/`network_requests`）；需要时再补。
- WebView 依赖 MainActivity 存活；App 被回收后需重新打开一次。
