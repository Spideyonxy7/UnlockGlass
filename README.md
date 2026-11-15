# UnlockGlass v0.1 (experimental)
LSPosed module for HyperOS / SaanOS. Scope: com.miui.miwallpaper only.

Turns on the built-in lock -> home wallpaper unlock animation:
1. forces anim=true in WallpaperServiceController.updateKeyguardWallpaperStateAnim
2. makes DesktopImageEngineImpl.noNeedScaleAnim() return false (unless battery saver is on)

## Build (phone only)
Push this folder to a GitHub repo -> Actions tab -> "build" -> download artifact UnlockGlass-debug.
(or open in Android Studio and run assembleDebug)

## Install
1. Install the APK, enable it in LSPosed, scope = Wallpaper (com.miui.miwallpaper)
2. su -c "am force-stop com.miui.miwallpaper"  (or reboot)
3. Lock, unlock, then:  su -c "logcat -d" | grep UnlockGlass
Disable the module in LSPosed to roll back.
