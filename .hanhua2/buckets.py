"""Bucket the Phase-4 translation worklist for subagent dispatch."""
import json
import os

WORK = os.environ.get("HANHUA_WORK") or r"C:\Users\wzxcz\AppData\Local\Temp\dsh-StYPwy\hanhua2-work"

SKIP_PREFIX = (".github/",)
SKIP_FILES = {
    # upstream locale resources -- not English, out of scope
    "app/src/main/res/values-ja/strings.xml",
    "app/src/main/res/values-ko/strings.xml",
    "app/src/main/res/values-es/strings.xml",
    "app/src/main/res/values-zh-rHK/strings.xml",
    "app/src/main/res/values-zh-rTW/strings.xml",
    # our own planning files (handled by coordinator)
    "task_plan.md", "findings.md", "progress.md", "phase6-loading-pipeline.md",
}
SKIP_SUFFIX = (".gradle",)


def bucket(path):
    if path.startswith(SKIP_PREFIX) or path in SKIP_FILES or path.endswith(SKIP_SUFFIX):
        return None
    if path.startswith("app/src/main/res/"):
        return "resources"
    if path.startswith("app/src/test/") or path.startswith("tools/tests/"):
        return "tests"
    if path.startswith("app/src/main/java/") or path.startswith("app/src/main/kotlin/"):
        return "kotlin"
    if path.startswith("app/src/main/cpp/") or path.endswith((".c", ".cpp", ".h", ".hpp")):
        return "cpp"
    if path.startswith("tools/linuxfs/") or path.startswith("tools/") and not path.endswith(".md"):
        if path.endswith(".md"):
            return "docs"
        return "scripts"
    if path.endswith(".md"):
        return "docs"
    if path.endswith((".xml", ".yml", ".yaml", ".txt", ".properties", ".json", ".pro")):
        return "misc"
    return "misc"


def main():
    d = json.load(open(os.path.join(WORK, "delta", "files.json"), encoding="utf-8"))
    buckets = {}
    for r in d["changed"]:
        b = bucket(r["path"])
        if b:
            buckets.setdefault(b, []).append(
                {"path": r["path"], "eng": r["eng"], "added": r["added"], "kind": "changed"})
    for r in d["new"]:
        b = bucket(r["path"])
        if b:
            buckets.setdefault(b, []).append(
                {"path": r["path"], "eng": r["eng_lines"], "added": None, "kind": "new"})
    for b, items in sorted(buckets.items()):
        items.sort(key=lambda x: -(x["eng"] or 0))
    json.dump(buckets, open(os.path.join(WORK, "delta", "buckets.json"), "w",
                            encoding="utf-8"), indent=1, ensure_ascii=False)
    for b, items in sorted(buckets.items()):
        tot = sum(i["eng"] or 0 for i in items)
        print(f"{b:10s} files={len(items):3d} eng={tot:5d}")
        for i in items:
            print(f"    {i['eng']:4d}  {i['kind']:7s} {i['path']}")


if __name__ == "__main__":
    main()
