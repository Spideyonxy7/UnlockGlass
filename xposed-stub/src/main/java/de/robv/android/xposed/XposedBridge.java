package de.robv.android.xposed;
import java.util.Set;
public final class XposedBridge {
    public static synchronized void log(String text) {}
    public static synchronized void log(Throwable t) {}
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) { return null; }
}
