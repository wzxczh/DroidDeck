# -*- coding: utf-8 -*-
"""Direct-work batch 2: guest-script user messages (+ pytest assertion sync).

Each entry: (relpath, old, new, expected_count). Two-phase: verify all, then write all.
Kept English on purpose (asserted/protocol, not in this table):
  steam-install:9 (channel), steam-install repair/STEP lines, droiddeck-session step/== headers,
  appimage-run ==-lines (2 tokens asserted), game-env:232 could not start, esync say() family,
  netmanager/login1 D-Bus names, HTTP/JS strings.
"""
import io
import os
import sys

ROOT = r"D:\DroidDeck"
S = "tools/linuxfs/overlay/usr/local/bin/"
T = "tools/tests/"

FIXES = [
    # -- steam-compatibility (DirectAudio messages; "DirectAudio selected" synced in test) --
    (S + "steam-compatibility", '"calling CDS: failed, bad mode"', '"调用 CDS：失败，模式无效"', 1),
    (S + "steam-compatibility",
     '"droiddeck-proton: DirectAudio left off - it is built for Wine 11 and this Proton is \'${version:-unknown}\'. Pairing them would be silent rather than broken, so it is not worth guessing."',
     '"droiddeck-proton：未启用 DirectAudio——它为 Wine 11 构建，而此 Proton 是 \'${version:-unknown}\'。两者搭配只会无声而非损坏，不值得冒险。"', 1),
    (S + "steam-compatibility",
     '"droiddeck-proton: DirectAudio could not be placed in the prefix; Proton\'s own audio stays"',
     '"droiddeck-proton：无法把 DirectAudio 放入前缀；保持 Proton 自带音频"', 1),
    (S + "steam-compatibility", '"droiddeck-proton: DirectAudio selected in the prefix"',
     '"droiddeck-proton：已在前缀中选择 DirectAudio"', 1),
    (S + "steam-compatibility",
     '"droiddeck-proton: no prefix yet - DirectAudio takes effect the next time this game starts"',
     '"droiddeck-proton：尚无前缀——DirectAudio 将在下次启动此游戏时生效"', 1),
    (S + "steam-compatibility", '"droiddeck-proton: DirectAudio ready (Wine $major)"',
     '"droiddeck-proton：DirectAudio 就绪（Wine $major）"', 1),
    (S + "steam-compatibility", '"unknown appinfo field type %d"', '"未知的 appinfo 字段类型 %d"', 1),
    # -- test sync for the message above --
    (T + "test_game_environment.py", '"DirectAudio selected"', '"已选择 DirectAudio"', 4),
    # -- droiddeck-steam-compat --
    (S + "droiddeck-steam-compat", '"the client\'s transport refused the connection"',
     '"客户端传输层拒绝了连接"', 1),
    (S + "droiddeck-steam-compat", '"the client\'s transport refused authentication ("',
     '"客户端传输层拒绝了认证（"', 1),
    # -- droiddeck-game-env --
    (S + "droiddeck-game-env", '".NET runtime: FEX with full TSO and without multiblock"',
     '".NET 运行时：FEX 全量 TSO、关闭 multiblock"', 1),
    (S + "droiddeck-game-env", '"Godot: Vulkan renderer"', '"Godot：Vulkan 渲染器"', 1),
    (S + "droiddeck-game-env", '"Godot: Sentry crash handler off"', '"Godot：关闭 Sentry 崩溃处理"', 1),
    (S + "droiddeck-game-env", '"droiddeck-game-env: sync selection failed (%s); stock launch"',
     '"droiddeck-game-env：同步选择失败（%s）；使用原版启动"', 2),
    (S + "droiddeck-game-env", '"droiddeck-game-env: stock launch not recorded (%s)"',
     '"droiddeck-game-env：未记录原版启动（%s）"', 1),
    (S + "droiddeck-game-env", '"droiddeck-game-env: invalid configuration; using inherited environment"',
     '"droiddeck-game-env：配置无效；使用继承的环境"', 1),
    # -- droiddeck-proton-extra --
    (S + "droiddeck-proton-extra", '"droiddeck-proton-extra: %s has no ARM64 asset"',
     '"droiddeck-proton-extra：%s 没有 ARM64 资产"', 1),
    (S + "droiddeck-proton-extra", '"droiddeck-proton-extra: download failed (curl %d)"',
     '"droiddeck-proton-extra：下载失败（curl %d）"', 1),
    (S + "droiddeck-proton-extra", '"droiddeck-proton-extra: checksum mismatch; the download was deleted"',
     '"droiddeck-proton-extra：校验和不符；已删除下载的文件"', 1),
    (S + "droiddeck-proton-extra", '"droiddeck-proton-extra: tar failed (%d)"',
     '"droiddeck-proton-extra：解包失败（%d）"', 1),
    (S + "droiddeck-proton-extra", '"droiddeck-proton-extra: no Proton tree in the archive"',
     '"droiddeck-proton-extra：压缩包里没有 Proton 目录树"', 1),
    (S + "droiddeck-proton-extra", '"usage: droiddeck-proton-extra <steam root> ge|cachyos|<url>"',
     '"用法：droiddeck-proton-extra <steam root> ge|cachyos|<url>"', 1),
    (S + "droiddeck-proton-extra", '"droiddeck-proton-extra: no ARM64 build published for %s"',
     '"droiddeck-proton-extra：%s 未发布 ARM64 构建"', 1),
    (S + "droiddeck-proton-extra", '"droiddeck-proton-extra: expected ge, cachyos, a URL or a tarball path, got %r"',
     '"droiddeck-proton-extra：需要 ge、cachyos、URL 或压缩包路径，收到 %r"', 1),
    (S + "droiddeck-proton-extra", '"droiddeck-proton-extra: needs about %d GB free, has %.1f GB"',
     '"droiddeck-proton-extra：约需 %d GB 可用空间，当前有 %.1f GB"', 1),
    # -- droiddeck-login1 --
    (S + "droiddeck-login1", '"== login1: cannot complete suspend handshake: "',
     '"== login1：无法完成挂起握手："', 1),
    (S + "droiddeck-login1", '"== login1: cannot request pause: "',
     '"== login1：无法请求暂停："', 1),
    (S + "droiddeck-login1", '"droiddeck-login1: no DroidDeck session directory"',
     '"droiddeck-login1：没有 DroidDeck 会话目录"', 1),
    # -- droiddeck-flatpak usage --
    (S + "droiddeck-flatpak", '"usage: droiddeck-flatpak install|uninstall|update|updates [app-id]\\n"',
     '"用法：droiddeck-flatpak install|uninstall|update|updates [app-id]\\n"', 2),
    # -- droiddeck-appimage-run (only :17; ==-lines kept) --
    (S + "droiddeck-appimage-run", '"droiddeck-appimage-run: $APPDIR/AppRun is missing; import the AppImage again"',
     '"droiddeck-appimage-run：$APPDIR/AppRun 缺失，请重新导入该 AppImage"', 1),
    # -- droiddeck-desktop-games --
    (S + "droiddeck-desktop-games", "Comment=Steam game %d - opens in Steam under gamescope\\n",
     "Comment=Steam 游戏 %d —— 在 gamescope 下经 Steam 打开\\n", 1),
    (S + "droiddeck-desktop-games", '"droiddeck-desktop-games: %d entr%s of ours, %d the client\'s"',
     '"droiddeck-desktop-games：我们的条目 %d 个，客户端条目 %d 个"', 1),
    # -- droiddeck-seed-redists --
    (S + "droiddeck-seed-redists", '"== seeded redist markers into $reg"',
     '"== 已把 redist 标记写入 $reg"', 1),
    # -- droiddeck-script-run --
    (S + "droiddeck-script-run", '"droiddeck-script-run: $dir has no entry"',
     '"droiddeck-script-run：$dir 没有对应条目"', 1),
    (S + "droiddeck-script-run", '"droiddeck-script-run: $dir/files is missing; add the script again"',
     '"droiddeck-script-run：$dir/files 缺失；请重新添加脚本"', 1),
    (S + "droiddeck-script-run", '"== script $entry in $folder (${interp[*]}, FEX $fex)"',
     '"== 脚本 $entry，位于 $folder（${interp[*]}，FEX $fex）"', 1),
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
        p = os.path.join(ROOT, rel.replace("/", os.sep))
        t = io.open(p, encoding="utf-8", newline="").read()
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
        p = os.path.join(ROOT, rel.replace("/", os.sep))
        io.open(p, "w", encoding="utf-8", newline="").write(t)
        print(f"WROTE {rel}: {len(pairs)} rule(s)")
    print("ALL OK:", total, "replacements in", len(by_file), "files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
