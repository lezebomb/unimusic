import struct

p = r'D:\unimusic\work\libs492\libpoison.so'
data = open(p, 'rb').read()

TARGETS = {0x7e7ef: 'POISON', 0x7e7f6: 'APK-Sig', 0x86010: 'Friday', 0x7e791: 'signatures'}

e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
e_shentsize = struct.unpack_from('<H', data, 0x3A)[0]
e_shnum = struct.unpack_from('<H', data, 0x3C)[0]
e_shstrndx = struct.unpack_from('<H', data, 0x3E)[0]
shs = []
for i in range(e_shnum):
    o = e_shoff + i * e_shentsize
    shs.append(struct.unpack_from('<IIQQQQIIQQ', data, o))
strtab_off = shs[e_shstrndx][4]
def shname(n):
    e = data.index(b'\0', strtab_off + n)
    return data[strtab_off + n:e].decode('latin1')
secs = {}
for s in shs:
    name, typ, flags, addr, off, size, link, info, align, entsize = s
    secs[shname(name)] = dict(typ=typ, addr=addr, off=off, size=size, entsize=entsize)

for rname in ('.rela.dyn', '.rela.plt'):
    r = secs.get(rname)
    if not r:
        continue
    n = r['size'] // 24
    for i in range(n):
        o = r['off'] + i * 24
        r_offset, r_info, r_addend = struct.unpack_from('<QQq', data, o)
        if r_addend in TARGETS or r_offset in TARGETS:
            print('%s entry %d r_offset=%x type=%d addend=%x  => %s' % (
                rname, i, r_offset, r_info & 0xffffffff, r_addend & 0xffffffffffffffff,
                TARGETS.get(r_addend) or TARGETS.get(r_offset)))
