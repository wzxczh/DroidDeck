import struct, sys, os
sha = "0d98a9ae9ec97185f5518799aced119b46cdbe7a"
packdir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".git", "objects", "pack")
for name in sorted(os.listdir(packdir)):
    if not name.endswith(".idx"): continue
    data = open(os.path.join(packdir, name), "rb").read()
    print(name, "magic", data[:4], "ver", struct.unpack(">I", data[4:8])[0])
    fanout = struct.unpack(">256I", data[8:8+1024])
    n = fanout[255]
    print("  n =", n)
    names_off = 8 + 1024
    lo, hi = fanout[ord(sha[0])-1], fanout[ord(sha[0])]
    print("  range", lo, hi)
    found = False
    while lo < hi:
        mid = (lo+hi)//2
        s = data[names_off+mid*20:names_off+mid*20+20].hex()
        if s == sha:
            print("  FOUND at", mid); found = True; break
        if s < sha: lo = mid+1
        else: hi = mid
    if not found:
        # show neighbors
        mid = lo
        print("  not found; at lo:", data[names_off+mid*20:names_off+mid*20+20].hex())
