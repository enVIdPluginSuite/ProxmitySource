package dev.etran.proxmitysource

import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.configuration.file.FileConfiguration
import java.util.TreeSet
import kotlin.math.floor

/** Things that can make a source sound. All of them look at a sphere (trigger-radius) around the source. */
enum class TriggerType(val help: String) {
    PLAYER_ENTER("a player comes within the trigger radius"),
    PLAYER_EXIT("a player leaves the trigger radius"),
    PLAYER_INTERACT("a player right-clicks a block within the trigger radius"),
    ENTITY_ENTER("a mob/entity comes within the trigger radius (see /proxsource filter)"),
    REDSTONE("a redstone signal switches on within the trigger radius"),
    TIME("the in-game clock reaches a given hour (see /proxsource hours)"),
    TIMER("repeating: every frequency-seconds of real time, whether or not anyone is nearby");

    companion object {
        fun parse(s: String): TriggerType? =
            entries.firstOrNull { it.name.equals(s.trim().replace('-', '_'), ignoreCase = true) }
    }
}

enum class ValueType { STRING, DOUBLE, INT, BOOL }

/**
 * A setting a source can override. Anything not overridden comes from `defaults.<key>` in config.yml.
 * [rebake] marks settings that change the baked profile (so the source is re-baked when they change).
 */
enum class Prop(
    val key: String,
    val type: ValueType,
    val min: Double = 0.0,
    val max: Double = Double.MAX_VALUE,
    val rebake: Boolean = false,
) {
    SOUND("sound", ValueType.STRING),
    CATEGORY("category", ValueType.STRING),
    RADIUS("radius", ValueType.DOUBLE, 1.0, 1024.0, rebake = true),
    ECHOINESS("echoiness", ValueType.DOUBLE, 0.0, 1.0),
    PENETRATION("penetration", ValueType.DOUBLE, 0.0, 1.0, rebake = true),
    VOLUME("volume", ValueType.DOUBLE, 0.0, 1.0),
    AMPLIFICATION("amplification", ValueType.DOUBLE, 0.1, 8.0),
    FALLOFF("falloff", ValueType.DOUBLE, 0.1, 8.0),
    PITCH("pitch", ValueType.DOUBLE, 0.5, 2.0),
    COOLDOWN("cooldown-seconds", ValueType.DOUBLE, 0.0, 86400.0),
    TOLLS("tolls", ValueType.INT, 1.0, 64.0),
    TOLL_INTERVAL("toll-interval-seconds", ValueType.DOUBLE, 0.05, 60.0),
    FREQUENCY("frequency-seconds", ValueType.DOUBLE, 1.0, 604800.0),
    TOLL_BY_HOUR("toll-by-hour", ValueType.BOOL),
    TRIGGER_RADIUS("trigger-radius", ValueType.DOUBLE, 0.5, 64.0),
    ECHO_MAX_COUNT("echo-max-count", ValueType.INT, 0.0, 16.0),
    ECHO_DELAY("echo-delay-ticks", ValueType.INT, 1.0, 200.0),
    ECHO_DECAY("echo-decay", ValueType.DOUBLE, 0.05, 0.95),
    ECHO_PITCH_SHIFT("echo-pitch-shift", ValueType.DOUBLE, 0.5, 1.5);

    val configPath: String get() = "defaults.$key"

    /** Parses and validates user input. Throws IllegalArgumentException with a readable message. */
    fun parse(raw: String): Any {
        val s = raw.trim()
        return when (type) {
            ValueType.STRING -> {
                require(s.isNotEmpty()) { "Value cannot be empty." }
                if (this == CATEGORY) {
                    val cat = s.uppercase()
                    require(SoundCategory.values().any { it.name == cat }) {
                        "Unknown category. Use one of: " + SoundCategory.values().joinToString(", ") { it.name }
                    }
                    cat
                } else s
            }
            ValueType.DOUBLE -> {
                val d = s.toDoubleOrNull() ?: throw IllegalArgumentException("'$s' is not a number.")
                require(d in min..max) { "$key must be between $min and $max." }
                d
            }
            ValueType.INT -> {
                val i = s.toIntOrNull() ?: throw IllegalArgumentException("'$s' is not a whole number.")
                require(i.toDouble() in min..max) { "$key must be between ${min.toInt()} and ${max.toInt()}." }
                i
            }
            ValueType.BOOL -> when (s.lowercase()) {
                "true", "on", "yes", "enable", "enabled" -> true
                "false", "off", "no", "disable", "disabled" -> false
                else -> throw IllegalArgumentException("'$s' is not true/false.")
            }
        }
    }

    companion object {
        fun byKey(k: String): Prop? = entries.firstOrNull { it.key.equals(k.trim(), ignoreCase = true) }
    }
}

/** A point in a world that emits sound. Its reach is shaped by a baked [Profile]. */
class Source(val id: String, var world: String, var x: Double, var y: Double, var z: Double) {
    var enabled: Boolean = true
    val triggers: MutableSet<TriggerType> = linkedSetOf()

    /** ENTITY_ENTER filter. Empty = any living, non-player, non-armor-stand entity. Upper-case EntityType names. */
    val entityTypes: MutableSet<String> = linkedSetOf()

    /** PLAYER_INTERACT filter. Empty = any block. Upper-case Material names. */
    val materials: MutableSet<String> = linkedSetOf()

    /** TIME trigger hours (0-23 clock time, 6 = sunrise). Empty = every hour. */
    val hours: MutableSet<Int> = TreeSet()

    val overrides: MutableMap<Prop, Any> = linkedMapOf()

    val blockX: Int get() = floor(x).toInt()
    val blockY: Int get() = floor(y).toInt()
    val blockZ: Int get() = floor(z).toInt()

    fun distSq(px: Double, py: Double, pz: Double): Double {
        val a = px - x
        val b = py - y
        val c = pz - z
        return a * a + b * b + c * c
    }

    fun locationIn(world: World) = Location(world, x, y, z)

    fun str(p: Prop, cfg: FileConfiguration): String = (overrides[p] as? String) ?: cfg.getString(p.configPath) ?: ""
    fun dbl(p: Prop, cfg: FileConfiguration): Double = (overrides[p] as? Number)?.toDouble() ?: cfg.getDouble(p.configPath)
    fun int(p: Prop, cfg: FileConfiguration): Int = (overrides[p] as? Number)?.toInt() ?: cfg.getInt(p.configPath)
    fun bool(p: Prop, cfg: FileConfiguration): Boolean = (overrides[p] as? Boolean) ?: cfg.getBoolean(p.configPath)

    fun valueText(p: Prop, cfg: FileConfiguration): String = when (p.type) {
        ValueType.STRING -> str(p, cfg)
        ValueType.DOUBLE -> dbl(p, cfg).toString()
        ValueType.INT -> int(p, cfg).toString()
        ValueType.BOOL -> bool(p, cfg).toString()
    }
}
