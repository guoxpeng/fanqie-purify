package de.robv.android.xposed;

import java.lang.reflect.Method;

/**
 * Compile-time stub for LSPosed Xposed API.
 */
public class XC_MethodHook {
    public static class MethodHookParam {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public void setThrowable(Throwable t) {}
        public Object getResultOrThrowable() throws Throwable { return null; }
        public Throwable getThrowable() { return null; }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class Unhook {
        public void unhook() {}
        public XC_MethodHook getCallback() { return null; }
        public Method getHookedMethod() { return null; }
    }
}
