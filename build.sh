#!/bin/bash
# 构建 Balatro Mods 管理器 APK（纯 aapt2 + javac + d8 + apksigner，不需要 Gradle）
#
# 依赖（都可用环境变量覆盖，不设则用下面的默认值）：
#   ANDROID_BUILD_TOOLS  Android SDK build-tools 目录，需含 aapt2 / zipalign / lib/d8.jar / lib/apksigner.jar
#   JAVA_HOME            JDK 17+（javac 与 keytool）
#   ANDROID_JAR          android.jar（API 34 即可）
#   PYTHON               Python 3.8+
set -e
set -o pipefail

BT="${ANDROID_BUILD_TOOLS:-D:/Java/signer/android-14}"
JDK="${JAVA_HOME:-D:/bmmwork/jdk-21.0.3+9}"
AJAR="${ANDROID_JAR:-D:/bmmtmp/android-34/android.jar}"
PY="${PYTHON:-python}"

# 项目根目录跟随脚本位置，clone 到哪都能直接构建。
# Windows 下用 pwd -W 取 D:/foo 形式，因为 aapt2/zipalign 不认 /d/foo 这种路径。
WORK="$(cd "$(dirname "$0")" && { pwd -W 2>/dev/null || pwd; })"
OUT="$WORK/out"

# Windows 下可执行文件带 .exe 后缀，其它平台没有
EXE=""
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac

for f in "$BT/aapt2$EXE" "$BT/zipalign$EXE" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar"; do
  [ -e "$f" ] || { echo "缺少构建工具: $f" >&2; echo "请设置 ANDROID_BUILD_TOOLS 指向 build-tools 目录" >&2; exit 1; }
done
[ -e "$AJAR" ] || { echo "缺少 android.jar: $AJAR" >&2; echo "请设置 ANDROID_JAR" >&2; exit 1; }

cd "$WORK"
rm -rf out
mkdir -p out/classes out/dex

echo "[1/8] aapt2 compile"
"$BT/aapt2$EXE" compile --dir res -o "$OUT/res.zip"

echo "[2/8] aapt2 link"
"$BT/aapt2$EXE" link -o "$OUT/base.apk" -I "$AJAR" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 30 \
  --version-code 1 --version-name 1.0 \
  -A assets \
  "$OUT/res.zip"
ls -l "$OUT/base.apk"

echo "[3/8] javac"
find java -name '*.java' > out/srcs.txt
"$JDK/bin/javac" -J-Duser.language=en -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$AJAR" -classpath "$AJAR" \
  -d out/classes @out/srcs.txt
echo "class 数量: $(find out/classes -name '*.class' | wc -l)"

echo "[3b/8] 剥离 MethodParameters（否则 d8 会 NPE）"
"$PY" "$WORK/strip_mp.py" out/classes

echo "[4/8] d8"
CLASSES=$(find out/classes -name '*.class' | while read -r f; do cygpath -m "$f"; done | tr '\n' ' ')
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$AJAR" --min-api 21 --output "$OUT/dex" $CLASSES
ls -l out/dex/

echo "[5/8] 写入 classes.dex"
"$PY" - <<'PYEOF'
import zipfile
apk = r'D:/mmwork/out/base.apk'
dex = r'D:/mmwork/out/dex/classes.dex'
z = zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED)
z.write(dex, 'classes.dex')
z.close()
print('entries:', zipfile.ZipFile(apk).namelist())
PYEOF

echo "[6/8] zipalign"
"$BT/zipalign$EXE" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[7/8] 签名"
if [ ! -f "$WORK/mm.keystore" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$WORK/mm.keystore" -alias mm \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass mm123456 -keypass mm123456 \
    -dname "CN=Balatro Mods,O=Dev,C=CN" 2>&1 | tail -2
fi
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$WORK/mm.keystore" --ks-pass pass:mm123456 --key-pass pass:mm123456 \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/BalatroMods.apk" "$OUT/aligned.apk"

echo "[8/8] 校验"
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT/BalatroMods.apk" | head -6
ls -lh "$OUT/BalatroMods.apk"
echo "=== 构建完成 ==="
