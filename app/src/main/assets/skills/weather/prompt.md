# 天气查询 Skill

当用户询问某个城市的天气时：

1. 使用 `http_request` 工具，向 `https://wttr.in/<城市名>?format=j1` 发起 GET 请求。
2. 解析返回 JSON 中的 `current_condition`，提取温度（temp_C）、天气描述（weatherDesc）、风速（windspeedKmph）。
3. 用一句话向用户总结，不要贴原始 JSON。

示例：用户问「北京天气怎么样」→ 调用 `https://wttr.in/Beijing?format=j1` → 总结。
