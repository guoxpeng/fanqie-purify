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
 * 番茄畅听增强模块 v1.9.5（合并版+修复）
 * 基底 = v19 完整功能：VIP patch、底部商城/领现金tab隐藏、广告卡整体隐藏、
 *   三级入口隐藏(hideEntry/hideShallow/hideChain)、桌面快捷方式清理、阅读页金币面板、弹窗拦截。
 * 合入 adfix 增强：hookAdSignals 源码级拦截广告SDK调用、更广 BLOCKED 页面前缀、
 * v26：隐藏一律 GONE（不再 INVISIBLE 占位，菜单自动补位）；听歌页底部「看小视频免30分钟广告」横幅整条 GONE（hideWholeWidget，允许底部全宽短容器）。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FanqieEnhance";

    private static final String[] BLOCKED = {
            "com.xs.fm.live.impl.ecom.mall.NativeMallActivity",
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.xs.fm".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("[" + TAG + "] 加载: process=" + lpparam.processName);
        this.appCl = lpparam.classLoader;
        hookActivityBlocker();
        hookDialogBlocker();
        hookShortcutCleaner();
        hookAdSignals();
        scheduleAdSignalRetry();
        scheduleUnknownViewScanner();
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
                    h.postDelayed(new Runnable() { public void run() { patchVip(); } }, 600);
                    h.postDelayed(new Runnable() { public void run() { patchVip(); } }, 1500);
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

    /** 首轮120ms + 前30秒高频轮询(300ms)之后1.5s + OnGlobalLayout(限频120ms) */
    private void startHideWatch(final Activity act, final Handler h) {
        final WeakReference<Activity> wref = new WeakReference<>(act);
        h.postDelayed(new Runnable() {
            public void run() {
                Activity a = wref.get();
                if (a != null) { try { hideAll(a); } catch (Throwable ignored) {} }
            }
        }, 120);
        final long t0 = System.currentTimeMillis();
        h.postDelayed(new Runnable() {
            public void run() {
                Activity a = wref.get();
                if (a == null || a.isFinishing()) return;
                try { hideAll(a); } catch (Throwable ignored) {}
                h.postDelayed(this, (System.currentTimeMillis() - t0) < 30000 ? 300 : 1500);
            }
        }, 300);
        try {
            final View decor = act.getWindow().getDecorView();
            final WeakReference<Activity> ref = new WeakReference<>(act);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                private long last = 0;
                @Override
                public void onGlobalLayout() {
                    Activity a = ref.get();
                    if (a == null) return;
                    long now = System.currentTimeMillis();
                    if (now - last < 120) return;
                    last = now;
                    hideAll(a);
                }
            });
        } catch (Throwable ignored) {}
    }

    private int hideAll(Activity act) {
        int[] cnt = {0};
        if (readerAct != act) { readerAct = act; readerPage = 0; }
        try {
            View decor = act.getWindow().getDecorView();
            scanAll(decor, 0, cnt, act);
            scanRadioGroup(decor, cnt);
            scanReaderTopRightCoin(decor, cnt, act);
            scanListeningPageAds(act, cnt);
        } catch (Throwable ignored) {}
        try {
            scanAllWindows(act, cnt);
        } catch (Throwable ignored) {}
        return cnt[0];
    }

    /** 扫描听歌页：通过已知混淆广告资源 ID 精确隐藏 */
    private void scanListeningPageAds(Activity act, int[] cnt) {
        if (act == null) return;
        String actName = act.getClass().getName();
        // 听歌页在 MainFragmentActivity，且听歌 tab 为当前页
        if (!actName.contains("MainFragmentActivity")) return;
        try {
            // 已知广告位资源 ID（从 ui-now.xml 分析得到）
            // a3c: 全宽横幅 [0,1281][1080,1413]，猜你喜欢下方
            // fp_: 卡片容器 [60,1455][1020,1581]，含 fpd
            hideByResId(act, "a3c", cnt, "听歌页横幅广告");
            hideByResId(act, "fp_", cnt, "听歌页卡片广告");
            // 检测全屏覆盖型广告（覆盖 MV 的插屏/视频广告）
            detectOverlayAds(act, cnt);
        } catch (Throwable ignored) {}
    }

    /** 检测全屏覆盖广告：任何覆盖 MV 区域的大面积可点击 View（排除已知 UI） */
    private void detectOverlayAds(Activity act, int[] cnt) {
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
        if (cls.contains("RecyclerView") || cls.contains("ViewPager")
                || cls.contains("FrameLayout") || cls.contains("LinearLayout")
                || cls.contains("RelativeLayout") || cls.contains("ConstraintLayout")
                || cls.contains("CoordinatorLayout") || cls.contains("DecorView")
                || cls.contains("ContentFrameLayout") || cls.contains("BackView")) {
            // 基础布局容器不算，继续往下找
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++)
                    detectOverlayRecursive(vg.getChildAt(i), depth + 1, sw, sh, cnt);
            }
            return;
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
            Class<?> cls = XposedHelpers.findClassIfExists(fullClassName, appCl);
            if (cls == null) return;
            // 避免重复 hook
            String key = "hooked_" + fullClassName;
            if (seenGold.contains(key)) return;
            seenGold.add(key);
            XposedHelpers.hookAllConstructors(cls, new XC_MethodHook() {
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

    /** 扫描进程内所有窗口根View（覆盖悬浮窗/额外 window 里的领金币入口） */
    private void scanAllWindows(Activity act, int[] cnt) throws Exception {
        Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
        Object inst = wmg.getMethod("getInstance").invoke(null);
        Field f = wmg.getDeclaredField("mViews");
        f.setAccessible(true);
        Object o = f.get(inst);
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
        if (v == null || depth > 40) return;
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
                if (txt != null && (txt.contains("商城") || txt.contains("领现金"))) {
                    if (c.getVisibility() != View.GONE) {
                        c.setVisibility(View.GONE);
                        cnt[0]++;
                        XposedBridge.log("[" + TAG + "] 已隐藏底部tab['" + txt + "'] view=" + c.getClass().getName());
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
        if (t.equals("首页") || t.equals("听歌") || t.equals("我的")) return false;
        if (t.contains("资产")) return true;
        if (t.contains("购物车") || t.contains("优惠券")) return true;
        if (t.contains("立即领取")) return true;
        if (t.contains("直播") && t.length() <= 4) return true;
        if (t.contains("现金") && !t.matches(".*[0-9].*")) return true;
        if (t.contains("福利") && t.length() <= 8) return true;
        if (t.contains("商城") || t.contains("领现金")) return true;
        if (t.contains("游戏中心")) return true;
        if (t.contains("金币")) return true;
        if (t.contains("广告") && (t.contains("免") || t.contains("看"))) return true;
        if (t.contains("全天") && t.contains("畅听")) return true;
        if (t.contains("激励视频") || t.contains("观看视频")) return true;
        if (t.contains("再看") && t.contains("分钟")) return true;
        if (t.contains("免费畅听") || t.contains("免费听")) return true;
        if (t.contains("借钱")) return true;
        if (t.contains("公益")) return true;
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
        try {
            Class<?> acct = Class.forName("com.dragon.read.user.AcctManager", true, appCl);
            Field instF = acct.getDeclaredField("INSTANCE");
            instF.setAccessible(true);
            Object acctObj = instF.get(null);
            if (acctObj == null) { retry(); return; }
            Field umF = acct.getDeclaredField("userModel");
            umF.setAccessible(true);
            Object model = umF.get(acctObj);
            if (model == null) { retry(); return; }
            setField(model, "isVip", true);
            setField(model, "freeAd", true);
            setField(model, "expireTime", "2099-12-31");
            setField(model, "leftTime", "999999999");
            trySetField(model, "reverseVIP", true);
            trySetField(model, "freeAdLeft", 999999999L);
            trySetField(model, "freeAdExpire", 4102444800000L);
            XposedBridge.log("[" + TAG + "] 已patch userModel isVip=true reportLen=" + adHookReport.length());
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] patchVip 异常: " + t);
            retry();
        }
    }

    private void retry() {
        if (patchTries++ < 10) {
            final Handler h = new Handler(Looper.getMainLooper());
            h.postDelayed(new Runnable() { public void run() { patchVip(); } }, 400);
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

    /** 扫描听歌页所有无文本可点击 View（识别潜在广告位）；只跑一次，记录到日志 */
    private void scheduleUnknownViewScanner() {
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try { scanUnknownClickableViews(); } catch (Throwable t) {}
            }
        }, 5000);
    }

    /** 遍历当前 Activity 的 View 树，找出所有无文本/无 contentDescription 的可点击 View */
    private void scanUnknownClickableViews() {
        Activity top = getCurrentTopActivity();
        if (top == null) return;
        String actName = top.getClass().getName();
        View decor = top.getWindow().getDecorView();
        StringBuilder sb = new StringBuilder();
        scanUnknownClickables(decor, 0, actName, sb);
        if (sb.length() > 0) {
            XposedBridge.log("[" + TAG + "] 听歌页未知可点击View: " + sb);
        }
    }

    private void scanUnknownClickables(View v, int depth, String act, StringBuilder sb) {
        if (v == null || depth > 30) return;
        if (v.isClickable() || v.hasOnClickListeners()) {
            boolean hasText = false;
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                hasText = cs != null && cs.toString().trim().length() > 0;
            }
            if (!hasText && v.getContentDescription() == null) {
                String rid = "";
                try { rid = " id=" + v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
                int[] loc = new int[2];
                try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                sb.append("\n  ").append(v.getClass().getSimpleName())
                  .append(rid)
                  .append(" @[").append(loc[0]).append(",").append(loc[1]).append("]")
                  .append(" ").append(v.getWidth()).append("x").append(v.getHeight());
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                scanUnknownClickables(vg.getChildAt(i), depth + 1, act, sb);
            }
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

    /** 延迟类可能在首个 Activity 后才加载；补偿性重试，避免首次 findClassIfExists 过早失败。 */
    private void scheduleAdSignalRetry() {
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try { hookAdSignals(); } catch (Throwable t) {
                    XposedBridge.log("[" + TAG + "] 延迟广告 hook 失败: " + t);
                }
            }
        }, 2500);
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
                XposedHelpers.hookAllConstructors(vc, new XC_MethodHook() {
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
                        XposedHelpers.hookAllMethods(cls, hookedMethod, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(null);
                                XposedBridge.log("[" + TAG + "] 已阻止广告调用: " + hookedMethod);
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
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
                                || name.contains("UnlockTime");
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
        if (t.contains("签到") && t.length() <= 8) return true;
        if (t.contains("听歌")) return true;
        if (t.contains("领取") && t.contains("金币")) return true;
        if (t.contains("广告")) return true;
        if (t.contains("跳过") && t.length() <= 12) return true;
        if (t.contains("激励视频") || t.contains("观看视频")) return true;
        if (t.contains("广告") && (t.contains("免费") || t.contains("解锁") || t.contains("继续"))) return true;
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
                    coinText = ts.contains("金币") || ts.contains("领取") || ts.contains("广告");
                }
                boolean topRight = top >= 0 && top < screenH * 0.18f
                        && (left + w) > screenW * 0.52f
                        && w >= 120 && w <= screenW * 0.45f
                        && h >= 40 && h <= screenH * 0.08f
                        && (coinText || ((c.isClickable() || c.hasOnClickListeners()) && !(c instanceof TextView)));
                if (topRight) {
                    hideTopRightWidget(c, coinText ? "金币入口" : c.getClass().getSimpleName(), cnt, act);
                    return;
                }
            } catch (Throwable ignored) {}
            if (c instanceof ViewGroup) scanTopRight((ViewGroup) c, cnt, screenW, screenH, act);
        }
    }
}
