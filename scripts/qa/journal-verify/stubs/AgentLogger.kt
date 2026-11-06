package com.beichen.polaris.core

/** 验证用最小替身：只保留 journal 包用到的方法。 */
object AgentLogger {
    fun w(tag: String, msg: String) = println("W/$tag: $msg")
    fun i(tag: String, msg: String) = println("I/$tag: $msg")
    fun e(tag: String, msg: String, t: Throwable? = null) =
        println("E/$tag: $msg (${t?.javaClass?.simpleName}: ${t?.message})")
}
