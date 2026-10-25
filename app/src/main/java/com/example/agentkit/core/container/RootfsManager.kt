package com.example.agentkit.core.container

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.example.agentkit.core.AgentLogger
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * rootfs 管理：从 assets 解压发行版 rootfs（tar.gz）到 `<filesDir>/containers/<id>/rootfs`，
 * 并提供幂等校验、列举、删除。
 *
 * tar.gz 解压在 Kotlin 侧用最小 tar 解析器完成（Android 没有 `tar` 命令）：支持
 * ustar + GNU 长文件名（`L`），symlink 用 [Os.symlink]，权限用 [Os.chmod] 还原。
 */
object RootfsManager {

    private const val TAG = "RootfsManager"

    /**
     * 展示名映射。
     *
     * `alpine-x86_64` 只存在于本地调试包（`-Px86Rootfs=true`），用来在 x86_64 模拟器上
     * 真正跑容器；正式包只有 `alpine`（arm64）。名字里标出架构，避免测试时选错。
     */
    private val DISPLAY_NAMES = mapOf(
        "alpine" to "Alpine Linux",
        "alpine-x86_64" to "Alpine Linux（x86_64 调试）"
    )

    /** 一种可启动的发行版（由 `assets/containers/<name>.tar.gz` 描述）。 */
    data class Distro(
        val id: String,       // 目录名，如 "alpine"
        val name: String,     // 展示名，如 "Alpine Linux"
        val assetName: String // assets 内文件名，如 "alpine.tar.gz"
    )

    /**
     * assets/containers 下所有 rootfs 对应的发行版列表。
     *
     * 注意：AGP/AAPT2 打包时会自动把 `.gz` 资源解压并剥离 `.gz` 后缀，因此磁盘/APK 里
     * 看到的通常是 `alpine.tar` 而非 `alpine.tar.gz`。这里按 `.tar` 匹配即可兼容两种写法。
     */
    fun listAvailable(context: Context): List<Distro> {
        return runCatching {
            (context.assets.list("containers") ?: emptyArray())
                .filter { it.endsWith(".tar") }
                .map { asset ->
                    val id = asset.removeSuffix(".tar")
                    Distro(
                        id = id,
                        name = DISPLAY_NAMES[id] ?: id.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                        },
                        assetName = asset
                    )
                }
        }.getOrDefault(emptyList())
    }

    fun rootfsDir(context: Context, id: String): File =
        File(context.filesDir, "containers/$id/rootfs")

    /**
     * 已解压判据：**关键文件齐备**，而不是只看 `bin/` `etc/` 两个目录。
     *
     * 原来只判目录：解压中断（进程被杀 / 存储不足）会留下 `bin/` 与 `etc/` 都在、
     * 但里面是空的残缺树，于是永远显示「已就绪」，`ensureExtracted` 也永远直接返回，
     * 再也不会重新解压——真机上表现为「显示已就绪，但 proot 报 /bin/sh 不存在」。
     *
     * `bin/sh` 是**符号链接**（`-> /bin/busybox`），必须用 `lstat` 判断，不能用
     * `File.exists()`——后者会跟随链接，在 Android 上解析到不存在的宿主 `/bin/busybox`，
     * 永远返回 false。
     */
    fun isExtracted(context: Context, id: String): Boolean {
        val d = rootfsDir(context, id)
        // 布局版本：早期解压保留的是「绝对符号链接」，在 Android 上 execve 会解析到
        // 宿主的 /bin/busybox（不存在）。没有这个标记说明是旧布局，需要重解压一次。
        return contentReady(d) && File(d, LAYOUT_MARKER).isFile
    }

    /**
     * 只看**内容**是否齐备，不看布局标记。
     *
     * 拆开的原因是顺序：`ensureExtracted` 必须先用它确认解压成功，**再**写布局标记。
     * 曾经把两者合在一起，于是「校验要标记、标记要校验先通过」，解压永远算失败，
     * 变成无限重解压（真机日志里同一秒刷了 30 多次）。
     */
    private fun contentReady(d: File): Boolean {
        if (!File(d, "bin").isDirectory || !File(d, "etc").isDirectory) return false
        if (lstatType(File(d, "bin/sh")) == LinkType.MISSING) return false
        val busybox = File(d, "bin/busybox")
        return busybox.isFile && busybox.length() > 0L
    }

    /** 解压布局版本标记：有它才认为 rootfs 可用（见 [isExtracted]）。 */
    private const val LAYOUT_MARKER = ".agentkit-layout-v2"

    private enum class LinkType { MISSING, SYMLINK, REGULAR }

    private fun lstatType(f: File): LinkType = runCatching {
        val st = Os.lstat(f.absolutePath)
        when (st.st_mode and OsConstants.S_IFMT) {
            OsConstants.S_IFLNK -> LinkType.SYMLINK
            else -> LinkType.REGULAR
        }
    }.getOrDefault(LinkType.MISSING)

    /**
     * rootfs 体检报告（供安装失败时展示）。
     *
     * 只回答一个问题：**proot 要执行的那些文件，在磁盘上到底是不是齐的**。
     * 排查「execve(/bin/sh) 找不到」时，这一屏比任何猜测都直接。
     */
    fun inspect(context: Context, id: String): String {
        val d = rootfsDir(context, id)
        if (!d.isDirectory) return "rootfs 目录不存在：${d.absolutePath}"
        val sb = StringBuilder()
        for (p in listOf("bin/sh", "bin/busybox", "lib", "etc/alpine-release")) {
            val f = File(d, p)
            when (lstatType(f)) {
                LinkType.MISSING -> sb.append("$p：缺失\n")
                LinkType.SYMLINK -> {
                    val t = runCatching { Os.readlink(f.absolutePath) }.getOrNull().orEmpty()
                    val target = if (t.startsWith("/")) File(d, t.trimStart('/'))
                    else File(f.parentFile ?: d, t)
                    val ok = lstatType(target) != LinkType.MISSING
                    sb.append("$p：链接 -> $t（目标${if (ok) "在 rootfs 内存在" else "不存在"}）\n")
                }
                LinkType.REGULAR -> sb.append("$p：${if (f.isDirectory) "目录" else "${f.length()} 字节"}\n")
            }
        }
        val binCount = File(d, "bin").listFiles()?.size ?: 0
        sb.append("bin/ 条目数=$binCount")
        return sb.toString()
    }

    /**
     * 确保 rootfs 已解压（幂等）。已解压则直接返回；否则从 assets 解压。
     *
     * 资源流兼容两种格式：AGP 会把 `.gz` 资源在打包时解压成裸 tar，故 [openRootfs]
     * 先探测 gzip 魔数，是则走 [GZIPInputStream]，否则按裸 tar 解析。
     *
     * @return rootfs 目录；失败返回 null。
     */
    fun ensureExtracted(context: Context, distro: Distro): File? {
        val d = rootfsDir(context, distro.id)
        if (isExtracted(context, distro.id)) return d
        // 目录在但校验不过 = 上次解压中断 / 旧版残留。必须先清空，
        // 否则残留文件会让本次解压「看起来成功」但内容仍然不全。
        if (d.exists()) {
            AgentLogger.w(TAG, "rootfs(${distro.id}) 校验不通过，清空后重新解压：${inspect(context, distro.id)}")
            runCatching { d.deleteRecursively() }
        }
        runCatching {
            openRootfs(context, "containers/${distro.assetName}").use { stream ->
                extractTar(stream, d)
            }
        }.onFailure { e ->
            AgentLogger.e(TAG, "解压 ${distro.assetName} 失败：${e.message}")
            runCatching { d.deleteRecursively() } // 残损目录清理，避免下次误判已解压
            return null
        }
        // 顺序不能反：先确认内容解出来了，再写布局标记（标记本身也参与 isExtracted）。
        if (!contentReady(d)) {
            AgentLogger.e(TAG, "解压后内容仍不完整：${inspect(context, distro.id)}")
            runCatching { d.deleteRecursively() }
            return null
        }
        runCatching { File(d, LAYOUT_MARKER).createNewFile() }
        AgentLogger.i(TAG, "rootfs(${distro.id}) 解压完成：${inspect(context, distro.id)}")
        return d
    }

    /**
     * 打开 rootfs 资源并透明处理 gzip：若流头两字节为 gzip 魔数（0x1f 0x8b）则包
     * [GZIPInputStream]，否则原样返回（AGP 已解压的裸 tar）。用 [java.io.PushbackInputStream]
     * 回退探测字节，不消费数据。
     */
    private fun openRootfs(context: Context, assetPath: String): InputStream {
        val pb = java.io.PushbackInputStream(context.assets.open(assetPath), 2)
        val b1 = pb.read()
        val b2 = pb.read()
        if (b1 == 0x1f && b2 == 0x8b) {
            pb.unread(b2); pb.unread(b1)
            return GZIPInputStream(pb)
        }
        if (b2 != -1) pb.unread(b2)
        if (b1 != -1) pb.unread(b1)
        return pb
    }

    /** 删除某发行版的 rootfs（释放空间）。 */
    fun delete(context: Context, id: String): Boolean =
        runCatching { rootfsDir(context, id).deleteRecursively() }.getOrDefault(false)

    // ——— 最小 tar 解析（ustar + GNU 长文件名）———

    private fun extractTar(input: InputStream, dest: File) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (readFully(input, header) < 512) break
            if (header[0].toInt() == 0) break // 零块 = 归档结束
            val size = parseOctal(header, 124, 12)
            val typeflag = header[156].toInt().toChar()
            val name = longName ?: parseName(header, 0, 100)
            longName = null
            val linkname = parseName(header, 157, 100)

            when (typeflag) {
                'L' -> { // GNU 长文件名：其数据块即真实路径
                    longName = readString(input, size)
                    skipPad(input, size)
                    continue
                }
                'x', 'g' -> { // pax 扩展头：跳过
                    skipPad(input, size)
                    continue
                }
                '5' -> { // 目录
                    File(dest, sanitize(name)).mkdirs()
                    skipPad(input, size)
                }
                '2' -> { // 符号链接
                    val rel = sanitize(name)
                    val f = File(dest, rel)
                    f.parentFile?.mkdirs()
                    val target = relativizeLink(dest, rel, linkname)
                    runCatching {
                        if (lstatType(f) != LinkType.MISSING) f.delete()
                        Os.symlink(target, f.absolutePath)
                    }
                    skipPad(input, size)
                }
                '1', '3', '4', '6' -> skipPad(input, size) // 硬链/设备/FIFO：跳过数据
                else -> { // '0'/'7'/其它：常规文件
                    val f = File(dest, sanitize(name))
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { copyN(input, it, size) }
                    val mode = parseOctal(header, 100, 8)
                    if (mode > 0) runCatching { Os.chmod(f.absolutePath, mode.toInt()) }
                    skipPad(input, size)
                }
            }
        }
    }

    /**
     * 把**绝对**符号链接改成相对，限制在 rootfs 内。
     *
     * Alpine 的 `/bin/sh -> /bin/busybox` 是绝对链接。交给内核 execve 时，绝对目标
     * 会从**宿主根**解析——Android 上没有 `/bin/busybox`，直接 ENOENT。
     * proot 本应在 guest 命名空间内翻译它，但真机上这一步没生效
     *（`proot error: execve("/bin/sh"): No such file or directory`）。
     *
     * 转成 `sh -> busybox` 后，内核在 `$ROOTFS/bin/` 内直接解析成功，不依赖 proot
     * 的符号链接翻译。服务器对照实验里两种形态都能跑，但相对形态在 Android 上更稳。
     */
    private fun relativizeLink(root: File, linkRel: String, target: String): String {
        if (!target.startsWith("/")) return target
        val linkParent = File(root, linkRel).parentFile ?: root
        val absolute = File(root, target.trimStart('/'))
        val rel = runCatching { absolute.relativeTo(linkParent).path }.getOrNull()
        return if (rel.isNullOrEmpty()) "." else rel
    }

    private fun sanitize(name: String): String = name.removePrefix("./").trimStart('/')

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun parseName(buf: ByteArray, off: Int, len: Int): String {
        val max = (off + len).coerceAtMost(buf.size)
        var end = off
        while (end < max && buf[end].toInt() != 0) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(buf: ByteArray, off: Int, len: Int): Long {
        var value = 0L
        val max = (off + len).coerceAtMost(buf.size)
        var i = off
        while (i < max) {
            val c = buf[i].toInt() and 0xFF
            if (c == ' '.code || c == 0) { i++; continue }
            if (c < '0'.code || c > '7'.code) break
            value = value * 8 + (c - '0'.code)
            i++
        }
        return value
    }

    private fun readString(input: InputStream, size: Long): String {
        val n = size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val buf = ByteArray(n)
        readFully(input, buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun copyN(input: InputStream, out: FileOutputStream, size: Long) {
        val buf = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val toRead = buf.size.coerceAtMost(remaining.toInt())
            val n = input.read(buf, 0, toRead)
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipPad(input: InputStream, size: Long) {
        val blocks = ((size + 511) / 512).toInt()
        var remaining = (blocks * 512 - size).coerceAtLeast(0)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }
}
