#!/usr/bin/env bash
# 番茄畅听净化模块 —— 宿主机本地构建脚本（不需要手机 root，也不用 Termux 工具链）
#
# 为什么需要它：原来只能在手机上用 /data/local/tmp/fuck_andes/tool/TERMUX 那套工具链构建，
# 而那个目录是 root 700 —— adb shell 的 su 授权一旦没批下来就完全没法出包。
# 本脚本改用宿主机已有资源：JDK（自带 javac）+ Gradle 缓存里的 android.jar / apksig / bouncycastle
# + 一份下载好的 r8.jar（d8 就在里面），最后用「替换 classes.dex」的方式复用上一版 APK 的
# 资源与清单，再重新做 v1+v2 签名。
#
# 用法：  bash module/build_local.sh        # 产物 module-local.apk（默认以上一版 fanqie-enhance-*.apk 为模板）
# 产物：  module-local.apk（可直接 adb install -r）
#
# 依赖路径按本机情况用环境变量覆盖（ANDROID_JAR / R8_JAR / APKSIG_JAR / BCPROV_JAR / BCPKIX_JAR / WORK）。
set -e

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-/d/fqbuild}"                                  # 中转目录，建议纯 ASCII 路径
OUT_DIR="${OUT_DIR:-$PROJ}"
WORK_WIN="$(cygpath -m "$WORK" 2>/dev/null || echo "$WORK")"   # 给原生 JDK 用的 Windows 风格路径

PATH_ANDROID_JAR="${ANDROID_JAR:-C:/Users/laogu/.gradle/caches/9.6.1/transforms/89cb022d2c3c3126a818eb517dab7192/transformed/android.jar}"
PATH_R8="${R8_JAR:-$WORK_WIN/r8.jar}"                      # https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar
PATH_APKSIG="${APKSIG_JAR:-C:/Users/laogu/.gradle/caches/modules-2/files-2.1/com.android.tools.build/apksig/9.3.1/2881101f6a9d0baf6a341926ef0ff08c98a5d13b/apksig-9.3.1.jar}"
BCPROV="${BCPROV_JAR:-C:/Users/laogu/.gradle/caches/modules-2/files-2.1/org.bouncycastle/bcprov-jdk18on/1.79/4d8e2732bcee15f1db93df266c3f5b70ce5cac21/bcprov-jdk18on-1.79.jar}"
BCPKIX="${BCPKIX_JAR:-C:/Users/laogu/.gradle/caches/modules-2/files-2.1/org.bouncycastle/bcpkix-jdk18on/1.79/7693cec3b8779b74b35466dcaeeaac7409872954/bcpkix-jdk18on-1.79.jar}"
TEMPLATE_APK="${TEMPLATE_APK:-$PROJ/fanqie-enhance-v1.9.20.apk}"   # 上一版（同一 keystore 签的）模块包
KEYSTORE="${KEYSTORE:-$WORK_WIN/module.keystore}"                      # 手机上那份：/data/local/tmp/fuck_andes/module/module.keystore

mkdir -p "$WORK"
cd "$WORK"
rm -rf src stub_classes classes out unsigned.apk
mkdir -p src stub_classes classes out
cp -r "$PROJ/module/src/de" "$PROJ/module/src/com" src/

W="$WORK_WIN"
AJ="$PATH_ANDROID_JAR"

echo "== [1/5] 编译 Xposed API stub =="
javac -nowarn --release 8 -classpath "$AJ" -d "$W/stub_classes" \
  "$W/src/de/robv/android/xposed/IXposedHookLoadPackage.java" \
  "$W/src/de/robv/android/xposed/XC_MethodHook.java" \
  "$W/src/de/robv/android/xposed/XposedBridge.java" \
  "$W/src/de/robv/android/xposed/XposedHelpers.java" \
  "$W/src/de/robv/android/xposed/callbacks/XC_LoadPackage.java"
(cd stub_classes && jar cf "$W/stub.jar" .)

echo "== [2/5] 编译模块主体 =="
javac -nowarn --release 8 -classpath "$AJ;$W/stub.jar" -d "$W/classes" \
  "$W/src/com/eta/fanqie/enhance/MainHook.java"
(cd classes && jar cf "$W/classes.jar" .)

echo "== [3/5] d8 -> classes.dex =="
java -cp "$PATH_R8" com.android.tools.r8.D8 --lib "$AJ" --min-api 26 \
  --output "$W/out" "$W/classes.jar"
ls -la out/classes.dex

echo "== [4/5] 组装 APK（换 dex + 打补丁版本号，保留模板里的资源） =="
# 注意：这一步在 $PROJ 目录下用「相对路径」调 python —— Windows 版 python 拿到含中文的
#       绝对路径会因控制台编码(936)错乱，相对路径则没有这个问题。
( cd "$PROJ" && python - "$(basename "$TEMPLATE_APK")" "module/AndroidManifest.xml" "$WORK_WIN" <<'PY'
import re, struct, sys, zipfile
tmpl, manifest_src, work = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(manifest_src, encoding="utf-8").read()
vcode = int(re.search(r'versionCode="(\d+)"', src).group(1))
vname = re.search(r'versionName="([^"]+)"', src).group(1)

zin = zipfile.ZipFile(tmpl)
mf = bytes(zin.read("AndroidManifest.xml"))

# 二进制 XML 里 versionName 是 UTF-16LE 的字符串池项：先扫出模板里那个 "x.y.z" 旧版本号。
def find_versions(buf):
    hits, i = [], 0
    while i < len(buf) - 6:
        if buf[i + 1] == 0 and 0x30 <= buf[i] <= 0x39:
            j = i
            chars = []
            while j < len(buf) - 1 and buf[j + 1] == 0 and 0x20 <= buf[j] <= 0x7E:
                chars.append(chr(buf[j])); j += 2
            text = "".join(chars)
            if re.fullmatch(r"\d+\.\d+\.\d+", text):
                hits.append((i, text)); i = j
                continue
        i += 2
    return hits

hits = find_versions(mf)
cand = [h for h in hits if len(h[1]) == len(vname)]
if len(cand) != 1:
    raise SystemExit("模板里定位 versionName 失败: %r" % (hits,))
off, old_text = cand[0]
mf = mf[:off] + vname.encode("utf-16-le") + mf[off + len(old_text) * 2:]

# versionCode 是小端 int 属性值：按模板里扫到的旧值替换（拿 hits 数量对它做校验）。
cand_codes = [c for c in range(180000, 210000) if mf.count(struct.pack("<I", c)) == 1]
if not cand_codes:
    raise SystemExit("没找到唯一的旧 versionCode 候选值")
old_code = min(cand_codes, key=lambda c: abs(mf.index(struct.pack("<I", c)) - off))
mf = mf.replace(struct.pack("<I", old_code), struct.pack("<I", vcode))

out = work + "/unsigned.apk"
dex = open(work + "/out/classes.dex", "rb").read()
zout = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)
for info in zin.infolist():
    if info.filename.startswith("META-INF/"):
        continue                       # 丢掉旧签名块，稍后由 apksig 重签
    data = zin.read(info.filename)
    if info.filename == "classes.dex":
        data = dex
    elif info.filename == "AndroidManifest.xml":
        data = mf
    zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    zi.compress_type = info.compress_type
    zi.external_attr = info.external_attr
    zi.internal_attr = info.internal_attr
    zi.create_system = info.create_system
    zout.writestr(zi, data)
zout.close()
print("assembled %s  versionName=%s  versionCode=%d  dex=%d bytes" % (out, vname, vcode, len(dex)))
PY
)
echo "== [5/5] v1+v2 签名 =="
mkdir -p signer
cat > signer/Sign.java <<'JAVA'
import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

public class Sign {
    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(a[0])) { ks.load(in, a[1].toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey(a[2], a[1].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(a[2]);
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                "CERT", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(new File(a[3]))
                .setOutputApk(new File(a[4]))
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build()
                .sign();
        System.out.println("SIGNED -> " + a[4]);
    }
}
JAVA
javac -nowarn -cp "$PATH_APKSIG;$BCPROV;$BCPKIX" -d "$W/signer" "$W/signer/Sign.java"
java -cp "$W/signer;$PATH_APKSIG;$BCPROV;$BCPKIX" Sign "$KEYSTORE" android module \
  "$W/unsigned.apk" "$W/module.apk"

cp "$WORK/module.apk" "$OUT_DIR/module-local.apk"
echo "构建完成: $OUT_DIR/module-local.apk"
