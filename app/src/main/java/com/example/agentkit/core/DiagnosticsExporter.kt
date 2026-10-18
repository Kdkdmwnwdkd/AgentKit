package com.example.agentkit.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.example.agentkit.core.llm.KeyPoolStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断包导出（W-19）。
 *
 * 为什么需要：功能变多后（MCP、浏览器、TTS、定时任务），真机问题很难靠一句「不好用」定位。
 * 让用户点一下就能把「设备信息 + 脱敏配置 + 最近日志」打成一份文本发出来，
 * 比反复追问省事得多。
 *
 * 脱敏纪律（必须遵守）：
 *  - API Key 只保留前 4 后 4；
 *  - 日志文本里出现的完整 Key 一律替换；
 *  - 不收集联系人/通知正文/文件内容等任何用户数据。
 */
object DiagnosticsExporter {

    private const val MAX_LOG_LINES = 300

    /** 生成诊断文件；失败返回 null（调用方提示用户，不崩）。 */
    fun export(context: Context, logText: String): File? = runCatching {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        // longVersionCode 是 API 28 才有的；minSdk 26 必须兼容旧字段
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
        val version = "${pkg.versionName} ($versionCode)"
        val device = buildString {
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · Android ").append(Build.VERSION.RELEASE)
            append(" (SDK ").append(Build.VERSION.SDK_INT).append(')')
            append(" · ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        }
        val config = runCatching { ConfigStore(context).load() }.getOrNull()
        val key = config?.apiKey.orEmpty()
        val maskedKey = maskKey(key)

        // W-22 修：备用 Key 也必须脱敏——日志里可能出现备用 Key（401 回显、切换日志）。
        // 只替换主 Key 会让诊断包泄露备用池里的全部 Key。
        val profileId = runCatching { ConfigStore(context).loadActiveId() }.getOrNull() ?: "default"
        val backupKeys = runCatching { KeyPoolStore(context).list(profileId) }
            .getOrDefault(emptyList())
        val allKeys = (listOf(key) + backupKeys.map { it.key })
            .map { it.trim() }
            .filter { it.length >= 8 }
            .distinct()
        var safeLog = logText
        allKeys.forEach { k -> safeLog = safeLog.replace(k, maskKey(k)) }

        val tail = safeLog.lineSequence().toList().takeLast(MAX_LOG_LINES).joinToString("\n")
        val text = buildString {
            appendLine("AgentKit 诊断包")
            appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("版本：$version")
            appendLine("设备：$device")
            appendLine("包名：${context.packageName}")
            appendLine()
            appendLine("—— 模型配置（已脱敏）——")
            appendLine("供应商：${config?.providerId ?: "?"}")
            appendLine("端点：${config?.baseUrl ?: "?"}")
            appendLine("模型：${config?.model ?: "?"}")
            appendLine("Key：$maskedKey")
            if (backupKeys.isNotEmpty()) {
                appendLine("备用 Key（${backupKeys.size} 把）：" +
                    backupKeys.joinToString("、") { "${it.label}=${maskKey(it.key)}" })
            }
            appendLine()
            appendLine("—— 最近 $MAX_LOG_LINES 行日志 ——")
            appendLine(tail.ifBlank { "(无日志)" })
        }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "agentkit-diagnostics-$stamp.txt")
        file.writeText(text)
        AgentLogger.i("Diagnostics", "诊断包已生成：${file.name}（${file.length()} 字节）")
        file
    }.getOrNull()

    /** 调系统分享面板发送诊断文件。 */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AgentKit 诊断包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享诊断包").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { AgentLogger.w("Diagnostics", "分享失败：${it.message}") }
    }

    /** Key 脱敏：前 4 + 后 4，中间固定 4 个星号（短 Key 直接全遮）。 */
    internal fun maskKey(key: String): String = when {
        key.isBlank() -> "(未配置)"
        key.length <= 8 -> "****"
        else -> "${key.take(4)}****${key.takeLast(4)}"
    }
}
