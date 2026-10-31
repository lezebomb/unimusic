import struct, sys

p = sys.argv[1] if len(sys.argv) > 1 else r'D:\unimusic\work\libs492\libpoison.so'
data = open(p, 'rb').read()
e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
e_shentsize = struct.unpack_from('<H', data, 0x3A)[0]
e_shnum = struct.unpack_from('<H', data, 0x3C)[0]
e_shstrndx = struct.unpack_from('<H', data, 0x3E)[0]
order = []
for i in range(e_shnum):
    o = e_shoff + i * e_shentsize
    name, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from('<IIQQQQIIQQ', data, o)
    order.append((name, typ, addr, off, size, link, entsize))
strtab_off = order[e_shstrndx][3]
def nm(n):
    e = data.index(b'\0', strtab_off + n)
    return data[strtab_off + n:e].decode('latin1')
secs = {nm(n): dict(typ=t, addr=a, off=o, size=s, link=l, entsize=es) for n, t, a, o, s, l, es in order}
print('sections:', [k for k in secs][:40])
d = secs['.dynsym']; s = secs['.dynstr']
base = s['off']
syms = []
for i in range(d['size'] // 24):
    o = d['off'] + i * 24
    st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from('<IBBHQQ', data, o)
    e = data.index(b'\0', base + st_name)
    syms.append((i, data[base + st_name:e].decode('latin1'), st_value, st_shndx, st_name))
print('dynsym count', len(syms))
for i, n, v, sh, noff in syms:
    if any(k in n for k in ('pthread', 'mutex', 'abort', 'fortify', 'raise', 'kill')):
        print('SYM idx=%d nameoff=%d value=%x shndx=%d %s' % (i, base + noff, v, sh, n))
