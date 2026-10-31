import zipfile, re
z = zipfile.ZipFile(r'D:\unimusic\work\unm.zip')
s = z.read('precompiled/app.js').decode('utf8', 'ignore')
pats = [r'pathname\s*===?\s*["\'][^"\']+["\']', r'/\^\\/([a-z]+)', r'"/[a-z]{3,10}"']
for p in pats:
    for m in re.finditer(p, s):
        print(p[:12], '|', m.group(0)[:100])
