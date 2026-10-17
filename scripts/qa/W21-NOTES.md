# W-21 批次记录：多 Key 轮询 + 限流自动切换

> 依据：《四家对标-全功能面缺口清单》#41（多把 Key 轮询，P1）。
> 四家（RikkaHub / Operit / AiCode / Coomi）均有，AgentKit 此前单 Key——
> 一旦被限流（429 / 额度耗尽），整轮任务立刻断流，用户只看到一句报错。

## 交付

| 文件 | 作用 |
|---|---|
| `core/llm/KeyPoolPolicy.kt` | **纯函数**：限流判定 / 冷却时长 / 选 Key / 恢复时间 / 摘要，无 Android 无网络 |
| `core/llm/KeyPoolRuntime.kt` | 运行时：冷却状态表（内存）+ 轮询游标，线程安全 |
| `core/llm/KeyPoolStore.kt` | 持久化：按配置档案分组存槽位（只存 id/label/key） |
| `core/LLMClient.kt` | `OpenAIClient` 接入切换；`buildRequest` 参数化 Key；补 `Retry-After` 解析 |
| `ui/ChatViewModel.kt` | 每次取 client 前刷新 Key 池；暴露增删/摘要接口 |
| `ui/SettingsScreen.kt` | 备用 Key 卡片：脱敏列表 + 密码框添加 + 删除 |

## 关键决策

- **单 Key 时零行为变化**：`slots.size <= 1` 或 runtime 为 null 直接走原路径，
  不进切换分支——改造对既有用户的回归面为零。
- **401/403 不换 Key**：换一把也是同样的鉴权错，快速失败比让用户白等更诚实。
- **网络类 IOException 不换 Key**：换 Key 解决不了 DNS/超时问题。
- **冷却优先听服务端**：有 `Retry-After` 就用它（封顶 600 秒），否则指数退避
  30→60→120→300 秒；超长等待直接告诉用户，不无限重试。
- **冷却状态不落盘**：只在内存（进程级），重启重置——避免一次限流把 Key 判死几天。
- **Key 明文不上屏**：UI 只显示前 4 后 4；日志走既有脱敏。
- **协议覆盖如实说明**：只覆盖 OpenAI 兼容协议（deepseek / moonshot / qwen /
  openrouter / ollama 等绝大多数厂商）；Claude / Gemini 原生协议本批未接入，
  保持单 Key 行为，不假装支持。

## 验证

- harness **25/25**（失败判定 6 + 冷却 4 + 状态迁移 4 + 选 Key 7 + 统计 4）。
- CI：见当批 run（推送后回填）。
- APK 反验：W21 组 9 条池条目 + 5 条文案（见 verify-apk-features.py）。

## 真机复测要点

1. 设置页 → 备用 Key → 填第二把 Key（备注可选）→ 添加。
2. 用主 Key 发消息直到被限流（或手动把主 Key 改成无效但备用有效）。
3. 预期：日志出现「Key「主 Key」被限流（429），切换下一把」，请求自动成功，
   用户侧不中断；设置页摘要显示「备用 Key 1/2 把可用，1 把冷却中（最早 30 秒后恢复）」。
4. 两把全挂 → 明确报「所有 N 把 Key 都不可用…最早 xx 秒后恢复」。
