import com.example.agentkit.automation.shizuku.ShizukuResultLogic

/**
 * W-37 harness：Shizuku UserService 退出码语义映射。
 *
 * 真机事故：`echo hello` 6ms 返回 code=timeout，模型被 hint 骗着连试 12 次。
 * 根因是「通道启动失败(-1)」与「命令超时(-1000)」没区分开。
 * 断言目标：两种失败必须给出不同语义，且 startError 永远可读。
 */
fun main() {
    var pass = 0
    fun check(name: String, cond: Boolean) {
        if (cond) {
            pass++
        } else {
            println("FAIL: $name")
            kotlin.system.exitProcess(1)
        }
    }

    // 1. 正常成功
    val ok = ShizukuResultLogic.map("uid=2000", "", 0)
    check("exit=0 视为成功", ok.exitCode == 0 && !ok.timedOut && ok.startError == null && ok.stdout == "uid=2000")

    // 2. 命令自身失败（比如 ls 不存在的目录）：不是通道问题，不能标成 startError
    val cmdFail = ShizukuResultLogic.map("", "ls: /nope: No such file or directory", 2)
    check("命令失败≠通道失败", cmdFail.exitCode == 2 && !cmdFail.timedOut && cmdFail.startError == null)
    check("命令失败保留 stderr", cmdFail.stderr.contains("No such file"))

    // 3. 真超时：必须标 timedOut，且不能同时标 startError（否则调用方提示会互相打架）
    val to = ShizukuResultLogic.map("partial", "", ShizukuResultLogic.EXIT_TIMEOUT)
    check("超时只标 timedOut", to.timedOut && to.startError == null)
    check("超时保留已产出的输出", to.stdout == "partial")

    // 4. 通道异常：必须标 startError、不能标 timedOut
    val broken = ShizukuResultLogic.map("", "IllegalStateException: 服务已断开", ShizukuResultLogic.EXIT_FAILURE)
    check("通道异常只标 startError", broken.startError != null && !broken.timedOut)

    // 5. 通道异常但服务端没给原因：hint 不能是空串（模型看到空原因只能瞎猜）
    val silent = ShizukuResultLogic.map("", "", ShizukuResultLogic.EXIT_FAILURE)
    check("无原因也有兜底文案", silent.startError?.isNotBlank() == true)

    // 6. 退出码常量与 AIDL 约定一致（改一处忘另一处会静默错判）
    check("超时码=-1000", ShizukuResultLogic.EXIT_TIMEOUT == -1000)
    check("异常码=-1", ShizukuResultLogic.EXIT_FAILURE == -1)

    println("KS_SHIZUKU_RESULT_OK $pass/9")
}
