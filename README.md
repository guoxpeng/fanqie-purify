# 番茄畅听净化模块

一个 [Vector](https://github.com/AAswordman/Vector) / LSPosed Xposed 模块：净化番茄畅听（com.xs.fm）的使用体验——破解会员、隐藏营销入口与广告弹窗。

> 🏠 **项目主页 / 最新版本 / 问题反馈**：[**`guoxpeng/fanqie-purify`**](https://github.com/guoxpeng/fanqie-purify)
> 📦 **发布镜像**（Vector / LSPosed 管理器读取）：[`Xposed-Modules-Repo/com.eta.fanqie.enhance`](https://github.com/Xposed-Modules-Repo/com.eta.fanqie.enhance)

> **当前版本：v1.9.23** ｜ **适配目标应用：番茄畅听 `6.7.2.32`（versionCode `672`）实测通过**
> 框架要求：Root + Vector 或 LSPosed（xposedminversion 82，Android 8.0+）

> 💡 **v1.9.21 起「我的」页 / 设置项 / 桌面长按广告都改成了「文案 + 结构」的版本无关做法** ——
> 以后再出小版本通常不用改代码，跑一遍 `bash tools/adapt_new_version.sh` 看结论即可。

---

## 📌 适配版本（重要）

| 项目 | 值 |
|------|-----|
| **适配应用** | 番茄畅听 `com.xs.fm` |
| **适配版本名** | **`6.7.2.32`** |
| **适配 versionCode** | **`672`** |
| 实测设备 | Realme RMX2202 / Android 14 |
| 实测框架 | Vector（LSPosed 兼容） |

### 适配版本历史

| 番茄畅听版本 | versionCode | 对应模块版本 | 状态 | 该版新增的适配要点 |
|---|---|---|---|---|
| **`6.7.2.32`** | **`672`** | **v1.9.23** | ✅ **当前版本，实测通过** | 资源 id **没有平移**（13 个名字全部 `old == new`）但**含义变了**（`e4q` 从「我的资产」变成「快捷入口栏」，`br7`/`ej2`/`ce4` 整组消失）；混淆类 `f22.q`→`f32.q`、`a13.d0`→`i23.d0`。**「我的」页 / 设置项 / 桌面长按广告改为版本无关实现**；修掉「我的」页留空白与切 Tab 不刷新；修掉 `ShortcutManager` 类名写错导致长按图标广告栏不住 |
| `6.7.1.32` | `671` | v1.9.20 | ✅ 实测通过（上一版，不再维护） | versionCode 仍是 671 ⇒ 混淆「名字表」未变，但**资源 id 数值整体平移**，需按新包重新提取；「我的」页换成新 id（`bpz`→`br7`、`e33`→`e4q`、`ccv`→`ce3`/`ce4`）。新增阅读页 OneStop 购物卡 / 章节末广告入口行 / 首页 VIP 促销全屏浮层 / 「我的」页四类入口的拦截 |
| `6.7.1.16` | `671` | v1.9.10 | ✅ 实测通过（上一版，不再维护） | 16 个混淆类名 / 资源 id 全部复核存在；支持免登录；修复未登录时「我的」页头部消失 |
| `6.6.7.32` | `667` | v1.9.4 / v1.9.5 | ✅ 实测通过（历史版本） | DialogFragment 广告弹窗拦截、广告文本实时过滤、听歌页广告按资源 id 精确隐藏 |
| `6.6.4.32` | `664` | v1.9.2 | ✅ 实测通过（上上版） | 阅读页左上角金币入口隐藏（`h80` 顶部左右角均识别） |

> 上表除首行外均为**此前已验证通过的历史版本**，它们在对应模块版本上实测可用，但**已不再维护**；当前只对 `6.7.2.32 (672)` 做适配与验证。

**只保证在上述版本上工作。** 番茄的类名与资源 id 随版本强混淆、逐版变动，因此：

- 应用 **小版本更新**（如 6.7.1.x）通常仍可用；
- ⚠️ **同一个 versionCode 内换包，资源 id 也会平移**：实测 `6.7.1.16` → `6.7.1.32` 的 `versionCode` 都是 `671`，混淆「名字表」没变（`gxi`/`bwf`/`gtr`/`f22.q` 等名字依然存在），但**资源 id 的数值整体变了**，而且「我的」页连对象名都换了（`bpz`→`br7`、`e33`→`e4q`、`ccv`→`ce3`/`ce4`）。所以模块里**硬编码的 id 数值必须按新包重新提取**，按名字查找的也要逐一复核，不能因为 versionCode 没变就认为「不用适配」。
- 应用 **大版本更新**（如 6.7 → 6.8）后，模块用到的混淆类名 / 资源 id（例如 `h80`、`br7`、`e4q`、`ej2` 等）极可能失效，届时需要按新版本重新适配并升版本号。
- ✅ **v1.9.21 起，上述「我的」页 / 设置项 / 桌面快捷方式已经与资源 id 解耦**（改成「文案 + 结构」定位，详见下文「版本无关实现」），所以不少小版本更新即使不重新适配也照样有效。

### 🔁 一键适配新版本

```bash
bash tools/adapt_new_version.sh                      # 从手机拉包 + 资源 id / hook 目标比对（不动代码）
bash tools/adapt_new_version.sh --build              # 检查 + 自动 bump 版本号 + 构建 + 安装
bash tools/adapt_new_version.sh --arm-dump           # 打开手机端 View 树转储（诊断新页面结构）
bash tools/adapt_new_version.sh --pull-tree --clear-dump   # 把 View 树拉到 artifacts/
```

它会自动抽取 `MainHook.java` 里用到的资源名做新旧版 id 比对，并逐个检查 hook 目标（含混淆类名）在新 dex 里还在不在，最后给出「要不要改代码」的结论。

### 🧩 版本无关实现（v1.9.21）

| 目标 | 旧做法（脆弱） | 新做法（版本无关） |
|------|----------------|--------------------|
| 「我的」页顶部区块 | 背 `br7` / `e4q` / `ej2` / `ce4`（每版都换） | 拿住「个人主页」爬到整宽很扁的**头像行**，再取它的父容器，把**除头像行以外的子块全部收掉**（App 以后往这里塞新广告位也自动收） |
| 「我的」页判定 | `e4q` / `e4r` 资源 id | 文案「我的资产」（兜底「个人主页 + 金币余额」） |
| 隐藏后留空白 | 只 GONE 最里层 | `collapseEmptyAncestors()`：往上把「已无可见内容」的包装容器一起折叠 |
| 设置页「支付管理 / 免流量服务」 | 无 | 文案 + 「整行容器」结构 |
| 桌面长按图标广告 | `android.app.ShortcutManager`（**这个类不存在，等于没生效**） | `android.content.pm.ShortcutManager` + `ShortcutManagerCompat`，发布时即拦 + `onResume` 清理 |
| 长/短广告文案 | 「长度 > 14」门卡把 4 字的「深度卸载」挡在外面 | `isLongAdText()` 先按关键词判，只有长文案才需要 14 字护栏 |

仍需每版复核的版本相关部分：`PRE_HIDE_RES_IDS`（只省一帧闪现）、阅读页广告入口行工厂 `f32.q`、VIP 促销弹层 `i23.d0` —— `adapt_new_version.sh` 会提醒。
- 本模块的版本号（`1.9.x`）与番茄的版本号（`6.7.1.32`）是**两套独立编号**，不要混淆。

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
- **「我的」页头部（v1.9.20）**：我的消息 / 游戏中心（快捷入口栏 `ce3` 内的 `u2` 入口卡）、服务端活动入口、活动横幅 `ej2`（实测「中秋好礼限时购 · 一件立减15%>」，**纯图片、节点上没有文案**）、整个头部入口卡片 `ce4`
- **「我的」页资产区（v1.9.20）**：我的资产板块 `e4q`（含 提现 / 金币余额(币) / 现金余额(元) / 剩余时长(分)）、VIP 促销卡 `br7`（含「¥1开通」「立减」）
- **阅读页正文流（v1.9.18）**：OneStop 一站式购物卡（如「XX家具超市 + 反馈」）—— 无 resource-id 的 Lynx 自绘卡，短路其展示/请求策略后**连占位一起消失**，不留空白
- **章节末（v1.9.18）**：「看小视频免30分钟广告」入口行 —— 让生成它的工厂方法返回 `null`，从源头不再生成
- **首页（v1.9.19）**：「仅限当前设备开通7天会员」整屏 VIP 促销浮层
- 阅读页右上角「700金币」自绘小面板（按 viewId `h80` + 位置校验隐藏）
- 右侧悬浮「立即领取」金币球
- 首页顶部「直播」tab、首页浮动「登录领取」红包
- 章节页「看小视频免30分钟广告」提示链接、「2500金币待领取」入口
- 听歌页「全天畅听」广告卡、「看小视频免广告」横幅
- 听歌页 MusicAdUnlockTimeView（广告解锁倒计时条）
- 全屏覆盖型广告自动检测与隐藏

### 弹窗拦截
- 类名匹配：luckycat / 更新升级 / 广告弹窗 / 会员促销（`Promo` `Promotion` `VipPromo` `VipPopup`）/ 广告解锁弹层（`admodule` `unlocktime`，对应 `com.dragon.read.admodule.adfm.unlocktime.ui.*` 共 8 个）
- 内容匹配：「签到」「听歌领金币」「领取+金币」类弹窗自动关闭
- **DialogFragment 拦截（v1.9.5）**：章节末「看小视频免30分钟广告」等广告弹窗是 DialogFragment（不走 `Dialog.show()`），hook `androidx.fragment.app.DialogFragment.show()` + 已知广告 Fragment 的 `onCreateView` 直接阻止渲染
- **广告文本实时过滤**：hook `TextView.setText()`，广告文本被设置的瞬间即隐藏（覆盖 App 切换智能朗读/真人讲书等模式重建 View 的场景，零扫描零卡顿）
- **服务端下发促销浮层（v1.9.22）**：听书页「仅限当前设备开通7天会员，限时有效」这类整块浮层是 Lynx/自绘 Dialog，**整层没有任何 `TextView`**、文案只挂在 View 的 `content-desc` 上，且文案不在 APK 内。改为扫整棵弹窗子树的 **`content-desc` + `text`**，并在 `Dialog.show` 后 **0/300/800/1600/2800 ms 多点重扫**（Lynx 异步渲染）。护栏**故意不做尺寸判定** —— 实测该弹层窗口 decor 是整屏 1080×2400 的透明壳，按尺寸判会把弹层当成整页而跳过；改靠**文案特异性**
- **浮动窗兜底（v1.9.22）**：不是所有促销浮层都是 Dialog，有些直接往 `WindowManagerGlobal` 里 `addView`。hook `WindowManagerGlobal.addView`，只处理浮动类型窗口（`TYPE_APPLICATION` / panel / attachedDialog / overlay，**绝不碰 Activity 自身的窗口**），命中即整窗 `removeViewImmediate`（只设 `GONE` 不行：透明模态窗还在，会继续吃触摸，表现为界面「卡死」）
- **Dialog 类名观测日志（v1.9.22）**：把每个真正 `show` 出来的 Dialog 类名记一次（去重），换版本后再遇到漏网弹窗，日志里直接就有类名

### 语音广告拦截
- **「XX金币已到账…」语音播报（v1.9.23）**：听书一段时间后突然冒出的语音广告是字节 **Polaris（增长/激励体系）** 下发的、**下载的 `.aac`**（不是 TTS）。在播放入口源码级短路：`com.bytedance.polaris.impl.voice.w.a|o` + `com.bytedance.polaris.impl.audio.AudioHelper.s|r|j`，且**只短路返回 `void` 的重载**（`boolean`/`String` 这类策略查询一律不碰，不会破坏调用方状态机）

### 广告拦截（v1.9.9 增强）
- **听歌页贴片广告**：番茄的「贴片广告」由字节 Lynx（`com.ss.android.mannor`）模板渲染，装在 `MusicPatchAdContainer` 里，**没有固定资源 id**。模块改为**视觉层面**拦截：hook 该容器构造 + `View.setVisibility`，只置 `GONE`、绝不 `removeView`（`ViewStub` 只能 inflate 一次，remove 会抛异常），彻底消除「小视频 + 商家推荐 + 倒计时」广告卡

### 页面拦截
- 商城 Activity、luckycat 激励页、开屏广告、沉浸式广告、免费听广告页、广告解锁页等启动即 finish
- AdUnlockTimeDialogManager 弹窗拦截（源码级 hook）
- 激励广告 SDK 调用拦截（showAd / preloadAd 等）
- 广告倒计时 View 创建即 GONE（MusicAdUnlockTimeView / AdUnlockTimeFloatingView）

---

## 安装步骤

1. 手机已 **Root**，并装好 **LSPosed** 或 **Vector**（两者任选其一）
2. 安装本模块 APK
3. 打开 LSPosed / Vector 管理器，**启用本模块**
4. 在模块的**作用域**里勾选 **番茄畅听**
5. 强制停止番茄畅听（或重启手机）后重新打开，即可生效

> 不需要登录账号 —— 去广告与界面净化直接生效；想要完整 VIP 免听体验再登录即可。

## 🛠 构建

三套构建方式，**日常出包直接推 GitHub 就行**（方式三全自动，不需要本机装任何工具链）：

### 方式一：宿主机本地构建（方式三在 CI 上跑的就是它）

```bash
bash module/build_local.sh        # 产物：module-local.apk，可直接 adb install -r
```

- **依赖**：JDK（自带 `javac`）+ Gradle 缓存里的 `android.jar` / `apksig` / `bouncycastle` + 一份 `r8.jar`（d8 就在其中，可从 `https://dl.google.com/dl/android/maven2/com/android/tools/r8/` 下载）
- **做法**：编译 → d8 出 `classes.dex` → 替换进**上一版已签名 APK**（保留其资源与清单，省掉 aapt2 重打包）→ 就地补丁二进制清单里的 `versionCode`/`versionName` → 用**同一个 keystore** 重新做 v1+v2 签名，所以能直接 `adb install -r` 覆盖安装
- **可覆盖的环境变量**：`ANDROID_JAR` / `R8_JAR` / `APKSIG_JAR` / `BCPROV_JAR` / `BCPKIX_JAR` / `TEMPLATE_APK`（上一版模块 APK，须与本机安装的是同一签名）/ `KEYSTORE` / `WORK`
- 仓库里附带的 `fanqie-enhance-*.apk` 既是成品，也是下一次构建的 `TEMPLATE_APK`
- **模板按版本号取最大的那个**：当前是 `fanqie-enhance-v1.9.23.apk` —— 开发机实测通过的那一版，作为**产物基准**
- ⚠️ **本地产物与 CI 产物不一定是同一个二进制**：`--lib android.jar` 不同 —— 本地默认用 Gradle 缓存里那个**裁剪版**（不含 `java.lang.*` 桩），CI 用官方 platform-34 的**完整版**。d8 因此做出等价的编码选择差异（`filled-new-array` vs `new-array` + `aput-object`、`invokeinterface CharSequence.toString()` vs `invokevirtual Object.toString()`），dex 会差几百字节。两者**功能完全等价**（类 / 方法 / 字符串 / 字段完全相同，只差 1 条 method 引用），但 sha256 不同。若要逐字节复现 CI 产物，把 `ANDROID_JAR` 指向官方 platform-34 的 `android.jar`
- 正因为这个差异，**对外发布的永远是仓库里这份本地构建的模板包**，不是 CI 现场编译的产物（见下文方式三）

### 方式二：手机端 Termux 工具链（仅作备用）

`module/build.sh` 依赖手机上的 `/data/local/tmp/fuck_andes/tool/TERMUX`（`root:root 700`，必须 root 才能用）。换应用版本做适配时它更方便 —— 可以直接在手机上 `aapt2 dump resources` 提取新包的资源 id。

### 方式三：GitHub Actions 校验 + 自动发布

工作流在 [`.github/workflows/build.yml`](.github/workflows/build.yml)，**push 到 `main` 即自动完成全部动作**：

1. 装 JDK 21 → 取 `android.jar`（优先用 runner 自带的 Android SDK，取不到才下官方 platform 包）→ 拉 `r8`/`apksig`/`bouncycastle`
2. **校验发布包**：确认仓库根目录里的 `fanqie-enhance-v{versionName}.apk` 存在，且其二进制清单里的 `versionName`/`versionCode` 与 `module/AndroidManifest.xml` 完全一致，并且含 `classes.dex` 与 v1 签名块（用 [`tools/apkver.py`](tools/apkver.py)）。任何一条不过就**直接失败**
3. 调 `module/build_local.sh` 出包 —— **只作为「源码还能编过」的验证**，产物叫 `ci-rebuild-*.apk`，只进 workflow artifact，**不发布**
4. tag 为 `{versionCode}-{versionName}`：**Release 不存在就建，存在就覆盖资产**，同时同步
   [`Xposed-Modules-Repo/com.eta.fanqie.enhance`](https://github.com/Xposed-Modules-Repo/com.eta.fanqie.enhance)（Vector / LSPosed 读取的发布仓库），主仓库与发布仓库**两边都带 `.sha256`**

> ★ **发布包 = 仓库里那份模板 APK**，也就是你本地构建、真机验证通过后提交进来的那一份。
> CI 不拿自己现场编译的产物去发布 —— 它用的是 runner 自带的官方 `android.jar`，
> d8 在拿到完整 `java.*` 桩时会选不同的等价编码，产物字节跟本地那份对不上。
>
> 所以**发新版的正确顺序**：改代码 → 升 `module/AndroidManifest.xml` 的
> `versionCode`/`versionName` → 本地 `bash module/build_local.sh` → 真机验证 →
> 把 `module-local.apk` 改名成 `fanqie-enhance-v{versionName}.apk` 提交进仓库 → push。
> 漏了最后一步，CI 会在第 2 步直接失败（这是故意的：宁可失败，也不发一个没人验证过的包）。

需要的仓库 secrets（Settings → Secrets and variables → Actions，已配置）：

| Secret | 作用 | 缺失时 |
|---|---|---|
| `MODULE_KEYSTORE_BASE64` | 模块签名 keystore（`module.keystore`）的 base64 | 只影响第 3 步那个**不发布**的编译验证产物（改用临时密钥签名）；**不影响发布**，发布包自带签名 |
| `RELEASE_REPO_TOKEN` | 对发布仓库有写权限的 PAT | 跳过发布仓库同步 |

> 本模块的版本号（`1.9.x`）与番茄的版本号（`6.7.1.32`）是**两套独立编号**，不要混淆。

### 已知限制
- **番茄更新（即使 versionCode 不变）可能改变混淆类名 / 资源 id**（如 `h80`、`e4q`、`ej2`），届时需按新版本重新适配并升版本号（见上文「适配版本」）
- **「我的」页的活动横幅 `ej2` 是轮换位**：屏蔽是按容器 id 做的，所以后续换成别的活动（春节、双十一…）也会一起消失；若只想屏蔽某一档活动，需要改成按内容/跳转目标过滤
- 正文流的 OneStop 广告卡被短路后，阅读器不再为它预留位置 —— 这是有意为之（避免留白），但如果将来官方把有用内容放进同一容器，也会一起不显示
- 模块只影响 `com.xs.fm` 主进程，不影响其他应用
- 若重装模块后不生效，在管理器里把模块开关关掉再打开一次即可

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
| **广告位总闸（v1.9.11）** | hook `com.dragon.read.base.ad.AdConfigManager.checkAdAvailable(广告位, 类型)`，被动展示型广告位直接返回 false；另 hook `AdLynxHelper.checkIfRitAvailable` 覆盖 Lynx 场景键（含 `reader_lynx_video_ad`） |
| **OneStop 广告策略短路（v1.9.14）** | 阅读页购物卡走 `com.bytedance.tomato.onestop.readerad.strategy.*`，短路 `ReadFlowOneStopAdDisplayStrategy.a()` 与 `ReadFlowOneStopAdRequestStrategy.a()` 返回 false —— 广告在**请求之前**就被否掉，阅读器不会给广告行留占位 |
| **广告入口行工厂拦截（v1.9.18）** | 章节末广告行由 `f22.q.a()`/`b()`/`c()`（`AddShortcutLine`/`ButtonLine`/`BuyVipEntranceLine`）生成，只被阅读页广告行 provider `r23.d.a` 调用、调用点均判空；工厂返回 `null` → App 自行跳过「添加该行」，不报错也不留空白 |
| **VIP 促销浮层短路（v1.9.19）** | 首页整屏促销层由 H5/Lynx 经 JSBridge 调 `com.dragon.read.hybrid.bridge.modules.vip.a.showVipPromotionPopup()` 弹出（Dialog 类名 `a13.d0` 是纯混淆名，命中不了类名白名单）；短路该方法 + 对促销 Dialog `show()` 兜底 |
| **「我的」页头部定向屏蔽（v1.9.20）** | 头部入口全在 AppBar 内，`hideChain` 的整链放弃保护会让文本规则变成空操作。`hideMineEntryCard()` 先确认 `ce4` 存在（其他页面无此 id）再整卡 `GONE`；文本侧 `hideMineHeaderEntry()` 只藏「可点击入口卡」，带 25% 屏高护栏，**绝不越过 AppBar** |

完整源码见 [`MainHook.java`](module/src/com/eta/fanqie/enhance/MainHook.java)（单文件实现，约 2900 行）。

---

## 目录结构

```
├── README.md                    ← 你正在看的
├── DISCLAIMER.md                ← 免责声明全文
├── CHANGELOG.md                 ← 版本历史
├── RELEASE_NOTES_v1.9.23.md     ← 最新版发布说明（含适配版本 + 逐项拦截清单）
├── REVERSE_ENGINEERING_v1.9.23.md ← 广告面逆向报告（规模统计 / 已封堵链路 / 刻意不封堵的）
├── RELEASE_NOTES_v1.9.20.md     ← 历史版本发布说明
├── .github/workflows/build.yml  ← push 即自动打包 + 发布 Release（主仓库 + 模块发布仓库）
├── tools/                       ← 工具脚本（apkver 版本校验 / adscan / dexsig / dexfind / dexdump / dexdis）
└── module/
    ├── src/                     ← Java 源码（MainHook.java + Xposed stub）
    ├── build.sh                 ← 手机端构建脚本（Termux 工具链，需 root）
    ├── build_local.sh           ← 宿主机本地构建脚本（**不需要 root，推荐**）
    └── out/module.apk           ← 成品
```

> ⚠️ 仓库根目录的 `fanqie-enhance-v*.apk` 有**两个身份**：既是发布出去的成品，也是下一次构建的模板
> （本模块自己没有资源，模板只提供图标 / `assets/xposed_init` 等资产 + 清单骨架）。
> `build_local.sh` 默认按版本号取最大的那个当模板；CI 则**显式指定「本次要发布的那一版」**当模板。
>
> **当前版本对应的那个包（现在是 `fanqie-enhance-v1.9.23.apk`）绝对不能删** ——
> 它是对外分发的那个二进制，删了 CI 的「校验发布包」会直接失败。
> 更早的历史版本包删掉只影响「本地构建时挑不到旧模板」，CI 不受影响。

## 许可证

MIT License — 详见 [LICENSE](LICENSE)

**再次提醒：仅供学习交流，请在 24 小时内自行决定是否保留，支持正版。**
