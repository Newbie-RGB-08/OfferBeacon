#!/bin/bash
# OfferBeacon APK 一键构建脚本（无 Gradle，直接用 SDK 构建工具链）
set -e

BASE="D:/OfferBeaconBuild"
SDK="$BASE/sdk"
BT="$SDK/build-tools/34.0.0"
# d8 使用 35.0.0（34.0.0 的 d8 存在内部类崩溃 bug）
D8_DIR="$SDK/build-tools/35.0.0"
[ -d "$D8_DIR" ] || D8_DIR="$BT"
PLAT="$SDK/platforms/android-34/android.jar"
APP="$BASE/app"
BUILD="$BASE/build"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/7] aapt2 compile resources"
"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "[2/7] aapt2 link"
"$BT/aapt2" link -o "$BUILD/base.apk" \
  -I "$PLAT" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  "$BUILD/res.zip"

echo "[3/7] javac"
javac -encoding UTF-8 -source 8 -target 8 \
  -classpath "$PLAT" \
  -d "$BUILD/classes" \
  "$BUILD/gen/com/offerbeacon/app/R.java" \
  "$APP/java/com/offerbeacon/app/MainActivity.java" 2>&1 | grep -v "bootstrap class path" | grep -v "deprecat" || true

echo "[4/7] d8 dex"
python3 - "$BUILD/classes" "$BUILD/classes.jar" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, src).replace('\\', '/'))
print('jarred', out)
PY
"$D8_DIR/d8.bat" --release --lib "$PLAT" --min-api 24 \
  --output "$BUILD/dex" "$BUILD/classes.jar"

echo "[5/7] pack classes.dex"
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('added classes.dex')
PY

echo "[6/7] zipalign"
"$BT/zipalign" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "[7/7] sign"
if [ ! -f "$BASE/offerbeacon.keystore" ]; then
  KEYTOOL="C:/Program Files/Java/latest/jdk-21/bin/keytool.exe"
  [ -f "$KEYTOOL" ] || KEYTOOL="D:/Program Files/Java/jdk-21.0.12.1/bin/keytool.exe"
  "$KEYTOOL" -genkeypair -keystore "$BASE/offerbeacon.keystore" -alias offerbeacon \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass offerbeacon2026 -keypass offerbeacon2026 \
    -dname "CN=OfferBeacon, OU=OfferBeacon, O=OfferBeacon, C=CN"
fi
"$BT/apksigner.bat" sign \
  --ks "$BASE/offerbeacon.keystore" \
  --ks-key-alias offerbeacon \
  --ks-pass pass:offerbeacon2026 \
  --key-pass pass:offerbeacon2026 \
  --out "$BASE/OfferBeacon.apk" \
  "$BUILD/aligned.apk"

echo "=== verify ==="
"$BT/apksigner.bat" verify --print-certs "$BASE/OfferBeacon.apk" | head -6
"$BT/aapt2" dump badging "$BASE/OfferBeacon.apk" | head -8
ls -lh "$BASE/OfferBeacon.apk"
echo "BUILD_OK"
