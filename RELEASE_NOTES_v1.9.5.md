# 番茄畅听净化模块 v1.9.5 (versionCode 19500)

> 目标应用：番茄畅听 com.xs.fm（实测 6.6.7.32 / versionCode 667）
> 框架要求：Root + Vector / LSPosed（xposedminversion 82，Android 8.0+）

## 本版更新

### 新增
- **屏蔽「借钱」「我的公益」入口**：「我的」页及各页面出现时自动隐藏（shouldHide 文本规则）
- **听歌页广告拦截增强**：
  - 按资源 ID `a3c` 精确隐藏 `MusicAdUnlockTimeView`（广告解锁倒计时条），同时 hook 构造函数防止重建
  - 按资源 ID `fp_` 精确隐藏广告卡片容器
  - 全屏覆盖型广告自动检测：非基础布局的大面积可点击 View 自动 GONE
- **广告 SDK 源码级拦截**：hook `AdUnlockTimeDialogManager` 弹窗展示方法（realShowDialog / showDialog 等）
- **激励广告调用拦截**：showAd / preloadAd / requestAd / loadAd 等方法短路返回
- **广告 View 创建即隐藏**：`MusicAdUnlockTimeView` / `AdUnlockTimeFloatingView` 构造函数 hook
- **未知可点击 View 扫描**：5 秒后一次性记录所有无文本可点击 View，便于发现新广告位

### 改进
- 隐藏策略统一改为 GONE（不再 INVISIBLE 占位），菜单自动补位
- `hideAdCard` 保护机制升级：`protectedWithin` 3 层深度检查防误伤顶栏
- 广告卡候选限制为 widget 级（宽度 <60% 屏宽），「全天畅听」banner 只藏 142×113 药丸
- 桌面快捷方式过滤新增「领红包」关键词

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
