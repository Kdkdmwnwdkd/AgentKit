package com.example.agentkit.core.container

import android.content.Context
import android.system.Os
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
                        name = id.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                        },
                        assetName = asset
                    )
                }
        }.getOrDefault(emptyList())
    }

    fun rootfsDir(context: Context, id: String): File =
        File(context.filesDir, "containers/$id/rootfs")

    /** 已解压判据：rootfs 下同时存在 `bin/` 与 `etc/`。 */
    fun isExtracted(context: Context, id: String): Boolean {
        val d = rootfsDir(context, id)
        return File(d, "bin").isDirectory && File(d, "etc").isDirectory
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
        runCatching {
            openRootfs(context, "containers/${distro.assetName}").use { stream ->
                extractTar(stream, d)
            }
        }.onFailure { e ->
            AgentLogger.e(TAG, "解压 ${distro.assetName} 失败：${e.message}")
            runCatching { d.deleteRecursively() } // 残损目录清理，避免下次误判已解压
            return null
        }
        return if (isExtracted(context, distro.id)) d else null
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
                    val f = File(dest, sanitize(name))
                    f.parentFile?.mkdirs()
                    runCatching {
                        if (f.exists()) f.delete()
                        Os.symlink(linkname, f.absolutePath)
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
