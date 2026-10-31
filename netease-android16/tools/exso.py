import zipfile, re, os
z = zipfile.ZipFile(r"D:\unimusic\work\origin.apk")
names = [n for n in z.namelist() if n.endswith(".so") and "poison" in n.lower()]
print(names)
os.makedirs(r"D:\unimusic\work\libs", exist_ok=True)
for n in names:
    data = z.read(n)
    out = os.path.join(r"D:\unimusic\work\libs", os.path.basename(n))
    open(out, "wb").write(data)
    print(out, len(data))
