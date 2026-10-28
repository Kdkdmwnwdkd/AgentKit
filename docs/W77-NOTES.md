# W-77：终于出字了，但慢了 60 倍——根因是 `-O0`

## 一、先记下这次的成功与失败

真机 1.444 第一次真的出了字：

```
你好！有什么可以帮助你的吗？
```

7 token / 42 字符 / 命中结束符（第 7 步）/ 23100 ms

链路终于通了：分词 → prefill → 采样 → 流式上屏，全部走通。W-72~W-76 的修复累积生效。

**但速度慢到不可用**：

| 阶段 | 实测 | 正常应有 | 慢多少 |
|---|---|---|---|
| 预热 1 token（6 线程） | 24571 ms | 200~400 ms | 约 80 倍 |
| 预热 1 token（4 线程） | 7521 ms | 200~400 ms | 约 25 倍 |
| prefill 83 token | 188069 ms（0.4 tok/s） | 3~5 秒（20~40 tok/s） | 约 50 倍 |
| 生成 7 token | 23100 ms（0.3 tok/s） | 1.5~2 秒（3~5 tok/s） | 约 12 倍 |
| 首字 | 220384 ms | 2~4 秒 | — |

## 二、两个数字指向同一个根因

### 线索 1：1 个 token 就要 24.5 秒

3B Q4_K_M 是 2 GB 权重。生成 1 个 token 必须把 2 GB 全读一遍，手机 LPDDR4X 带宽
约 20 GB/s，理论下限 **100 ms**，实际 200~400 ms。24.5 秒意味着慢了 80 倍。

**模型太大解释不了 80 倍。** 内存带宽不足只能解释 2~3 倍。

### 线索 2：6 线程 24.5 秒，4 线程 7.5 秒——线程越多越慢

正常实现里多线程只会更快或持平。开 6 个线程反而慢 3.3 倍，只有一种解释：
**单线程计算本身的耗时被放大了几十倍，以至于线程同步的固定开销显得微不足道，
而更多线程意味着更多次同步。**

这正是 `-O0` 的特征：每个内层循环都有函数调用、没有向量化、没有寄存器复用。

### 结论

AGP 在 debug 构建里给 `externalNativeBuild` 传 `-DCMAKE_BUILD_TYPE=Debug`。
CMake 的 `CMAKE_CXX_FLAGS_DEBUG` 默认只有 `-g`（等价 `-O0`）。

我们的 `app/build.gradle.kts` 里 `buildTypes.debug` 从来没配过 cmake arguments，
CMakeLists.txt 也没碰过 build type。所以**从第一版起，ggml 就一直按 `-O0` 编译**。

这也解释了为什么前面几版所有调参（分块、线程、采样、prompt 长度）都像在隔靴搔痒：
底层计算慢 50 倍，上层怎么调都是零头。

## 三、修法：三层保险

```cmake
# ① 直接替换 Debug 的 flags 字符串（CACHE FORCE 会压掉命令行 -D 传进来的值）
foreach(_lang C CXX)
    set(CMAKE_${_lang}_FLAGS_DEBUG "-O3 -g -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_${_lang}_FLAGS_RELWITHDEBINFO "-O3 -g -DNDEBUG" CACHE STRING "" FORCE)
endforeach()

# ② 给 ggml 的每个编译 target 追加 -O3（gcc/clang 对多个 -O 取最后一个）
foreach(_t ggml ggml-base ggml-cpu llama)
    if (TARGET ${_t})
        target_compile_options(${_t} PRIVATE -O3)
    endif()
endforeach()
```

```kotlin
// ③ gradle 显式传 Release（AGP 可能用自己的 -D 覆盖，所以只作辅助）
arguments += "-DCMAKE_BUILD_TYPE=Release"
```

再加一条**运行时自证**：

```cpp
#ifdef __OPTIMIZE__
    LOGI("编译优化：已开启（-O2/-O3 内核）");
#else
    LOGE("编译优化：未开启（-O0）！这次推理会慢几十倍，问题在构建参数而不是设备");
#endif
```

下次日志里这行如果是「未开启」，就说明三层保险都没生效，不用再猜设备。

## 四、预期

| 指标 | 1.444 实测 | 修完预期 |
|---|---|---|
| 预热 1 token | 24571 ms | 200~400 ms |
| prefill 83 token | 188 秒 | 3~5 秒 |
| 生成速度 | 0.3 tok/s | 3~5 tok/s |
| 首字 | 220 秒 | 3~6 秒 |
| 「你好」整轮 | 243 秒 | 8~15 秒 |

这是 CPU 跑 3B Q4 的物理上限区间，和 IRIDIS 的 4.8 tok/s 同一量级。

## 五、顺手改掉的：关于页的发布时间

之前「关于」页只有版本号，且我口头说的时间一直是 UTC（比用户慢 8 小时）。

现在读 APK 的安装时间戳，显式按 `Asia/Shanghai` 格式化：

```
版本 1.445  ·  2026-10-28 15:0x（北京时间）
```

时区写死在代码里，不再受 CI 机器（UTC）影响。

## 六、契约

50 → **55 条**，新增 5 条：

```
PASS  Debug 配置也强制 -O3
PASS  给 ggml 每个 target 追加 -O3
PASS  gradle 显式传 Release
PASS  运行时自报是否带 -O 优化
PASS  关于页构建时间固定北京时间
```
