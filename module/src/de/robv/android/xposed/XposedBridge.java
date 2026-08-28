package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-time stub for LSPosed Xposed API.
 */
public class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static void log(Object obj) {}
    public static Object hookMethod(Member hookMethod, XC_MethodHook callback) { return null; }
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
}
