package dev.etran.proxmitysource

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID

class ProxmitySourcePlugin : JavaPlugin() {

    lateinit var registry: SourceRegistry
        private set
    lateinit var engine: SoundEngine
        private set
    lateinit var triggers: TriggerManager
        private set
    lateinit var soundPack: SoundPack
        private set
    lateinit var baker: ProfileBaker
        private set

    /** Baked profiles by source id. Only touched on the main thread. */
    val profiles = HashMap<String, Profile>()

    private val muted = HashSet<UUID>()
    private val mutesFile get() = File(dataFolder, "muted.yml")

    override fun onEnable() {
        saveDefaultConfig()
        registry = SourceRegistry(this)
        engine = SoundEngine(this)
        soundPack = SoundPack(this)
        triggers = TriggerManager(this)
        baker = ProfileBaker(this)

        server.pluginManager.registerEvents(triggers, this)
        server.pluginManager.registerEvents(soundPack, this)
        server.pluginManager.registerEvents(baker, this)

        val cmd = ProxmityCommand(this)
        getCommand("proxsource")?.let {
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        // Wait one tick so worlds are loaded before sources are read.
        server.scheduler.runTask(this, Runnable {
            val notes = reloadAll()
            logger.info("Loaded ${registry.all().size} source(s), ${soundPack.count()} custom sound(s).")
            notes.forEach { logger.warning(it) }
        })
    }

    override fun onDisable() {
        triggers.stop()
        soundPack.stopHost()
        baker.shutdown()
    }

    fun profileFile(id: String) = File(File(dataFolder, "profiles"), "$id.prx")

    /** Re-reads config.yml, sources.yml, mutes and the sounds folder. Returns warnings to show. */
    fun reloadAll(): List<String> {
        reloadConfig()
        loadMutes()
        registry.load()
        val notes = soundPack.reload()
        triggers.restart()
        baker.restartWatcher()
        syncProfiles()
        return notes
    }

    /**
     * Makes sure every source has a profile: loads saved ones from disk (off the main thread), and queues a bake
     * for sources that have none, or whose saved profile no longer matches their settings (if auto-rebake is on).
     */
    fun syncProfiles() {
        profiles.keys.retainAll(registry.ids().toSet())
        val autoRebake = config.getBoolean("profile.auto-rebake", true)
        for (s in registry.all()) {
            val existing = profiles[s.id]
            if (existing != null) {
                if (autoRebake && existing.hash != baker.hashFor(s)) baker.request(s.id)
                continue
            }
            val f = profileFile(s.id)
            if (!f.exists()) {
                baker.request(s.id)
                continue
            }
            val id = s.id
            server.scheduler.runTaskAsynchronously(this, Runnable {
                val loaded = Profile.read(f)
                if (!isEnabled) return@Runnable
                server.scheduler.runTask(this, Runnable {
                    val src = registry.get(id) ?: return@Runnable
                    if (profiles[id] != null) return@Runnable
                    if (loaded == null) {
                        logger.warning("Profile file for '$id' is unreadable; re-baking.")
                        baker.request(id)
                    } else {
                        profiles[id] = loaded
                        if (autoRebake && loaded.hash != baker.hashFor(src)) baker.request(id)
                    }
                })
            })
        }
    }

    // ------------------------------------------------------------------ messaging

    fun prefix(): String = (config.getString("settings.prefix") ?: "&6[ProxmitySource] &r").replace('&', '§')

    fun msg(to: CommandSender, text: String) {
        to.sendMessage(prefix() + text.replace('&', '§'))
    }

    // ------------------------------------------------------------------ per-player mute

    fun isMuted(p: Player) = p.uniqueId in muted

    /** Returns true if the player is now muted. */
    fun toggleMute(p: Player): Boolean {
        val nowMuted = if (!muted.remove(p.uniqueId)) {
            muted.add(p.uniqueId)
            true
        } else false
        saveMutes()
        return nowMuted
    }

    private fun loadMutes() {
        muted.clear()
        if (!mutesFile.exists()) return
        YamlConfiguration.loadConfiguration(mutesFile).getStringList("muted").forEach {
            try {
                muted.add(UUID.fromString(it))
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun saveMutes() {
        val y = YamlConfiguration()
        y.set("muted", muted.map { it.toString() })
        dataFolder.mkdirs()
        y.save(mutesFile)
    }
}
