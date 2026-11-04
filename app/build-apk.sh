#!/usr/bin/env bash
# 手工打包 APK：aapt2 → javac → d8 → zipalign → apksigner
#
# 为什么不用 Gradle：
#   1. Gradle + AGP 要拉几百 MB 的 Maven 依赖，网络稍有波动就卡住；
#   2. 这个 App 没有第三方依赖、没有 XML 布局、没有 AndroidX，
#      手工四步就够，而且每一步失败都能一眼看出是哪个环节；
#   3. 构建时间从几分钟压到几秒。
#
# 前置：ANDROID_SDK 指向装了 platforms;android-34 和 build-tools;34.0.0 的 SDK，
#      JAVA_HOME 指向 JDK 17。

set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-D:/android-build-tools/sdk}"
JDK="${JAVA_HOME:-D:/android-build-tools/jdk-17.0.20.1+1}"
BT="$SDK/build-tools/34.0.0"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"

for f in "$BT/aapt2.exe" "$BT/d8.bat" "$BT/zipalign.exe" "$BT/apksigner.bat" "$PLATFORM_JAR"; do
  if [ ! -e "$f" ]; then
    echo "缺少构建组件: $f" >&2
    echo "先用 sdkmanager 安装 platforms;android-34 与 build-tools;34.0.0" >&2
    exit 1
  fi
done

if [ ! -x "$JDK/bin/javac.exe" ]; then
  echo "找不到 JDK: $JDK" >&2
  exit 1
fi

# d8 / apksigner / keytool 这些 .bat 都要靠 JAVA_HOME 找 java
export JAVA_HOME="$JDK"

# Windows 程序只认 Windows 路径，绝对路径统一转换一次
WIN_PLATFORM="$(cygpath -w "$PLATFORM_JAR" 2>/dev/null || echo "$PLATFORM_JAR")"

# 每次构建开一个新目录，而不是先 rm -rf 老目录。
# 原因：Windows 上一些安全软件/文件系统权限会让 rm -rf 直接失败，
# 构建脚本不该因为清不掉旧产物就整条挂掉。build/ 已在 .gitignore，
# 攒够了手动删一次即可（或在 PowerShell 里 Remove-Item）。
OUT="../build/apk-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] 编译资源"
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"

echo "[2/6] 链接资源，生成 R.java"
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$WIN_PLATFORM" \
  --manifest AndroidManifest.xml \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 4 \
  --version-name 0.4.0 \
  --auto-add-overlay

echo "[3/6] 编译 Java"
find java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# 强制英文输出：中文报错在 Git Bash 里会被当成二进制，grep 一过滤就丢了真正的原因
if ! "$JDK/bin/javac.exe" \
  -J-Duser.language=en -J-Duser.country=US \
  -source 8 -target 8 \
  -classpath "$WIN_PLATFORM" \
  -encoding UTF-8 \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  echo "Java 编译失败：" >&2
  cat "$OUT/javac.log" >&2
  exit 1
fi

echo "[4/6] 转 dex"
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
"$BT/d8.bat" --lib "$WIN_PLATFORM" --min-api 26 --output "$OUT/dex" @"$OUT/classlist.txt"

echo "[5/6] 打包 dex"
# 注意：这里用 -c 而不是 heredoc。heredoc 喂 stdin 的写法在本机会让
# shutil.copyfile 的目标变成空 zip（旧的 AndroidManifest.xml 直接丢失，
# apksigner 报 Missing AndroidManifest.xml），-c 传参稳定。
python -c '
import shutil, sys, zipfile
out = sys.argv[1]
shutil.copyfile(out + "/base.apk", out + "/withdex.apk")
with zipfile.ZipFile(out + "/withdex.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(out + "/dex/classes.dex", "classes.dex")
n = len(zipfile.ZipFile(out + "/withdex.apk").namelist())
print("  classes.dex 已写入, 共", n, "项")
' "$OUT"

echo "[6/6] 对齐并签名"
"$BT/zipalign.exe" -f -p 4 "$OUT/withdex.apk" "$OUT/aligned.apk"

KS=debug.keystore
if [ ! -f "$KS" ]; then
  echo "  生成调试签名证书"
  "$JDK/bin/keytool.exe" -genkeypair \
    -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

FINAL=../bhbench.apk
export JAVA_HOME="$JDK"
"$BT/apksigner.bat" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 26 \
  --out "$FINAL" "$OUT/aligned.apk"

"$BT/apksigner.bat" verify --print-certs "$FINAL" | head -4

SIZE=$(wc -c < "$FINAL" | tr -d ' ')
echo
echo "完成：bhbench.apk  (${SIZE} 字节)"
echo "安装： adb install -r bhbench.apk"
