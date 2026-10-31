import zipfile, zlib, struct, os
src = r"D:\unimusic\work\out492\origin-492-lspatched.apk"
dst = r"D:\unimusic\work\cand_nopoison_raw.apk"
drop = "lib/arm64-v8a/libpoison.so"
zin = zipfile.ZipFile(src)
fp = open(src, "rb")
zout = zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, allowZip64=True)
n = 0
for zi in zin.infolist():
    if zi.filename == drop:
        print("drop", zi.filename); continue
    fp.seek(zi.header_offset)
    lh = fp.read(30)
    sig, ver, flag, method, mt, md, crc, csize, usize, nlen, elen = struct.unpack("<IHHHHHIIIHH", lh)
    assert sig == 0x04034b50, hex(sig)
    fp.seek(zi.header_offset + 30 + nlen + elen)
    raw = fp.read(zi.compress_size)
    if zi.compress_type == zipfile.ZIP_STORED:
        data = raw
    else:
        data = zlib.decompress(raw, -15)
    ni = zipfile.ZipInfo(zi.filename, date_time=zi.date_time)
    ni.compress_type = zi.compress_type
    ni.external_attr = zi.external_attr
    ni.internal_attr = zi.internal_attr
    ni.create_system = zi.create_system
    zout.writestr(ni, data)
    n += 1
zout.close(); fp.close(); zin.close()
print("entries", n, "size", os.path.getsize(dst))
print("check arsc:", [(i.filename, i.compress_type) for i in zipfile.ZipFile(dst).infolist() if i.filename == "resources.arsc"])
