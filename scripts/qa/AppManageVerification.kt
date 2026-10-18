import com.example.agentkit.tools.AppManageLogic

/**
 * W-24 harness：软件安装/卸载的校验与结果判定纯函数（不加载 Android）。
 *
 * 重点：
 *  - 包名白名单（拒绝单段/中文/非法字符）；
 *  - APK 路径（只 .apk、拒 ..、大小写不敏感）；
 *  - `pm uninstall` 输出解析——**exitCode 不可靠**（Failure 时也可能为 0），
 *    必须以 stdout/stderr 关键词为准；系统应用/设备管理员/未安装要能区分。
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
    println("=== W-24 app_install / app_uninstall 校验 ===")

    check("包名：合法包名通过") {
        assertTrue("com.example.app", AppManageLogic.validatePackageName("com.example.app") == null)
        assertTrue("三段", AppManageLogic.validatePackageName("com.smile.gifmaker") == null)
        assertTrue("带下划线数字", AppManageLogic.validatePackageName("com.a_b.c2") == null)
        assertTrue("首尾空白容忍", AppManageLogic.validatePackageName("  com.a.b  ") == null)
    }

    check("包名：非法拒绝") {
        assertTrue("空", AppManageLogic.validatePackageName("") != null)
        assertTrue("单段", AppManageLogic.validatePackageName("app") != null)
        assertTrue("中文", AppManageLogic.validatePackageName("com.应用.测试") != null)
        assertTrue("空格", AppManageLogic.validatePackageName("com.exa mple") != null)
        assertTrue("斜杠", AppManageLogic.validatePackageName("com/a.b") != null)
        assertTrue("数字开头", AppManageLogic.validatePackageName("1com.a.b") != null)
    }

    check("APK 路径：.apk 通过（大小写不敏感）") {
        assertTrue("普通", AppManageLogic.validateApkPath("downloads/app.apk") == null)
        assertTrue("大写", AppManageLogic.validateApkPath("APP.APK") == null)
        assertTrue("带空格", AppManageLogic.validateApkPath("my app.apk") == null)
    }

    check("APK 路径：非 apk / 穿越 / 空 拒绝") {
        assertTrue("zip", AppManageLogic.validateApkPath("a.zip") != null)
        assertTrue("无后缀", AppManageLogic.validateApkPath("app") != null)
        assertTrue("穿越", AppManageLogic.validateApkPath("../etc/app.apk") != null)
        assertTrue("空", AppManageLogic.validateApkPath("") != null)
        assertTrue("apk.exe", AppManageLogic.validateApkPath("app.apk.exe") != null)
    }

    check("卸载结果：Success → 成功（即使 exitCode 非 0）") {
        val (ok, _) = AppManageLogic.interpretUninstall(0, "Success", "")
        assertTrue("成功", ok)
        val (ok2, _) = AppManageLogic.interpretUninstall(1, "Success", "warning")
        assertTrue("仍成功", ok2)
    }

    check("卸载结果：exitCode=0 但 Failure → 失败（exitCode 不可靠）") {
        val (ok, note) = AppManageLogic.interpretUninstall(
            0, "Failure [DELETE_FAILED_INTERNAL_ERROR]", ""
        )
        assertTrue("失败", !ok)
        assertTrue("说明含系统应用", note.contains("系统"))
    }

    check("卸载结果：设备管理员 / 未安装 分别说明") {
        val (ok1, note1) = AppManageLogic.interpretUninstall(
            0, "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]", ""
        )
        assertTrue("失败", !ok1)
        assertTrue("提示设备管理", note1.contains("设备管理"))

        val (ok2, note2) = AppManageLogic.interpretUninstall(0, "Failure [not installed for 0]", "")
        assertTrue("失败", !ok2)
        assertTrue("提示未安装", note2.contains("未安装"))
    }

    check("卸载结果：非零退出且无关键词 → 失败带 stderr") {
        val (ok, note) = AppManageLogic.interpretUninstall(1, "", "Permission denied")
        assertTrue("失败", !ok)
        assertTrue("带原因", note.contains("Permission denied"))
    }

    check("卸载结果：输出为空且 exit=0 → 结果不明（不冒充成功）") {
        val (ok, note) = AppManageLogic.interpretUninstall(0, "", "")
        assertTrue("不算成功", !ok)
        assertTrue("说明不确定", note.contains("不明"))
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
