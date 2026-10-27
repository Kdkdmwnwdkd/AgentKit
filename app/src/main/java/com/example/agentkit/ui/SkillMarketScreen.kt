package com.example.agentkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.skill.market.MarketInstallState
import com.example.agentkit.skill.market.MarketUiItem
import java.util.Locale

/**
 * 插件市场页（W-48）。
 *
 * 与「技能中心」的分工：
 *  - 技能中心 = **本机已装**（授权、卸载、看能力与安全策略）；
 *  - 本页 = **远端可装**（浏览、安装/更新、管理市场源）。
 * 两边共用同一套安装实现（`SkillImportRepository.installZip`），所以 Zip Slip、id 白名单、
 * 失败回滚、导入即降权这些安全约束一处生效、两处一致。
 *
 * 交互约定：
 *  - 一次只允许一个安装/卸载（VM 用 `marketBusyId` 守卫），按钮按 id 置灰而不是全局禁用——
 *    用户能清楚看到「正在处理的是哪个包」；
 *  - 状态行（`marketStatus`）承担所有反馈：刷新结果、源错误、安装成功/失败，页面不再弹 Toast。
 */
@Composable
fun SkillMarketScreen(vm: ChatViewModel) {
    val sources by vm.marketSources.collectAsStateWithLifecycle()
    val items by vm.marketItems.collectAsStateWithLifecycle()
    val loading by vm.marketLoading.collectAsStateWithLifecycle()
    val busyId by vm.marketBusyId.collectAsStateWithLifecycle()
    val status by vm.marketStatus.collectAsStateWithLifecycle()

    var sourceInput by rememberSaveable { mutableStateOf("") }
    // 市场源默认收起：首屏先给"能装什么"，配置放进去才展开（低频操作不占首屏）
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }
    // 卸载必须二次确认：它删的是磁盘目录 + 授权状态，误点一次就得重新装、重新授权
    var confirmUninstall by remember { mutableStateOf<MarketUiItem?>(null) }

    // 首次进入自动刷新一次：否则用户看到的是一张空列表，还得自己去找右上角刷新
    LaunchedEffect(Unit) { vm.refreshMarket() }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = AppDest.MARKET.title,
            actionIcon = Icons.Outlined.Refresh,
            actionDescription = "刷新市场",
            actionEnabled = !loading,
            onAction = { vm.refreshMarket() }
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberPersistentScrollState())
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space4)
        ) {
            // —— 市场源：收起成一行摘要，把首屏让给插件列表 ——
            // 工具型界面的首屏应先回答「能装什么」，而不是「配置在哪」；源管理是低频操作。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    Modifier.padding(
                        horizontal = AgentSpacing.space4,
                        vertical = AgentSpacing.space2
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(AgentSpacing.space3))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "市场源",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (sources.isEmpty()) "尚未添加，点「管理」填一个索引地址"
                                else "${sources.size} 个索引 · 点「管理」增删",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AkTextButton(
                            text = if (sourcesOpen) "收起" else "管理",
                            onClick = { sourcesOpen = !sourcesOpen }
                        )
                    }

                    if (sourcesOpen) {
                        Spacer(Modifier.height(AgentSpacing.space3))
                        Text(
                            text = "索引是一个公开 JSON 地址，列出插件名、版本与下载地址。" +
                                "安装前会校验体积与 SHA-256，并复用「导入技能」的同一套安全链路。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(AgentSpacing.space3))
                        AkTextField(
                            value = sourceInput,
                            onValueChange = { sourceInput = it },
                            label = "索引地址",
                            placeholder = "https://example.com/agentkit-market.json",
                            machine = true
                        )
                        Spacer(Modifier.height(AgentSpacing.space3))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
                        ) {
                            AkPrimaryButton(
                                text = "添加并刷新",
                                icon = Icons.Outlined.Download,
                                modifier = Modifier.weight(1f),
                                enabled = sourceInput.isNotBlank() && !loading,
                                onClick = {
                                    if (vm.addMarketSource(sourceInput.trim())) sourceInput = ""
                                }
                            )
                            AkOutlinedButton(
                                text = "刷新",
                                icon = Icons.Outlined.Refresh,
                                modifier = Modifier.weight(1f),
                                enabled = !loading && sources.isNotEmpty(),
                                onClick = { vm.refreshMarket() }
                            )
                        }
                        if (sources.isEmpty()) {
                            Spacer(Modifier.height(AgentSpacing.space3))
                            Text(
                                text = "尚未添加市场源。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            sources.forEach { src ->
                                Spacer(Modifier.height(AgentSpacing.space2))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
                                ) {
                                    Text(
                                        text = src.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    AkTextButton(
                                        text = "移除",
                                        icon = Icons.Outlined.Delete,
                                        onClick = { vm.removeMarketSource(src.url) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (items.isEmpty() && sources.isNotEmpty() && !loading) {
                Text(
                    text = "没有可显示的插件。点右上角刷新，或检查索引地址是否可访问。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items.forEach { item ->
                MarketPluginCard(
                    item = item,
                    busy = busyId != null,
                    working = busyId == item.plugin.id,
                    onInstall = { vm.installMarketPlugin(item.plugin.id) },
                    onUninstall = { confirmUninstall = item }
                )
            }
        }
    }

    confirmUninstall?.let { target ->
        AkConfirmDialog(
            title = "卸载「${target.plugin.name}」",
            message = "将删除本地技能目录并撤销其授权；市场条目仍保留，之后可以重新安装。",
            confirmText = "卸载",
            danger = true,
            onConfirm = {
                vm.uninstallMarketPlugin(target.plugin.id)
                confirmUninstall = null
            },
            onDismiss = { confirmUninstall = null }
        )
    }
}

/** 单个插件卡片：元信息 + 状态 + 一个主操作 +（已装时）卸载。 */
@Composable
private fun MarketPluginCard(
    item: MarketUiItem,
    busy: Boolean,
    working: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    val plugin = item.plugin
    val stateText = when (item.state) {
        MarketInstallState.NOT_INSTALLED -> "未安装"
        MarketInstallState.INSTALLED -> "已安装 ${item.installedVersion.orEmpty()}"
        MarketInstallState.UPDATE_AVAILABLE -> "可更新（已装 ${item.installedVersion.orEmpty()}）"
    }
    AkSectionCard(
        title = plugin.name,
        icon = Icons.Outlined.Extension,
        description = plugin.description.ifBlank { "（作者没有填写描述）" }
    ) {
        Text(
            text = listOf(
                "版本 ${plugin.version}",
                plugin.author.ifBlank { "未知作者" },
                sizeText(plugin.sizeBytes)
            ).joinToString(" · ") + " · $stateText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (plugin.permissions.isNotEmpty()) {
            Spacer(Modifier.height(AgentSpacing.space2))
            Text(
                text = "声明能力：" + plugin.permissions.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(AgentSpacing.space3))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
        ) {
            when (item.state) {
                MarketInstallState.NOT_INSTALLED -> AkPrimaryButton(
                    text = if (working) "安装中…" else "安装",
                    icon = Icons.Outlined.Download,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = onInstall
                )

                MarketInstallState.UPDATE_AVAILABLE -> AkPrimaryButton(
                    text = if (working) "更新中…" else "更新到 ${plugin.version}",
                    icon = Icons.Outlined.Download,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = onInstall
                )

                MarketInstallState.INSTALLED -> {
                    AkOutlinedButton(
                        text = if (working) "重装中…" else "重新安装",
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        onClick = onInstall
                    )
                    AkTextButton(
                        text = "卸载",
                        icon = Icons.Outlined.Delete,
                        enabled = !busy,
                        onClick = onUninstall
                    )
                }
            }
        }
    }
}

/** 体积文案：未声明时明确说「未知」，不拿 0 假装成 0 字节。 */
private fun sizeText(bytes: Long): String = when {
    bytes <= 0L -> "体积未知"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
}
