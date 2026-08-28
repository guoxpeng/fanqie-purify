# 番茄畅听净化模块 v1.9.5 (versionCode 19500)

> 目标应用：番茄畅听 com.xs.fm（实测 6.6.7.32 / versionCode 667）
> 框架要求：Root + Vector / LSPosed（xposedminversion 82，Android 8.0+）

## 本版更新

### 新增
- **屏蔽「借钱」「我的公益」入口**：「我的」页及各页面出现时自动隐藏（shouldHide 文本规则）
- **听歌页广告拦截增强**：按资源 ID `a3c`/`fp_` 精确隐藏广告位 + hook 构造函数防重建
- **DialogFragment 广告弹窗拦截（重点修复）**：
  - APK 逆向定位：章节末「看小视频免30分钟广告」= `ReaderInspireDialogFragment`（DialogFragment，不走 `Dialog.show()`，旧逻辑全部漏掉）
  - hook `androidx.fragment.app.DialogFragment.show()` 两个重载，类名含 ad/inspire/interrupt 关键词短路
  - 精确 hook 4 个广告 Fragment 的 `onCreateView` 返回 null 阻止渲染：`ReaderInspireDialogFragment` / 听书 `InspireDialogFragment` / `InterruptAdReaderDialogNew` / `ReaderRuleDescriptionFragment`
- **广告文本实时过滤**：hook `TextView.setText()` + `View.setContentDescription`，广告文本被设置的瞬间隐藏自身及卡片容器（覆盖切换智能朗读/真人讲书等模式重建场景，零扫描零延迟）
- **`View.setVisibility` 拦截**：已知广告 View（`entranceview.h` 全天畅听 / `novelug.progress.b` 300金币）被设为 VISIBLE 时强制 GONE，防重建闪现
- **广告 SDK 源码级拦截**：`AdUnlockTimeDialogManager` 弹窗展示方法、激励广告 showAd/preloadAd 等短路

### 改进（性能）
- **移除粗暴扫描**：删除全树 View 遍历探测（scanUnknownClickables / forceScanAdText），改为源码级 hook
- `hideAll()` 限频 1.2s、`OnGlobalLayout` 限频 1.2s，避免卡顿
- 短类名匹配误伤修复：`"h"`/`"b"` 曾误伤阅读器渲染器 `com.dragon.reader.lib.drawlevel.view.h`（阅读页文字被隐藏），改为全限定类名匹配
- 隐藏策略统一 GONE；`hideAdCard` 保护 `protectedWithin` 3 层防误伤顶栏

### 适配
- 版本号改为语义化 1.9.5（versionCode 19500）
- 适配番茄畅听 6.6.7.32 (versionCode 667)

## 历史版本要点（v14 → v1.9.4）

- VIP 免听：反射 AcctManager 写入 isVip/freeAd/过期时间 2099
- 底部导航隐藏「商城」「领现金」，保留 首页/听歌/我的
- 我的页隐藏：我的资产、金币余额、现金余额、福利面板、购物车、优惠券、商城入口
- 阅读页隐藏：右上角「700金币」小面板（h80 viewId + 位置校验）、左上角金币入口
- 章节页隐藏「看小视频免30分钟广告」链接、「2500金币待领取」入口（浅隐藏策略防误伤顶栏）
- 右侧悬浮「立即领取」金币球：全窗口扫描（WindowManagerGlobal），启动约 1 秒内消失
- 弹窗拦截：签到 / 听歌领金币 / 领取+金币 类弹窗自动关闭；luckycat、更新升级、广告弹窗按类名拦截
- 页面拦截：商城 Activity、luckycat 激励页、开屏广告、激励视频全屏广告页等启动即 finish
- 桌面快捷方式清理：金币/领现金/畅听/福利/领取/赚钱/卸载/存储/领红包等关键词过滤

## 安装步骤（重要，顺序不能错）

1. **安装模块 APK**
2. **打开番茄畅听并登录账号**（未登录拿不到用户模型，VIP patch 不生效）
3. **在 Vector / LSPosed 中启用本模块**，作用域勾选番茄畅听
   - Vector CLI：
     ```
     /data/adb/lspd/cli modules enable com.eta.fanqie.enhance
     /data/adb/lspd/cli scope add com.eta.fanqie.enhance com.xs.fm/0
     ```
4. **强制停止番茄畅听后重新打开**
5. 验证：`logcat | grep FanqieEnhance` 出现 `已patch userModel isVip=true`

## 已知问题

- 听歌页随机出现的视频/插屏广告可能来自 WebView 或独立渲染层，当前覆盖检测有概率漏检
- 部分广告 View 类名随版本混淆变化，需定期更新 hook 目标

## 免责声明

本项目仅供学习与技术交流，不修改番茄畅听 APK 本体、不联网、不上传数据。会员权益属付费服务，请支持正版。详见仓库 [DISCLAIMER.md](https://github.com/guoxpeng/fanqie-purify/blob/main/DISCLAIMER.md)。
