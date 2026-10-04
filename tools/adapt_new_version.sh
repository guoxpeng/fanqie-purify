#!/usr/bin/env bash
# 番茄畅听净化模块 —— 「一键适配新版本」脚本
#
# 为什么需要它：番茄每出一个小版本，资源 id 与混淆类名都可能变。以前全靠人工
# 比对（aapt dump resources + 手工看 dex），一步漏掉就会「有的地方没效果 / 留空白」。
# 这个脚本把整套动作串起来，并在真正构建之前告诉你「这一版有哪些地方变了、要不要改代码」。
#
# 它做四件事：
#   ① 拉包        —— 从手机上把当前安装的 com.xs.fm 的 base.apk 拷回来（也可用 --apk 指定）
#   ② 资源 id 比对 —— 模块用到的每一个资源名，在新旧版本的 id 数值是否一致
#   ③ 代码目标比对 —— 模块 hook 的类/方法（含混淆名）在新版 dex 里还在不在
#   ④ 构建 + 安装 —— 通过就自动 bump 版本号、构建 module-local.apk、adb install -r
#
# 用法示例：
#   bash tools/adapt_new_version.sh                    # 拉包 + 只做检查报告（不动代码）
#   bash tools/adapt_new_version.sh --build            # 检查 + 自动 bump 版本 + 构建 + 安装
#   bash tools/adapt_new_version.sh --apk fanqie_67232.apk --prev fanqie_67132.apk --build
#   bash tools/adapt_new_version.sh --arm-dump         # 在手机上打开 View 树转储开关（诊断新页面）
#   bash tools/adapt_new_version.sh --pull-tree        # 把手机上的 View 树/转储拉到 artifacts/
#   bash tools/adapt_new_version.sh --pull-tree --clear-dump   # 拉完顺手关掉转储开关
#
# 本脚本依赖：adb（可用 ADB= 指定）、aapt（可用 AAPT= 指定）、python、tools/dexdump.py。
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ"

PKG="com.xs.fm"
ADB="${ADB:-}"
AAPT="${AAPT:-}"
DO_BUILD=0
ARM_DUMP=0
PULL_TREE=0
CLEAR_DUMP=0
NEW_APK=""
PREV_APK=""

while [ $# -gt 0 ]; do
  case "$1" in
    --apk)       NEW_APK="${2:-}"; shift 2 ;;
    --prev)      PREV_APK="${2:-}"; shift 2 ;;
    --adb)       ADB="${2:-}"; shift 2 ;;
    --build)     DO_BUILD=1; shift ;;
    --arm-dump)  ARM_DUMP=1; shift ;;
    --pull-tree) PULL_TREE=1; shift ;;
    --clear-dump) CLEAR_DUMP=1; shift ;;
    -h|--help)   sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数: $1（--help 看用法）" >&2; exit 2 ;;
  esac
done

# ---------- 工具定位 ----------
if [ -z "$ADB" ]; then
  for c in /d/scrcpy/adb.exe /d/绿软/QtScrcpy-win-x64-v3.3.3/adb.exe /d/LDPlayer9/adb.exe "$(command -v adb 2>/dev/null)"; do
    [ -n "$c" ] && [ -x "$c" ] && ADB="$c" && break
  done
fi
if [ -z "$AAPT" ]; then
  for c in /d/LDPlayer9/aapt.exe "$(command -v aapt 2>/dev/null)" \
           "$(ls -1 "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt 2>/dev/null | tail -1)"; do
    [ -n "$c" ] && [ -x "$c" ] && AAPT="$c" && break
  done
fi
PY="${PYTHON:-$(command -v python3 || command -v python)}"
say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
warn() { printf '\033[33m ! %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m ✓ %s\033[0m\n' "$*"; }
bad()  { printf '\033[31m ✗ %s\033[0m\n' "$*"; }

mkdir -p artifacts .buildtmp

# ---------- ① 拉包 ----------
if [ -z "$NEW_APK" ]; then
  say "① 从手机拉取当前安装的 $PKG"
  [ -z "$ADB" ] && { bad "没找到 adb（用 --adb 指定）"; exit 1; }
  "$ADB" devices | grep -qw device || { bad "没有已连接的设备"; exit 1; }
  VER="$("$ADB" shell dumpsys package $PKG 2>/dev/null | grep -m1 versionName | tr -d '\r' | awk -F= '{print $2}')"
  CODE="$("$ADB" shell dumpsys package $PKG 2>/dev/null | grep -m1 versionCode | tr -d '\r' | awk -F= '{print $2}' | awk '{print $1}')"
  echo "  设备上的版本: versionName=$VER versionCode=$CODE"
  # 沿用仓库既有命名：fanqie_<versionCode><versionName 最后一段>.apk，例如 6.7.2.32/672 -> fanqie_67232.apk
  NAME="fanqie_${CODE}$(echo "$VER" | awk -F. '{print $NF}').apk"
  if [ -f "$NAME" ]; then
    ok "本地已有 $NAME，直接复用"
  else
    BASE="$("$ADB" shell pm path $PKG 2>/dev/null | head -1 | sed 's/^package://' | tr -d '\r')"
    echo "  设备上的包路径: $BASE"
    # 注意：MSYS/Git-Bash 会把 /sdcard/x 当成 Windows 路径改写，所以这里绕开它，
    #      直接用 exec-out 把 base.apk 字节流打到本地文件。
    "$ADB" shell "su -c 'cp \"$BASE\" /data/local/tmp/pull.apk && chmod 644 /data/local/tmp/pull.apk'" || true
    "$ADB" exec-out cat /data/local/tmp/pull.apk > "$NAME" || true
    if [ ! -s "$NAME" ]; then
      bad "拉包失败（su 授权？）—— 也可以用 --apk /path/to/base.apk 手动指定"
      rm -f "$NAME"; exit 1
    fi
    ok "已保存 $NAME ($(wc -c < "$NAME") bytes)"
  fi
  NEW_APK="$NAME"
fi
[ -s "$NEW_APK" ] || { bad "APK 不存在或为空: $NEW_APK"; exit 1; }

# ---------- 上一版基准 ----------
if [ -z "$PREV_APK" ]; then
  PREV_APK="$(ls -1t fanqie_*.apk 2>/dev/null | grep -v "^$(basename "$NEW_APK")$" | head -1)"
fi
if [ -z "$PREV_APK" ] || [ ! -s "$PREV_APK" ]; then
  warn "没有找到上一版 APK 作基准（--prev 可指定）；资源 id 只能列出当前值，无法判断「有没有变」"
  PREV_APK=""
fi
echo "  新版本: $NEW_APK"
[ -n "$PREV_APK" ] && echo "  基准版: $PREV_APK"

# ---------- ② 资源 id 比对 ----------
# 资源名清单从 MainHook.java 里自动抽取，避免脚本和代码不同步。
# 抽的是所有 hideByResId / hideByResIdUp / hideMineResIdCollapse 的第一个字符串参数，
# 外加 registerForceHideIds 里那份 names 数组。
extract_res_names() {
  {
    grep -oE '(hideByResId|hideByResIdUp|hideMineResIdCollapse)\(act, "[A-Za-z0-9_]+"' \
      module/src/com/eta/fanqie/enhance/MainHook.java | sed -E 's/.*"([A-Za-z0-9_]+)"/\1/'
    awk '/private void registerForceHideIds/,/^    }/' module/src/com/eta/fanqie/enhance/MainHook.java \
      | grep -oE '^\s*"[A-Za-z0-9_]+"' | tr -d ' "' | sed '/^$/d'
  } | sort -u
}
RES_NAMES="$(extract_res_names)"

dump_ids() {   # $1 = apk, $2 = 输出文件
  [ -z "$AAPT" ] && return 1
  # 注意：aapt 的输出里 resource 行前面有缩进（'    resource 0x... id/xxx'），不能锚定行首
  "$AAPT" d resources "$1" 2>/dev/null | grep -E 'resource 0x[0-9a-f]+ id/' > "$2" || true
  [ -s "$2" ]   # aapt 没吐东西（路径/权限问题）也算失败
}

say "② 资源 id 比对（共 $(echo "$RES_NAMES" | wc -l) 个模块用到的资源名）"
NEW_IDS=.buildtmp/adapt_new_ids.txt
OLD_IDS=.buildtmp/adapt_prev_ids.txt
dump_ids "$NEW_APK" "$NEW_IDS" || { warn "aapt 不可用，跳过资源比对（AAPT= 可指定）"; NEW_IDS=""; }
if [ -n "$PREV_APK" ]; then dump_ids "$PREV_APK" "$OLD_IDS" || OLD_IDS=""; else OLD_IDS=""; fi

RES_CHANGED=""
if [ -n "$NEW_IDS" ]; then
  printf '  %-10s %-14s %-14s %s\n' 资源名 基准id 新版id 结论
  for n in $RES_NAMES; do
    nid="$(grep -E " id/$n\$" "$NEW_IDS" | awk '{print $2}' | head -1)"
    oid=""; [ -n "$OLD_IDS" ] && oid="$(grep -E " id/$n\$" "$OLD_IDS" | awk '{print $2}' | head -1)"
    if [ -z "$nid" ]; then
      printf '  %-10s %-14s %-14s %s\n' "$n" "${oid:-—}" "—" "新版已无此 id（旧版残留的隐藏项会自动跳过）"
    elif [ -z "$oid" ]; then
      printf '  %-10s %-14s %-14s %s\n' "$n" "—" "$nid" "新增/无基准"
    elif [ "$oid" = "$nid" ]; then
      printf '  %-10s %-14s %-14s %s\n' "$n" "$oid" "$nid" "SAME"
    else
      printf '  %-10s %-14s %-14s %s\n' "$n" "$oid" "$nid" "CHANGED"
      RES_CHANGED="$RES_CHANGED $n"
    fi
  done
  [ -n "$RES_CHANGED" ] && warn "以下资源 id 数值变了，需要更新 MainHook.java 里的 PRE_HIDE_RES_IDS：$RES_CHANGED"
else
  warn "跳过（没有 aapt）"
fi

# ---------- ③ 代码目标比对 ----------
# 这些是模块 hook 的类。前一半是**稳定的非混淆类名**（字节自己的 SDK / 业务类，
# 换版本基本不变）；后一半是**混淆名**，必须每版重新确认（就是这次踩坑最多的地方）。
STABLE_TARGETS=(
  "com.dragon.read.base.ad.AdConfigManager"
  "com.dragon.read.music.player.widget.MusicPatchAdContainer"
  "com.dragon.read.widget.dialog.i"
  "com.dragon.read.admodule.adfm.unlocktime.entranceview.h"
  "com.dragon.read.music.player.block.common.adunlock.MusicAdUnlockTimeView"
  "com.dragon.read.ad.feedbanner.widget.BookMallAdFeedPlayOverPage"
  "com.bytedance.tomato.onestop.readerad.ui.ReadFlowOneStopAtAdView"
)
OBF_TARGETS=(
  "f32.q:i23.d0"                     # 阅读页广告入口行工厂 : VIP 促销弹层
)

DEXDIR=.buildtmp/adapt_dex
say "③ 代码目标比对（新版 dex 里这些类还在不在）"
rm -rf "$DEXDIR"; mkdir -p "$DEXDIR"
( cd "$DEXDIR" && unzip -o -q "$PROJ/$NEW_APK" 'classes*.dex' ) || true
NDEX="$(ls -1 "$DEXDIR"/*.dex 2>/dev/null | wc -l)"
echo "  解出 $NDEX 个 dex -> $DEXDIR"
dex_has() {   # $1 = 类全名（点分）
  local d="L$(echo "$1" | tr '.' '/');"
  grep -alq -F "$d" "$DEXDIR"/*.dex 2>/dev/null
}
MISS=""
for c in "${STABLE_TARGETS[@]}"; do
  if dex_has "$c"; then ok "$c"; else bad "$c  ← 不见了，必须重新定位"; MISS="$MISS $c"; fi
done
# 混淆类名：只检查单字母类描述符还在不在（只能当烟雾测试，语义是否还是那个东西要人工确认）
for pair in "${OBF_TARGETS[@]}"; do
  IFS=':' read -r a b <<< "$pair"
  for c in $a $b; do
    d="L$(echo "$c" | tr '.' '/' );"          # 点分 -> dex 描述符（斜杠），漏了这步会误报 MISS
    if grep -alq -F "$d" "$DEXDIR"/*.dex 2>/dev/null; then
      warn "混淆类 $c 仍存在（记得确认它还是「阅读页广告入口行工厂 / VIP 促销弹层」）"
    else
      bad "混淆类 $c 不见了 → 必须在 dex 里重新搜它的调用者，更新 MainHook.java"
      MISS="$MISS $c"
    fi
  done
done

# ---------- 自动构建结论 ----------
say "④ 结论"
if [ -n "$MISS" ]; then
  warn "有目标丢失:$MISS"
  warn "「我的」页/桌面快捷方式/设置项现在走的是「文案+结构」的版本无关逻辑，"
  warn "资源 id 变了也不用改；只有上面这些 hook 目标真的消失时才需要动代码。"
else
  ok "所有 hook 目标都在，通常无需改代码即可直接用新版本。"
fi
if [ -n "$RES_CHANGED" ]; then
  warn "PRE_HIDE_RES_IDS 建议按上表更新（只是省一帧闪现，功能不依赖它）。"
fi
echo
echo "  需要人工确认时："
echo "    bash tools/adapt_new_version.sh --arm-dump    # 打开手机上的 View 树转储"
echo "    （进目标页面，然后）"
echo "    bash tools/adapt_new_version.sh --pull-tree   # 把 View 树拉到 artifacts/"

if [ "$PULL_TREE" = 1 ]; then
  say "拉取手机上的 View 树/转储"
  [ -z "$ADB" ] && { bad "没找到 adb"; exit 1; }
  CACHE="/data/data/$PKG/cache"
  for f in $("$ADB" shell "su -c 'ls $CACHE/tree_*.txt $CACHE/mine_tree.txt 2>/dev/null'" | tr -d '\r'); do
    b="$(basename "$f")"
    "$ADB" shell "su -c 'cat $f'" > "artifacts/$b" 2>/dev/null || true
    [ -s "artifacts/$b" ] && ok "artifacts/$b ($(wc -l < "artifacts/$b") 行)"
  done
  if [ "$CLEAR_DUMP" = 1 ]; then
    "$ADB" shell "su -c 'rm -f $CACHE/dump_mine'" && ok "已关闭转储开关（避免持续写文件）"
  fi
fi

if [ "$ARM_DUMP" = 1 ]; then
  say "打开手机上的 View 树转储开关"
  [ -z "$ADB" ] && { bad "没找到 adb"; exit 1; }
  "$ADB" shell "su -c 'mkdir -p /data/data/$PKG/cache && touch /data/data/$PKG/cache/dump_mine'" \
    && ok "已打开：进入任意页面后，View 树会写到 /data/data/$PKG/cache/tree_<Activity>.txt（每 3 秒刷新）"
fi

if [ "$DO_BUILD" = 1 ]; then
  say "⑤ bump 版本号并构建"
  # 版本号规则（与历史版本一致）：1.9.21 -> versionCode 192100
  #   versionCode = major*100000 + minor*10000 + patch*100
  read -r VN VC < <("$PY" - <<'PY'
import re
p = "module/AndroidManifest.xml"
s = open(p, encoding="utf-8").read()
vname = re.search(r'versionName="([^"]+)"', s).group(1)
maj, mnr, pat = (int(x) for x in vname.split("."))
new = "%d.%d.%d" % (maj, mnr, pat + 1)
code = maj * 100000 + mnr * 10000 + (pat + 1) * 100
s = re.sub(r'versionName="[^"]+"', 'versionName="%s"' % new, s)
s = re.sub(r'versionCode="\d+"', 'versionCode="%d"' % code, s)
open(p, "w", encoding="utf-8").write(s)
print(new, code)
PY
)
  ok "版本号 -> $VN (versionCode=$VC)"
  bash module/build_local.sh
  if [ -n "$ADB" ]; then
    "$ADB" install -r module-local.apk | tail -1
    V="$("$AAPT" dump badging module-local.apk 2>/dev/null | head -1)"
    echo "  $V"
  fi
  warn "发版前记得 git 提交，并确认模板 APK（build_local.sh 的 TEMPLATE_APK / CI 里的模板）已换成最新一版"
fi
