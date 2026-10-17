package com.example.agentkit.tools

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * calculator（W-18）：四则/括号/幂/百分号/开方。
 *
 * 为什么不用 eval / JS 引擎：计算器是高频小工具，走 Rhino 要起引擎、还有安全面；
 * 一个 60 行的递归下降解析器就够，且**没有任何代码执行能力**。
 */
class CalculatorTool : AgentTool {

    override val name = "calculator"

    override val description =
        "计算数学表达式（支持 + - * / % ^、括号、sqrt）。用于算账、单位换算、百分比等需要精确数字的场合；" +
            "不要用它处理字符串或执行代码。"

    override val systemPrompt = "需要精确计算时用 calculator，不要心算后直接给数字（容易错）。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("expression", "string", "数学表达式，如 (120+80)*0.85 或 sqrt(2)*10")
    )

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val expr = args["expression"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (expr.isBlank()) {
            return ToolResult.fail("missing_param", "缺少 expression", "传入算式，例如 (120+80)*0.85。")
        }
        return runCatching { Calculator.evaluate(expr) }.fold(
            onSuccess = { v ->
                ToolResult.ok(
                    code = "calc_ok",
                    message = "$expr = ${Calculator.format(v)}",
                    data = "结果：${Calculator.format(v)}"
                )
            },
            onFailure = { e ->
                ToolResult.fail(
                    code = "calc_failed",
                    message = "算式无法计算：${e.message}",
                    hint = "检查括号是否配对、有没有非法字符；只支持数字与 + - * / % ^ sqrt。"
                )
            }
        )
    }
}

/**
 * 安全表达式求值（纯函数，W-18）。
 *
 * 文法：
 *   expr   := term (('+'|'-') term)*
 *   term   := unary (('*'|'/'|'%') unary)*
 *   unary  := ('-'|'+') unary | power
 *   power  := primary ('^' unary)?            // 右结合
 *   primary:= number | 'sqrt' '(' expr ')' | '(' expr ')'
 */
object Calculator {

    fun evaluate(expression: String): Double {
        val parser = Parser(expression)
        val value = parser.parseExpr()
        parser.skipSpaces()
        if (!parser.atEnd()) throw IllegalArgumentException("无法解析：位置 ${parser.pos} 附近「${parser.remaining()}」")
        if (value.isNaN() || value.isInfinite()) throw IllegalArgumentException("结果不是有限数")
        return value
    }

    /** 整数结果不带小数点（3 而不是 3.0）；其余保留最多 10 位有效小数。 */
    fun format(v: Double): String {
        if (abs(v - v.toLong()) < 1e-9 && abs(v) < 1e15) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.10f", v).trimEnd('0').trimEnd('.')
    }

    private class Parser(private val src: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= src.length
        fun remaining(): String = src.drop(pos).take(20)
        fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun parseExpr(): Double {
            var left = parseTerm()
            while (true) {
                skipSpaces()
                when {
                    eat('+') -> left += parseTerm()
                    eat('-') -> left -= parseTerm()
                    else -> return left
                }
            }
        }

        private fun parseTerm(): Double {
            var left = parseUnary()
            while (true) {
                skipSpaces()
                when {
                    eat('*') -> left *= parseUnary()
                    eat('/') -> {
                        val d = parseUnary()
                        if (abs(d) < 1e-12) throw IllegalArgumentException("除数为 0")
                        left /= d
                    }
                    eat('%') -> {
                        val d = parseUnary()
                        if (abs(d) < 1e-12) throw IllegalArgumentException("取余的除数为 0")
                        left %= d
                    }
                    else -> return left
                }
            }
        }

        private fun parseUnary(): Double {
            skipSpaces()
            if (eat('-')) return -parseUnary()
            if (eat('+')) return parseUnary()
            return parsePower()
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            skipSpaces()
            if (eat('^')) return base.pow(parseUnary())
            return base
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            if (eat('(')) {
                val v = parseExpr()
                skipSpaces()
                if (!eat(')')) throw IllegalArgumentException("缺少右括号")
                return v
            }
            if (src.startsWith("sqrt", pos, ignoreCase = true)) {
                pos += 4
                skipSpaces()
                if (!eat('(')) throw IllegalArgumentException("sqrt 后需要括号")
                val v = parseExpr()
                skipSpaces()
                if (!eat(')')) throw IllegalArgumentException("sqrt 缺少右括号")
                if (v < 0) throw IllegalArgumentException("负数不能开平方")
                return sqrt(v)
            }
            return parseNumber()
        }

        private fun parseNumber(): Double {
            skipSpaces()
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("这里需要一个数字")
            return src.substring(start, pos).toDoubleOrNull()
                ?: throw IllegalArgumentException("数字格式错误：${src.substring(start, pos)}")
        }

        private fun eat(c: Char): Boolean {
            if (pos < src.length && src[pos] == c) {
                pos++
                return true
            }
            return false
        }
    }
}
