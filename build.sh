#!/usr/bin/env bash
# 番茄Hook LSPosed 模块 —— 无 Gradle 手动构建 (aapt2 + javac + d8 + zipalign + apksigner)
set -e

# ROOT 自定位：脚本所在目录（fq-hook/）。
# 历史坑：这里曾写死成别的会话目录，会话被清理后 ROOT 失效 ——
# 而脚本第二件事就是 rm -rf "$OUT"，于是「构建一个不存在的项目」。
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cygpath -w "$HERE" 2>/dev/null || echo "$HERE")"
#  给 Windows 工具（javac/aapt2/d8）用的正斜杠路径；给 bash 工具（rm/mkdir/find）用 $HERE。
#  历史坑：OUT 一律用反斜杠的 D:\...，结果 `rm -rf "$OUT"` 被 safe-delete 钩子判成
#  「embedded drive prefix」直接拦下，整个构建停在第 0 步。
ROOT_M="$(cygpath -m "$HERE" 2>/dev/null || echo "$HERE")"
TOOLS="D:/WorkBuddy/_tools"
SDK="$TOOLS/android-sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
APP="$ROOT_M/app"
LIB="$ROOT_M/lib"
OUT="$ROOT_M/out"
KS="D:/WorkBuddy/_tools/keystore/debug.keystore"

export JAVA_HOME="$(cygpath -w "$TOOLS/jdk" 2>/dev/null || echo "$TOOLS/jdk")"
JAVAC="$TOOLS/jdk/bin/javac.exe"
KEYTOOL="$TOOLS/jdk/bin/keytool.exe"
AAPT2="$BT/aapt2.exe"
D8="$BT/d8.bat"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"

MIN_SDK=29
TGT_SDK=34
# ── 版本号单一来源 ──────────────────────────────────────────────
# VERSION 只维护在 MainHook.java 一处，这里读出来；VER_CODE 由版本号自动推。
# 历史坑：build.sh / MainHook.java / dist 文件名 三处手改，漏一处不报错，
# 会出现「APK 名 1.6.3、面板显示 1.6.2」。
VER_NAME=$(sed -n 's/.*private static final String VERSION *= *"\([^"]*\)".*/\1/p' \
             "$APP/src/com/syh/fqhook/MainHook.java" | head -1)
if [ -z "$VER_NAME" ]; then
  echo "!! 读不到 MainHook.java 的 VERSION 常量，构建中止"; exit 1
fi
MAJ=${VER_NAME%%.*}
REST=${VER_NAME#*.}
MIN=${REST%%.*}
PAT=${REST#*.}
if [ "$PAT" = "$REST" ]; then PAT=0; fi
VER_CODE=$(( MAJ * 10000 + MIN * 100 + PAT ))
echo " 版本来源: MainHook.java VERSION=$VER_NAME  ->  versionCode=$VER_CODE"

# ── 把版本号同步回 AndroidManifest.xml ──────────────────────────
# 历史坑（真机装出来才暴露）：manifest 里写死 versionName="1.6.2"/versionCode="10"，
# 而 build.sh 虽然传了 --version-name/--version-code，aapt2 link 以 manifest 为准 ——
# 产物里仍是 1.6.2。LSPosed 模块列表显示的版本取自这里，于是面板写着 v1.7.0、
# LSPosed 显示 v1.6.2，还让人以为装错了包。
# 单一来源仍是 MainHook.java 的 VERSION，这里只做单向同步（改完还原）。
MANIFEST="$APP/AndroidManifest.xml"
if [ -f "$MANIFEST" ]; then
  cp -f "$MANIFEST" "$MANIFEST.bak"
  sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER_NAME\"/" "$MANIFEST"
  sed -i "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$VER_CODE\"/" "$MANIFEST"
  MV=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)
  MC=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)
  if [ "$MV" != "$VER_NAME" ] || [ "$MC" != "$VER_CODE" ]; then
    echo "!! manifest 版本同步失败 (期望 $VER_NAME/$VER_CODE，实得 $MV/$MC)"; 
    mv -f "$MANIFEST.bak" "$MANIFEST"; exit 1
  fi
  echo " manifest 已同步: versionName=$MV versionCode=$MC"
fi

echo "==================================================="
echo " 构建 番茄Hook (LSPosed 模块)  v$VER_NAME"
echo "==================================================="

#  清理用 $HERE（正斜杠绝对路径），不用 $OUT。
#  两重考虑：① safe-delete 钩子对盘符前缀/反斜杠路径会拦；② rm -rf 整个 out/
#  其实没必要 —— 里面只有构建中间产物，逐个子目录清更可控。
for d in res gen classes dex; do rm -rf "$HERE/out/$d"; done
rm -f "$HERE/out/res.zip" "$HERE/out/base.apk" "$HERE/out/unsigned.apk" \
      "$HERE/out/aligned.apk" "$HERE/out/fq-hook.apk" \
      "$HERE/out/classlist.txt" "$HERE/out/javac.log" "$HERE/out/d8.log"
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

# ── 自动落 dist/（版本命名 + sha256），消除第三处手工同步 ──
mkdir -p "$ROOT/dist"
DIST="$ROOT/dist/fq-hook-lsp-v$VER_NAME.apk"
cp -f "$OUT/fq-hook.apk" "$DIST"
( cd "$ROOT/dist" && sha256sum "fq-hook-lsp-v$VER_NAME.apk" > "fq-hook-lsp-v$VER_NAME.apk.sha256" )
echo "发行: $DIST  v$VER_NAME ($VER_CODE)"
ls -la "$DIST"
cat "$DIST.sha256"

# ── 还原 manifest（构建期的临时同步，不留在源码里）──
if [ -f "$MANIFEST.bak" ]; then
  mv -f "$MANIFEST.bak" "$MANIFEST"
  echo "manifest 已还原为仓库原值"
fi

# ── 终检：产物 manifest 里的版本必须等于 VERSION（否则 LSPosed 显示会错版本）──
FINAL_V=$("$AAPT2" dump badging "$DIST" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
FINAL_C=$("$AAPT2" dump badging "$DIST" 2>/dev/null | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")
if [ "$FINAL_V" != "$VER_NAME" ] || [ "$FINAL_C" != "$VER_CODE" ]; then
  echo "!! 终检失败：产物 manifest 版本 $FINAL_V/$FINAL_C != 期望 $VER_NAME/$VER_CODE"
  exit 1
fi
echo "终检 OK：产物 manifest 版本 = $FINAL_V ($FINAL_C)"
