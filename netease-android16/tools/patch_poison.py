import struct, sys

# usage: python patch_poison.py <src.so> <dst.so>
src, dst = sys.argv[1], sys.argv[2]
data = bytearray(open(src, 'rb').read())

e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
e_shentsize = struct.unpack_from('<H', data, 0x3A)[0]
e_shnum = struct.unpack_from('<H', data, 0x3C)[0]
e_shstrndx = struct.unpack_from('<H', data, 0x3E)[0]
order = []
for i in range(e_shnum):
    o = e_shoff + i * e_shentsize
    order.append(struct.unpack_from('<IIQQQQIIQQ', data, o))
strtab_off = order[e_shstrndx][4]
def nm(n):
    e = data.index(b'\x00', strtab_off + n)
    return data[strtab_off + n:e].decode('latin1')
secs = {nm(v[0]): dict(addr=v[3], off=v[4], size=v[5]) for v in order}

dsym, dstr = secs['.dynsym'], secs['.dynstr']
base = dstr['off']
# find offset of replacement name inside .dynstr
def str_off(name):
    start = base
    end = base + dstr['size']
    blob = bytes(data[start:end])
    idx = blob.find(name.encode('latin1') + b'\x00')
    if idx < 0:
        raise SystemExit('name not found in dynstr: ' + name)
    return idx

target = str_off('rand')
print('rand dynstr offset', target)

remap = {'pthread_mutex_lock', 'pthread_mutex_unlock', 'abort'}
hits = []
for i in range(dsym['size'] // 24):
    o = dsym['off'] + i * 24
    st_name = struct.unpack_from('<I', data, o)[0]
    e = data.index(b'\x00', base + st_name)
    name = bytes(data[base + st_name:e]).decode('latin1')
    if name in remap:
        struct.pack_into('<I', data, o, target)
        hits.append((i, name))
print('patched:', hits)
open(dst, 'wb').write(bytes(data))
print('wrote', dst, len(data))
