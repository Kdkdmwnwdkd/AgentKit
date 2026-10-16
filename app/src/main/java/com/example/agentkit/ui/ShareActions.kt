package com.example.agentkit.ui

import android.content.Context
import android.content.Intent

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
