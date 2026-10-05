# -*- coding: utf-8 -*-
"""Line-level English check for Android resource XML: lists lines with >=3 English
words after stripping name="..." attributes, skipping lines that already contain Chinese."""
import re
import sys

STOP = set("""the and for with this that from when then into are was were not but can will its
their have has been all any one use used using via per you your which while also only over such
each than there these those what where who how why out get may see run runs file files line path
value from after before under between same other another some more most less least very just
because should would could does did done doing being will shall must can able if else then true
false null none one two first second next""".split())


def check(p):
    lines = open(p, encoding="utf-8").read().split("\n")
    bad = []
    for i, l in enumerate(lines, 1):
        if re.search(r"[一-鿿]", l):
            continue
        body = re.sub(r'name="[^"]*"|tools:[^=]*="[^"]*"', "", l)
        w = [x for x in re.findall(r"[A-Za-z]{3,}", body) if x.lower() not in STOP]
        if len(w) >= 3:
            bad.append((i, l.strip()[:120]))
    print(f"{p}: {len(bad)} suspicious lines")
    for i, l in bad[:25]:
        print(f"  {i}: {l}")


if __name__ == "__main__":
    for path in sys.argv[1:]:
        check(path)
