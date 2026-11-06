package com.beichen.polaris.core

/**
 * `AgentLogger` 的 JVM 桩（只给 R-12 harness 用）。
 *
 * 真身会拖出 `ConfigSanitizer → skill/ModelConfig` 一长串；而终端池对日志的唯一要求是
 * 「能调用、不抛异常」。桩成 no-op 是**安全的**：本 harness 断言的是身份语义，
 * 不是日志内容（日志内容由 T07/T08 那套在构建机上验）。
 */
object AgentLogger {
    fun i(tag: String, message: String, e: Throwable? = null) {}
    fun w(tag: String, message: String, e: Throwable? = null) {}
    fun e(tag: String, message: String, e: Throwable? = null) {}
}
