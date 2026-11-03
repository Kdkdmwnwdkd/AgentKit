/**
 * R11 验证：工具参数 schema 的**单一序列化点**（ToolSchemaJson）。
 *
 * 被测的是一处真实缺陷：OpenAI / Claude / Gemini 三家客户端原先各有一份
 * `toApiTool`，三份逐字相同且**都只写 type/description/enum**，
 * `JsonSchema.Property` 里已有的 `items` / `properties` / `required` 全被丢掉。
 *
 * 后果不是「缺字段」，是声明残缺：`spawn_agents` 的 `tasks` 是对象数组，
 * 发出去只剩 `{"type":"array"}`——Gemini 这类严格实现会拒掉整个工具声明，
 * 宽松实现则让模型看不见元素结构。该工具源码注释里明确写着这条要求，
 * 说明数据层按规范写了、序列化层没接上，属于典型的「上下半场脱节」。
 *
 * 断言分三组：
 *  1. 回归：扁平工具（无 items / 无嵌套）的产出**逐字节不变**；
 *  2. 新增：items / 嵌套 properties / required 递归写出；
 *  3. 边界：空 enum、非 array 带 items、空嵌套对象。
 *
 * 全部纯函数，不加载 Android 类、不联网。
 */
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolSchemaJson

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$name:\n  期望 $expected\n  实际 $actual")
}

/** 顶层对象 schema 的速记构造：全部必填（与 JsonSchema.objectSchema 一致）。 */
private fun schema(vararg props: Pair<String, JsonSchema.Property>): JsonSchema =
    JsonSchema(properties = mapOf(*props), required = props.map { it.first })

fun main() {
    println("=== AgentKit R11 验证（工具 schema 单点序列化）===")

    println("\n[R11] 回归：扁平工具产出与改动前逐字节一致")

    check("R11-1 单字段工具：键序与内容完全等价于旧实现") {
        val out = ToolSchemaJson.objectSchema(
            schema("path" to JsonSchema.Property("string", "文件路径"))
        ).toString()
        assertEquals(
            "扁平 schema",
            """{"type":"object","properties":{"path":{"type":"string","description":"文件路径"}},"required":["path"]}""",
            out
        )
    }

    check("R11-2 无 description 时不写该键（旧行为）") {
        val out = ToolSchemaJson.objectSchema(
            schema("n" to JsonSchema.Property("integer"))
        ).toString()
        assertEquals(
            "空描述省略",
            """{"type":"object","properties":{"n":{"type":"integer"}},"required":["n"]}""",
            out
        )
    }

    check("R11-3 enum 原样写出，位置在 description 之后") {
        val out = ToolSchemaJson.objectSchema(
            schema(
                "mode" to JsonSchema.Property(
                    type = "string",
                    description = "模式",
                    enum = listOf("a", "b")
                )
            )
        ).toString()
        assertEquals(
            "enum schema",
            """{"type":"object","properties":{"mode":{"type":"string","description":"模式","enum":["a","b"]}},"required":["mode"]}""",
            out
        )
    }

    check("R11-4 空 properties / 空 required 仍写出（与旧行为一致）") {
        assertEquals(
            "空 schema",
            """{"type":"object","properties":{},"required":[]}""",
            ToolSchemaJson.objectSchema(JsonSchema()).toString()
        )
    }

    println("\n[R11] 新增：array 的 items 必须写出")

    check("R11-5 object 数组写出 items 及其嵌套 properties/required") {
        val tasks = JsonSchema.Property(
            type = "array",
            description = "子任务",
            items = JsonSchema.Property(
                type = "object",
                properties = mapOf(
                    "title" to JsonSchema.Property("string", "标题"),
                    "instruction" to JsonSchema.Property("string", "指令")
                ),
                required = listOf("instruction")
            )
        )
        val out = ToolSchemaJson.objectSchema(schema("tasks" to tasks)).toString()
        assertEquals(
            "items 递归",
            """{"type":"object","properties":{"tasks":{"type":"array","description":"子任务",""" +
                """"items":{"type":"object","properties":{"title":{"type":"string","description":"标题"},""" +
                """"instruction":{"type":"string","description":"指令"}},"required":["instruction"]}}},""" +
                """"required":["tasks"]}""",
            out
        )
    }

    check("R11-6 string 数组写出 items.type（无 description 时省略）") {
        val tags = JsonSchema.Property(
            type = "array",
            items = JsonSchema.Property("string")
        )
        val out = ToolSchemaJson.objectSchema(schema("tags" to tags)).toString()
        assertEquals(
            "标量数组",
            """{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string"}}},"required":["tags"]}""",
            out
        )
    }

    println("\n[R11] 边界：不该写的字段一个都不写")

    check("R11-7 非 array 类型即使带 items 也不写 items") {
        // 有的供应商对非数组类型带 items 会判 schema 非法，宁可不写
        val weird = JsonSchema.Property(
            type = "string",
            items = JsonSchema.Property("string")
        )
        val out = ToolSchemaJson.objectSchema(schema("x" to weird)).toString()
        assertEquals(
            "非数组不带 items",
            """{"type":"object","properties":{"x":{"type":"string"}},"required":["x"]}""",
            out
        )
    }

    check("R11-8 空 enum 不写（空数组等于「无合法取值」，比不写更糟）") {
        val out = ToolSchemaJson.objectSchema(
            schema("e" to JsonSchema.Property("string", enum = emptyList()))
        ).toString()
        assertEquals(
            "空 enum 省略",
            """{"type":"object","properties":{"e":{"type":"string"}},"required":["e"]}""",
            out
        )
    }

    check("R11-9 空嵌套 properties 不写该键") {
        val out = ToolSchemaJson.objectSchema(
            schema("o" to JsonSchema.Property("object", properties = emptyMap()))
        ).toString()
        assertEquals(
            "空嵌套省略",
            """{"type":"object","properties":{"o":{"type":"object"}},"required":["o"]}""",
            out
        )
    }

    println("\n[R11] 深层嵌套：递归不设层数上限")

    check("R11-10 三层嵌套（array → object → array → object）完整写出") {
        val deep = JsonSchema.Property(
            type = "array",
            items = JsonSchema.Property(
                type = "object",
                properties = mapOf(
                    "ids" to JsonSchema.Property(
                        type = "array",
                        items = JsonSchema.Property(
                            type = "object",
                            properties = mapOf("v" to JsonSchema.Property("string")),
                            required = listOf("v")
                        )
                    )
                ),
                required = listOf("ids")
            )
        )
        val out = ToolSchemaJson.objectSchema(schema("d" to deep)).toString()
        assertEquals(
            "三层嵌套",
            """{"type":"object","properties":{"d":{"type":"array","items":{"type":"object","properties":""" +
                """{"ids":{"type":"array","items":{"type":"object","properties":{"v":{"type":"string"}},""" +
                """"required":["v"]}}},"required":["ids"]}}},"required":["d"]}""",
            out
        )
    }

    check("R11-11 同一属性被两个工具复用时无状态污染（纯函数）") {
        val shared = JsonSchema.Property(
            type = "array",
            items = JsonSchema.Property("string")
        )
        val a = ToolSchemaJson.objectSchema(schema("s" to shared)).toString()
        val b = ToolSchemaJson.objectSchema(schema("t" to shared)).toString()
        if (a == b) throw AssertionError("两个不同键名生成了相同输出：$a")
        if (!a.contains("\"s\"") || !b.contains("\"t\"")) {
            throw AssertionError("键名丢失：a=$a b=$b")
        }
    }

    println()
    if (failures.isEmpty()) {
        println("R11 结果：$passed 通过 / 0 失败")
    } else {
        println("R11 结果：$passed 通过 / ${failures.size} 失败")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
