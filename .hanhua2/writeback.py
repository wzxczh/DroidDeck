"""Batch write-back: copy resolved3 over the workspace, delete rename/leftover files.

Run with elevated sandbox permission (one approval). Idempotent.
"""
import json
import os
import shutil
import sys

WORK = os.environ.get("HANHUA_WORK") or os.path.join(
    os.environ.get("TEMP", r"C:\Users\wzxcz\AppData\Local\Temp"), "hanhua2-work")
ROOT = r"D:\DroidDeck"
SKIP_DIRS = {".git", ".hanhua-orig", ".hanhua2", "node_modules"}


def main():
    src_root = os.path.join(WORK, "resolved3")
    copied = 0
    for dp, dn, fn in os.walk(src_root):
        for f in fn:
            sp = os.path.join(dp, f)
            rel = os.path.relpath(sp, src_root)
            dp_ = os.path.join(ROOT, rel)
            os.makedirs(os.path.dirname(dp_), exist_ok=True)
            shutil.copyfile(sp, dp_)
            copied += 1
    print("copied:", copied)

    inv = json.load(open(os.path.join(WORK, "conflicts.json"), encoding="utf-8"))
    deleted = []
    for p, info in inv.items():
        if info["stages"] == [1, 2]:
            target = os.path.join(ROOT, p.replace("/", os.sep))
            if os.path.exists(target):
                os.remove(target)
                deleted.append(p)
    print("deleted:", deleted)

    # post-check: no conflict markers anywhere in the repo (outside git/scratch)
    left = []
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in SKIP_DIRS]
        for f in fn:
            p = os.path.join(dp, f)
            try:
                with open(p, "rb") as fh:
                    head = fh.read(4_000_000)
            except OSError:
                continue
            if b"<<<<<<< " in head or b"\n>>>>>>> " in head:
                left.append(os.path.relpath(p, ROOT))
    print("files still containing markers:", left)
    return 1 if left else 0


if __name__ == "__main__":
    sys.exit(main())
