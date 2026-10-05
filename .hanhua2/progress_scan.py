# -*- coding: utf-8 -*-
"""Per-task completion scan: for each delta work item, how many of the English
lines the update introduced are still untranslated in the workspace?"""
import json
import os
import re

WORK = os.environ.get("HANHUA_WORK") or r"C:\Users\wzxcz\AppData\Local\Temp\dsh-StYPwy\hanhua2-work"
ROOT = r"D:\DroidDeck"
STR_RE = re.compile(r'"(?:[^"\\\n]|\\.)*"')

STOP = set("""the and for with this that from when then into are was were not but can will
its their have has been all any one use used using via per you your which while also only
over such each than there these those what where who how why out get may see run runs file
files line path value values one two next after before if else true false null none""".split())


def englishish(s):
    s = re.sub(r"\$\{[^}]*\}|%[1-9]\$?[a-z]|https?://\S+|/[\w./-]+", " ", s)
    words = [w for w in re.findall(r"[A-Za-z]{2,}", s) if w.lower() not in STOP]
    return len(words) >= 3 and not re.search(r"[一-鿿]", s)


def main():
    d = json.load(open(os.path.join(WORK, "delta", "files.json"), encoding="utf-8"))
    buckets = {}
    rows = []
    for r in d["changed"]:
        rel = r["path"]
        diffp = os.path.join(WORK, "delta", rel.replace("/", "__") + ".diff")
        if not os.path.exists(diffp) or not r["eng"]:
            continue
        diff = open(diffp, encoding="utf-8").read().split("\n")
        added_eng = [l[1:] for l in diff if l.startswith("+") and not l.startswith("+++")]
        added_eng = [l for l in added_eng if englishish(l)]
        try:
            live = open(os.path.join(ROOT, rel.replace("/", os.sep)), encoding="utf-8").read()
        except Exception:
            live = ""
        live_lines = set(l.split("\r")[0].strip() for l in live.split("\n"))
        remain = [l for l in added_eng if l.strip() and l.split("\r")[0].strip() not in live_lines]
        if added_eng:
            rows.append((rel, len(added_eng) - len(remain), len(added_eng)))
    # new files: english message-ish lines still present
    for r in d["new"]:
        rel = r["path"]
        if not r["eng_lines"]:
            continue
        try:
            t = open(os.path.join(ROOT, rel.replace("/", os.sep)), encoding="utf-8").read()
        except Exception:
            continue
        n = 0
        for l in t.split("\n"):
            s = l.strip()
            if not s or re.search(r"[一-鿿]", s):
                continue
            if rel.endswith((".md", ".txt")):
                if englishish(s):
                    n += 1
            else:
                strs = STR_RE.findall(s)
                if strs and any(englishish(x) for x in strs):
                    n += 1
        if n:
            rows.append((rel, 0, n))
    # summarize by first path segment bucket
    def bucket(p):
        if p.startswith("app/src/main/java"): return "kotlin"
        if p.startswith("app/src/test"): return "app-tests"
        if p.startswith("app/src/main/cpp") or p.endswith((".c", ".cpp", ".h", ".hpp")): return "cpp"
        if p.startswith("tools/linuxfs") or p.startswith("tools/release") or p.startswith("tools/build") or p.startswith("tools/proot/bench") or p.endswith((".sh", ".py")) and p.startswith("tools/"): return "scripts"
        if p.endswith(".md"): return "docs"
        if p.startswith("app/src/main/res"): return "resources"
        return "misc"
    agg = {}
    for rel, done, total in rows:
        b = bucket(rel)
        a = agg.setdefault(b, [0, 0, 0, 0])
        a[0] += 1; a[1] += done; a[2] += total
        if done < total: a[3] += 1
    print(f"{'bucket':12s} {'files':>5s} {'todo-files':>10s} {'lines-done':>10s} {'lines-left':>10s}")
    for b, a in sorted(agg.items()):
        nf, done, total, todo = a
        print(f"{b:12s} {nf:5d} {todo:10d} {done:10d} {total - done:10d}")
    print("\n-- files with remaining English (top 45) --")
    for rel, done, total in sorted(rows, key=lambda x: -(x[2] - x[1]))[:45]:
        if done < total:
            print(f"  {total - done:4d}/{total:4d}  {rel}")


if __name__ == "__main__":
    main()
