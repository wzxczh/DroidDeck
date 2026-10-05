"""Create per-subagent task list files in WORK/tasks/."""
import json
import os

WORK = os.environ.get("HANHUA_WORK") or r"C:\Users\wzxcz\AppData\Local\Temp\dsh-StYPwy\hanhua2-work"
B = json.load(open(os.path.join(WORK, "delta", "buckets.json"), encoding="utf-8"))


def pick(bucket, pred=None, exclude=()):
    out = [i["path"] for i in B.get(bucket, [])
           if (pred is None or pred(i)) and i["path"] not in exclude]
    return out


K = {i["path"]: i for i in B.get("kotlin", [])}
C = {i["path"]: i for i in B.get("cpp", [])}
S = {i["path"]: i for i in B.get("scripts", [])}
D = {i["path"]: i for i in B.get("docs", [])}
T = {i["path"]: i for i in B.get("tests", [])}

def kp(*prefixes, extra=()):
    return [p for p in K if p.startswith(prefixes) or p in extra]

tasks = {}
# ---- kotlin by area (tests bundled with their source) ----
tasks["K1_session"] = kp("app/src/main/java/com/droiddeck/launcher/session/",
                         extra=("app/src/main/java/com/droiddeck/launcher/ComponentsMenu.kt",))
tasks["K2_ui"] = kp("app/src/main/java/com/droiddeck/launcher/ui/")
tasks["K3_runtime"] = kp("app/src/main/java/com/droiddeck/launcher/runtime/",
                         "app/src/main/java/com/droiddeck/launcher/store/",
                         "app/src/main/java/com/droiddeck/launcher/update/")
tasks["K4_core_frontend"] = kp("app/src/main/java/com/droiddeck/launcher/core/",
                               "app/src/main/java/com/droiddeck/launcher/frontend/")
tasks["K5_gpu_input_wayland"] = kp("app/src/main/java/com/droiddeck/launcher/gpu/",
                                   "app/src/main/java/com/droiddeck/launcher/input/",
                                   "app/src/main/java/com/droiddeck/launcher/wayland/")
tasks["K6_root"] = [p for p in K if "/session/" not in p and "/ui/" not in p and
                    "/runtime/" not in p and "/store/" not in p and "/update/" not in p and
                    "/core/" not in p and "/frontend/" not in p and "/gpu/" not in p and
                    "/input/" not in p and "/wayland/" not in p]

# ---- app tests not bundled above ----
TAPP = [p for p in T if p.startswith("app/src/test/")]
tasks["K6_root"] += [p for p in TAPP if p not in tasks["K1_session"] + tasks["K2_ui"] +
                     tasks["K3_runtime"] + tasks["K4_core_frontend"] + tasks["K5_gpu_input_wayland"]]
for k in list(tasks):
    tasks[k] = sorted(set(tasks[k]))

# ---- cpp ----
tasks["C1_wayland_big"] = sorted(p for p in C if any(x in p for x in (
    "wl_color_mgmt.c", "compositor.c", "sc_layer.c", "vk_present.c", "ahb_swapchain.c")))
tasks["C2_wayland_rest"] = sorted(p for p in C if p not in tasks["C1_wayland_big"] and
                                  p.startswith("app/src/main/cpp/"))
tasks["C3_tools"] = sorted(p for p in C if not p.startswith("app/src/main/cpp/"))

# ---- scripts ----
core_scripts = {p for p in S if any(x in p for x in (
    "droiddeck-session", "droiddeck-steam-", "steam-compatibility"))}
tasks["S1_core_session"] = sorted(core_scripts)
tasks["S2_overlay_rest"] = sorted(p for p in S if p not in core_scripts and
                                  p.startswith("tools/linuxfs/"))
tasks["S3_tools_dev"] = sorted(p for p in S if not p.startswith("tools/linuxfs/") or
                               p == "tools/linuxfs/licenses/uruntime-LICENSE")
tasks["S3_tools_dev"] = sorted(set(tasks["S3_tools_dev"]) |
                               {p for p in S if p.startswith("tools/release/") or
                                p.startswith("tools/proot/") or p.startswith("tools/build")})

# ---- docs ----
tasks["D1_devdocs"] = sorted(p for p in D if p.startswith("docs/development/"))
tasks["D2_rest_docs"] = sorted(p for p in D if p not in tasks["D1_devdocs"])

# ---- tools tests (run AFTER scripts) ----
tasks["T1_pytest"] = sorted(p for p in T if p.startswith("tools/tests/"))

os.makedirs(os.path.join(WORK, "tasks"), exist_ok=True)
for name, files in tasks.items():
    with open(os.path.join(WORK, "tasks", name + ".txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(files))
    eng = sum((K.get(p) or C.get(p) or S.get(p) or D.get(p) or T.get(p) or {}).get("eng", 0)
              for p in files)
    print(f"{name:20s} files={len(files):3d} eng~{eng:5d}")
# leftovers check
allp = set(K) | set(C) | set(S) | set(D) | set(T)
assigned = set(sum(tasks.values(), []))
print("UNASSIGNED:", sorted(allp - assigned))
