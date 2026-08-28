# 项目记忆文件

> 最后更新：2026-08-28
> 本文件记录所有项目的关键经验、流程和避坑规则。

---

## 一、Vector / Xposed-Modules-Repo 上传要求

### 仓库创建规则（来自 Xposed-Modules-Repo 官方）

```
Repo Requirement:
1. Repo name = 模块包名（如 com.eta.fanqie.enhance）
2. Repo description = 模块显示名（如「番茄畅听净化模块」）← 必填，否则搜不到！
3. 至少一个有效 Release
4. Release tag 格式：VersionCode-VersionName（如 19500-1.9.5）
5. Release 必须有 APK asset
```

### ⚠️ 踩坑：description 为空导致搜不到

- **现象**：在 modules.lsposed.org 搜索「番茄」找不到模块
- **原因**：repo description 为空（null），索引机器人无法获取模块名
- **修复**：通过 GitHub API 设置 description = 模块中文名
- **教训**：创建仓库后第一件事就是设置 description！

### 完整上传流程

```bash
# 1. 创建 GitHub 仓库（Xposed-Modules-Repo 组织下）
#    repo name = 包名
#    description = 模块中文名 ← 关键！

# 2. 设置 topics（可选但推荐）
curl -X PATCH -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/Xposed-Modules-Repo/$PACKAGE_NAME" \
  -d '{"description":"模块名","topics":["xposed","android","关键词"]}'

# 3. 构建 APK（见下方构建流程）

# 4. 创建 Release
curl -X POST -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/Xposed-Modules-Repo/$PACKAGE_NAME/releases" \
  -d '{"tag_name":"19500-1.9.5","name":"1.9.5","body":"更新说明"}'

# 5. 上传 APK asset
curl -X POST -H "Authorization: token $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  "https://uploads.github.com/repos/Xposed-Modules-Repo/$PACKAGE_NAME/releases/$RELEASE_ID/assets?name=Module_v1.9.5.apk" \
  --data-binary "@module.apk"

# 6. 同步源码到 Xposed-Modules-Repo 仓库
git clone https://token@github.com/Xposed-Modules-Repo/$PACKAGE_NAME.git
# 复制更新的文件
git add -A && git commit && git push
```

### modules.lsposed.org 索引机制

- 索引机器人自动扫描 Xposed-Modules-Repo 组织下的所有仓库
- 依据 repo description 显示模块名
- 依据 release tag 和 APK asset 提供下载
- 新 Release 创建后通常几小时内被收录

---

## 二、APK 构建流程（Windows 本地）

### 环境要求

- JDK 21（OpenJDK Temurin）
- Android SDK Build Tools 36.0.0（d8 兼容 Java 21 编译的匿名内部类）
- Android SDK Platforms android-34

### 构建步骤

```bash
BUILD_TOOLS="$HOME/AppData/Local/Android/Sdk/build-tools/36.0.0"
ANDROID_JAR="$HOME/AppData/Local/Android/Sdk/platforms/android-34/android.jar"
WORK_DIR="/tmp/fanqie-build"

# 1. 编译 Xposed API stub
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$WORK_DIR/stub_classes" \
  module/src/de/robv/android/xposed/*.java \
  module/src/de/robv/android/xposed/callbacks/*.java

# 2. 编译主代码
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -classpath "$WORK_DIR/stub_classes" \
  -d "$WORK_DIR/classes" \
  module/src/com/eta/fanqie/enhance/MainHook.java

# 3. jar + d8 转 DEX
cd "$WORK_DIR/classes" && jar cf ../out/classes.jar . && cd ..
"$BUILD_TOOLS/d8.bat" --lib "$ANDROID_JAR" --min-api 26 \
  --output "$WORK_DIR/out" "$WORK_DIR/out/classes.jar"

# 4. aapt2 资源链接（必须从项目根目录执行）
"$BUILD_TOOLS/aapt2.exe" link -o "$WORK_DIR/out/module-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest module/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 19500 --version-name "1.9.5"

# 5. 加入 DEX 和 assets
cd "$WORK_DIR/out"
jar uf module-unsigned.apk classes.dex
jar uf module-unsigned.apk assets/xposed_init
cd "$PROJECT_ROOT"

# 6. 签名
"$BUILD_TOOLS/apksigner.bat" sign \
  --ks "$WORK_DIR/module.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$WORK_DIR/out/Module_v1.9.5.apk" "$WORK_DIR/out/module-unsigned.apk"
```

### ⚠️ 构建踩坑

| 问题 | 原因 | 解决 |
|------|------|------|
| d8 NullPointerException | Java 21 匿名内部类 + build-tools 34 的 d8 不兼容 | 用 build-tools **36.0.0** |
| aapt2 link 找不到 manifest | cd 后工作目录变了 | aapt2 命令必须从**项目根目录**执行 |
| stub 编译警告 source 8 obsolete | Java 21 不推荐 source/target 8 | 忽略警告，编译仍成功 |
| adb push 路径被翻译 | Windows Git Bash 路径转换 | 用 `adb shell "cat > /sdcard/file"` + `cp` |

---

## 三、番茄畅听净化模块（com.eta.fanqie.enhance）

### 项目结构

```
fanqie-purify/
├── module/
│   ├── src/com/eta/fanqie/enhance/MainHook.java  ← 单文件实现
│   ├── AndroidManifest.xml
│   ├── assets/xposed_init
│   └── build.sh          ← 手机端构建脚本（Termux）
├── RELEASE_NOTES_v*.md
├── CHANGELOG.md
├── README.md
└── MEMORY.md             ← 本文件
```

### 核心功能

| 功能 | 实现方式 |
|------|----------|
| VIP 免听 | 反射 `AcctManager.INSTANCE.userModel`，写入 isVip/freeAd |
| 入口隐藏 | `shouldHide()` 文本规则 + View 树遍历 + 位置校验 |
| 广告拦截 | BLOCKED Activity 前缀表 + Dialog 类名/内容匹配 + 广告 SDK hook |
| 听歌页广告 | 资源 ID 精确隐藏（a3c/fp_）+ 全屏覆盖检测 + 动态构造函数 hook |
| 桌面快捷方式 | hook ShortcutManager，过滤金币/领现金等关键词 |

### 版本历史

| 版本 | versionCode | 主要变更 |
|------|-------------|----------|
| 1.9.5 | 19500 | 屏蔽借钱/公益、听歌页广告增强、全屏覆盖检测 |
| 1.9.4 | 19400 | 隐藏阅读页左上角金币入口 |
| 1.9.3 | 19300 | 拦截激励视频全屏广告页 |
| 1.9.2 | 19200 | 隐藏阅读页右上角700金币小面板 |
| 1.9.1 | 19100 | 看小视频免广告链接隐藏、浅隐藏策略 |

### 仓库

| 仓库 | 用途 |
|------|------|
| `guoxpeng/fanqie-purify` | 主开发仓库 |
| `Xposed-Modules-Repo/com.eta.fanqie.enhance` | Vector/LSPosed 模块仓库（自动索引） |

---

## 四、Zalo 汉化模块（com.zalocn）

### 项目结构

```
zalo-cn/
├── app/                  ← Android 应用模块
├── build/                ← 构建产物
├── data/                 ← 汉化数据
├── RELEASE_NOTES_v*.md
├── SUMMARY
└── README.md
```

### 仓库

| 仓库 | 用途 |
|------|------|
| `guoxpeng/zalo-cn` | 主开发仓库 |
| `Xposed-Modules-Repo/com.zalocn` | Vector/LSPosed 模块仓库 |

### 模块信息

- 包名：`com.zalocn`
- 描述：`Zalo 汉化`
- Topics：`lsposed-module, xposed, xposed-module, zalo`

---

## 五、通用避坑规则

### Git / GitHub

- **不要在 commit 中包含 token**：推送后立即恢复 remote URL
- **Git config**：项目中不要修改全局 config，用 local config
- **CRLF 问题**：Windows 上 `.md` 文件会有 CRLF 警告，可忽略
- **adb push 路径翻译**：Git Bash 会把 `/sdcard/` 翻译为 Windows 路径，用 `adb shell "cat > file"` 代替

### Android / Xposed

- **模块安装顺序**：先装模块 → 再登录目标 App → 再启用模块作用域 → 强停重启
- **Vector CLI**：
  ```bash
  /data/adb/lspd/cli modules enable com.eta.fanqie.enhance
  /data/adb/lspd/cli scope add com.eta.fanqie.enhance com.xs.fm/0
  ```
- **混淆类名变化**：App 更新后广告 View 类名可能变化，需用诊断日志重新发现
- **View 隐藏策略**：GONE 优于 INVISIBLE（不留空位），但要保护导航栏/顶栏

### 构建

- **build-tools 版本**：Java 21 必须用 build-tools 36+，34 的 d8 有 bug
- **aapt2 工作目录**：必须从项目根目录执行，否则找不到 manifest
- **签名 keystore**：首次构建自动生成，后续复用

---

## 六、调试技巧

### 抓取模块日志

```bash
adb logcat -d | grep FanqieEnhance
```

### 诊断未知 View（广告发现）

模块内置 `scanUnknownClickableViews()`，5 秒后扫描所有无文本可点击 View 并记录到 logcat。搜索 `听歌页未知可点击View` 可看到结果。

### 实时监控广告出现

```bash
# 清空日志
adb logcat -c
# 等用户操作后
adb logcat -d | grep -E "FanqieEnhance|侦察|覆盖|广告"
```

### UI Dump 分析

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml > ui_dump.xml
# 然后用 Python 解析 NAF 节点和文本节点
```
