import com.example.agentkit.core.agent.AgentRunState
import com.example.agentkit.core.agent.AgentRunStateStore
import com.example.agentkit.core.agent.ApprovalGrouping
import com.example.agentkit.core.container.ToolchainInstaller
import com.example.agentkit.core.workspace.FileBackupStore
import com.example.agentkit.skill.Capability
import com.example.agentkit.skill.CapabilityRegistry
import com.example.agentkit.skill.checkCapabilityGate
import com.example.agentkit.skill.isToolAllowedByWhitelist
import com.example.agentkit.skill.policyFor
import com.example.agentkit.skill.trustLabel
import java.io.File

/**
 * B 批 harness：信任等级 fail-closed + 自动备份 + 前台服务/运行态 + 容器运行时安装。
 *
 * 四组断言：
 *  1. **信任等级**：未知/缺省 trust 必须按最严处理（这是 B 批修掉的 fail-open）；
 *  2. **自动备份**：写前备份 → 回滚能还原；新建文件回滚即删除；路径穿越被拒；
 *  3. **运行态**：进程被杀后残留 running 应对账成 interrupted 并给出恢复提示；
 *  4. **容器运行时**：包管理器识别、缺什么装什么、命令生成正确。
 *
 * 全部为纯逻辑断言，不依赖 Android 运行时。
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

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 $expected，实际 $actual")
}

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

/** 建一个临时目录，块结束自动删。 */
private fun tmpDir(block: (File) -> Unit) {
    val dir = File(System.getProperty("java.io.tmpdir"), "agentkit-b-${System.nanoTime()}")
    dir.mkdirs()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

fun main() {
    println("== B 批 harness：信任等级 / 自动备份 / 运行态 / 容器运行时 ==")

    /* ------------------------- B1 信任等级 fail-closed ------------------------- */

    check("B1-1 精确 packaged 仍给最高信任（不能矫枉过正）") {
        val p = policyFor("packaged")
        assertTrue("packaged 应允许脚本", p.allowScript)
        assertEquals("packaged 上限应为 DANGEROUS", Capability.DANGEROUS, p.maxCapability)
    }

    check("B1-2 imported / remote 收紧") {
        val imported = policyFor("imported")
        assertTrue("imported 不应允许脚本", !imported.allowScript)
        assertEquals("imported 上限应为 PRIVILEGED", Capability.PRIVILEGED, imported.maxCapability)
        val remote = policyFor("remote")
        assertTrue("remote 不应允许脚本", !remote.allowScript)
        assertEquals("remote 上限应为 SAFE", Capability.SAFE, remote.maxCapability)
    }

    check("B1-3 未知 trust 值按最严处理（旧的 else→packaged 是 fail-open）") {
        // 注意：大小写与首尾空格归一化后仍应识别为 packaged（同一个意图，不该被降级）
        listOf("trusted", "admin", "full", "god", "x", "packaged2", "imported2", "remote2")
            .forEach { bad ->
                val p = policyFor(bad)
                assertTrue("未知 trust「$bad」不应允许脚本", !p.allowScript)
                assertEquals("未知 trust「$bad」上限应为 SAFE", Capability.SAFE, p.maxCapability)
            }
    }

    check("B1-3b 大小写/空格归一化后仍识别为 packaged") {
        listOf("packaged", "Packaged", "PACKAGED", " packaged ").forEach { good ->
            val p = policyFor(good)
            assertTrue("「$good」应允许脚本", p.allowScript)
            assertEquals("「$good」上限应为 DANGEROUS", Capability.DANGEROUS, p.maxCapability)
        }
    }

    check("B1-4 null / 空串同样按最严处理") {
        listOf(null, "", "   ").forEach { bad ->
            val p = policyFor(bad)
            assertTrue("空 trust 不应允许脚本", !p.allowScript)
            assertEquals("空 trust 上限应为 SAFE", Capability.SAFE, p.maxCapability)
        }
    }

    check("B1-5 能力门对未知来源的 DANGEROUS 工具必须拦截") {
        val msg = checkCapabilityGate("shell_run", "trusted")
        assertTrue("未知来源调 shell_run 应被拦截", msg != null)
        val packaged = checkCapabilityGate("shell_run", "packaged")
        assertTrue("packaged 调 shell_run 不应被能力门拦（由人工审批兜底）", packaged == null)
    }

    check("B1-6 trustLabel 对未知值不谎称内置") {
        assertTrue("packaged 应显示打包可信", trustLabel("packaged").contains("打包可信"))
        assertTrue("Packaged 归一化后也应显示打包可信", trustLabel("Packaged").contains("打包可信"))
        assertTrue("未知值应标明来源未知", trustLabel("weird").contains("未知"))
    }

    check("B1-7 SkillManifest 默认 trust 不再是 packaged") {
        val src = read("app/src/main/java/com/example/agentkit/skill/SkillProtocol.kt")
        assertTrue(
            "manifest 默认 trust 应为空（未知）而非 packaged",
            "val trust: String = \"\"" in src
        )
    }

    check("B1-8 assets 内置技能由 SkillLoader 显式标为 packaged") {
        val src = read("app/src/main/java/com/example/agentkit/skill/SkillProtocol.kt")
        assertTrue("SkillLoader 应显式 copy(trust = \"packaged\")", "copy(trust = \"packaged\")" in src)
    }

    /* ------------------------- B2 自动备份 ------------------------- */

    check("B2-1 备份旧内容后可回滚还原") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val f = File(ws, "a.txt")
            f.writeText("v1")
            val id = store.backupBeforeWrite("a.txt")
            assertTrue("应产生备份 id", id != null)
            f.writeText("v2")
            assertEquals("回滚前内容应是 v2", "v2", f.readText())
            assertTrue("回滚应成功", store.restore(id!!))
            assertEquals("回滚后应还原为 v1", "v1", f.readText())
        }
    }

    check("B2-2 新建文件的备份回滚即删除该文件") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val id = store.backupBeforeWrite("new.txt")
            assertTrue("应产生备份 id", id != null)
            val f = File(ws, "new.txt")
            f.writeText("hello")
            assertTrue("回滚应成功", store.restore(id!!))
            assertTrue("回滚后文件应被删除", !f.exists())
        }
    }

    check("B2-3 路径穿越被拒绝（不产生备份）") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            assertTrue(".. 穿越应被拒", store.backupBeforeWrite("../evil.txt") == null)
            assertTrue("绝对路径应被拒", store.backupBeforeWrite("/etc/passwd") == null)
            assertTrue("空路径应被拒", store.backupBeforeWrite("  ") == null)
        }
    }

    check("B2-4 子目录文件可备份与回滚") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val sub = File(ws, "src/main").apply { mkdirs() }
            val f = File(sub, "k.kt")
            f.writeText("old")
            val id = store.backupBeforeWrite("src/main/k.kt")
            assertTrue("应产生备份 id", id != null)
            f.writeText("new")
            assertTrue("回滚应成功", store.restore(id!!))
            assertEquals("子目录内容应还原", "old", f.readText())
        }
    }

    check("B2-5 list 按时间倒序且 latestFor 命中最近一条") {
        tmpDir { dir ->
            val ws = File(dir, "ws").apply { mkdirs() }
            val store = FileBackupStore(File(dir, "bak"), ws)
            val f = File(ws, "x.txt")
            f.writeText("a")
            val id1 = store.backupBeforeWrite("x.txt")
            Thread.sleep(5)
            f.writeText("b")
            val id2 = store.backupBeforeWrite("x.txt")
            val list = store.list()
            assertEquals("应有 2 条备份", 2, list.size)
            assertEquals("最新一条应是最后创建的", id2, list.first().id)
            assertEquals("latestFor 应返回最近一条", id2, store.latestFor("x.txt")?.id)
            assertTrue("两条 id 应不同", id1 != id2)
        }
    }

    check("B2-6 写工具确实调用了备份钩子") {
        val src = read("app/src/main/java/com/example/agentkit/tools/BuiltInTools.kt")
        assertTrue("write_file 应调用 backupBeforeWrite", "backupHook?.backupBeforeWrite(path)" in src)
        assertTrue("edit_file 应调用 backupBeforeWrite", src.contains("backupHook?.backupBeforeWrite(path)"))
        assertTrue("应存在 WriteBackupHook 接口", "fun interface WriteBackupHook" in src)
    }

    check("B2-7 ChatViewModel 注入备份仓") {
        val src = read("app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt")
        assertTrue("应注入 FileBackupStore", "FileBackupStoreFactory.default(app)" in src)
        assertTrue("WriteFileTool 应传入备份钩子", "WriteFileTool(workspace, WriteBackupHook" in src)
        assertTrue("EditFileTool 应传入备份钩子", "EditFileTool(workspace, WriteBackupHook" in src)
    }

    /* ------------------------- B3 前台服务与运行态 ------------------------- */

    check("B3-1 残留 running 运行态对账成 interrupted") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(
                AgentRunState(
                    sessionId = "s1",
                    prompt = "帮我把项目构建出来",
                    stepIndex = 3,
                    status = AgentRunState.STATUS_RUNNING,
                    startedAt = 1000L
                )
            )
            val interrupted = store.reconcile()
            assertTrue("应检出中断", interrupted != null)
            assertEquals("状态应为 interrupted", AgentRunState.STATUS_INTERRUPTED, interrupted!!.status)
            assertTrue("恢复提示应含原文", interrupted.note.contains("帮我把项目构建出来"))
            assertTrue("恢复提示应提到步骤", interrupted.note.contains("第 3 步"))
            assertTrue("恢复提示应给出继续指引", interrupted.note.contains("继续"))
        }
    }

    check("B3-2 已完成的运行态不产生中断提示") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(AgentRunState(sessionId = "s1", status = AgentRunState.STATUS_DONE))
            assertTrue("已完成不应报中断", store.reconcile() == null)
        }
    }

    check("B3-3 对账只发生一次（幂等）") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            store.save(AgentRunState(sessionId = "s1", status = AgentRunState.STATUS_RUNNING))
            assertTrue("首次应检出", store.reconcile() != null)
            assertTrue("再次不应检出", store.reconcile() == null)
        }
    }

    check("B3-4 无运行态文件时不报错") {
        tmpDir { dir ->
            val store = AgentRunStateStore(File(dir, "run.json"))
            assertTrue("无文件应返回 null", store.load() == null)
            assertTrue("无文件不应报中断", store.reconcile() == null)
        }
    }

    check("B3-5 前台服务已在 Manifest 注册且类型正确") {
        val src = read("app/src/main/AndroidManifest.xml")
        assertTrue("应注册 AgentRunService", ".core.agent.AgentRunService" in src)
        assertTrue("前台服务类型应为 dataSync", "foregroundServiceType=\"dataSync\"" in src)
        assertTrue("不应 exported", "android:exported=\"true\"\n            android:foregroundServiceType" !in src)
    }

    check("B3-6 send() 启保活、finally 停保活并写运行态") {
        val src = read("app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt")
        assertTrue("应启动前台服务", "AgentRunService.start(app" in src)
        assertTrue("应停止前台服务", "AgentRunService.stop(app)" in src)
        assertTrue("应写入 running 运行态", "AgentRunState.STATUS_RUNNING" in src)
        assertTrue("结束时应标 done", "AgentRunState.STATUS_DONE" in src)
    }

    check("B3-7 中断提示在 UI 可见且可关闭") {
        val vm = read("app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt")
        val screen = read("app/src/main/java/com/example/agentkit/ui/ChatScreen.kt")
        assertTrue("VM 应暴露 interruptedNotice", "interruptedNotice" in vm)
        assertTrue("应有 dismiss 方法", "dismissInterruptedNotice" in vm)
        assertTrue("聊天页应渲染横幅", "InterruptedBanner" in screen)
    }

    /* ------------------------- B4 容器运行时 ------------------------- */

    check("B4-1 识别 apk 包管理器") {
        tmpDir { dir ->
            File(dir, "sbin").mkdirs()
            File(dir, "sbin/apk").writeText("#!/bin/sh")
            assertEquals(
                "应识别为 APK",
                ToolchainInstaller.PkgManager.APK,
                ToolchainInstaller.detectPkgManager(dir)
            )
        }
    }

    check("B4-2 识别 apt 包管理器") {
        tmpDir { dir ->
            File(dir, "usr/bin").mkdirs()
            File(dir, "usr/bin/apt-get").writeText("#!/bin/sh")
            assertEquals(
                "应识别为 APT",
                ToolchainInstaller.PkgManager.APT,
                ToolchainInstaller.detectPkgManager(dir)
            )
        }
    }

    check("B4-3 无包管理器时明确说明原因，不瞎试") {
        tmpDir { dir ->
            val d = ToolchainInstaller.detect(dir)
            assertTrue("包管理器应为 null", d.pkgManager == null)
            assertTrue("应给出原因", d.reason != null && d.reason.isNotBlank())
        }
    }

    check("B4-4 探测已安装的运行时") {
        tmpDir { dir ->
            File(dir, "bin").mkdirs()
            File(dir, "bin/node").writeText("")
            File(dir, "usr/bin").mkdirs()
            File(dir, "usr/bin/python3").writeText("")
            val d = ToolchainInstaller.detect(dir)
            assertTrue("应识别 node", ToolchainInstaller.Lang.NODE in d.installed)
            assertTrue("应识别 python", ToolchainInstaller.Lang.PYTHON in d.installed)
            assertTrue("不应识别 java", ToolchainInstaller.Lang.JAVA !in d.installed)
        }
    }

    check("B4-5 apk 安装命令正确且带 --no-cache") {
        val cmd = ToolchainInstaller.installCommand(ToolchainInstaller.Lang.NODE, ToolchainInstaller.PkgManager.APK)
        assertTrue("应含 apk add", cmd!!.startsWith("apk add"))
        assertTrue("应带 --no-cache（避免 apk 缓存吃满手机存储）", "--no-cache" in cmd)
        assertTrue("应含 nodejs", "nodejs" in cmd)
    }

    check("B4-6 apt 安装命令必须先 update") {
        val cmd = ToolchainInstaller.installCommand(ToolchainInstaller.Lang.PYTHON, ToolchainInstaller.PkgManager.APT)
        assertTrue("应含 apt-get update", cmd!!.contains("apt-get update"))
        assertTrue("应含 install -y（非交互）", "apt-get install -y" in cmd)
        assertTrue("update 必须在 install 之前", cmd.indexOf("update") < cmd.indexOf("install"))
    }

    check("B4-7 一键装齐缺失项；全装好时返回 null") {
        tmpDir { dir ->
            File(dir, "sbin").mkdirs()
            File(dir, "sbin/apk").writeText("")
            val d = ToolchainInstaller.detect(dir)
            val cmd = ToolchainInstaller.installMissingCommand(d)
            assertTrue("应生成命令", cmd != null)
            assertTrue("应含 nodejs", cmd!!.contains("nodejs"))
            assertTrue("应含 python3", cmd.contains("python3"))
            assertTrue("应含 openjdk", cmd.contains("openjdk"))
            assertTrue("只应出现一条 apk add", cmd.split("apk add").size == 2)

            // 全部装好 → 不需要安装
            File(dir, "bin").mkdirs()
            File(dir, "bin/node").writeText("")
            File(dir, "bin/python3").writeText("")
            File(dir, "bin/java").writeText("")
            val d2 = ToolchainInstaller.detect(dir)
            assertTrue("全装好应返回 null", ToolchainInstaller.installMissingCommand(d2) == null)
        }
    }

    check("B4-8 新工具必须登记能力表且为 DANGEROUS") {
        assertEquals(
            "container_toolchain 应为 DANGEROUS",
            Capability.DANGEROUS,
            CapabilityRegistry.cap("container_toolchain")
        )
    }

    check("B4-9 新工具挂人工审批并归入 shell 组") {
        val tool = read("app/src/main/java/com/example/agentkit/tools/ContainerToolchainTool.kt")
        assertTrue("应挂 needsApproval", "override val needsApproval = true" in tool)
        assertEquals(
            "应归入 shell 审批组",
            ApprovalGrouping.GROUP_SHELL,
            ApprovalGrouping.groupOf("container_toolchain")
        )
    }

    check("B4-10 容器工具已注册进 registry") {
        val vm = read("app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt")
        assertTrue("应注册 ContainerToolchainTool", "register(ContainerToolchainTool(app))" in vm)
    }

    check("B4-11 一次性命令执行器存在且走独立进程通道") {
        val runner = read("app/src/main/java/com/example/agentkit/core/container/ContainerCommandRunner.kt")
        assertTrue("应使用 ProcessBuilder（取退出码）", "ProcessBuilder" in runner)
        assertTrue("应等待退出", "waitFor" in runner)
        val screen = read("app/src/main/java/com/example/agentkit/ui/ContainerScreen.kt")
        assertTrue("容器页应有运行时安装入口", "ToolchainInstaller" in screen)
        assertTrue("容器页应调用执行器", "ContainerCommandRunner" in screen)
    }

    println("\n== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
