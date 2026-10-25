plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * 本地构建（无 GITHUB_RUN_NUMBER 环境变量）时的 versionCode 回退值。
 * 必须不小于项目历史最大 versionCode，防止本地包装机后因版本回退被系统拒绝覆盖安装。
 */
val LOCAL_FALLBACK_VERSION_CODE = 14

android {
    namespace = "com.example.agentkit"
    compileSdk = 34

    signingConfigs {
        // 仓库内共享的固定 debug 签名：本地与 CI 用同一把钥匙，
        // 保证不同机器/不同次构建产出的 APK 签名一致，可互相覆盖安装。
        // 注意：这是 Android 官方标准 debug 证书（密码公开为 android），仅用于调试，不是发布签名。
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // 仅本地 UI 验证用：`-PnoNative=true` 跳过 llama.cpp/终端 JNI 的 NDK 编译。
    // 产出的 APK 没有 .so，本地推理与内置终端不可用（两处都是懒加载 + try/catch 降级），
    // 其余界面/流程与正式包一致——用来在 x86_64 模拟器上跑 UI 验证，CI 与正式包不受影响。
    val skipNative = (project.findProperty("noNative") as String?)?.toBoolean() == true

    defaultConfig {
        applicationId = "com.example.agentkit"
        minSdk = 26
        // 28 而不是 34：Android 10+ 对 targetSdk>=29 的应用禁止从**应用数据目录**
        // 调用 execve（W^X）。容器里的所有程序（busybox、之后装的 node/python）
        // 都落在 filesDir，因此 proot 一旦进 guest 就必然失败——
        // 表现为 error=13 / execve("/bin/sh")，且无法通过改代码绕开。
        // Termux 至今保持 targetSdk 28，正是同一原因。
        // 代价：存储/通知/包可见性回退到旧模式（本项目不依赖 29+ 的新约束）。
        targetSdk = 28
        // 版本号每次构建自增：CI 上用 GITHUB_RUN_NUMBER（每次运行单调递增），
        // 本地构建回退到 LOCAL_FALLBACK_VERSION_CODE 常量。
        // 取 coerceAtLeast 兜底，确保 versionCode 永不小于历史值，否则 Android 拒绝覆盖安装。
        val ciVersionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: LOCAL_FALLBACK_VERSION_CODE
        versionCode = ciVersionCode.coerceAtLeast(LOCAL_FALLBACK_VERSION_CODE)
        versionName = "1.$ciVersionCode"
        // 本地 llama.cpp 推理只编 arm64-v8a：先覆盖全部真机，模拟器 x86_64 后续再补
        if (!skipNative) {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        // 不要动 release（本项目不产出 release 包）
    }

    // 仅本地/模拟器调试：`-Px86Rootfs=true` 时额外打进 x86_64 的 Alpine rootfs。
    // 为什么需要它：内置 rootfs 是 aarch64，x86_64 模拟器上 proot 起不来，
    // 容器相关链路（装运行时、跑命令）在服务器上就一直验证不了。
    // 该开关只在本地构建时用，CI 与正式包不带，避免白涨 ~8MB。
    if (project.hasProperty("x86Rootfs")) {
        sourceSets.getByName("debug") {
            assets.srcDir("src/debugX86/assets")
            jniLibs.srcDir("src/debugX86/jniLibs")
        }
    }

    // W-37：Shizuku UserService 走 AIDL 跨进程接口（app/src/main/aidl），显式开启
    buildFeatures {
        compose = true
        aidl = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }

    // proot 以 libproot.so 的形式打进 jniLibs，运行时从 nativeLibraryDir 执行。
    // 必须开 useLegacyPackaging：默认（minSdk>=23）native 库**不解压**，直接从 APK 里 mmap，
    // 那样就没有可 execve 的文件路径，proot 会报 error=13。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // 本地 llama.cpp 推理：只出 arm64-v8a（市面手机全覆盖，先做扎实再扩 x86_64 模拟器）
    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // W-13 B 方案：Shizuku 提权通道（用户需安装 Shizuku 并授权一次）
    implementation("dev.rikka.shizuku:api:13.1.5")
    // W-17：定时任务调度（WorkManager 周期最长 15 分钟，与我们的最小间隔一致）
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Skill 脚本执行引擎（纯 Java，无需 NDK）；将来要换 QuickJS 只需替换 JsEngine 实现
    implementation("org.mozilla:rhino-runtime:1.7.14")
    implementation("androidx.core:core-ktx:1.13.1")
    // W-24：离线 OCR（中文 + 英文混排，模型打进 APK，不依赖 Google Play 服务/GMS）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
