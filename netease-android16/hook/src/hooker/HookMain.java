package hooker;

import android.content.Context;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * v12:
 * 1) 自动恢复登录凭证（cookie 备份写入 prefs/mmkv）；
 * 2) 模块的 libdexkit.so 在 LSPatch 下无法被模块 classloader 找到 -> 由我们解出来，
 *    并用 Runtime.load0(模块类, 路径) 预加载（让 JNI_OnLoad 用模块的 classloader 去 FindClass，
 *    DexKitBridge 的 native 才能成功注册）；
 * 3) 模块的 Prefs 读取加保护：CLASS_* 缓存为空时返回不存在的类名，避免 findClassIfExists("") 抛异常
 *    打断整个 hook 注册流程（DexKit 搜索分支才是它本来该走的路）。
 * 另：libnode.so 需要出现在 App 的 nativeLibraryDir（模块把它复制出来再 sh 执行），
 *     已随 origin2.apk 的 lib/arm64-v8a/ 一起打包。
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String[] SKIP_DIRS = {"cache", "code_cache", "lib", "app_webview", "app_webview_com.hihonor.cloudmusic:browser", "oat", "app_textures", "app_so"};
    private static final String[] WANT_LIBS = {"libdexkit.so", "libnode.so"};

    private static File libDir;
    private static boolean dexkitLoaded;
    private static ClassLoader moduleLoader;

    private static boolean skip(File f) {
        String n = f.getName();
        for (String s : SKIP_DIRS) if (s.equals(n)) return true;
        return false;
    }

    private static void writeFile(File dst, byte[] data) throws Throwable {
        File p = dst.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        OutputStream out = new FileOutputStream(dst);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static boolean hasLogin(File prefs) {
        if (!prefs.exists()) return false;
        try {
            byte[] b = new byte[(int) Math.min(prefs.length(), 512 * 1024)];
            InputStream in = new FileInputStream(prefs);
            try {
                int n = in.read(b);
                if (n <= 0) return false;
                String s = new String(b, 0, n, "UTF-8");
                return s.contains("MUSIC_U");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static void restore(Context ctx) {
        try {
            File dataDir = ctx.getFilesDir().getParentFile();
            if (dataDir == null) return;
            File prefs = new File(dataDir, "shared_prefs/cm_cookie_storage.xml");
            File mmkv = new File(dataDir, "files/mmkv/cm_cookie_storage");
            if (hasLogin(prefs)) {
                XposedBridge.log("hooker: cookie already present, skip restore");
                return;
            }
            XposedBridge.log("hooker: no login cookie -> restoring from embedded backup");
            writeFile(prefs, Base64.decode(Creds.prefsXml(), Base64.DEFAULT));
            writeFile(mmkv, Base64.decode(Creds.mmkv(), Base64.DEFAULT));
            XposedBridge.log("hooker: restored prefs=" + prefs.length() + " mmkv=" + mmkv.length()
                    + " hasLogin=" + hasLogin(prefs));
        } catch (Throwable t) {
            XposedBridge.log("hooker: restore fail " + t);
        }
    }

    /** 从 lspatch 缓存里找到模块 apk，把需要的 so 解到私有目录，返回目录 */
    private static File extractModuleLibs(String pkg) {
        try {
            File out = new File("/data/user/0/" + pkg + "/files/nativelibs");
            File modDir = new File("/data/user/0/" + pkg + "/cache/lspatch/com.raincat.dolby_beta");
            if (!modDir.isDirectory()) modDir = new File("/data/data/" + pkg + "/cache/lspatch/com.raincat.dolby_beta");
            File[] apks = modDir.listFiles();
            if (apks == null) {
                XposedBridge.log("hooker: module dir not found: " + modDir);
                return out;
            }
            if (!out.exists()) out.mkdirs();
            for (File apk : apks) {
                if (!apk.getName().endsWith(".apk")) continue;
                ZipFile z = new ZipFile(apk);
                try {
                    for (String want : WANT_LIBS) {
                        File dst = new File(out, want);
                        ZipEntry e = z.getEntry("lib/arm64-v8a/" + want);
                        if (e == null) continue;
                        if (dst.exists() && dst.length() == e.getSize()) continue;
                        InputStream in = z.getInputStream(e);
                        try {
                            OutputStream os = new FileOutputStream(dst);
                            try {
                                byte[] buf = new byte[128 * 1024];
                                int r;
                                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                            } finally {
                                os.close();
                            }
                        } finally {
                            in.close();
                        }
                        XposedBridge.log("hooker: extracted " + want + " -> " + dst + " (" + dst.length() + ")");
                    }
                } finally {
                    z.close();
                }
            }
            return out;
        } catch (Throwable t) {
            XposedBridge.log("hooker: extractModuleLibs fail " + t);
            return null;
        }
    }

    private static File findLib(String name) {
        if (libDir == null) return null;
        File f = new File(libDir, name);
        return f.exists() ? f : null;
    }

    /** 用模块自己的类做 Runtime.load0 -> JNI_OnLoad 里 FindClass 用的是模块 classloader，native 才能注册上 */
    private static boolean preload(String so) {
        File f = findLib(so);
        if (f == null) return false;
        try {
            Class<?> from = Class.forName("com.raincat.dolby_beta.MainHook", false, moduleLoader);
            Class<?> rt = Class.forName("java.lang.Runtime");
            Method m = rt.getDeclaredMethod("load0", Class.class, String.class);
            m.setAccessible(true);
            Object receiver = rt.getMethod("getRuntime").invoke(null);
            m.invoke(receiver, from, f.getAbsolutePath());
            XposedBridge.log("hooker: preloaded " + so + " via module classloader");
            return true;
        } catch (Throwable t) {
            XposedBridge.log("hooker: preload " + so + " fail " + t);
            return false;
        }
    }

    private static Class<?> appCls;
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

    private static void installLibHooks(String pkg, final String so) {
        try {
            XposedHelpers.findAndHookMethod("java.lang.System", moduleLoader, "load",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object a0 = param.args[0];
                            if (!(a0 instanceof String)) return;
                            String path = (String) a0;
                            String base = path.substring(path.lastIndexOf('/') + 1);
                            if (!base.equals(so)) return;
                            File mine = findLib(so);
                            if (mine != null) {
                                XposedBridge.log("hooker: System.load(" + so + ") swallowed, already preloaded");
                                param.setResult(null);
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
                            if (!want.equals(so)) return;
                            if (preload(so)) param.setResult(null);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("hooker: System.loadLibrary hook fail " + t);
        }
    }

    /** 模块的 Prefs.get(String,String)：CLASS_* 缓存为空时返回不存在的类名，避免 findClassIfExists("") 抛异常 */
    private static void guardClassPrefs() {
        try {
            XposedHelpers.findAndHookMethod("com.raincat.dolby_beta.O000OO0OO00O00OOO0O", moduleLoader,
                    "O000O0O00OO0O0OOO0O", String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object k = param.args[0];
                            if (!(k instanceof String) || !((String) k).startsWith("CLASS_")) return;
                            Object r = param.getResult();
                            if (r == null || ((String) r).trim().isEmpty()) {
                                XposedBridge.log("hooker: CLASS pref '" + k + "' empty -> placeholder");
                                param.setResult("none.DummyClass");
                            }
                        }
                    });
            XposedBridge.log("hooker: guarded module CLASS_* prefs");
        } catch (Throwable t) {
            XposedBridge.log("hooker: guardClassPrefs fail " + t);
        }
    }

    private static void copy(File src, File dst, int depth) {
        if (depth > 6) return;
        try {
            if (src.isDirectory()) {
                if (skip(src)) return;
                if (!dst.exists() && !dst.mkdirs()) return;
                File[] kids = src.listFiles();
                if (kids == null) return;
                for (File k : kids) copy(k, new File(dst, k.getName()), depth + 1);
            } else {
                long len = src.length();
                if (len <= 0 || len > 20L * 1024 * 1024) return;
                File parent = dst.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                InputStream in = new FileInputStream(src);
                try {
                    OutputStream out = new FileOutputStream(dst);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void dump(Context ctx, String tag) {
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) return;
            File base = new File(ext, "creds");
            File dataDir = ctx.getFilesDir().getParentFile();
            File[] kids = dataDir == null ? null : dataDir.listFiles();
            if (kids != null) for (File k : kids) copy(k, new File(base, k.getName()), 0);
            XposedBridge.log("hooker: dump(" + tag + ") done");
        } catch (Throwable t) {
            XposedBridge.log("hooker: dump fail " + t);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (lp == null) return;
        String pkg = lp.packageName;
        if (pkg == null || !pkg.startsWith("com.hihonor.cloudmusic")) return;
        final ClassLoader cl = lp.classLoader;
        if (cl == null) return;
        moduleLoader = HookMain.class.getClassLoader();
        XposedBridge.log("hooker: v15 loaded into " + pkg);

        libDir = extractModuleLibs(pkg);
        dexkitLoaded = preload("libdexkit.so");
        installLibHooks2(pkg, "libdexkit.so", cl);
        installLibHooks2(pkg, "libnode.so", cl);
        guardClassPrefs();

        try {
            Class<?> app = Class.forName("android.app.Application");
            XposedHelpers.findAndHookMethod(app, "attachBaseContext", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length == 1 && param.args[0] instanceof Context) {
                        restore((Context) param.args[0]);
                    }
                }
            });
            XposedBridge.log("hooker: hooked Application.attachBaseContext for cookie restore");
        } catch (Throwable t) {
            XposedBridge.log("hooker: hook attachBaseContext fail " + t);
        }

        try {
            Class<?> app = Class.forName("android.app.Application");
            XposedHelpers.findAndHookMethod(app, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Context ctx = (Context) param.thisObject;
                    new Thread(new Runnable() {
                        public void run() {
                            try { Thread.sleep(15000); } catch (Throwable ignore) {}
                            dump(ctx, "post-oncreate");
                        }
                    }, "hooker-dump").start();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("hooker: hook onCreate fail " + t);
        }
    }
}
