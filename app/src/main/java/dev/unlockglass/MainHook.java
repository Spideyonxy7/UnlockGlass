package dev.unlockglass;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * UnlockGlass v0.6 (experimental). Scope: com.android.systemui only.
 *
 * Instead of relying on the Xiaomi wallpaper service, we put our own image layer (the HOME
 * wallpaper) behind the keyguard views, inside the notification shade window, and drive it with
 * the lock screen swipe progress: crossfade + zoom 1.12 -> 1.0 + blur -> sharp.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[UnlockGlass] ";

    private static final String SHADE_MGR = "com.android.systemui.shade.ShadeExpansionStateManager";
    private static final String KG_VIEW_MGR = "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager";
    private static final String SHADE_VIEW = "com.android.systemui.shade.NotificationShadeWindowView";

    // live-tunable with:  su -c "settings put global ug_range 0.6"   (read each time the keyguard shows)
    //   ug_range  : swipe progress (0..1) at which the home wallpaper is fully visible (bigger = slower)
    //   ug_scale  : start zoom of the home wallpaper (1.0 = no zoom)
    //   ug_blur   : start blur radius (0 = no blur)
    //   ug_front  : 1 = draw the layer ABOVE the lock screen content, 0 = behind it
    private static float sRange = 0.55f;
    private static float sStartScale = 1.12f;
    private static float sMaxBlur = 30f;
    private static boolean sFront = false;

    private static volatile Object sKgViewMgr;
    private static volatile ViewGroup sShadeView;
    private static ImageView sOverlay;
    private static float sRest = Float.NaN;
    private static float sLastP = -1f;
    private static boolean sReload = true;
    private static long sNotShowingSince;
    private static int sLogBucket = -1;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.android.systemui".equals(lpp.packageName)) return;
        XposedBridge.log(TAG + "v0.6 systemui loaded");
        hookSystemUi(lpp.classLoader);
    }

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
            Class<?> sv = XposedHelpers.findClass(SHADE_VIEW, cl);
            XposedBridge.hookAllConstructors(sv, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ViewGroup) {
                        sShadeView = (ViewGroup) param.thisObject;
                        sOverlay = null;
                        XposedBridge.log(TAG + "captured NotificationShadeWindowView");
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "capture shade view failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(SHADE_MGR, cl, "onPanelExpansionChanged",
                    float.class, boolean.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                onExpansion((Float) param.args[0]);
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

    // ---------------------------------------------------------------- swipe handling

    private static boolean readBool(Object obj, String field) {
        try {
            return obj != null && XposedHelpers.getBooleanField(obj, field);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object kscOf(Object mgr) {
        try {
            return XposedHelpers.getObjectField(mgr, "mKeyguardStateController");
        } catch (Throwable t) {
            return null;
        }
    }

    private void onExpansion(float fraction) {
        ViewGroup shade = sShadeView;
        Object mgr = sKgViewMgr;
        if (shade == null || mgr == null) return;

        Object ksc = kscOf(mgr);
        boolean showing = readBool(ksc, "mShowing");
        if (!showing) {
            sRest = Float.NaN; // the CHECK runnable hides the overlay if it is still visible
            return;
        }
        if (Float.isNaN(sRest)) {
            sRest = fraction > 0.5f ? 1f : 0f;
            refreshConfig(shade.getContext());
            XposedBridge.log(TAG + "keyguard showing, rest=" + sRest + " first fraction=" + fraction);
        }
        boolean bouncer = readBool(ksc, "mPrimaryBouncerShowing");

        float ratio = Math.max(0f, Math.min(1f, Math.abs(fraction - sRest)));
        float t = Math.min(1f, ratio / Math.max(0.05f, sRange));
        float p = bouncer ? 0f : t * t * (3f - 2f * t); // smoothstep
        applyOverlay(shade, p);
    }


    // ---------------------------------------------------------------- live config

    private static float cfg(Context ctx, String key, float def) {
        try {
            String v = android.provider.Settings.Global.getString(ctx.getContentResolver(), key);
            if (v != null && !v.trim().isEmpty()) return Float.parseFloat(v.trim());
        } catch (Throwable ignored) { }
        return def;
    }

    private static void refreshConfig(Context ctx) {
        sRange = cfg(ctx, "ug_range", 0.55f);
        sStartScale = cfg(ctx, "ug_scale", 1.12f);
        sMaxBlur = cfg(ctx, "ug_blur", 30f);
        sFront = cfg(ctx, "ug_front", 0f) >= 0.5f;
        XposedBridge.log(TAG + "config range=" + sRange + " scale=" + sStartScale
                + " blur=" + sMaxBlur + " front=" + sFront);
    }

    private static void placeOverlay(ViewGroup shade, ImageView ov) {
        try {
            int last = shade.getChildCount() - 1;
            int idx = shade.indexOfChild(ov);
            boolean ok = sFront ? (idx == last) : (idx == 0);
            if (ok) return;
            shade.removeView(ov);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (sFront) {
                shade.addView(ov, lp);
            } else {
                shade.addView(ov, 0, lp);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "placeOverlay failed: " + t);
        }
    }

    // ---------------------------------------------------------------- overlay layer

    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            ViewGroup shade = sShadeView;
            ImageView ov = sOverlay;
            if (shade == null || ov == null || ov.getVisibility() != View.VISIBLE) return;
            Object mgr = sKgViewMgr;
            boolean showing = mgr != null && readBool(kscOf(mgr), "mShowing");
            long now = SystemClock.uptimeMillis();
            if (showing) {
                sNotShowingSince = 0;
                shade.postDelayed(this, 120);
            } else {
                if (sNotShowingSince == 0) sNotShowingSince = now;
                if (now - sNotShowingSince > 400) {
                    hideOverlay();
                } else {
                    shade.postDelayed(this, 120);
                }
            }
        }
    };

    private static void hideOverlay() {
        ImageView ov = sOverlay;
        if (ov != null) {
            ov.setAlpha(0f);
            ov.setRenderEffect(null);
            ov.setVisibility(View.GONE);
        }
        sLastP = -1f;
        sReload = true;
        sLogBucket = -1;
    }

    private void applyOverlay(ViewGroup shade, float p) {
        if (p <= 0f) {
            if (sOverlay != null && sOverlay.getVisibility() == View.VISIBLE) hideOverlay();
            sLastP = 0f;
            return;
        }
        if (Math.abs(p - sLastP) < 0.004f && p < 1f) return;
        ImageView ov = ensureOverlay(shade);
        if (ov == null) return;
        sLastP = p;
        if (ov.getVisibility() != View.VISIBLE) {
            refreshConfig(shade.getContext());
            placeOverlay(shade, ov);
            ov.setVisibility(View.VISIBLE);
            sNotShowingSince = 0;
            shade.postDelayed(CHECK, 120);
        }
        float s = sStartScale - (sStartScale - 1f) * p;
        ov.setAlpha(p);
        ov.setScaleX(s);
        ov.setScaleY(s);
        float blur = (1f - p) * sMaxBlur;
        if (blur > 0.5f) {
            ov.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        } else {
            ov.setRenderEffect(null);
        }
        int bucket = (int) (p * 10f);
        if (bucket != sLogBucket) {
            sLogBucket = bucket;
            XposedBridge.log(TAG + "overlay p=" + p + " scale=" + s + " blur=" + blur);
        }
    }

    private static ImageView ensureOverlay(ViewGroup shade) {
        try {
            Context ctx = shade.getContext();
            if (sOverlay == null || sOverlay.getParent() != shade) {
                ImageView iv = new ImageView(ctx);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setVisibility(View.GONE);
                iv.setClickable(false);
                iv.setFocusable(false);
                shade.addView(iv, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                sOverlay = iv;
                sReload = true;
                XposedBridge.log(TAG + "overlay view added to shade window");
            }
            if (sReload) {
                Drawable d = loadHomeWallpaper(ctx);
                if (d == null) return null;
                sOverlay.setImageDrawable(d);
                sReload = false;
            }
            return sOverlay;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ensureOverlay failed: " + t);
            return null;
        }
    }

    private static Drawable loadHomeWallpaper(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
            if (pfd != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, o);
                pfd.close();
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 2400) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                ParcelFileDescriptor pfd2 = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
                Bitmap bmp = BitmapFactory.decodeFileDescriptor(pfd2.getFileDescriptor(), null, o2);
                pfd2.close();
                if (bmp != null) {
                    XposedBridge.log(TAG + "home wallpaper loaded " + bmp.getWidth() + "x" + bmp.getHeight());
                    return new BitmapDrawable(ctx.getResources(), bmp);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "getWallpaperFile failed: " + t);
        }
        try {
            Drawable d = WallpaperManager.getInstance(ctx).getDrawable();
            XposedBridge.log(TAG + "home wallpaper via getDrawable: " + (d != null));
            return d;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "getDrawable failed: " + t);
            return null;
        }
    }
}
