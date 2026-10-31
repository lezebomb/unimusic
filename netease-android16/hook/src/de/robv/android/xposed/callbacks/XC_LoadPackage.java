package de.robv.android.xposed.callbacks;

public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
        public String processName;
        public boolean isFirstApplication;
        public Object appInfo;
    }
}
