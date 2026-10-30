# 参照与净室声明（THIRD PARTY NOTICES — 思想层）

> 这份文件记的是**「读过、借了思路、但零行代码进入本仓库」**的项目。
>
> 与 [`DEPENDENCIES.md`](DEPENDENCIES.md) 的分工：
>
> | 文件 | 管什么 | 判据 |
> |---|---|---|
> | `DEPENDENCIES.md` | **分发物**里的第三方东西（Maven 包、随包二进制、llama.cpp 源码） | 会打进 APK |
> | 本文件 | **读过但没抄**的参照项目（做法、架构、产品判断） | 一行都不进 APK |
>
> 最后核对：1.143（2026-10-30）。

---

## 1. 一句话结论

北辰的代码**全部为独立编写**。外部项目只用于「理解机制」，没有任何一行代码、
任何一种命名体系、任何一段注释被复制进来。可核对的事实：

- 全部业务代码在 `app/src/main/java/com/example/agentkit/`，包名与任何参照项目不同；
- 51 个工具、15 个分组的命名是自研前缀体系（`read_file` / `ui_click` / `shell_run` / `workflow_*`），
  与参照项目均不对应；
- 参照仓库不在构建链路里——`app/build.gradle.kts` 的 `implementation` 列表**没有任何一项**
  来自下表；
- 下表项目的代码**不在本仓库、不在 CI 缓存、不在任何构建产物中**。

---

## 2. 参照项目台账

### 2.1 五个 Agent 类参照（运行层改造期间通读）

| 项目 | 许可证 | 读了什么 | 借了什么（思想层） | 代码进入 | 风险 |
|---|---|---|---|---|---|
| [WWRJ3321/IRIDIS](https://github.com/WWRJ3321/IRIDIS) | Apache-2.0 | AgentLoop、工具分组、本地推理档位、UI 路由 | ① 工具按组按需载入；② 机型档位表（按 SoC 定线程/ctx）；③ 系统提示尽量精简 | 否 | 低 |
| [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | **AGPL-3.0** | AgentLoop 状态机、Hooks、设置页组织 | ① 工具调用前后钩子严格配对；② 「多助手 = 一整套配置档案」的产品形态 | 否 | **高：只取思路** |
| [AAswordman/Operit](https://github.com/AAswordman/Operit) | LGPL-3.0 | 工具注册与权限分级、容器接入、设置页信息架构 | ① 单结果独立截断（不可整批截）；② 「提示只列子集、其余靠搜索」的方向 | 否 | 中 |
| [jieapi/AiCode](https://github.com/jieapi/AiCode) | **GPL-3.0** | 内置 rootfs + proot 落地、检查点机制 | ① 首启解压 rootfs 的流程；② 检查点快照的粒度取舍 | 否 | **高：只取思路** |
| [TensorHub-ORG/Coomi](https://github.com/TensorHub-ORG/Coomi) | Apache-2.0 | 换栈后架构（Java 壳 + Rust 引擎 + Vue WebView） | ① 逐工具失败指纹与退避的形态；② **反例**：换栈不能解决入口拥挤 | 否 | 低 |

### 2.2 其他参照

| 项目 | 许可证 | 借了什么 | 代码进入 | 风险 |
|---|---|---|---|---|
| zafiro / libterm（终端后端形态） | — | 终端后端三层切分（接口 / 工厂 / 会话池）、输出截断器形状 | 否（净室重写） | 低 |
| [Termux](https://github.com/termux/termux-app)（proot 预编译） | GPL-2.0 | 不涉及——**这是真正的分发物**，按 GPL-2.0 合规处理，见 `DEPENDENCIES.md` §4 | 二进制，独立进程调用 | 已处理 |
| [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | 本地推理引擎（源码依赖） | 是，见 `DEPENDENCIES.md` §3 | 已处理 |

---

## 3. 净室纪律（三条，写代码前过一遍）

**① 读和写不同时。**
读参照仓库时**不打开本项目的编辑器**；写代码时**不打开参照仓库**。中间至少隔一次
上下文切换。这是净室开发的标准做法——事后论证「我没抄」永远不如过程上
「我没机会抄」有力。

**② 只搬「为什么」，不搬「怎么写」。**
可以带走：为什么这么做、什么条件下会错、取舍是什么。
不可以带走：类名、方法名、参数顺序、分支结构、注释措辞、常量取值。

自检方法：把自己写的东西和对方并排看，**如果函数签名能逐个对上，就是搬了，重写**。

**③ 措辞只写「设计参照」，不写「来源 + 行号」。**
`来源：operit #9 / aicode C3` 这种写法看起来像移植清单。正确写法见下节。

---

## 4. 注释措辞规范

提到参照项目时，**必须同时出现「净室 / 未复制代码」字样**，且不写对方行号。

```kotlin
// ✅ 说清借的是思想，并声明实现独立
/**
 * 工具结果体积上限 + 单结果截断（T06，设计参照：operit / aicode 的「单结果独立截断」，
 * 净室实现，未复制代码）。
 */

// ✅ 点明对方许可证，风险自知
/**
 * 前台应用探测（设计参照：rikkahub，AGPL-3.0——只读思路，未复制代码）。
 */

// ❌ 像移植清单，自伤举证
// 来源：operit #9 / aicode C3（ConversationMarkupManager.kt:111）
```

---

## 5. 怎么核对这份台账

```bash
# 参照项目名出现在哪些源码里（应只出现在注释中）
grep -rn "IRIDIS\|rikkahub\|Operit\|AiCode\|Coomi\|zafiro" app/src/main/java --include=*.kt

# 每处是否都带了「净室」或「未复制代码」
grep -rn "设计参照\|净室" app/src/main/java --include=*.kt | wc -l

# 构建链路里有没有它们（应为空）
grep -n "IRIDIS\|rikkahub\|Operit\|AiCode\|Coomi" app/build.gradle.kts settings.gradle.kts
```
