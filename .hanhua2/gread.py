"""Minimal read-only git object reader (no git.exe).

Supports: loose objects, packfiles (idx v2, OFS/REF deltas), tree/commit parsing,
index (v2/v3/v4-ish) stage extraction, merge-base walk.
"""
import hashlib
import os
import struct
import sys
import zlib
from collections import deque

GIT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".git")
GIT = os.path.normpath(GIT)
OBJ = os.path.join(GIT, "objects")


def sha1_hex(b: bytes) -> str:
    return hashlib.sha1(b).hexdigest()


class ObjectStore:
    def __init__(self):
        self.loose_cache = {}
        self.packs = []          # list of (idx_path, pack_path, idx_data)
        self.pack_cache = {}
        self._load_packs()

    def _load_packs(self):
        packdir = os.path.join(OBJ, "pack")
        if not os.path.isdir(packdir):
            return
        for name in sorted(os.listdir(packdir)):
            if name.endswith(".idx"):
                idx = os.path.join(packdir, name)
                pack = idx[:-4] + ".pack"
                if os.path.exists(pack):
                    with open(idx, "rb") as f:
                        data = f.read()
                    self.packs.append((idx, pack, data))

    # ---------- object access ----------
    def get(self, sha: str) -> tuple:
        """returns (type, data)"""
        sha = sha.lower()
        if sha in self.loose_cache:
            return self.loose_cache[sha]
        p = os.path.join(OBJ, sha[:2], sha[2:])
        if os.path.exists(p):
            with open(p, "rb") as f:
                raw = zlib.decompress(f.read())
            hdr, _, body = raw.partition(b"\x00")
            typ = hdr.split(b" ")[0].decode()
            res = (typ, body)
            self.loose_cache[sha] = res
            return res
        # packed
        r = self._get_packed(sha)
        if r is None:
            raise KeyError("object not found: " + sha)
        self.loose_cache[sha] = r
        return r

    def _idx_lookup(self, idx_data, sha: str):
        # idx v2
        if idx_data[:4] != b"\xfftOc":
            raise RuntimeError("idx v1 not supported")
        ver = struct.unpack(">I", idx_data[4:8])[0]
        if ver != 2:
            raise RuntimeError("idx version %d not supported" % ver)
        fanout = struct.unpack(">256I", idx_data[8:8 + 1024])
        n = fanout[255]
        names_off = 8 + 1024
        b0 = int(sha[:2], 16)
        lo, hi = fanout[b0 - 1] if b0 else 0, fanout[b0]
        # binary search
        while lo < hi:
            mid = (lo + hi) // 2
            s = idx_data[names_off + mid * 20: names_off + mid * 20 + 20].hex()
            if s == sha:
                break
            if s < sha:
                lo = mid + 1
            else:
                hi = mid
        else:
            return None
        mid = (lo + hi) // 2
        s = idx_data[names_off + mid * 20: names_off + mid * 20 + 20].hex()
        if s != sha:
            return None
        crc_off = names_off + n * 20
        off_off = crc_off + n * 4
        word = struct.unpack(">I", idx_data[off_off + mid * 4: off_off + mid * 4 + 4])[0]
        if word & 0x80000000:
            big_off = off_off + n * 4 + (word & 0x7FFFFFFF) * 8
            return struct.unpack(">Q", idx_data[big_off: big_off + 8])[0]
        return word

    def _get_packed(self, sha: str):
        for idx_path, pack_path, idx_data in self.packs:
            off = self._idx_lookup(idx_data, sha)
            if off is None:
                continue
            return self._read_pack_obj(pack_path, off)
        return None

    def _read_pack_obj(self, pack_path, offset, depth=0):
        key = (pack_path, offset)
        if key in self.pack_cache:
            return self.pack_cache[key]
        with open(pack_path, "rb") as f:
            f.seek(offset)
            b = f.read(1)
            c = b[0]
            typ = (c >> 4) & 7
            size = c & 15
            shift = 4
            while c & 0x80:
                b = f.read(1)
                c = b[0]
                size |= (c & 0x7F) << shift
                shift += 7
            if typ in (1, 2, 3, 4):
                names = {1: "commit", 2: "tree", 3: "blob", 4: "tag"}
                dec = zlib.decompressobj()
                data = dec.decompress(f.read(size + 512))
                res = (names[typ], data)
            elif typ == 6:  # ofs delta
                b = f.read(1)
                c = b[0]
                base_rel = c & 0x7F
                while c & 0x80:
                    b = f.read(1)
                    c = b[0]
                    base_rel = ((base_rel + 1) << 7) | (c & 0x7F)
                base_type, base_data = self._read_pack_obj(pack_path, offset - base_rel, depth + 1)
                dec = zlib.decompressobj()
                delta = dec.decompress(f.read(size + 512))
                data = apply_delta(base_data, delta)
                res = (base_type, data)
            elif typ == 7:  # ref delta
                base_sha = f.read(20).hex()
                base_type, base_data = self.get(base_sha)
                dec = zlib.decompressobj()
                delta = dec.decompress(f.read(size + 512))
                data = apply_delta(base_data, delta)
                res = (base_type, data)
            else:
                raise RuntimeError("bad pack object type %d" % typ)
        self.pack_cache[key] = res
        return res


def apply_delta(base: bytes, delta: bytes) -> bytes:
    i = 0

    def varint():
        nonlocal i
        r = 0
        shift = 0
        while True:
            c = delta[i]
            i += 1
            r |= (c & 0x7F) << shift
            shift += 7
            if not (c & 0x80):
                break
        return r

    varint()  # base size
    out_size = varint()
    out = bytearray()
    while i < len(delta):
        c = delta[i]
        i += 1
        if c & 0x80:
            off = 0
            for j in range(4):
                if c & (1 << j):
                    off |= delta[i] << (j * 8)
                    i += 1
            sz = 0
            for j in range(3):
                if c & (1 << (4 + j)):
                    sz |= delta[i] << (j * 8)
                    i += 1
            if sz == 0:
                sz = 0x10000
            out += base[off:off + sz]
        else:
            ln = c & 0x7F
            if ln == 0:
                raise RuntimeError("bad delta insert 0")
            out += delta[i:i + ln]
            i += ln
    if len(out) != out_size:
        raise RuntimeError("delta size mismatch")
    return bytes(out)


# ---------- commit / tree ----------
def parse_commit(data: bytes):
    text = data.decode("utf-8", "replace")
    tree = None
    parents = []
    for line in text.split("\n"):
        if line.startswith("tree "):
            tree = line[5:].strip()
        elif line.startswith("parent "):
            parents.append(line[7:].strip())
        elif line == "":
            break
    return tree, parents


def parse_tree(data: bytes):
    """returns list of (mode, name, sha)"""
    out = []
    i = 0
    while i < len(data):
        sp = data.index(b" ", i)
        mode = data[i:sp].decode()
        nul = data.index(b"\x00", sp)
        name = data[sp + 1:nul].decode("utf-8", "surrogateescape")
        sha = data[nul + 1:nul + 21].hex()
        out.append((mode, name, sha))
        i = nul + 21
    return out


def walk_tree(store, tree_sha, prefix=""):
    """yields (relpath, mode, sha) for all blobs"""
    for mode, name, sha in parse_tree(store.get(tree_sha)[1]):
        p = prefix + name
        if mode == "40000":
            yield from walk_tree(store, sha, p + "/")
        else:
            yield (p, mode, sha)


# ---------- index ----------
def parse_index(path):
    """returns list of dicts: path, stage, sha, mode"""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"DIRC":
        raise RuntimeError("bad index")
    ver, n = struct.unpack(">II", data[4:12])
    if ver not in (2, 3, 4):
        raise RuntimeError("index version %d" % ver)
    entries = []
    off = 12
    for _ in range(n):
        start = off
        ctime, ctimen, mtime, mtimen, dev, ino, mode, uid, gid, size = struct.unpack(
            ">10I", data[off:off + 40])
        off += 40
        sha = data[off:off + 20].hex()
        off += 20
        flags = struct.unpack(">H", data[off:off + 2])[0]
        off += 2
        stage = (flags >> 12) & 7
        if flags & 0x4000:  # extended
            off += 2
        end = data.index(b"\x00", off)
        name = data[off:end].decode("utf-8", "surrogateescape")
        off = end + 1
        # pad to multiple of 8 from entry start
        while (off - start) % 8:
            off += 1
        entries.append({"path": name, "stage": stage, "sha": sha, "mode": mode})
        if ver >= 4:
            raise RuntimeError("index v4 varint paths not supported")
    return entries


def merge_base(store, sha_a, sha_b):
    def ancestors(sha):
        seen = set()
        q = deque([sha])
        while q:
            c = q.popleft()
            if c in seen:
                continue
            seen.add(c)
            _, parents = parse_commit(store.get(c)[1])
            q.extend(parents)
        return seen

    a_set = ancestors(sha_a)
    q = deque([sha_b])
    seen = set()
    while q:
        c = q.popleft()
        if c in seen:
            continue
        seen.add(c)
        if c in a_set:
            return c
        _, parents = parse_commit(store.get(c)[1])
        q.extend(parents)
    return None


if __name__ == "__main__":
    store = ObjectStore()
    print("packs:", len(store.packs))
