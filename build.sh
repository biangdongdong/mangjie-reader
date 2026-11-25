#!/bin/bash
set -e

# 手搓构建：aapt2 → javac → d8 → 打包 → zipalign → apksigner
# 用法：./build.sh              普通版（版本号从 AndroidManifest.xml 的 versionName 取）
#       ./build.sh 1.1.41        普通版，指定版本
#       ./build.sh 1.1.41 --tts  语音版：换清单（com.mangjie.reader.tts）、带原生库与模型
#
# 两个版本共用同一份源码，差别只在：
#   清单 → package/authorities/application label/多一个 TTS 引擎服务
#   资源 → assets/tts 下的模型、lib/arm64-v8a 下的两个 so
# 因此可以同时装在一台机器上（包名不同），也能互相覆盖升级各自的数据。

export JAVA_HOME="$HOME/tools/jdk17"
export ANDROID_HOME="$HOME/tools/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"

BT="$ANDROID_HOME/build-tools/33.0.2"
PLATFORM="$ANDROID_HOME/platforms/android-33/android.jar"
FRAMEWORK="$ANDROID_HOME/platforms/android-33/framework.aidl"
KS="keystore.jks"
ALIAS="s918"
PASS="s918demo"

TTS=0
VER=""
for a in "$@"; do
  case "$a" in
    --tts) TTS=1 ;;
    *)     VER="$a" ;;
  esac
done

if [ -z "$VER" ]; then
  VER=$(grep -o 'android:versionName="[^"]*"' AndroidManifest.xml | head -1 | sed 's/.*="//; s/"//')
fi
mkdir -p dist

if [ "$TTS" = "1" ]; then
  MAN="AndroidManifest-tts.xml"
  OUT="dist/盲界阅读-demo-${VER}-tts.apk"
  LINK_EXTRA="--custom-package com.mangjie.reader"
  ASSETS_ARG="-A build/assets"
  echo "== 语音版：$MAN → $OUT"
else
  MAN="AndroidManifest.xml"
  OUT="dist/盲界阅读-demo-${VER}.apk"
  LINK_EXTRA=""
  ASSETS_ARG=""
  echo "== 普通版：$MAN → $OUT"
fi

echo "[1/6] 清理"
rm -rf build
mkdir -p build/res build/gen build/classes build/dex

if [ "$TTS" = "1" ]; then
  # 模型与 so 放在工程里，不进 git 也无所谓，构建前就地取用
  if [ ! -f 语音库/model-steps-3.onnx ] || [ ! -f 语音库/vocos.onnx ]; then
    echo "!! 缺模型：语音库/model-steps-3.onnx 或 语音库/vocos.onnx"
    exit 1
  fi
  if [ ! -f libs-jni/arm64-v8a/libonnxruntime.so ] || [ ! -f libs-jni/arm64-v8a/libsherpa-onnx-jni.so ]; then
    echo "!! 缺原生库：libs-jni/arm64-v8a/"
    exit 1
  fi
  mkdir -p build/assets/tts build/lib/arm64-v8a
  cp -f 语音库/model-steps-3.onnx 语音库/vocos.onnx 语音库/tokens.txt 语音库/lexicon.txt \
        语音库/date.fst 语音库/number.fst 语音库/phone.fst build/assets/tts/
  cp -f libs-jni/arm64-v8a/libonnxruntime.so libs-jni/arm64-v8a/libsherpa-onnx-jni.so build/lib/arm64-v8a/
  echo "   模型 $(du -sh build/assets/tts | cut -f1)，原生库 $(du -sh build/lib | cut -f1)"
fi

echo "[2/6] 链接资源与清单"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" \
  --manifest "$MAN" \
  -R build/res.zip --auto-add-overlay \
  $LINK_EXTRA $ASSETS_ARG \
  --java build/gen \
  --min-sdk-version 24 --target-sdk-version 33

echo "[3/6] 编译 Java"
# AIDL：点明语音服务接口（com.dianming.phoneapp.ISpeakService），生成的 java 与 R.java 一起进 javac
if [ -f src/com/dianming/phoneapp/ISpeakService.aidl ]; then
  for f in src/com/dianming/phoneapp/*.aidl; do
    "$BT/aidl" -p"$FRAMEWORK" -Isrc -obuild/gen "$f" || { echo "!! AIDL 编译失败：$f"; exit 1; }
  done
  echo "  AIDL 已生成：$(find build/gen/com/dianming -name '*.java' | wc -l) 个接口"
fi
SRCS=$(find src build/gen -name '*.java')
set +e
"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM" -classpath "$PLATFORM:libs/*" \
  -d build/classes $SRCS 2>&1 | grep -v "^Note:"
JAVAC_RC=${PIPESTATUS[0]}
set -e

if [ "$JAVAC_RC" != "0" ] || [ ! -d build/classes/com ]; then
  echo "!! 编译失败（javac 退出码 $JAVAC_RC）"
  exit 1
fi

echo "[4/6] d8 转 dex"
"$BT/d8" --lib "$PLATFORM" --min-api 24 --output build/dex \
  $(find build/classes -name '*.class') libs/*.jar

echo "[5/6] 合并 dex 进 APK"
cp build/base.apk build/merged.apk
# 方法数可能被拆成 classes.dex / classes2.dex，全部要进包
(cd build/dex && zip -q ../merged.apk $(ls *.dex))
# d8 只转 .class，jar 里的 java 资源会被丢掉；Rhino 的 Messages.properties 必须留在 APK 里，
# 否则它抛错误时找不到 bundle，所有 @js 规则书源全部失效
for j in libs/*.jar; do
  unzip -o -q "$j" 'org/mozilla/javascript/resources/*' -d build/dex 2>/dev/null || true
done
if [ -d build/dex/org/mozilla/javascript/resources ]; then
  (cd build/dex && zip -q ../merged.apk org/mozilla/javascript/resources/*)
fi

if [ "$TTS" = "1" ]; then
  # so 不压缩进包，zipalign 后才能被加载器直接映射
  (cd build && zip -q -0 merged.apk lib/arm64-v8a/libonnxruntime.so lib/arm64-v8a/libsherpa-onnx-jni.so)
fi

echo "[6/6] 对齐与签名"
# -p：按 4KB 页对齐未压缩文件（.so 只在页对齐时才能被系统直接 mmap 加载；
#     只做 4 字节对齐时，Android 12+ 部分机器会加载失败）。没有 so 的普通版也安全。
"$BT/zipalign" -f -p 4 build/merged.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --ks-key-alias "$ALIAS" --out "$OUT" build/aligned.apk
"$BT/apksigner" verify "$OUT"
ls -l "$OUT"
