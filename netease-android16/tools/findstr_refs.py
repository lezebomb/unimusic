import struct, sys
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

p = sys.argv[1] if len(sys.argv) > 1 else r'D:\unimusic\work\libs492\libpoison.so'
data = open(p, 'rb').read()

# ELF headers
e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
e_phnum = struct.unpack_from('<H', data, 0x38)[0]
loads = []
for i in range(e_phnum):
    o = e_phoff + i * e_phentsize
    p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from('<IIQQQQQQ', data, o)
    loads.append((p_type, p_flags, p_offset, p_vaddr, p_filesz, p_memsz))
for l in loads:
    print('PH type=%d flags=%x off=%x vaddr=%x filesz=%x memsz=%x' % l)

# section headers
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

targets = {}
for s in [b'APK Signature:%s', b'POISON', b'Friday']:
    idx = data.find(s)
    while idx != -1:
        targets.setdefault(s.decode(), []).append(idx)
        idx = data.find(s, idx + 1)
print('string file offsets:', targets)

# for each string, compute vaddr via mapping load->off
def off2va(off):
    for p_type, p_flags, p_offset, p_vaddr, p_filesz, p_memsz in loads:
        if p_type == 1 and p_offset <= off < p_offset + p_filesz:
            return p_vaddr + (off - p_offset)
    return None

for name, offs in targets.items():
    for off in offs:
        va = off2va(off)
        print('%s file=%x va=%x' % (name, off, va or 0))
        if va:
            pat = struct.pack('<Q', va)
            hits = []
            i = data.find(pat)
            while i != -1:
                hits.append(i)
                i = data.find(pat, i + 1)
            print('   ptr8 hits at file offsets:', [hex(h) for h in hits[:10]])
            pat4 = struct.pack('<I', va)
            hits4 = []
            i = data.find(pat4)
            while i != -1:
                hits4.append(i)
                i = data.find(pat4, i + 1)
            print('   ptr4 hits at file offsets:', [hex(h) for h in hits4[:10]])
