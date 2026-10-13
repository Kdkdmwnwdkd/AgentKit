package com.example.agentkit.core.container

import android.content.Context
import android.system.Os
import com.example.agentkit.core.AgentLogger
import java.io.File
import java.io.FileOutputStream

/**
 * proot 运行时管理：把随包分发的 proot 独立可执行文件从 assets 提取到可写且可执行的
 * 路径，并拼出启动容器的 argv 模板。
 *
 * 【GPL 合规】proot 是 GPL-2.0 授权的独立可执行文件，我们 **不** 把它静态链接进自己的
 * .so（那会触发 GPL 对整体代码的传染），而是作为独立程序随 APK 的
 * `assets/bin/arm64-v8a/proot` 分发，运行时提取后 `execve`。随包附带的
 * `assets/3rdparty/proot/COPYING`（GPLv3 全文）与 `SOURCE_OFFER.txt`（源码要约）即
 * 履行 GPL 的分发义务。
 *
 * 运行时若 proot 缺失（未执行 `scripts/build-proot.sh` 或未放入 assets），[isAvailable]
 * 返回 false，调用方据此显示降级文案，而不是静默崩溃。
 */
object ProotRunner {

    private const val TAG = "ProotRunner"

    /** assets 内 proot 二进制（按 ABI 分目录，当前仅 arm64-v8a）。 */
    const val PROOT_ASSET = "bin/arm64-v8a/proot"

    /** 提取后的宿主路径：`<filesDir>/bin/proot`。 */
    fun binaryFile(context: Context): File = File(context.filesDir, "bin/proot")

    /** proot 是否已就绪（文件存在且可执行）。 */
    fun isAvailable(context: Context): Boolean {
        val f = binaryFile(context)
        return f.exists() && f.canExecute()
    }

    /**
     * 确保 proot 二进制就位：首次从 assets 提取并 `chmod 0700`（Android 对 filesDir
     * 内的文件默认无执行位）。已存在且可执行则直接复用。
     *
     * @return 就绪的 proot 文件；任何失败返回 null（调用方降级）。
     */
    fun ensureBinary(context: Context, force: Boolean = false): File? {
        val out = binaryFile(context)
        if (out.exists() && out.canExecute() && !force) return out
        runCatching {
            context.assets.open(PROOT_ASSET).use { ais ->
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos -> ais.copyTo(fos) }
            }
            // 0700：仅本应用可读可执行（proot 内含完整路径重写逻辑，最小权限够用）
            Os.chmod(out.absolutePath, 0b111000000)
        }.onFailure { e ->
            AgentLogger.e(TAG, "proot 二进制提取/赋权失败：${e.message}")
            return null
        }
        return if (out.exists() && out.canExecute()) out else null
    }

    /**
     * 拼出 proot 的「绑定 + 根目录 + 工作目录」参数（不含 proot 自身路径与 guest shell）。
     *
     * - `-r <rootfs>`：把解压后的 rootfs 作为容器根；
     * - `-b /dev -b /proc -b /sys`：透传 Android 的三个伪文件系统（容器里 `ps`/挂载视图靠它）；
     * - `-b /sdcard`：把外存挂进容器，便于和宿主交换文件；
     * - `-w <workDir>`：guest 初始工作目录（如 `/root`）；
     * - `-L`：fake_id0 扩展，让容器内 uid 映射成 0（非 root 安卓机上也能 `apt`/构建）。
     *
     * @param rootfs   已解压的 rootfs 宿主路径
     * @param workDir  guest 内初始工作目录（相对 guest 根，如 `/root`）
     */
    fun buildArgs(rootfs: File, workDir: String): List<String> = buildList {
        add("-r"); add(rootfs.absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        add("-b"); add("/sdcard")
        add("-w"); add(workDir)
        add("-L")
    }

    /** 容器内的默认环境变量（guest 视角，区别于宿主 TerminalSession 的 buildEnv）。 */
    fun guestEnv(packageName: String): List<String> = listOf(
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "ANDROID_PACKAGE=$packageName"
    )
}
