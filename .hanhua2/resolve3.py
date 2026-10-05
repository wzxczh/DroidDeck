"""Conflict resolver v3 -- FINAL policy: take THEIRS for every hunk (518/518).

Justification chain (see audit_local.py + manual2.md):
  * our side of every hunk is translation-only (structural check vs base),
    except 11 hunks individually reviewed -> also safe to take theirs;
  * local non-translation fixes sit OUTSIDE conflict hunks (survive the merge);
  * resources: values-zh-rCN takes upstream's complete Chinese; values/ is
    afterwards synced FROM zh-rCN (keys missing there are kept + flagged).

Outputs: WORK/resolved3/<path> for every conflicted content file,
         WORK/decisions3.json (per-hunk audit trail),
         WORK/values_only_keys.json (keys present only in default values).
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gread import ObjectStore

WORK = os.environ.get("HANHUA_WORK") or os.path.join(
    os.environ.get("TEMP", r"C:\Users\wzxcz\AppData\Local\Temp"), "hanhua2-work")


def blob(store, sha):
    if not sha:
        return None
    return store.get(sha)[1].decode("utf-8", "replace")


def parse_hunks(text):
    lines = text.split("\n")
    segs, hunks, cur = [], [], []
    i, n = 0, len(lines)
    while i < n:
        if lines[i].startswith("<<<<<<< "):
            segs.append(cur); cur = []
            i += 1
            ours = []
            while i < n and not lines[i].startswith("======="):
                ours.append(lines[i]); i += 1
            i += 1
            theirs = []
            while i < n and not lines[i].startswith(">>>>>>> "):
                theirs.append(lines[i]); i += 1
            i += 1
            hunks.append((ours, theirs))
        else:
            cur.append(lines[i]); i += 1
    segs.append(cur)
    return segs, hunks


def resolve_file(text):
    segs, hunks = parse_hunks(text)
    out = list(segs[0])
    for k in range(len(hunks)):
        out.extend(hunks[k][1])          # THEIRS
        out.extend(segs[k + 1])
    return "\n".join(out), len(hunks)


def keys_of(xml_text):
    """-> dict kind->name for <string>/<string-array>/<plurals>"""
    root = ET.fromstring(xml_text)
    res = {}
    for el in root:
        name = el.get("name")
        if name:
            res[(el.tag, name)] = ET.tostring(el, encoding="unicode")
    return res


def main():
    store = ObjectStore()
    inv = json.load(open(os.path.join(WORK, "conflicts.json"), encoding="utf-8"))
    os.makedirs(os.path.join(WORK, "resolved3"), exist_ok=True)
    decisions = {}
    hunk_total = 0
    for p, info in sorted(inv.items()):
        if info["stages"] == [1, 2]:
            decisions[p] = {"status": "delete-by-theirs"}
            continue
        live = os.path.join(r"D:\DroidDeck", p.replace("/", os.sep))
        text = open(live, "rb").read().decode("utf-8", "replace")
        if "<<<<<<< " not in text:
            decisions[p] = {"status": "no-markers"}
            continue
        res, n = resolve_file(text)
        hunk_total += n
        decisions[p] = {"status": "resolved-theirs", "hunks": n}
        dest = os.path.join(WORK, "resolved3", p.replace("/", os.sep))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        open(dest, "w", encoding="utf-8", newline="").write(res)

    # ---- resource sync: default values := zh-rCN (+ values-only keys kept) ----
    zh = open(os.path.join(WORK, "resolved3", "app/src/main/res/values-zh-rCN/strings.xml"),
              encoding="utf-8").read()
    dv = open(os.path.join(WORK, "resolved3", "app/src/main/res/values/strings.xml"),
              encoding="utf-8").read()
    zh_keys, dv_keys = keys_of(zh), keys_of(dv)
    only_dv = sorted(k for k in dv_keys if k not in zh_keys)
    missing_ref = sorted(k for k in zh_keys if k not in dv_keys)
    # default := zh-rCN, then re-add keys that only exist in default (flagged)
    body = zh.split("?>", 1)[1] if zh.startswith("<?xml") else zh
    extra = []
    for k in only_dv:
        extra.append("    " + dv_keys[k].strip())
    if extra:
        body = body.rstrip()
        assert body.endswith("</resources>")
        body = body[: -len("</resources>")] + "\n".join(extra) + "\n</resources>\n"
    open(os.path.join(WORK, "resolved3", "app/src/main/res/values/strings.xml"),
         "w", encoding="utf-8", newline="").write(body)
    json.dump({"only_in_default": [list(k) for k in only_dv],
               "only_in_zh": [list(k) for k in missing_ref]},
              open(os.path.join(WORK, "values_only_keys.json"), "w", encoding="utf-8"),
              indent=1, ensure_ascii=False)

    json.dump(decisions, open(os.path.join(WORK, "decisions3.json"), "w",
                              encoding="utf-8"), indent=1, ensure_ascii=False)
    resolved = sum(1 for d in decisions.values() if d.get("status") == "resolved-theirs")
    print("hunks resolved (theirs):", hunk_total)
    print("files resolved:", resolved, " deletions:", sum(
        1 for d in decisions.values() if d["status"] == "delete-by-theirs"))
    print("keys only in default (kept+flagged):", [list(k) for k in only_dv])
    print("keys only in zh (missing from default):", [list(k) for k in missing_ref])


if __name__ == "__main__":
    main()
