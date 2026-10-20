package com.example.agentkit.core.llm

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * LLM 通道共用的 OkHttp 配置（W-46）。
 *
 * ## 为什么要单独建一份
 *
 * 三处客户端（[com.example.agentkit.core.LLMClient]、[ClaudeClient]、[GeminiClient]）
 * 原来都是裸 `OkHttpClient()`，等于用 OkHttp 默认值：**读超时 10 秒**。
 * 而读超时的语义是「两次收到字节之间的最长间隔」——推理模型（DeepSeek-R1 / QwQ / o1 类）
 * 在吐第一个 token 之前思考半分钟是常态，长系统提示（本项目上限 4000 字符）与弱网下更久。
 * 结果：请求在 10 秒时抛 `SocketTimeoutException`，被 `RealAgentLoop.isRetryable` 当成
 * 网络抖动，再按 3s/10s/25s 退避重试三次，用户干等 40 秒后看到「调用失败」，
 * 日志里全是「网络超时」——与真实原因（模型在想）完全对不上。
 *
 * ## 取值理由
 *
 * - `readTimeout = 180s`：覆盖「首 token 前长时间静默」的极端情况；真有 3 分钟不出一个字节的
 *   端点，基本已死，报错是对的。
 * - `callTimeout = 0`（不限整通时长）：一次长回答可能要流几分钟，按整通计时会误杀；
 *   真正的失控由协程取消（用户点停止 / ViewModel 回收）兜底。
 * - `writeTimeout = 60s`：请求体最大不过几 MB（含图片 data URL），弱网也够。
 * - 连接 15s：比默认 10s 略宽，移动网络握手慢时不至于立刻失败。
 *
 * ## 为什么共享一个实例
 *
 * OkHttp 的连接池、Dispatcher 线程池都是**按实例**持有的。每个 Client 各建一个
 * 等于给同一批端点开多份连接池与线程，切换供应商时尤其浪费。三家协议共用本实例。
 */
object LlmHttp {

    /** 共享实例（懒加载，首次使用时才建线程池）。 */
    val shared: OkHttpClient by lazy { build() }

    private fun build(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // 0 = 不限整通时长：长回答流式输出可以持续数分钟，靠协程取消收口
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
