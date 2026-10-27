package com.example.agentkit.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 壁纸与颜色（W-67）：从设置页拆出来的独立子页。
 *
 * ## 为什么拆
 *
 * 这些东西原来是设置页第一组的「一大块」：三个模式 chip + 五色圆点 + HEX 输入框 +
 * 动态取色开关 + 背景图按钮 + 浓度滑杆，一口气占掉一整屏，把真正的设置项全推到
 * 折叠线以下。用户的原话是「搞成一个小的，就比如说壁纸与颜色之类的……别搞得
 * 这一大块不好看」。
 *
 * 拆开之后，设置页只剩一行 `壁纸与颜色 / 天青 · 跟随系统`，点进来才是这一页。
 * 这不是单纯的搬家：**调外观是少数人会反复干的低频事，而找设置是所有人每次都要干的事**，
 * 两者的优先级本来就不该在同一层。
 *
 * ## 页内顺序
 *
 * 模式 → 强调色 → 动态取色（Android 12+）→ 背景图，从「影响面最大」到「最局部」。
 * 前三项都不落盘以外的副作用，改完直接看到；背景图涉及 SAF 授权，所以放最后。
 */
@Composable
fun AppearanceScreen(
    vm: ChatViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        PageTopBar(title = AppDest.APPEARANCE.title)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberPersistentScrollState())
                .navigationBarsPadding()
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space4)
        ) {
            // —— 明暗 ——
            AkGroup(
                title = "模式",
                description = "跟随系统会随系统的深色模式自动切换"
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = AgentSpacing.space4,
                        end = AgentSpacing.space4,
                        top = AgentSpacing.space2,
                        bottom = AgentSpacing.space3
                    ),
                    horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
                ) {
                    ThemeModeChip(
                        label = "跟随系统",
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = { onThemeModeChange(ThemeMode.SYSTEM) }
                    )
                    ThemeModeChip(
                        label = "浅色",
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = { onThemeModeChange(ThemeMode.LIGHT) }
                    )
                    ThemeModeChip(
                        label = "深色",
                        selected = themeMode == ThemeMode.DARK,
                        onClick = { onThemeModeChange(ThemeMode.DARK) }
                    )
                }
            }

            // —— 强调色 ——
            AkGroup(
                title = "强调色",
                description = "只换强调色，背景与文字保持原有对比度"
            ) {
                Spacer(Modifier.height(AgentSpacing.space2))
                AccentPicker()
                Spacer(Modifier.height(AgentSpacing.space3))
            }

            // —— 动态取色（Android 12+ 才有系统能力，低版本不摆开关）——
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AkGroup(
                    title = "动态取色",
                    description = "从系统壁纸里取色，覆盖上面的强调色"
                ) {
                    AkListItem(
                        icon = Icons.Outlined.Image,
                        title = "跟随系统壁纸取色",
                        description = if (dynamicColor) {
                            "已开启：界面强调色由壁纸决定（Android 12+）"
                        } else {
                            "关闭：使用上面选的强调色"
                        },
                        trailing = {
                            AkSwitch(
                                checked = dynamicColor,
                                onCheckedChange = onDynamicColorChange
                            )
                        }
                    )
                }
            }

            // —— 背景图 ——
            BackgroundSection(vm)
        }
    }
}

/** 主题三选 chip（§6.7 筛选 chip 语汇：选中 primary@12% + on-primary-container）。 */
@Composable
private fun ThemeModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    AkChip(text = label, selected = selected, onClick = onClick)
}

/**
 * 强调色选择（W-64）：五个预设 + 一个自定义。
 *
 * 为什么不做色轮：色轮在手机上要占掉一整屏，且用户真正想要的是「接近某个色」，
 * 而不是调 HSV 三个滑杆。给几个调好的预设 + 一个 HEX 输入，够用且不啰嗦。
 *
 * 选中态用「外圈 + 对勾」而不是只放大：色块本身有深浅，只靠尺寸在小屏上分辨不出来。
 */
@Composable
private fun AccentPicker() {
    val ctx = LocalContext.current
    val currentHex = AppearanceState.accentHex
    var custom by remember(currentHex) { mutableStateOf(currentHex ?: "") }

    Column(Modifier.padding(horizontal = AgentSpacing.space4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)) {
            AccentPreset.entries.forEach { preset ->
                val hex = preset.color(false).toHexString()
                val selected = currentHex.equals(hex, ignoreCase = true)
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(preset.color(false))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .clickable { AppearanceState.setAccent(ctx, hex) }
                )
            }
            // 「恢复默认」：不选预设也不选自定义就是品牌色
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { AppearanceState.setAccent(ctx, null) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "默认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(AgentSpacing.space3))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            AkTextField(
                value = custom,
                onValueChange = { custom = it.take(9) },
                label = "自定义颜色",
                placeholder = "#B7862B",
                modifier = Modifier.weight(1f),
                machine = true
            )
            AkTextButton(
                text = "应用",
                onClick = {
                    val parsed = parseAccentHex(custom)
                    if (parsed == null) {
                        Toast.makeText(ctx, "颜色格式是 #RRGGBB，比如 #B7862B", Toast.LENGTH_SHORT).show()
                    } else {
                        AppearanceState.setAccent(ctx, custom)
                    }
                }
            )
        }
        Text(
            text = if (currentHex == null) "当前：默认品牌色"
            else "当前：$currentHex（只改强调色，背景与文字不动）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AgentSpacing.space2)
        )
    }
}

/**
 * hotfix32：背景图分区——SAF 选图（持久化读授权）+「换一张 / 清除」+ 浓度滑杆。
 *
 * 位图本体不落 App 存储（只存 content:// URI 字符串），换机/清缓存即失效，
 * 符合「照片是用户的、App 只借来显示」的语义；intent 必须带
 * FLAG_GRANT_PERSISTABLE_URI_PERMISSION，否则 takePersistableUriPermission
 * 会抛（临时授权在进程重启后失效）。
 *
 * W-67：标题从行内挪到组标题，选中状态写进组的副标题——原来一行里既说「背景图」
 * 又说浓度，文字挤成两行，收进独立页后没必要再这样省地方。
 */
@Composable
private fun BackgroundSection(vm: ChatViewModel) {
    val context = LocalContext.current
    val bgUri by vm.chatBackgroundUri.collectAsStateWithLifecycle()
    val bgAlpha by vm.chatBackgroundAlpha.collectAsStateWithLifecycle()
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        // SAF 默认只授予临时读权限——不 take 的话重启后背景就丢了；
        // 失败（个别 ROM 策略）也不拦选图，临时授权本次会话仍可用
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        vm.setChatBackground(uri.toString())
    }

    AkGroup(
        title = "背景图",
        description = if (bgUri != null) {
            // hotfix33：背景提升为全 App（抽屉/设置/模型/记忆等页面全部透出）
            "全 App 生效（含抽屉与各功能页）· 当前浓度 ${(bgAlpha * 100).toInt()}%"
        } else {
            "选一张照片当全 App 背景，只存路径，不占 App 存储"
        },
        paddedContent = true
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            AkOutlinedButton(
                text = if (bgUri != null) "换一张" else "选择图片",
                icon = Icons.Outlined.Image,
                modifier = Modifier.weight(1f),
                onClick = {
                    pickImage.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                            // 持久化授权必须随 intent 一起请求（否则 take 会抛）
                            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                    )
                }
            )
            if (bgUri != null) {
                AkTextButton(
                    text = "清除",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { vm.setChatBackground(null) }
                )
            }
        }
        if (bgUri != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = AgentSpacing.space3)
            ) {
                Text(
                    text = "浓度",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(AgentSpacing.space3))
                Slider(
                    value = bgAlpha,
                    onValueChange = { vm.setChatBackgroundAlpha(it) },
                    // hotfix33：上限放到 100%（原图）——真机反馈「60% 都不够清楚，要 100%；
                    // 为什么要设置 60%，我们自己不可以弄吗」：浓度是用户的选择，不是产品规定
                    valueRange = 0.05f..1.0f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(AgentSpacing.space2))
                Text(
                    text = "${(bgAlpha * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
