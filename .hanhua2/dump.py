"""Dump base/ours/theirs trees + conflict inventory for the in-progress merge."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gread import ObjectStore, parse_commit, parse_index, walk_tree, merge_base

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
GIT = os.path.join(ROOT, ".git")
WORK = os.environ.get("HANHUA_WORK") or os.path.join(
    os.environ.get("TEMP", r"C:\Users\wzxcz\AppData\Local\Temp"), "hanhua2-work")


def read_ref(path):
    if os.path.exists(path):
        with open(path) as f:
            return f.read().strip()
    # fall back to packed-refs
    rel = os.path.relpath(path, os.path.join(GIT)).replace(os.sep, "/")
    with open(os.path.join(GIT, "packed-refs")) as f:
        for line in f:
            if line.startswith("#"):
                continue
            sha, name = line.rstrip("\n").split(" ")
            if name == rel:
                return sha
    raise KeyError("ref not found: " + rel)


def dump_tree(store, tree_sha, outdir, skip_prefixes=()):
    files = {}
    for p, mode, sha in walk_tree(store, tree_sha):
        if any(p.startswith(sp) for sp in skip_prefixes):
            continue
        dest = os.path.join(outdir, p.replace("/", os.sep))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        typ, data = store.get(sha)
        with open(dest, "wb") as f:
            f.write(data)
        files[p] = {"sha": sha, "mode": mode}
    return files


def main():
    store = ObjectStore()
    head = read_ref(os.path.join(GIT, "refs", "heads", "main"))
    merge_head = read_ref(os.path.join(GIT, "MERGE_HEAD"))
    print("HEAD:", head)
    print("MERGE_HEAD:", merge_head)
    base = merge_base(store, head, merge_head)
    print("merge-base:", base)
    head_tree = parse_commit(store.get(head)[1])[0]
    upstream_tree = parse_commit(store.get(merge_head)[1])[0]
    base_tree = parse_commit(store.get(base)[1])[0] if base else None

    os.makedirs(WORK, exist_ok=True)
    for name, tsha in (("ours", head_tree), ("theirs", upstream_tree), ("base", base_tree)):
        if tsha is None:
            continue
        outdir = os.path.join(WORK, name)
        files = dump_tree(store, tsha, outdir)
        print(name, "files:", len(files))
        with open(os.path.join(WORK, name + "_files.json"), "w", encoding="utf-8") as f:
            json.dump(files, f, indent=1, sort_keys=True)

    # conflict inventory from index
    entries = parse_index(os.path.join(GIT, "index"))
    conf = {}
    for e in entries:
        if e["stage"] != 0:
            conf.setdefault(e["path"], {})[e["stage"]] = e
    print("conflicted paths (index):", len(conf))
    inv = {}
    for p, stages in sorted(conf.items()):
        inv[p] = {
            "stages": sorted(stages),
            "base": stages.get(1, {}).get("sha"),
            "ours": stages.get(2, {}).get("sha"),
            "theirs": stages.get(3, {}).get("sha"),
            "modes": {str(k): v["mode"] for k, v in stages.items()},
        }
    with open(os.path.join(WORK, "conflicts.json"), "w", encoding="utf-8") as f:
        json.dump(inv, f, indent=1, sort_keys=True)
    # summary of kinds
    kinds = {}
    for p, i in inv.items():
        s = tuple(i["stages"])
        kinds[s] = kinds.get(s, 0) + 1
    print("stage-kind counts:", {str(k): v for k, v in sorted(kinds.items())})


if __name__ == "__main__":
    main()
