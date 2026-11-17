# UnlockGlass v0.7 (experimental)
LSPosed module for HyperOS / SaanOS. Scope: com.android.systemui ONLY.

Adds an image layer (your home wallpaper) behind the lock screen views and drives it with the
lock screen swipe: crossfade + zoom 1.12 -> 1.0 + blur -> sharp. Does not touch the Xiaomi wallpaper service.

Tunables in MainHook.java: RANGE, START_SCALE, MAX_BLUR.

Update: unzip -o over ~/UnlockGlass, git add -A && git commit -m v0.7 && git push, then take the APK from Actions.
After install: su -c "killall com.android.systemui" (or reboot).
Logs: su -c "logcat -d" | grep UnlockGlass
Roll back: disable the module in LSPosed.

## Live tuning (no rebuild)
su -c "settings put global ug_range 0.7"   # bigger = slower reveal
su -c "settings put global ug_scale 1.2"   # start zoom
su -c "settings put global ug_blur 40"     # start blur
su -c "settings put global ug_front 1"     # 1 = layer above lock content, 0 = behind
Applied the next time the lock screen shows. Reset: su -c "settings delete global ug_range" (same for the others)
