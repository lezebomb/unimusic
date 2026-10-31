import os, struct, sys
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

D = r"D:\unimusic\work\libs492"

def load_elf(path):
    data = open(path, "rb").read()
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", data, 0x3E)[0]
    secs = {}
    order = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        name, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from("<IIQQQQIIQQ", data, o)
        order.append((name, typ, addr, off, size, link, entsize))
    strtab_off = order[e_shstrndx][3]
    def nm(n):
        e = data.index(b"\0", strtab_off + n)
        return data[strtab_off + n:e].decode("latin1")
    out = {}
    for name, typ, addr, off, size, link, entsize in order:
        out[nm(name)] = dict(typ=typ, addr=addr, off=off, size=size, link=link, entsize=entsize)
    return data, out

def dynsyms(data, secs):
    d = secs.get(".dynsym"); s = secs.get(".dynstr")
    if not d or not s:
        return []
    base = s["off"]
    res = []
    n = d["size"] // 24
    for i in range(n):
        o = d["off"] + i * 24
        st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", data, o)
        e = data.index(b"\0", base + st_name)
        res.append((data[base + st_name:e].decode("latin1"), st_value, st_shndx, st_info))
    return res

def plt_map(data, secs):
    """map plt stub addr -> imported symbol name (lld layout, rela.plt order)"""
    plt = secs.get(".plt")
    rela = secs.get(".rela.plt")
    syms = dynsyms(data, secs)
    if not plt or not rela or not syms:
        return {}
    entsize = plt["entsize"] or 16
    n = rela["size"] // 24
    m = {}
    for i in range(n):
        o = rela["off"] + i * 24
        r_offset, r_info, r_addend = struct.unpack_from("<QQq", data, o)
        symidx = r_info >> 32
        if symidx >= len(syms):
            continue
        name = syms[symidx][0]
        m[plt["addr"] + (i + 1) * entsize] = name
    return m

def find_gadget(path):
    r = load_elf(path)
    if not r:
        return []
    data, secs = r
    pm = plt_map(data, secs)
    if not pm:
        return []
    text = secs.get(".text")
    if not text:
        return []
    code = data[text["off"]: text["off"] + text["size"]]
    base = text["addr"]
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = False
    insns = list(md.disasm(code, base))
    hits = []
    for i, ins in enumerate(insns):
        if ins.mnemonic != "bl":
            continue
        try:
            tgt = int(ins.op_str, 16)
        except ValueError:
            continue
        sym = pm.get(tgt)
        if sym != "pthread_mutex_destroy":
            continue
        window = insns[i + 1: i + 21]
        chain = ["pthread_mutex_destroy"]
        for j in window:
            if j.mnemonic in ("bl", "b"):
                try:
                    t2 = int(j.op_str, 16)
                except ValueError:
                    continue
                s2 = pm.get(t2)
                if s2 in ("pthread_mutex_lock", "pthread_mutex_timedlock", "abort", "__assert2", "__stack_chk_fail"):
                    chain.append(s2)
                elif s2:
                    chain.append(s2)
        interesting = [c for c in chain[1:] if c in ("pthread_mutex_lock", "pthread_mutex_timedlock", "abort", "__assert2")]
        if interesting:
            hits.append((ins.address, [c for c in chain if c not in ("pthread_mutex_destroy",)][:6]))
    return hits

tot = 0
for f in sorted(os.listdir(D)):
    if not f.endswith(".so"):
        continue
    try:
        h = find_gadget(os.path.join(D, f))
    except Exception as e:
        print(f"{f}: ERR {e}")
        continue
    if h:
        tot += len(h)
        print(f"=== {f}: {len(h)} sites")
        for a, ch in h[:6]:
            print(f"   0x{a:x} -> {ch}")
print("total sites", tot)
