#!/bin/bash
# 朱雀解析手动流水线构建（无 Gradle）。签名口令可用环境变量 KS_PASS 覆盖，默认 zhuque；
# 首次构建会在工程根目录生成自用的 keystore.jks（已在 .gitignore 中，切勿提交）。
# 路径按需调整：JDK 21、Android SDK（platforms;android-34、build-tools;36.0.0）、kotlinc。
set -e
cd "$(dirname "$0")"

export JAVA_HOME=$HOME/workspace/jdk-21.0.12.1+1
export PATH=$JAVA_HOME/bin:$PATH
SDK=$HOME/workspace/android-sdk
BT=$SDK/build-tools/36.0.0
ANDROID_JAR=$SDK/platforms/android-34/android.jar

rm -rf build && mkdir -p build/classes build/dex

echo "== 1. aapt2 编译资源并链接 =="
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/app-unsigned.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    -A assets \
    --no-compress \
    --auto-add-overlay

echo "== 2. kotlinc 编译 ASR 桥（AsrBridge.kt） =="
KOTLINC=$HOME/workspace/dev-tools/kotlinc/bin/kotlinc
STDLIB=$HOME/workspace/dev-tools/kotlinc/lib/kotlin-stdlib.jar
$KOTLINC asr/AsrBridge.kt -cp "$ANDROID_JAR:asr/aar/classes.jar" -jvm-target 1.8 -d build/ktclasses

echo "== 2b. javac 编译 =="
javac --release 8 -cp "$ANDROID_JAR:libs/commons-compress.jar:libs/commons-io.jar:libs/xz.jar:asr/aar/classes.jar:build/ktclasses:$STDLIB" -d build/classes \
    src/com/muse/player/MediaItem.java \
    src/com/muse/player/MainActivity.java \
    src/com/muse/player/PlayerActivity.java \
    src/com/muse/player/ImageActivity.java \
    src/com/muse/player/VideoWpService.java \
    src/com/muse/player/MpkgImport.java \
    src/com/muse/player/MediaSink.java \
    src/com/muse/player/DeepScan.java \
    src/com/muse/player/Subtitles.java \
    src/com/muse/player/AsrSubtitle.java \
    src/com/muse/player/PkgImport.java \
    src/com/muse/player/ImportActivity.java

echo "== 3. d8 转 dex（应用类 + 解包库类 + sherpa + kotlin-stdlib） =="
mkdir -p build/libclasses
(cd build/libclasses && unzip -qo ../../libs/commons-compress.jar -x "META-INF/*" && unzip -qo ../../libs/commons-io.jar -x "META-INF/*" && unzip -qo ../../libs/xz.jar -x "META-INF/*" && unzip -qo ../../asr/aar/classes.jar -x "META-INF/*" && unzip -qo "$STDLIB" -x "META-INF/*")
$BT/d8 --lib "$ANDROID_JAR" --output build/dex $(find build/classes build/libclasses build/ktclasses -name "*.class")

echo "== 4. 塞入 dex =="
(cd build/dex && zip -q ../app-unsigned.apk classes.dex)

echo "== 4b. 塞入原生库（sherpa JNI + onnxruntime，arm64） =="
mkdir -p build/nativelib/lib/arm64-v8a
cp asr/aar/jni/arm64-v8a/libsherpa-onnx-jni.so asr/aar/jni/arm64-v8a/libonnxruntime.so build/nativelib/lib/arm64-v8a/
(cd build/nativelib && zip -q -r ../app-unsigned.apk lib)

echo "== 5. 对齐 + 签名 =="
$BT/zipalign -f 4 build/app-unsigned.apk build/app-aligned.apk
if [ ! -f keystore.jks ]; then
    keytool -genkeypair -keystore keystore.jks -alias zhuque \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "${KS_PASS:-zhuque}" -keypass "${KS_PASS:-zhuque}" -dname "CN=Zhuque Player, O=Open Source, C=CN" 2>/dev/null
fi
$BT/apksigner sign --ks keystore.jks --ks-pass "pass:${KS_PASS:-zhuque}" --key-pass "pass:${KS_PASS:-zhuque}" \
    --out build/muse-player.apk build/app-aligned.apk

echo "== 6. 验收 =="
$BT/apksigner verify --print-certs build/muse-player.apk | head -3
$BT/aapt2 dump badging build/muse-player.apk | head -4
ls -la build/muse-player.apk
echo "BUILD OK"
