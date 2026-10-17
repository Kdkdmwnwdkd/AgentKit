package com.example.agentkit.core.llm

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 备用 Key 池持久化（W-21）：按配置档案（profileId）分组存槽位。
 *
 * 只存 id/label/key 三件套；**冷却状态不落盘**（内存态，见 [KeyPoolRuntime]）——
 * 重启即重置，避免一次限流把 Key 长期判死。
 *
 * Key 以明文存 SharedPreferences（与既有 apiKey 处理一致，加密不在本批范围）；
 * 任何对外展示/日志路径都必须先过脱敏（前后各留 4 位）。
 */
class KeyPoolStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** 读取某档案的备用 Key 列表。 */
    fun list(profileId: String): List<KeyPoolPolicy.KeySlot> = runCatching {
        val raw = prefs.getString(key(profileId), null) ?: return emptyList()
        json.decodeFromString<List<KeyPoolPolicy.KeySlot>>(raw)
    }.getOrDefault(emptyList())

    /** 覆盖写入某档案的备用 Key 列表。 */
    fun save(profileId: String, slots: List<KeyPoolPolicy.KeySlot>) {
        prefs.edit().putString(key(profileId), json.encodeToString(slots)).apply()
    }

    /** 追加一把 Key；重复 label 直接覆盖同 id。 */
    fun add(profileId: String, label: String, rawKey: String): KeyPoolPolicy.KeySlot {
        val slot = KeyPoolPolicy.KeySlot(
            id = "k${System.currentTimeMillis()}",
            label = label.ifBlank { "备用 Key" },
            key = rawKey.trim()
        )
        // 同 Key 内容已存在则视为重复，不重复添加
        val current = list(profileId)
        if (current.any { it.key == slot.key }) return current.first { it.key == slot.key }
        save(profileId, current + slot)
        return slot
    }

    fun remove(profileId: String, slotId: String) {
        save(profileId, list(profileId).filterNot { it.id == slotId })
    }

    fun clear(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
    }

    /** 展示用脱敏 Key：只留前 4 后 4，中间统一打码。 */
    fun masked(rawKey: String): String {
        val k = rawKey.trim()
        if (k.length <= 8) return "****"
        return "${k.take(4)}****${k.takeLast(4)}"
    }

    private fun key(profileId: String) = "pool_$profileId"

    private companion object {
        const val PREFS = "agentkit_keypool"
    }
}
