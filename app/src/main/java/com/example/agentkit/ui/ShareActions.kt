package com.example.agentkit.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 系统分享面板发送纯文本（hotfix17 消息分享与 hotfix22 会话导出共用）。
 *
 * 用 ACTION_SEND + text/plain：不依赖任何额外权限，用户自己选目标 App
 * （微信 / 邮件 / 文件管理器…）。面板未安装任何接收方时 createChooser 显示
 * 空态，不崩（系统兜底）。
 *
 * 抽成共用函数而不是两处各写一遍：消息分享与会话导出是同一机制，
 * 复制粘贴会漂移（chooser 标题、blank 守卫各写各的）。
 *
 * @param chooserTitle 面板标题（调用方给场景化文案，如「分享消息」「导出会话」）。
 */
internal fun shareText(context: Context, text: String, chooserTitle: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle))
}

/**
 * hotfix29：会话导出从「分享一段话」升级为「分享一个 .md 真文件」。
 *
 * 真机反馈原话：「我分享的话也是一段话分享，我不是说变成文件啥的」——
 * text/plain 的 EXTRA_TEXT 到微信/邮件里就是一坨纯文本，没有文件名、
 * 不能存文件管理器、Markdown 格式特征全丢。
 *
 * 机制：写 cacheDir/exports/<净化文件名>.md → FileProvider 换 content://
 * （authorities 与 L8 作品库/构建产物安装同一通道，见 AndroidManifest）→
 * ACTION_SEND + type=text/markdown + EXTRA_STREAM + 读 URI 临时授权。
 * 落 cacheDir 而非 filesDir：导出件是易重建的临时产物，系统清缓存时
 * 自动回收，不占用户存储配额。
 *
 * 失败降级：写盘或 FileProvider 抛异常（磁盘满/极端文件名）时回退
 * [shareText] 纯文本分享——保证「分享」这个动作永远有出口，不崩。
 *
 * @param fileName 期望文件名（会话标题），非法字符由 [sanitizeFileName] 净化。
 * @param chooserTitle 面板标题（如「导出会话」）。
 */
internal fun shareMarkdownFile(
    context: Context,
    fileName: String,
    content: String,
    chooserTitle: String
) {
    if (content.isBlank()) return
    val safeName = sanitizeFileName(fileName)
    val file = runCatching {
        File(File(context.cacheDir, "exports").apply { mkdirs() }, "$safeName.md")
            .apply { writeText(content) }
    }.getOrNull() ?: run {
        // 降级：写盘失败（磁盘满等）也不让分享动作死掉
        shareText(context, content, chooserTitle)
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: run {
        shareText(context, content, chooserTitle)
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        // 邮件客户端拿 EXTRA_SUBJECT 当主题；文件管理器忽略，无害
        putExtra(Intent.EXTRA_SUBJECT, safeName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }.onFailure {
        // 目标 App 一个都不认 text/markdown 时 createChooser 空态不抛，
        // 但个别 ROM 会 ActivityNotFoundException——兜底回纯文本
        shareText(context, content, chooserTitle)
    }
}

/**
 * 文件名净化：会话标题是用户自由输入，直接当文件名会踩
 * FAT32/exFAT/EXT4 通用非法字符（\ / : * ? " < > |）与控制字符，
 * 还可能超长（>255B 文件名上限）。统一替换为全角下划线视觉近似，
 * 截到 60 字符（中文标题 20 字，够辨识且不超各文件系统上限）。
 * 净化后为空（标题全是非法字符）时回退 "conversation"。
 */
internal fun sanitizeFileName(raw: String): String {
    val cleaned = raw.map { ch ->
        if (ch in ILLEGAL_FILE_CHARS || ch.code < 0x20) '_' else ch
    }.joinToString("").trim().take(60).trim()
    return cleaned.ifEmpty { "conversation" }
}

private val ILLEGAL_FILE_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
