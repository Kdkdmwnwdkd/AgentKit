# T09 真机验证清单 · proot 落位是否可 `execve`

> 关联：`00-roadmap.md` §4 T09 · 代码位置：`core/container/ProotRunner.kt`（`ensureBinary` / `binaryFile` / `buildInvocation`）
> 为什么必须实测：Android 10+（API 29+）对应用私有目录有 **W^X（Write XOR Execute）** 限制，部分 ROM / SELinux 策略会拒绝从 `filesDir` `execve` 三方可执行文件。**沙箱内无法验证，只能在真机跑一次。**

---

## 背景：为什么这件事会「看起来像别的问题」

被拦时的表象极具迷惑性：

- `proot` 进程**瞬间退出**（退出码非 0）；
- logcat **没有**清晰的 `permission denied`，很容易误判成「rootfs 坏了 / ptrace 受限 / proot 二进制损坏」；
- 表现为容器页一直显示降级文案，或终端一打开就黑屏退出。

参照仓库 AiCode 的注释明确写了「`filesDir` 不是允许 `execve` 的位置」，而我们当前正是提取到 `filesDir/bin/proot`（`ProotRunner.binaryFile()`）。**这条验证的结果会反向决定后续是否要改落位方案**。

---

## 验证步骤（在真机上按顺序跑）

### 第 1 步：确认 proot 已落位且权限正确

```bash
adb shell run-as com.example.agentkit ls -l /data/data/com.example.agentkit/bin/proot
```

- **期望**：文件存在，权限为 `-rwx------`（0700）。
- 若不存在：先在 App 里打开一次「容器」页触发提取（`ensureBinary`），或确认 `assets/bin/arm64-v8a/proot` 已随包分发（需先跑 `scripts/build-proot.sh`）。

### 第 2 步：直接执行 proot 看退出码（**核心判据**）

```bash
adb shell run-as com.example.agentkit /data/data/com.example.agentkit/bin/proot --version; echo "exit=$?"
```

- **通过**：输出 proot 版本号，`exit=0` → `filesDir` 可 `execve`，**无需改造**，清单到此结束。
- **失败**：`exit` 非 0（常见 1 / 126 / 127 / 139），继续第 3 步。

### 第 3 步：抓 SELinux 拒绝日志

```bash
adb logcat -c
adb shell run-as com.example.agentkit /data/data/com.example.agentkit/bin/proot --version
adb logcat -d | grep -i "avc: denied"
```

- **命中 `avc: denied { execute }`**（或 `execute_no_trans`）→ **确认被 SELinux / W^X 拦下**，执行第 5 步改造方案。
- **未命中 avc** 但退出码非 0 → 大概率是二进制与 ABI 不匹配或构建产物损坏，先核对：
  ```bash
  adb shell getprop ro.product.cpu.abi      # 必须是 arm64-v8a
  adb shell run-as com.example.agentkit /data/data/com.example.agentkit/bin/proot --help 2>&1 | head
  ```

### 第 4 步（可选）：确认容器内命令不再卡死（T10 的验收）

在 App 内打开容器终端，依次执行：

```bash
echo $CI $NO_COLOR $PAGER        # 期望：true 1 cat
mktemp                            # 期望：/tmp/tmp.XXXX（不再指向 App cache）
which git && git log --oneline -1 # 期望：直接输出，不进分页器
sudo apt-get install -y tree 2>/dev/null || apt-get install -y tree  # 期望：不卡在确认提示
```

- 若 `git log` 仍进分页器 / `apt` 仍卡确认 → T10 的 env 没生效，回报 `ProotRunner.guestEnvMap()` 的实际取值。

### 第 5 步（仅第 3 步命中 avc 才做）：改造落位方案

1. 把 proot 二进制改名为 `libproot.so`，放进 `app/src/main/jniLibs/arm64-v8a/`；
2. 运行时改从 `context.applicationInfo.nativeLibraryDir` 取路径（改 `ProotRunner.binaryFile()`）；
3. 在 `app/build.gradle.kts` 的 packaging 里设 `useLegacyPackaging = true`——否则 `.so` 不解压，`execve` 拿不到实体文件；
4. **`targetSdk` 保持 34 不变**。

> 参照仓库为了绕过该限制把 `targetSdk` 锁在 28，代价是上不了 Google Play。**我们不接受这个代价**，所以宁可改落位也不降 targetSdk。

---

## 回报格式

请把以下信息回报给主理人（齐活林）：

| 项 | 内容 |
|---|---|
| 设备型号 / Android 版本 | 如 Pixel 7 / Android 14 |
| `ro.product.cpu.abi` | 应为 arm64-v8a |
| 第 2 步的 exit 码 | 0 或非 0（写具体值） |
| 第 3 步是否命中 `avc: denied { execute }` | 是 / 否 |
| 第 4 步四项命令的实际输出 | 贴输出 |
| 结论 | 「filesDir 可用，无需改造」/「需改走 jniLibs + nativeLibraryDir」 |

主理人据此决定：结论为「可用」则 T09 关闭、继续 T01/T07/T08；结论为「需改造」则先做第 5 步再进入后续改造项。
