# UnlockGlass v0.6 (experimental)
LSPosed module for HyperOS / SaanOS. Scope: com.android.systemui ONLY.

Adds an image layer (your home wallpaper) behind the lock screen views and drives it with the
lock screen swipe: crossfade + zoom 1.12 -> 1.0 + blur -> sharp. Does not touch the Xiaomi wallpaper service.

Tunables in MainHook.java: RANGE, START_SCALE, MAX_BLUR.

Update: unzip -o over ~/UnlockGlass, git add -A && git commit -m v0.6 && git push, then take the APK from Actions.
After install: su -c "killall com.android.systemui" (or reboot).
Logs: su -c "logcat -d" | grep UnlockGlass
Roll back: disable the module in LSPosed.
