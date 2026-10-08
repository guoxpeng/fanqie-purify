# 番茄畅听 6.7.2.32 广告面逆向报告（v1.9.23）

目标包：`fanqie_67232.apk`（111 MB，15 个 dex，`resources.arsc` 2.3 MB）
方法：纯静态（自研 `tools/*.py` 直接解析 dex 结构，无 smali 工具）+ 真机取证（LSPosed 日志 / uiautomator / `dumpsys window`）

工具：
| 工具 | 作用 |
|---|---|
| `tools/adscan.py` | 按关键词枚举 dex 里**真实定义**的类（广告 / 弹窗 / 会员促销 / hybrid bridge 四组）+ 字符串池搜索 |
| `tools/dexsig.py` | 打印类的方法签名清单（决定 hook 哪个重载） |
| `tools/dexfind.py` | 按字符串 / 资源 id 反查引用它的类与方法（`--id` / `--str`） |
| `tools/dexdump.py` | 单类的字段 / 方法 / 字符串常量 / 调用表 |

---

## 1. 规模（6.7.2.32 实测）

| 维度 | 数量 |
|---|---|
| 广告相关类 | **5181** |
| hybrid bridge 模块（服务端 H5/Lynx 调原生的入口） | **28** |
| `com.dragon.read.widget.dialog.i` 的**原生弹窗族**子类 | **85** |
| 广告模块弹窗 `com.dragon.read.admodule.adfm.unlocktime.ui.*` | 8 |

> 结论：**逐一按类名封堵不可持续**（一版一变），凡是服务端下发的 Lynx/自绘内容，静态分析根本枚举不到 —— 只能在运行时按**文案 + 结构**兜底。

---

## 2. 已定位并封堵的链路

### 2.1 「仅限当前设备开通7天会员，限时有效」整块浮层
- 真机取证：它出现在 **`AudioPlaySingleActivity` 上方的浮动窗口**（`ty=APPLICATION`、`gr=CENTER`、`surfaceInsets=Rect(96,96,-96,-96)`），
  uiautomator 里整层**没有任何 `TextView`**，只有 `desc=[仅限当前设备开通7天会员，限时有效]`。
- 静态核实：`aapt2 dump resources` 里只有同义的 `string/ab0`（限时有效）、`string/cnx`（随机优惠仅限当前设备使用，不自动续费），
  `--id 0x7f061268` / `0x7f0605a3` 在 15 个 dex 里**零 const 引用** ⇒ 文案是**服务端下发**，APK 内无宿主类可认。
- 另一条已知链路（`com.dragon.read.hybrid.bridge.modules.vip.a` → `new i23.d0` → `com.dragon.read.widget.dialog.i.show()`）
  v1.9.19 起已短路；`i23.d0 extends com.dragon.read.widget.dialog.i extends android.app.Dialog`（已核实继承链），
  说明**所有这类原生弹窗都会经过 `android.app.Dialog.show()`** —— 这是一个稳定的、版本无关的收口点。

### 2.2 「XX金币已到账…」语音广告（用户反馈）
逆向定位到字节 **Polaris（增长/激励体系）语音播报子系统**：

| 类 / 方法 | 作用 | 取证字符串 |
|---|---|---|
| `com.bytedance.polaris.impl.voice.w.a(String,String)` | 金币语音播报入口 | `开始播放语音播报`、`POLARIS_COIN_AUDIO_TIPS`、`v3_goldcoin_box_audio_play` |
| `com.bytedance.polaris.impl.voice.w.o(SentenceTemplate,Map,long,String)` | 模板语音 | — |
| `com.bytedance.polaris.impl.audio.AudioHelper.s(String)/r(long)/j(String)` | 真正下声的底层音频 | `disable_audio_tips`、`coin_tips_last_play_time_lite1/2` |
| `com.bytedance.polaris.impl.voice.n.b()` | 关闭播报 | `已关闭金币播报` |
| `v11.k0.i()/o()/s()` | 奖励 tips 请求方 | `warningtone_template_1_default_1.aac`（**下载的 .aac，不是 TTS**） |

⇒ v1.9.23 在 `w.a` / `w.o` / `AudioHelper.s|r|j` 上短路（只短路返回 void 的重载）。
真机日志：`✅ 金币语音播报屏蔽已挂载` + `🔇 已拦截语音播报: com.bytedance.polaris.impl.audio.AudioHelper.j`

---

## 3. 刻意**没有**封堵的（避免误伤用户自己的利益）

| 类 | 原因 |
|---|---|
| `com.bytedance.polaris.impl.rewardpopup.g3 / j` | 奖励领取弹窗，是**用户自己的金币**，封了等于扣钱 |
| `com.bytedance.polaris.impl.lottery.DailyLotteryKmpPopupDialog` | 每日抽奖 |
| `com.bytedance.polaris.impl.luckyservice.xbridge.dialog.FortuneShareDialog` | 分享得福利 |
| `com.bytedance.polaris.impl.shortcut.j`、`com.bytedance.polaris.impl.push.o` | 桌面/推送相关，非广告位 |
| 桌面长按快捷方式保留的 1 条 | 非广告项 |

不过它们**依然走 `android.app.Dialog.show()`**，所以只要内容命中 `isDialogBad()` / `isPromoPopupText()` 的文案规则，
仍会被内容级兜底收掉（规则刻意避开「金币 / 奖励 / 签到」这类用户主动想看的词）。

---

## 4. 新增封堵（v1.9.23）

- Dialog 类名规则补 `admodule` / `unlocktime` ⇒ 覆盖 `com.dragon.read.admodule.adfm.unlocktime.ui.*`（8 个广告解锁弹窗）。
- 内容级兜底（v1.9.22）：扫整棵弹窗子树的 **`content-desc` + `text`**，多点重扫（0/300/800/1600/2800 ms），
  外加 `WindowManagerGlobal.addView` 悬浮窗兜底（只处理 `type=2/panel/attachedDialog/overlay`，绝不碰 activity 自身窗口）。
- 观测日志（v1.9.22 起）：`Dialog.show 观测到类: xxx`（去重）—— 遇到新的漏网弹窗，日志里直接给类名。

## 5. 下次要查的方向（留给下一次「主动出击」）

1. **开屏弹窗** `com.dragon.read.pages.splash.j2`（`dialog.i` 子类，可能服务端控制是否弹）——目前未封。
2. **hybrid bridge 弹层入口**：`--list-bridges` 只有 28 个，可逐个 dump 找「show 原生弹层」的方法（已知 `vip.a.showVipPromotionPopup`，其余待查）。
3. **silk subwindow** 通道：`com.bytedance.component.silk.road.subwindow.manager.c.e()` 已 hook，但只按类名启发式；可改成「先看内容再决定」。
4. **`disable_audio_tips`**：`AudioHelper` 里读的 AB/settings key。若拿到它的读取入口，从开关层面关掉语音播报比短路播放更干净。
