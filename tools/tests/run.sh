#!/bin/sh
# 在电脑上跑纯逻辑测试：不需要 Android Studio，也不需要连盒子。
#
#   ./tools/tests/run.sh
#
# 这些测试调的都是 App 里的真方法（不复制逻辑），所以只能编译通过就等于
# 「改坏了立刻知道」。需要两样东西：JDK、Android SDK 的 android.jar。
#
# 环境变量都能覆盖：
#   JAVA_HOME   JDK 目录（默认用 PATH 里的 javac/java）
#   ANDROID_SDK_ROOT  SDK 目录（默认依次找 ANDROID_HOME、~/Library/Android/sdk）

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SRC="$ROOT/app/src/main/java"

# 挑一个真能跑的 JDK。
# 不能无脑信 JAVA_HOME：这台机器上它指向一个 x86 的 JDK 8，在 Apple Silicon 上
# 一执行就 "Bad CPU type"——所以每个候选都先 javac -version 试一下。
JAVAC=""
JAVA=""
for home in "${JAVA_HOME:-}" /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/java /usr/local/opt/java; do
    [ -n "$home" ] || continue
    [ -x "$home/bin/javac" ] || continue
    if "$home/bin/javac" -version >/dev/null 2>&1; then
        JAVAC="$home/bin/javac"
        JAVA="$home/bin/java"
        break
    fi
done
if [ -z "$JAVAC" ]; then
    if javac -version >/dev/null 2>&1; then
        JAVAC=javac
        JAVA=java
    else
        echo "找不到能用的 JDK（JAVA_HOME 和 PATH 里的 javac 都跑不起来）。"
        echo "macOS 上装一个 arm64 的：brew install openjdk"
        exit 2
    fi
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
JAR=""
for api in 34 33 30 28; do
    if [ -f "$SDK/platforms/android-$api/android.jar" ]; then
        JAR="$SDK/platforms/android-$api/android.jar"
        break
    fi
done
# 本机没有上面那几个就先找任意一个：CI 的 runner 装哪些平台不受我们控制，
# 为了一个 jar 让整条流水线红掉不值当（jar 只是提供 android.* 的签名，版本新一点不影响）
if [ -z "$JAR" ] && [ -d "$SDK/platforms" ]; then
    JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$JAR" ]; then
    echo "找不到 android.jar。装个 Android SDK platform，或者设 ANDROID_SDK_ROOT 指过去。"
    echo "（找过：$SDK/platforms/android-*/android.jar）"
    exit 2
fi

OUT=$(mktemp -d "${TMPDIR:-/tmp}/hostbox-tests.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

echo "android.jar: $JAR"
echo "输出目录   : $OUT"
echo

if ! "$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 \
        -bootclasspath "$JAR" \
        -sourcepath "$SRC:$HERE/stub" \
        -d "$OUT" "$HERE"/cases/tv/hostbox/app/*.java 2>&1; then
    echo
    echo "编译失败——测试引用了不存在的接口，或者源码本身编不过。"
    exit 1
fi

fail=0
for f in "$HERE"/cases/tv/hostbox/app/*.java; do
    cls=$(basename "$f" .java)
    echo "===== $cls ====="
    # MainActivity 继承 Activity，跑的时候 classpath 里必须有 android.jar
    if ! "$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT:$JAR" "tv.hostbox.app.$cls"; then
        fail=$((fail + 1))
    fi
    echo
done

if [ "$fail" -gt 0 ]; then
    echo "有 $fail 个测试类没过。"
    exit 1
fi
echo "全部测试类通过。"
