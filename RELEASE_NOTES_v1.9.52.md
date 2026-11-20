# v1.9.52 发布说明

> 适配：番茄畅听 **6.8.0.32**（versionCode **680**）实测通过 ｜ 设备 Motorola / Android 15 ｜ Vector（LSPosed 兼容）
> 本版把 **v1.9.24 → v1.9.52** 的积累一次性收口。主功能仍是**广告 / 营销净化**，另外附上「顶部黑条」的完整排查结论。

## 一、广告净化（v1.9.24 – v1.9.38 积累）

| 能力 | 入口 | 说明 |
|---|---|---|
| 章节结束语音广告 | `hookAudioAdManager()` | 拦 `AudioAdManager` / TTS 播报链路 |
| 金币播报音频源头 | `hookPlayerUrlBlocker()` | 语音广告是服务端下发的 `.aac`（`novel-tts` / `warningtone_*.aac`），按 URL 特征短路 |
| 首页推荐悬浮窗 | `hookMainRecommendFloat()` | 双 11 一类运营浮窗，从源头不创建 |
| 畅听时长 / 全天畅听 | `hookListenTimeGate()` | 点听书不再被「时长已用完」拦停 |
| 赚金币浮层 | `hookCoinSheetPages()` | 拦截 680 版从下往上占满屏的金币浮层 |
| 听书页购物/金币球 | `hookTextViewAdFilter` 家族 | 按**父链内容特征**定位，不依赖坐标与资源 id |
| 676 / 680 适配 | — | 新增被动展示位拦截；含「智能朗读 / 真人讲书」的顶栏整条不隐藏 |

修复：`speak()` 两个重载塞在同一个 `try` 里导致第二个签名永远挂不上（v1.9.30）。

## 二、已回滚的实验（避免重踩）

- **v1.9.38 给播控通知注入 MediaStyle → 已彻底移除**。注入会额外造出一个空壳媒体通知，反而**多出一条黑条**；v1.9.39 关闭，v1.9.40 试过「克隆原通知再注入」也被实测证伪，代码里保留 `false` 开关并注明「永不再开」。
- **v1.9.42 移除灵动岛 opt-out**。干净 A/B（模块 0 条 hook 日志）下，黑条像素宽度与装模块时**完全相同**，证明黑条不是模块画出来的。

## 三、顶部黑条到底是什么（结论）

> **它是 Motorola 系统 UI 的「媒体灵动岛」折叠态 `LiveContentPeek`**，既不是模块画的，也不属于番茄。

| 观测项 | 结果 |
|---|---|
| 像素测量 | 黑条 x65–1374 / y30–139，纯黑（RGB < 40），与 `LiveContentPeek` 窗口 frame `72,18–1368,142` 吻合 |
| SurfaceFlinger | `LiveContentPeek#4138 flags:0 a:1`，alpha=1 **确在渲染**（`dumpsys window` 的 `isVisible=false` 是假象） |
| 归属 | 番茄进程在 → 有；`force-stop com.xs.fm` → 立刻消失；YouTube 无媒体通知 → 从不出现 |
| 内容来源 | 只读通知 extras 的 `motorola.livecontent.peekTitle / peekContent / peekIcon` |
| 第三方能否写 | **不能**：同一份通知里 `android.title / android.text` 完整到达系统，`motorola.*` 被框架整体丢弃 |
| 空内容后果 | 文字/图标全空 → 露出 `LiveContentContainerView` 的深色默认背景 `normalColorRes=0x01060B3D`（深色模式即纯黑） |

**所以对第三方音频 App，这层永远是「有壳无内容」= 黑条**。v1.9.43–v1.9.48 陆续验证过
`NotificationManager.notify` / `Service.startForeground` / 标准通知字段 / `flags bit0`，全部无效；
其中**注入 MediaSession token 让下拉通知栏的媒体卡片恢复显示**，这一项保留（见"顺带修好"）。

## 四、实验性功能（默认不激活）

新增 `hookSystemUiLiveContentForFanqie()`，在 **SystemUI 进程**里挂两个点：

1. `LiveContentInteractor.requestAttachMedia(Z)` —— 番茄播放中把 `true` 改 `false`，不挂浮层；
2. `LiveContentContainerView.getNeedBackground()` —— 番茄播放中返回 `false`，不铺深色底。

两条都只在「活动媒体会话包名 = `com.xs.fm` 且状态 PLAYING」时动手，其他 App 放行。

- **默认不生效**：模块 `xposedscope` 仍只有 `com.xs.fm`。
- 想试用需手动加作用域并重启 SystemUI：
  ```bash
  su -c '/data/adb/lspd/cli scope add com.eta.fanqie.enhance com.android.systemui/0'
  su -c 'killall com.android.systemui'
  ```
  撤销：`su -c '/data/adb/lspd/cli scope rm com.eta.fanqie.enhance com.android.systemui/0'`
- ⚠️ **状态：未完成验证**。实测 `requestAttachMedia` 在番茄播放时并未被调用（系统还有别的挂载路径），
  `getNeedBackground` 抑制方案在收尾时被打断、未取得最终画面证据。**不推荐普通用户启用。**

## 五、验证过的替代方案（不动模块）

向 Motorola 私有设置库写 `live_content_type_disabled=media`，黑条立刻消失：

```bash
su -c 'content insert --uri content://com.motorola.android.providers.settings/secure \
  --bind name:s:live_content_type_disabled --bind value:s:media'
# 回滚
su -c 'content delete --uri content://com.motorola.android.providers.settings/secure \
  --where "name=\"live_content_type_disabled\""'
```

代价：**所有 App 的媒体胶囊都不显示**；通知类灵动岛（计时器 / 导航 / 外卖）不受影响。

## 六、顺带修好

- 下拉通知栏的媒体卡片能正常显示番茄的书名 / 作者 / 封面（此前为空）。
- 顶层 `xposedscope` 增加 SystemUI 分支后，模块对非目标进程**立即 return**，不影响其他应用。

## 升级方式

```bash
adb install -r fanqie-enhance-v1.9.52.apk
```

覆盖安装即可。升级后请 **force-stop 番茄畅听再打开**，hook 才会在新进程生效。
