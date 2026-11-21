#!/usr/bin/env bash
# Builds videomemories.apk without Gradle (same no-Gradle pipeline as the
# sibling camera apps). Requires: JDK 17+, ANDROID_HOME with
# platforms/android-34 and build-tools;34.0.0. An NDK (r21 works) builds
# the native converter; without it the APK still builds and falls back to
# the slow Java converter.
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR"; exit 1; }

SRC=app/src/main/java
MANIFEST=app/src/main/AndroidManifest.xml
RES=app/src/main/res
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "[1/6] aapt2 compile + link (resources + manifest)"
VER=$(grep -o 'android:versionCode="[0-9]*"' "$MANIFEST" | head -1 | grep -o '[0-9]*')
"$BT/aapt2" compile --dir "$RES" -o "$OUT/res.zip"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    --min-sdk-version 16 \
    --target-sdk-version 22 \
    --auto-add-overlay \
    "$OUT/res.zip"

echo "[2/6] javac"
find "$SRC" -name '*.java' > "$OUT/sources.txt"
javac \
    -source 8 -target 8 \
    -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" \
    -nowarn \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "[3/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" \
    --release \
    --min-api 16 \
    --lib "$ANDROID_JAR" \
    --output "$OUT" \
    $(cat "$OUT/classes.txt")

echo "[4/6] add classes.dex"
(cd "$OUT" && zip -q base.apk classes.dex)

echo "[4b/6] native YUV converter (skipped, with Java fallback, if no NDK)"
NDK_DIR="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK_DIR" ]; then
    NDK_DIR=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | head -1 || true)
fi
CLANG="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi16-clang"
if [ -n "$NDK_DIR" ] && [ -x "$CLANG" ]; then
    mkdir -p "$OUT/lib/armeabi-v7a"
    "$CLANG" -O2 -fPIC -shared \
        -o "$OUT/lib/armeabi-v7a/libvideomemories.so" \
        app/src/main/jni/convert.c
    (cd "$OUT" && zip -q base.apk lib/armeabi-v7a/libvideomemories.so)
    echo "   lib/armeabi-v7a/libvideomemories.so"
else
    echo "   NDK not found - APK will use the Java converter"
fi

echo "[5/6] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
# versioned name: a browser may serve its cached copy of a same-named APK
APK="$OUT/videomemories-v$VER.apk"
if [ ! -f keystore.jks ]; then
    keytool -genkeypair -keystore keystore.jks -storepass android \
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign \
    --ks keystore.jks --ks-pass pass:android --key-pass pass:android \
    --out "$APK" \
    "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$APK" | head -5

echo
echo "APK: $PWD/$APK"
ls -la "$APK"
