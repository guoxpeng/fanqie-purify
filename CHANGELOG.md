# 更新日志

所有重要变更记录于此。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [CI] 自动打包 / 自动发布 - 2026-09-21

### 新增
- `.github/workflows/build.yml`：push 到 `main` 即自动构建模块 APK → 上传 workflow artifact → 维护 `{versionCode}-{versionName}` Release（不存在则建、存在则覆盖资产）→ 同步 [Xposed-Modules-Repo/com.eta.fanqie.enhance](https://github.com/Xposed-Modules-Repo/com.eta.fanqie.enhance) 发布仓库
- 仓库 secrets：`MODULE_KEYSTORE_BASE64`（官方签名密钥，产物可直接覆盖安装）、`RELEASE_REPO_TOKEN`（发布仓库写入）

### 修复
- **工作流此前每次都卡在「准备 android.jar」**：runner 从 `dl.google.com` 拿到的是非 zip 的错误页，`unzip` 直接失败、后面步骤全部跳过 —— 这就是 Release 一直停在旧版本的原因之一。改为优先用 runner 预装的 `android-34` SDK（零下载），下载兜底路径加 `unzip -t` 校验
- **没有签名密钥导致永远不发布**：`MODULE_KEYSTORE_BASE64` 未配置时工作流只能用临时密钥签名并跳过 Release。现已配置，`gh release create` 在 tag 已存在时会退回 `--verify-tag` 重试

## [v1.9.11 ~ v1.9.20] - 2026-09-21

### 适配
- **番茄畅听 `6.7.1.32`（versionCode `671`）实测通过**（上一适配版本 `6.7.1.16`）
  - 两版 `versionCode` 相同 ⇒ 混淆「名字表」未变（`gxi`/`bwf`/`gtr`/`f22.q` 等名字依然存在），但**资源 id 数值整体平移**：`PRE_HIDE_RES_IDS` 已按新包重新提取（`gxi`→`0x7f102989`、`a3c`→`0x7f1004c5`、`fp_`→`0x7f1022e9` 等）
  - 「我的」页换成了一组新 id：`bpz`→`br7`（VIP 促销卡）、`e33`→`e4q`（我的资产板块）、`ccv`→`ce3`/`ce4`（快捷入口栏 / 头部入口卡片）
  - 结论写进 README：**不能因为 versionCode 没变就认为不用适配**
- 新增 `module/build_local.sh`：**宿主机本地构建**（JDK + Gradle 缓存里的 `android.jar`/`apksig`/`bouncycastle` + `r8.jar`），替换 `classes.dex` 后用同一 keystore 重签 v1+v2，**不再依赖手机 root 与 Termux 工具链**

### 新增（拦截）
- **阅读页正文流「一站式购物卡」（v1.9.14）**：如「XX家具超市 + 反馈」，是字节 OneStop（Lynx）自绘，整棵子树无 resource-id、也不经过 `AdConfigManager` / `AdLynxHelper`，旧规则全部漏过。改为短路 `ReadFlowOneStopAdDisplayStrategy.a()` 与 `ReadFlowOneStopAdRequestStrategy.a()` → 广告在**请求之前**就被否掉，阅读器不再给广告行留占位（**连空白一起消失**）
- **章节末「看小视频免30分钟广告」入口行（v1.9.18）**：文案来自布局字符串资源、阅读器每次翻页都会重建，靠「文末 GONE 一下」拦不住。逆向定位到工厂 `f22.q.a()`/`b()`/`c()`（`AddShortcutLine` / `ButtonLine` / `BuyVipEntranceLine`），三者只被阅读页广告行 provider `r23.d.a` 调用且调用点均判空 → 让工厂返回 `null`，App 自行跳过「添加该行」，不报错也不留空白
- **首页「VIP 促销全屏浮层」（v1.9.19）**：H5/Lynx 经 JSBridge 调 `com.dragon.read.hybrid.bridge.modules.vip.a.showVipPromotionPopup()` 弹出整屏促销层，其 Dialog 类名 `a13.d0` 是纯混淆名、命中不了原有类名白名单 → 短路该 bridge 方法 + 对促销 Dialog `show()` 兜底
- **广告位总闸（v1.9.11）**：hook `AdConfigManager.checkAdAvailable(广告位, 类型)`，被动展示型广告位直接返回 false；并 hook `AdLynxHelper.checkIfRitAvailable` 覆盖 Lynx 场景键（含 `reader_lynx_video_ad`）
- **「我的」页四类入口（v1.9.20）**：我的消息 / 游戏中心（快捷入口栏 `ce3` 内的 `u2` 入口卡）、我的资产板块（`e4q`，含提现 / 金币余额 / 现金余额 / 剩余时长）、活动横幅（`ej2`，实测「中秋好礼限时购 · 一件立减15%>」）

### 修复
- **「我的」页头部入口「文本命中却不隐藏」**：这些入口都在页头 AppBar 内，`hideChain()` 开头的 `isAppBarLike` 整链放弃保护会直接 return（日志：`跳过隐藏(位于顶栏内): '游戏中心'`）。新增 `hideMineHeaderEntry()`：绕过顶栏保护，但只就近隐藏「可点击入口卡」，并加 25% 屏高护栏
- **避免「整块页头消失」回归**：「我的资产」不再走文本兜底（那条路会一路爬到页头包装容器 `e2n`，把头像 / 昵称 / 个人主页一起吞掉，页面顶部留大片空白），改为按精确资源 id `e4q` 隐藏
- **只藏子项会留下空槽**：`ce3`（入口栏）+ `ej2`（横幅）都藏掉后，`ce4` 卡片背景仍占 1008×156 → 改为整张 `ce4` GONE，高度随之收起
- **横幅是纯图片、文本规则抓不到**：`ej2` 内的「中秋好礼限时购」是画进图片里的字，节点上既无 text 也无 content-desc，只能按容器 id 屏蔽
- `XC_MethodHook.MethodHookParam` 补上 `method` 字段（Xposed stub 缺失，导致诊断代码无法编译）

### 文档
- README：适配版本表 / 历史表更新到 `6.7.1.32`；新增「🛠 构建」章节；功能列表与工作原理表补齐 v1.9.11~v1.9.20 的拦截项
- 新增 `RELEASE_NOTES_v1.9.20.md`


## [v1.9.5] - 2026-08-28

### 新增
- 屏蔽「借钱」「我的公益」入口（shouldHide 文本规则）
- 听歌页广告拦截增强：按资源 ID `a3c`/`fp_` 精确隐藏 MusicAdUnlockTimeView 和广告卡片
- **DialogFragment 广告弹窗拦截（重点）**：APK 逆向定位章节末「看小视频免30分钟广告」= `ReaderInspireDialogFragment`（DialogFragment，不走 `Dialog.show()`）；hook `androidx.fragment.app.DialogFragment.show()` + 4 个广告 Fragment `onCreateView` 返回 null
- **广告文本实时过滤**：hook `TextView.setText()`/`setContentDescription`，广告文本设置瞬间隐藏（覆盖模式切换重建，零扫描）
- **`View.setVisibility` 拦截**：已知广告 View 设 VISIBLE 时强制 GONE（防重建闪现）

### 改进（性能）
- **移除粗暴扫描**：删除全树 View 遍历探测（scanUnknownClickables/forceScanAdText），改为源码级 hook
- `hideAll()`/`OnGlobalLayout` 限频 1.2s，避免卡顿
- **短类名匹配误伤修复**：`"h"` 误伤阅读器渲染器 `com.dragon.reader.lib.drawlevel.view.h`（阅读页文字被隐藏），改全限定类名匹配

### 适配
- 番茄畅听 6.6.7.32 (versionCode 667)
- 版本号语义化 1.9.5 (versionCode 19500)

## [v1.9.10] - 2026-09-11

### 适配
- **番茄畅听 `6.7.1.16`（versionCode `671`）实测通过**：逐一核对模块用到的 16 个混淆类名 / 资源 id，全部仍存在于 6.7.1.16（含 `h80` / `bpz` / `e33` / `ccv` / `c1` / `e10` 等）
- 模块版本号 `1.9.10`（versionCode `191000`）
- 文档补充**适配版本历史**：`6.7.1.16 (671)` / `6.6.7.32 (667)` / `6.6.4.32 (664)` 三版适配记录，明确后两版为「此前已验证、当前不再维护」

### 修复
- **未登录时「我的」页头部整块消失**（下拉才短暂出现，上滑到「全部」一行就没了）
  - 根因：未登录头部文案命中 `shouldHide()` 中过宽的 `t.contains("领取") && t.length() <= 6` 规则，随后 `hideEntry → hideChain` 向上连藏 3 层父容器，把 `#h4z`（头像 + 昵称 + 登录按钮）和 `#e10`（整个头部 `LinearLayout`）一并 GONE
  - `#e10` 的类名是普通 `android.widget.LinearLayout`、不含 AppBar，`isProtectedContainer()` 的类名白名单拦不住它；真正受保护的 `#c1`（`CommonCustomAppBarLayout`）在更外层，等循环走到它时头部内容早已被隐藏
  - 修复：`hideChain()` 开头新增「整链放弃」判定 —— 用新增的 `isAppBarLike()` 沿 `start` 父链一路向上找 `AppBar`/`Toolbar`/`ActionBar`/`TabLayout`，只要命中就整条链放弃，一帧都不动

### 免登录（重要）
- **确认：去广告与入口隐藏完全不依赖登录账号**
  - 在完全未登录（清空 `prefix_private_acct_user_info_cache` MMKV）状态下实测：日志正常输出 `已patch userModel: isVip=true freeAd=true leftTime=999999999`；首页 / 我的页 / 听歌页的广告位、金币球、福利入口均正常隐藏
  - 说明：VIP 状态**可以**在未登录（游客态）下写入，但实际能否试听 VIP 内容仍由番茄**服务端**校验决定，模块不绕过服务端
  - README 新增「免登录说明」独立章节

### 文档
- README / SUMMARY / CHANGELOG 全面更新，突出**适配版本号 `6.7.1.16`（`671`）**
- 修正安装步骤：删除「必须先登录」的过时要求（顺序放宽为 装模块 → 启用作用域 → 强停重启）
- 修正生效验证方式：模块日志**不在 logcat**，改指 `/data/adb/lspd/log/verbose_*.log`（tag `VectorLegacyBridge`）

### ⚠️ 走过的弯路（记录备查，勿重犯）
- 第一版修复用的是「文案含『登录』就放过」的粗粒度白名单。结果「登录领取」既是「我的」页头部的账号入口、又是首页 / 我的页右侧那个浮动红包广告的文案，白名单把广告一起放过了（用户当场反馈「首页的登录领取广告又回来了」）。**结论：必须用结构判定（在不在顶栏里），不能用文案判定。**
- `isAppBarLike()` 故意**不复用** `isProtectedContainer()`：后者把 `"TopView"` 也算受保护，而 `BookMallTopView`（首页搜索栏那一行）类名里含 `TopView`、首页广告位就装在它里面，复用会把首页广告一起放过。

## [v1.9.6 ~ v1.9.9] - 2026-09-10

### 新增
- **贴片广告视觉拦截（v1.9.9）**：听歌页随机出现的「贴片广告」由字节 Lynx（`com.ss.android.mannor`）模板渲染，装在 `MusicPatchAdContainer` 内、无固定资源 id。改为视觉层面拦截：hook 容器构造 + `View.setVisibility`，只置 `GONE`、绝不 `removeView`（`ViewStub` 只能 inflate 一次，remove 会抛异常）
- 源码级拦截解锁时长倒计时：`AdUnlockTimeDialogManager.realShowDialog` 等弹窗入口直接短路；`MusicAdUnlockTimeView`/`AdUnlockTimeFloatingView` 构造即 GONE（听歌页倒计时条 / 悬浮条）
- 右上角金币/领取/免费入口（阅读页「200金币」等）：`hideTopRightWidget` 向上找到含底色的 widget 容器整体 GONE；`scanTopRight` 放宽 TextView 候选
- BLOCKED 新增 `EcCenterActivity`、`adfm.unlocktime.` 前缀；Dialog 文本规则增加「广告」「跳过」
- `hookShortcutCleaner`/`removeAdShortcuts` 桌面快捷方式清理（自 v19 保留）
- **`View.setVisibility` 拦截**：已知广告 View 设 VISIBLE 时强制 GONE（防重建闪现）

### 修复
- **顶部菜单标签误伤（v19 老问题）**：`hideAdCard` 曾把 AppBarLayout（含标签栏）整体 INVISIBLE。v21 加 `isProtectedContainer`（AppBar/TopView/TabLayout/主导航/RadioButton≥2）；v23 升级 `protectedWithin`（候选 3 层内含受保护容器也拒绝）；v24 广告卡候选必须 widget 级（宽度<60%屏宽）——「全天畅听」banner 只藏 142x113 药丸，顶栏无损
- 隐藏动作加 visibility 去重，消除同目标每 300ms 重复日志（1040→1 条）
- 本地构建链修复：stub 补 `XposedBridge.hookAllMethods`/`hookAllConstructors`；`assets/xposed_init` 打包位置修正

### 已知问题
- 设备 PMIC 看门狗脏重启后 PM 状态可能损坏：第三方 App 解析失效/无法启动，需 `install -r` base.apk 重建 resolver；此时 Vector 守护进程可能整个开机周期不注入模块，需 PM 恢复后强停重启目标 App
- 首页搜索栏右侧「全天畅听」胶囊在冷启动首帧仍可能出现极短闪现（构造期已 GONE，疑为 Lynx 广告层叠加渲染，持续跟进）

## [v19.2] - 2026-08-24

### 新增
- 隐藏阅读页右上角「700金币」自绘小面板：该入口为 polaris 阅读器框架自绘（无 text/desc），按 viewId `h80` + 位置校验（顶部18% + 右半屏）精确隐藏，翻页重建自动压住

### 修复
- 「金币」规则不再排除带数字文本（命中「2500金币待领取」等真实文案）

## [v19.1] - 2026-08-24

### 新增
- 章节页「看小视频免30分钟广告」提示链接隐藏（含"广告"+"免/看"规则）
- 浅隐藏策略 `hideShallow()`：金币/免广告入口只藏自身+小父容器，父容器过宽（≥60%屏宽）时只藏自身，防止误伤顶栏

## [v19.0] - 2026-08-22

### 性能
- 悬浮金币球隐藏速度从 ~60s 提升到 ~1s：
  - 首轮扫描 200→120ms
  - 自续轮询：前30s每300ms，之后降频1.5s（WeakReference+isFinishing 自动停止）
  - OnGlobalLayout 限频 300→120ms
  - 扫描深度 28→40 层
- 新增 `scanAllWindows()`：反射 WindowManagerGlobal.mViews 遍历进程内所有窗口根 View，覆盖独立悬浮窗

## [v18.x] - 2026-08-21~24

### v18.2
- 修复触底隐藏误伤底部导航栏：新增 `isNavBar()` 文本级保护（容器同时含≥2个底部tab文本则绝不隐藏）；商城/领现金 tab 一律只藏自身

### v18.1
- 日志去噪：只在真正隐藏时打印
- 直播 tab 只藏单个 TabView 不伤整栏

### v18.0
- OnGlobalLayoutListener 持续压制：下拉刷新/切页重新渲染的面板一出现即再隐藏
- 福利/立即领取触底3层隐藏（整块面板消失不留空壳）

## [v17] - 2026-08-21

### 修复
- **重大**：修复 v16 中 `onCreate()` 无参签名错误导致 hookActivityBlocker 整段注册失败（NoSuchMethodError 被吞、全部功能失效），改为正确的 `onCreate(Bundle)`

## [v16] - 2026-08-21

### 性能
- patchVip 提前至 Activity.onCreate 即试；重试节奏 400ms×10

## [v15] - 2026-08-20

### 新增
- 弹窗内容匹配：签到弹窗 / 听歌领金币 / 领取+金币 类弹窗自动 dismiss（不依赖类名）

## [v14 及更早]

- 会员破解（AcctManager.INSTANCE 反射链，getDeclaredField+setAccessible 解决 INSTANCE 非 public 问题）
- 底部导航 商城/领现金 tab 隐藏
- 我的页入口批量隐藏（资产/金币余额/现金余额/福利/购物车/优惠券/商城）
- 广告页面拦截（BLOCKED 前缀表 finish()）
- luckycat/更新/广告类弹窗拦截
