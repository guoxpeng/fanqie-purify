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
 * 番茄畅听增强模块 v1.9.10（适配 6.7.1.16 / versionCode 671）
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
    private boolean hasListenerAttached = false;      // 防止重复注册 OnGlobalLayout
    private boolean patchAdContainerHooked = false;   // v1.9.9 听歌页贴片视频广告容器 hook 已注册
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.xs.fm".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("[" + TAG + "] v1.9.10 加载: process=" + lpparam.processName);
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
        scheduleAdSignalRetry();
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
                    if (t.length() > 14 && !isMinePageAdText(t)) return; // 放行我的页长文案
                    if (isAdEntryText(t) || isMinePageAdText(t)) {
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
                        if (t.length() > 14 && !isMinePageAdText(t)) return;
                        if (isAdEntryText(t) || isMinePageAdText(t)) {
                            hideAdEntryView((View) param.thisObject, t);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
        XposedBridge.log("[" + TAG + "] TextView.set 广告文本过滤器已启用");
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
                    if (target.getVisibility() != View.GONE) {
                        target.setVisibility(View.GONE);
                        XposedBridge.log("[" + TAG + "] 文本拦截隐藏['" + t + "'] " + target.getClass().getSimpleName()
                                + " w=" + target.getWidth() + " h=" + target.getHeight());
                    }
                } catch (Throwable ignored) {}
            }
        }, 10);
    }

    private void hookShortcutCleaner() {
        try {
            final String[] BAD = {"金币", "领现金", "畅听", "福利", "领取", "赚钱", "卸载", "存储", "领红包"};
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object arg = param.args[0];
                    if (!(arg instanceof java.util.List)) return;
                    java.util.List<?> list = (java.util.List<?>) arg;
                    java.util.ArrayList<Object> keep = new java.util.ArrayList<>();
                    int removed = 0;
                    for (Object o : list) {
                        boolean bad = false;
                        try {
                            String id = (String) o.getClass().getMethod("getId").invoke(o);
                            CharSequence label = (CharSequence) o.getClass().getMethod("getShortLabel").invoke(o);
                            String all = (id + " " + label).toLowerCase();
                            for (String b : BAD) {
                                if (all.contains(b.toLowerCase())) { bad = true; break; }
                            }
                        } catch (Throwable ignored) {}
                        if (bad) removed++; else keep.add(o);
                    }
                    if (removed > 0) {
                        param.args[0] = keep;
                        XposedBridge.log("[" + TAG + "] 已过滤桌面快捷方式 " + removed + " 个(领金币等广告入口)");
                    }
                }
            };
            Class<?> sm = null;
            try { sm = XposedHelpers.findClass("android.app.ShortcutManager", ClassLoader.getSystemClassLoader()); } catch (Throwable t1) {}
            if (sm == null) { try { sm = Class.forName("android.app.ShortcutManager"); } catch (Throwable t2) {} }
            if (sm == null) throw new RuntimeException("ShortcutManager 不可用");
            XposedBridge.hookAllMethods(sm, "addDynamicShortcuts", h);
            XposedBridge.hookAllMethods(sm, "setDynamicShortcuts", h);
            XposedBridge.hookAllMethods(sm, "updateShortcuts", h);
            XposedBridge.hookAllMethods(sm, "pushDynamicShortcut", h);
            XposedBridge.log("[" + TAG + "] 桌面快捷方式过滤器已启用");
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
        // 仅在首次注册 OnGlobalLayout；后续布局变化由 listener 驱动，不再轮询
        if (!hasListenerAttached) {
            try {
                final View decor = act.getWindow().getDecorView();
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
                hasListenerAttached = true;
            } catch (Throwable ignored) {}
        }
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
        if (cachedHideIds != null || act == null) return;
        try {
            String[] names = {
                    "bpz",  // 我的页 VIP 宣传卡片（外层 ConstraintLayout 容器）
                    "e33",  // 我的资产卡片（外层 LinearLayout 容器）
                    "ccv",  // 我的消息卡片（RelativeLayout 容器）
                    "gzy",  // 尊享免广告权益文案
                    "gr0",  // ¥14 开通按钮
                    "gjj",  // 金币余额(币) 标签
                    "gio",  // 现金余额(元) 标签
                    "gy0",  // 剩余时长(分) 标签
            };
            Set<Integer> ids = new HashSet<>();
            for (String n : names) {
                try {
                    int id = act.getResources().getIdentifier(n, "id", act.getPackageName());
                    if (id > 0) ids.add(id);
                } catch (Throwable ignored) {}
            }
            if (!ids.isEmpty()) {
                cachedHideIds = ids;
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
        // --- "我的"页面 VIP 宣传广告 与 我的资产 区域 ---
        // ⚠️ v1.9.9 重要修复：这里**只能直接隐藏卡片容器本身，禁止向上多级隐藏**。
        //    原实现用 hideByResIdUp("gzy", 3) / ("gjj", 4) 向上找父容器，实测会一路
        //    打到 #e10 和 #c1(com.dragon.read.widget.behavior.CommonCustomAppBarLayout)
        //    —— 那是"我的"页整个顶部 AppBar（头像/昵称/个人主页入口/我的消息 全在里面），
        //    结果整个头部被 GONE：页面顶部出现大片空白、下面的菜单/列表位置明显错乱。
        //    gzy/gr0 本就是 bpz 的子节点，gjj/gio/gy0 本就是 e33 的子节点，
        //    父容器 GONE 后子节点自然不可见，无需再向上隐藏。
        hideByResId(act, "bpz", cnt, "我的页VIP宣传卡片");          // VIP 卡片容器（ConstraintLayout）
        hideByResId(act, "e33", cnt, "我的资产卡片");                // 我的资产卡片容器（LinearLayout）
        hideByResId(act, "ccv", cnt, "我的消息卡片");                // 我的消息卡片容器（RelativeLayout，内含 HorizontalScrollView#ccu）
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
                    XposedBridge.log("[" + TAG + "] 跳过隐藏(位于顶栏内): '" + t + "'");
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

    private void removeAdShortcuts(Activity act) {
        try {
            final String[] BAD = {"金币", "领现金", "畅听", "福利", "领取", "赚钱", "卸载", "存储"};
            Object sm = act.getSystemService("shortcut");
            if (sm == null) return;
            java.util.List<?> dyn = (java.util.List<?>) sm.getClass().getMethod("getDynamicShortcuts").invoke(sm);
            if (dyn == null || dyn.isEmpty()) return;
            java.util.ArrayList<String> rm = new java.util.ArrayList<>();
            for (Object o : dyn) {
                try {
                    String id = (String) o.getClass().getMethod("getId").invoke(o);
                    CharSequence label = (CharSequence) o.getClass().getMethod("getShortLabel").invoke(o);
                    String all = (id + " " + label).toLowerCase();
                    for (String b : BAD) {
                        if (all.contains(b.toLowerCase())) { rm.add(id); break; }
                    }
                } catch (Throwable ignored) {}
            }
            if (!rm.isEmpty()) {
                sm.getClass().getMethod("removeDynamicShortcuts", java.util.List.class).invoke(sm, rm);
                XposedBridge.log("[" + TAG + "] 已移除桌面快捷方式: " + rm);
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
                            XposedBridge.log("[" + TAG + "] 已拦截广告View(构造即GONE): " + clsName);
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
