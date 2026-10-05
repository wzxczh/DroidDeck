# -*- coding: utf-8 -*-
"""Supplement values-zh-rCN/strings.xml with keys that exist only in default values
(the 92 upstream never localized). Output: WORK/resolved3/values-zh-rCN/strings.xml
→ picked up by final_writeback.py (added to its list)."""
import json
import os
import re
import xml.etree.ElementTree as ET

WORK = os.environ.get("HANHUA_WORK") or r"D:\DroidDeck\.hanhua2\work"
DV = os.path.join(WORK, "stage", "app/src/main/res/values/strings.xml")
ZH = r"D:\DroidDeck\app\src\main\res\values-zh-rCN\strings.xml"
ZH_OUT = os.path.join(WORK, "stage", "app/src/main/res/values-zh-rCN/strings.xml")


def entries(xml_text):
    root = ET.fromstring(xml_text)
    out = {}
    for el in root:
        n = el.get("name")
        if n:
            out[el.tag + "/" + n] = ET.tostring(el, encoding="unicode").strip()
    return out


def main():
    dv = open(DV, encoding="utf-8").read()
    zh = open(ZH, encoding="utf-8").read()
    dve, zhe = entries(dv), entries(zh)
    missing = [k for k in dve if k not in zhe]
    print("missing in zh-rCN:", len(missing))
    body = zh.split("?>", 1)[1] if zh.startswith("<?xml") else zh
    body = body.rstrip()
    assert body.endswith("</resources>"), "unexpected zh ending"
    add = ["    " + dve[k] for k in sorted(missing)]
    new = body[: -len("</resources>")] + "\n".join(add) + "\n</resources>\n"
    ET.fromstring(new)  # validate
    os.makedirs(os.path.dirname(ZH_OUT), exist_ok=True)
    open(ZH_OUT, "w", encoding="utf-8", newline="").write(new)
    print("added:", len(missing))
    for k in sorted(missing):
        print("   ", k)


if __name__ == "__main__":
    main()
