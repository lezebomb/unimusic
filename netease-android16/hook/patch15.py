import io

p = r'D:\unimusic\work\hook\src\hooker\HookMain.java'
b = open(p, 'rb').read()

b = b.replace(b'installLibHooks(pkg, "libdexkit.so");', b'installLibHooks2(pkg, "libdexkit.so", cl);')
b = b.replace(b'installLibHooks(pkg, "libnode.so");', b'installLibHooks2(pkg, "libnode.so", cl);')
b = b.replace(b'hooker: v12 loaded into ', b'hooker: v15 loaded into ')

new = r'''    private static Class<?> appCls;
    private static Method load0M;
    private static Object rtInst;

    private static void initLoad0(ClassLoader l) {
        try {
            if (appCls != null) return;
            appCls = Class.forName("com.netease.cloudmusic.CloudMusicApplication", false, l);
            Class<?> rtc = Class.forName("java.lang.Runtime");
            load0M = rtc.getDeclaredMethod("load0", Class.class, String.class);
            load0M.setAccessible(true);
            rtInst = rtc.getMethod("getRuntime").invoke(null);
            XposedBridge.log("hooker: app-ns load0 ready");
        } catch (Throwable t) {
            XposedBridge.log("hooker: initLoad0 fail " + t);
        }
    }

    private static void loadAppNs(String fileOrName) throws Throwable {
        if (load0M == null) throw new IllegalStateException("load0 not ready");
        load0M.invoke(rtInst, appCls, fileOrName);
    }

    private static void installLibHooks2(String pkg, final String so, final ClassLoader appLoader) {
        initLoad0(appLoader);
        try {
            XposedHelpers.findAndHookMethod("java.lang.System", moduleLoader, "load",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object a0 = param.args[0];
                            if (!(a0 instanceof String)) return;
                            String path = (String) a0;
                            String base = path.substring(path.lastIndexOf('/') + 1);
                            if (base.equals(so)) {
                                if (findLib(so) != null) {
                                    XposedBridge.log("hooker: System.load(" + so + ") swallowed, already preloaded");
                                    param.setResult(null);
                                }
                                return;
                            }
                            try {
                                loadAppNs(path);
                                param.setResult(null);
                            } catch (Throwable t) {
                                XposedBridge.log("hooker: load passthru fail " + path);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("hooker: System.load hook fail " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("java.lang.System", moduleLoader, "loadLibrary",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object a0 = param.args[0];
                            if (!(a0 instanceof String)) return;
                            String name = (String) a0;
                            String want = name.startsWith("lib") ? name + ".so" : "lib" + name + ".so";
                            if (want.equals(so)) {
                                if (preload(so)) param.setResult(null);
                                return;
                            }
                            try {
                                loadAppNs(System.mapLibraryName(name));
                                param.setResult(null);
                            } catch (Throwable t) {
                                XposedBridge.log("hooker: loadLibrary passthru fail " + name);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("hooker: System.loadLibrary hook fail " + t);
        }
    }

'''

anchor = b'    private static void installLibHooks(String pkg, final String so) {'
assert anchor in b, 'anchor missing'
b = b.replace(anchor, new.encode('utf-8') + anchor, 1)
open(p, 'wb').write(b)
print('patched ok, bytes=', len(b))
