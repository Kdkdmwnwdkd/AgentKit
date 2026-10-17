package com.example.agentkit.automation.shizuku

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.agentkit.core.AgentLogger
import java.io.File

/**
 * 内置 Shizuku 安装器（W-13 B 方案，对齐 Operit 的「安装内置版本」体验）。
 *
 * 为什么内置 APK：
 *  - 国内应用商店基本没有 Shizuku，`market://` 会落到空白页（真机录像实证「当前页面已失效」）；
 *  - 让用户自己开浏览器找 releases 页，等于把「装一个 App」变成「找安装包」；
 *  - Shizuku 是 Apache-2.0 开源、官方签名发布，内置安装与从官网下载完全等价。
 *
 * APK 来源（**不进仓库**，构建时由 CI 拉取到 `app/src/main/assets/shizuku.apk`）：
 * 见 `.github/workflows/build.yml` 的「Fetch Shizuku APK」步骤；
 * 本地未放置该文件时，[hasBundledApk] 为 false，调用方自动降级到官方发布页。
 */
object ShizukuInstaller {

    private const val TAG = "ShizukuInstaller"

    /** assets 中的文件名。 */
    const val ASSET_NAME = "shizuku.apk"

    /** 解出到 cacheDir 下的子目录（须与 `res/xml/file_paths.xml` 的 shizuku 路径一致）。 */
    private const val CACHE_DIR = "shizuku"

    /** 安装动作结果：调用方据此决定「等安装完成」还是「去开权限/去下载」。 */
    enum class Result {
        /** 已拉起系统安装器（用户点「安装」即可）。 */
        INSTALLER_LAUNCHED,

        /** 未授予「安装未知应用」：已跳转该设置页，用户回来再点一次。 */
        NEED_UNKNOWN_SOURCES,

        /** 包内没有内置 APK（本地构建常见）：调用方应退到官方发布页。 */
        NO_BUNDLED_APK,

        /** 解包/拉起失败：调用方退到官方发布页。 */
        FAILED
    }

    /** 包内是否带内置 APK（不抛异常，本地无该文件时返回 false）。 */
    fun hasBundledApk(context: Context): Boolean = runCatching {
        context.assets.list("")?.contains(ASSET_NAME) == true
    }.getOrDefault(false)

    /**
     * 用内置 APK 拉起系统安装器。
     *
     * 流程：检查「安装未知应用」授权 → 解出到 cacheDir/shizuku/ → FileProvider 出 content:// →
     * ACTION_VIEW(application/vnd.android.package-archive)。全程不静默安装（系统不允许，也不需要）。
     */
    fun install(context: Context): Result {
        if (!hasBundledApk(context)) return Result.NO_BUNDLED_APK

        // Android 8.0+：必须先允许本应用「安装未知应用」
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val allowed = runCatching { context.packageManager.canRequestPackageInstalls() }
                .getOrDefault(false)
            if (!allowed) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { AgentLogger.w(TAG, "无法打开「安装未知应用」设置：${it.message}") }
                return Result.NEED_UNKNOWN_SOURCES
            }
        }

        return try {
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            val apk = File(dir, ASSET_NAME)
            context.assets.open(ASSET_NAME).use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            AgentLogger.i(TAG, "已拉起内置 Shizuku 安装器（${apk.length()} 字节）")
            Result.INSTALLER_LAUNCHED
        } catch (t: Throwable) {
            AgentLogger.w(TAG, "内置 Shizuku 安装失败：${t.message}")
            Result.FAILED
        }
    }

    /** 官方发布页（兜底与「查看更新」共用）。 */
    fun openReleasePage(context: Context) {
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(web) }
    }
}
