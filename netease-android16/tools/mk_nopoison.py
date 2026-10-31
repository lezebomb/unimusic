import zipfile, shutil, os
src = r"D:\unimusic\work\out492\origin-492-lspatched.apk"
dst = r"D:\unimusic\work\cand_nopoison_raw.apk"
drop = {"lib/arm64-v8a/libpoison.so"}
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED)
n=0
for it in zin.infolist():
    if it.filename in drop:
        print("dropped", it.filename); continue
    data = zin.read(it.filename)
    zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
    zi.compress_type = it.compress_type
    zi.external_attr = it.external_attr
    zout.writestr(zi, data)
    n+=1
zout.close(); zin.close()
print("entries", n, "size", os.path.getsize(dst))
