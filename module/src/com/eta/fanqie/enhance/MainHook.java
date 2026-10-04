package com.eta.fanqie.enhance;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 番茄畅听增强模块 v1.9.21（适配 6.7.2.32 / versionCode 672）
 * 基底 = v1.9.5：VIP patch、底部商城/领现金tab隐藏、广告卡整体隐藏、
 *   三级入口隐藏(hideEntry/hideShallow/hideChain)、桌面快捷方式清理、阅读页金币面板、弹窗拦截。
 * 合入 adfix 增强：hookAdSignals 源码级拦截广告SDK调用、更广 BLOCKED 页面前缀。
 * v1.9.6 适配新版本：
 *   - 章节末"看小视频免30分钟广告"旧类 ReaderInspireDialogFragment 已不存在；改为 hook FreeAdConversionDialog（继承 AbsQueueBottomSheetDialogFragment）
 *   - 新增听书章节完毕广告拦截：BookMallAdFeedPlayOverPage / BookMallAdFeedPlayTransView / BookMallAdFeedPlayPage / BookMallAdFeedCloseView 创建即 GONE
 *   - "看剧赚钱红包/看小视频免广告/番茄免费小说"等推广广告：BottomSheet 全屏浮层在 AudioPlaySingleActivity 出现时立即拦截
 *   - 新增「看剧赚钱」「看视频免」「免费小说」「推广」「不要错过」等文本规则
 * v1.9.9 修复「听歌页贴片视频广告」（看小视频免广告 | X秒 + 千问/商家推荐/精彩应用/赶紧下载）：
 *   - 根因：该广告是字节 Lynx 广告引擎（com.ss.android.mannor / OneStop 体系）用**服务端下发的
 *     Lynx 模板**渲染的「贴片广告」，不是普通 View / 不是 Dialog，所以 uiautomator 抓不到、
 *     TextView.setText 文本过滤也无效（广告文案不在 APK 内）。
 *   - 拦截：hookMusicPatchAd() —— 纯视觉拦截，不打断 App 状态机：
 *     ① MusicPatchAdContainer 构造 / setAdPatchView 之后 / onAttachedToWindow 之后 → 一律 GONE
 *     ② View.setVisibility 里对该类名兜底：只要被设为 VISIBLE 就改回 GONE
 *     （不 removeView：容器由 ViewStub 一次性 inflate，摘掉会抛 IllegalStateException）
 *     注：曾尝试让 oh1/a.c()(isAdViewOk) 返回 false，反汇编证实会导致
 *     「正在展示」状态不置位 → 每次切歌重新请求广告，故弃用。
 * v1.9.10 修复「未登录时『我的』页头部整块消失」（下拉才短暂出现的 BUG）：
 *   - 根因：未登录时头部会命中 shouldHide() 里过宽的 `t.contains("领取") && t.length() <= 6`
 *     规则（头部账号入口文案），随后 hideEntry → hideChain 向上连藏 3 层，把 #h4z
 *     (头像+昵称 ConstraintLayout) 和 #e10(整个头部 LinearLayout) 一并 GONE。
 *     #e10 类名是普通 android.widget.LinearLayout，不含 AppBar，所以
 *     isProtectedContainer() 的类名白名单拦不住它；而真正的保护对象
 *     #c1(CommonCustomAppBarLayout) 在更外层，循环走到它才 break 已经太晚。
 *   - 修复：hideChain() 开头新增「整链放弃」判定 —— 先用 isAppBarLike() 沿 start 的
 *     父链一路向上找 AppBar/Toolbar/ActionBar/TabLayout，只要命中就整个放弃，
 *     一帧都不动。
 *   - ⚠️ 走过的弯路（务必不要重犯）：第一版用的是「文案含『登录』就 return false」的
 *     粗粒度白名单。结果「登录领取」既是头部账号入口、又是首页/我的页右侧那个浮动
 *     红包广告的文案，白名单把广告一起放过了（用户立刻反馈"首页的登录领取广告又回来了"）。
 *     所以这里**必须用结构判定（在不在顶栏里），不能用文案判定**。
 *   - isAppBarLike() 故意不复用 isProtectedContainer()：后者把 "TopView" 也算受保护，
 *     而 BookMallTopView（首页搜索栏那一行）类名含 "TopView"、里面就装着首页广告位，
 *     复用会把首页广告一起放过。
 * v1.9.11 新增「广告位总闸」hookAdConfigGate()（参考同类项目 FanqieHook 的思路）：
 *   - 定位：反汇编 classes14.dex 得到
 *     com.dragon.read.base.ad.AdConfigManager.checkAdAvailable(String, String) : boolean
 *   - 做法：按「广告位字符串」在**广告请求/渲染之前**直接返回 false，
 *     一次 hook 覆盖全部被动广告位，天然没有「渲染一帧后才隐藏」的闪烁。
 *   - 只拦被动展示位（splash_ad / page_front_ad / page_middle_ad / reader_banner /
 *     audio_patch_ad / audio_info_flow），用户主动触发的激励视频/金币流程一律放行。
 *   - 同时把所有出现过的广告位打一条日志，便于换版本后重新收集黑名单。
 *   - ⚠️ 不能照搬对方类名：NsAdImpl / NsVipImpl 在畅听里不存在（基线版本不同）。
 * v1.9.21 适配 6.7.2.32（versionCode 672）—— 逐项核对后只需改两个混淆类名：
 *   - 资源 id 全部未变：aapt dump resources 对比 671/672，模块用到的 14 个资源名
 *     （gxi/bwf/gtr/abz/abx/a3c/fp_/br7/gtn/dg3/e4q/e4r/ej2/ce4/h80）id 数值逐一相同，
 *     PRE_HIDE_RES_IDS 与 getIdentifier 规则原样可用。
 *   - 阅读页广告行工厂改名：Lf22/q; → Lf32/q;（a/b/c 仍分别构造 AddShortcutLine /
 *     ButtonLine / BuyVipEntranceLine，且仍只被阅读页广告行 provider 调用）
 *   - VIP 促销弹层改名：a13.d0 → i23.d0（super 仍是 com.dragon.read.widget.dialog.i）
 *   - 其余 hook 目标（AdConfigManager.checkAdAvailable / AdLynxHelper.checkIfRitAvailable /
 *     MusicPatchAdContainer.setAdPatchView / FreeAdConversionDialog / AbsQueueBottomSheetDialogFragment /
 *     OneStop 两个策略类 / bridge modules.vip.a.showVipPromotionPopup）逐一反汇编确认存在，签名未变。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FanqieEnhance";

    private static final String[] BLOCKED = {
            // 广告SDK/页面
            "com.bytedance.ug.sdk.luckycat.",
            "com.ss.android.excitingvideo.",
            "com.dragon.read.ad.dark.ui.",
            "com.dragon.read.ad.exciting.video.",
            "com.dragon.read.ad.immersive.",
            "com.dragon.read.admodule.adfm.landing.activity.",
            "com.dragon.read.pages.splash.ad.",
            "com.dragon.read.reader.speech.ad.",
            "com.dragon.read.pages.freeadvertising",
            "com.dragon.read.admodule.adfm.ecom.EcCenterActivity",
            "com.dragon.read.admodule.adfm.unlocktime.",
            "com.dragon.read.admodule.adfm.inspire.",
            "com.dragon.read.admodule.",
            "com.dragon.read.ad.feedbanner.",                          // v1.9.6 新增：听书播放页广告模块
            "com.dragon.read.ad.freead.",                              // v1.9.6 新增：FreeAdConversionDialog
            "com.dragon.read.ad.openingscreenad.OpeningScreenADActivity", // v1.9.6 新增：开屏广告
            "com.dragon.read.ad.exciting.video.AdBrowserActivity",
            "com.dragon.read.ad.exciting.video.ExcitingDarkAdActivity",
            "com.dragon.read.ad.exciting.video.ExcitingSubmitDialogActivity",
            // 商城/电商
            "com.xs.fm.live.impl.ecom.mall.NativeMallActivity",
            "com.xs.fm.live.impl.ecom.",
            "com.dragon.read.mall.",
            // 任务/金币/福利中心
            "com.dragon.read.task.",
            "com.dragon.read.coin.",
            "com.dragon.read.welfare.",
            "com.dragon.read.wallet.",
            // 全部功能页（含商城/借钱/公益等入口）
            "com.dragon.read.pages.mine.AllFunctionActivity",
            // 邀请/红包
            "com.dragon.read.invite.",
            "com.dragon.read.redpacket.",
    };

    private ClassLoader appCl;
    private int patchTries = 0;
    private final Set<String> seenGold = new HashSet<>();
    private int coinEntryId = -1;
    private int readerPage = 0;
    private Activity readerAct = null;
    private final StringBuilder adHookReport = new StringBuilder();
    private long lastReportLog = 0;
    private final Set<String> clickedSkip = new HashSet<>();
    private long lastSkipClick = 0;
    // 性能缓存
    private boolean vipPatched = false;              // patchVip 成功后不再重复反射
    private Object cachedWmgInstance = null;          // WindowManagerGlobal 单例缓存
    private Field cachedMViewsField = null;           // mViews 字段缓存
    private long lastHideAllTime = 0;                 // hideAll 上次执行时间
    private static final long MIN_HIDE_INTERVAL = 1200; // hideAll 最小间隔 1.2秒，避免卡顿

    private boolean patchAdContainerHooked = false;   // v1.9.9 听歌页贴片视频广告容器 hook 已注册
    private View minePageDecor = null;                // v1.9.20 isMinePage 缓存（同一 decor 只判定一次）
    private boolean minePageFlag = false;
    private Object patchedUserModel = null;           // v1.9.9 已 patch 的 AcctUserModel 实例（登出会换新实例 → 需重新 patch）
    private Set<Integer> cachedHideIds = null;        // v1.9.9 需要「按资源ID强制隐藏」的 viewId 集合（我的页VIP卡/资产卡等）
    /**
     * v1.9.9 听歌页「看小视频免广告」贴片视频广告容器。
     *
     * 该 View 是听歌页根布局 app:id/d4s 的直接子 View，正常时 GONE；
     * App 拉到广告后由 qp2/o0.K()（PatchAdHolderBlock#tryProduceAd）调
     * MusicPatchAdContainer.setAdPatchView(...) 并设为 VISIBLE，内容由
     * com.ss.android.mannor（字节 Lynx 广告引擎）自绘 —— 因此 uiautomator 抓不到、
     * APK 里也 grep 不到「商家推荐/赶紧下载」等文案（模板由服务端下发）。
     */
    private static final String PATCH_AD_CONTAINER_CLS =
            "com.dragon.read.music.player.widget.MusicPatchAdContainer";

    /**
     * v1.9.11 新增、v1.9.12 按 6.7.1.32 重新提取、v1.9.21 按 6.7.2.32 复核：已知广告资源
     * ID 硬编码集合。
     *
     * 说明：6.7.2.32 与 6.7.1.32 相比，资源 id 数值**没有平移**（aapt dump resources 逐项对比
     * 确认模块用到的全部资源名 id 完全一致），所以下面这些硬编码整数原样保留。
     * 提取命令：aapt d resources fanqie_67232.apk | grep -E 'resource 0x[0-9a-f]+ .*id/'
     * 用于 ViewGroup.addView 时的预拦截，在 View 被添加进树之前就直接 GONE，
     * 彻底消除「首帧闪现」问题（事后 setVisibility/GONE 仍会先渲染一帧）。
     * 这些 ID 从 aapt2 dump resources 获得，只对该版本有效；换版本后需重新提取。
     */
    private static final Set<Integer> PRE_HIDE_RES_IDS = new HashSet<>(java.util.Arrays.asList(
            0x7f102989, // id/gxi  主页顶部"全天畅听"入口
            0x7f100e89, // id/bwf  阅读页全天免费畅听中容器
            0x7f1028fe, // id/gtr  阅读页全天免费畅听文本
            0x7f10062d, // id/abz  阅读页300金币 ImageView
            0x7f10062b, // id/abx  阅读页300金币容器
            0x7f1004c5, // id/a3c  听歌页横幅广告
            0x7f1022e9, // id/fp_  听歌页卡片广告
            // v1.9.21 新增：「我的」页四块（按 6.7.2.32 的实机 View 树重新定位）
            0x7f100df5, // id/bsf  我的页 VIP 促销卡片
            0x7f101af2, // id/e87  我的资产 + 邀请好友/好友管理板块
            0x7f101a72, // id/e4q  我的页快捷入口栏（我的消息/优惠券/购物车/商城/游戏中心）
            0x7f10116c  // id/cfe  上者的外包装容器（只藏 e4q 会留下 1008x238 的空白）
    ));

    /**
     * v1.9.11 广告位总闸所在类。
     *
     * 反汇编 6.7.1.16（classes14.dex）确认：
     *   Class descriptor : 'Lcom/dragon/read/base/ad/AdConfigManager;'
     *     name   : 'checkAdAvailable'
     *     type   : '(Ljava/lang/String;Ljava/lang/String;)Z'
     *     access : 0x0011 (PUBLIC FINAL)
     * 该类是单例（Companion.getInstance() / 合成方法 a()），静态字段 b 持有实例、
     * 静态字段 a 是 HashSet（广告位集合）。
     *
     * 定位命令（换版本后重跑）：
     *   dexdump.exe <dex> | awk '/Class descriptor/{c=$0} /name.*: .checkAdAvailable./{print c; print $0}'
     */
    private static final String AD_CONFIG_MANAGER_CLS = "com.dragon.read.base.ad.AdConfigManager";

    /**
     * v1.9.11 需要从源头拦截的「被动展示型」广告位。
     *
     * ⚠️ 设计原则（参考同类项目 FanqieHook 的做法）：**只拦被动展示位**，
     *    用户主动触发的激励视频 / 金币 / 「看广告免广告」流程一律放行，
     *    否则会破坏用户自己的正常操作路径。
     *
     * 下面这些字符串均在 6.7.1.16 的 dex 里实际存在（逐个 grep 验证过）。
     * 换版本后如果某个位置不再出现，直接删掉即可，不影响其他项。
     */
    private static final Set<String> BLOCKED_AD_POSITIONS = new HashSet<>(java.util.Arrays.asList(
            "splash_ad",          // 开屏广告
            "page_front_ad",      // 信息流首屏广告（首页搜索栏上方那个一闪而过的块）
            "page_middle_ad",     // 信息流中插广告
            "reader_banner",      // 阅读页底部 banner
            "audio_patch_ad",     // 听书/听歌页贴片广告
            "audio_patch_pre_ad", // 听书贴片前置广告
            "audio_info_flow",    // 听书信息流广告
            // ⚠️ "book_mall_feed_ad" 已移除：该广告位与听书页正常 UI（tab/三个点菜单）绑定，
            //    拦截会导致正常功能消失。商店图标改用 View 层定位隐藏。
            "mini_game_config",   // 小游戏广告配置
            "free_ad_enter"       // 免费广告入口（看广告解锁等）
    ));

    /**
     * v1.9.12 新增：阅读页 Lynx 广告「场景键」黑名单（6.7.2.32 复核，键名未变）。
     *
     * <p>屏幕上的广告：阅读页正文流里插进来的推广卡（图片 + 标题「曲靖恒源家居购物公司」
     * + 描述 + 「反馈」按钮），bounds≈[140,1452][940,1884]。它**不在 View 树里带资源 id**
     * （uiautomator dump 出来只有一堆无 id 的 ViewGroup，只有 content-desc 有无障碍文案），
     * 因为它由字节 Lynx 广告引擎自绘——运行日志可见 App 拉取缓存 tag
     * {@code reader_lynx_video_ad}，dex 里该字符串正是 gk2/a（TTVideoEngine 配置）
     * 用来选广告场景的键。
     *
     * <p>为什么之前拦不住：模块原有的「广告位总闸」hook 的是
     * {@code AdConfigManager.checkAdAvailable(position, source)}，而 Lynx 广告走的是
     * {@code com.dragon.read.lynx.AdLynxHelper.checkIfRitAvailable(scene, rit)}：
     * 它先把 scene 经 {@code PatchAndInfoFlowAdConfigAdapter.transConfigPositionFromScene(scene)}
     * 翻译成配置位（未知 scene 会落到默认值 {@code audio_patch_ad}），再拿翻译结果去
     * checkAvailable。scene 本身根本没进过 checkAdAvailable，所以黑名单加场景键无效。
     *
     * <p>拦截点：直接 hook {@code AdLynxHelper.checkIfRitAvailable}，命中这些场景键时返回
     * 该方法的「校验不通过」返回值 {@code ad_available_check_rit_disenable}。
     * 这是 App 自己的失败分支：调用方 requestAd 收到非 {@code ad_available_check_ok}
     * 就 {@code listener.onRequestFailed(-4, "rit校验不通过")}，**在广告请求/渲染之前**就中止，
     * 不会打断任何状态机，也不会出现「渲染一帧后才隐藏」的闪烁。
     *
     * <p>只放被动展示型场景：{@code reward_ad}（激励视频）等用户主动触发的一律不在此列。
     */
    private static final Set<String> BLOCKED_LYNX_AD_SCENES = new HashSet<>(java.util.Arrays.asList(
            "reader_lynx_video_ad",   // 阅读页正文流 Lynx 推广卡（本次屏幕上的广告）
            "reading_video_ad",       // 阅读页视频广告
            "series_lynx_video_ad",   // 系列/短剧 Lynx 广告
            "landing_video_ad"        // 落地页 Lynx 视频广告
    ));

    /** v1.9.11 已出现过的广告位（只打印一次，避免刷屏） */
    private final Set<String> seenAdPositions = new HashSet<>();
    /** v1.9.11 已拦截过的广告位（只打印一次） */
    private final Set<String> blockedAdPositions = new HashSet<>();
    /** v1.9.11 广告位总闸是否已挂载 */
    private boolean adConfigGateHooked = false;
    /** v1.9.12 已出现过的 Lynx 广告场景键（每个只打印一次，便于换版本后重新收集） */
    private final Set<String> seenLynxScenes = new HashSet<>();
    /** v1.9.12 Lynx 广告场景拦截器是否已注册（幂等，防重复 hook） */
    private boolean lynxAdHookDone = false;
    /** v1.9.15 已打印过「OneStop 广告策略拦截」日志的 key（策略在每次翻页都会被调用，只打一次避免刷屏） */
    private final Set<String> oneStopBlockedLogged = new HashSet<>();
    /** v1.9.17 已打印过祖先链的广告文本（用于反查入口的宿主类） */
    private final Set<String> loggedAdEntryChain = new HashSet<>();
    /** v1.9.18 阅读页广告入口行工厂是否已挂载 */
    private boolean readerAdLineHooked = false;
    /** v1.9.19 首页 VIP 促销全屏弹层拦截是否已挂载 */
    private boolean vipPromoHooked = false;
    /** v1.9.18 已屏蔽过的广告入口行（只打一次日志） */
    private final Set<String> readerAdLineBlocked = new HashSet<>();
    /** v1.9.21 已打过「构造即 GONE」日志的广告 View 类（这些 View 会高频重复构造，只打一次） */
    private final Set<String> loggedCtorClasses = new HashSet<>();
    /** v1.9.21 已打过「位于顶栏内、跳过隐藏」日志的文案（每 2 秒重复一次，只打一次） */
    private final Set<String> loggedTopBarSkipped = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.xs.fm".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("[" + TAG + "] v1.9.21 加载: process=" + lpparam.processName);
        this.appCl = lpparam.classLoader;
        hookActivityBlocker();
        hookDialogBlocker();
        hookShortcutCleaner();
        hookAdSignals();
        hookKnownAdViews();
        hookDialogFragmentBlocker();
        hookTextViewAdFilter();
        hookListeningChapterAd();      // v1.9.6 新增：听书章节完毕广告拦截
        hookAdPopupOverlay();            // v1.9.6 新增：听书页广告浮层兜底拦截
        hookAbsQueueBottomSheetDialog(); // v1.9.7 新增：拦截语音领金币等走 silk subwindow 的 BottomSheet 广告
        hookSilkSubwindowManager();      // v1.9.7 新增：拦截页面弹窗广告（silk subwindow 中央调度）
        hookMusicPatchAd();              // v1.9.9 新增：拦截听歌页 Lynx 贴片视频广告（看小视频免广告）
        hookAdConfigGate();              // v1.9.11 新增：广告位总闸（源码级，从源头拦截被动广告位）
        hookLynxAdBlocker();             // v1.9.12 新增：阅读页 Lynx 广告场景拦截（reader_lynx_video_ad 等）
        hookOneStopReaderAd();           // v1.9.14 新增：从源头关闭阅读页 OneStop 一站式广告（去空白）
        hookReaderAdLineFactory();       // v1.9.18 新增：从源头屏蔽章节末广告入口行（看小视频免30分钟广告等）
        hookVipPromotionPopup();         // v1.9.19 新增：从源头屏蔽首页 VIP 促销全屏弹层
        scheduleAdSignalRetry();
        scheduleMineTreeDump();          // v1.9.21 诊断：抓「我的」页视图树
    }

    /**
     * 精准拦截广告文本 View：hook TextView.setText()，广告文本被设置的瞬间立即 GONE 自身及父容器。
     * 不依赖扫描/轮询，App 任何模式切换重建 View 都会触发，零卡顿。
     */
    private void hookTextViewAdFilter() {
        final XC_MethodHook setTextHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    CharSequence cs = (CharSequence) param.args[0];
                    if (cs == null) return;
                    String t = cs.toString().trim();
                    if (t.length() == 0) return;
                    // v1.9.21：「深度卸载」这类短广告文案只有 4 个字，旧的 14 字门卡直接把它挡在外面。
                    // 改为先用 isLongAdText 判定（它内部按关键词/数字模式，不按长度），
                    // 只有「长文案」才需要那个 14 字护栏来避免误伤阅读页正文。
                    boolean sus = isLongAdText(t);
                    if (t.length() > 14 && !isMinePageAdText(t) && !sus) return; // 放行正文长文案
                    if (isAdEntryText(t) || isMinePageAdText(t) || sus) {
                        hideAdEntryView((View) param.thisObject, t);
                    }
                } catch (Throwable ignored) {}
            }
        };
        // setText(CharSequence) 与 setText(CharSequence, BufferType)
        try { XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, setTextHook); } catch (Throwable ignored) {}
        try { XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, "android.widget.TextView$BufferType", setTextHook); } catch (Throwable ignored) {}
        // 内容描述也拦截（无文本仅图标的入口）
        try {
            XposedHelpers.findAndHookMethod(View.class, "setContentDescription", CharSequence.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        CharSequence cs = (CharSequence) param.args[0];
                        if (cs == null) return;
                        String t = cs.toString().trim();
                        if (t.length() == 0) return;
                        boolean sus = isLongAdText(t);
                        if (t.length() > 14 && !isMinePageAdText(t) && !sus) return;
                        if (isAdEntryText(t) || isMinePageAdText(t) || sus) {
                            hideAdEntryView((View) param.thisObject, t);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
        XposedBridge.log("[" + TAG + "] TextView.set 广告文本过滤器已启用");
    }

    /**
     * v1.9.21 新增：偏长的广告文案（全屏广告 / 清理加速类弹窗），最长 30 字。
     *
     * 这些文案超过了短规则 14 字的限制（如「今天最多可以赚3688金币」「深度卸载」
     * 「手机内存不足卡顿」类的假清理/加速广告），之前全部漏过。
     * 为了不误伤阅读页正文，命中条件刻意写得很紧：
     *   ① 清理/加速类关键词（小说正文几乎不可能出现）；
     *   ② 「赚 + 金币」必须同时带数字（「今天最多可以赚3688金币」），避开正文里的普通句子。
     */
    private boolean isLongAdText(String t) {
        if (t == null || t.length() > 30) return false;
        // ① 假清理 / 加速 / 卸载类。这些词在小说正文里几乎不可能出现，
        //    所以这里不做长度限制 —— 「深度卸载」只有 4 个字，之前被 14 字的门卡拦在外面。
        if (t.contains("深度卸载") || t.contains("内存不足") || t.contains("手机卡顿")
                || t.contains("一键清理") || t.contains("立即清理") || t.contains("垃圾清理")
                || t.contains("手机发热") || t.contains("手机耗电") || t.contains("清理垃圾")
                || t.contains("深度清理") || t.contains("一键加速") || t.contains("立即加速")
                || t.contains("手机加速") || t.contains("手机降温") || t.contains("清理大师")) return true;
        // 注意：故意不写「手机内存 / 运行内存 / 释放内存」这种四个字的泛词 ——
        //      它们有可能正好是书名/章节名，误伤成本大于收益；真正的广告文案是
        //      「手机内存不足卡顿」，已经被上面的「内存不足」覆盖了。
        // ② 「赚 + 金币」必须同时带数字（「今天最多可以赚3688金币」），避开正文里的普通句子
        if (t.contains("赚") && t.contains("金币") && t.matches(".*\\d.*")) return true;
        // ③ 「卸载」必须搭配广告/金币/红包，否则可能是正常的「卸载应用」
        if (t.contains("卸载") && (t.contains("广告") || t.contains("金币") || t.contains("红包"))) return true;
        return false;
    }

    /** 广告入口文本关键词（精确匹配，防误伤正文） */
    private boolean isAdEntryText(String t) {
        if (t == null || t.length() > 14) return false;
        // 畅听/免广告类
        if (t.contains("全天免费畅听") || t.contains("全天畅听") || t.contains("免广告")
                || t.contains("看小视频") || t.contains("看视频免") || t.contains("免费畅听")
                || t.contains("免费听") || t.contains("畅听中") || t.contains("看小视频免")) return true;
        // 金币/钱包类
        if (t.contains("金币余额") || t.contains("现金余额") || t.contains("领金币")
                || t.contains("逛街赚金币") || t.contains("可领") || t.contains("待领")
                || t.contains("去领") || t.contains("金币待")) return true;
        // 资产/会员/商城类
        if (t.contains("我的资产") || t.contains("邀请好友") || t.contains("购物车")
                || t.contains("优惠券") || t.contains("立即领取") || t.contains("领红包")
                || t.contains("签到") || t.contains("商城") || t.contains("领现金")
                || t.contains("福利") || t.contains("借钱") || t.contains("公益")
                || t.contains("做任务") || t.contains("赚金币") || t.contains("我的收益")) return true;
        // 激励视频/解锁类
        if (t.contains("激励视频") || t.contains("观看视频") || t.contains("解锁时长")
                || t.contains("可解锁")) return true;
        // v1.9.6 新增：推广广告类（番茄免费小说/看剧赚钱等穿山甲/优量汇素材）
        if (t.contains("看剧赚钱") || t.contains("赚钱红包") || t.contains("看短剧")
                || t.contains("免费小说") || t.contains("刷短剧") || t.contains("多人推荐")
                || t.contains("不要错过") || t.contains("下载试试")) return true;
        return false;
    }

    /** "我的"页面广告/资产入口关键词（允许长文案，覆盖 VIP 横幅） */
    private boolean isMinePageAdText(String t) {
        if (t == null) return false;
        if (t.contains("尊享免广告") || t.contains("无限下载") || t.contains("多种权益")) return true;
        if (t.contains("¥14开通") || t.contains("VIP")) return true;
        if (t.contains("我的资产") || t.contains("金币余额") || t.contains("现金余额")
                || t.contains("剩余时长") || t.contains("提现")) return true;
        return false;
    }

    /**
     * 隐藏广告文本 View：延迟到布局完成后，向上找到"广告卡片块"级容器（含箭头等兄弟元素）整体 GONE。
     * 不能在 setText 瞬间执行——此时 w/h 均为 0，找不到卡片容器。
     */
    private void hideAdEntryView(final View v, final String t) {
        try {
            if (v == null) return;
            if (v.getVisibility() == View.GONE) return;
            final Handler h = new Handler(Looper.getMainLooper());
            hideAdEntryViewDelayed(v, t, h, 0);
        } catch (Throwable ignored) {}
    }

    /** 延迟隐藏广告卡片：重试直到布局完成（最多 6 次） */
    private void hideAdEntryViewDelayed(final View v, final String t, final Handler h, final int attempt) {
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (v.getVisibility() == View.GONE) return;
                    View root = null;
                    int sw = 0, sh = 0;
                    try { root = v.getRootView(); sw = root.getWidth(); sh = root.getHeight(); } catch (Throwable ignored) {}
                    if (sw <= 0 || sh <= 0) { // 还没布局，重试
                        if (attempt < 6) hideAdEntryViewDelayed(v, t, h, attempt + 1);
                        return;
                    }
                    View target = v;
                    View cur = v;
                    int[] ploc = new int[2];
                    // 向上最多 8 层：找到包含广告文本+箭头的卡片容器（宽 < 90% 屏宽、非页面级）
                    for (int i = 0; i < 8; i++) {
                        View p = (View) cur.getParent();
                        if (p == null) break;
                        int pw = p.getWidth(), ph = p.getHeight();
                        try { p.getLocationOnScreen(ploc); } catch (Throwable ignored2) {}
                        // 页面级容器保护：宽>=90%屏宽 且 高>=40%屏高 → 不藏，停
                        if (sw > 0 && sh > 0 && pw >= sw * 0.9f && ph >= sh * 0.4f) break;
                        // 顶部栏保护：位于屏幕上部 8% 的全宽扁平容器
                        if (sw > 0 && pw >= sw * 0.8f && ploc[1] < sh * 0.08f && ph < sh * 0.1f) break;
                        // 底部导航保护
                        if (sw > 0 && sh > 0 && ploc[1] >= sh * 0.85f && ph < sh * 0.1f && pw >= sw * 0.6f) break;
                        // 广告卡片块：宽 < 90% 屏宽的容器持续向上取最大安全层
                        if (pw < sw * 0.9f) {
                            target = p;
                            cur = p;
                        } else {
                            break;
                        }
                    }
                    // v1.9.16 补充：章节末「看小视频免30分钟广告」这类入口是一个**整宽可点击容器**
                    // （6.7.1.32：id=dls，bounds=[0,1418][1080,1640]），上面的爬升循环因为
                    // 「宽>=90%屏宽 = 页面级」会提前 break，结果只隐藏了内部文字行，
                    // 外框/箭头/点击区还在，看起来还是「广告入口」。
                    // 对这类入口改为：往上找到**最近的可点击祖先**，整块 GONE，
                    // 入口连外框一起消失（且不依赖资源 id 数值，换版本也稳）。
                    if (t.contains("看小视频") || t.contains("免广告")) {
                        // 诊断：把祖先链（类名 + 资源名）打一次，方便定位入口宿主类
                        if (loggedAdEntryChain.add(t)) {
                            try {
                                StringBuilder sb = new StringBuilder();
                                View p = v;
                                for (int i = 0; i < 12 && p != null; i++) {
                                    sb.append(p.getClass().getName());
                                    int pid = p.getId();
                                    if (pid != View.NO_ID) {
                                        try {
                                            sb.append("#").append(v.getResources().getResourceEntryName(pid));
                                        } catch (Throwable ignored3) {}
                                    }
                                    sb.append(p.isClickable() ? "[clickable]" : "");
                                    sb.append(" > ");
                                    p = (p.getParent() instanceof View) ? (View) p.getParent() : null;
                                }
                                XposedBridge.log("[" + TAG + "] 广告入口祖先链['" + t + "']: " + sb);
                            } catch (Throwable ignored4) {}
                        }
                        View p = v;
                        for (int i = 0; i < 6; i++) {
                            View parent = (p.getParent() instanceof View) ? (View) p.getParent() : null;
                            if (parent == null) break;
                            p = parent;
                            if (p.isClickable()) { target = p; break; }
                        }
                    }
                    if (target.getVisibility() != View.GONE) {
                        target.setVisibility(View.GONE);
                        XposedBridge.log("[" + TAG + "] 文本拦截隐藏['" + t + "'] " + target.getClass().getSimpleName()
                                + " w=" + target.getWidth() + " h=" + target.getHeight());
                    }
                } catch (Throwable ignored) {}
            }
        }, 10);
    }

    /**
     * v1.9.21：桌面快捷方式（长按 App 图标弹出的菜单）广告关键词。
     *
     * 「今天最多可以赚3688金币 / 深度卸载 / 手机内存不足卡顿」这三个广告入口**根本不在 APK 里**
     * —— resources.arsc 和全部 15 个 dex 都 grep 不到，是服务端下发、由 ShortcutManager
     * 动态发布出来的。因此只能按关键词在「发布的那一瞬间」过滤掉：
     *   storageId   -> 「手机内存不足卡顿」
     *   uninstallId -> 「深度卸载」
     *   totalCoinId -> 「今天最多可以赚3688金币」
     * 实测 App 每次启动都会重新发布这三个，所以既拦发布（hookShortcutCleaner），
     * 也在 onResume 时清理一次（removeAdShortcuts）作为兜底。
     */
    private static final String[] AD_SHORTCUT_BAD = {
            "金币", "领现金", "畅听", "福利", "领取", "赚钱", "卸载", "存储",
            "领红包", "清理", "内存", "卡顿", "加速", "红包", "签到", "任务", "免费",
            "现金", "提现", "宝箱", "翻倍", "抽奖", "免单", "赚钱",
    };

    /** 已过滤掉的快捷方式 id（用于日志去重：App 每次启动都重新发布，不然会刷屏） */
    private final Set<String> filteredShortcutIds = new HashSet<>();

    /**
     * 过滤一组 ShortcutInfo：命中广告关键词的丢弃，其余保留。
     *
     * @return null 表示「不是快捷方式列表」或「一条都不需要过滤」——调用方应保持原参数不动。
     */
    private java.util.List<Object> filterShortcutList(Object arg) {
        if (!(arg instanceof java.util.List)) return null;
        java.util.List<?> list = (java.util.List<?>) arg;
        if (list.isEmpty()) return null;
        java.util.ArrayList<Object> keep = new java.util.ArrayList<>();
        java.util.ArrayList<String> removedIds = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            if (isAdShortcut(o)) removedIds.add(str(o, "getId"));
            else keep.add(o);
        }
        if (removedIds.isEmpty()) return null;
        if (filteredShortcutIds.addAll(removedIds)) {
            XposedBridge.log("[" + TAG + "] 已过滤桌面快捷方式(长按图标广告) " + removedIds
                    + " 保留 " + keep.size() + " 个");
        }
        return keep;
    }

    /**
     * 该快捷方式是否是广告入口 —— 按 id / 短标题 / 长标题里的关键词判定。
     *
     * 中文关键词覆盖服务端下发的文案（「深度卸载」「手机内存不足卡顿」「今天最多可以赚3688金币」…）；
     * 英文关键词兜底覆盖 id（storageId / uninstallId / totalCoinId 这些 id 里没有中文）。
     */
    private boolean isAdShortcut(Object o) {
        if (o == null) return false;
        String all = (str(o, "getId") + " " + str(o, "getShortLabel") + " " + str(o, "getLongLabel"))
                .toLowerCase();
        if (all.trim().isEmpty()) return false;
        for (String b : AD_SHORTCUT_BAD) {
            if (all.contains(b.toLowerCase())) return true;
        }
        return all.contains("uninstall") || all.contains("storage") || all.contains("clean")
                || all.contains("coin") || all.contains("gold") || all.contains("cash")
                || all.contains("redpacket") || all.contains("reward") || all.contains("bonus");
    }

    /** 反射调用无参 getter，失败返回空串。 */
    private String str(Object o, String method) {
        try {
            Object r = o.getClass().getMethod(method).invoke(o);
            return r == null ? "" : r.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void hookShortcutCleaner() {
        try {
            final XC_MethodHook h = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        boolean sawList = false;
                        for (int i = 0; i < param.args.length; i++) {
                            java.util.List<Object> keep = filterShortcutList(param.args[i]);
                            if (keep != null) param.args[i] = keep;
                            if (param.args[i] instanceof java.util.List) sawList = true;
                        }
                        // 单条推送（pushDynamicShortcut(ShortcutInfo)）没有列表可过滤，
                        // 命中广告就直接跳过整个调用（Xposed 的 setResult 会中止原方法）。
                        if (!sawList && param.args.length > 0) {
                            Object last = param.args[param.args.length - 1];
                            if (isAdShortcut(last)) {
                                String id = str(last, "getId");
                                if (filteredShortcutIds.add(id)) {
                                    XposedBridge.log("[" + TAG + "] 已拦截单条桌面快捷方式广告: " + id);
                                }
                                param.setResult(null);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            };
            // ⚠️ v1.9.21 重要修复：ShortcutManager 在 **android.content.pm** 包下，
            //    以前写成 android.app.ShortcutManager —— 那个类根本不存在，于是每次启动都打
            //    「hookShortcutCleaner 失败: ShortcutManager 不可用」，长按图标的广告一条都没拦住，
            //    只剩 onResume 里的 removeAdShortcuts 事后补救（滞后、且会跟 App 反复拉锯）。
            int mounted = 0;
            for (String cn : new String[]{
                    "android.content.pm.ShortcutManager",
                    "androidx.core.content.pm.ShortcutManagerCompat"}) {
                Class<?> c = null;
                try { c = Class.forName(cn); } catch (Throwable t1) {}
                if (c == null) { try { c = XposedHelpers.findClassIfExists(cn, appCl); } catch (Throwable t2) {} }
                if (c == null) continue;
                XposedBridge.hookAllMethods(c, "addDynamicShortcuts", h);
                XposedBridge.hookAllMethods(c, "setDynamicShortcuts", h);
                XposedBridge.hookAllMethods(c, "updateShortcuts", h);
                XposedBridge.hookAllMethods(c, "pushDynamicShortcut", h);
                XposedBridge.log("[" + TAG + "] 桌面快捷方式过滤器已启用: " + cn);
                mounted++;
            }
            if (mounted == 0) throw new RuntimeException("ShortcutManager 不可用");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] hookShortcutCleaner 失败: " + t);
        }
    }

    private void hookActivityBlocker() {
        final Handler h = new Handler(Looper.getMainLooper());
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try { patchVip(); } catch (Throwable ignored) {}
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object act = param.thisObject;
                    if (act == null) return;
                    String name = act.getClass().getName();
                    for (String p : BLOCKED) {
                        if (name.startsWith(p)) {
                            try {
                                ((Activity) act).finish();
                                XposedBridge.log("[" + TAG + "] 已拦截页面: " + name);
                            } catch (Throwable ignored) {}
                            break;
                        }
                    }
                    patchTries = 0;
                    removeAdShortcuts((Activity) act);
                    h.postDelayed(new Runnable() { public void run() { patchVip(); } }, 100);
                    startHideWatch((Activity) act, h);
                    long nowR = System.currentTimeMillis();
                    if (adHookReport.length() > 0 && nowR - lastReportLog > 60000) {
                        lastReportLog = nowR;
                        XposedBridge.log("[" + TAG + "] Hook注册报告: " + adHookReport);
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] Activity 拦截器+VIP patch 已启用");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] hookActivityBlocker 失败: " + t);
        }
    }

    /** 优化版：首轮150ms延迟 + OnGlobalLayout(限频400ms)驱动，去掉300ms高频轮询 */
    private void startHideWatch(final Activity act, final Handler h) {
        final WeakReference<Activity> wref = new WeakReference<>(act);
        // 首轮延迟一次扫描（等布局完成）
        h.postDelayed(new Runnable() {
            public void run() {
                Activity a = wref.get();
                if (a != null && !a.isFinishing()) { try { hideAll(a); } catch (Throwable ignored) {} }
            }
        }, 150);
        // OnGlobalLayout 驱动（免轮询）：
        // ⚠️ v1.9.21 重要修复：以前用 hasListenerAttached 布尔量「只挂一次」，
        //    结果第一个 onResume 的 Activity 是 SplashActivity —— listener 挂在开屏页的 decor 上，
        //    开屏页一 finish，WeakReference 就变 null，此后 hideAll 再也不会被布局事件驱动。
        //    而底部四个 Tab（首页/听书/我的…）是同一个 Activity 里的兄弟容器，
        //    切 Tab 不触发 onResume、也不会重新 post 那一次 150ms 的 hideAll ——
        //    「我的」页就是这样漏掉的（用户看到“我的页没效果”）。
        //    现在改成按 decor 逐个挂（WeakHashMap 去重，同一个 decor 不会重复挂）。
        try {
            final View decor = act.getWindow().getDecorView();
            if (!listenedDecors.containsKey(decor)) {
                final WeakReference<Activity> ref = new WeakReference<>(act);
                decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    private long last = 0;
                    @Override
                    public void onGlobalLayout() {
                        Activity a = ref.get();
                        if (a == null || a.isFinishing()) return;
                        long now = System.currentTimeMillis();
                        if (now - last < 1200) return;  // 1.2秒限频，避免拖慢宿主 App
                        last = now;
                        hideAll(a);
                    }
                });
                listenedDecors.put(decor, Boolean.TRUE);
            }
        } catch (Throwable ignored) {}
        startHideWatchdog(h);
    }

    /** 已挂过 OnGlobalLayoutListener 的 decor（WeakHashMap：Activity 销毁后自动回收，不会泄漏）。 */
    private final java.util.WeakHashMap<View, Boolean> listenedDecors = new java.util.WeakHashMap<>();
    /** v1.9.21 兜底看门狗是否已在跑 */
    private boolean watchdogRunning = false;
    /** 上次 hideAll 看到的 decor（用来在切 Tab 后强制重扫一次） */
    private View lastWatchdogDecor = null;

    /**
     * v1.9.21 新增：兜底看门狗。
     *
     * OnGlobalLayout 只在「布局真的变了」时才触发，而番茄的底部 Tab 切换、服务端下发的新广告块
     * 有时不产生布局事件。这个看门狗在每次 onResume 后跑 40 秒（每 1.5 秒一次，内部还有
     * 1.2 秒限频），保证「切到我的页 → 还没刷干净」这种情况能自愈；
     * 40 秒后自动停下，不会长期占 CPU。每次 onResume 会重新启动它。
     */
    private void startHideWatchdog(final Handler h) {
        if (watchdogRunning) return;
        watchdogRunning = true;
        h.postDelayed(new Runnable() {
            int rounds = 0;
            @Override public void run() {
                try {
                    if (++rounds > 20) { watchdogRunning = false; return; }
                    Activity a = getForegroundActivity();
                    if (a == null || a.isFinishing() || a.isDestroyed()) { watchdogRunning = false; return; }
                    View decor = null;
                    try { decor = a.getWindow().getDecorView(); } catch (Throwable ignored) {}
                    if (decor != null && decor != lastWatchdogDecor) {
                        // 换了页面（decor 变了）→ 重置限频，立刻扫一次
                        lastHideAllTime = 0;
                        lastWatchdogDecor = decor;
                    }
                    try { hideAll(a); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
                h.postDelayed(this, 2000);
            }
        }, 2000);
    }

    /** 找当前真的在前台的 Activity（优先「有窗口焦点」的那个，拿不到就退回第一个存活的）。 */
    private Activity getForegroundActivity() {
        Activity fallback = null;
        try {
            for (Activity a : getAllActivities()) {
                try {
                    if (a.hasWindowFocus()) return a;
                    if (fallback == null) fallback = a;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    private int hideAll(Activity act) {
        // 限频：两次 hideAll 至少间隔 500ms，避免 OnGlobalLayout + 首屏扫描叠加
        long now = System.currentTimeMillis();
        if (now - lastHideAllTime < MIN_HIDE_INTERVAL) return 0;
        lastHideAllTime = now;
        int[] cnt = {0};
        if (readerAct != act) { readerAct = act; readerPage = 0; }
        try {
            View decor = act.getWindow().getDecorView();
            scanAll(decor, 0, cnt, act);
            scanRadioGroup(decor, cnt);
            scanReaderTopRightCoin(decor, cnt, act);
            scanListeningPageAds(act, cnt);
            hideKnownAdResources(act, cnt);
            // 强制扫描：遍历所有 View，通过 toString() 匹配广告关键词（覆盖非 TextView 的自绘广告）
            // 不做全树 forceScan：避免章节阅读时反复遍历造成卡顿；仅依赖精确资源/类名 hook
            if (false) forceScanAdText(decor, 0, cnt, act);
        } catch (Throwable ignored) {}
        try {
            scanAllWindows(act, cnt);
        } catch (Throwable ignored) {}
        return cnt[0];
    }

    /** 强制扫描：通过 View.toString() 检查广告关键词，覆盖非 TextView 的自绘广告 */
    private void forceScanAdText(View v, int depth, int[] cnt, Activity act) {
        if (v == null || depth > 30 || v.getVisibility() != View.VISIBLE) return;
        try {
            String vs = v.toString();
            if (vs.length() > 0 && vs.length() < 200) {
                String lower = vs.toLowerCase();
                // 直接匹配广告横幅文本（覆盖 Canvas.drawText 绘制的广告）
                if (lower.contains("看小视频") || lower.contains("看视频免")
                        || lower.contains("免广告阅读") || lower.contains("免广告")
                        || lower.contains("解锁时长") || lower.contains("可解锁")
                        || lower.contains("做任务免") || lower.contains("观看视频免")
                        || lower.contains("激励视频") || lower.contains("再看") && lower.contains("秒")) {
                    // 找到广告 View，向上找合理的容器隐藏
                    View toHide = v;
                    for (int i = 0; i < 3; i++) {
                        View parent = (View) toHide.getParent();
                        if (parent == null) break;
                        int pw = parent.getWidth(), ph = parent.getHeight();
                        int sw = act.getWindow().getDecorView().getWidth();
                        int sh = act.getWindow().getDecorView().getHeight();
                        if (pw >= sw * 0.9f && ph >= sh * 0.5f) break; // 页面级不停
                        toHide = parent;
                    }
                    if (toHide.getVisibility() != View.GONE) {
                        toHide.setVisibility(View.GONE);
                        cnt[0]++;
                        XposedBridge.log("[" + TAG + "] [forceScan] 隐藏广告: " + vs.substring(0, Math.min(60, vs.length())));
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                forceScanAdText(vg.getChildAt(i), depth + 1, cnt, act);
            }
        }
    }

    /** 扫描听歌页全屏覆盖型广告（已知资源ID已移至 hideKnownAdResources） */
    private void scanListeningPageAds(Activity act, int[] cnt) {
        if (act == null) return;
        String actName = act.getClass().getName();
        if (!actName.contains("MainFragmentActivity") && !actName.contains("AudioPlay")) return;
        try {
            detectOverlayAds(act, cnt);
        } catch (Throwable ignored) {}
    }

    /**
     * v1.9.9：登记「需要按资源 ID 强制隐藏」的 viewId 集合（只做一次）。
     *
     * 背景：「我的」页 VIP 宣传卡(bpz) 与 我的资产卡(e33) 由 App 在布局刷新时反复设回 VISIBLE，
     * 仅在扫描时执行一次 setVisibility(GONE) 会被立刻覆盖，表现为卡片"闪一下又回来"。
     * 因此把这些 viewId 登记下来，在 View.setVisibility 的 hook 里「设为可见的瞬间」直接改回 GONE。
     *
     * 注意：bpz 是 androidx ConstraintLayout、e33 是 android.widget.LinearLayout，
     * 属于框架类，绝不能对它们做「构造即 GONE」（见 hookViewClass 的安全护栏），
     * 所以这里只能走资源 ID 路径。
     */
    private void registerForceHideIds(Activity act) {
        if (act == null) return;
        try {
            // v1.9.21：6.7.2.32 又换了一轮 —— 6.7.1.32 用的 br7/gtn/dg3/ej2/ce4 在这一版的
            //          「我的」页里**一个都不存在**了（实测把整棵 View 树 dump 出来核对过），
            //          getIdentifier 返回 0 或 findViewById 找不到 → 等于空转。
            //          下面这组是按 6.7.2.32 的实机 View 树重新定位的：
            String[] names = {
                    "bsf",  // 我的页 VIP 促销卡片（整卡 ConstraintLayout：立减/尊享免广告/立即开通）
                    "e87",  // 我的资产 + 邀请好友/好友管理 板块（整块 LinearLayout）
                    "e4q",  // 快捷入口栏（我的消息/优惠券/购物车/商城/游戏中心）
            };
            // 注意：这里用「合并」而不是「覆盖」——  hideMineGenericBlocks 也会往同一个集合里加 id，
            // 不能把它的成果冲掉。
            Set<Integer> ids = cachedHideIds;
            if (ids == null) {
                ids = new HashSet<>();
                cachedHideIds = ids;
            }
            int before = ids.size();
            for (String n : names) {
                try {
                    int id = act.getResources().getIdentifier(n, "id", act.getPackageName());
                    if (id > 0) ids.add(id);
                } catch (Throwable ignored) {}
            }
            if (ids.size() != before) {
                XposedBridge.log("[" + TAG + "] 已登记强制隐藏资源ID " + ids.size() + " 个: " + ids);
            }
        } catch (Throwable ignored) {}
    }

    /** 通过已知资源 ID + 文本匹配 精确隐藏广告元素（全覆盖：主页/阅读页/听歌页） */
    private void hideKnownAdResources(Activity act, int[] cnt) {
        if (act == null) return;
        // v1.9.9：登记「我的页」需要持续强制隐藏的 viewId（供 setVisibility hook 使用）
        registerForceHideIds(act);
        // --- 主页顶部"全天畅听"入口 (id=gxi, 142x47 药丸) ---
        hideByResId(act, "gxi", cnt, "全天畅听入口");
        // --- 阅读页全天免费畅听中：资源 ID bwf（可点击容器），gtr(文本) ---
        hideByResIdUp(act, "bwf", cnt, "全天免费畅听中", 2);
        hideByResIdUp(act, "gtr", cnt, "全天免费畅听文本", 3);
        // --- 阅读页300金币：资源 ID abz（ImageView），父容器 abx ---
        hideByResIdUp(act, "abz", cnt, "300金币", 3);
        hideByResIdUp(act, "abx", cnt, "300金币容器", 2);
        // --- 听歌页已知广告位 ---
        hideByResId(act, "a3c", cnt, "听歌页横幅广告");
        hideByResId(act, "fp_", cnt, "听歌页卡片广告");
        // --- "我的"页面：VIP 促销卡 / 我的资产板块 / 快捷入口栏（v1.9.21 按 6.7.2.32 重新定位） ---
        // ⚠️ v1.9.9 重要修复：这里**只能直接隐藏卡片容器本身，禁止向上多级隐藏**。
        //    原实现用 hideByResIdUp("gzy", 3) / ("gjj", 4) 向上找父容器，实测会一路
        //    打到 #e10 和 #c1(com.dragon.read.widget.behavior.CommonCustomAppBarLayout)
        //    —— 那是"我的"页整个顶部 AppBar（头像/昵称/个人主页入口/我的消息 全在里面），
        //    结果整个头部被 GONE：页面顶部出现大片空白、下面的菜单/列表位置明显错乱。
        // 6.7.2.32 实测 View 树（c1 CommonCustomAppBarLayout > e63 LinearLayout）：
        //   hbj 头像/昵称行 → 保留
        //   bsf VIP 促销卡（1008x210，含「立减」「尊享免广告…」「立即开通」）
        //   cfe > e4q 快捷入口栏（1008x238，含 我的消息/优惠券/购物车/商城/游戏中心）
        //   e87 我的资产（提现/金币余额/现金余额/剩余时长）+ 邀请好友/好友管理（1008x473）
        // 三者任意一个不藏，屏幕顶部就会留着卡片或大片空白。
        // ⚠️ 必须用 hideMineResIdCollapse（而不是 hideByResId）：
        //    e4q 外面还套着 cfe > cfd 两层只负责圆角/滚动/左右箭头的包装容器，
        //    只 GONE 最里面那层，外面仍占着 1008x238 的布局高度 → 屏幕上就是一块空白。
        hideMineResIdCollapse(act, "bsf", cnt, "我的页VIP促销卡片");     // VIP 促销卡容器（6.7.2.32）
        hideMineResIdCollapse(act, "e4q", cnt, "我的页快捷入口栏");       // 我的消息/优惠券/购物车/商城/游戏中心（6.7.2.32）
        hideMineResIdCollapse(act, "cfe", cnt, "快捷入口栏外包装");       // e4q 的外层包装（去空白的关键）
        hideMineResIdCollapse(act, "e87", cnt, "我的资产+邀请好友板块");   // 我的资产 / 邀请好友 / 好友管理（6.7.2.32）
        // 下面两个是 6.7.1.32 的旧 id，6.7.2.32 已不存在（找不到就自动跳过，保留无害）
        hideByResId(act, "br7", cnt, "我的页VIP促销卡片(旧)");
        hideByResId(act, "ej2", cnt, "我的页活动横幅(旧)");
        hideMineEntryCard(act, cnt);                                 // 我的页头部入口卡片（我的消息/游戏中心/活动横幅）
        hideMineGenericBlocks(act, cnt);                             // v1.9.21：版本无关、按文案定位整块隐藏
        hideExtraRows(act, cnt);                                     // v1.9.21：设置页等零散条目（支付管理/免流量服务）
        dumpMineTreeOnce(act);                                       // v1.9.21 诊断：转储当前页 View 树（开关文件控制）
    }

    /**
     * v1.9.21：另外要收掉的零散条目 —— 它们不在「我的」页顶部块里，而是设置页列表里的一行。
     *
     * 用户指定：「设置 → 支付管理 / 免流量服务」两项要隐藏。
     * 同样不写资源 id（那两项在本版的 id 是 fis / fjc，下一版又会变），
     * 只认文案 + 「整行容器」的结构。
     */
    private static final String[] EXTRA_HIDE_ROW_TEXTS = {
            "支付管理", "免流量服务",
    };

    private long lastExtraRowTime = 0;

    /**
     * v1.9.21：【版本无关】按文案隐藏「列表里的一整行」。
     *
     * 做法：找到文案 → 向上取最近一层「宽度≥ 75% 屏宽、高度≤ 12% 屏高」的容器（就是那一行），
     * 整行 GONE（子项全没、行高也随之消失，不会留空白），并把行 id 登记进强制隐藏集合。
     */
    private void hideExtraRows(Activity act, int[] cnt) {
        if (act == null) return;
        try {
            long now = System.currentTimeMillis();
            if (now - lastExtraRowTime < 2500) return;      // 限频，别每次布局事件都全页找一遍
            lastExtraRowTime = now;
            View decor = act.getWindow().getDecorView();
            View root = decor.getRootView() != null ? decor.getRootView() : decor;
            int sw = root.getWidth(), sh = root.getHeight();
            if (sw <= 0 || sh <= 0) return;
            for (String t : EXTRA_HIDE_ROW_TEXTS) {
                View tv = findTextViewByText(decor, t, 0);
                if (tv == null) continue;
                View row = findListRow(tv, sw, sh);
                if (row == null || row == tv) continue;
                if (row.getVisibility() == View.GONE) continue;
                row.setVisibility(View.GONE);
                if (row.getId() != View.NO_ID) addForceHideId(row.getId());
                cnt[0]++;
                XposedBridge.log("[" + TAG + "] 已隐藏列表行['" + t + "'] "
                        + row.getClass().getName() + " w=" + row.getWidth() + " h=" + row.getHeight());
            }
        } catch (Throwable ignored) {}
    }

    /** 从文案节点向上找「整行」容器：宽度≥ 75% 屏宽且高度≤ 12% 屏高。 */
    private View findListRow(View tv, int sw, int sh) {
        View cur = tv;
        for (int i = 0; i < 5 && cur != null; i++) {
            int w = cur.getWidth(), h = cur.getHeight();
            if (w >= sw * 0.75f && h > 0 && h <= sh * 0.12f) return cur;
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return null;
    }

    /**
     * v1.9.21 新增：【去空白】按资源名隐藏「我的」页区块，并把外面那层空壳一起折叠。
     *
     * 与 hideByResId 的唯一差别就是最后多调一次 {@link #collapseEmptyAncestors}。
     */
    private void hideMineResIdCollapse(Activity act, String resName, int[] cnt, String desc) {
        try {
            int resId = act.getResources().getIdentifier(resName, "id", act.getPackageName());
            if (resId <= 0) return;
            View target = act.getWindow().getDecorView().findViewById(resId);
            if (target == null) return;
            if (target.getVisibility() != View.GONE) {
                target.setVisibility(View.GONE);
                cnt[0]++;
                XposedBridge.log("[" + TAG + "] 已隐藏" + desc + "(id=" + resName + ") "
                        + target.getClass().getName() + " w=" + target.getWidth() + " h=" + target.getHeight());
            }
            collapseEmptyAncestors(target, cnt);
        } catch (Throwable ignored) {}
    }

    /**
     * v1.9.21 新增：【版本无关·去空白】自下而上折叠「已经没有可见内容」的外层容器。
     *
     * 番茄的卡片外面通常还套着一两层只负责圆角/背景/横向滚动/左右箭头的包装容器
     * （6.7.2.32 的快捷入口栏就是 cfe > cfd > e4q 三层）。只把最里面那层 GONE，
     * 外面那层依然占着 1008x238 的布局高度 —— 屏幕上就是一块空白，
     * 正是本次用户反馈的「没效果、还留空白区域」。
     *
     * 做法：从刚隐藏的节点：往上走，只要父容器已经没有任何可见且占位的内容就一起 GONE，
     * 直到遇到页面外壳（头像行 / 顶部标签栏）为止，绝不越过。
     */
    private void collapseEmptyAncestors(View v, int[] cnt) {
        if (v == null) return;
        try {
            View cur = v;
            for (int i = 0; i < 6; i++) {
                View p = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                if (p == null) break;
                if (mineChromePresent(p)) break;      // 已到页面外壳（头像行 / 标签栏）
                if (p.getVisibility() == View.GONE) break;
                if (hasVisibleContent(p, 0)) break;   // 父容器还有别的可见内容，保留
                p.setVisibility(View.GONE);
                if (p.getId() != View.NO_ID) addForceHideId(p.getId());
                cnt[0]++;
                XposedBridge.log("[" + TAG + "] 已折叠空壳容器(去空白): "
                        + p.getClass().getName() + " h=" + p.getHeight());
                cur = p;
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 容器内是否还有「可见且占位」的内容：GONE / INVISIBLE / 宽高为 0 一律算空。
     *
     * 只用于 {@link #collapseEmptyAncestors} 的判断 —— 宁可不折叠，也不要多折叠，
     * 所以只要找到一个可见且有尺寸的叶子就返回 true。
     */
    private boolean hasVisibleContent(View v, int depth) {
        if (v == null || depth > 12) return false;
        if (v.getVisibility() != View.VISIBLE) return false;
        if (!(v instanceof ViewGroup)) return v.getWidth() > 0 && v.getHeight() > 0;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (hasVisibleContent(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    /**
     * v1.9.20：「我的」页头部「功能入口 + 活动横幅」卡片（ce4）。
     *
     * 该卡片位于「我的」页页头 AppBar 内，只装两样东西：
     *   ① ce3 —— 横向快捷入口栏（我的消息 / 游戏中心 / 服务端活动入口）；
     *   ② ej2 —— 活动横幅（实测为「中秋好礼限时购 一件立减15%>」）。
     *
     * 为什么单独处理：整卡在 AppBar 内，通用 hideChain 开头的「顶栏整链放弃」保护会直接 return，
     * 文本规则命中了也什么都不做（运行日志："跳过隐藏(位于顶栏内): '游戏中心'"）。
     *
     * 做法：先用 ce4 自身存在与否判定当前确实是「我的」页（其他页面无此 id，避免误伤），
     * 再把整张卡片 GONE —— 子项全没了，卡片背景与高度也随之消失；
     * 只藏子项（ce3+ej2）会留下一块 1008x156 的空白圆角底，正是要避免的「大片空白」。
     * 因为整卡干掉，服务端下发的「带字图片」入口（节点上没有 text/desc）也一并消失。
     */
    private void hideMineEntryCard(Activity act, int[] cnt) {
        try {
            View decor = act.getWindow().getDecorView();
            int cardId = act.getResources().getIdentifier("ce4", "id", act.getPackageName());
            if (cardId <= 0) return;
            View card = decor.findViewById(cardId);
            if (card == null || card.getVisibility() == View.GONE) return;   // 不存在 → 当前不是「我的」页
            card.setVisibility(View.GONE);
            cnt[0]++;
            XposedBridge.log("[" + TAG + "] 已隐藏我的页头部入口卡片(ce4): 我的消息/游戏中心/活动横幅");
        } catch (Throwable ignored) {}
    }

    /**
     * v1.9.21 诊断：一次性打印「我的」页顶部视图树（类名#资源名 可见性 尺寸 坐标）。
     *
     * 换版本适配时唯一可靠的定位手段 —— 「我的」页一直被 App 自己的 marquee/轮播动画
     * 占着，uiautomator dump 永远报 "could not get idle state"，拿不到 UI 层级；
     * 而模块运行在 App 进程里，可以直接把 View 树打出来。
     * 只打一次、只打屏幕上半部分，避免刷屏。
     */
    /**
     * v1.9.21：「我的」页 View 树转储改成了**运行时开关**，不再需要为了诊断重新编译。
     *
     * 发版时保持关闭 —— 开销只是偶尔一次 File.exists()，可以忽略；
     * 适配新版本时在手机上建一个空文件即可打开（不用改代码、不用重新构建）：
     *   adb shell su -c 'touch /data/data/com.xs.fm/cache/dump_mine'
     * 然后进入「我的」页，转储会写到：
     *   /data/data/com.xs.fm/cache/mine_tree.txt
     */
    private static final String DEBUG_DUMP_MINE_FILE = "dump_mine";

    private boolean mineDumpWanted(Activity act) {
        try {
            return new java.io.File(act.getCacheDir(), DEBUG_DUMP_MINE_FILE).exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 各 Activity 上一次转储的时间。
     *
     * 注意必须**分 Activity 记录**：一开始用了全局一个 long，结果 map 遍历顺序固定的那个
     * Activity 每次都抢先、把限频占满，其他页面永远拿不到转储。
     */
    private final java.util.Map<String, Long> lastDumpTimeByAct = new java.util.HashMap<>();

    /**
     * v1.9.21 诊断：把**当前 Activity 的整个 View 树**写一份到 /data/data/com.xs.fm/cache/tree_&lt;Activity&gt;.txt。
     *
     * 不再限定「我的」页 —— 调「我的」页时要看「我的」页，改首页时要看首页，
     * 而 uiautomator 因为轮播/marquee 动画永远拿不到 idle。这份转储是唯一可靠的层级证据。
     * 每 3 秒重写一次（文件名带 Activity 名），所以随时切页、随时拉最新的就能看到那一页的真实结构。
     */
    private void dumpMineTreeOnce(Activity act) {
        if (act == null || !mineDumpWanted(act)) return;
        String key = act.getClass().getName();
        long now = System.currentTimeMillis();
        Long last = lastDumpTimeByAct.get(key);
        if (last != null && now - last < 3000) return;
        lastDumpTimeByAct.put(key, now);
        try {
            StringBuilder sb = new StringBuilder();
            dumpViewTree(act.getWindow().getDecorView(), 0, sb);
            // LSPosed 日志会丢/截断，而这份转储动辄六七百行、会直接刷爆日志区，
            // 所以**只写文件、不往 XposedBridge 里逐行打**（root 可读，最可靠）。
            String name = "tree_" + act.getClass().getSimpleName() + ".txt";
            java.io.File f = new java.io.File(act.getCacheDir(), name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {}
    }

    /** 当前进程内所有还活着的 Activity。 */
    private java.util.List<Activity> getAllActivities() {
        java.util.List<Activity> out = new java.util.ArrayList<>();
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            Field af = atClass.getDeclaredField("mActivities");
            af.setAccessible(true);
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) af.get(at);
            if (map == null) return out;
            for (Object ref : map.values()) {
                Field arf = ref.getClass().getDeclaredField("activity");
                arf.setAccessible(true);
                Activity a = (Activity) arf.get(ref);
                if (a != null && !a.isFinishing() && !a.isDestroyed()) out.add(a);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * v1.9.21 新增：【版本无关】的「我的」页区块隐藏。
     *
     * 为什么不能再靠资源 id：番茄每版都会重排/改名（6.7.1.32 用 br7/e4q/ej2/ce4，
     * 6.7.2.32 又换成 bsf/e87/e4q，而且同一个 e4q 在两版里指向的东西完全不同）。
     * 硬编码 id 每版都得重新逆向，改错一处就留一片空白。
     *
     * 改用「文案 + 结构」定位：
     *   ① 在「我的」页里找到锚点文案（我的资产 / 我的消息 / 尊享免广告 / 邀请好友…）；
     *   ② 从这个 TextView 向上爬，取「最高一层还没爬到页面外壳的卡片容器」的祖先
     *      （页面外壳 = 含「个人主页」头像行、或含顶部标签栏「全部/听书」的容器）；
     *   ③ 把这张卡片整块 GONE，并把它的资源 id 登记进「强制隐藏」集合 ——
     *      App 重建/重排时再由 setVisibility / addView 两个钩子压回去。
     *
     * 这样即使以后版本把资源 id 全换掉，只要这几块卡片的文案还在，就能继续生效。
     */
    private long lastMineGenericTime = 0;

    private void hideMineGenericBlocks(Activity act, int[] cnt) {
        if (act == null) return;
        try {
            // 限频：这一轮要做多次「全子树文本查找」，没必要每次 hideAll 都跑
            long now = System.currentTimeMillis();
            if (now - lastMineGenericTime < 2500) return;
            View decor = act.getWindow().getDecorView();
            if (!looksLikeMinePage(decor)) return;
            lastMineGenericTime = now;
            // ① 主手段：把「头像行所在容器」里除头像行以外的子块全部收掉（版本无关）。
            View scope = findMineTopBlockContainer(decor);
            if (scope != null) hideMineSiblingBlocks(scope, cnt);
            // ② 后手：按锚点文案再扫一遍（限定在上面那个容器里，找不到容器时才全页扫），
            //    兜住「卡片不在头像行容器里」的版本。
            View searchRoot = scope != null ? scope : decor;
            for (String label : MINE_BLOCK_LABELS) {
                View tv = findTextViewByText(searchRoot, label, 0);
                if (tv != null) hideMineBlockByText(tv, label, cnt);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * v1.9.21：【版本无关·主手段】定位「我的」页顶部那一整块卡片区的容器。
     *
     * 思路：拿住「个人主页」——这句文案只出现在头像/昵称行里，换版本也换不掉。
     * 从它往上爬到「头像行」那一层（整宽、很扁的容器），再取它的父容器 ——
     * 那个父容器就是「装着头像行 + 所有促销卡/入口卡/资产卡」的顶部块
     * （6.7.2.32 实测为 CommonCustomAppBarLayout#c1 > LinearLayout#e63）。
     *
     * 拿到这个容器后，只要把「除头像行以外的子块」全部收掉就行 ——
     * 不用再管它这一版叫什么名字、分成了几块，App 以后再往这里塞新广告位也照样自动收掉。
     */
    private View findMineTopBlockContainer(View decor) {
        if (decor == null) return null;
        try {
            View anchor = findTextViewByText(decor, "个人主页", 0);
            if (anchor == null) anchor = findTextViewByText(decor, "介绍一下自己吧", 0);
            if (anchor == null) return null;
            View root = decor.getRootView() != null ? decor.getRootView() : decor;
            int sw = root.getWidth(), sh = root.getHeight();
            if (sw <= 0 || sh <= 0) return null;
            // 爬到「头像行」：宽度≥ 75% 屏宽且高度 ≤ 15% 屏高
            View row = null;
            View cur = anchor;
            for (int i = 0; i < 6 && cur != null; i++) {
                if (cur.getWidth() >= sw * 0.75f && cur.getHeight() > 0 && cur.getHeight() <= sh * 0.15f) {
                    row = cur;
                    break;
                }
                cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            }
            if (row == null) return null;
            View parent = row.getParent() instanceof View ? (View) row.getParent() : null;
            if (!(parent instanceof ViewGroup)) return null;
            if (((ViewGroup) parent).getChildCount() < 2) return null;      // 只有一个孩子 → 不是容器
            if (parent.getWidth() < sw * 0.75f) return null;
            return parent;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * v1.9.21：【版本无关·主手段】把顶部块容器里除「头像行 / 标签栏 / 底部导航」以外的子块全部 GONE。
     *
     * 为什么不能只靠资源 id：6.7.1.32 用 br7/e4q/ej2/ce4，6.7.2.32 换成 bsf/e87/e4q，
     * 而且同一个 e4q 在两版里指向的东西完全不同 —— 每版都要重新逆向，改错一处就留一片空白。
     * 这一层直接用「兄弟关系」说话，与 id 名字无关。
     */
    private void hideMineSiblingBlocks(View scope, int[] cnt) {
        if (!(scope instanceof ViewGroup)) return;
        try {
            ViewGroup g = (ViewGroup) scope;
            int hidden = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                if (child == null) continue;
                if (isMineKeepChild(child)) continue;
                if (child.getVisibility() == View.GONE) continue;
                child.setVisibility(View.GONE);
                if (child.getId() != View.NO_ID) addForceHideId(child.getId());
                collapseEmptyAncestors(child, cnt);
                hidden++;
                XposedBridge.log("[" + TAG + "] 已隐藏我的页顶部区块(与头像行同级): "
                        + child.getClass().getName() + " w=" + child.getWidth() + " h=" + child.getHeight());
            }
            cnt[0] += hidden;
        } catch (Throwable ignored) {}
    }

    /**
     * 顶部块容器里必须保留的子块 —— 完全按文案组合判定，与资源 id 无关。
     *
     * 目前只保留「头像/昵称行」；标签栏和底部导航没出现在这个容器里，但万一哪一版挪进来了，
     * 这里也能拦住。宁可不藏，也不要误伤。
     */
    private boolean isMineKeepChild(View child) {
        if (child == null) return true;
        try {
            if (treeHasText(child, "个人主页", 0)) return true;      // 头像/昵称行
            if (treeHasText(child, "介绍一下自己吧", 0)) return true;
            if (treeHasText(child, "全部", 0) && treeHasText(child, "听书", 0)) return true;   // 标签栏
            if (treeHasText(child, "首页", 0) && treeHasText(child, "我的", 0)) return true;    // 底部导航
            // 万一以后把「设置/反馈/继续播放」也放进这个容器，不要误伤
            if (treeHasText(child, "继续播放", 0) || treeHasText(child, "设置", 0)
                    || treeHasText(child, "设置与反馈", 0) || treeHasText(child, "意见反馈", 0)
                    || treeHasText(child, "帮助与反馈", 0)) return true;
        } catch (Throwable ignored) {
            return true;   // 判定不了就保留（安全侧）
        }
        return false;
    }

    /**
     * v1.9.21：【版本无关】当前页面是不是「我的」页 —— 只看文案，不看资源 id。
     *
     * 「我的资产」只在「我的」页出现；万一以后 App 改了这句文案，再用
     * 「个人主页 + 金币余额」这个组合兜底（两者同时出现也只在「我的」页）。
     * 之前用 e4q/e4r 这类资源 id 判定，每换一版就失效一次，是这次踩的最大坑。
     */
    private boolean looksLikeMinePage(View decor) {
        if (decor == null) return false;
        try {
            if (treeHasText(decor, "我的资产", 0)) return true;
            return treeHasText(decor, "个人主页", 0) && treeHasText(decor, "金币余额", 0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 「我的」页要整块收掉的区块：按锚点文案找（换版本也换不掉这些文案）。
     *
     * 分组说明：
     *   ① 板块标题：爬到「卡片容器」那一层整块收掉；
     *   ② 快捷入口子项：爬到「快捷入口栏」那一层整条收掉；
     *   ③ 促销/福利文案：爬到 VIP 促销卡、签到条那一层。
     * 刻意不放「VIP」「福利」这种太短的词（两三个字很容易在别的模块撞上），
     * 改用「尊享免广告」「无限下载」「立即签到」这种只在本页出现的短语。
     */
    private static final String[] MINE_BLOCK_LABELS = {
            "我的资产", "邀请好友", "好友管理", "剩余时长",
            "我的消息", "游戏中心", "优惠券", "购物车", "商城",
            "尊享免广告", "无限下载", "立即开通", "立即签到",
    };

    private void hideMineBlockByText(View v, String label, int[] cnt) {
        if (v == null) return;
        try {
            View root = v.getRootView();
            if (root == null) return;
            int sw = root.getWidth(), sh = root.getHeight();
            if (sw <= 0 || sh <= 0) return;
            // 位置护栏：只处理「真的显示在屏幕上」的文案。
            // 底部四个 Tab 共用一个 decor，别的 Tab 里也有「商城/购物车」这类词，
            // 靠这个护栏挡住屏幕外那些同名节点。
            if (!v.isShown()) return;
            View block = null;
            View cur = v;
            for (int i = 0; i < 8; i++) {
                View parent = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                if (parent == null) break;
                if (mineChromePresent(parent)) break;      // 再往上就是头像行/标签栏，绝不越过
                if (cur.getWidth() >= sw * 0.75f && cur.getHeight() > 0 && cur.getHeight() <= sh * 0.45f) {
                    block = cur;                           // 这一层还算「卡片」
                }
                cur = parent;
            }
            if (block == null || block == v || block.getVisibility() == View.GONE) return;
            block.setVisibility(View.GONE);
            int bid = block.getId();
            if (bid != View.NO_ID) addForceHideId(bid);   // 重建后由 addView / setVisibility 钩子继续压
            cnt[0]++;
            XposedBridge.log("[" + TAG + "] 已隐藏我的页区块['" + label + "'] "
                    + block.getClass().getName() + " w=" + block.getWidth() + " h=" + block.getHeight()
                    + (bid != View.NO_ID ? (" id=0x" + Integer.toHexString(bid)) : ""));
            collapseEmptyAncestors(block, cnt);           // 【去空白】把外面剩下的空壳一起收掉
        } catch (Throwable ignored) {}
    }

    /** 当前节点是否已经爬到「我的」页的页面外壳（头像行 / 顶部标签栏）。 */
    private boolean mineChromePresent(View v) {
        if (v == null) return true;
        try {
            if (treeHasText(v, "个人主页", 0)) return true;
            return treeHasText(v, "全部", 0) && treeHasText(v, "听书", 0);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 把资源 id 追加进「强制隐藏」集合（App 重建/重排后继续压 GONE）。 */
    private void addForceHideId(int id) {
        if (id == View.NO_ID) return;
        Set<Integer> ids = cachedHideIds;
        if (ids == null) {
            ids = new HashSet<>();
            cachedHideIds = ids;
        }
        ids.add(id);
    }

    /** 找到第一个包含指定文案的 TextView。 */
    private View findTextViewByText(View v, String needle, int depth) {
        if (v == null || depth > 22) return null;
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null && cs.toString().contains(needle)) return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findTextViewByText(g.getChildAt(i), needle, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * v1.9.21 诊断用：每 2 秒轮询一次，直到把「我的」页视图树抓到为止。
     * 由 App 缓存目录里的 {@link #DEBUG_DUMP_MINE_FILE} 开关文件控制（见 dumpMineTreeOnce）。
     */
    private void scheduleMineTreeDump() {
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // 先用 Application 的 cacheDir 看一下开关文件在不在 ——
                    // 没开就什么都不做（一个 File.exists()，开销可忽略）。
                    Object app = Class.forName("android.app.ActivityThread")
                            .getMethod("currentApplication").invoke(null);
                    if (app instanceof android.content.Context
                            && new java.io.File(((android.content.Context) app).getCacheDir(),
                                    DEBUG_DUMP_MINE_FILE).exists()) {
                        for (Activity a : getAllActivities()) dumpMineTreeOnce(a);
                    }
                } catch (Throwable ignored) {}
                h.postDelayed(this, 2000);        // 常驻；开关没开时每 2 秒只做一次 File.exists()
            }
        }, 2000);
    }

    /** 在 View 树里查找包含指定文案的 TextView（深度受限，只用于「我的」页识别）。 */
    private boolean treeHasText(View v, String needle, int depth) {
        if (v == null || depth > 22) return false;
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null && cs.toString().contains(needle)) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (treeHasText(g.getChildAt(i), needle, depth + 1)) return true;
            }
        }
        return false;
    }

    private void dumpViewTree(View v, int depth, StringBuilder sb) {
        if (v == null || depth > 20) return;
        int[] loc = new int[2];
        try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
        if (loc[1] > 2300) return;                       // 跳过屏幕外/最底部的装饰节点
        String id = "-";
        try {
            if (v.getId() != View.NO_ID) id = v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {}
        String txt = "";
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null) {
                String t = cs.toString().replace('\n', ' ').trim();
                if (t.length() > 30) t = t.substring(0, 30);
                if (t.length() > 0) txt = " '" + t + "'";
            }
        }
        sb.append(depth).append(' ').append(v.getClass().getName()).append('#').append(id)
                .append(v.getVisibility() == View.GONE ? " GONE"
                        : v.getVisibility() == View.INVISIBLE ? " INVIS" : "")
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append(" @").append(loc[0]).append(',').append(loc[1]).append(txt).append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), depth + 1, sb);
        }
    }

    /**
     * v1.9.20：「我的」页头部需要屏蔽的入口文案。
     * 只用于「我的」页（见 {@link #isMinePage}），因此可以放心放宽到「含有」匹配。
     */
    private boolean isMineHeaderBlockText(String t) {
        if (t == null || t.length() > 12) return false;
        if (t.contains("我的消息")) return true;
        if (t.contains("游戏中心")) return true;
        // 注意：「我的资产」故意不放这里 —— 它的就近可点击祖先不存在，走浪漫兜底会
        // 一路爬过入口行/卡片直到页头包装容器（e2n，高 568），把头像/昵称一起吞掉。
        // 它改为在 hideKnownAdResources 里按精确资源 id（e4q）隐藏。
        if (t.contains("中秋") || t.contains("好礼")) return true;   // 服务端活动入口（如「中秋好礼」）
        return false;
    }

    /**
     * 当前显示的页面是否「我的」页。
     *
     * 判定依据：优先用 #e4q（快捷入口栏），回退 #e4r（头像+昵称 容器）—— 两个都是「我的」页
     * 独有的资源 id（首页/阅读页/听歌页均不存在），用它做闸门可以确保
     * {@link #isMineHeaderBlockText} 的宽匹配不会误伤正文小说文本。
     *
     * ⚠️ v1.9.21：6.7.2.32 的「我的」页已经**没有 e4r 了**（实测 View 树里查无此 id），
     * 只用 e4r 判定会让整个「我的」页分支永远是 false —— 「我的消息/游戏中心」的文本规则
     * 静默失效。所以改成 e4q 优先。
     */
    private boolean isMinePage(Activity act) {
        if (act == null) return false;
        try {
            View decor = act.getWindow().getDecorView();
            if (decor == minePageDecor) return minePageFlag;   // 同一帧内缓存，避免每行文本都 findViewById
            boolean isMine = false;
            for (String marker : new String[]{"e4q", "e4r"}) {
                int id = act.getResources().getIdentifier(marker, "id", act.getPackageName());
                if (id > 0 && decor.findViewById(id) != null) { isMine = true; break; }
            }
            minePageDecor = decor;
            minePageFlag = isMine;
            return isMine;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 「我的」页头部入口定向隐藏：不走 hideChain（它是空操作），而是就近藏「可点击入口」这张卡片。
     * 绝不越过 AppBar，避免像 v1.9.9 那样把整条页头 GONE 掉。
     */
    private void hideMineHeaderEntry(View v, String t, int[] cnt, Activity act) {
        try {
            int sh = 0;
            try { sh = act != null ? act.getWindow().getDecorView().getHeight() : 0; } catch (Throwable ignored) {}
            View target = null;
            View cur = v;
            // 1) 优先：最近的「可点击入口」祖先（我的消息/游戏中心 入口卡、活动入口卡）
            //    高度护栏：超过 25% 屏高的可点击容器多半是整块页头/页面容器，不收。
            for (int i = 0; i < 6 && cur != null; i++) {
                if (cur != v && cur.isClickable()
                        && (sh <= 0 || (cur.getHeight() > 0 && cur.getHeight() <= sh * 0.25f))) {
                    target = cur; break;
                }
                View p = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                if (p == null || isAppBarLike(p)) break;
                cur = p;
            }
            // 2) 兜底：没有可点击入口时，取就近「高度 <= 15% 屏高」的卡片/横幅；
            //    绝不拿到更大的容器 —— 否则会把整块页头（头像/昵称）一起 GONE 掉。
            if (target == null) {
                cur = v;
                for (int i = 0; i < 6 && cur != null; i++) {
                    View p = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                    if (p == null || isAppBarLike(p)) break;
                    if (sh > 0 && p.getHeight() > 0 && p.getHeight() <= sh * 0.15f) target = p;
                    cur = p;
                }
            }
            if (target == null || target.getVisibility() == View.GONE) return;
            target.setVisibility(View.GONE);
            cnt[0]++;
            XposedBridge.log("[" + TAG + "] 已隐藏我的页入口['" + t + "'] " + target.getClass().getName()
                    + " w=" + target.getWidth() + " h=" + target.getHeight());
        } catch (Throwable ignored) {}
    }

    /** 通过资源 ID 找到 View 后，向上隐藏 N 层父容器 */
    private void hideByResIdUp(Activity act, String resName, int[] cnt, String desc, int upLevels) {
        try {
            int resId = act.getResources().getIdentifier(resName, "id", act.getPackageName());
            if (resId <= 0) return;
            View target = act.getWindow().getDecorView().findViewById(resId);
            if (target == null || target.getVisibility() == View.GONE) return;
            // 向上找 N 层父容器来隐藏
            View toHide = target;
            for (int i = 0; i < upLevels; i++) {
                View parent = (View) toHide.getParent();
                if (parent == null) break;
                // ⚠️ v1.9.9 安全护栏：绝不向上吞掉 AppBar/Toolbar 类容器。
                //    这类容器是整页头部（"我的"页的 CommonCustomAppBarLayout 里同时装着
                //    头像/昵称/个人主页入口/我的消息/VIP卡/资产卡），一旦 GONE 整个页头消失。
                String pCls = parent.getClass().getName();
                if (pCls.contains("AppBarLayout") || pCls.contains("Toolbar")
                        || pCls.contains("ActionBar") || pCls.contains("TabLayout")) break;
                // 不要越过页面级容器（宽>=90%屏宽且高>=70%屏高）
                int[] loc = new int[2];
                try { parent.getLocationOnScreen(loc); } catch (Throwable ignored2) {}
                View decor = act.getWindow().getDecorView();
                int sw = decor.getWidth(), sh = decor.getHeight();
                int pw = parent.getWidth(), ph = parent.getHeight();
                if (pw >= sw * 0.9f && ph >= sh * 0.7f) break; // 页面级，停
                toHide = parent;
            }
            if (toHide.getVisibility() != View.GONE) {
                toHide.setVisibility(View.GONE);
                cnt[0]++;
                int[] loc = new int[2];
                try { target.getLocationOnScreen(loc); } catch (Throwable ignored2) {}
                XposedBridge.log("[" + TAG + "] 已隐藏" + desc + "(id=" + resName + ") "
                        + toHide.getClass().getName() + " w=" + toHide.getWidth() + " h=" + toHide.getHeight()
                        + " loc=[" + loc[0] + "," + loc[1] + "]");
                // 动态 hook 防重建
                hookViewClass(toHide.getClass().getName());
            }
        } catch (Throwable ignored) {}
    }

    /** 检测全屏覆盖广告：仅在听歌页/播放页执行，跳过其他页面减少开销 */
    private void detectOverlayAds(Activity act, int[] cnt) {
        String actName = act.getClass().getName();
        // 只在听歌相关页面检测，首页/我的/阅读页不需要
        if (!actName.contains("MainFragmentActivity") && !actName.contains("AudioPlay")
                && !actName.contains("MusicPlayer")) return;
        View decor = act.getWindow().getDecorView();
        int sw = decor.getWidth(), sh = decor.getHeight();
        if (sw <= 0 || sh <= 0) return;
        detectOverlayRecursive(decor, 0, sw, sh, cnt);
    }

    private void detectOverlayRecursive(View v, int depth, int sw, int sh, int[] cnt) {
        if (v == null || depth > 15) return;
        if (v.getVisibility() != View.VISIBLE) return;
        int w = v.getWidth(), h = v.getHeight();
        if (w < sw * 0.8f || h < sh * 0.4f) { // 不够大不算覆盖广告
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++)
                    detectOverlayRecursive(vg.getChildAt(i), depth + 1, sw, sh, cnt);
            }
            return;
        }
        // 这个 View 足够大（宽>=80%屏宽 且 高>=40%屏高）
        String cls = v.getClass().getName();
        // 排除已知的非广告大容器
        boolean isBasicLayout = cls.contains("RecyclerView") || cls.contains("ViewPager")
                || cls.contains("CoordinatorLayout") || cls.contains("DecorView")
                || cls.contains("ContentFrameLayout") || cls.contains("BackView")
                || cls.contains("ConstraintLayout");
        if (isBasicLayout) {
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++)
                    detectOverlayRecursive(vg.getChildAt(i), depth + 1, sw, sh, cnt);
            }
            return;
        }
        // FrameLayout/LinearLayout 等常见广告容器：检查是否含广告相关内容
        boolean mayBeAdContainer = cls.contains("FrameLayout") || cls.contains("LinearLayout")
                || cls.contains("RelativeLayout");
        if (mayBeAdContainer) {
            // 检查子节点是否有广告文本
            boolean hasAdContent = false;
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount() && !hasAdContent; i++) {
                    hasAdContent = viewContainsAdText(vg.getChildAt(i), 0);
                }
            }
            if (!hasAdContent) {
                // 非广告容器，继续往下找
                if (v instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++)
                        detectOverlayRecursive(vg.getChildAt(i), depth + 1, sw, sh, cnt);
                }
                return;
            }
        }
        // 非基础容器但很大 → 可能是广告覆盖层
        String key = "overlay_" + cls + "_" + w + "x" + h;
        if (!seenGold.contains(key)) {
            seenGold.add(key);
            int[] loc = new int[2];
            try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
            XposedBridge.log("[" + TAG + "] [侦察] 全屏覆盖View: " + cls
                    + " w=" + w + " h=" + h + " loc=[" + loc[0] + "," + loc[1] + "]"
                    + " clickable=" + v.isClickable());
        }
        // 如果是可点击的大面积非基础 View，直接隐藏
        if (v.isClickable() || v.hasOnClickListeners()) {
            if (v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
                cnt[0]++;
                XposedBridge.log("[" + TAG + "] 已隐藏全屏覆盖广告: " + cls
                        + " w=" + w + " h=" + h);
            }
        }
    }

    /** 按资源名精确查找并隐藏 View（通过反射在 View 树中查找） */
    private void hideByResId(Activity act, String resName, int[] cnt, String desc) {
        try {
            int resId = act.getResources().getIdentifier(resName, "id", act.getPackageName());
            if (resId <= 0) return;
            View decor = act.getWindow().getDecorView();
            View target = decor.findViewById(resId);
            if (target != null && target.getVisibility() != View.GONE) {
                String fullCls = target.getClass().getName();
                target.setVisibility(View.GONE);
                cnt[0]++;
                int[] loc = new int[2];
                try { target.getLocationOnScreen(loc); } catch (Throwable ignored2) {}
                XposedBridge.log("[" + TAG + "] 已隐藏" + desc + "(id=" + resName + ") "
                        + fullCls
                        + " w=" + target.getWidth() + " h=" + target.getHeight()
                        + " loc=[" + loc[0] + "," + loc[1] + "]");
                // 动态 hook 该类的构造函数，防止重建后再次显示
                hookViewClass(fullCls);
            }
        } catch (Throwable ignored) {}
    }

    /** 动态 hook 指定类的构造函数，创建即 GONE */
    private void hookViewClass(String fullClassName) {
        try {
            // ⚠️ 安全护栏（v1.9.9 新增，必须保留）：
            // 绝不能对框架/标准控件类做「构造即 GONE」的 hook。
            // 否则 hideByResId 隐藏 bpz(ConstraintLayout) / e33(LinearLayout) 后会把
            // androidx.constraintlayout.widget.ConstraintLayout、android.widget.LinearLayout
            // 的构造函数全部 hook，导致全 App 布局整体消失（灾难性）。
            if (fullClassName == null) return;
            if (!isAdRelatedViewClass(fullClassName)) return;
            Class<?> cls = XposedHelpers.findClassIfExists(fullClassName, appCl);
            if (cls == null) return;
            // 避免重复 hook
            String key = "hooked_" + fullClassName;
            if (seenGold.contains(key)) return;
            seenGold.add(key);
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ((android.view.View) param.thisObject).setVisibility(android.view.View.GONE);
                        XposedBridge.log("[" + TAG + "] 动态hook创建即GONE: " + fullClassName);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[" + TAG + "] 已动态注册hook: " + fullClassName);
        } catch (Throwable ignored) {}
    }

    /**
     * 判断某个 View 类是否属于「广告相关」——只有这类才允许做「构造即 GONE」的 hook。
     * 白名单式匹配，框架/标准控件（android.*、androidx.*、kotlin.*、com.lynx.* 等）一律拒绝。
     */
    private boolean isAdRelatedViewClass(String n) {
        if (n == null) return false;
        // 明确拒绝框架/通用类
        if (n.startsWith("android.") || n.startsWith("androidx.")
                || n.startsWith("java.") || n.startsWith("kotlin.")
                || n.startsWith("com.google.android.material.")
                || n.startsWith("com.lynx.") || n.startsWith("org.chromium.")
                || n.startsWith("com.bytedance.ies.")) {
            return false;
        }
        // 只放行广告相关包/类名
        String l = n.toLowerCase();
        return n.contains("admodule") || n.contains("ad.feedbanner") || n.contains("ad.freead")
                || n.contains("unlocktime") || n.contains("entranceview")
                || n.contains("AdPatch") || n.contains("PatchAd") || n.contains("MusicPatchAd")
                || n.contains("BookMallAd") || n.contains("FreeAd") || n.contains("Inspire")
                || n.contains("luckycat") || n.contains("Mannor") || n.contains("mannor")
                || l.contains("adview") || l.contains("adcard") || l.contains("adfloat")
                || l.contains(".ad.") || l.contains(".ads.");
    }

    /** 扫描进程内所有窗口根View（覆盖悬浮窗/额外 window 里的领金币入口）；反射结果已缓存 */
    private void scanAllWindows(Activity act, int[] cnt) throws Exception {
        // 首次调用时缓存 WindowManagerGlobal 单例和 mViews 字段，避免每次反射
        if (cachedWmgInstance == null) {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            cachedWmgInstance = wmg.getMethod("getInstance").invoke(null);
            cachedMViewsField = wmg.getDeclaredField("mViews");
            cachedMViewsField.setAccessible(true);
        }
        Object o = cachedMViewsField.get(cachedWmgInstance);
        if (!(o instanceof java.util.List)) return;
        java.util.List<?> list = (java.util.List<?>) o;
        for (int i = 0; i < list.size(); i++) {
            Object v;
            try { v = list.get(i); } catch (Throwable t) { break; }
            if (v instanceof View) {
                try {
                    scanAll((View) v, 0, cnt, act);
                    scanRadioGroup((View) v, cnt);
                } catch (Throwable ignored) {}
            }
        }
    }

    private void scanAll(View v, int depth, int[] cnt, Activity act) {
        if (v == null || depth > 25) return;
        if (coinEntryId == -1 && act != null) {
            try { coinEntryId = act.getResources().getIdentifier("h80", "id", act.getPackageName()); }
            catch (Throwable t) { coinEntryId = 0; }
        }
        if (coinEntryId > 0 && v.getId() == coinEntryId) {
            try {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                View dec = act.getWindow().getDecorView();
                int sw = dec.getWidth(), sh = dec.getHeight();
                boolean topRight = loc[1] >= 0 && loc[1] < sh * 0.18f && ((loc[0] + v.getWidth()) > sw * 0.5f || loc[0] < sw * 0.35f); // 顶部左右角均视为金币入口(阅读页左上角/主页右上角)
                if (topRight && v.getVisibility() != View.GONE) {
                    v.setVisibility(View.GONE);
                    cnt[0]++;
                    XposedBridge.log("[" + TAG + "] 已隐藏金币入口(h80) loc=[" + loc[0] + "," + loc[1] + "] cls=" + v.getClass().getName());
                }
            } catch (Throwable ignored) {}
            return;
        }
        try {
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) {
                    String t = cs.toString().trim();
                    if (t.length() > 0 && t.length() <= 20) {
                        tryAutoSkip(v, t);
                        // v1.9.20："我的"页头部入口（我的消息/游戏中心/我的资产/活动入口）单独走定向隐藏。
                        // 这些入口在页头 AppBar 内，通用 hideChain 会因顶栏保护整链放弃（日志："跳过隐藏(位于顶栏内)"），
                        // 所以必须在 shouldHide 前面拦下来。
                        if (isMineHeaderBlockText(t) && isMinePage(act)) {
                            hideMineHeaderEntry(v, t, cnt, act);
                            return;
                        }
                        if (shouldHide(t)) {
                            if ((t.contains("看小视频") || (t.contains("免") && t.contains("分钟") && t.contains("广告")))
                                    && act != null) {
                                hideWholeWidget(v, t, cnt, act);
                                return;
                            }
                            if ((t.contains("金币") || t.contains("领取") || t.contains("免费"))
                                    && isTopRightCorner(v, act)) {
                                hideTopRightWidget(v, t, cnt, act);
                                return;
                            }
                            boolean adCard = (t.contains("全天") && t.contains("畅听"))
                                    || t.contains("领金币") || t.contains("逛街赚金币")
                                    || (t.contains("可领") && t.contains("金币"))
                                    || t.contains("领红包") || t.contains("去赚钱");
                            boolean shallow = t.contains("金币")
                                    || (t.contains("广告") && (t.contains("免") || t.contains("看")));
                            if (adCard) hideAdCard(v, t, cnt, act);
                            else if (shallow) hideShallow(v, t, cnt, act);
                            else hideEntry(v, t, cnt, act);
                            return;
                        } else if (t.contains("广告") && !seenGold.contains(t)) {
                            seenGold.add(t);
                            XposedBridge.log("[" + TAG + "] 侦察金币文本: '" + t + "' cls=" + v.getClass().getName()
                                    + " parent=" + (v.getParent() != null ? v.getParent().getClass().getName() : "null"));
                        }
                    }
                }
            }
            if (v.getContentDescription() != null) {
                String cd = v.getContentDescription().toString().trim();
                if (cd.length() > 0 && cd.length() <= 20 && shouldHide(cd)) {
                    if ((cd.contains("金币") || cd.contains("领取") || cd.contains("免费"))
                            && isTopRightCorner(v, act)) {
                        hideTopRightWidget(v, cd, cnt, act);
                        return;
                    }
                    boolean adCardCd = (cd.contains("全天") && cd.contains("畅听"))
                            || cd.contains("领金币") || cd.contains("逛街赚金币")
                            || (cd.contains("可领") && cd.contains("金币"))
                            || cd.contains("领红包") || cd.contains("去赚钱");
                    boolean shallowCd = cd.contains("金币")
                            || (cd.contains("广告") && (cd.contains("免") || cd.contains("看")));
                    if (adCardCd) hideAdCard(v, cd, cnt, act);
                    else if (shallowCd) hideShallow(v, cd, cnt, act);
                    else hideEntry(v, cd, cnt, act);
                    return;
                }
            }
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                scanAll(vg.getChildAt(i), depth + 1, cnt, act);
            }
        }
    }

    private void scanRadioGroup(View v, int[] cnt) {
        if (v == null) return;
        if (v instanceof RadioGroup) {
            ViewGroup rg = (ViewGroup) v;
            for (int i = 0; i < rg.getChildCount(); i++) {
                View c = rg.getChildAt(i);
                String txt = findText(c);
                if (txt != null) {
                    // 仅保留核心tab：首页/听歌/我的；其余一律隐藏
                    boolean keep = txt.equals("首页") || txt.equals("听歌") || txt.equals("我的")
                            || txt.equals("书城") || txt.equals("音乐");
                    if (!keep && !txt.isEmpty()) {
                        if (c.getVisibility() != View.GONE) {
                            c.setVisibility(View.GONE);
                            cnt[0]++;
                            XposedBridge.log("[" + TAG + "] 已隐藏非核心底部tab['" + txt + "'] view=" + c.getClass().getName());
                        }
                    }
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                scanRadioGroup(vg.getChildAt(i), cnt);
            }
        }
    }

    private String findText(View v) {
        if (v == null) return null;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            return cs == null ? null : cs.toString().trim();
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String t = findText(vg.getChildAt(i));
                if (t != null && t.length() > 0) return t;
            }
        }
        return null;
    }

    private boolean shouldHide(String t) {
        // 核心导航标签 —— 绝不隐藏
        if (t.equals("首页") || t.equals("听歌") || t.equals("我的")) return false;
        // 子分类标签 —— 不隐藏（推荐/听书/音乐/短剧/看书/漫剧/分类）
        if (t.equals("推荐") || t.equals("听书") || t.equals("音乐") || t.equals("短剧")
                || t.equals("看书") || t.equals("漫剧") || t.equals("分类")) return false;
        // ⚠️ v1.9.10 说明：这里**不能**用「文案含『登录』就放过」的粗粒度白名单。
        //    「登录领取」既是「我的」页头部里的账号入口，也是首页/我的页右侧那个
        //    浮动红包广告的文案。粗粒度白名单会把广告一起放过（实测：首页/我的页
        //    的「登录领取」红包又冒出来了）。
        //    正确做法是在 hideChain 里做「整链放弃」的结构判定 —— 见 hideChain 开头。
        // === 金币/赚钱类 ===
        if (t.contains("金币")) return true;
        if (t.contains("赚") && (t.contains("金币") || t.contains("钱") || t.contains("取"))) return true;
        if (t.contains("可领") || t.contains("待领") || t.contains("去领")) return true;
        // === 福利/红包/签到类 ===
        if (t.contains("福利") && t.length() <= 10) return true;
        if (t.contains("红包") || t.contains("签到") || t.contains("邀请")) return true;
        if (t.contains("新人") || t.contains("首单")) return true;
        // === 畅听/VIP广告 ===
        if (t.contains("全天") && t.contains("畅听")) return true;
        if (t.contains("免费畅听") || t.contains("免费听") || t.contains("免广告")) return true;
        if (t.contains("看小说") && (t.contains("广告") || t.contains("分钟"))) return true;
        if (t.contains("看小视频") && t.contains("广告")) return true;
        if ((t.contains("看") || t.contains("观看")) && t.contains("免") && t.contains("广告")) return true;
        if (t.contains("畅听") && t.length() <= 6) return true;
        if (t.contains("激励视频") || t.contains("观看视频")) return true;
        if (t.contains("再看") && t.contains("分钟")) return true;
        // === 广告相关 ===
        if (t.contains("广告") && (t.contains("免") || t.contains("看"))) return true;
        if (t.contains("举报广告")) return true;
        // === 商城/购物/金融 ===
        if (t.contains("商城") || t.contains("购物") || t.contains("优惠券")) return true;
        if (t.contains("借钱") || t.contains("公益")) return true;
        if (t.contains("游戏中心") || t.contains("游戏") && t.length() <= 4) return true;
        // === 直播（非听歌/阅读核心功能） ===
        if (t.contains("直播") && t.length() <= 5) return true;
        // === 资产/钱包 ===
        if (t.contains("资产") || t.contains("钱包") || t.contains("购物车")) return true;
        // === 领取/立即领取 ===
        if (t.contains("立即领取") || t.contains("领取") && t.length() <= 6) return true;
        // === 现金（排除带数字的余额显示） ===
        if (t.contains("现金") && !t.matches(".*\\d+.*")) return true;
        // === 做任务/任务 ===
        if (t.contains("做任务") || (t.contains("任务") && t.length() <= 6)) return true;
        // === 上滑商城 ===
        if (t.contains("上滑") && t.contains("商城")) return true;
        return false;
    }

    /** 隐藏入口：底部tab(商城/领现金)无条件只藏自身；普通条目触底隐藏父框 */
    private void hideEntry(View v, String t, int[] cnt, Activity act) {
        if (t.contains("商城") || t.contains("领现金")) {
            hideTabSelf(v, t, cnt);
            return;
        }
        View p = (View) v.getParent();
        if (p == null) return;
        if (p instanceof RadioGroup) {
            hideTabSelf(v, t, cnt);
            return;
        }
        String cls = p.getClass().getName();
        if (cls.contains("TabView")) {
            hideTabSelf(p, t, cnt);
            return;
        }
        hideChain(p, t, cnt, act);
    }

    /** 浅隐藏：自身+直接父容器；父过宽(可能顶栏)或导航tab时只藏自身 */
    private void hideShallow(View v, String t, int[] cnt, Activity act) {
        int screenW = 0;
        try { screenW = act.getWindow().getDecorView().getWidth(); } catch (Throwable ignored) {}
        View p = (View) v.getParent();
        if (p != null && !(p instanceof RadioGroup)
                && screenW > 0 && p.getWidth() < screenW * 0.6f
                && p.getVisibility() != View.GONE
                && !isProtectedContainer(p)) {
            if (p.getVisibility() != View.GONE) {
                p.setVisibility(View.GONE);   // 一律 GONE：菜单自动补位，不留空位
                cnt[0]++;
                XposedBridge.log("[" + TAG + "] 已浅隐藏['" + t + "'] 父=" + p.getClass().getName());
            }
            return;
        }
        hideTabSelf(v, t, cnt);
    }

    /** 广告卡整体隐藏：对 全天畅听/领金币 等入口，向上找广告卡片容器整体GONE(不再只藏文字) */
    private void hideAdCard(View v, String t, int[] cnt, Activity act) {
        int screenW = 0, screenH = 0;
        try { View d = act.getWindow().getDecorView(); screenW = d.getWidth(); screenH = d.getHeight(); } catch (Throwable ignored) {}
        View best = null;
        View cur = v;
        StringBuilder chain = new StringBuilder();
        for (int i = 0; i < 8 && cur != null; i++) {
            int w = cur.getWidth(), h = cur.getHeight();
            int[] loc = new int[2];
            try { cur.getLocationOnScreen(loc); } catch (Throwable ignored2) {}
            boolean pageLike = w >= screenW * 0.9f && h >= screenH * 0.45f;   // 页面级容器不藏
            boolean navLike = loc[1] >= screenH * 0.78f && h < screenH * 0.12f && w >= screenW * 0.8f;
            chain.append("[" + i + "]" + cur.getClass().getSimpleName() + "(" + w + "x" + h + "@" + loc[1] + ") ");
            // 不断更新 best：停在最外层"卡片块"（100px ~ 35% 屏高、非页面级、非底部导航）
            if (h >= 100 && h <= screenH * 0.35f && w < screenW * 0.6f && !pageLike && !navLike && !protectedWithin(cur, 3)) {
                best = cur;
            }
            if (pageLike) break;
            cur = (View) cur.getParent();
        }
        if (best != null && best != v) {
            if (best.getVisibility() != View.INVISIBLE) {
                best.setVisibility(View.INVISIBLE);   // 连背景一起藏 + 保持布局(顶部不上移)
                cnt[0]++;
                XposedBridge.log("[" + TAG + "] 已隐藏广告卡['" + t + "'] " + best.getClass().getName()
                        + " w=" + best.getWidth() + " h=" + best.getHeight() + " chain=" + chain);
            }
            return;
        }
        hideShallow(v, t, cnt, act);
    }

    private void hideTabSelf(View tab, String t, int[] cnt) {
        int[] loc = new int[2];
        int screenH = 0;
        try {
            tab.getLocationOnScreen(loc);
            screenH = tab.getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable ignored) {}
        if (tab.getVisibility() != View.GONE) {
            tab.setVisibility(View.GONE);   // 一律 GONE：菜单自动补位
            cnt[0]++;
            XposedBridge.log("[" + TAG + "] 已隐藏tab['" + t + "'] " + tab.getClass().getName());
        }
    }

    /** 是否位于屏幕上部 30% 区域 */
    private boolean isTopZone(View v, Activity act) {
        int[] loc = new int[2];
        try { v.getLocationOnScreen(loc); } catch (Throwable t) { return false; }
        int screenH = 0;
        try { screenH = act.getWindow().getDecorView().getHeight(); } catch (Throwable t) {}
        return screenH > 0 && loc[1] < screenH * 0.30f;
    }

    /** 整条横幅隐藏：从命中视图向上找「短横幅级」容器整体 GONE（底部全宽允许，顶部栏绝不碰） */
    private void hideWholeWidget(View v, String t, int[] cnt, Activity act) {
        int screenW = 0, screenH = 0;
        try { View d = act.getWindow().getDecorView(); screenW = d.getWidth(); screenH = d.getHeight(); } catch (Throwable ignored) {}
        View best = v;
        View cur = (View) v.getParent();
        for (int i = 0; i < 8 && cur != null; i++) {
            if (protectedWithin(cur, 3)) break;
            int w = cur.getWidth(), h = cur.getHeight();
            int[] loc = new int[2];
            try { cur.getLocationOnScreen(loc); } catch (Throwable ignored2) {}
            boolean pageLike = w >= screenW * 0.9f && h >= screenH * 0.45f;
            if (pageLike) break;
            boolean shortBand = h > 60 && h < screenH * 0.3f;
            boolean widgetOk = (w < screenW * 0.6f) || (loc[1] >= screenH * 0.4f);   // 底部/中部全宽短条允许，顶部全宽拒绝
            if (shortBand && widgetOk) {
                best = cur;
            } else if (h >= screenH * 0.3f) {
                break;
            }
            cur = (View) cur.getParent();
        }
        if (best.getVisibility() != View.GONE) {
            best.setVisibility(View.GONE);
            cnt[0]++;
            XposedBridge.log("[" + TAG + "] 已整条隐藏['" + t + "'] " + best.getClass().getName()
                    + " w=" + best.getWidth() + " h=" + best.getHeight());
        }
    }

    /** 右上角区域判断：顶部22%以内 且 右侧越过62%屏宽 */
    private boolean isTopRightCorner(View v, Activity act) {
        if (v == null || act == null) return false;
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            View decor = act.getWindow().getDecorView();
            int sw = decor.getWidth(), sh = decor.getHeight();
            return sw > 0 && sh > 0 && loc[1] >= 0 && loc[1] < sh * 0.22f
                    && (loc[0] + v.getWidth()) > sw * 0.62f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 右上角金币/广告入口整体隐藏：向上找含底色的 widget 级祖先一并 GONE */
    private void hideTopRightWidget(View v, String t, int[] cnt, Activity act) {
        int screenW = 0, screenH = 0;
        try { View d = act.getWindow().getDecorView(); screenW = d.getWidth(); screenH = d.getHeight(); } catch (Throwable ignored) {}
        View best = v;
        View cur = (View) v.getParent();
        for (int i = 0; i < 5 && cur != null; i++) {
            if (protectedWithin(cur, 2)) break;
            int w = cur.getWidth(), h = cur.getHeight();
            if (screenW > 0 && w < screenW * 0.45f && h < screenH * 0.15f) {
                best = cur;   // 仍是 widget 级容器（含底色），继续扩大
            } else break;
            cur = (View) cur.getParent();
        }
        if (best.getVisibility() != View.GONE) {
            best.setVisibility(View.GONE);
            cnt[0]++;
            XposedBridge.log("[" + TAG + "] 已整体隐藏右上角入口['" + t + "'] " + best.getClass().getName()
                    + " w=" + best.getWidth() + " h=" + best.getHeight());
        }
    }

    /** 触底隐藏父链：向上连藏3层容器；页面级容器保护 + 底部导航栏保护 */
    private void hideChain(View start, String t, int[] cnt, Activity act) {
        // ⚠️ v1.9.10 关键护栏：若 start 位于 AppBar/Toolbar/TabLayout 这类页面顶栏内，
        //    整条 hideChain 直接放弃 —— 一帧都不动。
        //    为什么不能像以前那样在循环里逐层 break：depth0 是文本自身、depth1 是它的
        //    直接父容器，这两层在「我的」页头部是 #h4z(头像+昵称+登录按钮 的
        //    ConstraintLayout)，而真正受保护的 #c1(CommonCustomAppBarLayout) 在更外层，
        //    等循环走到它时，头部内容早已被 GONE 掉了（表现为未登录时顶部整块空白）。
        //    注意这里用 isAppBarLike（只认 AppBar/Toolbar/ActionBar/TabLayout），
        //    故意不用 isProtectedContainer —— 后者把 "TopView" 也算进去，
        //    会误伤 BookMallTopView 里的首页广告位。
        try {
            View p = start;
            int guard = 0;
            while (p != null && guard++ < 40) {
                View par = p.getParent() instanceof View ? (View) p.getParent() : null;
                if (par != null && isAppBarLike(par)) {
                    // v1.9.21：这条会随着看门狗/布局事件每 2 秒重复打印，改成每段文案只打一次。
                    if (loggedTopBarSkipped.add(t)) {
                        XposedBridge.log("[" + TAG + "] 跳过隐藏(位于顶栏内): '" + t + "'");
                    }
                    return;
                }
                p = par;
            }
        } catch (Throwable ignored) {}
        View cur = start;
        int depth = 0;
        int hid = 0;
        int screenW = 0, screenH = 0;
        try {
            View decor = act.getWindow().getDecorView();
            screenW = decor.getWidth();
            screenH = decor.getHeight();
        } catch (Throwable ignored) {}
        while (cur != null && depth < 3) {
            if (screenW > 0 && screenH > 0) {
                int w = cur.getWidth();
                int h = cur.getHeight();
                int[] loc = new int[2];
                try { cur.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                int top = loc[1];
                // 页面级容器保护
                if (w >= screenW * 0.9f && h >= screenH * 0.7f) break;
                // 底部导航栏保护：屏幕坐标 + 屏幕底部 + 全宽 + 扁平容器，不藏
                if (top >= screenH * 0.78f && h < screenH * 0.2f && w >= screenW * 0.8f) break;
                // 兜底：贴底扁平容器(相对坐标也查一次)不藏
                if (cur.getTop() >= screenH * 0.82f && h < screenH * 0.15f && w >= screenW * 0.6f) break;
            }
            if (protectedWithin(cur, 2)) break; // 顶部栏/底部导航/主导航容器保护(含外层包装)
            if (cur.getVisibility() != View.GONE && !isProtectedContent(cur)) {
                cur.setVisibility(View.GONE);
                hid++;
            }
            cur = (View) cur.getParent();
            depth++;
        }
        if (hid > 0) {
            cnt[0] += hid;
            XposedBridge.log("[" + TAG + "] 已触底隐藏['" + t + "'] " + depth + "层 根=" + start.getClass().getName());
        }
    }

    private void patchVip() {
        if (appCl == null) return;
        if (vipPatched) return;  // 成功过一次就不重复反射
        try {
            Class<?> acct = Class.forName("com.dragon.read.user.AcctManager", true, appCl);
            Field instF = acct.getDeclaredField("INSTANCE");
            instF.setAccessible(true);
            Object acctObj = instF.get(null);
            if (acctObj == null) {
                XposedBridge.log("[" + TAG + "] patchVip: AcctManager.INSTANCE == null，稍后重试");
                retry(); return;
            }
            Field umF = acct.getDeclaredField("userModel");
            umF.setAccessible(true);
            Object model = umF.get(acctObj);
            if (model == null) {
                // 关键诊断：AcctManager.userModel 只在 <init>(从 SharedPreferences
                // "key_acct_user" 缓存反序列化) 赋值，以及 clearUserSessionAndBroadcast()
                // 里被换成一个新的空 AcctUserModel（登出）。为 null 极少见。
                XposedBridge.log("[" + TAG + "] patchVip: userModel == null，稍后重试");
                retry(); return;
            }
            // 关键：App 登出时会把 userModel 整个换成新实例，patch 会随之丢失。
            // 所以这里按「实例身份」判断，而不是用一次性布尔标记 —— 换了实例就重新 patch，
            // 这样「未登录 / 刚登出」状态下依然保持 isVip / freeAd 生效。
            if (model == patchedUserModel) return;
            setField(model, "isVip", true);
            setField(model, "freeAd", true);
            // expireTime/leftTime/reverseVIP/freeAdLeft/freeAdExpire 用 trySetField：
            // 这些字段在不同版本里可能不存在，硬 setField 抛异常会中断整段 patch
            trySetField(model, "expireTime", "2099-12-31");
            trySetField(model, "leftTime", "999999999");
            trySetField(model, "reverseVIP", true);
            trySetField(model, "freeAdLeft", 999999999L);
            trySetField(model, "freeAdExpire", 4102444800000L);
            patchedUserModel = model;   // 记住已 patch 的实例
            vipPatched = true;          // 兼容旧标记
            patchTries = 0;
            XposedBridge.log("[" + TAG + "] 已patch userModel: isVip=true freeAd=true leftTime=999999999");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] patchVip 异常: " + t);
            retry();
        }
    }

    private void retry() {
        if (patchTries++ < 5 && !vipPatched) {
            final Handler h = new Handler(Looper.getMainLooper());
            h.postDelayed(new Runnable() { public void run() { patchVip(); } }, 500);
        }
    }

    private void setField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private void trySetField(Object obj, String name, Object value) {
        try { setField(obj, name, value); } catch (Throwable ignored) {}
    }

    /**
     * v1.9.21：兜底清理已经发布出去的广告快捷方式。
     *
     * 与 hookShortcutCleaner 用同一套规则（{@link #isAdShortcut}），每次 onResume 跑一次。
     * 为什么还需要它：App 会在安装后/后台把这三个快捷方式**先**发布出去，
     * 而长按菜单是 Launcher 进程读 ShortcutManager 的数据渲染的 —— 只有真正删掉才算数。
     * 因为 App 会反复重新发布，这里是「删了它再发」的拉锯，所以日志按 id 去重，避免刷屏。
     */
    private void removeAdShortcuts(Activity act) {
        try {
            Object sm = act.getSystemService("shortcut");
            if (sm == null) return;
            java.util.List<?> dyn = (java.util.List<?>) sm.getClass().getMethod("getDynamicShortcuts").invoke(sm);
            if (dyn == null || dyn.isEmpty()) return;
            java.util.ArrayList<String> rm = new java.util.ArrayList<>();
            for (Object o : dyn) {
                if (isAdShortcut(o)) {
                    String id = str(o, "getId");
                    if (id.length() > 0) rm.add(id);
                }
            }
            if (rm.isEmpty()) return;
            sm.getClass().getMethod("removeDynamicShortcuts", java.util.List.class).invoke(sm, rm);
            if (filteredShortcutIds.addAll(rm)) {
                XposedBridge.log("[" + TAG + "] 已移除桌面快捷方式(长按图标广告): " + rm);
            }
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] removeAdShortcuts: " + t);
        }
    }

    /** 获取当前前台 Activity（简化版：取 decorView 所在 window 对应的 Activity） */
    private Activity getCurrentTopActivity() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            Field af = atClass.getDeclaredField("mActivities");
            af.setAccessible(true);
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) af.get(at);
            if (map == null) return null;
            for (Object ref : map.values()) {
                Field arf = ref.getClass().getDeclaredField("activity");
                arf.setAccessible(true);
                Activity a = (Activity) arf.get(ref);
                if (a != null && !a.isFinishing() && !a.isDestroyed()) return a;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Hook 已知广告 View 类的构造函数 + ViewGroup.addView，创建即 GONE */
    private void hookKnownAdViews() {
        // 仅用全限定类名匹配，避免短名(如 "h"/"b")误伤阅读器渲染器等无关 View
        final String[] AD_VIEW_FULL_NAMES = {
                "com.bytedance.polaris.impl.novelug.progress.b",          // 阅读页右上角300金币
                "com.dragon.read.admodule.adfm.unlocktime.entranceview.h", // 全天免费畅听中
                "com.dragon.read.music.player.block.common.adunlock.MusicAdUnlockTimeView", // 听歌页广告解锁条
                "com.dragon.read.admodule.adfm.unlocktime.AdUnlockTimeFloatingView",         // 浮动广告条
                // v1.9.6 新增：听书章节完毕广告页
                "com.dragon.read.ad.feedbanner.widget.BookMallAdFeedPlayOverPage",     // 章节完毕广告页
                "com.dragon.read.ad.feedbanner.widget.BookMallAdFeedPlayTransView",    // 章节完毕过渡视图
                "com.dragon.read.ad.feedbanner.widget.BookMallAdFeedPlayPage",         // 听书播放页广告
                "com.dragon.read.ad.feedbanner.widget.BookMallAdFeedCloseView",        // 关闭按钮（兜底）
                // v1.9.13 新增：阅读页「阅读流一站式广告」(OneStop) 自绘推广卡
                // （屏幕上那张「XX家具超市 + 反馈」购物卡就是它）。特征：整棵子树没有任何
                //  resource-id，只有 content-desc 无障碍文案 —— 典型的 Lynx/OneStop 自绘。
                // 宿主：com.dragon.read.reader.ad.onestop.view.ReadFlowOneStopAdLine
                // （内部持有 cj1/c.e() 返回的 ReadFlowOneStopAtAdView）。
                // ⚠️ 它既不过 AdLynxHelper.checkIfRitAvailable，也不过 AdConfigManager.checkAdAvailable，
                //    所以之前两个「源头总闸」都拦不到，只能按 View 类名视觉隐藏。
                "com.bytedance.tomato.onestop.readerad.ui.ReadFlowOneStopAtAdView",
                "com.bytedance.tomato.onestop.readerad.ui.ReadFlowOneStopNonRoundEntranceLayout",
        };
        // 用全限定类名 HashSet 加速 addView 拦截
        final Set<String> adFullNames = new HashSet<>();
        for (String s : AD_VIEW_FULL_NAMES) adFullNames.add(s);
        for (final String clsName : AD_VIEW_FULL_NAMES) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(clsName, appCl);
                if (cls == null) continue;
                XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View v = (View) param.thisObject;
                            v.setVisibility(View.GONE);
                            // v1.9.21：这几个 View 会被高频反复构造（一次启动上千次），
                            // 日志只打一次，否则会把 LSPosed 日志区整个淹掉。
                            if (loggedCtorClasses.add(clsName)) {
                                XposedBridge.log("[" + TAG + "] 已拦截广告View(构造即GONE): " + clsName);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[" + TAG + "] 已注册广告View构造hook: " + clsName);
            } catch (Throwable ignored) {}
        }
        // Hook ViewGroup.addView：用全限定类名匹配（不用短名，避免误伤）
        try {
            XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View child = (View) param.args[0];
                        if (child == null) return;
                        String fullClsName = child.getClass().getName();
                        if (adFullNames.contains(fullClsName)) {
                            child.setVisibility(View.GONE);
                            XposedBridge.log("[" + TAG + "] 已拦截广告View(addView时GONE): " + fullClsName);
                            return;
                        }
                        // v1.9.11 新增：按已知广告资源 ID 预拦截，消除首帧闪现。
                        // 资源 ID 在 XML inflate 时已通过 setId() 设置，addView 前已可用。
                        int cid = child.getId();
                        if (cid == View.NO_ID) return;
                        Set<Integer> cached = cachedHideIds;
                        if (PRE_HIDE_RES_IDS.contains(cid)
                                || (cached != null && cached.contains(cid))) {
                            // v1.9.21：cachedHideIds（「我的」页那几块）也要在这里拦。
                            // 只靠 View.setVisibility 兜底是不够的：App 重新 inflate 时默认就是
                            // VISIBLE，压根不会调 setVisibility(VISIBLE)，于是「藏了又回来」。
                            child.setVisibility(View.GONE);
                            XposedBridge.log("[" + TAG + "] 已拦截广告View(addView时GONE by resId): 0x" + Integer.toHexString(cid));
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[" + TAG + "] ViewGroup.addView 拦截器已启用");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] ViewGroup.addView 拦截失败: " + t);
        }
        // Hook View.setVisibility：当广告 View 被设为 VISIBLE 时，直接移除其父容器（彻底杜绝反复重显与布局抖动）
        try {
            final Set<String> adFullNamesFinal = adFullNames;
            // 记录已移除的父容器，避免反复 removeView 造成 layout 抖动（这正是卡顿来源）
            final Set<Integer> removedParents = new HashSet<>();
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int vis = (int) param.args[0];
                        if (vis != View.VISIBLE) return;
                        View v = (View) param.thisObject;
                        // v1.9.9：按资源 ID 强制隐藏（「我的」页 VIP 宣传卡 bpz / 我的资产卡 e33 等）。
                        // App 会在布局刷新时把这两个卡片重新设为 VISIBLE，仅靠一次性 setVisibility(GONE)
                        // 会反复闪现，这里在「设为可见的瞬间」直接改回 GONE。
                        try {
                            Set<Integer> ids = cachedHideIds;
                            if (ids != null && !ids.isEmpty()) {
                                int vid = v.getId();
                                if (vid != View.NO_ID && ids.contains(vid)) {
                                    param.args[0] = View.GONE;
                                    return;
                                }
                            }
                        } catch (Throwable ignored) {}
                        // v1.9.9：听歌页「看小视频免广告」贴片视频广告容器。
                        // App 拉到广告后会把 MusicPatchAdContainer 设为 VISIBLE（Lynx 自绘整张广告卡），
                        // 这里在「设为可见的瞬间」直接改回 GONE。
                        // ⚠️ 必须 return 且绝不走下面的 removeView 分支：该容器由 ViewStub
                        //    一次性 inflate，摘掉它会让 App 下次 inflate() 抛 IllegalStateException。
                        if (PATCH_AD_CONTAINER_CLS.equals(v.getClass().getName())) {
                            param.args[0] = View.GONE;
                            return;
                        }
                        String cls = v.getClass().getName();
                        if (adFullNamesFinal.contains(cls)) {
                            // 1) 先尝试整体移除：找到最近的可安全移除父容器（跳过全屏/页面级），removeView 一次到位
                            View parent = v.getParent() instanceof View ? (View) v.getParent() : null;
                            View removeTarget = null;
                            if (parent != null && parent.getParent() instanceof ViewGroup) {
                                int sw = 0, sh = 0;
                                try { sw = parent.getRootView().getWidth(); sh = parent.getRootView().getHeight(); } catch (Throwable ignored) {}
                                View cur = parent;
                                View prev = v;
                                for (int i = 0; i < 4; i++) {
                                    if (!(cur.getParent() instanceof ViewGroup)) break;
                                    ViewGroup gp = (ViewGroup) cur.getParent();
                                    int pw = cur.getWidth(), ph = cur.getHeight();
                                    // 页面级/全屏容器保护：不再向上
                                    if (sw > 0 && sh > 0 && pw >= sw * 0.9f && ph >= sh * 0.4f) break;
                                    // 顶部栏/底部导航保护
                                    if (sw > 0 && pw >= sw * 0.8f && ph < sh * 0.1f) break;
                                    prev = cur;
                                    cur = gp;
                                }
                                removeTarget = prev;
                            }
                            if (removeTarget != null && removeTarget.getParent() instanceof ViewGroup) {
                                final ViewGroup vg = (ViewGroup) removeTarget.getParent();
                                final View target = removeTarget;
                                final int key = System.identityHashCode(target);
                                if (removedParents.add(key)) {
                                    target.post(new Runnable() {
                                        @Override public void run() {
                                            try {
                                                if (target.getParent() == vg) {
                                                    vg.removeView(target);
                                                    XposedBridge.log("[" + TAG + "] 已移除广告父容器: " + cls + " → removeView " + target.getClass().getSimpleName());
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    });
                                    param.args[0] = View.GONE;
                                } else {
                                    param.args[0] = View.GONE;
                                }
                            } else {
                                // 2) 兜底：无父容器可移除时直接 GONE
                                param.args[0] = View.GONE;
                                XposedBridge.log("[" + TAG + "] setVisibility拦截(GONE): " + cls);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[" + TAG + "] View.setVisibility 拦截器已启用（移除父容器模式）");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] View.setVisibility 拦截失败: " + t);
        }
    }

    /** 延迟类可能在首个 Activity 后才加载；多次重试（3s/8s/15s/30s），避免首次 findClassIfExists 过早失败。 */
    private void scheduleAdSignalRetry() {
        final Handler h = new Handler(Looper.getMainLooper());
        final long[] delays = {5000, 15000, 30000};
        for (long delay : delays) {
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        hookAdSignals();
                        hookKnownAdViews();
                        hookMusicPatchAd();   // v1.9.9：SDK 类可能延迟加载，重试注册
                        hookLynxAdBlocker();  // v1.9.12：AdLynxHelper 可能延迟加载，重试注册
                        hookReaderAdLineFactory(); // v1.9.18：类可能延迟加载，重试注册
                        hookVipPromotionPopup();   // v1.9.19：类可能延迟加载，重试注册
                    } catch (Throwable t) {
                        XposedBridge.log("[" + TAG + "] 延迟广告 hook 失败: " + t);
                    }
                }
            }, delay);
        }
    }

    /** 源码级广告拦截：解锁时长倒计时弹窗/倒计时条 + 激励广告SDK入口 */
    private void hookAdSignals() {
        // 1) 听歌页倒计时弹窗管理器：拦截弹窗展示
        try {
            final Class<?> dlgMgr = XposedHelpers.findClassIfExists(
                    "com.dragon.read.admodule.adfm.unlocktime.AdUnlockTimeDialogManager", appCl);
            if (dlgMgr != null) {
                final String[] dlgMethods = {"realShowDialog", "showDialog", "showUnlockDialog", "checkAndShowDialog", "show"};
                for (final String m : dlgMethods) {
                    try {
                        XposedBridge.hookAllMethods(dlgMgr, m, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(null);
                                XposedBridge.log("[" + TAG + "] 已阻止解锁时长弹窗: " + m);
                            }
                        });
                    } catch (Throwable ignored) {}
                }
                adHookReport.append("AdUnlockTimeDialogManager✓ ");
            } else {
                adHookReport.append("AdUnlockTimeDialogManager✗ ");
            }
        } catch (Throwable ignored) {}
        // 2) 倒计时条/悬浮条 view：创建即 GONE
        final String[] adViews = {
                "com.dragon.read.music.player.block.common.adunlock.MusicAdUnlockTimeView",
                "com.dragon.read.admodule.adfm.unlocktime.AdUnlockTimeFloatingView",
                "com.dragon.read.admodule.adfm.unlocktime.entranceview.h"
        };
        for (final String vn : adViews) {
            try {
                Class<?> vc = XposedHelpers.findClassIfExists(vn, appCl);
                if (vc == null) {
                    adHookReport.append("View✗").append(vn.substring(vn.lastIndexOf('.') + 1)).append(" ");
                    continue;
                }
                XposedBridge.hookAllConstructors(vc, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try { ((android.view.View) param.thisObject).setVisibility(android.view.View.GONE); } catch (Throwable ignored) {}
                    }
                });
                adHookReport.append("View✓").append(vn.substring(vn.lastIndexOf('.') + 1)).append(" ");
            } catch (Throwable ignored) {}
        }
        // 3) 激励广告SDK入口（类名可能被混淆，找不到就跳过）
        final String[] classes = {
                "com.bytedance.ug.sdk.luckycat.impl.manager.LuckyCatManager",
                "com.bytedance.ug.sdk.luckycat.impl.manager.LuckyCatAdManager",
                "com.dragon.read.admodule.adfm.AdFmManager",
                "com.dragon.read.reader.speech.ad.AdManager"
        };
        final String[] methods = {"showAd", "showRewardAd", "preloadAd", "requestAd", "loadAd"};
        for (String className : classes) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(className, appCl);
                if (cls == null) continue;
                for (String method : methods) {
                    try {
                        final String hookedMethod = method;
                        XposedBridge.hookAllMethods(cls, hookedMethod, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(null);
                                XposedBridge.log("[" + TAG + "] 已阻止广告调用: " + hookedMethod);
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        // 4) v1.9.6 新增：听书激励弹窗 FreeAdConversionDialog 的 show 方法短路
        try {
            final Class<?> fcdCls = XposedHelpers.findClassIfExists("com.dragon.read.ad.freead.FreeAdConversionDialog", appCl);
            if (fcdCls != null) {
                XposedHelpers.findAndHookMethod(fcdCls, "show", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(null);
                        XposedBridge.log("[" + TAG + "] 已阻止 FreeAdConversionDialog.show()");
                    }
                });
                adHookReport.append("FreeAdConversionDialog✓ ");
            }
        } catch (Throwable ignored) {}
    }

    /**
     * v1.9.11 新增：广告位总闸 —— 从**源头**掐断被动展示型广告。
     *
     * <p>为什么加这个：此前的去广告全靠「View 树层面隐藏」——等广告 View 被创建出来、
     * 挂到界面上之后才把它 GONE 掉。这套做法有三个先天缺陷：
     * <ol>
     *   <li><b>首帧闪现</b>：广告 View 已经渲染过一帧才被隐藏，用户能看见闪一下
     *       （首页搜索栏上方那个「全天畅听 / 领金币」块就是这个症状）；</li>
     *   <li><b>要逐个跟</b>：每换一个广告位就得重新定位 View / 资源 id，番茄一改版就失效；</li>
     *   <li><b>拦不住自绘广告</b>：Lynx 模板广告（贴片广告）根本不在 View 树里，只能靠视觉兜底。</li>
     * </ol>
     *
     * <p>同类项目 FanqieHook（dev.operit.fanqiehook）用的是**源码级广告位总闸**：
     * hook 广告配置管理器的 {@code checkAdAvailable(position, source)}，按「广告位字符串」
     * 直接返回 false。这是所有广告位的统一入口，一次 hook 覆盖全部，且**在广告请求/渲染之前**
     * 就返回了，天然没有首帧闪现问题。
     *
     * <p>本模块在畅听 6.7.1.16 上定位到的对应方法（反汇编 classes14.dex 确认）：
     * <pre>
     *   com.dragon.read.base.ad.AdConfigManager.checkAdAvailable(String, String) : boolean
     * </pre>
     * 注意：<b>不能照搬对方的类名</b> —— 对方 hook 的
     * {@code com.dragon.read.component.biz.impl.NsAdImpl} 在畅听里**不存在**
     * （畅听与小说的 dragon 基线版本不同）。
     *
     * <p>策略：只拦 {@link #BLOCKED_AD_POSITIONS} 里的「被动展示位」，
     * 用户主动触发的激励视频 / 金币 / 看广告免广告流程一律放行（返回原值）。
     * 同时把所有出现过的广告位打一条日志，方便换版本后重新收集。
     */
    private void hookAdConfigGate() {
        if (adConfigGateHooked) return;
        adConfigGateHooked = true;

        Class<?> cls = null;
        try {
            cls = XposedHelpers.findClassIfExists(AD_CONFIG_MANAGER_CLS, appCl);
        } catch (Throwable ignored) {}
        if (cls == null) {
            XposedBridge.log("[" + TAG + "] 广告位总闸: " + AD_CONFIG_MANAGER_CLS
                    + " 未找到，跳过（不影响其他拦截）");
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(cls, "checkAdAvailable", String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String position = param.args[0] == null ? "" : String.valueOf(param.args[0]);
                                String source = (param.args.length > 1 && param.args[1] != null)
                                        ? String.valueOf(param.args[1]) : "";

                                // 收集：所有出现过的广告位各打一次，便于换版本后重新确定黑名单
                                if (seenAdPositions.add(position)) {
                                    XposedBridge.log("[" + TAG + "] 广告位出现: " + position
                                            + " | source=" + source);
                                }

                                if (BLOCKED_AD_POSITIONS.contains(position)) {
                                    param.setResult(Boolean.FALSE);
                                    if (blockedAdPositions.add(position)) {
                                        XposedBridge.log("[" + TAG + "] 🚫 已从源头拦截广告位: "
                                                + position + " | source=" + source);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            XposedBridge.log("[" + TAG + "] ✅ 广告位总闸已挂载: "
                    + "AdConfigManager.checkAdAvailable(String,String)");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] 广告位总闸挂载失败: " + t);
        }
    }

    /**
     * v1.9.12 新增：从源头拦截阅读页 Lynx 广告（见 {@link #BLOCKED_LYNX_AD_SCENES}）。
     *
     * <p>hook 两处：
     * <ol>
     *   <li>{@code AdLynxHelper.checkIfRitAvailable(scene, rit)} —— 命中黑名单场景键时
     *       直接返回 {@code "ad_available_check_rit_disenable"}，让 App 走它自己的
     *       「rit 校验不通过」失败分支（requestAd 里会 onRequestFailed），广告请求前即中止；</li>
     *   <li>{@code AdLynxHelper.requestAd(scene, ...)} —— 只打日志，用于换版本后重新收集
     *       Lynx 场景键（每个新场景只打一次）。</li>
     * </ol>
     */
    private void hookLynxAdBlocker() {
        if (lynxAdHookDone) return;
        Class<?> helper = null;
        try {
            helper = XposedHelpers.findClassIfExists("com.dragon.read.lynx.AdLynxHelper", appCl);
        } catch (Throwable ignored) {}
        if (helper == null) {
            // 不置位：AdLynxHelper 可能延迟加载，留给 scheduleAdSignalRetry 重试
            XposedBridge.log("[" + TAG + "] Lynx 广告拦截: AdLynxHelper 未找到，稍后重试");
            return;
        }
        lynxAdHookDone = true;
        // ① 拦截点：checkIfRitAvailable(scene, rit) -> "ad_available_check_rit_disenable"
        try {
            XposedBridge.hookAllMethods(helper, "checkIfRitAvailable", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String scene = (param.args != null && param.args.length > 0 && param.args[0] != null)
                                ? String.valueOf(param.args[0]) : "";
                        if (BLOCKED_LYNX_AD_SCENES.contains(scene)) {
                            param.setResult("ad_available_check_rit_disenable");
                            XposedBridge.log("[" + TAG + "] 🚫 已从源头拦截 Lynx 广告场景: " + scene);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[" + TAG + "] ✅ Lynx 广告场景拦截器已挂载: "
                    + "AdLynxHelper.checkIfRitAvailable");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Lynx 场景拦截挂载失败: " + t);
        }
        // ② 侦察：收集所有出现过的 Lynx 广告场景键（每场景一次），换版本后据此更新黑名单
        try {
            XposedBridge.hookAllMethods(helper, "requestAd", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String scene = (param.args != null && param.args.length > 0 && param.args[0] != null)
                                ? String.valueOf(param.args[0]) : "";
                        if (scene.length() > 0 && seenLynxScenes.add(scene)) {
                            XposedBridge.log("[" + TAG + "] Lynx 广告场景出现: " + scene);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * v1.9.14 新增：从源头关闭阅读页 OneStop 一站式广告（屏幕上的「XX家具超市 + 反馈」购物卡）。
     *
     * <p>v1.9.13 已经把广告 View（{@code ReadFlowOneStopAtAdView}）构造即 GONE，广告确实看不到了，
     * 但**广告位 Reserved 的空间还在，正文不会自动上提**，留下一大片空白。根因是：广告 View
     * 只是被隐藏，而阅读器已经把这条「广告行」的占位高度算进了排版。
     *
     * <p>正确做法是从源头让它根本不展示 —— 反汇编 classes13.dex 确认两个策略类各有一个
     * {@code a(Lca/a;)Z} 方法，返回值就是“能否展示/能否请求”：
     * <pre>
     *   ReadFlowOneStopAdDisplayStrategy.a(Lca/a;)Z    // true=展示策略通过，false=不展示
     *   ReadFlowOneStopAdRequestStrategy.a(Lca/a;)Z    // true=可发起请求
     *   （vm 里：v1 初始=0，末尾仅当校验全通过时 const/4 v1,#1 再 return）
     * </pre>
     * 把这两个方法直接短路成 false，广告既不会被请求、也不会被展示，
     * 阅读器自然不会给它留占位，正文连贯、没有空白。
     *
     * <p>用反射筛选签名（只改 {@code a(...)} 且返回 boolean 的重载），不硬编码参数类型，
     * 换版本后即使参数类型变了也能命中。
     */
    private void hookOneStopReaderAd() {
        hookOneStopStrategy("com.bytedance.tomato.onestop.readerad.strategy.ReadFlowOneStopAdDisplayStrategy", "展示");
        hookOneStopStrategy("com.bytedance.tomato.onestop.readerad.strategy.ReadFlowOneStopAdRequestStrategy", "请求");
    }

    /**
     * v1.9.18 新增：从源头屏蔽阅读页「章节末」广告入口行
     * （屏幕上那个「看小视频免30分钟广告」小卡片，以及同批次的买VIP入口/加桌面快捷方式行）。
     *
     * <p>为什么之前的隐藏拦不住：这些入口不是普通弹窗，而是阅读器 drawlevel 体系里的一个
     * “Line”。每次翻页/重排，阅读器都会重新 constructing 并把它插进页面，
     * 所以“文末 GONE 一下”只能隐藏一帧，下一帧又重新出现 —— 必须从“生成入口”这一步堵住。
     *
     * <p>反汇编 6.7.2.32 定位到（classes14.dex）：
     * <pre>
     *   Lf32/q;->a(...)Lcom/dragon/read/ad/AddShortcutLine;   // 加桌面快捷方式行
     *   Lf32/q;->b(...)Lcom/dragon/read/ad/ButtonLine;         // 章节末广告按钮行（「看小视频免30分钟广告」）
     *   Lf32/q;->c(...)Lcom/dragon/read/ad/BuyVipEntranceLine; // 章节末买VIP入口行
     * </pre>
     * 用 tools/findcallers.py 确认：三个方法**只**被阅读页广告行 provider
     * {@code z33.d.a(be3/c)} 调用，且调用点全部是 {@code if-nez v0, -> 0x01eb} 判空跳转 ——
     * 因此让它们返回 null，App 会自己跳过「添加该行」，既干净又不会报错。
     *
     * <p>只屏蔽这三个广告入口行工厂，不动其他阅读器 Line，正文排版不受影响、也不会留空白。
     */
    private void hookReaderAdLineFactory() {
        if (readerAdLineHooked) return;
        Class<?> q = null;
        try {
            q = XposedHelpers.findClassIfExists("f32.q", appCl);
        } catch (Throwable ignored) {}
        if (q == null) {
            XposedBridge.log("[" + TAG + "] 阅读页广告行工厂 f32.q 未找到，稍后重试");
            return;
        }
        readerAdLineHooked = true;
        final String[] methods = {"a", "b", "c"};
        for (final String m : methods) {
            try {
                XposedBridge.hookAllMethods(q, m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            java.lang.reflect.Member mem = param.method;
                            if (!(mem instanceof java.lang.reflect.Method)) return;
                            Class<?> rt = ((java.lang.reflect.Method) mem).getReturnType();
                            if (rt == null || !rt.getName().startsWith("com.dragon.read.ad.")) return;
                            param.setResult(null);
                            if (readerAdLineBlocked.add(m + ":" + rt.getSimpleName())) {
                                XposedBridge.log("[" + TAG + "] 🚫 已从源头屏蔽阅读页广告入口行: f32.q."
                                        + m + "() -> " + rt.getSimpleName());
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {}
        }
        XposedBridge.log("[" + TAG + "] ✅ 阅读页广告入口行工厂拦截已挂载: f32.q.a/b/c");
    }

    /**
     * v1.9.19 新增：从源头屏蔽首页「VIP 促销全屏弹层」
     * （屏幕上那整屏的「仅限当前设备开通7天会员，限时有效」浮层；整层无 resource-id、
     * 只有 content-desc，是 Lynx 自绘的促销页）。
     *
     * <p>反汇编 6.7.2.32 classes14.dex 定位到它的原生入口（H5/Lynx 通过 JSBridge 调过来）：
     * <pre>
     *   Lcom/dragon/read/hybrid/bridge/modules/vip/a;
     *     showVipPromotionPopup(IBridgeContext, String, Z, I, I)V
     * </pre>
     * 方法体内 {@code new i23.d0(ctx)} 并最后调 {@code com.dragon.read.widget.dialog.i.show()}
     * 把整屏促销弹层展示出来（i23.d0 继承 com.dragon.read.widget.dialog.i）。
     *
     * <p>拦截策略（两层）：
     * <ol>
     *   <li>把 {@code showVipPromotionPopup} 整个短路（该方法返回 void、正常展示分支也
     *       不回调 JS，短路不会造成页面卡死）；</li>
     *   <li>兜底：即使从别的路径直接 {@code i23.d0.show()}，也把该类的 show 压掉。</li>
     * </ol>
     */
    private void hookVipPromotionPopup() {
        if (vipPromoHooked) return;
        boolean any = false;
        // ① 入口：bridge 方法短路
        try {
            Class<?> a = XposedHelpers.findClassIfExists(
                    "com.dragon.read.hybrid.bridge.modules.vip.a", appCl);
            if (a != null) {
                XposedBridge.hookAllMethods(a, "showVipPromotionPopup", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(null);
                        XposedBridge.log("[" + TAG + "] 🚫 已从源头拦截首页VIP促销全屏弹层: showVipPromotionPopup");
                    }
                });
                any = true;
            }
        } catch (Throwable ignored) {}
        // ② 兜底：促销弹层类 i23.d0 直接 show 时压掉
        try {
            Class<?> dlgBase = XposedHelpers.findClassIfExists(
                    "com.dragon.read.widget.dialog.i", appCl);
            if (dlgBase != null) {
                XposedBridge.hookAllMethods(dlgBase, "show", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject != null
                                    && "i23.d0".equals(param.thisObject.getClass().getName())) {
                                param.setResult(null);
                                XposedBridge.log("[" + TAG + "] 🚫 已拦截VIP促销弹层 show(): i23.d0");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                any = true;
            }
        } catch (Throwable ignored) {}
        if (any) {
            vipPromoHooked = true;
            XposedBridge.log("[" + TAG + "] ✅ 首页VIP促销弹层拦截已挂载");
        }
    }

    private void hookOneStopStrategy(final String clsName, final String label) {
        Class<?> cls = null;
        try {
            cls = XposedHelpers.findClassIfExists(clsName, appCl);
        } catch (Throwable ignored) {}
        if (cls == null) {
            XposedBridge.log("[" + TAG + "] OneStop 读取页广告策略未找到: " + clsName);
            return;
        }
        // 诊断：打出方法表，便于换版本后核对策略方法签名
        try {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                sb.append(m.getName()).append(java.util.Arrays.toString(m.getParameterTypes()))
                        .append("->").append(m.getReturnType().getSimpleName()).append("; ");
            }
            XposedBridge.log("[" + TAG + "] OneStop策略方法表(" + label + "): " + sb);
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] OneStop策略方法表读取失败(" + label + "): " + t);
        }
        // 拦截：hookAllMethods 会对所有名为 a 的方法挂 hook（含静态/重载），
        // 在调用时按「返回 boolean 且只有一个参数」筛选，不依赖反射签名硬匹配。
        try {
            XposedBridge.hookAllMethods(cls, "a", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        java.lang.reflect.Member member = param.method;
                        if (!(member instanceof java.lang.reflect.Method)) return;
                        java.lang.reflect.Method mm = (java.lang.reflect.Method) member;
                        if (mm.getReturnType() != boolean.class) return;
                        if (param.args == null || param.args.length != 1) return;
                        param.setResult(Boolean.FALSE);
                        // 该策略每次翻页都会被调用，日志只打一次，避免刷屏
                        if (oneStopBlockedLogged.add(label + ":" + mm.getName())) {
                            XposedBridge.log("[" + TAG + "] 🚫 已拦截阅读页 OneStop 广告策略(" + label + "): "
                                    + mm.getName() + java.util.Arrays.toString(mm.getParameterTypes()));
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[" + TAG + "] ✅ OneStop 阅读页广告策略已挂载(" + label + "): " + clsName);
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] OneStop 策略 hook 异常(" + label + "): " + t);
        }
    }

    /**
     * v1.9.9 新增：拦截听歌页「看小视频免广告」贴片视频广告（2026-09-11 定位）。
     *
     * 症状：听歌页切换/重进时随机出现视频卡片 —— 卡片内是 Lynx 渲染的广告素材
     *       （App 图标 +「千问」+「商家推荐」+「精彩应用」+「5亿+用户已下载」+「赶紧下载」），
     *       右上角带「看小视频免广告 | X秒」倒计时。
     *
     * 根因：这不是普通 View、不是 Dialog/DialogFragment，而是**字节 Lynx 广告引擎**
     *       （com.ss.android.mannor / OneStop 体系）用**服务端下发的 Lynx 模板**渲染的「贴片广告」。
     *       场景键 mannor_audio_patch。因此 uiautomator dump 抓不到（Lynx 自绘、不在无障碍树），
     *       TextView.setText 文本过滤也天然无效（"商家推荐/赶紧下载"等文案不在 APK 内）。
     *
     * 调用链（6.6.8.32 实测）：
     *   听歌页布局 bfg.xml / bfx.xml 内含 ViewStub(id=eoj, layout=@layout/b3n)，
     *   b3n.xml 只有唯一一个 fill_parent 的 MusicPatchAdContainer。
     *     qp2/w.C()  PatchAdBlock#doRequestPatchAd        → 请求贴片广告
     *     qp2/w.D()  PatchAdBlock#tryRequestAd
     *     qp2/o0.K() PatchAdHolderBlock#tryProduceAd      → oh1/a.a() 生成广告视图
     *     qp2/o0.M() PatchAdHolderBlock#tryInsertAd       → ViewStub.inflate()
     *                                                     → MusicPatchAdContainer.setAdPatchView(adView)
     *   MusicPatchAdContainer（com.dragon.read.music.player.widget）内含 LynxView，承载整张广告卡。
     *
     * 拦截策略（纯视觉拦截，**不打断 App 状态机**）：
     *
     *   为什么不 hook oh1/a.c()（isAdViewOk）判 false？
     *     反汇编 qp2/o0.K() 证实：c() 在 0x00bd 只用于打日志，在 0x00d1/0x00d5 才是
     *     「if (c() == false) return;」的判定点。判 false 确实能让本次不展示，
     *     但 App 也就不会执行 0x00f9 的 Store.dispatch(tq2/v0(musicId, true))，
     *     「贴片广告正在展示」状态永远不置位 → qp2/o0.M()(tryInsertAd) 的
     *     「正在展示，不重新展示」守卫失效 → 每次切歌都会重新发起广告请求。
     *     因此放弃该方案，改为「让 App 正常走完，但容器永远不可见」。
     *
     *   实际拦截点：
     *     ① MusicPatchAdContainer 构造 → GONE（听歌页布局里常驻，默认即 GONE）
     *     ② setAdPatchView(...) 之后 → 再压 GONE（广告视图注入完成瞬间）
     *     ③ onAttachedToWindow 之后 → 再压 GONE（App 可能在 attach 后才设 VISIBLE）
     *     ④ View.setVisibility 里对该类名做兜底：只要被设为 VISIBLE 就改回 GONE
     *        （见 hookKnownAdViews 的 setVisibility 拦截器）
     *
     * ⚠️ 只 GONE、**不 removeView**：该容器由 ViewStub 一次性 inflate，
     *    把容器从父布局摘掉会让 ViewStub 变成游离状态，App 下次再调 inflate()
     *    会抛 IllegalStateException("ViewStub must have a non-null ViewGroup viewParent")。
     */
    private void hookMusicPatchAd() {
        if (patchAdContainerHooked) return;

        final String CONTAINER = PATCH_AD_CONTAINER_CLS;
        Class<?> containerCls = null;
        try {
            containerCls = XposedHelpers.findClassIfExists(CONTAINER, appCl);
        } catch (Throwable ignored) {}
        if (containerCls == null) {
            adHookReport.append("MusicPatchAdContainer✗ ");
            return;
        }
        // 先置位：即使个别子 hook 失败也不要反复重试刷日志
        patchAdContainerHooked = true;
        adHookReport.append("MusicPatchAdContainer✓ ");

        // ① 构造即 GONE（该容器在听歌页布局里常驻，默认就是 GONE）
        try {
            XposedBridge.hookAllConstructors(containerCls, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ((View) param.thisObject).setVisibility(View.GONE);
                        XposedBridge.log("[" + TAG + "] 已隐藏听歌页贴片广告容器(构造即GONE)");
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] hook 容器构造失败: " + t);
        }

        // ② setAdPatchView(...)：广告视图被注入后立刻压回 GONE
        try {
            XposedBridge.hookAllMethods(containerCls, "setAdPatchView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ((View) param.thisObject).setVisibility(View.GONE);
                        XposedBridge.log("[" + TAG + "] 已拦截贴片广告视图注入(setAdPatchView→GONE)");
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] hook setAdPatchView 失败: " + t);
        }

        // ③ 挂到窗口时再压一次 GONE（App 可能在 attach 后才设 VISIBLE）。
        //    注意：onAttachedToWindow 继承自 android.view.View，
        //    Vector 的 findAndHookMethod 对继承方法会报
        //    "NoSuchMethodError: ...#onAttachedToWindow()#exact"，
        //    所以用 hookAllMethods（沿继承链查找）并单独 try 兜住。
        try {
            XposedBridge.hookAllMethods(containerCls, "onAttachedToWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ((View) param.thisObject).setVisibility(View.GONE);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] hook onAttachedToWindow 失败(可忽略): " + t);
        }

        XposedBridge.log("[" + TAG + "] 已注册听歌页贴片广告容器拦截: " + CONTAINER);
    }

    /** 自动点击跳过按钮：广告倒计时弹窗/页/浮层只要渲染了「跳过」就自动点掉（限频+去重） */
    private void tryAutoSkip(View v, String t) {
        if (v == null || t == null) return;
        boolean hit = t.equals("跳过") || t.equals("跳过广告")
                || (t.contains("跳过") && t.length() <= 10)
                || t.matches("\\d+\\s*s?\\s*\\|?\\s*跳过.*");
        if (!hit) return;
        if (!(v.isClickable() || v.hasOnClickListeners())) return;
        long now = System.currentTimeMillis();
        if (now - lastSkipClick < 2000) return;
        String key = t + "@" + v.getWidth() + "x" + v.getHeight();
        if (!clickedSkip.add(key)) return;
        lastSkipClick = now;
        try {
            v.performClick();
            XposedBridge.log("[" + TAG + "] 已自动点击跳过['" + t + "']");
        } catch (Throwable ignored) {}
    }

    /**
     * v1.9.6 适配：听书激励弹窗 FreeAdConversionDialog 拦截。
     * 旧版 hook 的 ReaderInspireDialogFragment / InspireDialogFragment 在 6.6.8.32 已不存在。
     * 新版统一为 BottomSheet 形态的 FreeAdConversionDialog（extends AbsQueueBottomSheetDialogFragment）。
     */
    private void hookDialogFragmentBlocker() {
        // 通用：androidx DialogFragment.show 两个重载
        final String[] SHOW_SIGS = {
                "androidx.fragment.app.DialogFragment",
        };
        try {
            Class<?> dfCls = XposedHelpers.findClassIfExists("androidx.fragment.app.DialogFragment", appCl);
            if (dfCls != null) {
                XC_MethodHook showHook = new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object frag = param.thisObject;
                            String n = frag.getClass().getName();
                            if (isAdFragmentClass(n)) {
                                param.setResult(null);
                                XposedBridge.log("[" + TAG + "] 已拦截广告DialogFragment(show): " + n);
                            }
                        } catch (Throwable ignored) {}
                    }
                };
                try { XposedHelpers.findAndHookMethod(dfCls, "show", "androidx.fragment.app.FragmentManager", String.class, showHook); } catch (Throwable ignored) {}
                try { XposedHelpers.findAndHookMethod(dfCls, "show", "androidx.fragment.app.FragmentTransaction", String.class, showHook); } catch (Throwable ignored) {}
                // 兜底：onStart（此时尚未真正展示窗口，可 dismiss）
                XC_MethodHook onStartHook = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object frag = param.thisObject;
                            String n = frag.getClass().getName();
                            if (isAdFragmentClass(n)) {
                                try { param.thisObject.getClass().getMethod("dismiss").invoke(param.thisObject); } catch (Throwable ignored2) {}
                                XposedBridge.log("[" + TAG + "] 已拦截广告DialogFragment(onStart): " + n);
                            }
                        } catch (Throwable ignored) {}
                    }
                };
                try { XposedHelpers.findAndHookMethod(dfCls, "onStart", onStartHook); } catch (Throwable ignored) {}
                XposedBridge.log("[" + TAG + "] DialogFragment 拦截器已启用");
            }
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] DialogFragment 拦截器失败: " + t);
        }

        // 精确 hook 已知广告 Fragment 的 onCreateView：返回 null 阻止渲染
        final String[] AD_FRAGMENTS = {
                // v1.9.6 新增：听书激励弹窗 (BottomSheet 形态，继承 AbsQueueBottomSheetDialogFragment)
                "com.dragon.read.ad.freead.FreeAdConversionDialog",
                // 旧版已废弃（6.6.8.32 中找不到这些类，作为兜底保留）
                "com.dragon.read.reader.ad.dialog.newstyle.ReaderInspireDialogFragment",
                "com.dragon.read.reader.speech.ad.listen.dialog.newstyle.InspireDialogFragment",
                "com.dragon.read.reader.ad.dialog.newstyle.InterruptAdReaderDialogNew",
                "com.dragon.read.reader.ad.dialog.newstyle.ReaderRuleDescriptionFragment",
        };
        for (final String clsName : AD_FRAGMENTS) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(clsName, appCl);
                if (cls == null) continue;
                XposedHelpers.findAndHookMethod(cls, "onCreateView",
                        "android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(null);
                                XposedBridge.log("[" + TAG + "] 已拦截广告Fragment(无View): " + clsName);
                            }
                        });
                XposedBridge.log("[" + TAG + "] 已注册广告Fragment hook: " + clsName);
            } catch (Throwable ignored) {}
        }

        // v1.9.6 新增：hook FreeAdConversionDialog 的 show() / forceClose() 双重保险
        try {
            Class<?> fcd = XposedHelpers.findClassIfExists("com.dragon.read.ad.freead.FreeAdConversionDialog", appCl);
            if (fcd != null) {
                XposedHelpers.findAndHookMethod(fcd, "show", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            param.setResult(null);
                            XposedBridge.log("[" + TAG + "] 已拦截 FreeAdConversionDialog.show() → 直接拦截弹窗");
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[" + TAG + "] FreeAdConversionDialog.show() 拦截已启用");
            }
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] FreeAdConversionDialog.show hook 失败: " + t);
        }
    }

    /**
     * v1.9.7 修复：听书章节完毕"语音领金币"广告漏网根因。
     * FreeAdConversionDialog 继承 AbsQueueBottomSheetDialogFragment，其 show() 被重写，
     * 不调用 super.show()，而是委托给字节 silk subwindow 管理器（i70/a -> manager.c.e(Li70/c)），
     * 因此标准 DialogFragment.show hook 完全不触发。这里直接 hook 基类自身的 show() 两个重载。
     */
    private void hookAbsQueueBottomSheetDialog() {
        try {
            final Class<?> aqCls = XposedHelpers.findClassIfExists(
                    "com.dragon.read.widget.dialog.AbsQueueBottomSheetDialogFragment", appCl);
            if (aqCls == null) {
                XposedBridge.log("[" + TAG + "] AbsQueueBottomSheetDialogFragment 未找到，跳过");
                return;
            }
            XC_MethodHook showHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object frag = param.thisObject;
                        String n = frag.getClass().getName();
                        if (isAdFragmentClass(n)) {
                            param.setResult(null);
                            XposedBridge.log("[" + TAG + "] 已拦截广告BottomSheet(show): " + n);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            try { XposedHelpers.findAndHookMethod(aqCls, "show", showHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(aqCls, "show",
                    "androidx.fragment.app.FragmentManager", String.class, showHook); } catch (Throwable ignored) {}
            // onCreateView 返回 null 兜底
            try {
                XposedHelpers.findAndHookMethod(aqCls, "onCreateView",
                        "android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (isAdFragmentClass(param.thisObject.getClass().getName())) {
                                        param.setResult(null);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
            } catch (Throwable ignored) {}
            XposedBridge.log("[" + TAG + "] AbsQueueBottomSheetDialog 拦截器已启用");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] AbsQueueBottomSheetDialog 拦截器失败: " + t);
        }
    }

    /**
     * v1.9.7 新增：silk subwindow 中央调度器拦截（覆盖"页面弹窗广告"等所有走 silk 通道的广告）。
     * 仅当被弹出的子窗口内容(i70/c 实现类)命中广告类名时才拦截，不影响正常子窗口。
     */
    private void hookSilkSubwindowManager() {
        try {
            final Class<?> mgrCls = XposedHelpers.findClassIfExists(
                    "com.bytedance.component.silk.road.subwindow.manager.c", appCl);
            if (mgrCls == null) {
                XposedBridge.log("[" + TAG + "] silk subwindow manager 接口未找到，跳过");
                return;
            }
            XposedBridge.hookAllMethods(mgrCls, "e", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object arg = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                        if (arg != null && isAdFragmentClass(arg.getClass().getName())) {
                            param.setResult(false);
                            XposedBridge.log("[" + TAG + "] 已拦截 silk 子窗口广告: " + arg.getClass().getName());
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[" + TAG + "] silk subwindow manager 拦截器已启用");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] silk subwindow manager 拦截器失败: " + t);
        }
    }

    /**
     * v1.9.6 新增：听书章节完毕广告拦截
     * 目标：BookMallAdFeedPlayOverPage / PlayTransView / PlayPage / CloseView
     * 触发时机：章节播放完毕后弹出全屏广告页（含"再来一次"按钮），用户体验非常糟
     * 策略：构造即 GONE + ViewGroup.addView 拦截 + setVisibility 拦截（三重保险）
     */
    private void hookListeningChapterAd() {
        final String[] targets = {
                "com.dragon.read.ad.feedbanner.widget.BookMallAdFeedPlayOverPage",     // 章节完毕广告页（含"再来一次"按钮）
                "com.dragon.read.ad.feedbanner.widget.BookMallAdFeedPlayTransView",    // 章节完毕过渡视图
                "com.dragon.read.ad.feedbanner.widget.BookMallAdFeedPlayPage",         // 听书播放页广告
                "com.dragon.read.ad.feedbanner.widget.BookMallAdFeedCloseView",        // 关闭按钮（无需显示）
        };
        for (final String clsName : targets) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(clsName, appCl);
                if (cls == null) {
                    XposedBridge.log("[" + TAG + "] 听书章节广告 hook 跳过(类不存在): " + clsName);
                    continue;
                }
                // 1) 构造函数 hook：创建即 GONE
                XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            ((View) param.thisObject).setVisibility(View.GONE);
                            XposedBridge.log("[" + TAG + "] 听书章节广告已构造即GONE: " + clsName);
                        } catch (Throwable ignored) {}
                    }
                });
                // 2) View.setVisibility 拦截：广告 View 被设为 VISIBLE 时强制 GONE
                XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int vis = (int) param.args[0];
                            if (vis != View.VISIBLE) return;
                            View v = (View) param.thisObject;
                            String cls = v.getClass().getName();
                            for (String t : targets) {
                                if (cls.equals(t)) {
                                    param.args[0] = View.GONE;
                                    XposedBridge.log("[" + TAG + "] 听书章节广告setVisibility拦截: " + cls);
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[" + TAG + "] 已注册听书章节广告 hook: " + clsName);
            } catch (Throwable t) {
                XposedBridge.log("[" + TAG + "] hookListeningChapterAd 失败 " + clsName + ": " + t);
            }
        }
    }

    /**
     * v1.9.6 新增：听书页广告浮层兜底拦截
     * 针对底部弹出式/全屏覆盖型推广广告（看剧赚钱红包/番茄免费小说等穿山甲/优量汇素材），
     * 这类广告通常通过 androidx BottomSheetDialog / 系统 Window 弹出，但显示文案中必含"广告/推广/立即打开/不要错过"等关键词。
     * 兜底策略：在 AudioPlaySingleActivity 中拦截任何 DialogFragment/Window 添加的浮层 View，按文本规则 GONE。
     */
    private void hookAdPopupOverlay() {
        try {
            // 1) 拦截 androidx BottomSheetDialogFragment.show 的所有子类
            //    FreeAdConversionDialog 也是 BottomSheetDialogFragment 子类，已在 hookDialogFragmentBlocker 中拦截
            //    此处覆盖更广：包名含 "ad." / "ads." / "inspire" / "popup" / "splash" / "reward" 一律短路过 show
            Class<?> dfCls = XposedHelpers.findClassIfExists("androidx.fragment.app.DialogFragment", appCl);
            if (dfCls != null) {
                XC_MethodHook showHook2 = new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object frag = param.thisObject;
                            String n = frag.getClass().getName();
                            // v1.9.6 增强：包名启发式（保守，仅 ad.* / popup.* / reward.*）
                            String low = n.toLowerCase();
                            if (low.contains(".ad.") || low.contains(".ads.")
                                    || low.contains(".popup") || low.contains(".reward")
                                    || low.contains(".splash") || low.contains(".inspire")) {
                                param.setResult(null);
                                XposedBridge.log("[" + TAG + "] 已拦截广告DialogFragment(show启发式): " + n);
                            }
                        } catch (Throwable ignored) {}
                    }
                };
                try { XposedHelpers.findAndHookMethod(dfCls, "show", "androidx.fragment.app.FragmentManager", String.class, showHook2); } catch (Throwable ignored) {}
                try { XposedHelpers.findAndHookMethod(dfCls, "show", "androidx.fragment.app.FragmentTransaction", String.class, showHook2); } catch (Throwable ignored) {}
                XposedBridge.log("[" + TAG + "] 广告DialogFragment启发式拦截已启用");
            }
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] hookAdPopupOverlay 失败: " + t);
        }
    }

    /** 判断 Fragment 类名是否命中广告弹窗关键词（保守，仅广告 SDK/弹窗类） */
    private boolean isAdFragmentClass(String n) {
        if (n == null) return false;
        String l = n.toLowerCase();
        // v1.9.6 增强：番茄内部 ad 包整体拦截（admodule/ad/ads/feedbanner/freead）
        if (l.contains("com.dragon.read.ad.") || l.contains("com.dragon.read.ads.")
                || l.contains("com.dragon.read.admodule.")) return true;
        // 阅读器激励/中断广告
        if (l.contains("inspire") || l.contains("interruptad") || l.contains("interrupt_ad")) return true;
        // 广告弹窗通用关键词（限定在 ad 相关包，避免误伤）
        if ((l.contains("ad") || l.contains("advert")) && (l.contains("dialog") || l.contains("pop") || l.contains("unlock"))) return true;
        if (l.contains("luckycat") || l.contains("reward") && l.contains("dialog")) return true;
        return false;
    }

    private void hookDialogBlocker() {
        final Handler h = new Handler(Looper.getMainLooper());
        try {
            XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final Dialog dlg = (Dialog) param.thisObject;
                        if (dlg == null) return;
                        String name = dlg.getClass().getName();
                        boolean classHit = name.contains("luckycat") || name.contains("Lucky")
                                || name.contains("Update") || name.contains("Upgrade")
                                || name.contains("AdDialog") || name.contains("AdPop")
                                || name.contains("Advert") || name.contains("VipPaying")
                                || name.contains("UnlockTime") || name.contains("Coin")
                                || name.contains("Reward") || name.contains("Task")
                                || name.contains("Welfare") || name.contains("RedPacket")
                                || name.contains("Sign") || name.contains("Invite")
                                || name.contains("Mall") || name.contains("Shop");
                        if (classHit) {
                            dlg.dismiss();
                            XposedBridge.log("[" + TAG + "] 已拦截弹窗(类名): " + name);
                            return;
                        }
                        h.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (!dlg.isShowing()) return;
                                    String hit = findBadDialogText(dlg);
                                    if (hit != null) {
                                        dlg.dismiss();
                                        XposedBridge.log("[" + TAG + "] 已拦截弹窗(内容:'" + hit + "'): " + name);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }, 400);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[" + TAG + "] Dialog 拦截器已启用");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Dialog 拦截器失败: " + t);
        }
    }

    private String findBadDialogText(Dialog dlg) {
        try {
            View decor = dlg.getWindow().getDecorView();
            return findBadText(decor, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private String findBadText(View v, int depth) {
        if (v == null || depth > 12) return null;
        if (v instanceof TextView) {
            try {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) {
                    String t = cs.toString().trim();
                    if (t.length() > 0 && t.length() <= 16 && isDialogBad(t)) {
                        return t;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String r = findBadText(vg.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private boolean isDialogBad(String t) {
        // 签到/领金币/红包弹窗
        if (t.contains("签到") && t.length() <= 10) return true;
        if (t.contains("领取") && (t.contains("金币") || t.contains("红包") || t.length() <= 8)) return true;
        if (t.contains("可领取") || t.contains("待领取") || t.contains("去领取")) return true;
        if (t.contains("金币翻倍") || t.contains("金币待")) return true;
        // 听歌/听书任务弹窗
        if (t.contains("听歌") && t.length() <= 12) return true;
        if (t.contains("做任务") || t.contains("任务完成")) return true;
        // 广告相关
        if (t.contains("广告")) return true;
        if (t.contains("激励视频") || t.contains("观看视频")) return true;
        if (t.contains("跳过") && t.length() <= 14) return true;
        if (t.contains("免广告") || t.contains("免费畅听") || t.contains("免费听")) return true;
        // 限时/促销弹窗
        if (t.contains("限时") || t.contains("新人红包")) return true;
        if (t.contains("邀请") && (t.contains("好友") || t.contains("返现"))) return true;
        // 畅听/VIP推销
        if (t.contains("畅听") && t.length() <= 12) return true;
        if (t.contains("会员") && (t.contains("领取") || t.contains("体验"))) return true;
        // v1.9.6 新增：推广广告/全屏浮层关键词
        if (t.contains("看剧赚钱") || t.contains("赚钱红包") || t.contains("看短剧")) return true;
        if (t.contains("番茄免费小说") || t.contains("免费小说")) return true;
        if (t.contains("刷短剧") || t.contains("下载试试")) return true;
        if (t.contains("不要错过") || t.contains("多人推荐") || t.contains("马上了解")) return true;
        if (t.contains("看视频免") || t.contains("看小视频")) return true;
        if (t.contains("推广") && t.length() <= 8) return true;
        return false;
    }


    private static final String[] NAV_TABS = {"首页", "听歌", "我的"};

    /** 文本级导航栏检测：容器同时含 >=2 个底部 tab 精确文本即视为导航栏/页面级容器 */
    private boolean isNavBar(View v) {
        int hit = 0;
        for (String s : NAV_TABS) {
            if (containsExactText(v, s)) hit++;
            if (hit >= 2) return true;
        }
        // 含 >=2 个 RadioButton(底部tab) 也视为导航栏
        if (countRadioButton(v) >= 2) return true;
        return false;
    }

    private int countRadioButton(View v) {
        int n = 0;
        if (v instanceof android.widget.RadioButton) n++;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) n += countRadioButton(vg.getChildAt(i));
        }
        return n;
    }

    private boolean containsExactText(View v, String s) {
        if (v == null) return false;
        if (v instanceof TextView) {
            try {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && s.equals(cs.toString().trim())) return true;
            } catch (Throwable ignored) {}
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (containsExactText(vg.getChildAt(i), s)) return true;
            }
        }
        return false;
    }

    /** 深度保护检查：容器自身受保护，或其 depth 层内的后代含受保护容器（如 AppBarLayout 外层的同名 FrameLayout 包装） */
    private boolean protectedWithin(View v, int depth) {
        if (v == null) return true;
        if (isProtectedContainer(v)) return true;
        if (depth <= 0) return false;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (protectedWithin(vg.getChildAt(i), depth - 1)) return true;
            }
        }
        return false;
    }

    /** 容器级保护：顶部栏/工具栏/标签栏/含主导航的容器绝不可整体隐藏（防止顶部菜单标签被误藏） */
    private boolean isProtectedContainer(View v) {
        if (v == null) return true;
        String n = v.getClass().getName();
        if (n.contains("AppBar") || n.contains("TopBar") || n.contains("TopView")
                || n.contains("Toolbar") || n.contains("TabLayout") || n.contains("TitleBar")
                || n.contains("BottomNav") || n.contains("BottomTab") || n.contains("BottomNavigation")) return true;
        if (countRadioButton(v) >= 2) return true;
        int hit = 0;
        for (String s : NAV_TABS) {
            if (containsExactText(v, s)) hit++;
            if (hit >= 2) return true;
        }
        return false;
    }

    /**
     * 严格的「页面顶栏」判定：只认 AppBar / Toolbar / ActionBar / TabLayout。
     * <p>
     * 与 {@link #isProtectedContainer} 的区别：后者把 "TopView" 也算受保护，是为了保护
     * 顶部标签栏；但 BookMallTopView（首页搜索栏那一行）类名里也含 "TopView"，首页的
     * 广告位就在它里面，用它做「整链放弃」会误伤。所以 hideChain 的整链放弃判定只用
     * 这个更严格的白名单。
     */
    private boolean isAppBarLike(View v) {
        if (v == null) return false;
        String n = v.getClass().getName();
        return n.contains("AppBar") || n.contains("Toolbar")
                || n.contains("ActionBar") || n.contains("TabLayout");
    }

    /** 兜底保护：底部导航类名或含主tab文本的容器绝不可被 hideChain 触底隐藏 */
    private boolean isProtectedContent(View v) {
        if (v == null) return true;
        String name = v.getClass().getName();
        if (name.contains("BottomTab") || name.contains("BottomNavigation")) return true;
        return containsExactText(v, "首页") || containsExactText(v, "听歌") || containsExactText(v, "我的");
    }

    /** 阅读页右上角 100金币 等入口整体隐藏：仅在章节阅读页生效 */
    private void scanReaderTopRightCoin(View v, int[] cnt, Activity act) {
        if (v == null || act == null) return;
        int screenW = 0, screenH = 0;
        try { View d = act.getWindow().getDecorView(); screenW = d.getWidth(); screenH = d.getHeight(); } catch (Throwable ignored) {}
        if (screenW <= 0 || screenH <= 0) return;
        if (!act.getClass().getName().contains("ReaderActivity")) {
            readerPage = -1;   // 仅小说阅读页扫描，避免其他页面顶部图标被误藏
            return;
        }
        readerPage = 1;
        try {
            scanTopRight((ViewGroup) v, cnt, screenW, screenH, act);
        } catch (Throwable ignored) {}
    }

    private boolean hasChapterTitle(View v) {
        if (v instanceof TextView) {
            try {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.toString().trim().matches("^第.{1,8}章.*")) return true;
            } catch (Throwable ignored) {}
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (hasChapterTitle(vg.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private void scanTopRight(ViewGroup vg, int[] cnt, int screenW, int screenH, Activity act) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c == null || c.getVisibility() == View.GONE) continue;
            try {
                int[] loc = new int[2];
                c.getLocationOnScreen(loc);
                int top = loc[1], left = loc[0], w = c.getWidth(), h = c.getHeight();
                boolean coinText = false;
                if (c instanceof TextView) {
                    CharSequence cs = ((TextView) c).getText();
                    String ts = cs == null ? "" : cs.toString().trim();
                    coinText = ts.contains("金币") || ts.contains("领取") || ts.contains("广告")
                            || ts.contains("赚") || ts.matches(".*\\d+金币.*");
                }
                // 宽松条件：位于屏幕上部 20% 且右侧超过 50% 屏宽
                boolean topRight = top >= 0 && top < screenH * 0.20f
                        && (left + w) > screenW * 0.50f
                        && w >= 60 && w <= screenW * 0.50f
                        && h >= 30 && h <= screenH * 0.15f;
                if (!topRight) {
                    // 递归检查子 View
                    if (c instanceof ViewGroup) scanTopRight((ViewGroup) c, cnt, screenW, screenH, act);
                    continue;
                }
                // 匹配：含金币文本，或自身可点击，或子 View 中有可点击的（如金币图标在不可点击容器内）
                boolean isCoin = coinText
                        || (c.isClickable() || c.hasOnClickListeners())
                        || hasClickableChild(c);
                if (isCoin) {
                    hideTopRightWidget(c, coinText ? "金币入口" : c.getClass().getSimpleName(), cnt, act);
                    return;
                }
            } catch (Throwable ignored) {}
            if (c instanceof ViewGroup) scanTopRight((ViewGroup) c, cnt, screenW, screenH, act);
        }
    }

    /** 检查 View 的直接子节点是否有可点击的（用于识别金币图标等无文字但子节点可点击的容器） */
    private boolean hasClickableChild(View v) {
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup vg = (ViewGroup) v;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child != null && (child.isClickable() || child.hasOnClickListeners())) {
                return true;
            }
        }
        return false;
    }

    /** 检查 View 子树是否包含广告相关文本（用于识别广告容器） */
    private boolean viewContainsAdText(View v, int depth) {
        if (v == null || depth > 8) return false;
        if (v instanceof TextView) {
            try {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) {
                    String t = cs.toString().trim();
                    if (t.length() > 0 && t.length() <= 20) {
                        if (t.contains("金币") || t.contains("畅听") || t.contains("广告")
                                || t.contains("领取") || t.contains("赚钱") || t.contains("福利")
                                || t.contains("免费") || t.contains("免广告") || t.contains("签到")
                                || t.contains("红包") || t.contains("做任务") || t.contains("激励视频")) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (viewContainsAdText(vg.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }
}
