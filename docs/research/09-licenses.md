# 09 · 许可证决策记录

> 本文是 AgentKit「化为自己实现」路线的合规底账。**将来若开源，这份文档就是答辩材料**；现在自用阶段，它是防止无意中把 GPL 代码引进来的护栏。所有结论以 2026-10-12 调研时点的上游许可证为准。

## 决策总表

| 项目 | 许可证 | 传播性 | 结论 | 依据 |
|---|---|---|---|---|
| ggml-org/llama.cpp | MIT | 宽松 | ✅ **可编译集成**（NDK 出 .so） | MIT 允许闭源分发+修改，唯需保留版权声明 |
| modelcontextprotocol/kotlin-sdk | MIT | 宽松 | ✅ **可直接依赖** | 同上 |
| niki914/zafiro | MIT | 宽松 | ✅ **可深度参考/借鉴代码** | 同上；仍建议意译重写保独立性 |
| TensorHub-ORG/Coomi | Apache-2.0 | 宽松+专利授权 | ✅ **可借鉴代码** | Apache-2.0 需保留 NOTICE |
| nerve-sparks/iris_android | Apache-2.0 | 宽松+专利授权 | ✅ **可借鉴代码** | 同上 |
| eclipse-jgit/jgit | EDL-1.0（BSD 式） | 宽松 | ✅ **可直接依赖** | 保留声明即可 |
| Rosemoe/sora-editor | LGPL-2.1 | 弱 Copyleft | ⚠️ **库方式可用**（附义务），本方案已决定不引 | 动态/独立库使用 + 提供源码书面要约即可；我们选自绘，零义务 |
| proot | GPL-3.0 | 强 Copyleft | ⚠️ **仅二进制随包**，代码零引用 | 独立可执行文件 + 附 LICENSE + 源码书面要约；不静态链接进我们的 .so |
| AnLinux | Apache-2.0（脚本） | 宽松 | ✅ **思路照搬**（脚本我们自己写） | 脚本逻辑不构成衍生 |
| rikkahub/rikkahub | AGPL-3.0 | 强 Copyleft+网络 | ❌ **只吸收架构思想，净室重写** | 连"参考后重写"都要留档证明独立性 |
| AAswordman/Operit | LGPL-3.0 | 弱 Copyleft | ❌ **只吸收架构思想** | 代码引用会污染整体 |
| jieapi/AiCode | GPL-3.0 | 强 Copyleft | ❌ **只吸收架构思想** | 同上 |
| termux/termux-app | GPL-3.0 | 强 Copyleft | ❌ **只吸收架构思想，VT100 状态机按公开规范重写** | ctlseqs 是公开规范，实现照规范写不侵权 |

## 三条判定规则（遇到新项目时自查）

1. **MIT / Apache-2.0 / BSD / EDL** → 可依赖、可借鉴代码。保留版权声明与 NOTICE。
2. **LGPL-2.1 / LGPL-3.0（库形态）** → 可以作为**未修改的独立库**依赖，履行「源码书面要约 + 替换权」；**不允许**把它的源码 copy 进我们的源码树混合编译。sora-editor 属于此类——我们不用，故无义务。
3. **GPL / AGPL** → **架构思想可学，代码零接触**。proot 是唯一例外，且以「独立二进制 + 分离进程」形态使用（mere aggregation 辩护路径），随包附：
   - `assets/licenses/proot-LICENSE.txt`（GPL-3.0 全文）
   - `assets/licenses/proot-SOURCE-OFFER.txt`（源码书面要约：提供官方仓库地址与版本 tag）

## proot 二进制随包的合规细节（第 5 步落地时执行）

- [ ] 从官方 release 或自行 NDK 编译获取 arm64-v8a `proot`，**记录版本 tag 与来源**
- [ ] APK 内放 LICENSE 全文 + 书面要约（上述两文件，构建时打进 assets）
- [ ] App 内「关于」页展示开源许可清单（AboutScreen.kt 加一节，用户可见）
- [ ] **绝不** `System.loadLibrary` 链接它；只通过 `ProcessBuilder` 起独立进程
- [ ] 我们的 Kotlin/C++ 代码与 proot 无静态/动态链接关系，`.so` 只有 llama.cpp（MIT）与我们自写的 pty 封装

## 我们自己的开源许可证选择（预决策，未最终定）

候选：**Apache-2.0**（推荐）/ MIT。

| 考量 | Apache-2.0 | MIT |
|---|---|---|
| 专利授权条款 | ✅ 显式授予 | ❌ 无 |
| 对二次分发者的约束 | 保留 NOTICE + 声明修改 | 仅保留版权 |
| 与企业贡献者兼容 | ✅ | ✅ |
| AgentKit 场景 | 将来可能有第三方提 PR/二次开发，专利条款是保护 | 最简洁 |

**倾向 Apache-2.0**，与 Coomi/iris_android/JGit 生态一致；最终定版在开源前与用户确认。注意：选了 Apache-2.0 后，**依赖树里不能出现 GPL 链接**——proot 的独立进程形态要经得住这个检验（上表第三条）。

## 自用阶段的护栏（现在就要做的）

1. **代码来源标注**：任何从外部项目"参考后重写"的文件，在文件头 KDoc 注明「架构参考自 X（许可证），净室重写」。01-07 号文档已列出每处参考对象。
2. **禁止直接 copy 的动作**：不 clone GPL/AGPL 项目源码进本仓库；不 `gradle` 依赖 GPL 产物（proot 不走 gradle，走 assets）。
3. **llama.cpp vendor 策略**：`app/src/main/cpp/` 下的 llama.cpp 源码**保留其 MIT 版权头与 LICENSE 文件**（vendored 是 MIT 允许的标准做法）。
4. **设计文档不侵权**：`docs/design-preview-v3.html` 等为原创，未使用任何竞品素材。

## 审查检查点

| 时点 | 动作 |
|---|---|
| 第 3 步（llama.cpp）落地 | 确认 cpp/ 下 LICENSE 完整、.so 无其他来源混入 |
| 第 4 步（终端）落地 | 确认 VT100 状态机为规范重写，无 Termux 代码痕迹；pty 封装为自写 |
| 第 5 步（容器）落地 | 执行上文 proot 合规 checklist |
| 开源前 | 全仓库 license 扫描（`gradle-license-plugin` 或 `scancode-toolkit`），本文档更新为终版 |
