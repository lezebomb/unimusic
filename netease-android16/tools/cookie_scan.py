import re, sys, io

p = r'D:\unimusic\work\dump_creds\shared_prefs\cm_cookie_storage.xml'
t = open(p, 'rb').read().decode('utf-8', 'replace')

pat = re.compile(r'<string name="([^"]+)">(.*?)</string>', re.S)
keys = []
for m in pat.finditer(t):
    k, v = m.group(1), m.group(2)
    keys.append(k)
    if 'MUSIC_U' in k:
        print('KEY:', k)
        print('VAL:', v[:400])
print('---- total keys:', len(keys))
print('---- music_u keys:', [k for k in keys if 'MUSIC_U' in k])
