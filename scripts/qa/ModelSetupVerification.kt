import com.example.agentkit.core.llm.FailureKind
import com.example.agentkit.core.llm.ProviderCatalog
import com.example.agentkit.core.llm.ProviderGroup
import com.example.agentkit.core.llm.RecoveryAction
import com.example.agentkit.core.llm.diagnoseException
import com.example.agentkit.core.llm.diagnoseHttpFailure
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * P1（配模型路径）harness：连接失败归因矩阵 + Key 获取入口覆盖。
 *
 * 两条都是纯函数/纯数据，可直接断言：
 *  - [diagnoseHttpFailure] / [diagnoseException]：把整张状态码与异常矩阵跑一遍。
 *    重点是**不要把限流说成 Key 错**、**不要把 404 说成网络问题**——这两条是
 *    真实用户会照着错误提示做错事的场景。
 *  - [ProviderCatalog.consoleUrlOf]：凡是需要 Key 的非本地供应商都必须有控制台地址；
 *    本地部署与未收录 id 必须返回空串（宁可不显示入口，也不给错链接）。
 *
 * 另加一条源码断言：模型页必须在 Key 为空时渲染该入口、且失败卡片给出可点动作
 * （否则数据做对了，用户还是看不到）。
 */
private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

fun main() {
    println("== P1 harness：连接失败归因 + Key 获取入口 ==")

    check("401 / 403 → Key 问题，动作是「去拿 Key」") {
        listOf(401, 403).forEach { code ->
            val d = diagnoseHttpFailure(code)
            assertTrue("$code 应归因 KEY，实际 ${d.kind}", d.kind == FailureKind.KEY)
            assertTrue("$code 动作应为 GET_KEY，实际 ${d.action}", d.action == RecoveryAction.GET_KEY)
        }
    }

    check("402 余额不足 → 也是 Key 侧问题（去控制台处理）") {
        val d = diagnoseHttpFailure(402)
        assertTrue("402 应归因 KEY，实际 ${d.kind}", d.kind == FailureKind.KEY)
        assertTrue("402 动作应为 GET_KEY，实际 ${d.action}", d.action == RecoveryAction.GET_KEY)
        assertTrue("文案应提到余额，实际：${d.suggestion}", d.suggestion.contains("余额"))
    }

    check("429 → 限流，不得说成 Key 错；动作是重试") {
        val d = diagnoseHttpFailure(429)
        assertTrue("429 应归因 RATE_LIMIT，实际 ${d.kind}", d.kind == FailureKind.RATE_LIMIT)
        assertTrue("429 动作应为 RETRY，实际 ${d.action}", d.action == RecoveryAction.RETRY)
        assertTrue(
            "文案必须明确「不是 Key 错了」，实际：${d.suggestion}",
            d.suggestion.contains("不是 Key 错")
        )
    }

    check("404 → 模型/端点不存在，动作是拉模型列表") {
        val d = diagnoseHttpFailure(404)
        assertTrue("404 应归因 MODEL，实际 ${d.kind}", d.kind == FailureKind.MODEL)
        assertTrue("404 动作应为 PICK_MODEL，实际 ${d.action}", d.action == RecoveryAction.PICK_MODEL)
    }

    check("400 + 响应体提到 model → 归因模型名；400 其它 → 归因端点") {
        val modelIssue = diagnoseHttpFailure(400, "{\"error\":{\"message\":\"model not found\"}}")
        assertTrue("应归因 MODEL，实际 ${modelIssue.kind}", modelIssue.kind == FailureKind.MODEL)
        assertTrue("动作应为 PICK_MODEL", modelIssue.action == RecoveryAction.PICK_MODEL)

        val paramIssue = diagnoseHttpFailure(400, "{\"error\":\"invalid temperature\"}")
        assertTrue("应归因 ENDPOINT，实际 ${paramIssue.kind}", paramIssue.kind == FailureKind.ENDPOINT)
    }

    check("5xx → 服务端问题（不是用户配置），动作重试") {
        listOf(500, 502, 503).forEach { code ->
            val d = diagnoseHttpFailure(code)
            assertTrue("$code 应归因 SERVER，实际 ${d.kind}", d.kind == FailureKind.SERVER)
            assertTrue("$code 动作应为 RETRY，实际 ${d.action}", d.action == RecoveryAction.RETRY)
            assertTrue("文案应说明不是用户配置的问题", d.suggestion.contains("不是你配置的问题"))
        }
    }

    check("其它 4xx → 端点问题") {
        val d = diagnoseHttpFailure(418)
        assertTrue("418 应归因 ENDPOINT，实际 ${d.kind}", d.kind == FailureKind.ENDPOINT)
    }

    check("域名解析失败 → 端点问题，且提示 localhost 不能用于自建") {
        val d = diagnoseException(UnknownHostException("api.example.invalid"))
        assertTrue("应归因 ENDPOINT，实际 ${d.kind}", d.kind == FailureKind.ENDPOINT)
        assertTrue("文案应提示域名写错，实际：${d.suggestion}", d.suggestion.contains("域名"))
    }

    check("超时 → 网络问题，动作重试，并提示局域网 http") {
        val d = diagnoseException(SocketTimeoutException("timeout"))
        assertTrue("应归因 NETWORK，实际 ${d.kind}", d.kind == FailureKind.NETWORK)
        assertTrue("动作应为 RETRY，实际 ${d.action}", d.action == RecoveryAction.RETRY)
        assertTrue("文案应提示局域网 http，实际：${d.suggestion}", d.suggestion.contains("http://"))
    }

    check("TLS 失败 → 端点问题（可能是 https 用错）") {
        val d = diagnoseException(SSLHandshakeException("bad cert"))
        assertTrue("应归因 ENDPOINT，实际 ${d.kind}", d.kind == FailureKind.ENDPOINT)
    }

    check("连接被拒 → 网络问题（端口没服务）") {
        val d = diagnoseException(ConnectException("refused"))
        assertTrue("应归因 NETWORK，实际 ${d.kind}", d.kind == FailureKind.NETWORK)
        assertTrue("文案应提到端口，实际：${d.suggestion}", d.suggestion.contains("端口"))
    }

    check("未知异常 / null → 兜底网络问题，不崩") {
        listOf(null, IllegalStateException("boom")).forEach { t ->
            val d = diagnoseException(t)
            assertTrue("兜底应归因 NETWORK，实际 ${d.kind}", d.kind == FailureKind.NETWORK)
            assertTrue("兜底建议不应为空", d.suggestion.isNotBlank())
        }
    }

    check("需要 Key 的非本地供应商，都有 Key 创建页") {
        val missing = ProviderCatalog.all.filter { provider ->
            provider.requiresApiKey &&
                provider.group != ProviderGroup.LOCAL &&
                ProviderCatalog.consoleUrlOf(provider.id).isBlank()
        }.map { it.id }
        assertTrue("缺少控制台地址：$missing", missing.isEmpty())
    }

    check("控制台地址必须是 https 直链（非 http、非空主机）") {
        ProviderCatalog.all.forEach { provider ->
            val url = ProviderCatalog.consoleUrlOf(provider.id)
            if (url.isNotBlank()) {
                assertTrue("${provider.id} 的地址应以 https:// 开头：$url", url.startsWith("https://"))
                assertTrue("${provider.id} 的地址含空白：$url", url.none { it.isWhitespace() })
                val host = url.removePrefix("https://").substringBefore("/")
                // xAI 等厂商的控制台根路径本身就是 Key 页，不能靠「路径要够长」判断，
                // 只校验主机名合法（有点、够长），避免把合法根地址误判成首页。
                assertTrue(
                    "${provider.id} 的主机名不合法：$host",
                    host.contains(".") && host.length >= 7
                )
            }
        }
    }

    check("本地部署与未收录 id 不返回控制台地址") {
        listOf("ollama", "lmstudio", "vllm", "local-llama", "my-custom-endpoint").forEach { id ->
            assertTrue(
                "$id 不应有控制台地址，实际 ${ProviderCatalog.consoleUrlOf(id)}",
                ProviderCatalog.consoleUrlOf(id).isBlank()
            )
        }
    }

    check("模型页：Key 为空时渲染获取入口，且只在有地址时渲染") {
        val src = read("app/src/main/java/com/example/agentkit/ui/ModelConfigScreen.kt")
        assertTrue("未按 apiKey 为空判断", src.contains("if (apiKey.isBlank())"))
        assertTrue("未调用 consoleUrlOf", src.contains("ProviderCatalog.consoleUrlOf(providerId)"))
        assertTrue("未在地址为空时不渲染", src.contains("if (consoleUrl.isNotBlank())"))
        assertTrue("缺少入口组件", src.contains("private fun KeyConsoleLink("))
    }

    check("模型页：失败卡片给出可执行动作（拿 Key / 选模型 / 重试）") {
        val src = read("app/src/main/java/com/example/agentkit/ui/ModelConfigScreen.kt")
        assertTrue("未处理 GET_KEY", src.contains("RecoveryAction.GET_KEY ->"))
        assertTrue("未处理 PICK_MODEL", src.contains("RecoveryAction.PICK_MODEL ->"))
        assertTrue("未处理 RETRY", src.contains("RecoveryAction.RETRY ->"))
    }

    check("没有控制台地址时不渲染「去拿 Key」按钮（不让按钮空转）") {
        val src = read("app/src/main/java/com/example/agentkit/ui/ModelConfigScreen.kt")
        assertTrue("onGetKey 应为可空回调", src.contains("onGetKey: (() -> Unit)? = null"))
        assertTrue("渲染前未判空", src.contains("if (onGetKey != null)"))
        assertTrue("未按地址是否为空决定回调", src.contains("if (consoleUrl.isNotBlank())"))
    }

    check("模型页：测试成功可一键保存并回对话") {
        val src = read("app/src/main/java/com/example/agentkit/ui/ModelConfigScreen.kt")
        assertTrue("缺少成功收尾按钮", src.contains("保存并回到对话"))
        assertTrue("缺少 onUseAndReturn 回调", src.contains("onUseAndReturn"))
    }

    println()
    println("PASS=$pass FAIL=$fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
