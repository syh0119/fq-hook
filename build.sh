#!/usr/bin/env bash
# 番茄Hook LSPosed 模块 —— 无 Gradle 手动构建 (aapt2 + javac + d8 + zipalign + apksigner)
set -e

ROOT="D:/WorkBuddy/2026-10-09-18-38-30/lsp"
TOOLS="D:/WorkBuddy/2026-10-09-14-12-52/build/tools"
SDK="$TOOLS/sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
APP="$ROOT/app"
LIB="$ROOT/lib"
OUT="$ROOT/out"
KS="D:/WorkBuddy/2026-10-09-14-12-52/build/debug.keystore"

export JAVA_HOME="$(cygpath -w "$TOOLS/jdk" 2>/dev/null || echo "$TOOLS/jdk")"
JAVAC="$TOOLS/jdk/bin/javac.exe"
KEYTOOL="$TOOLS/jdk/bin/keytool.exe"
AAPT2="$BT/aapt2.exe"
D8="$BT/d8.bat"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"

MIN_SDK=29
TGT_SDK=34
VER_CODE=10
VER_NAME=1.6.2

echo "==================================================="
echo " 构建 番茄Hook (LSPosed 模块)  v$VER_NAME"
echo "==================================================="

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/7] aapt2 compile 资源…"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/7] aapt2 link（含 assets/xposed_init）…"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TGT_SDK" \
  --version-code "$VER_CODE" \
  --version-name "$VER_NAME" \
  "$OUT/res.zip"

echo "[3/7] javac 编译…"
SRCS=$(find "$APP/src" "$OUT/gen" -name '*.java' 2>/dev/null | tr '\n' ' ')
echo "      源文件: $(echo $SRCS | wc -w) 个"
set +e
"$JAVAC" -encoding UTF-8 -nowarn \
  -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$PLATFORM;$LIB/api-82.jar" \
  -d "$OUT/classes" \
  $SRCS > "$OUT/javac.log" 2>&1
RC=$?
set -e
grep -a -v "bootstrap class path\|source value 8\|target value 8\|deprecat\|warning" "$OUT/javac.log" | head -40 || true
if [ $RC -ne 0 ]; then echo "!! javac 失败 (rc=$RC)"; cat "$OUT/javac.log"; exit 1; fi
echo "      javac OK"

echo "[4/7] d8 转 dex（仅模块自有类，Xposed API 只做编译期依赖）…"
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
echo "      class 数: $(wc -l < "$OUT/classlist.txt")"
set +e
"$D8" --release --min-api "$MIN_SDK" \
  --lib "$PLATFORM" --lib "$LIB/api-82.jar" \
  --output "$OUT/dex" $(cat "$OUT/classlist.txt" | tr '\n' ' ') > "$OUT/d8.log" 2>&1
RC=$?
set -e
tail -20 "$OUT/d8.log"
if [ $RC -ne 0 ]; then echo "!! d8 失败 (rc=$RC)"; exit 1; fi
echo "      d8 OK"

echo "[5/7] 打包 classes.dex 进 APK…"
"C:/Users/Admin/.workbuddy/binaries/python/versions/3.13.12/python.exe" - <<PYEOF
import zipfile, shutil, os
src = r"$OUT/base.apk"
dst = r"$OUT/unsigned.apk"
dex = r"$OUT/dex/classes.dex"
shutil.copy2(src, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("      classes.dex:", os.path.getsize(dex), "bytes")
PYEOF

echo "[6/7] zipalign…"
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[7/7] 签名…"
"$APKSIGNER" sign \
  --ks "$(cygpath -w "$KS" 2>/dev/null || echo "$KS")" \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/fq-hook.apk" "$OUT/aligned.apk"

echo
echo "==================================================="
ls -la "$OUT/fq-hook.apk"
"$APKSIGNER" verify --print-certs "$OUT/fq-hook.apk" 2>&1 | head -4
echo "==================================================="
echo "产物: $OUT/fq-hook.apk"
