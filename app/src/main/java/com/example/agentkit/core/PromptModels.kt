package com.example.agentkit.core

import kotlinx.serialization.Serializable

/**
 * 功能五（P1-7）：一条提示词（快捷指令）。
 *
 * 对标 Coomi PromptLibrary（SavedPrompt {id,title,content,tags}）与 QuickCommands
 * （一键填入输入框）；RikkaHub 的 PromptLibrary 实为注入位点设置、Operit 无此功能，
 * 故以 Coomi 为唯一参照，净室重写。
 *
 * @param builtin true = 内置提示词：不可编辑不可删除（与 Coomi `builtin:` 前缀同语义），
 *   用户可「另存为自定义」后自由改。
 */
@Serializable
data class PromptItem(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val builtin: Boolean = false
)
