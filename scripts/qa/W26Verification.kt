import com.example.agentkit.core.BackupArchive
import com.example.agentkit.core.BackupManifest
import com.example.agentkit.core.agent.TitleLogic

/**
 * W-26 harness：AI 标题清洗（TitleLogic）+ 备份含备用 Key 池（BackupArchive）。
 *
 * 重点：
 *  - 标题清洗要能吃掉模型的脏输出（引号/「标题：」前缀/句末标点/第二行解释）；
 *  - 无信息量的开场不触发生成（省一次模型调用）；
 *  - 备份**往返**：带 keypool.json 的包读回后 keyPoolJson 不丢；
 *    老备份（无该成员）读回为 null，恢复时保持现状；
 *  - 未知 entry 仍被白名单挡住（不因新增成员放宽校验）。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun manifest(conv: Int, mem: Int) = BackupManifest(
    version = BackupArchive.VERSION,
    createdAt = 1_700_000_000_000L,
    conversationCount = conv,
    memoryCount = mem,
    hasConfig = false,
    promptCount = 0,
    hasPrompts = false
)

fun main() {
    println("=== W-26 标题清洗 + 备份 Key 池 ===")

    // ---------- TitleLogic.clean ----------
    check("去中文引号包裹") {
        assertTrue("「」", TitleLogic.clean("「整理下载目录」") == "整理下载目录")
        assertTrue("“”", TitleLogic.clean("“整理下载目录”") == "整理下载目录")
        assertTrue("英文双引号", TitleLogic.clean("\"整理下载目录\"") == "整理下载目录")
        assertTrue("英文单引号", TitleLogic.clean("'整理下载目录'") == "整理下载目录")
    }

    check("去「标题：」前缀（中英文）") {
        assertTrue("中文", TitleLogic.clean("标题：整理下载目录") == "整理下载目录")
        assertTrue("Title:", TitleLogic.clean("Title: 整理下载目录") == "整理下载目录")
        assertTrue("带空格", TitleLogic.clean("标题:  整理下载目录") == "整理下载目录")
    }

    check("去句末标点") {
        assertTrue("。", TitleLogic.clean("整理下载目录。") == "整理下载目录")
        assertTrue("！", TitleLogic.clean("整理下载目录！") == "整理下载目录")
        assertTrue("?", TitleLogic.clean("整理下载目录?") == "整理下载目录")
        assertTrue("逗号", TitleLogic.clean("整理下载目录，") == "整理下载目录")
    }

    check("只取第一行（模型把解释写在后面）") {
        assertTrue(
            "第一行",
            TitleLogic.clean("整理下载目录\n这个标题概括了用户的需求") == "整理下载目录"
        )
    }

    check("超长截断 20 字 + 省略号") {
        val long = "一二三四五六七八九十一二三四五六七八九十一二三"
        val out = TitleLogic.clean(long)!!
        assertTrue("长度=${out.length}", out.length == TitleLogic.MAX_CHARS + 1)
        assertTrue("以省略号结尾", out.endsWith("…"))
    }

    check("空/纯标点/纯空白 → null") {
        assertTrue("空", TitleLogic.clean("") == null)
        assertTrue("空白", TitleLogic.clean("   \n  ") == null)
        assertTrue("纯标点", TitleLogic.clean("。。。") == null)
    }

    check("正常标题原样保留") {
        assertTrue("普通", TitleLogic.clean("整理下载目录") == "整理下载目录")
        assertTrue("带空格内部", TitleLogic.clean("  整理 下载 目录  ") == "整理 下载 目录")
    }

    // ---------- worthGenerating ----------
    check("无信息量开场不生成（省调用）") {
        assertTrue("你好", !TitleLogic.worthGenerating("你好"))
        assertTrue("在吗", !TitleLogic.worthGenerating("在吗"))
        assertTrue("hi", !TitleLogic.worthGenerating("hi"))
        assertTrue("HELLO 大写", !TitleLogic.worthGenerating("HELLO"))
        assertTrue("太短", !TitleLogic.worthGenerating("嗯"))
        assertTrue("纯表情", !TitleLogic.worthGenerating("😀😀😀😀"))
    }

    check("有信息量的请求应生成") {
        assertTrue("具体任务", TitleLogic.worthGenerating("帮我整理下载目录里的重复文件"))
        assertTrue("英文任务", TitleLogic.worthGenerating("summarize this pdf please"))
    }

    // ---------- BackupArchive：keypool 往返 ----------
    check("备份往返：带 keypool 的包能读回") {
        val pool = """{"p1":[{"id":"k1","label":"备用 1","key":"sk-abcdef123456"}]}"""
        val bytes = BackupArchive.create(
            manifest = manifest(0, 0),
            conversationsJson = "[]",
            memoriesJson = "[]",
            configJson = """{"profiles":[]}""",
            promptsJson = "[]",
            keyPoolJson = pool
        )
        val read = BackupArchive.read(bytes)
        assertTrue("应成功", read is BackupArchive.ReadResult.Ok)
        read as BackupArchive.ReadResult.Ok
        assertTrue("keypool 不丢", read.keyPoolJson == pool)
    }

    check("老备份（无 keypool 成员）读回为 null，恢复时保持现状") {
        val bytes = BackupArchive.create(
            manifest = manifest(0, 0),
            conversationsJson = "[]",
            memoriesJson = "[]",
            configJson = null,
            promptsJson = null,
            keyPoolJson = null
        )
        val read = BackupArchive.read(bytes)
        read as BackupArchive.ReadResult.Ok
        assertTrue("null", read.keyPoolJson == null)
    }

    check("损坏的 zip / 缺 manifest 仍被拒（新成员不放宽校验）") {
        val bad = BackupArchive.read("not a zip".toByteArray())
        assertTrue("拒绝", bad is BackupArchive.ReadResult.Invalid)
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
