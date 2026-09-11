# 番茄畅听净化模块 v1.9.10 (versionCode 191000)

> **适配目标应用：番茄畅听 `com.xs.fm` —— `6.7.1.16`（versionCode `671`）实测通过**
> 框架要求：Root + Vector / LSPosed（xposedminversion 82，Android 8.0+）
> 发布日期：2026-09-11

---

## 📌 适配版本号说明（重点）

| 项目 | 值 |
|------|-----|
| 适配应用包名 | `com.xs.fm` |
| **适配版本名** | **`6.7.1.16`** |
| **适配 versionCode** | **`671`** |
| 本模块版本 | `1.9.10`（versionCode `191000`） |

- 本版已把模块用到的 **16 个混淆类名 / 资源 id** 在 6.7.1.16 上逐一复核，全部仍然存在（含 `h80` / `bpz` / `e33` / `ccv` / `c1` / `e10` 等）。
- 番茄的类名与资源 id 逐版强混淆变动，**只保证 6.7.1.16（671）实测可用**。应用大版本升级（如 6.7 → 6.8）后极可能失效，需重新适配。
- 注意：**模块版本号 `1.9.x` 与番茄版本号 `6.7.1.16` 是两套独立编号**。

---

## 🆓 免登录说明

**本模块的核心能力不依赖登录账号。**

| 能力 | 是否依赖登录 | 说明 |
|------|--------------|------|
| 去广告（贴片广告 / 广告卡 / 弹窗 / 广告页 / 倒计时条） | ❌ 不依赖 | 纯本机 View 树 + 类名 + 资源 id 层面拦截，未登录 100% 生效 |
| 入口隐藏（商城 / 领现金 / 金币球 / 福利 / 我的资产 / 我的消息 / 全天畅听…） | ❌ 不依赖 | 同上，纯 UI 层面 |
| VIP 状态写入（`isVip` / `freeAd` / `expireTime` …） | ⚠️ 可写入，实际免听看服务端 | 未登录（游客态）时 `AcctManager.INSTANCE` 依然存在，模块能成功写入；但会员权益由番茄**服务端**校验，未登录能否真正试听 VIP 内容取决于服务端策略，模块不绕过服务端 |

**实测记录**：清空账号缓存（`prefix_private_acct_user_info_cache` MMKV）进入完全未登录态，模块日志仍输出
`已patch userModel: isVip=true freeAd=true leftTime=999999999`，且首页 / 我的页 / 听歌页的广告位、金币球、福利入口均正常隐藏。

**结论**：只想**去广告 + 界面净化** → **不用登录**；想要**完整 VIP 免听** → 建议登录。

---

## 本版更新

### 适配
- 番茄畅听 `6.7.1.16`（versionCode `671`）实测通过；16 个混淆类名 / 资源 id 全部复核存在
- 模块版本号 `1.9.10`（versionCode `191000`）

### 修复
- **未登录时「我的」页头部整块消失**（下拉才短暂出现，上滑到「全部」一行就没了）
  - 根因：未登录头部文案命中 `shouldHide()` 里过宽的 `t.contains("领取") && t.length() <= 6` 规则，随后 `hideEntry → hideChain` 向上连藏 3 层，把 `#h4z`（头像 + 昵称 + 登录按钮）和 `#e10`（整个头部 `LinearLayout`）一并 GONE
  - `#e10` 是普通 `android.widget.LinearLayout`、不含 AppBar，`isProtectedContainer()` 的类名白名单拦不住；真正受保护的 `#c1`（`CommonCustomAppBarLayout`）在更外层，等循环走到它时头部早已被隐藏
  - 修复：`hideChain()` 开头新增「整链放弃」判定 —— 用新增的 `isAppBarLike()` 沿父链一路向上找 `AppBar`/`Toolbar`/`ActionBar`/`TabLayout`，命中即整条链放弃，一帧都不动

### 免登录
- 确认并固化：去广告与入口隐藏**完全不依赖登录**；未登录同样能写入 VIP 字段
- 账号实例切换（登录 / 退出登录）后自动重新 patch

### 文档
- README / SUMMARY / CHANGELOG 全面更新，突出适配版本号 `6.7.1.16`（`671`）
- 修正安装步骤：删除「必须先登录」的过时要求
- 修正生效验证方式：模块日志**不在 logcat**，改指 `/data/adb/lspd/log/verbose_*.log`

---

## 安装步骤

1. **安装模块 APK**
   ```bash
   pm install -r /path/to/fanqie-purify.apk
   ```
2. **在 Vector / LSPosed 中启用本模块**，作用域勾选番茄畅听
   ```
   /data/adb/lspd/cli modules enable com.eta.fanqie.enhance
   /data/adb/lspd/cli scope add com.eta.fanqie.enhance com.xs.fm/0
   ```
3. **强制停止番茄畅听后重新打开**（模块随目标进程注入，必须重启目标进程才生效）
   ```bash
   am force-stop com.xs.fm
   ```
4. （可选）登录账号，仅用于获得完整 VIP 免听体验。

### 验证是否生效（⚠️ 日志不在 logcat）

```bash
adb shell "su -c 'grep FanqieEnhance /data/adb/lspd/log/verbose_*.log | tail -20'"
```

看到 `[FanqieEnhance] v1.9.10 加载: process=com.xs.fm` 与 `已patch userModel: isVip=true freeAd=true leftTime=999999999` 即成功。

---

## ⚠️ 走过的弯路（记录备查）

- 第一版修复用的是「文案含『登录』就放过」的粗粒度白名单。结果「登录领取」既是「我的」页头部的账号入口、又是首页 / 我的页右侧浮动红包广告的文案，白名单把广告一起放过了（当场反馈「首页的登录领取广告又回来了」）。**结论：必须用结构判定（在不在顶栏里），不能用文案判定。**
- `isAppBarLike()` 故意**不复用** `isProtectedContainer()`：后者把 `"TopView"` 也算受保护，而 `BookMallTopView`（首页搜索栏那一行）类名含 `TopView` 且首页广告位就装在它里面，复用会误伤。

---

## 已知问题

- 首页搜索栏右侧「全天畅听」胶囊在冷启动首帧仍可能出现极短闪现（构造期已 GONE，疑为 Lynx 广告层叠加渲染，持续跟进）
- 听书页右上角「商店」图标尚未隐藏（待适配）
- 番茄大版本更新可能改变混淆类名 / 资源 id，需按新版本重新适配

## 免责声明

本项目仅供学习与技术交流，不修改番茄畅听 APK 本体、不联网、不上传数据。会员权益属付费服务，请支持正版。详见仓库 [DISCLAIMER.md](https://github.com/guoxpeng/fanqie-purify/blob/main/DISCLAIMER.md)。
