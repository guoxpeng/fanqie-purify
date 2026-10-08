# v1.9.23 发布说明

> 适配：番茄畅听 **6.7.2.32**（versionCode **672**）｜ 实测设备 Realme RMX2202 / Android 14 ｜ Vector(LSPosed)

本版针对两条用户反馈，并做了一次**主动逆向**：

## 1. 「听一段时间会冒出 XX金币已到账… 的语音广告」

逆向 `classes13.dex` 定位到字节 Polaris（增长/激励体系）的语音播报子系统，在**播放入口源码级短路**：

- `com.bytedance.polaris.impl.voice.w.a(String,String)` —— 金币语音播报入口（`POLARIS_COIN_AUDIO_TIPS` / 日志「开始播放语音播报」）
- `com.bytedance.polaris.impl.voice.w.o(SentenceTemplate,Map,long,String)` —— 模板语音
- `com.bytedance.polaris.impl.audio.AudioHelper.s(String)/r(long)/j(String)` —— 真正下声的底层音频

只短路**返回 void** 的重载，`boolean/String` 这类策略查询一律不碰。
该语音是**下载的 .aac**（`warningtone_template_1_default_1.aac`），不是 TTS。

真机日志：`✅ 金币语音播报屏蔽已挂载` + `🔇 已拦截语音播报: com.bytedance.polaris.impl.audio.AudioHelper.j`

## 2. 「屏幕上还有跳出来的广告」（整块会员促销浮层）

真机取证：听书页（`AudioPlaySingleActivity`）上方一个 `ty=APPLICATION` 浮动窗口，
**整层没有任何 `TextView`**，文案只挂在 `content-desc`：`仅限当前设备开通7天会员，限时有效`。
该文案**不在 APK 里**（只能找到同义的 `string/ab0` / `string/cnx`），是服务端下发的 Lynx 内容。

修复：

- `findPromoPopupText()`：扫整棵弹窗子树的 **`content-desc` + `text`**（不再只读 `TextView`，也不再被 16 字上限卡死）
- `Dialog.show` 后 **0/300/800/1600/2800 ms 多点重扫**（Lynx 异步渲染）
- 弹层识别**故意不做尺寸护栏**：实测该弹层窗口 decor 是整屏 1080×2400 的透明壳（真内容只有中间 886×1410），按尺寸判会把弹层当成整页而跳过；改靠**文案特异性**（`PROMO_POPUP_PATTERNS`），且用户主动打开的会员开通页是 Activity 而不是 Dialog，本来就走不到这里
- 悬浮窗兜底 `hookFloatingPromoWindows()`：hook `WindowManagerGlobal.addView`，只处理浮动类型窗口，命中即整窗 `removeViewImmediate`
- 新增 `Dialog.show 观测到类: xxx` 日志（去重），以后漏网弹窗直接看类名

## 3. 顺带

- Dialog 类名规则补 `admodule` / `unlocktime` ⇒ 覆盖 `com.dragon.read.admodule.adfm.unlocktime.ui.*`（8 个广告解锁弹窗）
- 新增逆向工具 `tools/adscan.py`（按关键词枚举真实定义的类 / 搜字符串池）、`tools/dexsig.py`（打印方法签名）
- 完整逆向报告见 [`REVERSE_ENGINEERING_v1.9.23.md`](REVERSE_ENGINEERING_v1.9.23.md)

## 升级方式

直接覆盖安装即可（`adb install -r`）。升级后请**重启番茄畅听**（force-stop 再打开），hook 才会在新进程生效。
