"""把设备上的登录 cookie 内联进 hooker/Creds.java（用于自动恢复登录态）。

从设备抓取（包已装且已登录时）：
  adb shell "cp /sdcard/Android/data/com.hihonor.cloudmusic/files/creds/shared_prefs/cm_cookie_storage.xml /data/local/tmp/" 2>/dev/null
  # 或者用本仓库 hook 模块自带的导出（Application#onCreate 后 12s 复制到 files/creds）：
  adb pull /sdcard/Android/data/com.hihonor.cloudmusic/files/creds/shared_prefs/cm_cookie_storage.xml .
  adb pull /sdcard/Android/data/com.hihonor.cloudmusic/files/creds/files/mmkv/cm_cookie_storage .

用法：
  python gen_creds.py <cm_cookie_storage.xml> <mmkv_cm_cookie_storage> [输出 Creds.java 路径]

注意：生成出来的 Creds.java 含 MUSIC_U 等账号凭证，只留在自己机器上，不要提交。
"""
import base64
import sys

USAGE = "python gen_creds.py <cm_cookie_storage.xml> <mmkv_cm_cookie_storage> [out Creds.java]"


def b64(b: bytes) -> str:
    s = base64.b64encode(b).decode()
    return "\n".join('        + "' + s[i:i + 200] + '"' for i in range(0, len(s), 200))


def main() -> int:
    if len(sys.argv) < 3:
        print(USAGE)
        return 2
    xml = open(sys.argv[1], "rb").read()
    mmkv = open(sys.argv[2], "rb").read()
    out = sys.argv[3] if len(sys.argv) > 3 else "Creds.java"

    src = '''package hooker;

/** 由 tools/gen_creds.py 生成：内联登录 cookie，attachBaseContext 阶段自动写回。 */
public final class Creds {
    private Creds() {}

    public static String prefsXml() {
        return ""
%s;
    }

    public static String mmkv() {
        return ""
%s;
    }
}
''' % (b64(xml), b64(mmkv))

    open(out, "w", encoding="utf-8").write(src)
    print("written", out, len(src), "chars")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
