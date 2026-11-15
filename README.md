# UnlockGlass v0.5 (experimental)
LSPosed module for HyperOS / SaanOS. Scope: com.miui.miwallpaper AND com.android.systemui.

- wallpaper service: forces the built-in unlock animation on (anim=true, noNeedScaleAnim=false)
- SystemUI: forwards lock screen swipe-up progress to the wallpaper service so blur/blend follows the finger

## Update
unzip -o over ~/UnlockGlass, then: git add -A && git commit -m v0.5 && git push
Download the new APK from GitHub Actions (artifact UnlockGlass-debug).

## Install
1. Install APK, LSPosed: enable module, scope = Wallpaper (com.miui.miwallpaper) + System UI (com.android.systemui)
2. su -c "am force-stop com.miui.miwallpaper" ; then restart SystemUI: su -c "killall com.android.systemui" (or reboot)
3. Lock, swipe up slowly, release halfway, then swipe to unlock. Then:
   su -c "logcat -d" | grep UnlockGlass
Disable the module in LSPosed to roll back.
