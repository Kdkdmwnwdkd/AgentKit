package com.example.agentkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.example.agentkit.core.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * 关于页（UX v1 §1.9）：版本、设计规范、开源许可与数据去向。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题「关于」，无右上动作）。
 * 本页纯静态展示，不接 ViewModel——装一次看一次，不值得为它拉一份状态。
 *
 * W-65：设置页把「关于」与「权限引导」压成了一行（关于与帮助），所以权限入口搬到这里，
 * 紧跟应用标识之后——先知道这是什么，再知道要给它什么权限。
 */
@Composable
fun AboutScreen(onOpenPermissionGuide: () -> Unit = {}) {
    val ctx = LocalContext.current
    // 版本号从 PackageManager 读：不依赖 BuildConfig 生成开关，AGP 关掉 buildConfig 也不会编译失败
    val versionName = remember(ctx) {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "—"
    }
    // 「这一版是什么时候出的」直接读 APK 的安装时间戳 —— 它就是构建产物写入的时间，
    // 比在 gradle 里塞 BuildConfig 字段省事，也不会因为 buildConfig 开关被关掉而编译失败。
    //
    // 时区显式钉成 Asia/Shanghai：CI 机器跑在 UTC，用默认时区会显示成凌晨四点，
    // 和用户手机上看到的发布时间差 8 小时。
    val buildTime = remember(ctx) {
        runCatching {
            val ts = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
                .format(Date(ts))
        }.getOrNull() ?: "—"
    }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(title = AppDest.ABOUT.title)
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
            // —— 应用标识 ——
            AkSectionCard(title = "北辰", icon = Icons.Outlined.Shield) {
                Text(
                    text = "跑在本机的对话式手机助手：接你自己配置的模型接口，" +
                        "在危险操作前拦一道审批，把日志与记忆都留在本机。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                Text(
                    text = "版本 $versionName  ·  $buildTime（北京时间）",
                    style = machineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                // 更新入口放在版本号下面：用户想「看看有没有新版」时，视线就在这一行
                UpdateRow()
            }

            // —— 权限怎么给（W-65 从设置页并进来的那一半）——
            AkGroup(
                title = "帮助",
                description = "五项设备能力逐项说明「开了能做什么、不开会缺什么」"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Shield,
                    title = "权限怎么给",
                    description = "无障碍、Shizuku、悬浮窗、通知与电池优化，逐项引导",
                    onClick = onOpenPermissionGuide
                )
            }

            // —— 设计规范 ----
            AkSectionCard(
                title = "设计规范",
                icon = Icons.Outlined.Style,
                description = "视觉规范 v3.1（暖纸 Warm Paper）+ UX v1（信息架构与交互规则）。" +
                    "三层底色分层、0 阴影 0 描边、按压 = scale 0.97 + 100ms、" +
                    "动效只四个 token；色值全部取自规范 §3 色板，本页不引入规范外色相。"
            )

            // —— 数据去向 ----
            AkSectionCard(
                title = "数据去向",
                icon = Icons.Outlined.Lock,
                description = "对话内容、前台应用名与最近通知会随请求发送到你配置的模型接口，" +
                    "除此之外不外传到任何地方；日志与记忆只写本机 filesDir，" +
                    "可在「运行日志」「记忆库」两页自行导出或清空。"
            )

            // —— 开源许可 ----
            AkSectionCard(
                title = "开源许可",
                icon = Icons.Outlined.Info,
                description = "本项目使用下列开源组件，各组件版权归其作者所有：" +
                    "\n· Jetpack Compose（UI / Material3 / Icons）— Apache 2.0" +
                    "\n· AndroidX Activity、Lifecycle — Apache 2.0" +
                    "\n· Kotlin、Kotlinx Coroutines、kotlinx.serialization — Apache 2.0" +
                    "\n· OkHttp — Apache 2.0" +
                    "\n· Rhino（Mozilla）— MPL 2.0"
            )

            // —— 收尾印记：应用标记 + 规范版本，一屏的落款 ——
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(AgentSpacing.space2))
                Text(
                    text = "北辰 · 设计规范 v3.1 · UX v1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


/** 检查更新的三种终态 + 进行中。 */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Latest : UpdateState
    data class HasUpdate(val latest: Int) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * 版本行 + 检查更新。
 *
 * 失败不弹窗、不重试：`api.github.com` 在国内不一定通，硬转圈只会让人以为卡死。
 * 失败时直接把加速下载链接给出来——用户要的是新包，不是「检查成功」这个状态。
 */
@Composable
private fun UpdateRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val currentCode = remember(ctx) {
        runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }.getOrDefault(0)
    }

    fun startCheck() {
        if (state == UpdateState.Checking) return
        state = UpdateState.Checking
        scope.launch {
            UpdateChecker.check(currentCode).fold(
                onSuccess = { info ->
                    state = if (info.hasUpdate) UpdateState.HasUpdate(info.latestCode) else UpdateState.Latest
                },
                onFailure = { e ->
                    state = UpdateState.Failed(e.message?.take(60) ?: "网络错误")
                }
            )
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (val s = state) {
                    UpdateState.Idle -> "当前构建号 $currentCode"
                    UpdateState.Checking -> "正在检查…"
                    UpdateState.Latest -> "已是最新版（构建 $currentCode）"
                    is UpdateState.HasUpdate -> "有新版本：构建 ${s.latest}（当前 $currentCode）"
                    is UpdateState.Failed -> "检查失败：${s.reason}"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (state is UpdateState.HasUpdate) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            when (state) {
                is UpdateState.HasUpdate -> AkTextButton(
                    text = "去下载",
                    onClick = { openUrl(ctx, UpdateChecker.DOWNLOAD_URL) }
                )
                UpdateState.Checking -> Unit
                else -> AkTextButton(text = "检查更新", onClick = { startCheck() })
            }
        }
        // 检查失败时也给下载入口：多半是接口不通，但包本身是能下的
        if (state is UpdateState.Failed) {
            AkTextButton(
                text = "直接下载最新包",
                onClick = { openUrl(ctx, UpdateChecker.DOWNLOAD_URL) }
            )
            AkTextButton(
                text = "打开 Release 页",
                onClick = { openUrl(ctx, UpdateChecker.RELEASE_PAGE) }
            )
        }
    }
}

private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
