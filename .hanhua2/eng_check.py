# -*- coding: utf-8 -*-
"""Report English prose lines remaining in the given files (docs check).

usage: python eng_check.py <file> [<file> ...]
"""
import re
import sys

STOP = set("""the and for with this that from when then into are was were not but can will
its their have has been all any one use used using via per you your which while also only
over such each than there these those what where who how why out get may see run runs file
files line path value values see may like one two first second next after before above below
under between both same other another some more most less least very just because should
would could does did done doing being been will shall might must can able if else then true
false null none fig table page section chapter example examples note notes warning""".split())


def eng_lines(text, min_words=4):
    out = []
    fence = False
    for i, raw in enumerate(text.split("\n"), 1):
        s = raw.strip()
        if s.startswith("```"):
            fence = not fence
            continue
        if fence:
            continue
        if not s:
            continue
        if re.search(r"[一-鿿]", s):
            continue   # line already has Chinese -> not an untranslated line
        body = re.sub(r"`[^`]*`", " ", s)
        body = re.sub(r"https?://\S+", " ", body)
        body = re.sub(r"!?\[[^\]]*\]\([^)]*\)", " ", body)   # markdown links/images
        words = re.findall(r"[A-Za-z][A-Za-z']+", body)
        real = [w for w in words if w.lower() not in STOP]
        if len(real) < min_words:
            continue
        # line must look like prose (allow leading markup: # > - * | digits.)
        if not re.match(r"^[#>\-\*\s\d\.)|]+", s) and not re.match(r"^[\w\s.,;:!?()/'\"%&#*=\-><\[\]{}+@\\|]+$", s):
            continue
        out.append((i, s[:100]))
    return out


def main():
    total = 0
    for p in sys.argv[1:]:
        try:
            t = open(p, encoding="utf-8").read()
        except Exception as e:
            print(p, "ERR", e)
            continue
        hits = eng_lines(t)
        total += len(hits)
        print(f"{p}: {len(hits)} english prose lines")
        for i, s in hits[:6]:
            print(f"    {i}: {s}")
    print("TOTAL:", total)


if __name__ == "__main__":
    main()
