#!/usr/bin/env bash
# scripts/qa/lib/deps.sh —— harness 依赖构件解析（W-40）
#
# 背景：早批次脚本把 Gradle 缓存路径**连哈希一起写死**（例如
# `.../kotlin-compiler-embeddable/1.9.25/6e698f23ca.../xxx.jar`）。Gradle 版本一升、
# 缓存一清，整片脚本就报「找不到构件」，看起来像代码坏了，实际只是路径过期。
#
# 本文件只做一件事：按「组织路径 + 构件名通配」去找 jar，找不到就回落到本机 kotlinc 发行版。
# 用法（脚本里 source 之后直接调用 qa_find_jar）：
#
#   source "$ROOT/scripts/qa/lib/deps.sh"
#   KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
#   [ -n "$KOTLINC" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
#
# 约定：解析不到时返回空串，由调用方决定回落还是跳过——**绝不返回一个假路径假装存在**。

# Gradle 缓存根：优先 GRADLE_USER_HOME（CI 上是 /home/runner/.gradle），
# 其次 ~/.gradle，最后才回落到 /root/.gradle（沙箱）。
# 之前写死 /root/.gradle，导致 CI 里所有构件都解析不到、整片假红。
if [ -z "${QA_GRADLE_CACHE:-}" ]; then
    QA_GRADLE_CACHE="${AGENTKIT_GRADLE_CACHE:-}"
fi
if [ -z "${QA_GRADLE_CACHE:-}" ]; then
    _cand="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
    if [ -d "$_cand" ]; then
        QA_GRADLE_CACHE="$_cand"
    elif [ -d /root/.gradle/caches/modules-2/files-2.1 ]; then
        QA_GRADLE_CACHE=/root/.gradle/caches/modules-2/files-2.1
    else
        QA_GRADLE_CACHE="$_cand"
    fi
fi
export QA_GRADLE_CACHE
KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}

# qa_find_jar <组织路径目录> <构件通配>
# 输出：首个匹配的 jar 绝对路径；找不到输出空串（调用方可用 [ -n ] 判断）
qa_find_jar() {
    local dir="$1" pattern="$2"
    [ -d "$dir" ] || return 0
    find "$dir" -type f -name "$pattern" 2>/dev/null | grep -v -- '-sources' | sort | head -1
}

# qa_require_project <仓库根>：确认关键源目录存在（脚本从任意 cwd 调用都不会跑错地方）
qa_require_project() {
    local root="$1"
    [ -d "$root/app/src/main/java" ] || {
        echo "FATAL: $root 不像 AgentKit 仓库根目录（缺 app/src/main/java）" >&2
        exit 2
    }
}
