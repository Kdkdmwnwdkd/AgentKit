package com.example.agentkit.core

import android.content.Context
import android.content.SharedPreferences
import com.example.agentkit.skill.ModelConfig

/**
 * 模型配置持久化（SharedPreferences）。
 *
 * 解决的问题：原实现 ModelConfig 只存在内存，App 进程被系统回收后全部丢失，
 * 退回「openai.com / 空 Key / gpt-4o-mini」默认值，用户「明明填过」的认知与
 * App 实际状态永久不一致，进而出现「在别处能用、在本 App 一直 401」的迷惑现象。
 * 这里把三元组落盘，进程重启也能还原。
 *
 * Key 存明文（App 私有目录 SharedPreferences，与 AutomationPrefs、Skill 授权做法一致）；
 * 不引入新的加密依赖——移动端本地私有存储已足够隔离，加密无法防御 root 设备，
 * 反而徒增复杂度。读取与写入都过一遍 [ConfigSanitizer]，防历史脏数据复活。
 */
class ConfigStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读出配置。读出来的原始值再过一遍 [ConfigSanitizer]，
     * 防止历史脏数据（早期版本可能把带不可见字符的 Key 写进了盘）继续生效。
     *
     * @return 已净化的配置；从未保存过时返回 [ModelConfig] 默认值。
     */
    fun load(): ModelConfig {
        val raw = ModelConfig(
            baseUrl = sp.getString(KEY_BASE_URL, ModelConfig().baseUrl) ?: ModelConfig().baseUrl,
            apiKey = sp.getString(KEY_API_KEY, ModelConfig().apiKey) ?: ModelConfig().apiKey,
            model = sp.getString(KEY_MODEL, ModelConfig().model) ?: ModelConfig().model
        )
        return ConfigSanitizer.sanitized(raw)
    }

    /**
     * 保存配置。保存前先过一遍 [ConfigSanitizer]，确保落盘的就是净化后的 Key，
     * 下次读出来也是干净的（双保险：load 还会再洗一遍）。
     *
     * @param cfg 用户提交的原始配置（可能含不可见字符）。
     */
    fun save(cfg: ModelConfig) {
        val clean = ConfigSanitizer.sanitized(cfg)
        sp.edit().apply {
            putString(KEY_BASE_URL, clean.baseUrl)
            putString(KEY_API_KEY, clean.apiKey)
            putString(KEY_MODEL, clean.model)
        }.apply()
    }

    companion object {
        /** 与现有 SharedPreferences 命名风格保持一致（agentkit_ 前缀）。 */
        private const val PREFS_NAME = "agentkit_model_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
    }
}
