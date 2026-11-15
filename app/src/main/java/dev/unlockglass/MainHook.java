package dev.unlockglass;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * UnlockGlass v0.1 (experimental, untested on device).
 * Scope: com.miui.miwallpaper (HyperOS wallpaper service).
 *
 * HyperOS already contains a lock -> home wallpaper transition, but SystemUI asks for it
 * with anim=false and a gate (noNeedScaleAnim) skips the desktop scale animation.
 * This module turns both on.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[UnlockGlass] ";
    private static final String CTRL = "com.miui.miwallpaper.manager.WallpaperServiceController";
    private static final String DESKTOP = "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopImageEngineImpl";
    private static final String CB = "com.miui.miwallpaper.IRemoteAnimationFinishedCallback";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.miui.miwallpaper".equals(lpp.packageName)) return;
        ClassLoader cl = lpp.classLoader;
        XposedBridge.log(TAG + "loaded in " + lpp.processName);
        hookAnimFlag(cl);
        hookScaleGate(cl);
    }

    // updateKeyguardWallpaperStateAnim(boolean show, boolean anim, int mode, boolean block, callback)
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
                            if (!show && !anim) {
                                param.args[1] = true; // play the animated hide instead of a hard cut
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked updateKeyguardWallpaperStateAnim");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookAnimFlag failed: " + t);
        }
    }

    // DesktopImageEngineImpl.noNeedScaleAnim(): true means "skip the unlock scale animation"
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
                        XposedBridge.log(TAG + "noNeedScaleAnim was true (powerSave=" + powerSave + ")");
                        if (!powerSave) param.setResult(Boolean.FALSE);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked noNeedScaleAnim");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookScaleGate failed: " + t);
        }
    }
}
