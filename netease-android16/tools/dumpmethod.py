import sys

path = r'D:\unimusic\work\mod_dump8.txt'
lines = open(path, encoding='utf-8', errors='replace').read().split('\n')

clsline = sys.argv[1]          # e.g. Lcom/raincat/dolby_beta/O000OO0O0O00OO0OOO0;
meth = sys.argv[2] if len(sys.argv) > 2 else None

start = None
for i, l in enumerate(lines):
    if 'Class descriptor' in l and clsline in l:
        start = i
        break
if start is None:
    print('class not found')
    sys.exit(1)

end = len(lines)
for i in range(start + 1, len(lines)):
    if lines[i].startswith('Class #'):
        end = i
        break

print('class span', start, end)
if meth is None:
    # list method names
    for i in range(start, end):
        if "name          : '" in lines[i]:
            print(i, lines[i].strip())
    sys.exit(0)

target = "name          : '" + meth + "'"
hits = [i for i in range(start, end) if target in lines[i]]
if not hits:
    print('method not found; methods:')
    for i in range(start, end):
        if "name          : '" in lines[i]:
            print(' ', lines[i].strip())
    sys.exit(1)

i = hits[0]
# print header (name/type/access) then code until next method header
out = []
j = i
while j < end:
    line = lines[j]
    if j > i + 6 and "name          : '" in line:
        break
    out.append(line)
    j += 1
print('\n'.join(out))
