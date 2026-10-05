"""Auto-resolve content conflicts by rule; emit manual-review docket.

Rules (per hunk, ours = HEAD/localized side, theirs = upstream/new side):
  R1 identical                -> keep ours
  R2 structurally equal       -> take theirs (its strings get translated in Phase 4)
  R2c same code w/ comments   -> take theirs
  R3 theirs uses stringResource -> take theirs (translation lives in resources)
  MANUAL otherwise            -> docket with base/ours/theirs blocks

Special-cased files always go to the docket (semantic merges):
  strings.xml x2, README, docs/*.md, PATCHES.md, release.env, ZERO_COPY_SPIKE.md
Output: WORK/resolved/<path> for fully-auto files, WORK/review/manual.json,
        WORK/decisions.json, WORK/review/manual.md (readable docket).
"""
import json
import os
import re
import sys

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


def skeleton(line: str) -> str:
    return STR_RE.sub('""', line)


def strip_comment(line: str) -> str:
    s = skeleton(line)
    idx = s.find("//")
    if idx >= 0:
        s = s[:idx]
    if re.match(r"^\s*#", line):
        s = ""
    return s


def structurally_equal(ours: str, theirs: str, comment_too=False) -> bool:
    fn = strip_comment if comment_too else skeleton
    la = [x for x in ours.split("\n")]
    lb = [x for x in theirs.split("\n")]
    if len(la) != len(lb):
        return False
    return all(fn(x).rstrip() == fn(y).rstrip() for x, y in zip(la, lb))


def parse_hunks(text: str):
    """-> (segs: list[list[str]] lines, hunks: list[(ourlines, theirlines)])"""
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


def classify(ours_l, theirs_l):
    ours, theirs = "\n".join(ours_l), "\n".join(theirs_l)
    if ours == theirs:
        return "R1", ours_l
    if "stringResource(" in theirs and "stringResource(" not in ours:
        return "R3", theirs_l
    if structurally_equal(ours, theirs):
        return "R2", theirs_l
    if structurally_equal(ours, theirs, comment_too=True):
        return "R2c", theirs_l
    return "MANUAL", None


def main():
    inv = json.load(open(os.path.join(WORK, "conflicts.json"), encoding="utf-8"))
    os.makedirs(os.path.join(WORK, "resolved"), exist_ok=True)
    os.makedirs(os.path.join(WORK, "review"), exist_ok=True)
    stats = {}
    manual = []
    decisions = {}
    for p, info in sorted(inv.items()):
        stages = info["stages"]
        if stages == [1, 2]:
            decisions[p] = {"status": "delete-by-theirs", "stages": stages}
            stats["DELETE"] = stats.get("DELETE", 0) + 1
            continue
        live = os.path.join(ROOT, p.replace("/", os.sep))
        if not os.path.exists(live):
            decisions[p] = {"status": "missing", "stages": stages}
            continue
        raw = open(live, "rb").read()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            decisions[p] = {"status": "bad-utf8"}
            continue
        if "<<<<<<< " not in text:
            decisions[p] = {"status": "no-markers"}
            continue
        segs, hunks = parse_hunks(text)
        rules, resolved, all_auto = [], [], True
        for k, (ours_l, theirs_l) in enumerate(hunks):
            if p in SPECIAL:
                rules.append("SPECIAL")
                all_auto = False
                manual.append({"path": p, "hunk": k, "ours": ours_l, "theirs": theirs_l,
                               "reason": "special-file"})
                resolved.append(None)
                stats["SPECIAL"] = stats.get("SPECIAL", 0) + 1
                continue
            rule, res = classify(ours_l, theirs_l)
            rules.append(rule)
            stats[rule] = stats.get(rule, 0) + 1
            if rule == "MANUAL":
                all_auto = False
                manual.append({"path": p, "hunk": k, "ours": ours_l, "theirs": theirs_l,
                               "reason": "structurally-different"})
                resolved.append(None)
            else:
                resolved.append(res)
        decisions[p] = {"status": "auto" if all_auto else "partial", "rules": rules}
        if all_auto:
            out = list(segs[0])
            for k in range(len(hunks)):
                out.extend(resolved[k])
                out.extend(segs[k + 1])
            dest = os.path.join(WORK, "resolved", p.replace("/", os.sep))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "w", encoding="utf-8", newline="") as f:
                f.write("\n".join(out))

    json.dump(manual, open(os.path.join(WORK, "review", "manual.json"), "w",
                           encoding="utf-8"), indent=1, ensure_ascii=False)
    json.dump(decisions, open(os.path.join(WORK, "decisions.json"), "w",
                              encoding="utf-8"), indent=1, ensure_ascii=False)
    # readable docket
    with open(os.path.join(WORK, "review", "manual.md"), "w", encoding="utf-8") as f:
        cur = None
        for m in manual:
            if m["path"] != cur:
                cur = m["path"]
                f.write("\n\n# FILE %s\n" % cur)
            f.write("\n## hunk %d (%s)\n" % (m["hunk"], m["reason"]))
            f.write("### OURS\n```\n%s\n```\n" % "\n".join(m["ours"]))
            f.write("### THEIRS\n```\n%s\n```\n" % "\n".join(m["theirs"]))
    print("rule stats:", json.dumps(stats, indent=1, ensure_ascii=False))
    print("manual hunks:", len(manual))
    auto = sum(1 for d in decisions.values() if d.get("status") == "auto")
    partial = sum(1 for d in decisions.values() if d.get("status") == "partial")
    print("auto files:", auto, "partial:", partial, "total:", len(decisions))


if __name__ == "__main__":
    main()
