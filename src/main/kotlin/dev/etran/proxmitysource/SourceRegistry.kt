package dev.etran.proxmitysource

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** Holds every source and persists them to plugins/ProxmitySource/sources.yml. */
class SourceRegistry(private val plugin: ProxmitySourcePlugin) {
    private val file = File(plugin.dataFolder, "sources.yml")
    private val map = LinkedHashMap<String, Source>()

    fun all(): Collection<Source> = map.values
    fun get(id: String): Source? = map[id.lowercase()]
    fun ids(): List<String> = map.keys.toList()
    fun add(s: Source) {
        map[s.id] = s
    }

    fun remove(id: String): Boolean = map.remove(id.lowercase()) != null

    fun load() {
        map.clear()
        if (!file.exists()) return
        val yml = YamlConfiguration.loadConfiguration(file)
        val root = yml.getConfigurationSection("sources") ?: return
        for (id in root.getKeys(false)) {
            val sec = root.getConfigurationSection(id) ?: continue
            val world = sec.getString("world")
            val pos = sec.getDoubleList("position")
            if (world == null || pos.size != 3) {
                plugin.logger.warning("Skipping source '$id' in sources.yml: missing world/position.")
                continue
            }
            val s = Source(id.lowercase(), world, pos[0], pos[1], pos[2])
            s.enabled = sec.getBoolean("enabled", true)
            for (t in sec.getStringList("triggers")) {
                val tt = TriggerType.parse(t)
                if (tt != null) s.triggers.add(tt) else plugin.logger.warning("Source '$id': unknown trigger '$t'.")
            }
            sec.getStringList("entity-types").forEach { s.entityTypes.add(it.uppercase()) }
            sec.getStringList("materials").forEach { s.materials.add(it.uppercase()) }
            sec.getIntegerList("hours").forEach { s.hours.add(it) }
            val ov = sec.getConfigurationSection("overrides")
            if (ov != null) {
                for (p in Prop.entries) {
                    if (!ov.contains(p.key)) continue
                    s.overrides[p] = when (p.type) {
                        ValueType.STRING -> ov.getString(p.key) ?: continue
                        ValueType.DOUBLE -> ov.getDouble(p.key)
                        ValueType.INT -> ov.getInt(p.key)
                        ValueType.BOOL -> ov.getBoolean(p.key)
                    }
                }
            }
            map[s.id] = s
        }
    }

    fun save() {
        val yml = YamlConfiguration()
        for (s in map.values) {
            val b = "sources.${s.id}"
            yml.set("$b.world", s.world)
            yml.set("$b.position", listOf(s.x, s.y, s.z))
            yml.set("$b.enabled", s.enabled)
            yml.set("$b.triggers", s.triggers.map { it.name })
            yml.set("$b.entity-types", s.entityTypes.toList())
            yml.set("$b.materials", s.materials.toList())
            yml.set("$b.hours", s.hours.toList())
            for ((p, v) in s.overrides) yml.set("$b.overrides.${p.key}", v)
        }
        plugin.dataFolder.mkdirs()
        yml.save(file)
    }
}
