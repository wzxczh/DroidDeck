# -*- coding: utf-8 -*-
"""Test-assertion sync audit.

For every string literal inside app/src/test assertion calls, search the main source
tree for it. Literals not found anywhere in app/src/main flag a possible stale
expectation (source was translated but the test kept English, or vice versa).
Report-only; protocol/technical literals usually pass the search anyway.
"""
import os
import re
import sys

ROOT = r"D:\DroidDeck"
TESTS = os.path.join(ROOT, "app", "src", "test")
MAIN = os.path.join(ROOT, "app", "src", "main")

ASSERT_PAT = re.compile(
    r"(?:assert\w+|fail|error)\s*\((?:[^;]|\n)*?", re.I)
STR_PAT = re.compile(r'"((?:[^"\\\n]|\\.)+)"')


def collect_main_text():
    chunks = []
    for dp, dn, fn in os.walk(MAIN):
        for f in fn:
            if f.endswith((".kt", ".java", ".xml")):
                try:
                    chunks.append(open(os.path.join(dp, f), encoding="utf-8").read())
                except Exception:
                    pass
    return "\n".join(chunks)


def main():
    big = collect_main_text()
    flags = []
    for dp, dn, fn in os.walk(TESTS):
        for f in fn:
            if not f.endswith((".kt", ".java")):
                continue
            p = os.path.join(dp, f)
            try:
                text = open(p, encoding="utf-8").read()
            except Exception:
                continue
            rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
            for i, line in enumerate(text.split("\n"), 1):
                if "assert" not in line and "fail(" not in line:
                    continue
                for m in STR_PAT.finditer(line):
                    s = m.group(1)
                    if len(s) < 8:
                        continue
                    if re.fullmatch(r"[\w./:@\- ]+", s) and "/" in s:
                        continue  # paths/ids
                    needle = s.replace('\\"', '"')
                    if needle in big:
                        continue
                    # only flag message-like literals: Chinese, or >=4 English words
                    has_zh = bool(re.search(r"[一-鿿]", needle))
                    words = re.findall(r"[A-Za-z]{3,}", needle)
                    msg_like = has_zh or len(words) >= 4
                    if not msg_like:
                        continue
                    flags.append((rel, i, needle[:90]))
    print("assertion literals not found in main sources:", len(flags))
    for x in flags:
        print("   ", x)
    return 0


if __name__ == "__main__":
    sys.exit(main())
