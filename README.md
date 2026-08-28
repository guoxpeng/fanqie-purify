# 番茄畅听净化模块

一个 [Vector](https://github.com/AAswordman/Vector) / LSPosed Xposed 模块：净化番茄畅听（com.xs.fm）的使用体验——破解会员、隐藏营销入口与广告弹窗。

> **当前版本：v1.9.5** ｜ 目标应用：番茄畅听 6.6.7.32 (versionCode 667) 实测通过 ｜ 需要 Root + Vector 或 LSPosed

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
- **VIP 免听破解**：登录后自动将 `AcctUserModel` 写入 VIP 状态（isVip / freeAd / 过期时间 2099 等），可直接试听 VIP 书籍

### 入口净化（隐藏）
- 底部导航「商城」「领现金」tab（保留 首页/听歌/我的 三 tab）
- 「我的」页：我的资产 / 金币余额(币) / 现金余额(元) / 福利面板 / 购物车 / 优惠券 / 商城入口 / 借钱 / 我的公益
- 阅读页右上角「700金币」自绘小面板（按 viewId `h80` + 位置校验隐藏）
- 右侧悬浮「立即领取」金币球
- 首页顶部「直播」tab
- 章节页「看小视频免30分钟广告」提示链接、「2500金币待领取」入口
- 听歌页「全天畅听」广告卡、「看小视频免广告」横幅
- 听歌页 MusicAdUnlockTimeView（广告解锁倒计时条）
- 全屏覆盖型广告自动检测与隐藏

### 弹窗拦截
- 类名匹配：luckycat / 更新升级 / 广告弹窗
- 内容匹配：「签到」「听歌领金币」「领取+金币」类弹窗自动关闭

### 页面拦截
- 商城 Activity、luckycat 激励页、开屏广告、沉浸式广告、免费听广告页、广告解锁页等启动即 finish
- AdUnlockTimeDialogManager 弹窗拦截（源码级 hook）
- 激励广告 SDK 调用拦截（showAd / preloadAd 等）
- 广告倒计时 View 创建即 GONE（MusicAdUnlockTimeView / AdUnlockTimeFloatingView）

---

## 安装步骤（重要）

> ⚠️ **必须先装模块 → 再登录番茄 → 再启用模块作用域**。顺序错了会导致 patchVip 拿不到用户模型、部分功能不生效。

1. **安装模块 APK**
   ```bash
   pm install -r /path/to/fanqie-purify.apk
   ```
2. **打开番茄畅听并登录账号**（未登录时 AcctManager.INSTANCE 为 null，VIP patch 会跳过重试）
3. **在 Vector/LSPosed 管理器中启用模块**
   - Vector CLI 方式：
     ```bash
     /data/adb/lspd/cli modules enable com.eta.fanqie.enhance
     /data/adb/lspd/cli scope add com.eta.fanqie.enhance com.xs.fm/0
     ```
4. **强制停止番茄畅听后重新打开**（模块随目标进程注入，重启目标才生效）
   ```bash
   am force-stop com.xs.fm
   ```
5. 验证：`logcat | grep FanqieEnhance` 应看到 `已patch userModel isVip=true`

### 已知限制
- 卸载后重装模块需**重新 enable + scope add**
- 番茄大版本更新可能改变混淆类名/资源 id（如 h80），届时需更新模块适配
- 模块只影响 com.xs.fm 主进程，不影响其他应用

---

## 工作原理（简述）

| 机制 | 说明 |
|------|------|
| patchVip | 反射 `com.dragon.read.user.AcctManager.INSTANCE.userModel`，写入 isVip/freeAd/expireTime 等字段 |
| hideAll | 每 300ms 轮询 + OnGlobalLayout 监听，遍历 View 树按文本规则隐藏入口 |
| hideChain | 触底隐藏：向上连藏最多 3 层父容器，带页面级/导航栏双重保护防误伤 |
| scanAllWindows | 反射 WindowManagerGlobal.mViews，覆盖独立悬浮窗里的金币球 |
| Dialog blocker | hook Dialog.show()，类名+内容双重匹配后 dismiss |

完整源码见 [`MainHook.java`](module/src/com/eta/fanqie/enhance/MainHook.java)（约 500 行，单文件实现）。

---

## 目录结构

```
├── README.md          ← 你正在看的
├── DISCLAIMER.md      ← 免责声明全文
├── CHANGELOG.md       ← 版本历史
└── module/
    ├── src/           ← Java 源码
    ├── build.sh       ← 构建脚本
    └── out/module.apk ← 成品
```

## 许可证

MIT License — 详见 [LICENSE](LICENSE)

**再次提醒：仅供学习交流，请在 24 小时内自行决定是否保留，支持正版。**
