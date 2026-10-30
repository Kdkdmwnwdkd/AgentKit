package com.example.agentkit.core.kernel

/**
 * 工具契约：内核认识工具的全部信息。
 *
 * ## 和现有 `AgentTool` 的差别
 * 1. **描述与参数是函数**（`brief` / `params`），不是常量。全量模式下大模型需要完整说明，
 *    而 META/GROUPED 模式下这些字符串根本不会被渲染——常量在这里是白花的构造开销，
 *    以及更糟的：让人以为「描述写了就一定会发出去」。
 * 2. **`group` 是一等字段**。现有实现靠一张中心化白名单 `LOCAL_CORE_TOOLS` 决定谁能进
 *    本地模型视野，新增工具必须记得去改那张表，忘了就等于永久隐藏。分组 + 按需激活
 *    不需要维护任何白名单：新工具声明自己属于哪组，就自动能被发现。
 * 3. **审批看参数**（`approval`），不是布尔值。`shell_run` 跑 `ls` 和跑 `rm -rf` 是两件事，
 *    用 `needsApproval: Boolean` 只能二选一，最后不是天天弹窗（用户闭眼点允许）
 *    就是完全不弹（真危险时也拦不住）。
 * 4. **执行入口自带兜底**：见 [invoke]，工具抛异常不会把整轮对话炸掉。
 */
class ToolSpec(
    /** 工具名：模型调用时用的标识，必须全局唯一。 */
    val name: String,

    /** 所属分组（见 [Exposure]）。分组是「懒加载」的粒度，不是 UI 分类。 */
    val group: String,

    /** 危险等级：决定默认审批策略，也决定 META 模式下是否允许被自动调用。 */
    val danger: Danger = Danger.WRITE,

    /**
     * 一句话说明。参数是本次面向的模型——同一个工具可以给大模型写详细版、
     * 给小模型写一句话版，由实现方决定，内核只负责在需要时才调用它。
     */
    val brief: (ModelRef) -> String,

    /** 参数清单。默认无参。 */
    val params: (ModelRef) -> List<Param> = { emptyList() },

    /** 按入参决定审批档位。默认 [Approval.INHERIT]（交给 [Danger] 推断）。 */
    val approval: (Args) -> Approval = { Approval.INHERIT },

    /** 真正执行。允许抛异常，[invoke] 会兜住。 */
    val run: suspend (Args) -> Outcome
) {
    /**
     * 安全执行：把异常收敛成带 hint 的失败结果。
     *
     * 为什么不让异常往上抛：工具实现有 50 多个，任何一个的 `!!`、IO 异常、权限拒绝都会
     * 中断整个回合，用户看到的是「发送失败」而不是「这个工具失败了，模型换个方式」。
     * 兜底放在契约层，就不必要求每个实现者都记得 try/catch。
     */
    suspend fun invoke(args: Args): Outcome = try {
        run(args)
    } catch (t: Throwable) {
        // CancellationException 必须原样抛，否则协程取消会被吞掉（上层无法停止）。
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        Outcome.fail(
            text = "工具 $name 执行异常：${t.message ?: t::class.simpleName}",
            hint = "换一种方式完成，或先确认权限/参数是否正确；不要重复同样的调用"
        )
    }

    /** 审批档位（合并工具自述 + 危险等级）。纯函数，便于单测。 */
    fun approvalFor(args: Args, mode: ApprovalMode): Approval {
        val declared = approval(args)
        val base = if (declared != Approval.INHERIT) declared else when (danger) {
            Danger.READ -> Approval.ALLOW
            Danger.WRITE -> Approval.ASK
            Danger.DESTRUCTIVE -> Approval.ASK
        }
        // 入参不可读（元工具转发场景）时，工具自述的「这次安全」是不可信的：
        // 它读到的是 null，null 会被误当成「没有危险参数」。所以有副作用的工具
        // 在参数不可读时必须降级为询问。只读工具无副作用，仍按自述放行。
        val guarded = if (args.opaque && danger != Danger.READ && base == Approval.ALLOW) {
            Approval.ASK
        } else {
            base
        }
        return when (mode) {
            // 全放行模式下，不可逆操作仍然要问一次：这是「用户说过别烦我」和
            // 「这条命令删了就没了」之间唯一合理的折中。
            ApprovalMode.ALLOW_ALL -> if (danger == Danger.DESTRUCTIVE) Approval.ASK else Approval.ALLOW
            ApprovalMode.ASK_ALL -> if (guarded == Approval.DENY) Approval.DENY else Approval.ASK
            ApprovalMode.AUTO -> guarded
        }
    }

    override fun toString(): String = "ToolSpec($name, group=$group, danger=$danger)"
}

/**
 * 危险等级。
 *
 * READ 只读（可并行、默认放行）；WRITE 有副作用但可恢复；DESTRUCTIVE 不可逆
 * （删除、覆写、格式化），无论什么模式都不给「静默放行」的默认值。
 */
enum class Danger { READ, WRITE, DESTRUCTIVE }

/** 工具对某次具体调用的审批诉求。 */
enum class Approval {
    /** 不表态，由 [Danger] 与全局模式推断。 */
    INHERIT,

    /** 放行（工具自己确定这次调用无害，如只读子命令）。 */
    ALLOW,

    /** 需要用户点一次允许。 */
    ASK,

    /** 直接拒绝，不给用户弹窗（用于明显越权的参数，如路径越出沙箱）。 */
    DENY
}

/** 全局审批模式（用户设置里的一档）。 */
enum class ApprovalMode {
    /** 按工具/参数自动判定（默认）。 */
    AUTO,

    /** 一律询问（谨慎模式）。 */
    ASK_ALL,

    /** 一律放行（仅建议在完全可信环境使用；DESTRUCTIVE 仍按工具自述走）。 */
    ALLOW_ALL
}

/**
 * 一个参数。类型用字符串而非枚举：内核不需要理解类型，只需要把它原样渲染给对方模型，
 * 校验交给工具实现（它才知道什么叫合法）。
 */
data class Param(
    val name: String,
    val type: String = "string",
    val required: Boolean = true,
    val desc: String = ""
)
