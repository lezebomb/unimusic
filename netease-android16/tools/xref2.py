import struct, sys
from capstone import *
from capstone.arm64 import *

path = sys.argv[1]
tgts = [int(x, 16) for x in sys.argv[2:]]
data = open(path, 'rb').read()
e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
e_phnum = struct.unpack_from('<H', data, 0x38)[0]
segs = []
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    p_type, p_flags = struct.unpack_from('<II', data, off)
    p_offset, p_vaddr, p_paddr, p_filesz, p_memsz = struct.unpack_from('<QQQQQ', data, off + 8)
    segs.append((p_type, p_flags, p_offset, p_vaddr, p_filesz, p_memsz))
ex = [s for s in segs if s[0] == 1 and (s[1] & 1)]
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
found = []
for t, f, po, va, fs, ms in ex:
    code = data[po:po + fs]
    adrp = {}
    for insn in md.disasm(code, va):
        ops = insn.operands
        if insn.mnemonic == 'adrp' and len(ops) == 2 and ops[0].type == ARM64_OP_REG:
            adrp[insn.reg_name(ops[0].reg)] = ops[1].imm
            continue
        if insn.mnemonic == 'adr' and len(ops) == 2:
            if ops[1].imm in tgts:
                found.append((insn.address, ops[1].imm, insn.mnemonic + ' ' + insn.op_str))
            continue
        val = None
        if insn.mnemonic in ('add', 'sub') and len(ops) == 3 and ops[1].type == ARM64_OP_REG:
            b = insn.reg_name(ops[1].reg)
            if b in adrp and ops[2].type == ARM64_OP_IMM:
                v = adrp[b] + ops[2].imm
                val = v if insn.mnemonic == 'add' else adrp[b] - ops[2].imm
        elif insn.mnemonic in ('ldr', 'ldrb', 'ldrh', 'str', 'adrp_like') and len(ops) == 2 and ops[1].type == ARM64_OP_MEM:
            mem = ops[1].mem
            base = insn.reg_name(mem.base) if mem.base != 0 else None
            if base in adrp:
                val = adrp[base] + mem.disp
        if val in tgts:
            found.append((insn.address, val, insn.mnemonic + ' ' + insn.op_str))
print('matches', len(found))
for a, v, txt in found[:60]:
    print(hex(a), '->', hex(v), txt)
