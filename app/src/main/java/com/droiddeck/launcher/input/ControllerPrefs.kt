package com.droiddeck.launcher.input

import android.content.Context

object ControllerPrefs {
    const val STEAM_BLUE = 0xFF1A9FFF.toInt()
    const val OFF = "off"

    val tints = listOf(
        STEAM_BLUE to "Steam 蓝",
        0xFF66C0F4.toInt() to "天蓝",
        0xFFE8EEF4.toInt() to "白色",
        0xFFC77DFF.toInt() to "紫色",
        0xFF3DDC84.toInt() to "绿色",
        0xFFFFA726.toInt() to "琥珀",
        0xFFFF5252.toInt() to "红色",
        0xFFFF6FB5.toInt() to "粉色",
    )

    val opacities = listOf(40, 60, 80, 100)

    val sizes = listOf(80, 90, 100, 110, 125)

    val mappable = listOf(
        "a" to "A 键", "b" to "B 键", "x" to "X 键", "y" to "Y 键",
        "lb" to "左肩键", "rb" to "右肩键", "lt" to "左扳机", "rt" to "右扳机",
        "select" to "View 键", "start" to "菜单键",
    )

    val targets = listOf(
        "a" to "A", "b" to "B", "x" to "X", "y" to "Y",
        "lb" to "LB", "rb" to "RB", "lt" to "LT", "rt" to "RT",
        "l3" to "L3", "r3" to "R3", "select" to "View", "start" to "菜单", "guide" to "Steam",
        "up" to "方向键上", "down" to "方向键下", "left" to "方向键左", "right" to "方向键右",
        OFF to "隐藏",
    )

    class Settings(
        val tint: Int,
        val opacity: Int,
        val size: Int,
        val stickClick: Boolean,
        val adaptiveSticks: Boolean,
        val customLayout: Boolean,
        val mapping: Map<String, String>,
    )

    private fun prefs(context: Context) = context.getSharedPreferences("controller", Context.MODE_PRIVATE)

    fun read(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            tint = p.getInt("tint", STEAM_BLUE),
            opacity = p.getInt("opacity", 100).takeIf { it in opacities } ?: 100,
            size = p.getInt("size", 100).takeIf { it in sizes } ?: 100,
            stickClick = p.getBoolean("stickClick", true),
            adaptiveSticks = p.getBoolean("adaptiveSticks", true),
            customLayout = p.all.keys.any { it.startsWith("layout.") },
            mapping = mappable.associate { (id, _) -> id to target(context, id) },
        )
    }

    fun target(context: Context, id: String): String =
        prefs(context).getString("map.$id", id)?.takeIf { t -> targets.any { it.first == t } } ?: id

    fun setTarget(context: Context, id: String, target: String) {
        prefs(context).edit().putString("map.$id", target).apply()
    }

    fun resetMapping(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("map.") }.forEach { remove(it) } }.apply()
    }

    fun setTint(context: Context, tint: Int) {
        prefs(context).edit().putInt("tint", tint).apply()
    }

    fun setOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt("opacity", percent).apply()
    }

    fun setSize(context: Context, percent: Int) {
        prefs(context).edit().putInt("size", percent).apply()
    }

    fun setStickClick(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("stickClick", on).apply()
    }

    fun setAdaptiveSticks(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("adaptiveSticks", on).apply()
    }

    fun layout(context: Context, width: Int, height: Int): Map<String, Pair<Float, Float>> {
        val raw = prefs(context).getString("layout.${width}x$height", null) ?: return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val (group, pos) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val (x, y) = pos.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            group to (x to y)
        }.toMap()
    }

    fun setLayout(context: Context, width: Int, height: Int, positions: Map<String, Pair<Float, Float>>) {
        val raw = positions.entries.joinToString(";") { (group, pos) -> "$group:${pos.first},${pos.second}" }
        prefs(context).edit().putString("layout.${width}x$height", raw).apply()
    }

    fun resetLayout(context: Context, width: Int, height: Int) {
        prefs(context).edit().remove("layout.${width}x$height").apply()
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun resetAllLayouts(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("layout.") }.forEach { remove(it) } }.apply()
    }
}
