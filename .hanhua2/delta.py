"""Phase 4 delta report: what the version update brought in that needs translating.

For every file in the final workspace, compare against our committed (HEAD/ours) tree:
  - NEW file (not in ours)              -> whole-file work (upstream added)
  - CHANGED lines                        -> the changed lines are upstream's new content
Then flag which changed lines carry English string literals / prose.

Outputs:
  WORK/delta/files.json   list of changed/new files with counts
  WORK/delta/<relpath>.diff   unified diff (ours -> final) per changed file
  WORK/delta/summary.md   bucketed worklist for subagent tasks
"""
import json
import os
import re
import sys
from difflib import unified_diff

WORK = os.environ.get("HANHUA_WORK") or r"C:\Users\wzxcz\AppData\Local\Temp\dsh-StYPwy\hanhua2-work"
ROOT = r"D:\DroidDeck"
OURS = os.path.join(WORK, "ours")
SKIP = {".git", ".hanhua-orig", ".hanhua2", "build", ".gradle", ".idea", ".cxx", ".kotlin"}
STR_RE = re.compile(
    r'"""(?:[^"\\]|\\.)*?"""|"(?:[^"\\\n]|\\.)*"|\'(?:[^\'\\\n]|\\.)*\'')
HAS_ENG = re.compile(r"[A-Za-z]{2,}")
# prose (markdown) lines that are non-code
CODE_EXT = {".kt", ".java", ".c", ".cpp", ".h", ".hpp", ".py", ".sh", ".xml",
            ".gradle", ".properties", ".env", ".yml", ".yaml", ".json", ".cmake",
            ".mk", ".html", ".js", ".ts", ".rc", ".txt", ".pl", ".service", ".desktop"}


def englishish(s: str) -> bool:
    """True when the text looks like untranslated English prose/UI."""
    if not HAS_ENG.search(s):
        return False
    # strip technical tokens: paths, urls, identifiers, format specifiers
    t = re.sub(r"https?://\S+|/[\w./-]+|\$\{[^}]*\}|%[1-9]\$?[a-zA-Z]|`[^`]*`", " ", s)
    t = re.sub(r"\b[A-Z][A-Za-z]+(?:[A-Z][a-z]+)+\b", " ", t)   # CamelCase idents
    words = re.findall(r"[A-Za-z]{2,}", t)
    if not words:
        return False
    stop = {"the", "and", "for", "with", "this", "that", "not", "are", "was", "you",
            "your", "from", "when", "then", "into", "its", "it's", "but", "can",
            "will", "all", "any", "one", "use", "used", "using", "via", "per"}
    real = [w for w in words if w.lower() not in stop]
    return len(real) >= 2


def line_english(line: str, ext: str) -> bool:
    ls = line.strip()
    if not ls or ls.startswith("//") or ls.startswith("#") and ext not in (".py", ".sh"):
        pass  # comments: Round-1 scope excludes them; still count separately
    if ext == ".md":
        # markdown prose
        if ls.startswith(("|", "![", "[", "-", "*", ">", "|")):
            body = re.sub(r"[|#*>\[\]()`-]", " ", ls)
        else:
            body = ls
        return englishish(body)
    strs = STR_RE.findall(line)
    if strs:
        return any(englishish(s) for s in strs)
    return False


def main():
    final_files = []
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in SKIP]
        for f in fn:
            p = os.path.join(dp, f)
            final_files.append(os.path.relpath(p, ROOT).replace(os.sep, "/"))
    our_set = set()
    for dp, dn, fn in os.walk(OURS):
        for f in fn:
            p = os.path.join(dp, f)
            our_set.add(os.path.relpath(p, OURS).replace(os.sep, "/"))

    os.makedirs(os.path.join(WORK, "delta"), exist_ok=True)
    report = {"new": [], "changed": [], "removed": []}
    buckets = {}
    for rel in sorted(final_files):
        fp = os.path.join(ROOT, rel.replace("/", os.sep))
        try:
            final = open(fp, encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        if rel not in our_set:
            ext = os.path.splitext(rel)[1]
            if ext in CODE_EXT or ext == ".md" or ext == "":
                n = len([1 for ln in final.split("\n") if line_english(ln, ext or ".txt")])
                if n:
                    report["new"].append({"path": rel, "eng_lines": n})
            continue
        op = os.path.join(OURS, rel.replace("/", os.sep))
        try:
            old = open(op, encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        if old == final:
            continue
        ext = os.path.splitext(rel)[1]
        ol, nl = old.split("\n"), final.split("\n")
        diff = list(unified_diff(ol, nl, "ours/" + rel, "final/" + rel, lineterm=""))
        # changed final-side lines
        added = [l[1:] for l in diff if l.startswith("+") and not l.startswith("+++")]
        eng = [l for l in added if line_english(l, ext or ".txt")]
        if eng or ext == ".md":
            report["changed"].append({"path": rel, "added": len(added),
                                      "eng": len(eng)})
        if eng:
            buckets.setdefault(ext or "(none)", []).append(rel)
        if added and ext in (".md",):
            pass
        with open(os.path.join(WORK, "delta", rel.replace("/", "__") + ".diff"),
                  "w", encoding="utf-8") as f:
            f.write("\n".join(diff))
    # removed files (present in ours, gone in final)
    final_set = set(final_files)
    report["removed"] = sorted(our_set - final_set)

    json.dump(report, open(os.path.join(WORK, "delta", "files.json"), "w",
                           encoding="utf-8"), indent=1, ensure_ascii=False)
    print("new files w/ English:", len(report["new"]))
    print("changed files:", len(report["changed"]))
    print("removed files:", len(report["removed"]))
    tot = sum(r["eng"] for r in report["changed"])
    print("changed files w/ English lines:", sum(1 for r in report["changed"] if r["eng"]),
          "total eng lines:", tot)
    print("by ext:", {k: len(v) for k, v in sorted(buckets.items())})
    top = sorted(report["changed"], key=lambda r: -r["eng"])[:40]
    for r in top:
        print("  %4d eng / %4d added  %s" % (r["eng"], r["added"], r["path"]))
    print("NEW:")
    for r in sorted(report["new"], key=lambda r: -r["eng_lines"])[:40]:
        print("  %4d eng  %s" % (r["eng_lines"], r["path"]))


if __name__ == "__main__":
    main()
