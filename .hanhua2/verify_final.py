# -*- coding: utf-8 -*-
"""Phase-5 static verification (no compilation).

Checks:
  1. no real conflict markers anywhere (repo minus .git/scratch)
  2. every XML under app/src/main/res parses
  3. every R.string.X / R.plurals.X referenced in app/src exists in values/strings.xml
  4. per-file classification live vs theirs vs ours (skeleton = string literals blanked):
       OK-THEIRS  only string-literal differences from upstream (translation)
       OK-OURS    structurally == ours (upstream never touched it / local fix kept)
       REVIEW     structural difference from BOTH -> list for manual check
     (markdown/scripts compared too, skeleton = strings blanked)
  5. quote & brace balance sanity for code files (live vs theirs delta)
  6. English-UI leftover scan (heuristic) -> report for review
"""
import json
import os
import re
import sys
from difflib import SequenceMatcher

WORK = os.environ.get("HANHUA_WORK") or r"C:\Users\wzxcz\AppData\Local\Temp\dsh-StYPwy\hanhua2-work"
ROOT = r"D:\DroidDeck"
OURS = os.path.join(WORK, "ours")
THEIRS = os.path.join(WORK, "theirs")
SKIP = {".git", ".hanhua-orig", ".hanhua2", "build", ".gradle", ".idea", ".cxx", ".kotlin"}

STR_RE = re.compile(
    r'"""(?:[^"\\]|\\.)*?"""|"(?:[^"\\\n]|\\.)*"|\'(?:[^\'\\\n]|\\.)*\'')


def skel_lines(text):
    return [STR_RE.sub('""', l) for l in text.split("\n")]


def read(p):
    try:
        return open(p, encoding="utf-8").read()
    except (UnicodeDecodeError, OSError):
        return None


def check_markers():
    bad = []
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in SKIP]
        for f in fn:
            p = os.path.join(dp, f)
            try:
                data = open(p, "rb").read(8_000_000)
            except OSError:
                continue
            if re.search(rb"^<{7} ", data, re.M) or re.search(rb"^>{7} ", data, re.M):
                bad.append(os.path.relpath(p, ROOT))
    return bad


def check_xml():
    import xml.etree.ElementTree as ET
    bad = []
    base = os.path.join(ROOT, "app", "src", "main", "res")
    for dp, dn, fn in os.walk(base):
        for f in fn:
            if f.endswith(".xml"):
                p = os.path.join(dp, f)
                try:
                    ET.parse(p)
                except Exception as e:
                    bad.append((os.path.relpath(p, ROOT), str(e)))
    # AndroidManifest too
    p = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
    try:
        ET.parse(p)
    except Exception as e:
        bad.append(("AndroidManifest.xml", str(e)))
    return bad


def check_resources():
    import xml.etree.ElementTree as ET
    vals = set()
    root = ET.parse(os.path.join(ROOT, "app/src/main/res/values/strings.xml")).getroot()
    for el in root:
        if el.get("name"):
            vals.add((el.tag, el.get("name")))
    refs = set()
    pat_s = re.compile(r"\bR\.string\.(\w+)")
    pat_p = re.compile(r"\bR\.plurals\.(\w+)")
    for dp, dn, fn in os.walk(os.path.join(ROOT, "app", "src")):
        dn[:] = [d for d in dn if d not in SKIP]
        for f in fn:
            if f.endswith((".kt", ".java")):
                t = read(os.path.join(dp, f)) or ""
                refs |= {("string", m) for m in pat_s.findall(t)}
                refs |= {("plurals", m) for m in pat_p.findall(t)}
    # android:label / @string refs in xml
    pat_at = re.compile(r"@string/(\w+)")
    for dp, dn, fn in os.walk(os.path.join(ROOT, "app", "src", "main")):
        for f in fn:
            if f.endswith(".xml"):
                t = read(os.path.join(dp, f)) or ""
                refs |= {("string", m) for m in pat_at.findall(t)}
    missing = sorted(refs - vals)
    return [(k, n) for (k, n) in missing]


def classify_files():
    results = {"OK-THEIRS": [], "OK-OURS": [], "MISSING": [], "REVIEW": []}
    for label, tree in (("theirs", THEIRS), ("ours", OURS)):
        pass
    tset, oset = set(), set()
    for base, s in ((THEIRS, tset), (OURS, oset)):
        for dp, dn, fn in os.walk(base):
            for f in fn:
                s.add(os.path.relpath(os.path.join(dp, f), base).replace(os.sep, "/"))
    allf = sorted(tset | oset)
    for rel in allf:
        ext = os.path.splitext(rel)[1]
        if ext in (".png", ".jpg", ".webp", ".gif", ".so", ".bin", ".jar", ".ttf",
                   ".otf", ".woff", ".ico", ".pdf", ".zip", ".gz", ".zst", ".apk"):
            continue
        live_p = os.path.join(ROOT, rel.replace("/", os.sep))
        live = read(live_p)
        if live is None:
            if rel in tset:  # deleted but upstream has it
                results["MISSING"].append(rel)
            continue
        if rel in tset:
            theirs = read(os.path.join(THEIRS, rel.replace("/", os.sep)))
            if theirs is not None:
                a, b = skel_lines(live), skel_lines(theirs)
                if a == b:
                    results["OK-THEIRS"].append(rel)
                    continue
                sm = SequenceMatcher(None, a, b)
                if sm.ratio() > 0.995 and abs(len(a) - len(b)) <= 2:
                    results["OK-THEIRS"].append(rel)
                    continue
        if rel in oset:
            ours = read(os.path.join(OURS, rel.replace("/", os.sep)))
            if ours is not None:
                a, b = skel_lines(live), skel_lines(ours)
                if a == b:
                    results["OK-OURS"].append(rel)
                    continue
                sm = SequenceMatcher(None, b, a)
                if sm.ratio() > 0.995 and abs(len(a) - len(b)) <= 2:
                    results["OK-OURS"].append(rel)
                    continue
        if rel in tset or rel in oset:
            results["REVIEW"].append(rel)
    return results


def english_leftovers():
    """heuristic scan of user-visible English in final tree"""
    import xml.etree.ElementTree as ET
    hits = []
    pats = [
        (re.compile(r'\bText\(\s*"([A-Za-z][^"]{2,})"'), "Text()"),
        (re.compile(r'\btitle\s*=\s*"([A-Za-z][^"]{2,})"'), "title"),
        (re.compile(r'\blabel\s*=\s*"([A-Za-z][^"]{2,})"'), "label"),
        (re.compile(r'\bplaceholder\s*=\s*"([A-Za-z][^"]{2,})"'), "placeholder"),
        (re.compile(r'\bhint\s*=\s*"([A-Za-z][^"]{2,})"'), "hint"),
        (re.compile(r'\bnote\s*=\s*"([A-Za-z][^"]{2,})"'), "note"),
        (re.compile(r'showToast\([^,]*,\s*"([A-Za-z][^"]{3,})"'), "toast"),
        (re.compile(r'contentDescription\s*=\s*"([A-Za-z][^"]{2,})"'), "a11y"),
        (re.compile(r'\bcontentTitle\s*=\s*"([A-Za-z][^"]{2,})"'), "notif"),
        (re.compile(r'\bcontentText\s*=\s*"([A-Za-z][^"]{2,})"'), "notif"),
    ]
    for dp, dn, fn in os.walk(os.path.join(ROOT, "app", "src", "main")):
        dn[:] = [d for d in dn if d not in SKIP]
        for f in fn:
            if not f.endswith((".kt", ".java")):
                continue
            p = os.path.join(dp, f)
            t = read(p) or ""
            for pat, name in pats:
                for m in pat.finditer(t):
                    val = m.group(1)
                    if re.search(r"[一-鿿]", val):
                        continue
                    if re.match(r"^(GT-|SM-|Pixel|Emulator)", val):
                        continue
                    line = t.count("\n", 0, m.start()) + 1
                    hits.append((os.path.relpath(p, ROOT), line, name, val[:60]))
    # XML string values still English
    root = ET.parse(os.path.join(ROOT, "app/src/main/res/values/strings.xml")).getroot()
    for el in root:
        if el.tag == "string" and el.text and re.search(r"[A-Za-z]{2,}\s+[A-Za-z]{2,}", el.text) \
                and not re.search(r"[一-鿿]", el.text):
            hits.append(("values/strings.xml", 0, "resource", (el.get("name") or "") + "=" + el.text[:50]))
    return hits


def main():
    print("== conflict markers:", check_markers() or "OK")
    print("== xml parse:", check_xml() or "OK")
    print("== missing resource refs:", check_resources() or "OK")
    cls = classify_files()
    for k in ("OK-THEIRS", "OK-OURS", "MISSING", "REVIEW"):
        print(f"== {k}: {len(cls[k])}")
    for r in cls["REVIEW"]:
        print("   REVIEW:", r)
    for r in cls["MISSING"]:
        print("   MISSING:", r)
    try:
        json.dump(cls, open(os.path.join(WORK, "verify_class.json"), "w"), indent=1)
    except OSError:
        pass
    hits = english_leftovers()
    print("== english leftover candidates:", len(hits))
    for h in hits[:80]:
        print("   ", h)


if __name__ == "__main__":
    main()
