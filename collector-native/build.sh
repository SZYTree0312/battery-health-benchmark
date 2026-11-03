#!/usr/bin/env bash
# 交叉编译 bhbench 到 Android。
#
#   ./build.sh                 默认 aarch64（arm64-v8a），API 26
#   ./build.sh arm             armeabi-v7a，给老机器
#   ./build.sh x64             模拟器
#
# NDK 找不到时用环境变量指定：
#   ANDROID_NDK_HOME=/path/to/ndk ./build.sh

set -euo pipefail

cd "$(dirname "$0")"

ABI="${1:-arm64}"
MIN_API="${MIN_API:-26}"

case "$ABI" in
  arm64) TARGET="aarch64-linux-android${MIN_API}" ;;
  arm)   TARGET="armv7a-linux-androideabi${MIN_API}" ;;
  x64)   TARGET="x86_64-linux-android${MIN_API}" ;;
  *) echo "未知 ABI: $ABI（可选 arm64 / arm / x64）" >&2; exit 2 ;;
esac

find_ndk() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    echo "$ANDROID_NDK_HOME"; return
  fi
  local bases=(
    "${ANDROID_HOME:-}/ndk"
    "${ANDROID_SDK_ROOT:-}/ndk"
    "$HOME/Android/Sdk/ndk"
    "$HOME/Library/Android/sdk/ndk"
    "/c/Users/${USER:-$USERNAME}/AppData/Local/Android/Sdk/ndk"
  )
  for base in "${bases[@]}"; do
    [ -d "$base" ] || continue
    # 取版本号最大的那个
    local latest
    latest=$(ls -d "$base"/* 2>/dev/null | sort -V | tail -1 || true)
    if [ -n "$latest" ]; then echo "$latest"; return; fi
  done
}

NDK="$(find_ndk || true)"
if [ -z "$NDK" ]; then
  cat >&2 <<'EOF'
找不到 Android NDK。

装一个（任选）：
  * Android Studio → SDK Manager → NDK (Side by side)
  * 命令行：sdkmanager "ndk;26.3.11579264"

然后重跑，或显式指定：
  ANDROID_NDK_HOME=/path/to/ndk ./build.sh
EOF
  exit 1
fi

case "$(uname -s)" in
  Darwin) HOST="darwin-x86_64" ;;
  Linux)  HOST="linux-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST="windows-x86_64" ;;
  *) echo "不认识的主机平台: $(uname -s)" >&2; exit 2 ;;
esac

CXX="$NDK/toolchains/llvm/prebuilt/$HOST/bin/${TARGET}-clang++"
if [ ! -x "$CXX" ]; then
  echo "找不到编译器: $CXX" >&2
  echo "检查 NDK 版本是否包含 $HOST 工具链" >&2
  exit 1
fi

echo "NDK   : $NDK"
echo "目标  : $TARGET"
echo "编译器: $CXX"

"$CXX" -std=c++17 -O2 -Wall -Wextra \
  -fPIE -pie -static \
  -o bhbench bhbench.cpp

SIZE=$(wc -c < bhbench | tr -d ' ')
echo "完成: collector-native/bhbench (${SIZE} 字节)"
echo
echo "下一步："
echo "  python runner.py probe"
