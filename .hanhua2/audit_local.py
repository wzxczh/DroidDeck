"""Safety audit: structural (non-string) changes our side made vs base.

For every file present in both base/ and ours/ trees, diff skeleton-blanked lines.
Files with structural changes are listed with the changed line pairs, so we can
verify each local fix survives the merge (i.e. is NOT inside a taken-theirs hunk).
Also: which of those files are conflicted (thus at risk).
"""
import json
import os
import re
from difflib import SequenceMatcher

WORK = os.environ.get("HANHUA_WORK") or os.path.join(
    os.environ.get("TEMP", r"C:\Temp"), "hanhua2-work")

STR_RE = re.compile(
    r'"""(?:[^"\\]|\\.)*?"""'
    r"|\"(?:[^\"\\\n]|\\.)*\""
    r"|'(?:[^'\\\n]|\\.)*'",
    re.S)

TEXT_EXT = {".kt", ".java", ".c", ".cpp", ".h", ".hpp", ".py", ".sh", ".md",
            ".xml", ".gradle", ".properties", ".env", ".yml", ".yaml", ".txt",
            ".json", ".cmake", ".mk", ".rc", ".html", ".css", ".js", ".ts"}


def skel(line):
    return STR_RE.sub('""', line)


def main():
    base_dir = os.path.join(WORK, "base")
    ours_dir = os.path.join(WORK, "ours")
    conflicts = json.load(open(os.path.join(WORK, "conflicts.json"), encoding="utf-8"))
    conf_set = set(conflicts)
    results = {}
    for dp, dn, fn in os.walk(base_dir):
        for f in fn:
            bp = os.path.join(dp, f)
            rel = os.path.relpath(bp, base_dir).replace(os.sep, "/")
            op = os.path.join(ours_dir, rel.replace("/", os.sep))
            if not os.path.exists(op):
                continue
            ext = os.path.splitext(f)[1]
            if ext and ext not in TEXT_EXT and f not in ("Makefile", "Dockerfile", "gradlew"):
                continue
            try:
                b = open(bp, encoding="utf-8").read().split("\n")
                o = open(op, encoding="utf-8").read().split("\n")
            except Exception:
                continue
            zb = [skel(x) for x in b]
            zo = [skel(x) for x in o]
            if zb == zo:
                continue
            sm = SequenceMatcher(None, zb, zo)
            changes = []
            structural = False
            for tag, i1, i2, j1, j2 in sm.get_opcodes():
                if tag == "equal":
                    continue
                # string-only change? compare raw slices equality of skeleton vs raw
                raw_b = "\n".join(b[i1:i2])
                raw_o = "\n".join(o[j1:j2])
                if skel(raw_b) == skel(raw_o) and [skel(x) for x in b[i1:i2]] == [skel(x) for x in o[j1:j2]]:
                    kind = "string"
                else:
                    # per-line skeleton compare where line counts match
                    if (i2 - i1) == (j2 - j1) and all(
                            skel(b[x]) == skel(o[j1 + (x - i1)]) for x in range(i1, i2)):
                        kind = "string"
                    else:
                        kind = "STRUCT"
                        structural = True
                changes.append({"kind": kind, "base": b[i1:i2], "ours": o[j1:j2]})
            if structural:
                results[rel] = {
                    "conflicted": rel in conf_set,
                    "changes": [c for c in changes if c["kind"] == "STRUCT"],
                }
    out = os.path.join(WORK, "local_structural.json")
    json.dump(results, open(out, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    print("files with structural local changes:", len(results))
    for k, v in sorted(results.items()):
        print(("CONFLICTED " if v["conflicted"] else "clean      "), k, "hunks:", len(v["changes"]))


if __name__ == "__main__":
    main()
