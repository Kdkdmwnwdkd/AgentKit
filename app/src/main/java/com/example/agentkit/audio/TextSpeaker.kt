package com.example.agentkit.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 系统 TTS 封装（W-16）。
 *
 * 为什么用系统 TTS 而不是内置模型：零依赖、零下载、装机即有；
 * 代价是音色取决于系统语音包（用户可在系统设置里换）。对照 Operit 的 Sherpa 本地模型，
 * 我们先做「能读」这一步，音质后置。
 *
 * 初始化是异步的（`OnInitListener`），这里用 [CompletableDeferred] + 8 秒超时把它变成 suspend；
 * 引擎缺失（精简 ROM 常见）时明确失败，不假装读过。
 */
class TextSpeaker(context: Context) {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private var initDeferred: CompletableDeferred<Boolean>? = null

    private suspend fun ensureReady(): Boolean {
        if (ready && tts != null) return true
        val deferred = initDeferred ?: CompletableDeferred<Boolean>().also { initDeferred = it }
        if (tts == null) {
            withContext(Dispatchers.Main) {
                tts = TextToSpeech(appContext) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    if (!deferred.isCompleted) deferred.complete(ready)
                }
            }
        }
        return withTimeoutOrNull(8_000) { deferred.await() } ?: false
    }

    /**
     * 朗读一段文本（异步播放，调用立即返回）。
     *
     * @param language "zh" / "en" / "auto"
     * @param rate 0.5–2.0
     */
    suspend fun speak(text: String, language: String = "auto", rate: Float = 1.0f): Result<Unit> =
        withContext(Dispatchers.Main) {
            if (text.isBlank()) return@withContext Result.failure(IllegalArgumentException("文本为空"))
            if (!ensureReady()) {
                return@withContext Result.failure(
                    IllegalStateException("系统 TTS 未就绪（设备可能没有语音引擎，或引擎初始化超时）")
                )
            }
            val engine = tts ?: return@withContext Result.failure(IllegalStateException("TTS 引擎不可用"))
            val locale = when (language.lowercase()) {
                "zh" -> Locale.SIMPLIFIED_CHINESE
                "en" -> Locale.US
                else -> Locale.getDefault()
            }
            val langResult = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                return@withContext Result.failure(
                    IllegalStateException("系统缺少 ${locale.displayName} 语音包（可在系统设置 → 语音合成 中安装）")
                )
            }
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            val utteranceId = "agentkit_" + System.currentTimeMillis()
            val code = runCatching {
                engine.speak(text.take(MAX_SPEAK_CHARS), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }.getOrDefault(TextToSpeech.ERROR)
            if (code == TextToSpeech.ERROR) {
                Result.failure(IllegalStateException("TTS 播放失败（引擎返回 ERROR）"))
            } else {
                Result.success(Unit)
            }
        }

    /** 停止并释放引擎（ViewModel onCleared 调用）。 */
    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
        initDeferred = null
    }

    private companion object {
        /** 单次朗读上限：再长也没人听得完，且会把语音队列堵死。 */
        const val MAX_SPEAK_CHARS = 3_000
    }
}
