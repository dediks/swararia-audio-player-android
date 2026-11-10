#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="$ROOT/.toolchain/jdk"
export ANDROID_HOME="$ROOT/.toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ROOT/.cache/android"
export GRADLE_USER_HOME="$ROOT/.cache/gradle"
export TMPDIR="$ROOT/.cache/tmp"
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR"
cd "$ROOT"
exec "$ROOT/.toolchain/gradle/bin/gradle" --no-daemon --max-workers=1 --console=plain "$@"
