import com.example.agentkit.tools.Calculator

/**
 * W-18 小工具 harness：计算器求值（纯函数，无 Android 依赖）。
 *
 * 重点不是「能算」，而是**不能算的必须明确报错**（除零、括号不配对、非法字符），
 * 否则模型拿到 NaN/Infinity 会当作结果继续推理。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try { body(); pass++; println("  PASS  $name") }
    catch (t: Throwable) { fail++; println("  FAIL  $name → ${t.message}") }
}

private fun near(msg: String, expected: Double, actual: Double) {
    if (kotlin.math.abs(expected - actual) > 1e-6) throw AssertionError("$msg：期望 $expected，实际 $actual")
}

private fun expectError(msg: String, expr: String) {
    val threw = runCatching { Calculator.evaluate(expr) }.isFailure
    if (!threw) throw AssertionError("$msg：应当报错，但算出了 ${runCatching { Calculator.evaluate(expr) }.getOrNull()}")
}

fun main() {
    println("=== W-18 计算器 ===")

    check("四则与括号：(120+80)*0.85 = 170") {
        near("值", 170.0, Calculator.evaluate("(120+80)*0.85"))
    }
    check("运算优先级：2+3*4 = 14") {
        near("值", 14.0, Calculator.evaluate("2+3*4"))
    }
    check("幂右结合：2^3^2 = 512") {
        near("值", 512.0, Calculator.evaluate("2^3^2"))
    }
    check("开方：sqrt(2)*10") {
        near("值", 14.1421356237, Calculator.evaluate("sqrt(2)*10"))
    }
    check("取余：10%3 = 1") {
        near("值", 1.0, Calculator.evaluate("10%3"))
    }
    check("一元负号：-5+8 = 3") {
        near("值", 3.0, Calculator.evaluate("-5+8"))
    }
    check("空格式输入：' 1 +  2 ' = 3") {
        near("值", 3.0, Calculator.evaluate(" 1 +  2 "))
    }
    check("除零必须报错") { expectError("除零", "1/0") }
    check("括号不配对必须报错") { expectError("缺右括号", "(1+2") }
    check("非法字符必须报错") { expectError("非法字符", "1+abc") }
    check("负数开方必须报错") { expectError("sqrt(-1)", "sqrt(0-1)") }

    check("格式化：整数不带小数、小数去尾零") {
        if (Calculator.format(170.0) != "170") throw AssertionError("170 → ${Calculator.format(170.0)}")
        if (Calculator.format(1.5) != "1.5") throw AssertionError("1.5 → ${Calculator.format(1.5)}")
        if (Calculator.format(1.0 / 3) != "0.3333333333") throw AssertionError("1/3 → ${Calculator.format(1.0 / 3)}")
    }

    println("\n=== 汇总: $pass 通过 / $fail 失败 ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
