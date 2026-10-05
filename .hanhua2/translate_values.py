# -*- coding: utf-8 -*-
"""Translate the keys that exist only in default values/strings.xml (Round-1 policy:
default resource = Chinese). Applied to WORK/resolved3/values/strings.xml; the file is
copied into the workspace in the final batch write-back."""
import json
import os
import re
import xml.etree.ElementTree as ET

WORK = os.environ.get("HANHUA_WORK") or r"D:\DroidDeck\.hanhua2\work"
SRC = r"D:\DroidDeck\app\src\main\res\values\strings.xml"                 # input: workspace
TARGET = os.path.join(WORK, "stage", "app/src/main/res/values/strings.xml")  # output: staged

Z = {
 "app_fex_auto": "自动",
 "app_fex_hint": "FEX 在这台 ARM64 设备上运行 x86 Linux 程序。程序本身为 x86 构建时自动使用它。",
 "app_fex_not_ready": "此程序为 %1$s PC 构建，需要通过 FEX 运行，而 FEX 尚未配置。先在登录状态下打开一次 Steam：DroidDeck 会下载所需的 FEX 与 Steam Linux Runtime，之后即可打开。",
 "app_fex_off": "关闭",
 "app_fex_on": "开启",
 "app_fex_title": "使用 FEX",
 "app_fex_x86": "通过 FEX 运行 %1$s",
 "comp_default_failed": "无法保存默认 Proton：%1$s",
 "comp_default_live": "%1$s 现在会运行所有未在 Steam 中单独指定 Proton 的游戏。",
 "comp_default_next": "从下次 Steam 启动起，%1$s 将运行所有未单独指定 Proton 的游戏。",
 "comp_default_not_runnable": "%1$s 无法在此启动游戏，不能设为默认。",
 "comp_esync_builtin": "%1$s · 内置 esync，无需数据包",
 "comp_esync_pack": "%1$s · droiddeck-esync 数据包",
 "comp_esync_wanted": "%1$s · 尚无 droiddeck-esync 数据包",
 "display_filter": "缩放滤镜",
 "display_filter_hint": "Steam 与桌面共用。",
 "display_filter_live": "立即生效。Steam 与桌面共用。",
 "display_filter_note": "放大会话画面时应用 FSR、GSR 与 NIS。仅锐化在不放大时也有效。",
 "display_force_fullscreen": "强制游戏窗口全屏",
 "display_force_fullscreen_hint": "焦点变化后保持游戏全屏。画面显示偏小时请关闭。会话中即时生效。",
 "display_force_fullscreen_live": "焦点变化后保持游戏全屏。即时生效。",
 "display_frame_rate": "帧率",
 "display_game_fit": "16:9 游戏适配",
 "display_game_fit_hint": "16:9 游戏如何适配此显示。下次会话生效。",
 "display_game_fit_unavailable": "请选择“匹配屏幕”或窄于 16:9 的自定义尺寸。",
 "display_image_scaling": "图像缩放",
 "display_look_filter": "%1$s 将缩放设置为 %2$s。",
 "display_match_screen": "匹配屏幕（%1$d×%2$d）",
 "display_preserve": "保持比例",
 "display_resolution": "分辨率",
 "display_session": "会话显示",
 "display_sharpness": "滤镜锐度",
 "display_stretch": "拉伸到屏幕",
 "display_window_compatibility": "游戏窗口兼容性",
 "drawer_anisotropy": "各向异性过滤",
 "drawer_brightness": "亮度",
 "drawer_cas": "锐化（CAS）",
 "drawer_cas_level": "锐化强度",
 "drawer_contrast": "对比度",
 "drawer_crt": "CRT",
 "drawer_deband": "去色带",
 "drawer_deband_strength": "去色带强度",
 "drawer_effects": "屏幕效果",
 "drawer_effects_advanced": "高级",
 "drawer_effects_hint": "即时生效。任一效果开启时会占用少量 GPU 时间。",
 "drawer_fake_hdr": "伪 HDR",
 "drawer_fxaa": "FXAA（边缘平滑）",
 "drawer_gamma": "伽马",
 "drawer_look": "预设",
 "drawer_look_custom": "自定义",
 "drawer_ntsc": "NTSC",
 "drawer_page_controller": "手柄",
 "drawer_page_effects": "效果",
 "drawer_saturation": "饱和度",
 "drawer_scaling": "缩放模式",
 "drawer_scaling_sharpness": "缩放锐度",
 "drawer_second_screen_mode": "模式",
 "drawer_texture": "纹理过滤",
 "drawer_texture_hint": "DirectX 9–11 游戏，自下次启动起。",
 "drawer_texture_sharpness": "纹理锐度",
 "drawer_texture_sharpness_note": "负 mip 偏移：纹理更清晰，略有闪烁。自动匹配画面相对屏幕放大的倍数。",
 "drawer_toon": "卡通描边",
 "game_env_fsync_first": "设为 1 让本游戏使用 droiddeck-fsync；设为 0 则在数据包支持其 Proton 时优先使用 droiddeck-esync。",
 "game_env_no_esync": "设为 1 对本游戏关闭 droiddeck-esync；改用 droiddeck-fsync，fsync 也关闭时使用 droiddeck-ntsync。",
 "game_env_no_fsync": "设为 1 对本游戏禁用 droiddeck-fsync；有数据包时使用 droiddeck-esync，否则使用 droiddeck-ntsync。",
 "game_env_no_ntsync": "设为 1 对本游戏禁用 ntsync（droiddeck-ntsync 或内核驱动）；有数据包时使用 droiddeck-esync，否则使用 droiddeck-fsync。",
 "mode_steam_repair": "修复 Steam 客户端",
 "mode_steam_repair_button": "修复",
 "mode_steam_repair_hint": "下次 Steam 启动时重新下载 Steam 客户端。Steam 一直卡在加载界面时使用。游戏、登录状态与设置都会保留。",
 "mode_steam_repair_queued": "下次 Steam 启动时将重新下载 Steam 客户端。",
 "mode_storage_diagnostics": "存储诊断",
 "mode_storage_diagnostics_hint": "在“分享日志”中记录分配结果与采样存储耗时。开启期间可能减慢安装速度。下次会话生效。",
 "mode_tab_steam": "Steam",
 "perf_esync_hint": "游戏的 Windows 线程通过 eventfd 和共享内存互相等待，而不是经 wineserver 往返。使用为该 Proton 构建专门制作的 droiddeck-esync 数据包；数据包会自动获取。在新 Proton 构建的数据包出现之前，由 droiddeck-fsync 顶替。下次会话生效。",
 "perf_fastsync": "droiddeck-ntsync（实验性）",
 "perf_fastsync_hint": "用于测试的用户态 Linux ntsync 驱动替代实现。开启后，Proton 会用它替代 droiddeck-esync 与 droiddeck-fsync。支持 Proton Experimental ARM64、GE-Proton 与 proton-cachyos。下次会话生效。",
 "perf_fsync": "droiddeck-fsync 优先",
 "perf_fsync_hint": "在原版 Proton 文件上运行 Proton 自带的 fsync，由用户态提供 futex_waitv，因此无需数据包，且 Steam 更新 Proton 后立即有效。开启后，游戏优先使用它而非 droiddeck-esync，Proton 明确关闭 fsync 的少数情况除外。下次会话生效。",
 "proton_compatible_note": "Steam 兼容性菜单中标记为“%1$s”的构建带有 DroidDeck 的前缀修复，可在此设备启动游戏。默认为 Proton Experimental ARM64（%1$s），安装该更新构建后为 Proton 11.0 ARM64（%1$s）。没有该标记的 Valve 官方 Proton 条目无法在此设备启动游戏。",
 "proton_installed_compatible": "已安装 %1$s · %2$s",
 "session_end_fex_missing": "%1$s 为 x86 PC 构建，需通过尚未安装的 FEX 运行。先在登录状态下启动一次 Steam，让其下载 FEX 与 Steam Linux Runtime，然后再启动本程序。",
 "session_end_program": "此程序",
 "steam_compat_label": "兼容",
 "sync_backend_hint": "游戏如何保持线程同步。下次会话生效。",
 "sync_backend_title": "Wine 线程同步",
 "widgets_collapsed": "已折叠",
 "widgets_expanded": "已展开",
}
# deliberately left English (technical names / protocol values):
KEEP_EN = {"perf_esync": "droiddeck-esync", "sync_backend_esync": "esync",
           "sync_backend_fsync": "fsync", "sync_backend_ntsync": "ntsync",
           "sync_backend_wineserver": "wineserver"}


def esc(v):
    return v.replace("&", "&amp;").replace("<", "&lt;")


def main():
    txt = open(SRC, encoding="utf-8").read()
    only_p = os.path.join(WORK, "values_only_keys.json")
    if os.path.exists(only_p):
        only = json.load(open(only_p, encoding="utf-8"))["only_in_default"]
        keys = [k[1] for k in only if k[0] == "string"]
    else:
        keys = sorted(set(Z) | set(KEEP_EN))
    missing = [k for k in keys if k not in Z and k not in KEEP_EN]
    if missing:
        print("NO TRANSLATION FOR:", missing)
    n = 0
    os.makedirs(os.path.dirname(TARGET), exist_ok=True)
    for k, v in list(Z.items()) + list(KEEP_EN.items()):
        pat = re.compile(r'(<string name="%s">)(.*?)(</string>)' % re.escape(k), re.S)
        m = pat.search(txt)
        if not m:
            print("KEY NOT FOUND:", k)
            continue
        new = esc(v)
        if m.group(2) != new:
            txt = txt[:m.start()] + m.group(1) + new + m.group(3) + txt[m.end():]
            n += 1
    open(TARGET, "w", encoding="utf-8", newline="").write(txt)
    # validate
    root = ET.fromstring(txt)
    eng = []
    for el in root:
        if el.tag == "string" and el.text and re.search(r"[A-Za-z]{2,}\s+[A-Za-z]{2,}", el.text) \
                and not re.search(r"[一-鿿]", el.text):
            eng.append(el.get("name"))
    print("replaced:", n, "remaining english text values:", eng)
    print("keys of only-in-default not seen:", [k for k in keys if ('<string name="%s">' % k) not in txt])


if __name__ == "__main__":
    main()
