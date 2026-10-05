# -*- coding: utf-8 -*-
"""Direct-work batch 3: last guest-script stragglers (pad-defaults/fex/netmanager/shortcuts).

Kept English on purpose (verified): pad-defaults RPCS3 config values (Shader Mode/Async
Recomposer matched inside RPCS3's own config file), fex say-prefix identity, flatpak-setup
step "Adding Flathub" (STEP protocol), steam-library "Local Drive (/)" (value Steam reads),
netmanager D-Bus interface names, ui-scale/gamescope arg values.
"""
import io
import os
import sys

ROOT = r"D:\DroidDeck"
S = "tools/linuxfs/overlay/usr/local/bin/"

FIXES = [
    (S + "droiddeck-pad-defaults", '"droiddeck-pad-defaults: {what}: {done} ({path})"',
     '"droiddeck-pad-defaults：{what}：{done}（{path}）"', 2),
    (S + "droiddeck-fex",
     'say("could not ask the client to install FEX (%s)" % error)',
     'say("无法请求客户端安装 FEX（%s）" % error)', 1),
    (S + "droiddeck-fex",
     'say("FEX is installed at %s but holds no FEX or FEXInterpreter in usr/bin, bin or files; it has: %s"',
     'say("FEX 已安装在 %s，但 usr/bin、bin 或 files 里没有 FEX 或 FEXInterpreter；现有内容：%s"', 1),
    (S + "droiddeck-fex", 'missing.append("FEX (Steam app %s)" % FEX_APPID)',
     'missing.append("FEX（Steam 应用 %s）" % FEX_APPID)', 1),
    (S + "droiddeck-fex", 'missing.append("FEXServer beside %s" % fex["bin"])',
     'missing.append("FEXServer（在 %s 旁）" % fex["bin"])', 1),
    (S + "droiddeck-netmanager",
     'return_dbus_error(NAME + ".NotSupported", "Manage this connection in Android settings")',
     'return_dbus_error(NAME + ".NotSupported", "请在 Android 设置中管理此连接")', 1),
    (S + "droiddeck-steam-shortcuts", 'raise ValueError("unknown vdf type 0x%02x" % t)',
     'raise ValueError("未知的 vdf 类型 0x%02x" % t)', 1),
]


def main():
    by_file = {}
    ok = True
    for rel, old, new, cnt in FIXES:
        p = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.exists(p):
            print("MISSING FILE:", rel); ok = False; continue
        by_file.setdefault(rel, []).append((old, new, cnt))
    texts = {}
    for rel, pairs in by_file.items():
        t = io.open(os.path.join(ROOT, rel.replace("/", os.sep)), encoding="utf-8", newline="").read()
        texts[rel] = t
        for old, new, cnt in pairs:
            c = t.count(old)
            if c != cnt:
                print(f"FAIL count={c} (want {cnt}): {rel} :: {old[:80]!r}")
                ok = False
    if not ok:
        print("ABORTED (nothing written)")
        return 1
    total = 0
    for rel, pairs in by_file.items():
        t = texts[rel]
        for old, new, cnt in pairs:
            t = t.replace(old, new)
            total += cnt
        io.open(os.path.join(ROOT, rel.replace("/", os.sep)), "w", encoding="utf-8", newline="").write(t)
        print(f"WROTE {rel}: {len(pairs)} rule(s)")
    print("ALL OK:", total, "replacements in", len(by_file), "files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
