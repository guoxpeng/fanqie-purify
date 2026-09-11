# 番茄畅听净化模块 v1.9.2 (versionCode 19200)

> ⚠️ **本文档为历史版本说明**。最新版本见 [RELEASE_NOTES_v1.9.10.md](RELEASE_NOTES_v1.9.10.md) —— 适配番茄畅听 **6.7.1.16（versionCode 671）**。

> 目标应用：番茄畅听 com.xs.fm（实测 6.6.4.32 / versionCode 664）
> 框架要求：Root + Vector / LSPosed（xposedminversion 82，Android 8.0+）

## 本版更新

### 新增
- **隐藏阅读页右上角「700金币」小面板**：该入口为 polaris 阅读器框架自绘 View（无 text/contentDescription），按 viewId `h80` + 位置校验（顶部 18% + 右半屏）精确隐藏，翻页重建后自动再次压住

### 改进
- 「金币」文本规则不再排除含数字文案，命中「2500金币待领取」等真实变体

## 历史版本要点（v14 → v1.9.1）

- VIP 免听：反射 AcctManager 写入 isVip/freeAd/过期时间 2099
- 底部导航隐藏「商城」「领现金」，保留 首页/听歌/我的
- 我的页隐藏：我的资产、金币余额、现金余额、福利面板、购物车、优惠券、商城入口
- 章节页隐藏「看小视频免30分钟广告」链接、「2500金币待领取」入口（浅隐藏策略防误伤顶栏）
- 右侧悬浮「立即领取」金币球：全窗口扫描（WindowManagerGlobal），启动约 1 秒内消失
- 弹窗拦截：签到 / 听歌领金币 / 领取+金币 类弹窗自动关闭；luckycat、更新升级、广告弹窗按类名拦截
- 页面拦截：商城 Activity、luckycat 激励页、开屏广告等启动即 finish

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

## 免责声明

本项目仅供学习与技术交流，不修改番茄畅听 APK 本体、不联网、不上传数据。会员权益属付费服务，请支持正版。详见仓库 [DISCLAIMER.md](https://github.com/guoxpeng/fanqie-purify/blob/main/DISCLAIMER.md)。
