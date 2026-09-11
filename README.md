# 番茄畅听净化模块

一个 [Vector](https://github.com/AAswordman/Vector) / LSPosed Xposed 模块：净化番茄畅听（com.xs.fm）的使用体验——破解会员、隐藏营销入口与广告弹窗。

> **当前版本：v1.9.10** ｜ **适配目标应用：番茄畅听 `6.7.1.16`（versionCode `671`）实测通过**
> 框架要求：Root + Vector 或 LSPosed（xposedminversion 82，Android 8.0+）

---

## 📌 适配版本（重要）

| 项目 | 值 |
|------|-----|
| **适配应用** | 番茄畅听 `com.xs.fm` |
| **适配版本名** | **`6.7.1.16`** |
| **适配 versionCode** | **`671`** |
| 实测设备 | Realme RMX2202 / Android 12 |
| 实测框架 | Vector（LSPosed 兼容） |

**只保证在上述版本上工作。** 番茄的类名与资源 id 随版本强混淆、逐版变动，因此：

- 应用 **小版本更新**（如 6.7.1.x）通常仍可用；
- 应用 **大版本更新**（如 6.7 → 6.8）后，模块用到的混淆类名 / 资源 id（例如 `h80`、`bpz`、`e33`、`ccv` 等）极可能失效，届时需要按新版本重新适配并升版本号。
- 本模块的版本号（`1.9.x`）与番茄的版本号（`6.7.1.16`）是**两套独立编号**，不要混淆。

---

## 🆓 免登录说明（无需登录账号）

**本模块的核心能力不依赖登录账号。** 具体来说：

| 能力 | 是否依赖登录 | 说明 |
|------|--------------|------|
| **去广告**（贴片广告 / 广告卡片 / 弹窗 / 广告页面 / 倒计时条） | ❌ **完全不依赖** | 全部在**本机 View 树 + 类名 + 资源 id** 层面完成拦截，与账号态无关，未登录同样 100% 生效 |
| **入口隐藏**（商城 / 领现金 / 金币球 / 福利 / 我的资产 / 我的消息 / 全天畅听 等） | ❌ **完全不依赖** | 同上，纯 UI 层面隐藏 |
| **VIP 状态写入**（`AcctManager.INSTANCE.userModel` 的 `isVip` / `freeAd` / `expireTime` 等） | ⚠️ **可写入，但实际免听取决于服务端** | 未登录（游客态）时 `AcctManager.INSTANCE` 依然存在，模块能成功把 `isVip=true` / `freeAd=true` 写进去；但**会员权益本身是服务端校验的**，未登录时能否真正试听 VIP 书籍由番茄的服务端策略决定，模块不绕过也无法绕过服务端校验。 |

**结论：**

- 只想**去广告 + 界面净化** → **不用登录**，装上就有效果。
- 想获得**完整的 VIP 免听体验** → 建议登录账号（服务端权益校验需要通过），模块会在登录 / 退出登录时自动重新写入 VIP 字段（v1.9.10 起支持账号实例切换后自动重 patch）。

> 实测记录：在完全未登录（清空 `prefix_private_acct_user_info_cache` MMKV）的状态下启动，模块日志仍然输出
> `已patch userModel: isVip=true freeAd=true leftTime=999999999`，且首页 / 我的页 / 听歌页的广告位、金币球、福利入口均正常隐藏。

---

## ⚠️ 免责声明

1. **本项目仅供学习与技术交流使用**，用于研究 Android Framework、Xposed Hook 机制与 UI 自动化净化技术。
2. 本模块**不修改番茄畅听 APK 本体**，不联网、不上传任何数据；所有效果均在本机内存中临时生效。
3. 番茄畅听及其内容版权归字节跳动所有。会员权益属付费服务，**请支持正版**；本模块仅供个人学习研究，**严禁用于商业用途或二次分发牟利**。
4. 使用本模块产生的一切后果（包括但不限于账号风控、功能异常）由使用者自行承担。作者不对任何直接或间接损失负责。
5. 如你是有权方并认为本项目侵犯合法权益，请联系删除。
6. **下载即视为已阅读并同意以上全部条款。**

---

## 功能列表

### 会员相关
- **VIP 免听破解**：自动将 `AcctManager.INSTANCE.userModel` 写入 VIP 状态（`isVip` / `freeAd` / 过期时间 2099 / `leftTime` 等），可直接试听 VIP 书籍
- **账号态无关**：未登录同样写入；登录 / 退出登录切换实例后自动重新 patch

### 入口净化（隐藏）
- 底部导航「商城」「领现金」tab（保留 首页/听歌/我的 三 tab）
- 「我的」页：我的资产 / 金币余额(币) / 现金余额(元) / 福利面板 / 购物车 / 优惠券 / 商城入口 / 借钱 / 我的公益 / 我的消息
- 阅读页右上角「700金币」自绘小面板（按 viewId `h80` + 位置校验隐藏）
- 右侧悬浮「立即领取」金币球
- 首页顶部「直播」tab、首页浮动「登录领取」红包
- 章节页「看小视频免30分钟广告」提示链接、「2500金币待领取」入口
- 听歌页「全天畅听」广告卡、「看小视频免广告」横幅
- 听歌页 MusicAdUnlockTimeView（广告解锁倒计时条）
- 全屏覆盖型广告自动检测与隐藏

### 弹窗拦截
- 类名匹配：luckycat / 更新升级 / 广告弹窗
- 内容匹配：「签到」「听歌领金币」「领取+金币」类弹窗自动关闭
- **DialogFragment 拦截（v1.9.5）**：章节末「看小视频免30分钟广告」等广告弹窗是 DialogFragment（不走 `Dialog.show()`），hook `androidx.fragment.app.DialogFragment.show()` + 已知广告 Fragment 的 `onCreateView` 直接阻止渲染
- **广告文本实时过滤**：hook `TextView.setText()`，广告文本被设置的瞬间即隐藏（覆盖 App 切换智能朗读/真人讲书等模式重建 View 的场景，零扫描零卡顿）

### 广告拦截（v1.9.9 增强）
- **听歌页贴片广告**：番茄的「贴片广告」由字节 Lynx（`com.ss.android.mannor`）模板渲染，装在 `MusicPatchAdContainer` 里，**没有固定资源 id**。模块改为**视觉层面**拦截：hook 该容器构造 + `View.setVisibility`，只置 `GONE`、绝不 `removeView`（`ViewStub` 只能 inflate 一次，remove 会抛异常），彻底消除「小视频 + 商家推荐 + 倒计时」广告卡

### 页面拦截
- 商城 Activity、luckycat 激励页、开屏广告、沉浸式广告、免费听广告页、广告解锁页等启动即 finish
- AdUnlockTimeDialogManager 弹窗拦截（源码级 hook）
- 激励广告 SDK 调用拦截（showAd / preloadAd 等）
- 广告倒计时 View 创建即 GONE（MusicAdUnlockTimeView / AdUnlockTimeFloatingView）

---

## 安装步骤

> ✅ **顺序要求已放宽**：v1.9.10 起去广告与界面净化**不再要求先登录**。推荐顺序为「装模块 → 启用作用域 → 强停重启目标 App」，登录与否随意。

1. **安装模块 APK**
   ```bash
   pm install -r /path/to/fanqie-purify.apk
   ```
2. **在 Vector / LSPosed 管理器中启用模块**，作用域勾选番茄畅听
   - Vector CLI 方式：
     ```bash
     /data/adb/lspd/cli modules enable com.eta.fanqie.enhance
     /data/adb/lspd/cli scope add com.eta.fanqie.enhance com.xs.fm/0
     ```
3. **强制停止番茄畅听后重新打开**（模块随目标进程注入，必须重启目标进程才生效）
   ```bash
   am force-stop com.xs.fm
   ```
4. （可选）**登录账号**——只为获得完整 VIP 免听体验；不登录也能去广告。

### 如何确认模块已生效（⚠️ 日志不在 logcat 里）

`XposedBridge.log` 的输出**不会**出现在 `logcat` 中。Vector/LSPosed 会把它写进自己的日志文件，logcat 里只能看到 `VectorZygiskBridge: GET_BINDER` 这类框架噪声，**不要被它误导**。

正确姿势：

```bash
# 1. 找到最新的 Vector 详细日志
adb shell "su -c 'ls -t /data/adb/lspd/log/verbose_*.log | head -1'"

# 2. 在其中搜索模块 tag（写入时 tag 是 VectorLegacyBridge）
adb shell "su -c 'grep FanqieEnhance /data/adb/lspd/log/verbose_*.log | tail -20'"
```

看到下面这行即表示加载成功：

```
[FanqieEnhance] v1.9.10 加载: process=com.xs.fm
已patch userModel: isVip=true freeAd=true leftTime=999999999
```

### 已知限制
- 卸载后重装模块需**重新 enable + scope add**
- **番茄大版本更新可能改变混淆类名 / 资源 id**（如 `h80`），届时需按新版本重新适配并升版本号（见上文「适配版本」）
- 模块只影响 `com.xs.fm` 主进程，不影响其他应用
- LSPosed 的 `modules_config.db` 由守护进程在内存中缓存，直接改文件不生效，需在管理器 UI 中开关

---

## 工作原理（简述）

| 机制 | 说明 |
|------|------|
| patchVip | 反射 `com.dragon.read.user.AcctManager.INSTANCE.userModel`，写入 `isVip`/`freeAd`/`expireTime`/`leftTime` 等字段；实例身份变化时自动重 patch |
| hideAll | `OnGlobalLayout` 监听（1.2s 限频），遍历 View 树按文本规则隐藏入口（不做高频轮询，防卡顿） |
| hideChain | 触底隐藏：向上连藏最多 3 层父容器，带页面级 / 导航栏双重保护防误伤 |
| **isAppBarLike 整链放弃（v1.9.10）** | `hideChain` 入口先沿父链向上查找 `AppBar`/`Toolbar`/`ActionBar`/`TabLayout`，命中即**整条链放弃**。用于区分「我的页头部账号入口」与「浮动红包广告」——两者文案都是「登录领取」，**必须用结构判定而非文案白名单** |
| scanAllWindows | 反射 `WindowManagerGlobal.mViews`，覆盖独立悬浮窗里的金币球（低频率） |
| Dialog blocker | hook `Dialog.show()`，类名 + 内容双重匹配后 dismiss |
| DialogFragment blocker | hook `androidx.fragment.app.DialogFragment.show()` + 广告 Fragment `onCreateView` 返回 null（精准拦截，无扫描） |
| TextView 文本过滤 | hook `TextView.setText()`：广告文本被设置瞬间隐藏自身及卡片容器（零扫描、零延迟、防模式切换重建） |
| View.setVisibility 拦截 | 已知广告 View 被设 `VISIBLE` 时强制 `GONE`，防重建闪现 |
| 贴片广告视觉拦截（v1.9.9） | 针对 Lynx 模板广告（`MusicPatchAdContainer`），只 `GONE` 不 `remove`，避免 `ViewStub` 二次 inflate 崩溃 |

完整源码见 [`MainHook.java`](module/src/com/eta/fanqie/enhance/MainHook.java)（约 2100 行，单文件实现）。

---

## 目录结构

```
├── README.md                    ← 你正在看的
├── DISCLAIMER.md                ← 免责声明全文
├── CHANGELOG.md                 ← 版本历史
├── RELEASE_NOTES_v1.9.10.md     ← 最新版发布说明（含适配版本 + 免登录说明）
└── module/
    ├── src/                     ← Java 源码（MainHook.java + Xposed stub）
    ├── build.sh                 ← 构建脚本
    └── out/module.apk           ← 成品
```

## 许可证

MIT License — 详见 [LICENSE](LICENSE)

**再次提醒：仅供学习交流，请在 24 小时内自行决定是否保留，支持正版。**
