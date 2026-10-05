/*
 * wp_color_manager_v1 (wayland-protocols staging color-management-v1, version 1) - the subset Mesa's
 * Wayland WSI binds - plus the HDR gate, the per-surface image descriptions and the session log's
 * HDR evidence. See droiddeck_color.h for the design and HDR_RECON.md for why it is shaped this way.
 *
 * Strictness policy. A protocol error disconnects the client, and the client here is a GAME, so
 * errors are only raised where the protocol leaves no choice and a conforming client can never hit
 * them: a request gated on a feature we did not advertise, a property set twice, an incomplete
 * parameter set, a description that is not ready, an intent we did not advertise. Everything a
 * conforming client CAN produce on a bad day is accepted and logged instead: out-of-range HDR
 * metadata is dropped (Android gets none rather than nonsense), a second colour-management object
 * for the same surface replaces the first, a request on an inert object is ignored.
 */
#define _GNU_SOURCE
#include "droiddeck_color.h"
#include "droiddeck_ext.h"
#include "ahb_swapchain.h"
#include "sc_layer.h"
#include "color-management-v1-server-protocol.h"
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

extern volatile int g_zero_copy;

#define TAG "color"

/* AHARDWAREBUFFER_FORMAT_* (android/hardware_buffer.h), for the log only. */
const char *droiddeck_ahb_format_name(uint32_t f) {
    static char other[32];
    switch (f) {
    case 1: return "RGBA8888 (8-bit)";
    case 2: return "RGBX8888 (8-bit)";
    case 3: return "RGB888 (8-bit)";
    case 4: return "RGB565";
    case 0x16: return "RGBA16F (16-bit float)";
    case 0x2b: return "RGBA1010102 (10-bit)";
    default: snprintf(other, sizeof(other), "format %#x", f); return other;
    }
}

static int64_t now_ns(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static const char *primaries_name(uint32_t p) {
    switch (p) {
    case WP_COLOR_MANAGER_V1_PRIMARIES_SRGB: return "BT.709/sRGB";
    case WP_COLOR_MANAGER_V1_PRIMARIES_BT2020: return "BT.2020";
    case WP_COLOR_MANAGER_V1_PRIMARIES_DISPLAY_P3: return "Display P3";
    case WP_COLOR_MANAGER_V1_PRIMARIES_DCI_P3: return "DCI-P3";
    case WP_COLOR_MANAGER_V1_PRIMARIES_ADOBE_RGB: return "Adobe RGB";
    default: return "其它原色";
    }
}

static const char *tf_name(uint32_t tf) {
    switch (tf) {
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ: return "ST 2084 (PQ)";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_HLG: return "HLG";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_SRGB: return "sRGB";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_EXT_LINEAR: return "扩展线性";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_GAMMA22: return "gamma 2.2";
    case WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_BT1886: return "BT.1886";
    default: return "其它传输函数";
    }
}

static const char *dataspace_name(int32_t ds) {
    switch (ds) {
    case DROIDDECK_ADATASPACE_BT2020_PQ: return "BT2020_PQ";
    case DROIDDECK_ADATASPACE_BT2020_HLG: return "BT2020_HLG";
    case DROIDDECK_ADATASPACE_UNKNOWN: return "UNKNOWN (sRGB)";
    default: return "其它";
    }
}

/* ---------------------------------------------------------------- request (app) + gate */

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER; /* g_req, g_hdr (app threads + compositor) */

static struct {
    int mode;                       /* 0 off, 1 on, 2 forced */
    char source[64];
    int dxvk_hdr, zero_copy_forced;
    int display_known;
    int display_id, hdr10, ratio_available, api;
    char display_name[96], formats[96];
    float max_lum, max_avg, min_lum, ratio;
    float highest_ratio;            /* Display.getHighestHdrSdrRatio() (Android 16+), < 0 = not reported */
} g_req = {.highest_ratio = -1.0f};

static _Atomic int g_gate = -1;     /* -1 undecided, 0 closed, 1 open */
static char g_gate_why[320];        /* the closed gate's reason (for the summary) */
static _Atomic int g_output_on = 1; /* the drawer's HDR output switch (per session, starts on) */

void droiddeck_color_set_request(int mode, const char *source, int dxvk_hdr, int zero_copy_forced) {
    pthread_mutex_lock(&g_mu);
    g_req.mode = mode < 0 ? 0 : mode > 2 ? 2 : mode;
    snprintf(g_req.source, sizeof(g_req.source), "%s", source ? source : "environment");
    g_req.dxvk_hdr = dxvk_hdr ? 1 : 0;
    g_req.zero_copy_forced = zero_copy_forced ? 1 : 0;
    pthread_mutex_unlock(&g_mu);
    atomic_store(&g_output_on, 1); /* the drawer's switch is per session and starts on */
}

void droiddeck_color_set_display(int id, const char *name, const char *formats, int hdr10, float max_lum,
                              float max_avg, float min_lum, int ratio_available, float ratio, int api) {
    pthread_mutex_lock(&g_mu);
    int was_known = g_req.display_known, was_hdr10 = g_req.hdr10, was_id = g_req.display_id;
    g_req.display_known = 1;
    g_req.display_id = id;
    snprintf(g_req.display_name, sizeof(g_req.display_name), "%s", name ? name : "?");
    snprintf(g_req.formats, sizeof(g_req.formats), "%s", formats ? formats : "未知");
    g_req.hdr10 = hdr10 ? 1 : 0;
    g_req.max_lum = max_lum; g_req.max_avg = max_avg; g_req.min_lum = min_lum;
    g_req.ratio_available = ratio_available ? 1 : 0;
    g_req.ratio = ratio;
    g_req.api = api;
    pthread_mutex_unlock(&g_mu);
    /* The gate is decided once, when the compositor starts: a display that changes under a running
     * session is recorded, and what it means is said out loud. */
    if (was_known && atomic_load(&g_gate) >= 0 && (was_hdr10 != (hdr10 ? 1 : 0) || was_id != id)) {
        if (atomic_load(&g_gate) == 1 && !hdr10)
            droiddeck_log(TAG, "游戏现在位于 \"%s\"（显示器 %d），该显示器不报告 HDR10：HDR 帧仍标记为 "
                       "BT2020_PQ，由 SurfaceFlinger 为该显示器做 tone-mapping（对游戏开放的 HDR 在本次会话"
                       "内固定不变）", name ? name : "?", id);
        else if (atomic_load(&g_gate) == 0 && hdr10)
            droiddeck_log(TAG, "游戏现在位于 \"%s\"（显示器 %d），该显示器报告 HDR10——但本次会话 HDR 保持关闭"
                       "（闸门在合成器启动时决定）；重新启动应用才能启用", name ? name : "?", id);
    }
}

int droiddeck_color_gate_state(void) { return atomic_load(&g_gate); }
int droiddeck_color_hdr_open(void) { return atomic_load(&g_gate) == 1; }

/* ---------------------------------------------------------------- HDR evidence (summary) */

static struct {
    unsigned descs;                 /* HDR image descriptions made ready */
    unsigned applied;               /* commits that made one current on a surface */
    char applied_who[160];          /* the last surface that got one */
    uint64_t layer_frames, layer_10bit, layer_copy8, copy_frames; /* layer_frames = every frame shown AS HDR */
    uint64_t composed, swapchain, tonemapped;
    uint64_t tm_off;                /* of `tonemapped`: while the drawer's HDR output switch was off */
    unsigned output_offs;           /* times the switch was turned off */
    unsigned win_layer, win_copy, win_copy8, win_zc, win_composed, win_swapchain, win_tonemapped;
    char copy_reason[160];
    char layer_fmt[48];
    int ratio_n, win_ratio_n, ratio_live_n;
    float ratio_min, ratio_max, ratio_last, win_ratio_min, win_ratio_max, ratio_logged;
    float ratio_live_max;           /* highest reading taken WHILE HDR frames were on screen (the verdict's) */
    int64_t ratio_logged_ns, ratio_periodic_ns;
    /* Live headroom. The display may grant HDR frames no headroom at all while they are on screen - seen
     * on the Fold while the screen was being RECORDED (Android turns HDR headroom off for a recording,
     * which is SDR), and plausibly with the brightness slider at maximum (SDR white already at the panel's
     * limit) - so "the ratio rose once" is not enough for a reader at minute 3: time with HDR frames on
     * screen, the part of it with the ratio above 1, and the current no-headroom streak. */
    int64_t prev_sample_ns; int prev_live; float prev_ratio;
    int64_t live_ns, headroom_ns;
    int64_t nohead_since_ns;        /* HDR frames on screen and ratio <= 1.01 since then (0 = not now) */
    int nohead_said;                /* the streak's "no headroom" line is written */
    char verdict[512];
} g_hdr;
static _Atomic int64_t g_last_frame_ns;
static _Atomic int64_t g_last_tm_ns;    /* the last HDR frame shown tone-mapped to SDR */

/* What the device says beside the headroom (the app, non-root APIs; droiddeck_color_env_*). The Fold showed
 * three ways to lose HDR headroom with HDR frames on screen - a screen recording, heat under load, and
 * (plausibly) the brightness slider at maximum - so every no-headroom line carries this. Under g_mu. */
static struct {
    int known;
    int thermal;            /* PowerManager THERMAL_STATUS_* (0 none .. 6 shutdown), -1 unknown */
    float headroom;         /* PowerManager.getThermalHeadroom(10), < 0 = not available */
    int brightness;         /* Settings.System.SCREEN_BRIGHTNESS (0..255), -1 unknown */
    int bmode;              /* SCREEN_BRIGHTNESS_MODE: 1 automatic, 0 manual, -1 unknown */
    float requested;        /* the last HDR headroom asked for (layer or screen surface): > 0 the ratio,
                             * 0 none asked, -1 the API is missing on this Android (< 15) */
} g_env = {0, -1, -1.0f, -1, -1, 0.0f};

static const char *thermal_name(int t) {
    switch (t) {
    case 0: return "NONE"; case 1: return "LIGHT"; case 2: return "MODERATE"; case 3: return "SEVERE";
    case 4: return "CRITICAL"; case 5: return "EMERGENCY"; case 6: return "SHUTDOWN"; default: return "?";
    }
}

/* "thermal MODERATE (headroom 0.83), brightness 180/255 manual". Caller holds g_mu. */
static void env_text_locked(char *out, size_t n) {
    char t[48], b[40];
    if (g_env.thermal < 0) snprintf(t, sizeof(t), "温度 ?");
    else if (g_env.headroom >= 0.0f) snprintf(t, sizeof(t), "温度 %s（余量 %.2f）", thermal_name(g_env.thermal),
                                              g_env.headroom);
    else snprintf(t, sizeof(t), "温度 %s", thermal_name(g_env.thermal));
    if (g_env.brightness < 0) snprintf(b, sizeof(b), "亮度 ?");
    else snprintf(b, sizeof(b), "亮度 %d/255%s", g_env.brightness,
                  g_env.bmode == 1 ? " 自动" : g_env.bmode == 0 ? " 手动" : "");
    snprintf(out, n, "%s, %s", t, b);
}

/* What the evidence says about headroom lost with HDR frames on screen. A screenshot or a screen recording
 * is not detected in this build (that needs extra permissions), so it is named as a possibility when
 * nothing the app can measure explains the loss. Caller holds g_mu. */
static void nohead_causes_locked(char *out, size_t n, int brief) {
    const char *parts[2];
    char hot[48];
    int np = 0;
    if (g_env.thermal >= 2) {
        snprintf(hot, sizeof(hot), "设备过热（温度 %s）", thermal_name(g_env.thermal));
        parts[np++] = hot;
    }
    /* Maximum brightness only counts when it is MANUAL: the Fold kept 3.00 of headroom at 255 auto. */
    if (g_env.brightness >= 250 && g_env.bmode == 0) parts[np++] = "亮度调到最高（手动）";
    if (np == 1) snprintf(out, n, "可能是 %s", parts[0]);
    else if (np == 2) snprintf(out, n, "可能是 %s 和 %s", parts[0], parts[1]);
    else if (g_env.known) {
        char req[48], hi[48];
        if (g_env.requested > 0.0f) snprintf(req, sizeof(req), "%.1f 倍", g_env.requested);
        else snprintf(req, sizeof(req), "%s", g_env.requested < 0.0f ? "Android 15 以下无法请求" : "未请求");
        if (g_req.highest_ratio > 0.0f) snprintf(hi, sizeof(hi), "%.2f", g_req.highest_ratio);
        else snprintf(hi, sizeof(hi), "未报告");
        if (brief)
            snprintf(out, n, "无可辨识的原因（截屏或录屏？应用没拿到 HDR 增强？）——已请求 %s，最高"
                     "比值 %s", req, hi);
        else
            snprintf(out, n, "应用看不出原因（没有过热、亮度未到最高）：可能是截屏或录屏，"
                     "也可能是这台手机不为应用提升 HDR（请求的余量为 %s，"
                     "显示器的最高比值为 %s）", req, hi);
    } else
        snprintf(out, n, "截屏或录屏、发热，或手动亮度调到最高");
}

/* ---- the explicit HDR headroom request (API 35; sc_layer.c for the game layer, the app for the screen
 * surface): content peak / SDR white, capped at the display's highest ratio when it reports one. */
static _Atomic int g_screen_hr_x1000;       /* the screen surface's wanted headroom x1000 (HDR10 swapchain) */
static _Atomic int64_t g_screen_hr_ns;      /* the last HDR10-swapchain frame */
static char g_screen_hr_why[200];           /* under g_mu */

static float peak_of(const struct droiddeck_color *c, float disp_max, const char **src) {
    if (c->max_cll > 0.0f) { *src = "max CLL"; return c->max_cll; }
    if (c->has_st2086 && c->max_lum > 0.0f) { *src = "母版峰值"; return c->max_lum; }
    if (disp_max > 0.0f) { *src = "显示器峰值"; return disp_max; }
    *src = "假定值"; return 1000.0f;
}

float droiddeck_color_desired_headroom(const struct droiddeck_color *c, char *why, size_t n) {
    if (!c || !c->dataspace) { if (why && n) why[0] = 0; return 0.0f; }
    pthread_mutex_lock(&g_mu);
    const float disp_max = g_req.max_lum, highest = g_req.highest_ratio;
    pthread_mutex_unlock(&g_mu);
    const char *src;
    const float peak = peak_of(c, disp_max, &src), white = droiddeck_color_sdr_white();
    if (highest > 0.0f && highest <= 1.01f) {
        if (why && n) snprintf(why, n, "显示器报告的最高 HDR/SDR 比值为 %.2f：任何应用在这里都拿不到增强，"
                               "因此不作任何请求", highest);
        return 0.0f;
    }
    float r = peak / (white > 0.0f ? white : 203.0f);
    const int capped = highest > 1.0f && r > highest;
    if (capped) r = highest;
    if (r < 1.0f) r = 1.0f;
    if (why && n) {
        if (capped) snprintf(why, n, "内容峰值 %.0f nits（%s）/ SDR %.0f，已按显示器最高比值 %.2f 封顶",
                             peak, src, white, highest);
        else snprintf(why, n, "内容峰值 %.0f nits（%s）/ SDR %.0f%s", peak, src, white,
                      highest > 0.0f ? "" : "；显示器未报告最高比值");
    }
    return r;
}

void droiddeck_color_note_headroom_request(float ratio) {
    pthread_mutex_lock(&g_mu);
    g_env.requested = ratio;
    pthread_mutex_unlock(&g_mu);
}

void droiddeck_color_set_highest_ratio(float ratio) {
    pthread_mutex_lock(&g_mu);
    const float was = g_req.highest_ratio;
    g_req.highest_ratio = ratio > 0.0f ? ratio : -1.0f;
    pthread_mutex_unlock(&g_mu);
    if (ratio > 0.0f && (was < 0.0f || was != ratio) && atomic_load(&g_gate) == 1)
        droiddeck_log(TAG, "显示器的最高 HDR/SDR 比值为 %.2f%s", ratio,
                   ratio <= 1.01f ? "——它完全不报告 HDR 增强：任何应用都无法在此把 HDR 高光提到 SDR 白点之上"
                                  : "（它能给出的最大 HDR 余量）");
}

float droiddeck_color_screen_headroom(char *why, size_t n) {
    const int64_t t = atomic_load(&g_screen_hr_ns);
    if (!t || now_ns() - t > 1500000000LL) { if (why && n) why[0] = 0; return 0.0f; }
    if (why && n) {
        pthread_mutex_lock(&g_mu);
        snprintf(why, n, "%s", g_screen_hr_why);
        pthread_mutex_unlock(&g_mu);
    }
    return atomic_load(&g_screen_hr_x1000) / 1000.0f;
}

int droiddeck_color_last_frame_age_ms(void) {
    int64_t t = atomic_load(&g_last_frame_ns);
    if (!t) return -1;
    int64_t age = (now_ns() - t) / 1000000LL;
    return age > 0x7fffffff ? 0x7fffffff : (int)age;
}

/* The one-line answer a tester quotes. Caller holds g_mu. */
static void verdict_locked(char *out, size_t size) {
    int gate = atomic_load(&g_gate);
    if (gate < 0) { snprintf(out, size, "尚未决定（合成器还没启动）"); return; }
    if (gate == 0) { snprintf(out, size, "否，因为 %s", g_gate_why); return; }
    if (g_hdr.layer_frames) {
        const char *who = g_hdr.applied_who[0] ? g_hdr.applied_who : "游戏";
        /* Only the paths that carried frames, so the ratio, the share and the device evidence still fit
         * the log line. */
        char paths[200] = "";
        size_t pp = 0;
        const uint64_t zc_other = g_hdr.layer_frames - g_hdr.layer_10bit - g_hdr.layer_copy8 - g_hdr.composed -
                                  g_hdr.swapchain;
        const struct { uint64_t n; const char *what; } path[] = {
            {g_hdr.layer_10bit, "10-bit 零拷贝"}, {zc_other, "零拷贝"}, {g_hdr.layer_copy8, "8-bit layer 拷贝"},
            {g_hdr.composed, "已合成"}, {g_hdr.swapchain, "HDR10 swapchain"}};
        for (unsigned i = 0; i < sizeof(path) / sizeof(path[0]); i++)
            if (path[i].n && path[i].n <= g_hdr.layer_frames && pp < sizeof(paths) - 40)
                pp += (size_t)snprintf(paths + pp, sizeof(paths) - pp, "%s%llu %s", pp ? "，" : "",
                                       (unsigned long long)path[i].n, path[i].what);
        if (g_hdr.tonemapped && pp < sizeof(paths) - 60)
            pp += (size_t)snprintf(paths + pp, sizeof(paths) - pp, "；另有 %llu 帧 tone-mapped（其中 %llu 帧在 HDR 输出关闭时）",
                                   (unsigned long long)g_hdr.tonemapped, (unsigned long long)g_hdr.tm_off);
        char frames[340];
        snprintf(frames, sizeof(frames), "%llu 帧 %s 以 BT2020_PQ 显示（%s）",
                 (unsigned long long)g_hdr.layer_frames, who, paths);
        char env[128] = "", causes[256] = "";
        if (g_env.known) env_text_locked(env, sizeof(env));
        /* Brief: the verdict has to fit one log line with everything else in it. */
        if (g_hdr.nohead_said) nohead_causes_locked(causes, sizeof(causes), 1);
        /* Only readings taken while HDR frames were on screen count: the ratio says what the display did
         * with THEM, not with whatever else was up at another moment. And the share of that time with any
         * headroom, plus where it stands now: a ratio that rose once and then fell to 1.00 for minutes is
         * not the same answer. */
        char share[384] = "";
        const long long live_s = (long long)(g_hdr.live_ns / 1000000000LL);
        if (g_hdr.live_ns >= 1000000000LL)
            snprintf(share, sizeof(share), "；HDR 时间中有 %d%% 的时段余量高于 1.00（%lld / %lld 秒），当前 %.2f%s%s"
                     "%s%s%s",
                     (int)(100.0 * (double)g_hdr.headroom_ns / (double)g_hdr.live_ns + 0.5),
                     (long long)(g_hdr.headroom_ns / 1000000000LL), live_s, g_hdr.ratio_last,
                     g_hdr.nohead_said ? "——当前无余量：" : "", g_hdr.nohead_said ? causes : "",
                     env[0] ? " 【" : "", env, env[0] ? "】" : "");
        /* The display's own ceiling (Android 16+) and what was asked for, where known. */
        char ceil[80] = "", req[48] = "";
        if (g_req.highest_ratio > 0.0f) snprintf(ceil, sizeof(ceil), ", 最高可达 %.2f", g_req.highest_ratio);
        if (g_env.requested > 0.0f) snprintf(req, sizeof(req), ", 已请求 %.1f 倍", g_env.requested);
        else if (g_env.requested < 0.0f) snprintf(req, sizeof(req), ", Android 15 以下无法请求");
        if (g_hdr.ratio_live_n && g_hdr.ratio_live_max > 1.01f)
            snprintf(out, size, "是——%s；在它们显示于屏幕期间，显示器的 HDR/SDR 比值升到 %.2f"
                     "（1.00 即仅 SDR%s%s）%s", frames, g_hdr.ratio_live_max, g_hdr.nohead_said ? "" : ceil,
                     g_hdr.nohead_said ? "" : req, share);
        else if (g_hdr.ratio_live_n)
            snprintf(out, size, "已标记但未确认——%s；然而在它们显示于屏幕期间，显示器的 HDR/SDR 比值停在 %.2f"
                     "（%s%s%s）：Android 没有给它们 HDR 余量——%s%s%s%s", frames,
                     g_hdr.ratio_live_max, g_req.highest_ratio > 0.0f ? "" : "未报告最高比值",
                     ceil[0] ? ceil + 2 : "", g_hdr.nohead_said ? "" : req, g_hdr.nohead_said ? causes : "截屏或录屏？"
                     "发热？手动亮度调到最高？该显示器的 HDR 关闭？",
                     env[0] ? " 【" : "", env, env[0] ? "】" : "");
        else if (g_hdr.ratio_n)
            snprintf(out, size, "已标记，但未测量——%s；它们显示于屏幕期间没有取到 HDR/SDR 比值读数"
                     "（其余时段最高读数 %.2f）", frames, g_hdr.ratio_max);
        else
            snprintf(out, size, "仅凭标记判定为是——%s（该显示器不报告 HDR/SDR 比值，无从确认）", frames);
        return;
    }
    if (g_hdr.applied || g_hdr.descs) {
        if (g_hdr.tonemapped && g_hdr.tm_off == g_hdr.tonemapped)
            snprintf(out, size, "否——每当 %s 显示 HDR 时，抽屉里的 HDR 输出开关都是关的：它的 %llu 个 HDR "
                     "帧被 tone-mapping 成 SDR 显示——颜色正确，但没有 HDR 亮度（打开开关才能看到 HDR）",
                     g_hdr.applied_who[0] ? g_hdr.applied_who : "游戏", (unsigned long long)g_hdr.tonemapped);
        else if (g_hdr.tonemapped)
            snprintf(out, size, "否——%s 的 HDR 帧被 tone-mapping 成 SDR 显示（%llu 帧：其中 %llu 帧是经由不提供 "
                     "HDR10 swapchain 的 screen surface 生成帧，%llu 帧是 HDR 输出在抽屉里被关闭）——颜色正确，"
                     "但没有 HDR 亮度",
                     g_hdr.applied_who[0] ? g_hdr.applied_who : "游戏", (unsigned long long)g_hdr.tonemapped,
                     (unsigned long long)(g_hdr.tonemapped - g_hdr.tm_off), (unsigned long long)g_hdr.tm_off);
        else if (g_hdr.copy_frames)
            snprintf(out, size, "否，因为 %s 请求了 HDR，但它全部 %llu 个 HDR 帧都走了合成器的 8-bit SDR 拷贝"
                     "（%s），显示时未经 tone mapping",
                     g_hdr.applied_who[0] ? g_hdr.applied_who : "游戏", (unsigned long long)g_hdr.copy_frames,
                     g_hdr.copy_reason[0] ? g_hdr.copy_reason : "没有 display layer");
        else
            snprintf(out, size, "否，因为 %s 创建了 HDR image description，却没有任何一帧被显示过"
                     "（此后 swapchain 可能已失败——参见 DXVK 日志）",
                     g_hdr.applied_who[0] ? g_hdr.applied_who : "某个程序");
        return;
    }
    snprintf(out, size, "否，因为没有程序请求 HDR：colour manager 已经提供，但没有谁设置 HDR image "
             "description（是否设置了 DXVK_HDR=1？游戏自身显示选项里的 HDR 是否打开？）");
}

/* Log the verdict when it changed (force = log it even if not). */
static void log_verdict(int force) {
    char v[512];
    pthread_mutex_lock(&g_mu);
    verdict_locked(v, sizeof(v));
    int changed = strcmp(v, g_hdr.verdict) != 0;
    if (changed) snprintf(g_hdr.verdict, sizeof(g_hdr.verdict), "%s", v);
    pthread_mutex_unlock(&g_mu);
    if (changed || force) droiddeck_log(TAG, "屏幕上的 HDR：%s", v);
}

void droiddeck_color_frame_shown(const struct droiddeck_color *c, int path, uint32_t ahb_format) {
    if (!c || !c->dataspace) return;
    int first8 = 0;
    pthread_mutex_lock(&g_mu);
    switch (path) {
    case DROIDDECK_HDR_ZERO_COPY:
        if (ahb_format == 0x2b) g_hdr.layer_10bit++;
        g_hdr.win_zc++;
        snprintf(g_hdr.layer_fmt, sizeof(g_hdr.layer_fmt), "%s", droiddeck_ahb_format_name(ahb_format));
        break;
    case DROIDDECK_HDR_LAYER_COPY:
        if (!g_hdr.layer_copy8) first8 = 1;
        g_hdr.layer_copy8++; g_hdr.win_copy8++;
        snprintf(g_hdr.layer_fmt, sizeof(g_hdr.layer_fmt), "RGBA8888 layer 拷贝");
        break;
    case DROIDDECK_HDR_COMPOSED:
        g_hdr.composed++; g_hdr.win_composed++;
        snprintf(g_hdr.layer_fmt, sizeof(g_hdr.layer_fmt), "已合成 %s", droiddeck_ahb_format_name(ahb_format));
        break;
    case DROIDDECK_HDR_SWAPCHAIN:
        g_hdr.swapchain++; g_hdr.win_swapchain++;
        snprintf(g_hdr.layer_fmt, sizeof(g_hdr.layer_fmt), "HDR10 swapchain");
        atomic_store(&g_screen_hr_ns, now_ns());
        break;
    default: /* DROIDDECK_HDR_TONEMAPPED: shown, but not as HDR */
        g_hdr.tonemapped++; g_hdr.win_tonemapped++;
        if (!atomic_load(&g_output_on)) g_hdr.tm_off++;
        pthread_mutex_unlock(&g_mu);
        atomic_store(&g_last_tm_ns, now_ns());
        return;
    }
    g_hdr.layer_frames++;
    g_hdr.win_layer++;
    pthread_mutex_unlock(&g_mu);
    atomic_store(&g_last_frame_ns, now_ns());
    if (path == DROIDDECK_HDR_SWAPCHAIN) {
        /* The screen surface's headroom request (the app applies it): worked out again when the
         * description changes, else once a second. */
        static uint32_t ident;
        static int64_t calc_ns;
        const int64_t t = now_ns();
        if (c->identity != ident || t - calc_ns > 1000000000LL) {
            char why[200];
            const float r = droiddeck_color_desired_headroom(c, why, sizeof(why));
            ident = c->identity; calc_ns = t;
            pthread_mutex_lock(&g_mu);
            snprintf(g_screen_hr_why, sizeof(g_screen_hr_why), "%s", why);
            pthread_mutex_unlock(&g_mu);
            atomic_store(&g_screen_hr_x1000, (int)(r * 1000.0f + 0.5f));
        }
    }
    if (first8)
        droiddeck_log(TAG, "HDR 帧正经由合成器的 8-bit layer 拷贝抵达 display layer（这一帧游戏的 buffer "
                   "不是 gralloc buffer）：颜色保持正确（帧保留 BT2020_PQ 标记），精度降到 8 bit"
                   "（可能出现色带）");
}

void droiddeck_color_frame_copied(const char *who, const char *reason) {
    int first = 0;
    pthread_mutex_lock(&g_mu);
    g_hdr.copy_frames++;
    g_hdr.win_copy++;
    if (reason && strcmp(reason, g_hdr.copy_reason)) {
        snprintf(g_hdr.copy_reason, sizeof(g_hdr.copy_reason), "%s", reason);
        first = 1;
    }
    pthread_mutex_unlock(&g_mu);
    if (first)
        droiddeck_log(TAG, "%s 的 HDR 帧现在要走合成器的 8-bit SDR 拷贝了，原因是 %s：在情况改变之前都"
                   "不做 tone mapping 显示（画面发白）", who ? who : "某个窗口", reason ? reason : "?");
}

void droiddeck_color_ratio_sample(float ratio, int listener) {
    if (atomic_load(&g_gate) != 1) return;
    int64_t t = now_ns();
    int age = droiddeck_color_last_frame_age_ms();
    int live = age >= 0 && age < 1500;
    int log_it = 0, periodic = 0, nohead_now = 0, head_back = 0;
    float was;
    char env[128] = "", causes[256] = "";
    pthread_mutex_lock(&g_mu);
    was = g_hdr.ratio_logged;
    if (ratio > 0.0f) {
        /* Time with HDR frames on screen, credited to the reading that stood over it. */
        if (g_hdr.prev_sample_ns && g_hdr.prev_live) {
            int64_t dt = t - g_hdr.prev_sample_ns;
            if (dt > 0 && dt < 3000000000LL) {
                g_hdr.live_ns += dt;
                if (g_hdr.prev_ratio > 1.01f) g_hdr.headroom_ns += dt;
            }
        }
        g_hdr.prev_sample_ns = t; g_hdr.prev_live = live; g_hdr.prev_ratio = ratio;
        if (live && ratio <= 1.01f) {
            if (!g_hdr.nohead_since_ns) g_hdr.nohead_since_ns = t;
            if (!g_hdr.nohead_said && t - g_hdr.nohead_since_ns >= 5000000000LL) g_hdr.nohead_said = nohead_now = 1;
        } else {
            if (live && g_hdr.nohead_said) head_back = 1;
            g_hdr.nohead_since_ns = 0; g_hdr.nohead_said = 0;
        }
        if (!g_hdr.ratio_n || ratio < g_hdr.ratio_min) g_hdr.ratio_min = ratio;
        if (!g_hdr.ratio_n || ratio > g_hdr.ratio_max) g_hdr.ratio_max = ratio;
        if (!g_hdr.win_ratio_n || ratio < g_hdr.win_ratio_min) g_hdr.win_ratio_min = ratio;
        if (!g_hdr.win_ratio_n || ratio > g_hdr.win_ratio_max) g_hdr.win_ratio_max = ratio;
        g_hdr.ratio_n++; g_hdr.win_ratio_n++;
        g_hdr.ratio_last = ratio;
        if (live) {
            if (!g_hdr.ratio_live_n || ratio > g_hdr.ratio_live_max) g_hdr.ratio_live_max = ratio;
            g_hdr.ratio_live_n++;
        }
        /* A change is logged at once (at most 4 lines a second); while HDR frames are on screen the
         * steady value is restated every 5 s, so a tester's log shows it holding, not just moving. */
        float moved = ratio - g_hdr.ratio_logged;
        if (moved < 0.0f) moved = -moved;
        if ((g_hdr.ratio_n == 1 || moved >= 0.02f) && t - g_hdr.ratio_logged_ns > 250000000LL)
            log_it = 1;
        else if (live && t - g_hdr.ratio_periodic_ns > 5000000000LL)
            log_it = periodic = 1;
        if (log_it) { g_hdr.ratio_logged = ratio; g_hdr.ratio_logged_ns = t; g_hdr.ratio_periodic_ns = t; }
        if (nohead_now || (periodic && live && ratio <= 1.01f)) {
            env_text_locked(env, sizeof(env));
            nohead_causes_locked(causes, sizeof(causes), 0);
        }
    }
    pthread_mutex_unlock(&g_mu);
    if (nohead_now)
        droiddeck_log(TAG, "HDR 帧显示于屏幕期间，有 5 秒没有 HDR 余量（显示器 HDR/SDR 比值 %.2f）：%s——Android "
                   "会在屏幕被录制以及设备过热时收回 HDR 余量，而有些手机从不为应用提升 HDR；"
                   "在这种情况结束前 HDR 高光不会显得更亮 [%s]", ratio, causes, env);
    if (head_back)
        droiddeck_log(TAG, "HDR 余量恢复：HDR 帧在屏时显示器 HDR/SDR 比值 %.2f", ratio);
    if (!log_it) return;
    char when[64];
    if (age < 0) snprintf(when, sizeof(when), "本次会话还没有过");
    else snprintf(when, sizeof(when), "%s，最近一次在 %d 毫秒前", live ? "是" : "否", age);
    if (periodic)
        droiddeck_log(TAG, "显示器 HDR/SDR 比值 %.2f（稳定；HDR 帧在屏：%s）%s%s%s%s%s", ratio, when,
                   live && ratio <= 1.01f ? "——无 HDR 余量：" : "", live && ratio <= 1.01f ? causes : "",
                   env[0] ? " 【" : "", env, env[0] ? "】" : "");
    else if (was > 0.0f)
        droiddeck_log(TAG, "显示器 HDR/SDR 比值 %.2f（此前 %.2f；HDR 帧在屏：%s）[%s]", ratio, was, when,
                   listener ? "显示器监听器" : "采样器");
    else
        droiddeck_log(TAG, "显示器 HDR/SDR 比值 %.2f（首次读数；HDR 帧在屏：%s）[%s]", ratio, when,
                   listener ? "显示器监听器" : "采样器");
}

void droiddeck_color_stats_tick(void) {
    if (atomic_load(&g_gate) != 1) return;
    char line[512];
    int any;
    pthread_mutex_lock(&g_mu);
    any = g_hdr.win_layer || g_hdr.win_copy || g_hdr.win_tonemapped; /* the ratio alone is logged when it moves */
    if (any) {
        char ratio[256] = "无 HDR/SDR 比值读数", env[128] = "", causes[256] = "";
        if (g_env.known) env_text_locked(env, sizeof(env));
        if (g_hdr.nohead_said) nohead_causes_locked(causes, sizeof(causes), 1);
        if (g_hdr.win_ratio_n)
            snprintf(ratio, sizeof(ratio), "显示器 HDR/SDR 比值 %.2f-%.2f（当前 %.2f）%s%s", g_hdr.win_ratio_min,
                     g_hdr.win_ratio_max, g_hdr.ratio_last, g_hdr.nohead_said ? "——没有余量：" : "",
                     g_hdr.nohead_said ? causes : "");
        snprintf(line, sizeof(line), "HDR 最近 10 秒：%u 帧以 BT2020_PQ 显示（零拷贝 %u，8-bit layer 拷贝 %u，"
                 "合成画面 %u，HDR10 swapchain %u；最近的 buffer %s） | %u 帧 tone-mapped 成 SDR | %u 帧发白"
                 "拷贝 | %s%s%s",
                 g_hdr.win_layer, g_hdr.win_zc, g_hdr.win_copy8, g_hdr.win_composed, g_hdr.win_swapchain,
                 g_hdr.layer_fmt[0] ? g_hdr.layer_fmt : "-", g_hdr.win_tonemapped, g_hdr.win_copy, ratio,
                 env[0] ? " | " : "", env);
    }
    g_hdr.win_layer = g_hdr.win_copy = g_hdr.win_copy8 = 0;
    g_hdr.win_zc = g_hdr.win_composed = g_hdr.win_swapchain = g_hdr.win_tonemapped = 0;
    g_hdr.win_ratio_n = 0;
    pthread_mutex_unlock(&g_mu);
    if (any) droiddeck_log(TAG, "%s", line);
    log_verdict(0);
}

int droiddeck_color_hdr_state(void) {
    if (atomic_load(&g_gate) != 1) return 0;
    int age = droiddeck_color_last_frame_age_ms();
    if (age < 0 || age >= 1500) return 0;
    pthread_mutex_lock(&g_mu);
    int confirmed = !g_hdr.ratio_n || g_hdr.ratio_last > 1.01f; /* no ratio on this display: the tag is all there is */
    int nohead = g_hdr.nohead_said;
    pthread_mutex_unlock(&g_mu);
    return confirmed ? 1 : nohead ? 2 : 0;
}

int droiddeck_color_hdr_on_screen(void) { return droiddeck_color_hdr_state() == 1; }

static _Atomic int g_sdr_white_x100 = 20300;
void droiddeck_color_set_sdr_white(float nits) {
    if (!(nits >= 10.0f && nits <= 2000.0f)) return;
    atomic_store(&g_sdr_white_x100, (int)(nits * 100.0f + 0.5f));
    droiddeck_log(TAG, "HDR 画面里的 SDR 内容被定位在 %.0f nits（DROIDDECK_WAYLAND_HDR_SDR_NITS）", nits);
}
float droiddeck_color_sdr_white(void) { return atomic_load(&g_sdr_white_x100) / 100.0f; }

int droiddeck_color_requested(void) {
    pthread_mutex_lock(&g_mu);
    int m = g_req.mode;
    pthread_mutex_unlock(&g_mu);
    return m != 0;
}

int droiddeck_color_output(void) { return atomic_load(&g_output_on); }

void droiddeck_color_env_sample(int thermal, float headroom, int brightness, int bmode) {
    if (atomic_load(&g_gate) != 1) return;  /* evidence for the HDR lines only */
    char msg[2][200];
    int nmsg = 0;
    pthread_mutex_lock(&g_mu);
    const int first = !g_env.known;
    if (!first && thermal != g_env.thermal) {
        char hr[32] = "";
        if (headroom >= 0.0f) snprintf(hr, sizeof(hr), "，余量 %.2f", headroom);
        snprintf(msg[nmsg++], sizeof(msg[0]), "温度状态现在为 %s（此前 %s%s）%s", thermal_name(thermal),
                 thermal_name(g_env.thermal), hr, thermal >= 2 ? "——设备过热时 Android 会降低 HDR 余量" : "");
    }
    if (!first && (brightness != g_env.brightness || bmode != g_env.bmode))
        snprintf(msg[nmsg++], sizeof(msg[0]), "屏幕亮度现在为 %d/255 %s（此前 %d/255 %s）", brightness,
                 bmode == 1 ? "自动" : bmode == 0 ? "手动" : "?", g_env.brightness,
                 g_env.bmode == 1 ? "自动" : g_env.bmode == 0 ? "手动" : "?");
    g_env.thermal = thermal;
    g_env.headroom = headroom;
    g_env.brightness = brightness;
    g_env.bmode = bmode;
    g_env.known = 1;
    if (first) {
        char e[128];
        env_text_locked(e, sizeof(e));
        snprintf(msg[nmsg++], sizeof(msg[0]), "HDR/SDR 余量旁的设备证据：%s（温度状态和亮度的变化"
                 "会随时记录下来）", e);
    }
    pthread_mutex_unlock(&g_mu);
    for (int i = 0; i < nmsg; i++) droiddeck_log(TAG, "%s", msg[i]);
}

int droiddeck_color_tonemapped_on_screen(void) {
    if (atomic_load(&g_gate) != 1) return 0;
    int64_t t = atomic_load(&g_last_tm_ns);
    return t && now_ns() - t < 1500000000LL;
}

void droiddeck_color_set_output(int on) {
    on = on ? 1 : 0;
    if (atomic_load(&g_gate) != 1) {
        /* The drawer only shows the switch in sessions whose gate is open; say so if it ever gets here. */
        droiddeck_log(TAG, "HDR 输出开关（%s）被忽略：本次会话 HDR 没有开启", on ? "开" : "关");
        return;
    }
    if (atomic_exchange(&g_output_on, on) == on) return;
    char who[160];
    int age = droiddeck_color_last_frame_age_ms(), tm = droiddeck_color_tonemapped_on_screen();
    pthread_mutex_lock(&g_mu);
    if (!on) g_hdr.output_offs++;
    snprintf(who, sizeof(who), "%s", g_hdr.applied_who[0] ? g_hdr.applied_who : "还没有 HDR 程序");
    pthread_mutex_unlock(&g_mu);
    if (on)
        droiddeck_log(TAG, "抽屉里的 HDR 输出已打开：从下一帧起 HDR 帧重新以 HDR 送达显示器"
                   "（%s；%s）", who, tm ? "在此之前它们一直在被 tone-mapping 成 SDR" : "刚才没有帧在屏幕上");
    else
        droiddeck_log(TAG, "抽屉里的 HDR 输出已关闭：从下一帧起 HDR 帧会被 tone-mapping 成 SDR"
                   "（%s；%s）。游戏不会被告知——它继续渲染 HDR；游戏自身的 HDR 设置和 DXVK_HDR "
                   "都不受影响", who, age >= 0 && age < 1500 ? "HDR 帧刚才在屏幕上" : "刚才屏幕上没有 HDR 帧");
    droiddeck_request_redraw(); /* a paused game commits nothing: show the change now */
}

static _Atomic int g_session_ended;

void droiddeck_color_session_end(void) {
    if (atomic_exchange(&g_session_ended, 1)) return;
    if (atomic_load(&g_gate) < 0) return;
    pthread_mutex_lock(&g_mu);
    int asked = g_req.mode != 0;
    pthread_mutex_unlock(&g_mu);
    if (asked) log_verdict(1); /* nobody asked for HDR: no summary to write */
}

/* ---------------------------------------------------------------- image descriptions */

struct cm_desc {
    int refs;                       /* protocol objects + surfaces (pending / current) */
    struct droiddeck_color c;
    /* Set on the output / preferred descriptions the compositor serves (never on one a game built
     * from parameters): get_information is answered rather than refused, with these as the target. */
    int informative;
    float ref_lum;                  /* nits: PQ reference white */
    float disp_min_lum, disp_max_lum; /* nits: the display's own volume, from the app */
};

static uint32_t g_next_identity = 1;

static struct cm_desc *desc_ref(struct cm_desc *d) { if (d) d->refs++; return d; }
static void desc_unref(struct cm_desc *d) { if (d && --d->refs <= 0) free(d); }

/* The parametric creator's collected properties. 0 / has_* = 0 means unset. */
struct cm_params {
    uint32_t tf, primaries;
    int has_mprim; int32_t mprim[8];
    int has_mlum; uint32_t mlum_min, mlum_max;   /* min is cd/m² * 10000, max is cd/m² */
    int has_cll; uint32_t cll;
    int has_fall; uint32_t fall;
};

static void image_description_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void image_description_get_information(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct cm_desc *d = wl_resource_get_user_data(r);
    if (!d || !d->informative) {
        /* Built by the game from parameters: the protocol says it knows what it asked for. */
        wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_V1_ERROR_NO_INFORMATION,
                               "get_information is not allowed on this image description");
        return;
    }
    struct wl_resource *info = wl_resource_create(c, &wp_image_description_info_v1_interface,
                                                  wl_resource_get_version(r), id);
    if (!info) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(info, NULL, NULL, NULL); /* events only */
    const struct droiddeck_color *col = &d->c;
    int32_t xy[8] = { (int32_t)(col->red[0] * 1e6f + 0.5f),   (int32_t)(col->red[1] * 1e6f + 0.5f),
                      (int32_t)(col->green[0] * 1e6f + 0.5f), (int32_t)(col->green[1] * 1e6f + 0.5f),
                      (int32_t)(col->blue[0] * 1e6f + 0.5f),  (int32_t)(col->blue[1] * 1e6f + 0.5f),
                      (int32_t)(col->white[0] * 1e6f + 0.5f), (int32_t)(col->white[1] * 1e6f + 0.5f) };
    wp_image_description_info_v1_send_primaries(info, xy[0], xy[1], xy[2], xy[3], xy[4], xy[5], xy[6], xy[7]);
    wp_image_description_info_v1_send_primaries_named(info, col->primaries);
    wp_image_description_info_v1_send_tf_named(info, col->tf);
    /* min is cd/m² * 10000, max and reference are cd/m² */
    wp_image_description_info_v1_send_luminances(info, (uint32_t)(col->min_lum * 10000.0f + 0.5f),
                                                 (uint32_t)(col->max_lum + 0.5f), (uint32_t)(d->ref_lum + 0.5f));
    wp_image_description_info_v1_send_target_primaries(info, xy[0], xy[1], xy[2], xy[3], xy[4], xy[5], xy[6], xy[7]);
    wp_image_description_info_v1_send_target_luminance(info, (uint32_t)(d->disp_min_lum * 10000.0f + 0.5f),
                                                       (uint32_t)(d->disp_max_lum + 0.5f));
    /* done is a destructor event: the client's proxy is gone once it arrives, so the resource goes too. */
    wp_image_description_info_v1_send_done(info);
    wl_resource_destroy(info);
}
static const struct wp_image_description_v1_interface image_description_impl = {
    .destroy = image_description_destroy_req,
    .get_information = image_description_get_information,
};
static void image_description_resource_destroy(struct wl_resource *r) {
    desc_unref(wl_resource_get_user_data(r));
}

/* What the display layer shows while the gate is open - BT.2020 + PQ over the display's own
 * volume - served as the output's image description and as every surface's preferred one. It is
 * ready at once and open to get_information, and that is not optional: gamescope asks for the
 * preferred description and for its information in the same breath, before any roundtrip
 * (WaylandBackend.cpp, UpdateWPPreferredColorManagement). Answering 'failed' and then refusing
 * get_information with a protocol error severs its connection mid-roundtrip - the Fold 8 on r5:
 * "Broken pipe", signal 6 - while a device with the gate closed never offers colour management
 * and never gets here. gamescope reads only the luminances from the answer (reference white vs
 * the display's peak) to decide whether it exposes HDR to what it hosts. */
static void make_output_description(struct wl_client *c, struct wl_resource *parent, uint32_t id, const char *what) {
    struct cm_desc *d = calloc(1, sizeof(*d));
    if (!d) { wl_client_post_no_memory(c); return; }
    struct droiddeck_color *col = &d->c;
    col->identity = g_next_identity++;
    if (!g_next_identity) g_next_identity = 1;
    col->primaries = WP_COLOR_MANAGER_V1_PRIMARIES_BT2020;
    col->tf = WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ;
    col->dataspace = DROIDDECK_ADATASPACE_BT2020_PQ;
    col->has_st2086 = 1;
    col->red[0] = 0.708f; col->red[1] = 0.292f; col->green[0] = 0.170f; col->green[1] = 0.797f;
    col->blue[0] = 0.131f; col->blue[1] = 0.046f; col->white[0] = 0.3127f; col->white[1] = 0.3290f;
    col->min_lum = 0.005f; col->max_lum = 10000.0f;  /* the PQ volume */
    d->informative = 1;
    d->ref_lum = 203.0f;                              /* PQ reference white */
    pthread_mutex_lock(&g_mu);
    d->disp_min_lum = g_req.min_lum > 0.0f ? g_req.min_lum : 0.0f;
    d->disp_max_lum = g_req.max_lum > 0.0f ? g_req.max_lum : 1000.0f;
    pthread_mutex_unlock(&g_mu);
    snprintf(col->text, sizeof(col->text), "BT.2020，ST 2084 PQ；目标 %.0f nits（显示器）", d->disp_max_lum);

    struct wl_resource *r = wl_resource_create(c, &wp_image_description_v1_interface, wl_resource_get_version(parent), id);
    if (!r) { free(d); wl_client_post_no_memory(c); return; }
    d->refs = 1;
    wl_resource_set_implementation(r, &image_description_impl, d, image_description_resource_destroy);
    wp_image_description_v1_send_ready(r, col->identity);
    droiddeck_log(TAG, "%s 请求了 %s：已应答该显示器的 HDR10 volume（%s），ready 并对 get_information 开放",
               droiddeck_client_name(c), what, col->text);
}

static void params_create(struct wl_client *client, struct wl_resource *r, uint32_t id) {
    struct cm_params *p = wl_resource_get_user_data(r);
    if (!p->tf || !p->primaries) {
        wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INCOMPLETE_SET,
                               "transfer function and primaries must both be set");
        return;
    }
    struct cm_desc *d = calloc(1, sizeof(*d));
    if (!d) { wl_client_post_no_memory(client); return; }
    struct droiddeck_color *c = &d->c;
    c->identity = g_next_identity++;
    if (!g_next_identity) g_next_identity = 1;
    c->primaries = p->primaries;
    c->tf = p->tf;
    if (p->primaries == WP_COLOR_MANAGER_V1_PRIMARIES_BT2020 && p->tf == WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ)
        c->dataspace = DROIDDECK_ADATASPACE_BT2020_PQ;
    else if (p->primaries == WP_COLOR_MANAGER_V1_PRIMARIES_BT2020 && p->tf == WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_HLG)
        c->dataspace = DROIDDECK_ADATASPACE_BT2020_HLG;

    /* HDR metadata, kept only when it makes sense. The PQ defaults stand in for what was not given
     * (0.005 - 10000 cd/m², the target volume = the primary one); CTA-861.3 treats 0 as unknown. */
    char dropped[160] = "";
    float mlum_min = 0.005f, mlum_max = 10000.0f;
    if (p->has_mlum) {
        float mn = p->mlum_min / 10000.0f, mx = (float)p->mlum_max;
        if (mx > mn && mx > 0.0f) { mlum_min = mn; mlum_max = mx; }
        else snprintf(dropped + strlen(dropped), sizeof(dropped) - strlen(dropped), " 母版亮度 %.4f-%.0f（max <= min）,", mn, mx);
    }
    if (p->has_mprim || p->has_mlum) {
        c->has_st2086 = 1;
        if (p->has_mprim) {
            c->red[0] = p->mprim[0] / 1e6f; c->red[1] = p->mprim[1] / 1e6f;
            c->green[0] = p->mprim[2] / 1e6f; c->green[1] = p->mprim[3] / 1e6f;
            c->blue[0] = p->mprim[4] / 1e6f; c->blue[1] = p->mprim[5] / 1e6f;
            c->white[0] = p->mprim[6] / 1e6f; c->white[1] = p->mprim[7] / 1e6f;
        } else { /* the container's own primaries (BT.2020, D65) */
            c->red[0] = 0.708f; c->red[1] = 0.292f; c->green[0] = 0.170f; c->green[1] = 0.797f;
            c->blue[0] = 0.131f; c->blue[1] = 0.046f; c->white[0] = 0.3127f; c->white[1] = 0.3290f;
        }
        c->min_lum = mlum_min;
        c->max_lum = mlum_max;
    }
    float cll = p->has_cll ? (float)p->cll : 0.0f, fall = p->has_fall ? (float)p->fall : 0.0f;
    if (cll > 0.0f && (cll <= mlum_min || cll > mlum_max)) {
        snprintf(dropped + strlen(dropped), sizeof(dropped) - strlen(dropped), " max CLL %.0f（超出 %.4f-%.0f 范围）,", cll, mlum_min, mlum_max);
        cll = 0.0f;
    }
    if (fall > 0.0f && (fall <= mlum_min || fall > mlum_max || (cll > 0.0f && fall > cll))) {
        snprintf(dropped + strlen(dropped), sizeof(dropped) - strlen(dropped), " max FALL %.0f（超出范围或高于 max CLL）,", fall);
        fall = 0.0f;
    }
    if (cll > 0.0f || fall > 0.0f) { c->has_cta861 = 1; c->max_cll = cll; c->max_fall = fall; }

    int n = snprintf(c->text, sizeof(c->text), "%s，%s", primaries_name(c->primaries), tf_name(c->tf));
    if (c->has_st2086 && n < (int)sizeof(c->text))
        n += snprintf(c->text + n, sizeof(c->text) - (size_t)n,
                      "；母版 R %.4f,%.4f G %.4f,%.4f B %.4f,%.4f W %.4f,%.4f，%.4f-%.0f nits%s",
                      c->red[0], c->red[1], c->green[0], c->green[1], c->blue[0], c->blue[1], c->white[0], c->white[1],
                      c->min_lum, c->max_lum, p->has_mprim ? "" : "（容器基色）");
    if (n < (int)sizeof(c->text))
        n += snprintf(c->text + n, sizeof(c->text) - (size_t)n, "；max CLL %s%.0f，max FALL %s%.0f",
                      c->max_cll > 0.0f ? "" : "未知 ", c->max_cll, c->max_fall > 0.0f ? "" : "未知 ", c->max_fall);
    if (!c->has_st2086 && !c->has_cta861 && n < (int)sizeof(c->text))
        snprintf(c->text + n, sizeof(c->text) - (size_t)n, "（没有 HDR 元数据：游戏没有调用 vkSetHdrMetadataEXT）");

    struct wl_resource *out = wl_resource_create(client, &wp_image_description_v1_interface,
                                                 wl_resource_get_version(r), id);
    if (!out) { free(d); wl_client_post_no_memory(client); return; }
    d->refs = 1;
    wl_resource_set_implementation(out, &image_description_impl, d, image_description_resource_destroy);
    /* Sent from inside the request: Mesa blocks its swapchain on this event (it dispatches its queue
     * until ready/failed arrives), and the event loop flushes it before it sleeps again. */
    wp_image_description_v1_send_ready(out, c->identity);
    if (c->dataspace) {
        pthread_mutex_lock(&g_mu);
        g_hdr.descs++;
        pthread_mutex_unlock(&g_mu);
    }
    droiddeck_log(TAG, "image description #%u（来自 %s）：%s -> ready（在 display layer 上：dataspace %s）", c->identity,
               droiddeck_client_name(client), c->text, dataspace_name(c->dataspace));
    if (dropped[0]) {
        size_t l = strlen(dropped);
        if (l && dropped[l - 1] == ',') dropped[l - 1] = 0;
        droiddeck_log(TAG, "image description #%u：丢弃了违反协议规则的 HDR 元数据：%s"
                   "（其余部分交给 Android）", c->identity, dropped);
    }
    wl_resource_destroy(r); /* create is the creator's destructor */
}

#define PARAMS_ONCE(field, what)                                                                      \
    do {                                                                                              \
        if (field) {                                                                                  \
            wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_ALREADY_SET,       \
                                   what " already set");                                             \
            return;                                                                                   \
        }                                                                                             \
    } while (0)

static void params_set_tf_named(struct wl_client *c, struct wl_resource *r, uint32_t tf) {
    struct cm_params *p = wl_resource_get_user_data(r);
    PARAMS_ONCE(p->tf, "transfer function");
    if (tf != WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ) {
        wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_TF,
                               "transfer function %u was not advertised", tf);
        return;
    }
    p->tf = tf;
}
static void params_set_tf_power(struct wl_client *c, struct wl_resource *r, uint32_t eexp) {
    wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_UNSUPPORTED_FEATURE,
                           "set_tf_power is not supported");
}
static void params_set_primaries_named(struct wl_client *c, struct wl_resource *r, uint32_t primaries) {
    struct cm_params *p = wl_resource_get_user_data(r);
    PARAMS_ONCE(p->primaries, "primaries");
    if (primaries != WP_COLOR_MANAGER_V1_PRIMARIES_BT2020) {
        wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_INVALID_PRIMARIES_NAMED,
                               "primaries %u were not advertised", primaries);
        return;
    }
    p->primaries = primaries;
}
static void params_set_primaries(struct wl_client *c, struct wl_resource *r, int32_t rx, int32_t ry, int32_t gx,
                                 int32_t gy, int32_t bx, int32_t by, int32_t wx, int32_t wy) {
    wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_UNSUPPORTED_FEATURE,
                           "set_primaries is not supported");
}
static void params_set_luminances(struct wl_client *c, struct wl_resource *r, uint32_t mn, uint32_t mx, uint32_t ref) {
    wl_resource_post_error(r, WP_IMAGE_DESCRIPTION_CREATOR_PARAMS_V1_ERROR_UNSUPPORTED_FEATURE,
                           "set_luminances is not supported");
}
static void params_set_mastering_display_primaries(struct wl_client *c, struct wl_resource *r, int32_t rx, int32_t ry,
                                                   int32_t gx, int32_t gy, int32_t bx, int32_t by, int32_t wx, int32_t wy) {
    struct cm_params *p = wl_resource_get_user_data(r);
    PARAMS_ONCE(p->has_mprim, "mastering display primaries");
    p->has_mprim = 1;
    p->mprim[0] = rx; p->mprim[1] = ry; p->mprim[2] = gx; p->mprim[3] = gy;
    p->mprim[4] = bx; p->mprim[5] = by; p->mprim[6] = wx; p->mprim[7] = wy;
}
static void params_set_mastering_luminance(struct wl_client *c, struct wl_resource *r, uint32_t mn, uint32_t mx) {
    struct cm_params *p = wl_resource_get_user_data(r);
    PARAMS_ONCE(p->has_mlum, "mastering luminance");
    p->has_mlum = 1; p->mlum_min = mn; p->mlum_max = mx;
}
static void params_set_max_cll(struct wl_client *c, struct wl_resource *r, uint32_t v) {
    struct cm_params *p = wl_resource_get_user_data(r);
    p->has_cll = v != 0; p->cll = v;
}
static void params_set_max_fall(struct wl_client *c, struct wl_resource *r, uint32_t v) {
    struct cm_params *p = wl_resource_get_user_data(r);
    p->has_fall = v != 0; p->fall = v;
}
static const struct wp_image_description_creator_params_v1_interface params_impl = {
    .create = params_create,
    .set_tf_named = params_set_tf_named,
    .set_tf_power = params_set_tf_power,
    .set_primaries_named = params_set_primaries_named,
    .set_primaries = params_set_primaries,
    .set_luminances = params_set_luminances,
    .set_mastering_display_primaries = params_set_mastering_display_primaries,
    .set_mastering_luminance = params_set_mastering_luminance,
    .set_max_cll = params_set_max_cll,
    .set_max_fall = params_set_max_fall,
};
static void params_resource_destroy(struct wl_resource *r) { free(wl_resource_get_user_data(r)); }

/* ---------------------------------------------------------------- per-surface state */

/* One per wl_surface that ever had a colour-management object. Lives until the wl_surface goes (a
 * swapchain rebuild destroys the object and makes a new one for the same surface). */
struct cm_surf {
    struct wl_resource *surface;        /* the wl_surface */
    struct wl_listener surface_destroy;
    struct wl_resource *owner;          /* the live wp_color_management_surface_v1, NULL = none */
    struct cm_desc *pending, *current;
    int pending_dirty;
};

static void cm_surface_destroyed(struct wl_listener *l, void *data);

static struct cm_surf *surf_of(struct wl_resource *surface) {
    if (!surface) return NULL;
    struct wl_listener *l = wl_resource_get_destroy_listener(surface, cm_surface_destroyed);
    if (!l) return NULL;
    struct cm_surf *cs = wl_container_of(l, cs, surface_destroy);
    return cs;
}

static void describe_surface(struct wl_resource *surface, char *out, size_t size) {
    struct surface *s = surface ? wl_resource_get_user_data(surface) : NULL;
    if (s) droiddeck_surface_describe(s, out, size);
    else snprintf(out, size, "一个已关闭的窗口");
}

static void cm_surface_destroyed(struct wl_listener *l, void *data) {
    struct cm_surf *cs = wl_container_of(l, cs, surface_destroy);
    wl_list_remove(&cs->surface_destroy.link);
    if (cs->owner) wl_resource_set_user_data(cs->owner, NULL); /* inert from now on */
    desc_unref(cs->pending);
    desc_unref(cs->current);
    free(cs);
}

static void cm_surface_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }

static void cm_surface_set_image_description(struct wl_client *c, struct wl_resource *r,
                                             struct wl_resource *desc_res, uint32_t intent) {
    struct cm_surf *cs = wl_resource_get_user_data(r);
    struct cm_desc *d = desc_res ? wl_resource_get_user_data(desc_res) : NULL;
    if (!cs) {
        droiddeck_log(TAG, "%s 在 surface 已不复存在的 colour-management 对象上设置了 image description：已忽略",
                   droiddeck_client_name(c));
        return;
    }
    if (!d) {
        wl_resource_post_error(r, WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_IMAGE_DESCRIPTION,
                               "the image description is not ready");
        return;
    }
    if (intent != WP_COLOR_MANAGER_V1_RENDER_INTENT_PERCEPTUAL) {
        wl_resource_post_error(r, WP_COLOR_MANAGEMENT_SURFACE_V1_ERROR_RENDER_INTENT,
                               "rendering intent %u was not advertised", intent);
        return;
    }
    desc_unref(cs->pending);
    cs->pending = desc_ref(d);
    cs->pending_dirty = 1;
}

static void cm_surface_unset_image_description(struct wl_client *c, struct wl_resource *r) {
    struct cm_surf *cs = wl_resource_get_user_data(r);
    if (!cs) return;
    desc_unref(cs->pending);
    cs->pending = NULL;
    cs->pending_dirty = 1;
}

static const struct wp_color_management_surface_v1_interface cm_surface_impl = {
    .destroy = cm_surface_destroy_req,
    .set_image_description = cm_surface_set_image_description,
    .unset_image_description = cm_surface_unset_image_description,
};

/* Destroying the object does what unset_image_description does (double-buffered, like it). */
static void cm_surface_resource_destroy(struct wl_resource *r) {
    struct cm_surf *cs = wl_resource_get_user_data(r);
    if (!cs || cs->owner != r) return;
    cs->owner = NULL;
    desc_unref(cs->pending);
    cs->pending = NULL;
    cs->pending_dirty = 1;
}

void droiddeck_color_commit(struct wl_resource *surface) {
    if (atomic_load(&g_gate) != 1) return;
    struct cm_surf *cs = surf_of(surface);
    if (!cs || !cs->pending_dirty) return;
    cs->pending_dirty = 0;
    if (cs->pending == cs->current) return;
    struct cm_desc *old = cs->current;
    cs->current = desc_ref(cs->pending);
    char who[160];
    describe_surface(surface, who, sizeof(who));
    if (cs->current) {
        droiddeck_log(TAG, "%s：image description #%u 现在应用于它的帧（%s；perceptual intent）", who,
                   cs->current->c.identity, cs->current->c.text);
        if (cs->current->c.dataspace) {
            pthread_mutex_lock(&g_mu);
            g_hdr.applied++;
            snprintf(g_hdr.applied_who, sizeof(g_hdr.applied_who), "%s", who);
            pthread_mutex_unlock(&g_mu);
            if (!g_zero_copy)
                droiddeck_log(TAG, "%s：zero-copy presentation 处于关闭状态，因此它的 HDR 帧会被合成进 HDR "
                           "画面（每帧多一个 pass）——打开 Zero-copy presentation 才能走直接路径", who);
        }
    } else if (old) {
        droiddeck_log(TAG, "%s：image description #%u 已移除——它的帧恢复为 sRGB", who, old->c.identity);
    }
    desc_unref(old);
}

const struct droiddeck_color *droiddeck_color_of(struct wl_resource *surface) {
    if (atomic_load(&g_gate) != 1) return NULL;
    struct cm_surf *cs = surf_of(surface);
    return cs && cs->current ? &cs->current->c : NULL;
}

/* ---------------------------------------------------------------- output + feedback */

static void cm_output_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void cm_output_get_image_description(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    make_output_description(c, r, id, "该 output 的 image description");
}
static const struct wp_color_management_output_v1_interface cm_output_impl = {
    .destroy = cm_output_destroy_req,
    .get_image_description = cm_output_get_image_description,
};

static void cm_feedback_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void cm_feedback_get_preferred(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    make_output_description(c, r, id, "某 surface 的首选 image description");
}
static const struct wp_color_management_surface_feedback_v1_interface cm_feedback_impl = {
    .destroy = cm_feedback_destroy_req,
    .get_preferred = cm_feedback_get_preferred,
    .get_preferred_parametric = cm_feedback_get_preferred,
};

/* ---------------------------------------------------------------- wp_color_manager_v1 */

static void cm_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }

static void cm_get_output(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *output) {
    struct wl_resource *o = wl_resource_create(c, &wp_color_management_output_v1_interface, wl_resource_get_version(r), id);
    if (!o) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(o, &cm_output_impl, NULL, NULL);
}

static void cm_get_surface(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface) {
    struct wl_resource *o = wl_resource_create(c, &wp_color_management_surface_v1_interface, wl_resource_get_version(r), id);
    if (!o) { wl_client_post_no_memory(c); return; }
    struct cm_surf *cs = surf_of(surface);
    if (!cs) {
        cs = calloc(1, sizeof(*cs));
        if (!cs) { wl_resource_destroy(o); wl_client_post_no_memory(c); return; }
        cs->surface = surface;
        cs->surface_destroy.notify = cm_surface_destroyed;
        wl_resource_add_destroy_listener(surface, &cs->surface_destroy);
    }
    char who[160];
    describe_surface(surface, who, sizeof(who));
    if (cs->owner) {
        /* The protocol says surface_exists; a game would be disconnected for it. The newer object wins. */
        droiddeck_log(TAG, "%s：同一 surface 的第二个 colour-management 对象——较新的那个接管"
                   "（未抛出 surface_exists 协议错误）", who);
        wl_resource_set_user_data(cs->owner, NULL);
    }
    cs->owner = o;
    wl_resource_set_implementation(o, &cm_surface_impl, cs, cm_surface_resource_destroy);
    droiddeck_log(TAG, "%s：由 %s 创建了 colour-management surface（它的 Vulkan swapchain 请求了 sRGB "
               "之外的 colour space）", who, droiddeck_client_name(c));
}

static void cm_get_surface_feedback(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface) {
    struct wl_resource *o = wl_resource_create(c, &wp_color_management_surface_feedback_v1_interface,
                                               wl_resource_get_version(r), id);
    if (!o) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(o, &cm_feedback_impl, NULL, NULL);
}

static void cm_create_icc_creator(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    wl_resource_post_error(r, WP_COLOR_MANAGER_V1_ERROR_UNSUPPORTED_FEATURE, "ICC image descriptions are not supported");
}

static void cm_create_parametric_creator(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct cm_params *p = calloc(1, sizeof(*p));
    if (!p) { wl_client_post_no_memory(c); return; }
    struct wl_resource *o = wl_resource_create(c, &wp_image_description_creator_params_v1_interface,
                                               wl_resource_get_version(r), id);
    if (!o) { free(p); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(o, &params_impl, p, params_resource_destroy);
}

static void cm_create_windows_scrgb(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    wl_resource_post_error(r, WP_COLOR_MANAGER_V1_ERROR_UNSUPPORTED_FEATURE, "windows_scrgb is not supported");
}

static const struct wp_color_manager_v1_interface cm_impl = {
    .destroy = cm_destroy_req,
    .get_output = cm_get_output,
    .get_surface = cm_get_surface,
    .get_surface_feedback = cm_get_surface_feedback,
    .create_icc_creator = cm_create_icc_creator,
    .create_parametric_creator = cm_create_parametric_creator,
    .create_windows_scrgb = cm_create_windows_scrgb,
};

static void bind_cm(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    static struct wl_client *last_named;
    struct wl_resource *r = wl_resource_create(c, &wp_color_manager_v1_interface, ver > 1 ? 1 : (int)ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &cm_impl, NULL, NULL);
    /* All at bind, then done: Mesa reads them in the registry roundtrip that follows the bind and
     * lists VK_COLOR_SPACE_HDR10_ST2084_EXT from exactly this pair (bt2020 + st2084_pq). Only what
     * the display layer can honour is offered. */
    wp_color_manager_v1_send_supported_intent(r, WP_COLOR_MANAGER_V1_RENDER_INTENT_PERCEPTUAL);
    wp_color_manager_v1_send_supported_feature(r, WP_COLOR_MANAGER_V1_FEATURE_PARAMETRIC);
    wp_color_manager_v1_send_supported_feature(r, WP_COLOR_MANAGER_V1_FEATURE_SET_MASTERING_DISPLAY_PRIMARIES);
    wp_color_manager_v1_send_supported_primaries_named(r, WP_COLOR_MANAGER_V1_PRIMARIES_BT2020);
    wp_color_manager_v1_send_supported_tf_named(r, WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ);
    wp_color_manager_v1_send_done(r);
    /* One line per program: Mesa binds again for every surface-format query. */
    if (last_named != c) {
        last_named = c;
        droiddeck_log(TAG, "%s 绑定了 wp_color_manager_v1 版本 %u（提供：perceptual intent；带母版元数据的 "
                   "parametric description；BT.2020 primaries；ST 2084 PQ）——它的 Vulkan 驱动现在可以列出 "
                   "VK_COLOR_SPACE_HDR10_ST2084_EXT", droiddeck_client_name(c), ver > 1 ? 1u : ver);
    }
}

void droiddeck_color_client_gone(struct wl_client *client) {
    if (atomic_load(&g_gate) != 1 || atomic_load(&g_session_ended)) return;
    /* A program that presented HDR has left: say where the session stands now, while it is fresh. */
    const char *name = droiddeck_client_name(client);
    int mine;
    pthread_mutex_lock(&g_mu);
    mine = g_hdr.applied_who[0] && name && strstr(g_hdr.applied_who, name) != NULL;
    pthread_mutex_unlock(&g_mu);
    if (mine) log_verdict(1);
}

/* ---------------------------------------------------------------- the gate */

void droiddeck_color_init(struct wl_display *display) {
    char why[320] = "";
    int mode, dxvk_hdr, zc_forced, known, hdr10, id, ratio_avail, api;
    char name[96], formats[96], source[64];
    float max_lum, max_avg, min_lum, ratio;
    pthread_mutex_lock(&g_mu);
    mode = g_req.mode; dxvk_hdr = g_req.dxvk_hdr; zc_forced = g_req.zero_copy_forced;
    known = g_req.display_known; hdr10 = g_req.hdr10; id = g_req.display_id;
    ratio_avail = g_req.ratio_available; api = g_req.api;
    snprintf(name, sizeof(name), "%s", g_req.display_name);
    snprintf(formats, sizeof(formats), "%s", g_req.formats);
    snprintf(source, sizeof(source), "%s", g_req.source);
    max_lum = g_req.max_lum; max_avg = g_req.max_avg; min_lum = g_req.min_lum; ratio = g_req.ratio;
    pthread_mutex_unlock(&g_mu);

    char disp[256];
    if (known)
        snprintf(disp, sizeof(disp), "\"%s\"（显示器 %d，Android API %d）报告的 HDR 类型为 %s，峰值 %.0f nits，"
                 "HDR/SDR 比值 %s", name, id, api, formats, max_lum, ratio_avail ? "可用" : "不可用");
    else
        snprintf(disp, sizeof(disp), "应用无法读取该显示器的 HDR 能力");

    /* What asked for HDR, in the words the user knows it by: the editors' setting, or the env override. */
    char asked[128];
    if (mode == 2) snprintf(asked, sizeof(asked), "DROIDDECK_WAYLAND_HDR=force (%s)", source);
    else if (strstr(source, "env")) snprintf(asked, sizeof(asked), "DROIDDECK_WAYLAND_HDR=1 (%s)", source);
    else snprintf(asked, sizeof(asked), "HDR 输出处于开启状态（%s）", source);

    int layer_ok = sc_layer_can_tag_hdr();
    int ahb_ok = ahb_swapchain_advertised();
    char off_why[128];
    if (strstr(source, "env")) snprintf(off_why, sizeof(off_why), "%s 中的 DROIDDECK_WAYLAND_HDR=0 覆盖了该设置", source);
    else snprintf(off_why, sizeof(off_why), "HDR 输出设置是关闭的");
    if (mode == 0)
        snprintf(why, sizeof(why), "HDR 输出已关闭（%s）", off_why);
    else if (mode == 1 && !known)
        snprintf(why, sizeof(why), "%s，但%s", asked, disp);
    else if (mode == 1 && !hdr10)
        snprintf(why, sizeof(why), "%s，但这台显示器无法显示 HDR10：%s", asked, disp);
    else if (!layer_ok)
        snprintf(why, sizeof(why), "这个 Android 没有带 dataspace 控制的 display layer"
                 "（ASurfaceControl + ASurfaceTransaction_setBufferDataSpace，Android 10+）");
    else if (!ahb_ok)
        snprintf(why, sizeof(why), "这里无法使用 zero-copy presentation（未宣告 banner_ahb_v1），"
                 "而 HDR 帧只有放在游戏自己的 display layer 上才名副其实");

    if (why[0]) {
        snprintf(g_gate_why, sizeof(g_gate_why), "%s", why);
        atomic_store(&g_gate, 0);
        if (mode) {
            droiddeck_log(TAG, "HDR 闸门已关闭：%s。什么都没有宣告（没有 wp_color_manager_v1，没有 10-bit "
                       "dma-buf 格式）：游戏看到的是一台 SDR 显示器，与不开这个开关时一模一样", why);
            if (dxvk_hdr)
                droiddeck_log(TAG, "已设置 DXVK_HDR=1：DXGI 仍会声称存在一台此处任何 swapchain 都拿不到的 "
                           "HDR 显示器（会做检查的游戏回退到 SDR；有些会显示发白）——这台显示器上请移除它");
            log_verdict(1);
        } else if (dxvk_hdr || (known && hdr10)) {
            /* Off, and silent unless it is worth a line: a display that could show HDR10, or a DXVK
             * switch that promises games an HDR display they cannot get. */
            droiddeck_log(TAG, "本次会话 HDR 输出关闭（%s）%s%s", off_why,
                       (known && hdr10) ? "——这台显示器列出了 HDR10，因此为它打开 HDR 输出就能把 HDR "
                                          "提供给游戏" : "",
                       dxvk_hdr ? "——但已设置 DXVK_HDR=1，于是 DXGI 声称存在一台游戏拿不到 swapchain "
                                  "的 HDR 显示器" : "");
        }
        return;
    }

    if (!wl_global_create(display, &wp_color_manager_v1_interface, 1, NULL, bind_cm)) {
        snprintf(g_gate_why, sizeof(g_gate_why), "创建 wp_color_manager_v1 global 失败");
        atomic_store(&g_gate, 0);
        droiddeck_log("error", "color: wp_color_manager_v1 global 创建失败——HDR 闸门关闭");
        log_verdict(1);
        return;
    }
    atomic_store(&g_gate, 1);
    char syms[160];
    sc_layer_hdr_symbols(syms, sizeof(syms));
    droiddeck_log(TAG, "HDR 闸门%s：%s，%s。向游戏提供 HDR10：wp_color_manager_v1 版本 1（BT.2020 primaries "
               "+ ST 2084 PQ，带母版元数据的 parametric description）以及 10-bit AB30/XB30 dma-buf 格式；"
               "HDR 帧以 BT2020_PQ 放到游戏自己的 display layer 上（%s）",
               mode == 2 ? "强制开启（测试用）" : "已开启", asked, disp, syms);
    if (mode == 2 && !hdr10)
        droiddeck_log(TAG, "在不支持 HDR10 的显示器上使用 DROIDDECK_WAYLAND_HDR=force——仅限测试：SurfaceFlinger "
                   "会为这块面板 tone-map 游戏 layer（预期出现 GPU/CLIENT 合成），看上去不会像 HDR");
    if (zc_forced)
        droiddeck_log(TAG, "本次会话已打开 zero-copy presentation：HDR 游戏自己的 10-bit 帧直接送到它的 "
                   "display layer（最佳路径；任何需要合成器的都会拿到合成后的 HDR 画面）");
    if (!dxvk_hdr)
        droiddeck_log(TAG, "游戏环境里没有 DXVK_HDR=1：DXVK 游戏在 DXGI 中不会看到 HDR 显示器"
                   "（dxvk.conf 里的 dxgi.enableHDR 效果相同）——把 DXVK_HDR=1 加到容器或快捷方式的"
                   "环境变量里");
    (void)max_avg; (void)min_lum;
}
