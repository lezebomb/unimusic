import zipfile, zlib, struct, os, sys

src = sys.argv[1]
dst = sys.argv[2]
OLD = b"com.hihonor.cloudmusic"
NEW = b"com.hihonor.cloudmusix"
assert len(OLD) == len(NEW)

def transform(name, data):
    if name == "AndroidManifest.xml":
        c1 = data.count(OLD)
        c2 = data.count(OLD.decode().encode("utf-16-le"))
        data = data.replace(OLD, NEW).replace(OLD.decode().encode("utf-16-le"), NEW.decode().encode("utf-16-le"))
        print(f"manifest: utf8 hits={c1} utf16 hits={c2}")
    return data

zin = zipfile.ZipFile(src)
fp = open(src, "rb")
zout = zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, allowZip64=True)
for zi in zin.infolist():
    fp.seek(zi.header_offset)
    lh = fp.read(30)
    sig, ver, flag, method, mt, md, crc, csize, usize, nlen, elen = struct.unpack("<IHHHHHIIIHH", lh)
    assert sig == 0x04034b50, hex(sig)
    fp.seek(zi.header_offset + 30 + nlen + elen)
    raw = fp.read(zi.compress_size)
    data = raw if zi.compress_type == zipfile.ZIP_STORED else zlib.decompress(raw, -15)
    data = transform(zi.filename, data)
    ni = zipfile.ZipInfo(zi.filename, date_time=zi.date_time)
    ni.compress_type = zi.compress_type
    ni.external_attr = zi.external_attr
    ni.internal_attr = zi.internal_attr
    ni.create_system = zi.create_system
    zout.writestr(ni, data)
zout.close(); fp.close(); zin.close()
print("out", dst, os.path.getsize(dst))
print("arsc:", [(i.filename, i.compress_type) for i in zipfile.ZipFile(dst).infolist() if i.filename == "resources.arsc"])
