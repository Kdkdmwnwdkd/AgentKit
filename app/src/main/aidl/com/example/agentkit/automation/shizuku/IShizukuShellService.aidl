// Shizuku UserService 接口：由 Shizuku 在 shell（uid 2000）进程中实例化，代 App 执行命令。
// 返回 Bundle：stdout(String) / stderr(String) / exitCode(int)，超时或异常为负值。
package com.example.agentkit.automation.shizuku;

import android.os.Bundle;

interface IShizukuShellService {
    // Shizuku 保留的销毁入口：事务号固定（16777114），unbindUserService(remove=true) 时触发，
    // 实现里应结束进程。不可改动、不可复用该事务号。
    void destroy() = 16777114;

    Bundle exec(String command, int timeoutMs) = 1;
}
