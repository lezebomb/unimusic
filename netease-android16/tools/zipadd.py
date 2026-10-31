import zipfile, zlib, struct, os, sys

# usage: python zipadd.py <src.apk> <dst.apk> <key=value> ...
#   key = entry name, value = local file path on disk. Existing entry is replaced.
src, dst = sys.argv[1], sys.argv[2]
adds = {}
for spec in sys.argv[3:]:
    k, v = spec.split("=", 1)
    adds[k] = v

zin = zipfile.ZipFile(src)
fp = open(src, "rb")
zout = zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, allowZip64=True)
n = 0
for zi in zin.infolist():
    fp.seek(zi.header_offset)
    lh = fp.read(30)
    sig, ver, flag, method, mt, md, crc, csize, usize, nlen, elen = struct.unpack("<IHHHHHIIIHH", lh)
    assert sig == 0x04034b50, hex(sig)
    fp.seek(zi.header_offset + 30 + nlen + elen)
    raw = fp.read(csize)
    if zi.filename in adds:
        data = open(adds[zi.filename], "rb").read()
        ni = zipfile.ZipInfo(zi.filename, date_time=zi.date_time)
        ni.compress_type = zi.compress_type
        ni.external_attr = zi.external_attr
        zout.writestr(ni, data)
        n += 1
        continue
    data = raw if zi.compress_type == zipfile.ZIP_STORED else zlib.decompress(raw, -15)
    ni = zipfile.ZipInfo(zi.filename, date_time=zi.date_time)
    ni.compress_type = zi.compress_type
    ni.external_attr = zi.external_attr
    ni.internal_attr = zi.internal_attr
    ni.create_system = zi.create_system
    zout.writestr(ni, data)
    n += 1

names = set(zin.namelist())
for k, v in adds.items():
    if k in names:
        continue
    data = open(v, "rb").read()
    ni = zipfile.ZipInfo(k, date_time=(2026, 1, 1, 0, 0, 0))
    ni.compress_type = zipfile.ZIP_DEFLATED
    ni.external_attr = 0o644 << 16
    ni.create_system = 0
    zout.writestr(ni, data)
    n += 1
zout.close(); fp.close(); zin.close()
print("entries", n, "size", os.path.getsize(dst))
print("arsc", [(i.filename, i.compress_type) for i in zipfile.ZipFile(dst).infolist() if i.filename in ("resources.arsc", "classes2.dex")])
