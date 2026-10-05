package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.TarZst;
import com.droiddeck.launcher.session.SessionPrefs;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The Vulkan driver the in-app compositor runs on. It has to be Turnip: the system Adreno driver
 * does not implement VK_EXT_image_drm_format_modifier, so importing the dma-bufs gamescope hands
 * over fails and the session renders nothing. Two builds ship in the apk, one per Adreno
 * generation, and the GPU decides which is unpacked - unless the user has imported an AdrenoTools
 * zip of their own and chosen it, which then wins.
 *
 * <p>The guest's own Turnip is a different copy entirely - a glibc build inside the rootfs, or an
 * imported one ({@link LinuxVulkanDriverManager}). This one is the bionic build the app process
 * loads through adrenotools, and it is what every session ends in: whatever draws inside the
 * runtime, this is the driver that puts the frame on the panel.
 *
 * <p>Every driver, bundled or imported, lives under {@code files/graphics_driver/<id>/} with a
 * {@code meta.json} whose {@code libraryName} names the .so beside it - the AdrenoTools layout.
 * The import path is ported from Bannerlator's {@code AdrenotoolsManager} (GPL-3.0), including
 * the refusal of zips that belong in the Linux runtime list: a "-Linux" (glibc) Turnip names no
 * library on purpose, and installed here it would put the compositor on the system Vulkan - a
 * black screen wearing a driver's name.
 */
public final class TurnipDriver {
    private static final String TAG = "TurnipDriver";
    /** Adreno 7xx (8 Gen 2/3, e.g. the Pocket FIT's A750). */
    private static final String DRIVER_A7XX = "turnip25.1.0";
    /** Adreno 8xx (8 Elite, e.g. a Fold 8's A830). */
    private static final String DRIVER_A8XX = "turnip-sdk36";
    /** The two the apk carries; never removable, unpacked on demand. */
    public static final List<String> BUNDLED = Collections.unmodifiableList(Arrays.asList(DRIVER_A7XX, DRIVER_A8XX));
    /** Overrides the pick, for a device we cannot reach: a7xx, a8xx or system. */
    private static final String OVERRIDE_FILE = "Download/droiddeck-driver";
    /** Bundled drivers the user deleted: hidden from the list, their unpacked copy removed. */
    private static final String PREFS = "graphics_driver";
    private static final String KEY_HIDDEN = "hiddenBundled";
    /** Stored choice meaning "pick by GPU" - the default, and what a removed import falls back to. */
    public static final String AUTO = "";

    public static final String HELP_TEXT =
            "应用内合成器把画面送上屏幕的驱动 —— 每次会话（Steam 或桌面）的最后一步。"
            + "仅接受 AdrenoTools 压缩包（vulkan.adXXXX.so）：“-Linux” 的 Turnip "
            + "属于上方的 Linux 运行时驱动列表，在这里会被拒绝。合成器每个应用进程只加载一次驱动，"
            + "改动需在应用完全退出并重新启动后才生效。";

    private final Context context;
    private final File contentDir;

    public TurnipDriver(Context context) {
        this.context = context;
        this.contentDir = new File(context.getFilesDir(), "graphics_driver");
    }

    /** Absolute directory holding the driver, with a trailing slash - adrenotools wants both. */
    public String driverPath(String driverId) {
        return new File(contentDir, driverId).getAbsolutePath() + "/";
    }

    public String libraryName(String driverId) {
        JSONObject meta = readMeta(driverId);
        return meta != null ? meta.optString("libraryName", null) : null;
    }

    /** meta.json's "name", or the id when it has none. */
    public String displayName(String driverId) {
        JSONObject meta = readMeta(driverId);
        String name = meta != null ? meta.optString("name", "") : "";
        return name.isEmpty() ? driverId : name;
    }

    public String driverVersion(String driverId) {
        JSONObject meta = readMeta(driverId);
        return meta != null ? meta.optString("driverVersion", "") : "";
    }

    private JSONObject readMeta(String driverId) {
        if (driverId == null || driverId.isEmpty() || driverId.contains("/") || driverId.contains("..")) return null;
        File meta = new File(new File(contentDir, driverId), "meta.json");
        String content = FileUtils.readString(meta);
        if (content == null) return null;
        try {
            return new JSONObject(content);
        } catch (Exception e) {
            Log.w(TAG, "读取 " + driverId + " 的 meta.json 失败", e);
            return null;
        }
    }

    /** An imported driver is installed when its meta.json names a library that is there. */
    public boolean isInstalled(String driverId) {
        String library = libraryName(driverId);
        return library != null && !library.isEmpty() && new File(new File(contentDir, driverId), library).isFile();
    }

    /**
     * The bundled drivers the list shows: {@link #BUNDLED} minus the ones the user deleted. A deleted
     * one still lives in the apk and Auto still unpacks it on a GPU that needs it, so deleting can
     * never leave a device without a driver; it only leaves the list.
     */
    public List<String> visibleBundled() {
        java.util.Set<String> hidden = hiddenBundled();
        ArrayList<String> out = new ArrayList<>();
        for (String id : BUNDLED) if (!hidden.contains(id)) out.add(id);
        return out;
    }

    public java.util.Set<String> hiddenBundled() {
        return new java.util.HashSet<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_HIDDEN, java.util.Collections.<String>emptySet()));
    }

    /** "Delete" for a bundled driver: remove its unpacked copy and hide it from the list. */
    public void hideBundled(String driverId) {
        if (!BUNDLED.contains(driverId)) return;
        FileUtils.delete(new File(contentDir, driverId));
        java.util.Set<String> hidden = hiddenBundled();
        hidden.add(driverId);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_HIDDEN, hidden).apply();
        Log.i(TAG, "内置驱动 " + driverId + " 已被用户隐藏");
    }

    public void restoreBundled() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HIDDEN).apply();
    }

    /** Imported drivers only - the bundled two are listed by {@link #BUNDLED}. */
    public List<String> enumerateImported() {
        ArrayList<String> ids = new ArrayList<>();
        File[] dirs = contentDir.listFiles();
        if (dirs == null) return ids;
        for (File d : dirs) {
            String id = d.getName();
            if (d.isDirectory() && !BUNDLED.contains(id) && !id.startsWith(".") && isInstalled(id)) ids.add(id);
        }
        Collections.sort(ids);
        return ids;
    }

    /** The bundled build the GPU would pick on its own, for the "Auto" row's label. */
    public String autoId() {
        String id = chooseByDevice();
        return id == null ? "system" : id;
    }

    public void remove(String driverId) {
        if (driverId == null || driverId.isEmpty() || BUNDLED.contains(driverId)
                || driverId.contains("/") || driverId.contains("..")) return;
        Log.d(TAG, "正在移除导入的驱动 " + driverId);
        FileUtils.delete(new File(contentDir, driverId));
        if (driverId.equals(SessionPrefs.androidDriver(context))) SessionPrefs.setAndroidDriver(context, AUTO);
    }

    /**
     * Import an AdrenoTools zip. Returns the new driver id.
     * @throws IllegalArgumentException with a user-facing reason when the zip is not an AdrenoTools
     *         driver - including when it is a Linux runtime driver, which says which list it belongs in;
     *         {@link IOException} on read/extract failures.
     */
    public String installFromZip(Uri zipUri, String displayName) throws IOException {
        if (!contentDir.exists()) contentDir.mkdirs();
        File tmpDir = new File(contentDir, ".tmp-" + System.currentTimeMillis());
        FileUtils.delete(tmpDir);
        if (!tmpDir.mkdirs()) throw new IOException("无法创建 " + tmpDir);
        boolean keep = false;
        try {
            try (InputStream is = context.getContentResolver().openInputStream(zipUri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                if (is == null) throw new IOException("无法打开 " + zipUri);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    // Flatten: adrenotools reads the .so beside meta.json, and a base name also
                    // defeats zip-slip paths.
                    String base = new File(entry.getName()).getName();
                    if (base.isEmpty()) continue;
                    Files.copy(zis, new File(tmpDir, base).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            String rejected = rejectionReason(tmpDir);
            if (rejected != null) throw new IllegalArgumentException("不是 AdrenoTools 驱动：" + rejected);

            JSONObject meta = readMetaFile(new File(tmpDir, "meta.json"));
            String name = meta != null ? meta.optString("name", "") : "";
            if (name.isEmpty()) {
                name = displayName != null ? displayName : "adrenotools-driver";
                if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
            }
            String id = uniqueId(LinuxVulkanDriverManager.sanitizeId(name));
            File dir = new File(contentDir, id);
            if (!tmpDir.renameTo(dir)) throw new IOException("无法移动到 " + dir);
            keep = true;
            Log.i(TAG, "已导入 AdrenoTools 驱动 " + id + " (" + (meta != null ? meta.optString("libraryName", "") : "?") + ") -> " + dir);
            return id;
        } finally {
            if (!keep) FileUtils.delete(tmpDir);
        }
    }

    private static JSONObject readMetaFile(File metaFile) {
        try {
            return new JSONObject(FileUtils.readString(metaFile));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Why a just-extracted zip is not something adrenotools can be pointed at, or null when it is.
     * The kinds refused by name are the other driver lists' zips, which carry a meta.json too.
     */
    private static String rejectionReason(File dir) {
        File metaFile = new File(dir, "meta.json");
        if (!metaFile.isFile()) return "缺少 meta.json";
        String libraryName, kind;
        try {
            JSONObject meta = new JSONObject(FileUtils.readString(metaFile));
            libraryName = meta.optString("libraryName", "");
            kind = meta.optString("kind", "");
        } catch (Exception e) {
            return "meta.json 无法读取（" + e.getMessage() + "）";
        }
        if ("linux-vulkan-icd".equals(kind))
            return "这是 Linux 运行时驱动（请在“运行时驱动”一栏导入）";
        if ("wayland-game-driver".equals(kind))
            return "这是 Wayland 游戏驱动，本应用用不到";
        if (libraryName.isEmpty())
            return "meta.json 未指定 libraryName，无法交给 AdrenoTools";
        File library = new File(dir, libraryName);
        if (!library.isFile())
            return "meta.json 指定的 " + libraryName + " 不在压缩包中";
        if (!LinuxVulkanDriverManager.isAarch64Elf(library))
            return libraryName + " 不是 64 位 AArch64 ELF 共享库";
        // A glibc build that happens to carry a libraryName would load nothing in this process.
        if (LinuxVulkanDriverManager.containsAscii(library, "libc.so.6"))
            return libraryName + " 链接了 glibc —— 属于“运行时驱动”的“-Linux” Turnip";
        return null;
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (new File(contentDir, id).exists()) id = base + "-" + (n++);
        return id;
    }

    /**
     * Unpacks the driver this device needs and returns its id, or null to fall back to the system
     * Vulkan loader (which means a black session on Adreno, but is better than refusing to start
     * on a GPU neither build covers).
     */
    public String install() {
        String id = choose();
        if (id == null) return null;
        File dir = new File(contentDir, id);
        if (!new File(dir, "meta.json").isFile()) {
            FileUtils.delete(dir);
            if (!TarZst.extractAsset(context, "graphics_driver/adrenotools-" + id + ".tzst", dir)) {
                Log.e(TAG, "无法解包 " + id);
                FileUtils.delete(dir);
                return null;
            }
        }
        String library = libraryName(id);
        if (library == null || !new File(dir, library).isFile()) {
            Log.e(TAG, id + " 解包后缺少 " + library);
            return null;
        }
        Log.i(TAG, "图形驱动 " + id + "（" + library + "）");
        return id;
    }

    /**
     * The user's choice first - an imported driver, or one of the bundled two pinned by hand -
     * then the Downloads override, then the GPU. A chosen import that has since been removed logs
     * and falls through, so a stale preference never blocks a session.
     */
    private String choose() {
        String chosen = SessionPrefs.androidDriver(context);
        if (chosen != null && !chosen.isEmpty()) {
            if (BUNDLED.contains(chosen)) {
                Log.i(TAG, "用户固定的驱动：" + chosen);
                return chosen;
            }
            if (isInstalled(chosen)) {
                Log.i(TAG, "用户选择的导入驱动：" + chosen);
                return chosen;
            }
            Log.w(TAG, "所选驱动 \"" + chosen + "\" 已不存在，改按 GPU 选择");
        }
        return chooseByDevice();
    }

    private String chooseByDevice() {
        File override = new File(Environment.getExternalStorageDirectory(), OVERRIDE_FILE);
        String forced = override.isFile() ? FileUtils.readString(override) : null;
        if (forced != null) {
            forced = forced.trim().toLowerCase(java.util.Locale.US);
            Log.i(TAG, "由 " + override + " 强制指定的驱动：" + forced);
            if (forced.startsWith("system")) return null;
            if (forced.startsWith("a8")) return DRIVER_A8XX;
            if (forced.startsWith("a7")) return DRIVER_A7XX;
        }
        String model = gpuModel();
        Log.i(TAG, "GPU 型号：" + (model == null ? "未知" : model));
        if (model != null) {
            // "Adreno750", "adreno_830" - the generation is the first digit of the three.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d)\\d\\d").matcher(model);
            if (m.find()) {
                char generation = m.group(1).charAt(0);
                if (generation >= '8') return DRIVER_A8XX;
                if (generation == '7') return DRIVER_A7XX;
            }
        }
        // Nothing to go on: Android 16 shipped with the 8 Elite, so treat a new device as 8xx.
        return android.os.Build.VERSION.SDK_INT >= 36 ? DRIVER_A8XX : DRIVER_A7XX;
    }

    /** KGSL names the GPU here, and this file is world-readable where /dev/kgsl-3d0 is not. */
    private static String gpuModel() {
        for (String path : new String[]{
                "/sys/class/kgsl/kgsl-3d0/gpu_model",
                "/sys/class/kgsl/kgsl-3d0/gpu_chipid"}) {
            String value = FileUtils.readString(new File(path));
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }
}
