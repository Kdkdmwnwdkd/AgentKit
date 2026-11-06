package com.beichen.polaris.ui

/**
 * 设置页的两个行模型。
 *
 * 原本嵌在 `ChatViewModel` 里。R-14 给设置页收窄签名时，契约不得不引用它们——
 * 而「契约依赖实现类」正是这一轮要拆掉的东西，所以提到顶层：它们是**纯数据**，
 * 与 ViewModel 没有半点关系，放在里面只是历史惯性。
 */

/** 备用 Key 的 UI 视图：只带脱敏后的 Key，**明文绝不进 UI 状态**。 */
data class BackupKeyUi(val id: String, val label: String, val masked: String)

/**
 * 设置页「工具范围」的一行。
 *
 * @param count 该组在**当前注册表**里的工具数（MCP 工具算进 misc）
 * @param pinned 常驻组：不参与淘汰，UI 上不给关闭入口
 */
data class ToolGroupRow(
    val name: String,
    val note: String,
    val count: Int,
    val active: Boolean,
    val pinned: Boolean
)
