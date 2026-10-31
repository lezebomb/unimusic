import re
data = open(r"D:\unimusic\work\libs\libpoison.so","rb").read()
strs = re.findall(rb"[\x20-\x7e]{5,200}", data)
kw = ["xposed","Xposed","lspatch","LSPatch","frida","Frida","TracerPid","ptrace","maps","APK Signature","poison","Poison","POISON","signature","Signature","checksum","hook","Hook","magisk","riru","Zygisk","zygisk","debug","Debug","tamper","vm_","pthread","__system_property","/proc/","dex","apk"]
seen=set()
for s in strs:
    t=s.decode("latin1")
    if any(k in t for k in kw) and t not in seen:
        seen.add(t)
        print(t)
