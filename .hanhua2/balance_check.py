# -*- coding: utf-8 -*-
"""Quote/brace balance check: compare LIVE file balance against THEIRS (known-good).

A file passes when live and theirs agree on bracket balance. Absolute balance alone
is unreliable for templates/regex, so agreement is the criterion.
Report-only."""

import json
import os
import re
import sys

WORK = os.environ.get("HANHUA_WORK") or r"C:\Users\wzxcz\AppData\Local\Temp\dsh-StYPwy\hanhua2-work"
ROOT = r"D:\DroidDeck"
THEIRS = os.path.join(WORK, "theirs")
OURS = os.path.join(WORK, "ours")
SKIP = {".git", ".hanhua-orig", ".hanhua2", "build", ".gradle", ".idea", ".cxx", ".kotlin"}
CODE = {".kt", ".java", ".c", ".cpp", ".h", ".hpp", ".py", ".sh", ".gradle", ".xml", ".yml"}


def strip_strings_comments(text, ext):
    # blank string literals and comments so bracket counting only sees code
    if ext in (".py", ".sh"):
        text = re.sub(r"#[^\n]*", "", text)
    else:
        text = re.sub(r"//[^\n]*", "", text)
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    if ext in (".xml", ".yml", ".yaml", ".gradle", ".sh", ".py"):
        return text
    text = re.sub(r'"""(?:[^"\\]|\\.)*?"""', '""', text, flags=re.S)
    text = re.sub(r"'''(?:[^'\\]|\\.)*?'''", "''", text, flags=re.S)
    text = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', text)
    text = re.sub(r"'(?:[^'\\\n]|\\.)*'", "''", text)
    return text


def balance(text, ext):
    t = strip_strings_comments(text, ext)
    res = {}
    for a, b in (("{", "}"), ("(", ")"), ("[", "]")):
        res[a + b] = t.count(a) - t.count(b)
    return res


def read(p):
    try:
        return open(p, encoding="utf-8").read()
    except (UnicodeDecodeError, OSError):
        return None


def main():
    problems = []
    checked = 0
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in SKIP]
        for f in fn:
            ext = os.path.splitext(f)[1]
            if ext not in CODE:
                continue
            rel = os.path.relpath(os.path.join(dp, f), ROOT).replace(os.sep, "/")
            live = read(os.path.join(dp, f))
            if live is None:
                continue
            ref = None
            for tree in (THEIRS, OURS):
                rp = os.path.join(tree, rel.replace("/", os.sep))
                if os.path.exists(rp):
                    ref = read(rp)
                    if ref is not None:
                        break
            if ref is None:
                continue
            checked += 1
            bl, br = balance(live, ext), balance(ref, ext)
            if bl != br:
                problems.append((rel, bl, br))
    print("checked:", checked, "balance-mismatch vs upstream:", len(problems))
    for p in problems:
        print("   ", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
