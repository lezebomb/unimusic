package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public final void setPriority(int priority) {}

    public class Unhook {
        public Member getHookedMethod() { return null; }
        public void unhook() {}
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.returnEarly = true; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public void setThrowable(Throwable t) { this.throwable = t; }
        public Object getObjectExtra(String key) { return null; }
        public void setObjectExtra(String key, Object o) {}
    }
}
