// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// LSFG from the work of Camille LaVey / the Eden Emulator Project, following
// upstream lsfg-vk. Ported to WinNative and DroidDeck by @maxjivi05; only the
// Vulkan dispatch differs here (see lsfg_vkd.h).

#include "lsfg_shaders.hpp"
#include "lsfg_common.hpp"
#include "lsfg_dll.h"

#include <android/log.h>

#define LOG_TAG "LsfgShaders"
#define SHADER_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define SHADER_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lsfg {

LsfgShaders::LsfgShaders(const Device& device_, const std::string& cache_path,
                         uint32_t spirv_target)
    : device{device_.Handle()} {
    ModuleSet set;
    const DllStatus status = loadModules(cache_path, set);
    if (status != DllStatus::Ok) {
        SHADER_LOGE("着色器缓存不可用（%s）", statusName(status));
        return;
    }

    // Vulkan 1.1/1.2 compat: the cache is always built at the translator's
    // native SPIR-V 1.6 (no device exists at import time), so lower each module
    // to what THIS device accepts. A no-op on 1.3+ devices. Only the
    // DXBC-translated chain is lowered: downgradeSpirv knows the capabilities
    // DXVK emits, not whatever a precompiled vendor SPIR-V variant may carry,
    // and relabelling one of those could hand the driver invalid SPIR-V.
    uint32_t lowered = 0;
    for (Module& module : set.modules) {
        if (module.words.size() > 1 && module.words[1] > spirv_target) {
            if (set.variant != Variant::DxbcTranslated) {
                SHADER_LOGE("着色器 %u：预编译 SPIR-V 0x%x 高于此设备支持的 0x%x；不做降级",
                            module.id, module.words[1], spirv_target);
                return;
            }
            if (!downgradeSpirv(module.words, spirv_target)) {
                SHADER_LOGE("着色器 %u：无法将 SPIR-V 0x%x 降级到 0x%x",
                            module.id, module.words[1], spirv_target);
                return;
            }
            lowered++;
        }
    }
    if (lowered)
        SHADER_LOGI("已为此设备将 %u 个模块降级到 SPIR-V 0x%x", lowered, spirv_target);

    for (const Module& module : set.modules) {
        VkShaderModuleCreateInfo module_ci{};
        module_ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        module_ci.codeSize = module.words.size() * sizeof(uint32_t);
        module_ci.pCode = module.words.data();

        VkShaderModule handle = VK_NULL_HANDLE;
        if (vkd.CreateShaderModule(device, &module_ci, nullptr, &handle) != VK_SUCCESS) {
            SHADER_LOGE("着色器 %u 的 vkCreateShaderModule 失败", module.id);
            Release();
            return;
        }
        modules.emplace(module.id, handle);
    }

    valid = modules.size() == kShaderCount;
    if (valid) {
        SHADER_LOGI("已创建 %zu 个 LSFG 着色器模块，variant=%s", modules.size(),
                    variantName(set.variant));
    } else {
        SHADER_LOGE("应有 %u 个着色器模块，实际 %zu 个", kShaderCount, modules.size());
        Release();
    }
}

LsfgShaders::~LsfgShaders() {
    Release();
}

void LsfgShaders::Release() {
    if (device != VK_NULL_HANDLE) {
        for (auto& [id, module] : modules) {
            vkd.DestroyShaderModule(device, module, nullptr);
        }
    }
    modules.clear();
    valid = false;
}

VkShaderModule LsfgShaders::Get(uint32_t shader_id) const {
    const auto it = modules.find(shader_id);
    return it == modules.end() ? VK_NULL_HANDLE : it->second;
}

}
