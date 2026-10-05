"""Conflict resolver v2 -- uses base/ours/theirs stage blobs.

Per conflicted path with stages [1,2,3]:
  - locate each git hunk's OURS block inside the stage-2 (ours) file (monotonic search)
  - skeleton-diff base->ours at file level to find OUR structural (non-string) changes
  - hunk with NO structural change on our side  -> auto take THEIRS (Phase 4 retranslates)
  - hunk WITH structural change on our side     -> manual docket (base/ours/theirs shown)
Special files and stage [1,2] / [2,3] go to the docket (resources/docs/renames).

Outputs:
  WORK/resolved2/<path>          fully auto files (final content)
  WORK/partial2/<path>           partially resolved: auto hunks applied, manual hunks
                                 kept as conflict markers (for subagent editing)
  WORK/review/manual2.json/.md   manual docket with base context
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gread import ObjectStore

WORK = os.environ.get("HANHUA_WORK") or os.path.join(
    os.environ.get("TEMP", r"C:\Users\wzxcz\AppData\Local\Temp"), "hanhua2-work")
ROOT = r"D:\DroidDeck"

SPECIAL = {
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-zh-rCN/strings.xml",
    "README.md",
    "docs/development/flatpak.md",
    "docs/development/game-environment.md",
    "docs/development/steam-game-imports.md",
    "tools/gamescope/PATCHES.md",
    "tools/proot/PATCHES.md",
    "tools/gamescope/release.env",
    "app/src/main/cpp/waylandcomp/ZERO_COPY_SPIKE.md",
}

STR_RE = re.compile(
    r'"""(?:[^"\\]|\\.)*?"""'
    r"|\"(?:[^\"\\\n]|\\.)*\""
    r"|'(?:[^'\\\n]|\\.)*'",
    re.S)


def skel(line: str) -> str:
    return STR_RE.sub('""', line)


def blob(store, sha):
    if not sha:
        return None
    typ, data = store.get(sha)
    return data.decode("utf-8", "replace")


def parse_hunks(text: str):
    lines = text.split("\n")
    segs, hunks, cur = [], [], []
    i, n = 0, len(lines)
    while i < n:
        if lines[i].startswith("<<<<<<< "):
            segs.append(cur)
            cur = []
            i += 1
            ours = []
            while i < n and not lines[i].startswith("======="):
                ours.append(lines[i])
                i += 1
            i += 1
            theirs = []
            while i < n and not lines[i].startswith(">>>>>>> "):
                theirs.append(lines[i])
                i += 1
            i += 1
            hunks.append((ours, theirs))
        else:
            cur.append(lines[i])
            i += 1
    segs.append(cur)
    return segs, hunks


def find_block(haystack, needle, cursor):
    """find start index of needle (list of lines) at/after cursor; returns (start, end)"""
    if not needle:
        return cursor, cursor
    n = len(needle)
    first = needle[0]
    for start in range(cursor, len(haystack) - n + 1):
        if haystack[start] == first and haystack[start:start + n] == needle:
            return start, start + n
    return None


def structural_ours_lines(base_lines, ours_lines):
    """indices of ours_lines that differ structurally (not just strings) from base"""
    from difflib import SequenceMatcher
    zb = [skel(x) for x in base_lines]
    ob = [skel(x) for x in ours_lines]
    sm = SequenceMatcher(None, zb, ob)
    changed = set()
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "replace":
            changed.update(range(j1, j2))
        elif tag == "insert":
            changed.update(range(j1, j2))
        elif tag == "delete":
            # ours removed base lines: flag boundary line
            if j1 < len(ob):
                changed.add(j1)
            elif j1 > 0:
                changed.add(j1 - 1)
    return changed


def base_block_for(base_lines, ours_lines, o1, o2):
    from difflib import SequenceMatcher
    sm = SequenceMatcher(None, base_lines, ours_lines)
    blocks = sm.get_matching_blocks()
    bs, be = None, None
    for (ba, aa, size) in blocks:
        if size == 0:
            continue
        if bs is None and aa + size > o1:
            bs = ba + max(0, o1 - aa)
        if aa <= o2 <= aa + size:
            be = ba + (o2 - aa)
            break
        if aa < o2 < aa + size:
            be = ba + (o2 - aa)
            break
    if bs is None:
        bs = 0
    if be is None:
        # nearest: after last block start
        be = len(base_lines)
    if be < bs:
        bs, be = be, bs
    return base_lines[bs:be]


def main():
    store = ObjectStore()
    inv = json.load(open(os.path.join(WORK, "conflicts.json"), encoding="utf-8"))
    os.makedirs(os.path.join(WORK, "resolved2"), exist_ok=True)
    os.makedirs(os.path.join(WORK, "partial2"), exist_ok=True)
    os.makedirs(os.path.join(WORK, "review"), exist_ok=True)
    stats = {}
    manual = []
    decisions = {}
    for p, info in sorted(inv.items()):
        stages = info["stages"]
        if stages == [1, 2]:
            decisions[p] = {"status": "delete-by-theirs"}
            stats["DELETE"] = stats.get("DELETE", 0) + 1
            continue
        base = blob(store, info["base"]) if info.get("base") else ""
        ours = blob(store, info["ours"])
        theirs = blob(store, info["theirs"])
        if ours is None or theirs is None:
            decisions[p] = {"status": "missing-stage"}
            stats["MISSING_STAGE"] = stats.get("MISSING_STAGE", 0) + 1
            continue
        live = os.path.join(ROOT, p.replace("/", os.sep))
        text = open(live, "rb").read().decode("utf-8", "replace")
        if "<<<<<<< " not in text:
            decisions[p] = {"status": "no-markers"}
            continue
        segs, hunks = parse_hunks(text)
        bl, ol, tl = base.split("\n"), ours.split("\n"), theirs.split("\n")
        struct_lines = structural_ours_lines(bl, ol)
        cursor = 0
        rules, resolved, all_auto = [], [], True
        for k, (ourb, theirb) in enumerate(hunks):
            if p in SPECIAL:
                rules.append("SPECIAL")
                all_auto = False
                manual.append({"path": p, "hunk": k, "reason": "special-file",
                               "ours": ourb, "theirs": theirb, "base": None})
                resolved.append(None)
                stats["SPECIAL"] = stats.get("SPECIAL", 0) + 1
                continue
            pos = find_block(ol, ourb, cursor)
            if pos is None:
                rules.append("NOTFOUND")
                all_auto = False
                manual.append({"path": p, "hunk": k, "reason": "ours-block-not-found",
                               "ours": ourb, "theirs": theirb, "base": None})
                resolved.append(None)
                stats["NOTFOUND"] = stats.get("NOTFOUND", 0) + 1
                continue
            o1, o2 = pos
            cursor = o2
            local_struct = sorted(i for i in struct_lines if o1 <= i < o2)
            if not local_struct:
                rules.append("AUTO-THEIRS")
                stats["AUTO-THEIRS"] = stats.get("AUTO-THEIRS", 0) + 1
                resolved.append(theirb)
            else:
                rules.append("MANUAL")
                stats["MANUAL"] = stats.get("MANUAL", 0) + 1
                all_auto = False
                bb = base_block_for(bl, ol, o1, o2)
                manual.append({"path": p, "hunk": k,
                               "reason": "ours-has-structural-change",
                               "ours": ourb, "theirs": theirb, "base": bb,
                               "struct_ours_lines": local_struct})
                resolved.append(None)
        decisions[p] = {"status": "auto" if all_auto else "partial", "rules": rules}
        if all_auto:
            out = list(segs[0])
            for k in range(len(hunks)):
                out.extend(resolved[k])
                out.extend(segs[k + 1])
            dest = os.path.join(WORK, "resolved2", p.replace("/", os.sep))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            open(dest, "w", encoding="utf-8", newline="").write("\n".join(out))
        else:
            # partial: auto hunks applied, manual kept as markers
            out = list(segs[0])
            for k in range(len(hunks)):
                if resolved[k] is not None:
                    out.extend(resolved[k])
                else:
                    out.append("<<<<<<< HEAD")
                    out.extend(hunks[k][0])
                    out.append("=======")
                    out.extend(hunks[k][1])
                    out.append(">>>>>>> upstream/main")
                out.extend(segs[k + 1])
            dest = os.path.join(WORK, "partial2", p.replace("/", os.sep))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            open(dest, "w", encoding="utf-8", newline="").write("\n".join(out))

    json.dump(manual, open(os.path.join(WORK, "review", "manual2.json"), "w",
                           encoding="utf-8"), indent=1, ensure_ascii=False)
    json.dump(decisions, open(os.path.join(WORK, "decisions2.json"), "w",
                              encoding="utf-8"), indent=1, ensure_ascii=False)
    with open(os.path.join(WORK, "review", "manual2.md"), "w", encoding="utf-8") as f:
        cur = None
        for m in manual:
            if m["path"] != cur:
                cur = m["path"]
                f.write("\n\n# FILE %s\n" % cur)
            f.write("\n## hunk %d (%s)\n" % (m["hunk"], m["reason"]))
            if m.get("base"):
                f.write("### BASE (approx)\n```\n%s\n```\n" % "\n".join(m["base"]))
            if m["ours"] is not None:
                f.write("### OURS\n```\n%s\n```\n" % "\n".join(m["ours"]))
                f.write("### THEIRS\n```\n%s\n```\n" % "\n".join(m["theirs"]))
    print("stats:", json.dumps(stats, indent=1, ensure_ascii=False))
    print("manual hunks:", len(manual))
    auto = sum(1 for d in decisions.values() if d.get("status") == "auto")
    partial = sum(1 for d in decisions.values() if d.get("status") == "partial")
    print("auto files:", auto, "partial:", partial, "total:", len(decisions))


if __name__ == "__main__":
    main()
