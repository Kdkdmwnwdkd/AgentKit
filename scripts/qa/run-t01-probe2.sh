#!/usr/bin/env bash
# Phase1 T01 Round2 QA 对抗性探针（Edward）：编译 + 运行（离线，仅用本地/缓存构件）
set -euo pipefail

# W-39：仓库根目录由脚本位置推导，不再硬编码 /workspace/AgentKit
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

CACHE=/root/.gradle/caches/modules-2/files-2.1
KOTLINC=$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.25/6e698f23ca1f74d6672827f61e92c2fe015d1b88/kotlin-compiler-embeddable-1.9.25.jar
STDLIB=$CACHE/org.jetbrains.kotlin/kotlin-stdlib/1.9.25/f700a2f2b8f0d6d0fde48f56d894dc722fb029d7/kotlin-stdlib-1.9.25.jar
COROUTINES=$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar
SERIALIZATION=$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.6.3/2241746853abf04073e3ab0dcd9e6729d363b313/kotlinx-serialization-json-jvm-1.6.3.jar
SER_CORE=$(find $CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm -name "kotlinx-serialization-core-jvm-*.jar" | grep -v sources | head -1)
TROVE=$CACHE/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
# JB_ANNOTATIONS 用 QA 本地稳定副本（scripts/qa/lib/）：kotlinc 1.9.25 后端为（平台）可空
# 参数生成空值注解时要加载 org.jetbrains.annotations.NotNull；QA 实测直接引用 Gradle 缓存
# 里的副本会被并发 Gradle 进程瞬时扰动（classpath entry points to a non-existent location
# warning + 偶发 NoClassDefFoundError 崩在 IR lowering，非确定性），故固化本地副本。
JB_ANNOTATIONS=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/23.0.0/8cc20c07506ec18e0834947b84a864bfc094484e/annotations-23.0.0.jar
# 内存脚本化 SSE 响应（应用层拦截器短路）：OkHttp + Okio，均来自 Gradle 离线缓存
OKHTTP=$CACHE/com.squareup.okhttp3/okhttp/4.12.0/2f4525d4a200e97e1b87449c2cd9bd2e25b7e8cd/okhttp-4.12.0.jar
OKIO=$CACHE/com.squareup.okio/okio-jvm/3.6.0/5600569133b7bdefe1daf9ec7f4abeb6d13e1786/okio-jvm-3.6.0.jar

CLASSES=${AGENTKIT_CLASSES:-$ROOT/app/build/tmp/kotlin-classes/debug}
# W-39：集成型 harness 需要 AGP 编译产物。缺失时明确跳过（exit 3），
# 绝不把「环境不具备」伪装成「验证失败」，也不伪装成通过。
if [ ! -d "$CLASSES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺少 Gradle 编译产物：$CLASSES" >&2
  echo "  先执行 ./gradlew :app:compileDebugKotlin，或用 AGENTKIT_CLASSES 指向已有目录" >&2
  exit 3
fi

OUT=/tmp/qa-verify-t01-probe2
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE:$OKHTTP:$OKIO:$JB_ANNOTATIONS

rm -rf "$OUT" && mkdir -p "$OUT"

# 编译带分类重试：后端注解解析竞态（BackendException/NoClassDefFoundError）可重试；
# 前端 error: 是真实代码错误，立即失败不重试。
compile_probe2() {
  local attempt=$1 out
  out=$(java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -Xfriend-paths="$CLASSES" \
    -classpath "$DEPS" \
    -d "$OUT" $ROOT/scripts/qa/T01AdversarialProbe2.kt 2>&1) || true
  if echo "$out" | grep -q "error:"; then
    echo "$out"; return 1
  fi
  if echo "$out" | grep -q "BackendException\|NoClassDefFoundError"; then
    echo "  (compile attempt $attempt hit backend annotation race, retrying ...)" >&2
    return 2
  fi
  echo "$out" | grep -v "^warning:" || true
  return 0
}

echo "[1/2] compiling probe2 harness with kotlin-compiler-embeddable ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e
  compile_probe2 "$attempt"
  crc=$?
  set -e
  if [ "$crc" -eq 0 ]; then rc=0; break; fi
  if [ "$crc" -eq 1 ]; then exit 1; fi
  rm -rf "$OUT" && mkdir -p "$OUT"
done
if [ $rc -ne 0 ]; then
  echo "FATAL: probe2 compile keeps hitting backend annotation race after 5 attempts" >&2
  exit 1
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  T01AdversarialProbe2Kt
