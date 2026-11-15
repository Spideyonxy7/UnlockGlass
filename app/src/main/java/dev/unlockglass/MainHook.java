package dev.unlockglass;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * UnlockGlass v0.2 (experimental).
 *
 * miwallpaper : forces the built-in unlock animation on (same as v0.1).
 * systemui    : forwards the lock screen swipe-up progress to the wallpaper service
 *               (MiuiKeyguardWallPaperManager.updateKeyguardWallpaperRatio) so the
 *               blur/blend between lock and home wallpaper follows the finger.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[UnlockGlass] ";

    // ---- miwallpaper ----
    private static final String CTRL = "com.miui.miwallpaper.manager.WallpaperServiceController";
    private static final String DESKTOP = "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopImageEngineImpl";
    private static final String CB = "com.miui.miwallpaper.IRemoteAnimationFinishedCallback";

    // ---- systemui ----
    private static final String SHADE_MGR = "com.android.systemui.shade.ShadeExpansionStateManager";
    private static final String KG_VIEW_MGR = "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager";

    private static volatile Object sKgViewMgr;
    private static volatile boolean sSawFull;
    private static volatile float sLastRatio = -1f;
    private static volatile int sLogBucket = -1;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if ("com.miui.miwallpaper".equals(lpp.packageName)) {
            XposedBridge.log(TAG + "loaded in " + lpp.processName);
            hookAnimFlag(lpp.classLoader);
            hookScaleGate(lpp.classLoader);
        } else if ("com.android.systemui".equals(lpp.packageName)) {
            XposedBridge.log(TAG + "systemui loaded");
            hookSystemUi(lpp.classLoader);
        }
    }

    // ===================== miwallpaper =====================

    private void hookAnimFlag(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CTRL, cl, "updateKeyguardWallpaperStateAnim",
                    boolean.class, boolean.class, int.class, boolean.class, CB,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean show = (Boolean) param.args[0];
                            boolean anim = (Boolean) param.args[1];
                            int mode = (Integer) param.args[2];
                            XposedBridge.log(TAG + "StateAnim show=" + show + " anim=" + anim + " mode=" + mode);
                            if (!show && !anim) param.args[1] = true;
                        }
                    });
            XposedBridge.log(TAG + "hooked updateKeyguardWallpaperStateAnim");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookAnimFlag failed: " + t);
        }
    }

    private void hookScaleGate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(DESKTOP, cl, "noNeedScaleAnim", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof Boolean && (Boolean) r) {
                        boolean powerSave = false;
                        try {
                            powerSave = XposedHelpers.getBooleanField(param.thisObject, "mIsPowerSave");
                        } catch (Throwable ignored) { }
                        if (!powerSave) param.setResult(Boolean.FALSE);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked noNeedScaleAnim");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookScaleGate failed: " + t);
        }
    }

    // ======================= systemui =======================

    private void hookSystemUi(ClassLoader cl) {
        try {
            Class<?> kg = XposedHelpers.findClass(KG_VIEW_MGR, cl);
            XposedBridge.hookAllConstructors(kg, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sKgViewMgr = param.thisObject;
                    XposedBridge.log(TAG + "captured StatusBarKeyguardViewManager");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "capture keyguard mgr failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(SHADE_MGR, cl, "onPanelExpansionChanged",
                    float.class, boolean.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                onExpansion((Float) param.args[0], (Boolean) param.args[2]);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "onExpansion failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked ShadeExpansionStateManager.onPanelExpansionChanged");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook shade expansion failed: " + t);
        }
    }

    /** fraction: 1 = lock screen fully in place, 0 = swiped away (assumed, verified via log). */
    private void onExpansion(float fraction, boolean tracking) {
        Object mgr = sKgViewMgr;
        if (mgr == null) return;
        Object ksc = XposedHelpers.getObjectField(mgr, "mKeyguardStateController");
        boolean showing = ksc != null && (Boolean) XposedHelpers.callMethod(ksc, "isShowing");
        if (!showing) {
            sSawFull = false;
            sLastRatio = -1f;
            return;
        }
        if (fraction > 0.99f) sSawFull = true;
        if (!sSawFull) return; // safety: only follow swipes that started from the resting lock screen

        float ratio = Math.max(0f, Math.min(1f, 1f - fraction));
        float last = sLastRatio;
        boolean edge = (ratio == 0f || ratio == 1f) && ratio != last;
        if (!edge && Math.abs(ratio - last) < 0.01f) return;
        sLastRatio = ratio;

        Object wp = XposedHelpers.getObjectField(mgr, "mMiuiKeyguardWallPaperManager");
        if (wp == null) return;
        XposedHelpers.callMethod(wp, "updateKeyguardWallpaperRatio", Float.valueOf(ratio));

        int bucket = (int) (ratio * 10f);
        if (bucket != sLogBucket || edge) {
            sLogBucket = bucket;
            XposedBridge.log(TAG + "swipe fraction=" + fraction + " ratio=" + ratio + " tracking=" + tracking);
        }
    }
}
