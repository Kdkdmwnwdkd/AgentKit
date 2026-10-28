package com.example.agentkit.core.llm.local

/**
 * 设备画像：把「这台手机是什么芯片、什么 GPU、多少内存」翻译成「它该走哪条推理路」。
 *
 * 为什么需要这一层，而不是在 UI 里写 `if (Build.SOC_MODEL == "SM8650")`：
 *
 *  1. NPU 路线天生「一机一包」。LiteRT 的 QNN 模型是按具体 DSP 版本 AOT 编译的，
 *     换一颗芯片直接 `Failed to create QNN context: 1002`。Google 官方支持列表从
 *     SM8450（8 Gen 1）开始——也就是说 8 Gen 1 以下、以及全部天玑/麒麟/紫光，
 *     NPU 这条路根本不存在，不是「没适配好」。
 *  2. 所以「覆盖大多数人」的那条路不是 NPU，是 GPU（Vulkan）与 CPU。
 *     而 GPU 的可用性又分三档：Adreno 成熟、Mali 需按机型验证、PowerVR 基本放弃。
 *  3. 这三件事都是字符串判定，可以脱离真机在 CI 里断言。放这里，不散进 UI。
 *
 * 本文件纯 Kotlin，不 import android：调用方把 Build.* 与 GL_RENDERER 采好传进来。
 */
enum class SocVendor(val label: String) {
    QUALCOMM("高通骁龙"),
    MEDIATEK("联发科天玑"),
    KIRIN("海思麒麟"),
    SAMSUNG("三星 Exynos"),
    GOOGLE("Google Tensor"),
    UNISOC("紫光展锐"),
    UNKNOWN("未知");
}

/**
 * GPU 的 Vulkan 成熟度。用来决定「默认开不开 GPU」，不是用来排序性能。
 *
 * GOOD   —— 同一份代码在 Adreno 6xx/7xx 上表现一致，可以默认启用。
 * MIXED  —— 厂商驱动差异大，能跑但需要按机型白名单，默认关、用户可手动开。
 * POOR   —— 驱动长期不更新，跑大模型容易 device lost，不建议开。
 */
enum class VulkanMaturity { GOOD, MIXED, POOR, UNKNOWN }

enum class GpuFamily(val label: String, val maturity: VulkanMaturity) {
    ADRENO("Adreno", VulkanMaturity.GOOD),
    MALI("Mali", VulkanMaturity.MIXED),
    IMMORTALIS("Immortalis", VulkanMaturity.MIXED),
    XCLIPSE("Xclipse", VulkanMaturity.MIXED),
    POWERVR("PowerVR", VulkanMaturity.POOR),
    UNKNOWN("未知", VulkanMaturity.UNKNOWN);
}

/**
 * 设备档位。只由「内存 + SoC 代际」决定，不看跑分——
 * 跑分决定的是「快多少」，内存决定的是「能不能装下」，后者才是硬门槛。
 */
enum class DeviceTier(val label: String) {
    ENTRY("入门（4GB 级）"),
    LOW("中低端（6GB 级）"),
    MID("中高端（8GB 级）"),
    HIGH("旗舰（12GB+）"),
}

/**
 * 设备快照。字段全部允许为 null：Android 12 以下拿不到 SOC_MODEL，
 * 部分厂商 ROM 会把 SOC_MANUFACTURER 填成空串。取不到时按 GPU 与内存兜底。
 *
 * @param glRenderer GL_RENDERER 原始串，如 "Adreno (TM) 740" / "Mali-G77 MC9"。
 *   这是所有 Android 设备都能拿到的，比 SOC_MODEL 可靠得多。
 */
data class LocalDeviceProfile(
    val socModel: String? = null,
    val socManufacturer: String? = null,
    val hardware: String? = null,
    val glRenderer: String? = null,
    val sdkInt: Int = 0,
    val totalRamMb: Int = 0,
)

/** 一台设备最终该走哪条路，以及为什么。 */
data class DeviceAdvice(
    val tier: DeviceTier,
    val vendor: SocVendor,
    val gpu: GpuFamily,
    /** 归一化后的 GPU 名，如 "Adreno 740" / "Mali-G77"；取不到为 null。 */
    val gpuModel: String?,
    /** NPU 是否真的能用（不是「有没有 NPU」——天玑也有 APU，但我们的构建用不了）。 */
    val npuUsable: Boolean,
    val npuReason: String?,
    /** 首选路径。降级顺序仍由 LocalEngineCatalog.planLoad 决定。 */
    val preferredPath: LocalBackend,
    /** 这台机器适合的模型量级，直接进设置页文案。 */
    val modelBudget: String,
    /** 权重 + KV 的估算峰值，超过它就该劝用户换小模型。 */
    val memoryBudgetMb: Int,
    val notes: List<String>,
)

object LocalDeviceRules {

    // ---------------------------------------------------------------- SoC 归一化

    /**
     * 高通型号里 `sm` 后面四位是档位信号：
     *   8xxx = 旗舰（8450=8Gen1, 8550=8Gen2, 8650=8Gen3, 8750=8Elite）
     *   7xxx = 中高端（7675/7750 = 7 系）
     *   6xxx = 中低端（6375 = 骁龙 695）
     * 用档位而不是硬编码型号表，是为了让还没发布的芯片也能落进正确的档。
     */
    fun qualcommTier(soc: String?): Int? {
        val s = soc?.trim()?.lowercase() ?: return null
        val m = Regex("sm(\\d{4})").find(s) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return n / 1000
    }

    /**
     * NPU 能不能用。判定条件是「厂商 + 代际」，不是「有没有 NPU」。
     *
     * 天玑有 APU、麒麟有达芬奇，但 LiteRT 要在上面跑需要各自的 AOT 转换链
     * （NeuroPilot / 达芬奇工具链），我们没有这条链，等于没有。所以这里对
     * 非高通一律返回 false，理由写清楚，而不是含糊地说「不支持」。
     *
     * 高通也要 8 Gen 1（sm8450）起：QNN 的 HTP delegate 官方支持列表从这里开始。
     */
    fun npuUsable(profile: LocalDeviceProfile): Pair<Boolean, String?> {
        val vendor = vendorOf(profile)
        if (vendor != SocVendor.QUALCOMM) {
            val reason = when (vendor) {
                SocVendor.MEDIATEK -> "天玑的 APU 需要 NeuroPilot 单独转换模型，当前构建用不了"
                SocVendor.KIRIN -> "麒麟的达芬奇 NPU 没有公开的转换链，用不了"
                SocVendor.SAMSUNG -> "Exynos 的 NPU 没有对应的 LiteRT 委托，用不了"
                SocVendor.GOOGLE -> "Tensor 的 NPU 需要专用编译版模型，当前构建用不了"
                SocVendor.UNISOC -> "紫光展锐没有可用的 NPU 委托"
                else -> "无法识别芯片厂商，NPU 不可用"
            }
            return false to reason
        }
        // 型号可能只在 SOC_MODEL 或只在 HARDWARE 里，两个都翻，取第一个能解析的。
        val socText = listOfNotNull(profile.socModel, profile.hardware).joinToString(" ").lowercase()
        val m = Regex("sm(\\d{4})").find(socText)
            ?: return false to "取不到骁龙具体型号（Android 11 及以下不给），无法确认 Hexagon 版本"
        val n = m.groupValues[1].toIntOrNull()
            ?: return false to "骁龙型号无法解析，无法确认 Hexagon 版本"
        if (n / 1000 < 8) return false to "这颗骁龙不是 8 系，Hexagon 不在 LiteRT 支持列表内"
        if (n < 8450) return false to "Hexagon 版本低于 v69（8 Gen 1 起才支持），NPU 用不了"
        return true to null
    }

    // ---------------------------------------------------------------- GPU 归一化

    /**
     * 从 GL_RENDERER 解析 GPU 家族与型号。
     *
     * 真实串长这样（各厂商写法不统一，所以用宽松匹配）：
     *   "Adreno (TM) 740"、"Adreno (TM) 640"、"Mali-G77 MC9"、"Mali-G610 MC6"、
     *   "Immortalis-G715 MC11"、"PowerVR Rogue GE8320"、"Xclipse 920"、
     *   联发科部分机型是 "Mali-G57 MC2" 带空格，三星是 "Mali-G78 MP14"（MP 不是 MC）。
     */
    fun gpuOf(renderer: String?): Pair<GpuFamily, String?> {
        val r = renderer?.trim() ?: return GpuFamily.UNKNOWN to null
        if (r.isEmpty()) return GpuFamily.UNKNOWN to null
        val lower = r.lowercase()

        if (lower.contains("adreno")) {
            val m = Regex("adreno\\D{0,6}(\\d{3})").find(lower)
            val model = m?.let { "Adreno ${it.groupValues[1]}" } ?: "Adreno"
            return GpuFamily.ADRENO to model
        }
        if (lower.contains("immortalis")) {
            val m = Regex("immortalis-?(g\\d{3})").find(lower)
            val model = m?.let { "Immortalis-${it.groupValues[1].uppercase()}" } ?: "Immortalis"
            return GpuFamily.IMMORTALIS to model
        }
        if (lower.contains("mali")) {
            // G77 / G610 / G715：先抓 G 开头的三位数，抓不到就只认家族。
            val m = Regex("mali-?(g\\d{2,3})").find(lower)
            val model = m?.let { "Mali-${it.groupValues[1].uppercase()}" } ?: "Mali"
            return GpuFamily.MALI to model
        }
        if (lower.contains("powervr") || lower.contains("rogue")) {
            val m = Regex("(ge\\d{4})").find(lower)
            val model = m?.let { "PowerVR ${it.groupValues[1].uppercase()}" } ?: "PowerVR"
            return GpuFamily.POWERVR to model
        }
        if (lower.contains("xclipse")) {
            val m = Regex("xclipse\\D{0,3}(\\d{3})").find(lower)
            val model = m?.let { "Xclipse ${it.groupValues[1]}" } ?: "Xclipse"
            return GpuFamily.XCLIPSE to model
        }
        return GpuFamily.UNKNOWN to null
    }

    // ---------------------------------------------------------------- 厂商归一化

    /**
     * 厂商判定走三路兜底，因为 Build.* 三个字段各有各的空法：
     *  - SOC_MANUFACTURER（API 31+）：QTI / MediaTek / HiSilicon / samsung / Google
     *  - SOC_MODEL（API 31+）：SM8650 / MT6989 / Tensor G3
     *  - HARDWARE：qcom / mt6893 / kirin9000 / exynos990 / tensor
     * 最后再看 GPU：Adreno 必然是骁龙，Mali 是「非高通」的强信号（但不能反推出天玑）。
     */
    fun vendorOf(profile: LocalDeviceProfile): SocVendor {
        val blob = listOfNotNull(
            profile.socManufacturer, profile.socModel, profile.hardware,
        ).joinToString(" ").lowercase()

        when {
            blob.contains("qti") || blob.contains("qualcomm") || blob.contains("qcom") -> return SocVendor.QUALCOMM
            blob.contains("mediatek") || blob.contains("mtk") -> return SocVendor.MEDIATEK
            blob.contains("hisilicon") || blob.contains("kirin") || blob.contains("hi3") -> return SocVendor.KIRIN
            blob.contains("samsung") || blob.contains("exynos") -> return SocVendor.SAMSUNG
            blob.contains("google") || blob.contains("tensor") -> return SocVendor.GOOGLE
            blob.contains("unisoc") || blob.contains("spreadtrum") -> return SocVendor.UNISOC
            Regex("\\bsm\\d{4}").containsMatchIn(blob) -> return SocVendor.QUALCOMM
            Regex("\\bmt\\d{4}").containsMatchIn(blob) -> return SocVendor.MEDIATEK
        }
        // 兜底：只有 Adreno 能反过来定厂商。Mali 只说明「不是高通」，不足以定位到天玑。
        val (gpu, _) = gpuOf(profile.glRenderer)
        if (gpu == GpuFamily.ADRENO) return SocVendor.QUALCOMM
        return SocVendor.UNKNOWN
    }

    // ---------------------------------------------------------------- 档位与预算

    /**
     * 内存分档。阈值不是拍的，是按「权重 + KV + 计算缓冲」反推的：
     * 3B Q4_K_M 权重约 1.9GB，4096 上下文再加 0.6~0.9GB，系统与 App 本身还要 1.5GB 左右。
     * 所以 6GB 机器跑 3B 是「刚好卡住」，8GB 才叫舒适。
     */
    fun tierOf(profile: LocalDeviceProfile, vendor: SocVendor = vendorOf(profile)): DeviceTier {
        val ram = profile.totalRamMb
        // 内存取不到时不要乐观：按中低端处理，宁可少推一个模型。
        if (ram <= 0) return DeviceTier.LOW
        if (ram < 4500) return DeviceTier.ENTRY
        if (ram < 7500) return DeviceTier.LOW
        if (ram < 11500) {
            // 8GB 机器：认得出的两家才叫 MID，其余算 LOW 的上沿（同样 8GB，闪存与带宽差很多）。
            return if (vendor == SocVendor.QUALCOMM || vendor == SocVendor.MEDIATEK) {
                DeviceTier.MID
            } else {
                DeviceTier.LOW
            }
        }
        return DeviceTier.HIGH
    }

    /** 这台机器建议的模型量级与峰值预算（MB）。 */
    fun modelBudget(tier: DeviceTier): Pair<String, Int> = when (tier) {
        DeviceTier.ENTRY -> "0.5B~1B（Q4）" to 1800
        DeviceTier.LOW -> "1B~1.5B（Q4）" to 2800
        DeviceTier.MID -> "1.5B~3B（Q4）" to 4200
        DeviceTier.HIGH -> "3B~4B（Q4），7B 勉强" to 7000
    }

    // ---------------------------------------------------------------- 推荐路径

    /**
     * 综合判断：这台机器该先试哪条路。
     *
     * 规则刻意保守——GPU 默认只对 Adreno 打开，Mali 一律先给 CPU。
     * 理由不是 Mali 慢，是 Mali 的 Vulkan 驱动在中文 ROM 上常出现长时间推理后
     * `VK_ERROR_DEVICE_LOST`；而我们的场景恰恰是「挂着 Agent 跑几十分钟」。
     * 用户仍可手动切到 GPU，那时 we 会带上「可能中断」的提示，并落进崩溃记忆。
     */
    fun advise(profile: LocalDeviceProfile): DeviceAdvice {
        val vendor = vendorOf(profile)
        val (gpu, gpuModel) = gpuOf(profile.glRenderer)
        val tier = tierOf(profile, vendor)
        val (budgetText, budgetMb) = modelBudget(tier)
        val (npuOk, npuReason) = npuUsable(profile)
        val notes = ArrayList<String>(3)

        val preferred = when {
            npuOk -> LocalBackend.NPU
            gpu == GpuFamily.ADRENO -> LocalBackend.GPU
            gpu == GpuFamily.MALI || gpu == GpuFamily.IMMORTALIS -> {
                notes += if (tier == DeviceTier.ENTRY || tier == DeviceTier.LOW) {
                    "${gpu.label} 在入门/中低端机上跑大模型容易内存不足，先用 CPU"
                } else {
                    "${gpu.label} 的 Vulkan 驱动按机型差异大，可在设置里手动试 GPU"
                }
                LocalBackend.CPU
            }
            gpu == GpuFamily.XCLIPSE -> {
                notes += "Exynos 的 Xclipse 驱动对 ggml 算子覆盖不全，先用 CPU"
                LocalBackend.CPU
            }
            gpu == GpuFamily.POWERVR -> {
                notes += "PowerVR 驱动长期不更新，跑大模型容易中断，先用 CPU"
                LocalBackend.CPU
            }
            else -> LocalBackend.CPU
        }

        if (!npuOk && npuReason != null) notes += "NPU：$npuReason"
        if (vendor == SocVendor.UNKNOWN && profile.glRenderer.isNullOrBlank()) {
            notes += "取不到 GPU 与芯片信息，按最保守的 CPU 路线处理"
        }
        if (tier == DeviceTier.ENTRY) {
            notes += "内存偏小：建议不超过 1B 的模型，否则会在加载阶段被系统杀掉"
        }

        return DeviceAdvice(
            tier = tier,
            vendor = vendor,
            gpu = gpu,
            gpuModel = gpuModel,
            npuUsable = npuOk,
            npuReason = npuReason,
            preferredPath = preferred,
            modelBudget = budgetText,
            memoryBudgetMb = budgetMb,
            notes = notes,
        )
    }

    /**
     * 一行摘要，直接进设置页「本机诊断」。宁可长一点，也不要只说「已优化」。
     */
    fun summarize(advice: DeviceAdvice): String {
        val gpu = advice.gpuModel ?: advice.gpu.label
        return buildString {
            append(advice.vendor.label)
            append(" · ")
            append(gpu)
            append(" · ")
            append(advice.tier.label)
            append(" → 首选 ")
            append(advice.preferredPath.label)
            append("，建议 ")
            append(advice.modelBudget)
        }
    }
}
