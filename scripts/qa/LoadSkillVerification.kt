import com.example.agentkit.tools.LoadSkillLogic

/**
 * W-23 harness：load_skill 校验纯函数（不加载 Android，不联网）。
 *
 * 重点不是"正常路径能过"，而是**安全边界挡不挡得住**：
 *  - 只允许 https：http / 其它协议一律拒绝（防中间人替换技能包）；
 *  - 内网禁止：10/127/172.16-31/192.168/169.254/100.64-127/localhost/IPv6 本地段（防 SSRF）；
 *  - 体积上限 2 MB（防 zip 炸弹与流量耗尽）；
 *  - base64 容错（data: 前缀、换行、URL 安全字符）但不能把垃圾解出字节；
 *  - id 白名单与安装器一致。
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

fun main() {
    println("=== W-23 load_skill 校验与安全边界 ===")

    // ---------- URL ----------
    check("https 公网地址通过") {
        assertTrue("正常域名", LoadSkillLogic.validateUrl("https://example.com/skills/demo.zip") == null)
        assertTrue("带端口", LoadSkillLogic.validateUrl("https://example.com:8443/a.zip") == null)
        assertTrue("带查询串", LoadSkillLogic.validateUrl("https://cdn.jsdelivr.net/x.zip?raw=1") == null)
    }

    check("http 明文拒绝") {
        val err = LoadSkillLogic.validateUrl("http://example.com/a.zip")
        assertTrue("应拒绝", err != null)
        assertTrue("提示 https", err!!.contains("https"))
    }

    check("非 http 协议拒绝（file:// / ftp://）") {
        assertTrue("file", LoadSkillLogic.validateUrl("file:///sdcard/a.zip") != null)
        assertTrue("ftp", LoadSkillLogic.validateUrl("ftp://example.com/a.zip") != null)
    }

    check("环回/本机拒绝（SSRF）") {
        assertTrue("127.0.0.1", LoadSkillLogic.validateUrl("https://127.0.0.1/a.zip") != null)
        assertTrue("localhost", LoadSkillLogic.validateUrl("https://localhost/a.zip") != null)
        assertTrue("[::1]", LoadSkillLogic.validateUrl("https://[::1]/a.zip") != null)
        assertTrue("0.0.0.0", LoadSkillLogic.validateUrl("https://0.0.0.0/a.zip") != null)
    }

    check("内网段拒绝（10/172.16-31/192.168/169.254/CGNAT）") {
        assertTrue("10.x", LoadSkillLogic.validateUrl("https://10.1.2.3/a.zip") != null)
        assertTrue("172.16", LoadSkillLogic.validateUrl("https://172.16.0.1/a.zip") != null)
        assertTrue("172.31", LoadSkillLogic.validateUrl("https://172.31.255.255/a.zip") != null)
        assertTrue("192.168", LoadSkillLogic.validateUrl("https://192.168.1.1/a.zip") != null)
        assertTrue("169.254 元数据", LoadSkillLogic.validateUrl("https://169.254.169.254/a.zip") != null)
        assertTrue("100.64 CGNAT", LoadSkillLogic.validateUrl("https://100.64.0.1/a.zip") != null)
    }

    check("公网 IP 不误杀（172.32 / 100.63 / 100.128）") {
        assertTrue("172.32", LoadSkillLogic.validateUrl("https://172.32.0.1/a.zip") == null)
        assertTrue("100.63", LoadSkillLogic.validateUrl("https://100.63.0.1/a.zip") == null)
        assertTrue("100.128", LoadSkillLogic.validateUrl("https://100.128.0.1/a.zip") == null)
    }

    check("空 URL 拒绝") {
        assertTrue("空串", LoadSkillLogic.validateUrl("") != null)
        assertTrue("空白", LoadSkillLogic.validateUrl("   ") != null)
    }

    check("hostOf 解析：端口/路径/双斜杠") {
        assertTrue("普通", LoadSkillLogic.hostOf("https://a.com/x/y") == "a.com")
        assertTrue("端口", LoadSkillLogic.hostOf("https://a.com:8080/x") == "a.com")
        assertTrue("IPv6", LoadSkillLogic.hostOf("https://[2001:db8::1]:443/x") == "2001:db8::1")
    }

    // ---------- 体积 ----------
    check("体积：0 拒绝 / 2MB 边界通过 / 超限拒绝") {
        assertTrue("0", LoadSkillLogic.validateZipSize(0) != null)
        assertTrue("1 字节", LoadSkillLogic.validateZipSize(1) == null)
        assertTrue("正好 2MB", LoadSkillLogic.validateZipSize(2L * 1024 * 1024) == null)
        assertTrue("2MB+1", LoadSkillLogic.validateZipSize(2L * 1024 * 1024 + 1) != null)
    }

    // ---------- base64 ----------
    check("base64：普通内容可解码") {
        val bytes = LoadSkillLogic.decodeBase64("aGVsbG8=")
        assertTrue("hello", bytes != null && String(bytes) == "hello")
    }

    check("base64：data: 前缀 / 换行 / 无 padding 容错") {
        assertTrue("data 前缀", String(LoadSkillLogic.decodeBase64("data:application/zip;base64,aGVsbG8=")!!) == "hello")
        assertTrue("换行", String(LoadSkillLogic.decodeBase64("aGVs\nbG8=")!!) == "hello")
        assertTrue("无 padding", String(LoadSkillLogic.decodeBase64("aGVsbG8")!!) == "hello")
    }

    check("base64：URL 安全字符（- _）兼容") {
        // "??>" 的 base64 标准为 "Pz4+"，URL 安全为 "Pz4-"
        val std = LoadSkillLogic.decodeBase64("Pz4+")
        val urlSafe = LoadSkillLogic.decodeBase64("Pz4-")
        assertTrue("两者一致", std != null && urlSafe != null && std.contentEquals(urlSafe))
    }

    check("base64：空串/纯空白/乱码返回 null") {
        assertTrue("空", LoadSkillLogic.decodeBase64("") == null)
        assertTrue("空白", LoadSkillLogic.decodeBase64("   \n ") == null)
        assertTrue("非法", LoadSkillLogic.decodeBase64("这不是base64!!!") == null)
    }

    // ---------- id ----------
    check("id 白名单：字母/数字/点/下划线/短横线") {
        assertTrue("正常", LoadSkillLogic.validateId("demo_skill-1.0") == null)
        assertTrue("空", LoadSkillLogic.validateId("") != null)
        assertTrue("空格", LoadSkillLogic.validateId("a b") != null)
        assertTrue("斜杠（路径穿越）", LoadSkillLogic.validateId("../x") != null)
        assertTrue("中文", LoadSkillLogic.validateId("技能") != null)
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
