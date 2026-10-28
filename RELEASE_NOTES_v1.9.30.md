# 番茄畅听净化模块 v1.9.30（versionCode 193000）

> 适配：番茄畅听 **6.7.6.32**（versionCode **676**）｜ 实测设备 Realme RMX2202 / Android 15 ｜ Vector(LSPosed)
> 上一版：v1.9.29（适配 6.7.2.32 / 672）

## 一句话

番茄畅听升级到 6.7.6.32 后，模块 40+ 处 hook 全量核对，**只有两处需要处理**：阅读页广告入口行工厂换了混淆名（`f32.q` → `x32.q`），以及一个从 v1.9.10 起就一直静默失败的 TTS 静音 hook。本版一并修掉，并把前者改成**版本无关**实现。

## 版本支持

| 番茄畅听 | versionCode | 模块版本 | 状态 |
|---|---|---|---|
| **6.7.6.32** | **676** | **v1.9.30** | ✅ **当前版本，实机验证通过** |
| 6.7.2.32 | 672 | v1.9.23 ~ v1.9.29 | ✅ 可用（历史版本） |
| 6.7.1.32 | 671 | v1.9.20 | ✅ 实测通过（历史版本） |
| 6.7.1.16 | 671 | v1.9.10 | ✅ 实测通过（历史版本） |

## 本版更新

### 1. 阅读页广告入口行工厂：`f32.q` → `x32.q`（classes15.dex）

章节末那张「看小视频免30分钟广告」卡片，是靠让生成它的工厂方法返回 `null` 来从源头掐掉的。新版里这个类的混淆名变了：

- 反汇编确认三个工厂方法**结构没变**：`a → AddShortcutLine`、`b → ButtonLine`（就是那张免广告卡）、`c → BuyVipEntranceLine`；
- 调用方由 `z33.d.a` 变成 `u83.d.a`，三处调用点仍然紧跟 `move-result-object` + `if-nez`，所以「返回 null 让 App 自己跳过添加该行」照旧安全，不报错、不留空白。

**顺手把这一处改成版本无关实现**（以后改混淆名不用动代码）：

1. `READER_AD_LINE_FACTORY_CANDIDATES` 候选名列表（新版在前）；
2. `isReaderAdLineFactory()` 结构校验 —— 类里必须有方法返回 `com.dragon.read.ad.*Line` 才算命中，避免混淆名被复用到别的类上；
3. 候选全落空时 `scanReaderAdLineFactoryAsync()` 后台枚举 dex 类名兜底（只扫一次，命中即挂）。

实测日志：`🚫 已从源头屏蔽阅读页广告入口行: x32.q.b() -> ButtonLine`

### 2. TTS 静音 hook 长期「挂载失败」

日志里那条 `TTS 静音挂载失败: NoSuchMethodError: speak(CharSequence,int,HashMap)` 不是新版 App 造成的，而是**系统侧**：这个老签名在新 Android 上已被移除，而旧代码把它和真正生效的 `speak(CharSequence,int,Bundle,String)` 塞在同一个 `try` 里，异常直接把整个挂载流程打断（连「✅ 已静音」都从没打出来）。

改为逐签名独立 `try`，并补上 `speak(String,int,HashMap)` 这个更老的签名。现在日志是 `✅ TTS 播报已静音`。

### 3. 工具

- **`tools/dexq.py`**（新增）：不依赖 jadx/baksmali 的 dex 查询器。`--returns` 按返回类型找工厂方法、`--class` 打印方法表、`--refs` 找引用者、`--str` 搜字符串池、`--find-class` 按类名子串匹配。这次定位 `x32.q` 就是 `--returns Lcom/dragon/read/ad/AddShortcutLine;` 一条命令查出来的。
- **`tools/check_targets.py`**（新增）：把 `MainHook.java` 里出现的所有类名候选一次性拿去新 dex 里核对存在性。换版本先跑它，立刻知道哪些 hook 目标消失了。
- `tools/adapt_new_version.sh` 的混淆目标同步为 `x32.q`。

### 4. 仓库清理

`bd70de8`(v1.9.29) 曾把整份项目误提交到源码仓根目录（与 `module/` 逐字节相同），本版删除。源码仓只保留 `module/` 布局；根目录布局只属于发布仓库。

## 验证情况

| 项目 | 结果 |
|---|---|
| 模块加载 | ✅ `versionName=1.9.30`，日志零失败、零「未找到」 |
| 阅读页广告入口行 | ✅ `🚫 已从源头屏蔽阅读页广告入口行: x32.q.b() -> ButtonLine` |
| 阅读页 OneStop 广告 | ✅ `🚫 已拦截阅读页 OneStop 广告策略(请求)` |
| 首页 / 听歌 / 我的 / 阅读页 | ✅ 逐页看过：无残留广告卡、无空白占位、「全天畅听」「领金币」「我的资产」等入口均不显示 |
| 其余 40+ 处 hook | ✅ 广告位总闸、Lynx 广告场景、silk subwindow、贴片广告、全天畅听特权伪装、金币语音屏蔽全部照常挂载 |

## 升级方式

直接覆盖安装即可：

```
adb install -r fanqie-enhance-v1.9.30.apk
```

装完**强制停止番茄畅听**（`adb shell am force-stop com.xs.fm`）再打开，hook 才会在新进程生效。

> 模块日志不在 logcat，在 `/data/adb/lspd/log/modules_*.log`（tag `VectorLegacyBridge`）：
> `adb shell "su -c 'grep FanqieEnhance /data/adb/lspd/log/modules_*.log | tail -30'"`

## 已知边界

- `i23.d0`（首页 VIP 促销弹层）仍是每版需要人工复核的混淆类；`adapt_new_version.sh` 会提示。
- 阅读页广告入口行工厂现在有兜底扫描，但如果番茄把 `com.dragon.read.ad.*Line` 这套模型整体换掉（比如改叫 `*Card`），候选与扫描都会落空，需要按新模型重新定位。
- 去广告与界面净化**不依赖登录**；VIP 免听体验仍由服务端校验，模块不绕过服务端。

## 产物

| 文件 | 说明 |
|---|---|
| `fanqie-enhance-v1.9.30.apk` | 本版模块（versionCode `193000` / versionName `1.9.30`），sha256 见同名 `.sha256` |
| `module/build_local.sh` | 宿主机本地构建脚本（不需要手机 root，推荐） |
| `module/build.sh` | 手机端 Termux 工具链构建脚本（需 root） |
