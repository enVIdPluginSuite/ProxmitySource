package dev.etran.proxmitysource

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import kotlin.math.roundToInt

/**
 * Turns a block type into a "wall factor" (x50): 0 = sound passes freely, 50 = a normal solid block.
 * Built from config.yml (profile.wall-materials). Instances cache lookups and are NOT thread safe:
 * create one per bake and use it from a single thread.
 */
class WallRules(cfg: FileConfiguration) {
    private val rules = ArrayList<Pair<String, Double>>()
    private val partial = cfg.getDouble("profile.wall-partial-factor", 0.6)
    private val cache = HashMap<Material, Int>()

    init {
        val sec = cfg.getConfigurationSection("profile.wall-materials")
        if (sec != null) for (k in sec.getKeys(false)) rules.add(k.uppercase() to sec.getDouble(k))
    }

    fun factor50(m: Material): Int = cache.getOrPut(m) {
        if (m.isAir) {
            0
        } else {
            val name = m.name
            val rule = rules.firstOrNull { name.contains(it.first) }
            val f = when {
                rule != null -> rule.second
                !m.isSolid -> 0.0
                !m.isOccluding -> partial
                else -> 1.0
            }
            (f * 50).roundToInt().coerceIn(0, 255)
        }
    }

    /** Text that changes whenever the rules change (part of the profile's staleness hash). */
    fun signature(): String = rules.joinToString(",") { "${it.first}=${it.second}" } + ";p=$partial"
}
