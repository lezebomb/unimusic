import binascii, hashlib, json
cfg = json.load(open(r"D:\unimusic\work\x\assets_lspatch_config.json", encoding="utf-8"))
der = binascii.unhexlify(cfg["originalSignature"])
print("der len", len(der))
print("md5(orig cert der) =", hashlib.md5(der).hexdigest())
print("target             = 20081729e603e0b1089d9335a22748c4")
