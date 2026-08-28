# 更新日志

所有重要变更记录于此。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

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

## [v20/v1.9.6 ~ v1.9.10] - 2026-08-28

### 新增
- 源码级拦截解锁时长倒计时：`AdUnlockTimeDialogManager.realShowDialog` 等弹窗入口直接短路；`MusicAdUnlockTimeView`/`AdUnlockTimeFloatingView` 构造即 GONE（听歌页倒计时条/悬浮条）
- 右上角金币/领取/免费入口（阅读页「200金币」等）：`hideTopRightWidget` 向上找到含底色的 widget 容器整体 GONE；`scanTopRight` 放宽 TextView 候选
- BLOCKED 新增 `EcCenterActivity`、`adfm.unlocktime.` 前缀；Dialog 文本规则增加「广告」「跳过」
- `hookShortcutCleaner`/`removeAdShortcuts` 桌面快捷方式清理（自 v19 保留）

### 修复
- **顶部菜单标签误伤（v19 老问题）**：`hideAdCard` 曾把 AppBarLayout（含标签栏）整体 INVISIBLE。v21 加 `isProtectedContainer`（AppBar/TopView/TabLayout/主导航/RadioButton≥2）；v23 升级 `protectedWithin`（候选 3 层内含受保护容器也拒绝）；v24 广告卡候选必须 widget 级（宽度<60%屏宽）——「全天畅听」banner 只藏 142x113 药丸，顶栏无损
- 隐藏动作加 visibility 去重，消除同目标每 300ms 重复日志（1040→1 条）
- 本地构建链修复：stub 补 `XposedBridge.hookAllMethods`；`assets/xposed_init` 打包位置修正

### 已知问题
- 设备 PMIC 看门狗脏重启后 PM 状态可能损坏：第三方 App 解析失效/无法启动，需 `install -r` base.apk 重建 resolver；此时 Vector 守护进程可能整个开机周期不注入模块，需 PM 恢复后强停重启目标 App

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
