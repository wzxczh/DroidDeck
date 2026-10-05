package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.runtime.LinuxRuntime;

import java.io.File;

/**
 * LSFG frame generation's shader chain: extracted from the user's own copy of Lossless Scaling
 * and cached on device.
 *
 * <p>The 25 compute shaders are not redistributable. They live as resources inside the user's
 * paid {@code Lossless.dll} (Steam app 993090), which in this app the user installs from the
 * Steam client itself - so the DLL sits in the runtime's own Steam library. It is mapped
 * read-only and parsed as data, never loaded or executed. Extraction is slow enough to be worth
 * doing once (the DXBC chain is translated to SPIR-V), so the result is cached, keyed on the
 * DLL's size and hash. Call {@link #ensureCache} off the main thread.
 *
 * <p>Ported from Bannerlator's LsfgNative; the native side is the same lsfg_jni.cpp compiled into
 * the compositor library.
 */
public final class LsfgNative {
    private static final String TAG = "LsfgNative";

    static { System.loadLibrary("bannerwayland"); }

    private LsfgNative() {}

    // Mirrors lsfg::DllStatus.
    public static final int STATUS_OK = 0;
    public static final int STATUS_NOT_INSTALLED = 1;
    public static final int STATUS_UNREADABLE_FILE = 2;
    public static final int STATUS_NOT_PORTABLE_EXECUTABLE = 3;
    public static final int STATUS_MISSING_SHADERS = 4;
    public static final int STATUS_TRANSLATION_FAILED = 5;
    public static final int STATUS_CACHE_UNUSABLE = 6;

    private static native int nativeValidateDll(String dllPath);
    private static native int nativeDllVariant(String dllPath, boolean preferFp16);
    private static native int nativeBuildCache(String dllPath, String cachePath, boolean preferFp16);
    private static native boolean nativeCacheMatchesSource(String cachePath, String dllPath);
    private static native int nativeCacheVariant(String cachePath);
    private static native String nativeStatusName(int status);
    private static native String nativeVariantName(int variant);

    /** Where Lossless Scaling lands when installed from the client inside the runtime. */
    public static File losslessDll(Context context) {
        return new File(LinuxRuntime.rootDir(context),
                "root/.local/share/Steam/steamapps/common/Lossless Scaling/Lossless.dll");
    }

    /** Where the translated SPIR-V chain is cached. */
    public static File cacheFile(Context context) {
        return new File(context.getFilesDir(), "lsfg-native/shaders.cache");
    }

    public static boolean isInstalled(Context context) {
        return losslessDll(context).isFile();
    }

    /**
     * Makes sure a usable cache exists for the installed DLL, building it if the DLL is new or
     * changed. Returns a {@code STATUS_*} code; {@link #STATUS_OK} means the engine can start.
     */
    public static int ensureCache(Context context) {
        File dll = losslessDll(context);
        if (!dll.isFile()) return STATUS_NOT_INSTALLED;
        File cache = cacheFile(context);
        try {
            if (cache.isFile() && nativeCacheMatchesSource(cache.getPath(), dll.getPath())) {
                return STATUS_OK;
            }
            int status = nativeValidateDll(dll.getPath());
            if (status != STATUS_OK) {
                Log.w(TAG, "Lossless.dll rejected: " + statusName(status));
                return status;
            }
            //noinspection ResultOfMethodCallIgnored
            cache.getParentFile().mkdirs();
            status = nativeBuildCache(dll.getPath(), cache.getPath(), true);
            Log.i(TAG, "cache build: " + statusName(status)
                    + (status == STATUS_OK ? " (" + nativeVariantName(nativeCacheVariant(cache.getPath())) + ")" : ""));
            return status;
        } catch (Throwable t) {
            Log.e(TAG, "ensureCache", t);
            return STATUS_CACHE_UNUSABLE;
        }
    }

    public static String statusName(int status) {
        try {
            return nativeStatusName(status);
        } catch (Throwable t) {
            return "status " + status;
        }
    }
}
