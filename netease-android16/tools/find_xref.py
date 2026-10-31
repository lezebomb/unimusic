import struct, sys, re
from capstone import *
from capstone.arm64 import *

path = sys.argv[1]
data = open(path, 'rb').read()

# --- minimal ELF64 parse ---
assert data[:4] == b'\x7fELF'
e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
e_phnum = struct.unpack_from('<H', data, 0x38)[0]
segs = []
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    p_type, p_flags = struct.unpack_from('<II', data, off)
    p_offset, p_vaddr, p_paddr, p_filesz, p_memsz = struct.unpack_from('<QQQQQ', data, off + 8)
    segs.append((p_type, p_flags, p_offset, p_vaddr, p_filesz, p_memsz))
loads = [s for s in segs if s[0] == 1]

def off2va(o):
    for t, f, po, va, fs, ms in loads:
        if po <= o < po + fs:
            return va + (o - po)
    return None

def va2off(v):
    for t, f, po, va, fs, ms in loads:
        if va <= v < va + ms:
            return po + (v - va)
    return None

# exec segment
ex = [s for s in segs if s[0] == 1 and (s[1] & 1)]
print('exec segs', [(hex(s[3]), hex(s[4])) for s in ex])

# find strings
targets = {}
for pat in [b'APK Signature:%s', b'POISON', b'Friday', b'signatures']:
    idx = 0
    found = []
    while True:
        i = data.find(pat, idx)
        if i < 0:
            break
        found.append(i)
        idx = i + 1
    targets[pat] = [(i, off2va(i)) for i in found]
    print(pat, [(hex(o), (hex(off2va(o)) if off2va(o) else None)) for o in found][:8])

str_vas = set()
for pat, lst in targets.items():
    for o, v in lst:
        if v:
            str_vas.add(v)

md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = True

hits = []
for t, f, po, va, fs, ms in ex:
    code = data[po:po + fs]
    adrp = {}   # reg -> page va
    for insn in md.disasm(code, va):
        m = insn.mnemonic
        if m == 'adrp':
            try:
                rd = insn.reg_name(insn.operands[0].reg)
                imm = insn.operands[1].imm
                adrp[rd] = imm
            except Exception:
                pass
        elif m in ('add', 'ldr') and insn.operands and insn.operands[0].type == ARM64_OP_REG:
            try:
                base = insn.reg_name(insn.operands[1].reg) if insn.operands[1].type == ARM64_OP_REG else None
            except Exception:
                base = None
            if base and base in adrp:
                if insn.operands[1].shift.type == ARM64_SHIFT_NONE or True:
                    try:
                        disp = insn.operands[2].imm if len(insn.operands) > 2 else 0
                    except Exception:
                        disp = 0
                    ea = adrp[base] + disp
                    if ea in str_vas:
                        hits.append((insn.address, ea, insn.mnemonic + ' ' + insn.op_str))

print('hits', len(hits))
for a, ea, txt in hits[:40]:
    print(hex(a), '->', hex(ea), txt)
