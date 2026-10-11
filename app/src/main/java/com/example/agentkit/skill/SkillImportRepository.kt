package com.example.agentkit.skill

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 导入技能仓库（L7「技能导入与安全降权」，净室原创）。
 *
 * 职责：把第三方技能（从 URL 下载 zip / 从本机选 zip / 内置示例）落盘到
 * [Context.getFilesDir]/skills/imported/<id>/，并强制「降权」为 imported 来源，
 * 使其落入 L5 安全内核的收紧策略（禁脚本 + 能力上限 PRIVILEGED）。
 *
 * 关键安全约束：
 *  - **强制降权**：导入即把 trust 写为 imported（用户此前已授权写回 packaged 的技能，其 trust
 *    已是 packaged，保留，不降级——避免「授权后被重新导入又掉回 imported」）。
 *  - **防 Zip Slip**：解压前校验每个 entry 名称不含 ".."，且解压目标文件的 canonicalPath 必须
 *    落在目标目录 canonicalPath 之下，否则抛异常。
 *  - **失败回滚**：installZip 任何异常都用 runCatching 包裹，失败时删除可能创建的半残目录。
 */
class ImportedSkillRepository(private val context: Context) {

    /** 导入技能根目录：filesDir/skills/imported。 */
    private val baseDir: File
        get() = File(context.filesDir, "skills/imported")

    /** 解析用（容忍未知字段，兼容各版本 skill.json）。 */
    private val json = Json { ignoreUnknownKeys = true }

    /** 写回用（prettyPrint，便于人工审阅）。 */
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * 扫描 filesDir/skills/imported/<id>/skill.json，反序列化为技能清单列表。
     * @param app 当前 Context（与构造入参同源，保持与 SkillLoader 一致的调用形态）。
     */
    fun loadImported(app: Context): List<SkillManifest> {
        val out = mutableListOf<SkillManifest>()
        val root = File(app.filesDir, "skills/imported")
        val ids = runCatching { root.list()?.toList() }.getOrNull() ?: return out
        for (id in ids) {
            val file = File(root, "$id/skill.json")
            if (!file.isFile) continue
            runCatching {
                out += json.decodeFromString<SkillManifest>(file.readText())
            }
        }
        return out
    }

    /**
     * 解压 zip 到 filesDir/skills/imported/<id>/，并强制降权。
     * @return 成功返回技能 id（Result 包装），失败返回异常（并回滚半残目录）。
     */
    fun installZip(zipBytes: ByteArray): Result<String> {
        var destDir: File? = null
        val result = runCatching {
            // 第一遍：仅读取 zip 内 skill.json 拿 id（不落盘）
            val skillJsonText = readZipEntry(zipBytes) { name ->
                name == "skill.json" || name.endsWith("/skill.json")
            } ?: throw IllegalArgumentException("zip 内未找到 skill.json")
            val id = json.decodeFromString<SkillManifest>(skillJsonText).id
            if (id.isBlank()) throw IllegalArgumentException("skill.json 的 id 为空")

            destDir = File(baseDir, id)
            // 幂等：已有则先清空，支持重新导入
            if (destDir!!.exists()) destDir!!.deleteRecursively()

            // 第二遍：整包解压（带 Zip Slip 防护）
            extractZipAll(zipBytes, destDir!!)

            // 反序列化校验 name 非空
            val manifest = json.decodeFromString<SkillManifest>(File(destDir!!, "skill.json").readText())
            if (manifest.name.isBlank()) throw IllegalArgumentException("skill.json 的 name 为空")

            // 强制降权：仅当用户此前未显式授权（trust 非 packaged）时回写为 imported
            if (manifest.trust.lowercase() != "packaged") {
                writeTrust(destDir!!, "imported")
            }
            id
        }
        // 失败回滚：删除可能创建的半残目录
        if (result.isFailure) {
            destDir?.let { if (it.exists()) it.deleteRecursively() }
        }
        return result
    }

    /** 卸载指定技能：删除 filesDir/skills/imported/<id> 整目录。 */
    fun uninstall(id: String): Boolean {
        val dir = File(baseDir, id)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    /**
     * 改写 skill.json 里 trust 字段：allow=true → "packaged"（授权脚本），allow=false → "imported"。
     * @return 是否成功写回。
     */
    fun setScriptAuthorization(id: String, allow: Boolean): Boolean {
        val file = File(File(baseDir, id), "skill.json")
        if (!file.isFile) return false
        return runCatching {
            val obj = json.parseToJsonElement(file.readText()) as JsonObject
            val newObj = buildJsonObject {
                for ((k, v) in obj) {
                    if (k == "trust") put(k, JsonPrimitive(if (allow) "packaged" else "imported"))
                    else put(k, v)
                }
            }
            file.writeText(prettyJson.encodeToString(JsonObject.serializer(), newObj))
        }.isSuccess
    }

    /** 把 skill.json 的 trust 字段重写为 [trust]（prettyPrint 写回）。 */
    private fun writeTrust(dir: File, trust: String) {
        val file = File(dir, "skill.json")
        val obj = json.parseToJsonElement(file.readText()) as JsonObject
        val newObj = buildJsonObject {
            for ((k, v) in obj) {
                if (k == "trust") put(k, JsonPrimitive(trust)) else put(k, v)
            }
        }
        file.writeText(prettyJson.encodeToString(JsonObject.serializer(), newObj))
    }

    /** 在 zip 字节流里找到首个匹配 predicate 的条目并读取其文本（不落盘）。 */
    private fun readZipEntry(zipBytes: ByteArray, predicate: (String) -> Boolean): String? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && predicate(entry.name)) {
                    return zis.bufferedReader().readText()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    /** 把 zip 整体解压到 destDir，带 Zip Slip 防护。 */
    private fun extractZipAll(zipBytes: ByteArray, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    // 1) 名称含 ".." 直接拒绝（路径穿越特征）
                    if (entry.name.contains("..")) {
                        throw SecurityException("检测到 Zip Slip 路径穿越：${entry.name}")
                    }
                    val outFile = File(destDir, entry.name)
                    // 2) canonicalPath 必须落在目标目录之下（防 ../ 越界写）
                    if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                        throw SecurityException("检测到 Zip Slip 越界写入：${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { os -> zis.copyTo(os) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        /**
         * 生成一个示例技能的 zip 字节（纯提示词形态、无 entry，便于无网络演示）。
         * 含 skill.json（id=com.example.demo.greet、name=示例·打招呼、trust=imported）与 SKILL.md。
         */
        fun demoZipBytes(): ByteArray {
            val skillJson = buildJsonObject {
                put("id", JsonPrimitive("com.example.demo.greet"))
                put("name", JsonPrimitive("示例·打招呼"))
                put("version", JsonPrimitive("1.0.0"))
                put("description", JsonPrimitive("示例技能：用提示词形态向用户问好，用于演示「导入技能」功能。"))
                put("prompt", JsonPrimitive("当用户想打招呼或问好时，用中文友好地问候对方，并询问需要什么帮助。"))
                put("tools", buildJsonArray { add(JsonPrimitive("read_file")) })
                put("trust", JsonPrimitive("imported"))
            }
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("skill.json"))
                zos.write(prettyJson.encodeToString(JsonObject.serializer(), skillJson).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("SKILL.md"))
                zos.write("这是一个示例技能，用于演示 AgentKit 的「导入技能」功能。".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            return baos.toByteArray()
        }
    }
}
