# -*- coding: utf-8 -*-
"""Coordinator direct-work batch: exact ordinal replacements for session-layer leftovers.

Two-phase: (1) verify every (file, old, new) matches exactly once; (2) write all files.
Aborts before writing if any match is missing or ambiguous.
"""
import io
import os
import sys

ROOT = r"D:\DroidDeck"
B = "app/src/main/java/com/droiddeck/launcher/"

# (relpath, old, new)
FIXES = [
    # SessionPrefs labels (already applied individually; listed for audit only) -- none here
    # SecondaryLibrary
    (B + "session/SecondaryLibrary.kt", '"cannot create ${target.parent}"', '"无法创建 ${target.parent}"'),
    (B + "session/SecondaryLibrary.kt", '"cannot create $target"', '"无法创建 $target"'),
    (B + "session/SecondaryLibrary.kt", '"cannot clear $stage"', '"无法清空 $stage"'),
    (B + "session/SecondaryLibrary.kt", '"cannot create $stage"', '"无法创建 $stage"'),
    (B + "session/SecondaryLibrary.kt", '"cannot finish migration to $target"', '"无法完成到 $target 的迁移"'),
    # ProtonDefault
    (B + "session/ProtonDefault.kt", '"could not write the Proton request"', '"无法写入 Proton 请求"'),
    # ProtonExtras (159 must equal EsyncPacks.PROGRESS_LABEL)
    (B + "session/ProtonExtras.kt", '"Fetching droiddeck-esync pack"', '"正在获取 droiddeck-esync 包"'),
    (B + "session/ProtonExtras.kt", 'Log.w(TAG, "sync pack for ${tool.name}", t)',
     'Log.w(TAG, "获取 ${tool.name} 的同步数据包失败", t)'),
    # GpuClockPin
    (B + "session/GpuClockPin.kt", '"gpu clock: governed"', '"GPU 时钟：已调速"'),
    (B + "session/GpuClockPin.kt", '"gpu clock: no $KGSL_NODE, left governed"', '"GPU 时钟：无 $KGSL_NODE，保持调速"'),
    (B + "session/GpuClockPin.kt", '"gpu clock: held at its top"', '"GPU 时钟：已锁定最高频"'),
    (B + "session/GpuClockPin.kt", '"gpu clock: pin failed"', '"GPU 时钟：锁定失败"'),
    # GpuMemComponent
    (B + "session/GpuMemComponent.kt",
     '"hud: gpu memory from $source (${bytes / (1024 * 1024)} MiB now)"',
     '"hud: GPU 显存取自 $source（当前 ${bytes / (1024 * 1024)} MiB）"'),
    (B + "session/GpuMemComponent.kt", 'source = "/proc/<pid>/maps (KGSL mappings)"',
     'source = "/proc/<pid>/maps（KGSL 映射）"'),
    # GpuStatsComponent
    (B + "session/GpuStatsComponent.kt", '"hud: kgsl stats from the session\'s own: "',
     '"hud: kgsl 统计取自会话自带节点："'),
    (B + "session/GpuStatsComponent.kt", '"hud: could not stand in for kgsl stats: $e"',
     '"hud: 无法替代 kgsl 统计：$e"'),
    # HwmonComponent
    (B + "session/HwmonComponent.kt",
     '"hud: hwmon cpu temp ${cpu ?: "none"}, gpu temp ${gpu ?: "none"}, fan ${fanSource ?: "none"}"',
     '"hud: hwmon CPU 温度 ${cpu ?: "无"}，GPU 温度 ${gpu ?: "无"}，风扇 ${fanSource ?: "无"}"'),
    (B + "session/HwmonComponent.kt", '"hud: could not write the session\'s hwmon: $e"',
     '"hud: 无法写入会话的 hwmon：$e"'),
    # CpuStatComponent
    (B + "session/CpuStatComponent.kt",
     '"hud: /proc/stat from the cores\' idle times (${cores.size} cores)"',
     '"hud: /proc/stat 取自各核心空闲时间（${cores.size} 核）"'),
    # SessionArtifacts
    (B + "session/SessionArtifacts.kt",
     '"deleted ${old.size} session folder(s) past the newest ${SessionPaths.KEEP_SESSIONS}"',
     '"已删除超出保留范围的 ${old.size} 个会话文件夹（只保留最新 ${SessionPaths.KEEP_SESSIONS} 个）"'),
    (B + "session/SessionArtifacts.kt", '"cleared ${gone.size} session folder(s)"',
     '"已清理 ${gone.size} 个会话文件夹"'),
    # SessionFiles
    (B + "session/SessionFiles.kt", '"could not write steam-compatibility-label"',
     '"无法写入 steam-compatibility-label"'),
    # SessionPaths
    (B + "session/SessionPaths.kt", '"could not create $made"', '"无法创建 $made"'),
    # RumbleComponent
    (B + "session/RumbleComponent.kt", '"rumble: no listener ($e)"', '"震动：无监听端（$e）"'),
    # ComponentsManager (label/description are display; the FILE prefix "original " stays)
    (B + "session/ComponentsManager.kt",
     '.put("description", "Original · ${p.name} · ${p.version} · ${LABEL.getValue(comp)} $current".trim())',
     '.put("description", "原始 · ${p.name} · ${p.version} · ${LABEL.getValue(comp)} $current".trim())'),
    (B + "session/ComponentsManager.kt", '.put("label", "Original $protonVersion")',
     '.put("label", "原始 $protonVersion")'),
    # SessionService audio log (upstream added the mic clauses)
    (B + "session/SessionService.kt", '""" + microphone" else "")''', None),  # placeholder, handled below
]


def main():
    # drop placeholder entries
    fixes = [f for f in FIXES if f[2] is not None]
    # special: SessionService mic log (contains both new pieces on adjacent lines)
    ss_old = ('+ (if (wantsMic) " + microphone" else "") +\n'
              '            (if (SessionPrefs.micEnabled(this) && !wantsMic) " (microphone wanted but RECORD_AUDIO not granted)" else ""))')
    ss_new = ('+ (if (wantsMic) " + 麦克风" else "") +\n'
              '            (if (SessionPrefs.micEnabled(this) && !wantsMic) "（已请求麦克风但未授予 RECORD_AUDIO）" else ""))')
    fixes.append((B + "session/SessionService.kt", ss_old, ss_new))

    # phase 1: verify
    by_file = {}
    ok = True
    for rel, old, new in fixes:
        p = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.exists(p):
            print("MISSING FILE:", rel); ok = False; continue
        by_file.setdefault(rel, []).append((old, new))
    texts = {}
    for rel, pairs in by_file.items():
        p = os.path.join(ROOT, rel.replace("/", os.sep))
        t = io.open(p, encoding="utf-8", newline="").read()
        texts[rel] = t
        for old, new in pairs:
            c = t.count(old)
            if c != 1:
                print(f"FAIL count={c}: {rel} :: {old[:70]!r}")
                ok = False
    if not ok:
        print("ABORTED (nothing written)")
        return 1
    # phase 2: write
    for rel, pairs in by_file.items():
        t = texts[rel]
        for old, new in pairs:
            t = t.replace(old, new, 1)
        p = os.path.join(ROOT, rel.replace("/", os.sep))
        io.open(p, "w", encoding="utf-8", newline="").write(t)
        print(f"WROTE {rel}: {len(pairs)} replacement(s)")
    print("ALL OK:", sum(len(v) for v in by_file.values()), "replacements in", len(by_file), "files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
