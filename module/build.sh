#!/system/bin/sh
# 番茄畅听净化模块构建脚本 v2（stub 仅编译参考，不打包）
set -e
TOOL=/data/local/tmp/fuck_andes/tool/TERMUX
BASE=/data/local/tmp/fuck_andes/module
ANDROID_JAR=/data/local/tmp/fuck_andes/tool/android.jar
export LD_LIBRARY_PATH=$TOOL/lib
export JAVA_HOME=$TOOL/lib/jvm/java-17-openjdk
JAVA=$JAVA_HOME/bin/java
JAVAC=$JAVA_HOME/bin/javac

cd $BASE
rm -rf classes out stub_classes
mkdir -p classes out stub_classes

echo "== [1/6] 编译 Xposed API stub（仅编译参考） =="
$JAVAC -source 8 -target 8 -bootclasspath $ANDROID_JAR -d stub_classes \
  src/de/robv/android/xposed/*.java \
  src/de/robv/android/xposed/callbacks/*.java
$JAVA_HOME/bin/jar cf out/stub.jar -C stub_classes .
echo "stub.jar 已生成: $(find stub_classes -name '*.class' | wc -l) 个类"

echo "== [2/6] 编译模块主体 =="
$JAVAC -source 8 -target 8 -bootclasspath $ANDROID_JAR -classpath out/stub.jar -d classes \
  src/com/eta/fanqie/enhance/MainHook.java
echo "编译完成: $(find classes -name '*.class' | wc -l) 个 class"

echo "== [3/6] d8 -> classes.dex =="
cd classes
$JAVA_HOME/bin/jar cf ../out/classes.jar .
cd ..
$JAVA -cp $TOOL/share/java/d8.jar com.android.tools.r8.D8 \
  --lib $ANDROID_JAR --min-api 26 --output out out/classes.jar
echo "=== 校验 classes.dex 是否含 Xposed API 类（应为 0） ==="
$TOOL/bin/dexdump -f out/classes.dex 2>/dev/null | grep -c "de.robv.android.xposed" || echo "0 (无 Xposed 内置类，正确)"

echo "== [4/6] aapt2 link =="
$TOOL/bin/aapt2 link -o out/module-unsigned.apk \
  -I $ANDROID_JAR \
  --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 19600 --version-name 1.9.5-adfix

echo "== [5/6] 加入 classes.dex 与 xposed_init =="
mkdir -p assets
echo "com.eta.fanqie.enhance.MainHook" > assets/xposed_init
cd out
mkdir -p assets
cp ../assets/xposed_init assets/
$JAVA_HOME/bin/jar uf module-unsigned.apk classes.dex
$JAVA_HOME/bin/jar uf module-unsigned.apk assets/xposed_init

echo "== [6/6] 签名 =="
if [ ! -f "$BASE/module.keystore" ]; then
  $JAVA_HOME/bin/keytool -genkeypair -v -keystore $BASE/module.keystore \
    -alias module -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=FanqieEnhance,OU=Eta,O=Eta,L=Beijing,ST=Beijing,C=CN" \
    > /dev/null 2>&1
fi
$JAVA -jar $TOOL/share/java/apksigner.jar sign \
  --ks $BASE/module.keystore --ks-pass pass:android --key-pass pass:android \
  --out module.apk module-unsigned.apk
echo "构建完成:"
ls -la module.apk
