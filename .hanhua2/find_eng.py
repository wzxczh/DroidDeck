# -*- coding: utf-8 -*-
"""List message-like English string literals in a code file (coordinator direct-work tool).

usage: python find_eng.py <file> [--all]   [--all shows short strings too]
Lines already containing Chinese are skipped (considered translated).
Keeps protocol-looking strings (paths/urls/ids) out via heuristics but prints everything
that looks like prose: >=4 real English words (or >=2 with --all).
"""
import re
import sys

STR = re.compile(r'"(?:[^"\\\n]|\\.)*"')
STOP = set("""the and for with this that from when then into are was were not but can will
its their have has been all any one use used using via per you your which while also only
over such each than there these those what where who how why out get may see run runs file
files line path value from into after before under between both same other another some more
most less least very just because should would could does did done doing being will shall
must can able if else then true false null none this that these those with from per via
one two first second next only also only over such each""".split())


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    allmode = "--all" in sys.argv
    minw = 2 if allmode else 4
    p = args[0]
    lines = open(p, encoding="utf-8").read().split("\n")
    hits = 0
    for i, l in enumerate(lines, 1):
        if re.search(r"[一-鿿]", l):
            continue
        for s in STR.findall(l):
            body = s[1:-1]
            body2 = re.sub(r"\$\{[^}]*\}|%[1-9]\$?[a-z]|https?://\S+|/[\w./-]+", " ", body)
            w = [x for x in re.findall(r"[A-Za-z]{3,}", body2) if x.lower() not in STOP]
            if len(w) >= minw:
                print(f"{i}: {s[:130]}")
                hits += 1
                break
    print(f"-- {p}: {hits} candidate lines")


if __name__ == "__main__":
    main()
