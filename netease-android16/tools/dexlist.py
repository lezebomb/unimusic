import sys, struct

def uleb128(b, i):
    r = 0; s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7f) << s
        if not (x & 0x80): break
        s += 7
    return r, i

def strings(b):
    size, off = struct.unpack_from('<II', b, 0x38)
    out = []
    for i in range(size):
        so, = struct.unpack_from('<I', b, off + 4 * i)
        n, j = uleb128(b, so)
        out.append(b[j:j+n].decode('utf-8', 'replace'))
    return out

def types(b):
    size, off = struct.unpack_from('<II', b, 0x40)
    ss = strings(b)
    return [ss[struct.unpack_from('<I', b, off + 4 * i)[0]] for i in range(size)]

def classes(b):
    size, off = struct.unpack_from('<II', b, 0x60)
    ts = types(b)
    res = []
    for i in range(size):
        cidx, = struct.unpack_from('<I', b, off + 32 * i)
        res.append(ts[cidx])
    return res

for p in sys.argv[1:]:
    b = open(p, 'rb').read()
    cl = classes(b)
    print(f'== {p}  ({len(b)} bytes, {len(cl)} classes)')
    for c in cl:
        print('  ', c)
