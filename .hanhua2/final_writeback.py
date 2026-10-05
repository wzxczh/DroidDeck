# -*- coding: utf-8 -*-
"""Narrow final write-back: copy ONLY coordinator-staged files into the workspace.

NEVER re-copy the whole resolved3 tree (that would wipe subagent translations).
Usage: python final_writeback.py            -> copies the default manifest list
"""
import os
import shutil
import sys

WORK = os.environ.get("HANHUA_WORK") or r"D:\DroidDeck\.hanhua2\work"
ROOT = r"D:\DroidDeck"

# coordinator-staged files awaiting write-back (built by translate_values/supplement_zh)
DEFAULT = [
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-zh-rCN/strings.xml",
]


def main(extra=()):
    todo = list(DEFAULT) + list(extra)
    for rel in todo:
        src = os.path.join(WORK, "stage", rel.replace("/", os.sep))
        dst = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.exists(src):
            print("MISSING SRC:", rel)
            continue
        shutil.copyfile(src, dst)
        print("copied:", rel)


if __name__ == "__main__":
    main(sys.argv[1:])
