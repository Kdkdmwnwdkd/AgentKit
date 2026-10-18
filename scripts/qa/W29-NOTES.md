# W-29 批次记录：自定义请求头/请求体 + 余额查询

> 依据：缺口 #44（自定义 HTTP headers/body）、#42（余额查询）。P1 收尾两件。

## 一、自定义请求头 / 请求体（#44）

### 为什么此前等于没有
`LLMConfig` 早就支持 `customHeaders` / `customBody`，但 `ModelConfig` 里**没有这两个字段**，
UI 也没有入口——能力躺在协议层，用户碰不到。

### 现在
模型配置页「模型接入」卡底部新增折叠区「高级：自定义请求头 / 请求体」：
- **请求头**：每行 `Key: Value`，`#` 开头为注释；键名清洗成可打印 ASCII；
  同名后者覆盖；值里的冒号保留（`Referer: https://a.com/x`）。
- **请求体**：一段 JSON 对象，合并进请求 JSON；非对象/坏 JSON 当场红字提示且**不阻断保存**
  （保存时校验，非法内容原样留着让用户改）。

数据链：`ModelConfig.customHeaders/customBody` → `LLMConfig` → `buildRequest`（追加头）/
`buildJsonObject`（合并 body）。`parseCustomBody` 解析失败返回空 map，绝不让一个手滑
把对话打挂。

### 典型用途
中转站要求 `HTTP-Referer` / `X-Title`；厂商私有参数 `top_k`、`enable_thinking`。

## 二、余额查询（#42）

### 现实：没有统一接口
| 供应商 | 端点 | 字段 |
|---|---|---|
| DeepSeek | `/user/balance` | `balance_infos[].total_balance` + `currency` |
| Moonshot/Kimi | `/users/me/balance` | `data.available_balance` |
| 硅基流动 | `/user/info` | `data.balance` |
| OpenRouter | `/credits` | `total_credits - total_usage` |
| OpenAI / Anthropic / Gemini | **无接口** | 只能官网看账单 |

所以只覆盖确实有接口的四家；其余如实返回「该服务商未提供余额接口」，
**不编造数字**。

### 实现
- `BalanceParsing`：端点映射 + 四种响应形状的解析（纯函数，harness 16 条里的 6 条）；
- `BalanceService`：只做网络（8s/10s 超时、Bearer 鉴权、失败带原因）；
- UI：模型配置页「测试连接」下方「查余额」按钮，结果直接显示在按钮下方。

## 三、验证

- W-29 harness **16/16**（请求头 6 + 请求体 3 + 余额 7）。
- 既有 harness 无回归（27/25/14/9/12/12/13/18）。
- CI：见当批 run。
- APK 反验：W29 组 6 条池条目 + 4 条文案。

## 四、已知边界

- 余额只覆盖四家；其余如实说"没有接口"。
- 自定义请求体只做**浅合并**（顶层键覆盖），不递归合并嵌套对象。
- 请求头不支持多值同名（HTTP 允许，但各家 SDK 用法极少）。
