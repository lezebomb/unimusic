import struct, sys
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_REG, ARM64_INS_ADRP, ARM64_INS_ADD, ARM64_INS_LDR

p = r'D:\unimusic\work\libs492\libpoison.so'
data = open(p, 'rb').read()

TARGETS = {0x7e7ef: 'POISON', 0x7e7f6: 'APK Signature', 0x86010: 'Friday', 0x7e791: 'signatures'}

md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = True

text = data[0:0xa6704]
hits = []
prev_adrp = None  # (addr, reg, page)
for ins in md.disasm(text, 0):
    if ins.id == ARM64_INS_ADRP:
        page = ins.operands[1].imm
        prev_adrp = (ins.address, ins.operands[0].reg, page)
        continue
    if prev_adrp and ins.id in (ARM64_INS_ADD, ARM64_INS_LDR):
        # find add xN, xN(same reg), #imm  or ldr from [xN, #imm]
        base_ok = False
        for op in ins.operands:
            if op.type == ARM64_OP_REG and op.reg == prev_adrp[1]:
                base_ok = True
        if base_ok:
            for op in ins.operands:
                if op.type == ARM64_OP_IMM:
                    va = prev_adrp[2] + op.imm
                    if va in TARGETS:
                        hits.append((ins.address, TARGETS[va], hex(va), ins.mnemonic + ' ' + ins.op_str))
        prev_adrp = None
    else:
        if ins.id != ARM64_INS_ADRP:
            prev_adrp = None

for h in hits:
    print('XREF instr=%x target=%s va=%s : %s' % h)
print('total', len(hits))
