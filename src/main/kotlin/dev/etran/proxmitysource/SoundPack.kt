package dev.etran.proxmitysource

import com.sun.net.httpserver.HttpServer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Custom sounds. Minecraft can only play sounds the client already has, so the server builds a small
 * resource pack from the .ogg files in plugins/ProxmitySource/sounds/ and (optionally) hosts / sends it to players.
 */
class SoundPack(private val plugin: ProxmitySourcePlugin) : Listener {

    class CustomSound(val event: String, val path: String, val file: File, val channels: Int?)

    private val soundsDir = File(plugin.dataFolder, "sounds")
    val packFile = File(plugin.dataFolder, "proxmitysource-pack.zip")
    val bedrockFile = File(plugin.dataFolder, BEDROCK_NAME)
    private val bedrockMeta = File(plugin.dataFolder, "bedrock-pack.yml")
    private val optoutFile = File(plugin.dataFolder, "pack-optout.yml")
    private val optedOut = HashSet<UUID>()
    /** Players we sent the pack to and are waiting on an answer from (so we only react to OUR offers). */
    private val offered = HashSet<UUID>()
    private var watcher: BukkitTask? = null
    private var signature: String = ""
    private var initialized = false
    private val sounds = TreeMap<String, CustomSound>()
    private var http: HttpServer? = null
    private var httpExecutor: ExecutorService? = null

    var sha1: ByteArray? = null
        private set

    fun names(): List<String> = sounds.keys.toList()
    fun count(): Int = sounds.size
    fun sha1Hex(): String? = sha1?.joinToString("") { "%02x".format(it) }

    /** "toll" -> "proxmitysource:toll" when a custom sound of that name exists. Anything else is used as given. */
    fun resolve(key: String): String {
        val k = key.trim()
        if (k.contains(':')) return k
        val ev = k.lowercase().replace('/', '.')
        return if (sounds.containsKey(ev)) "$NAMESPACE:${sounds[ev]!!.path}" else k
    }

    /** Rescans sounds/, rebuilds both packs, restarts the host and the folder watcher. Returns notes (warnings etc). */
    fun reload(): List<String> {
        val notes = ArrayList<String>()
        loadOptout()
        rescanAndBuild(notes)
        startHost(notes)
        startWatcher()
        return notes
    }

    /** Scan + build Java pack + Bedrock pack (+ optional copy for Geyser). Offers the new pack if its content changed. */
    private fun rescanAndBuild(notes: MutableList<String>) {
        val before = sha1Hex()
        soundsDir.mkdirs()
        scan(notes)
        signature = folderSignature()
        if (sounds.isNotEmpty()) {
            val err = build()
            if (err != null) notes.add("Could not build resource pack: $err")
            if (plugin.config.getBoolean("bedrock.enabled", true)) {
                val berr = buildBedrock()
                if (berr != null) notes.add("Could not build Bedrock pack: $berr")
                else copyToGeyser(notes)
            } else bedrockFile.delete()
        } else {
            sha1 = null
            packFile.delete()
            bedrockFile.delete()
        }
        val after = sha1Hex()
        if (initialized && after != null && after != before && plugin.config.getBoolean("resource-pack.offer-on-update", true)) {
            Bukkit.getScheduler().runTask(plugin, Runnable { Bukkit.getOnlinePlayers().forEach { offer(it, true) } })
        }
        initialized = true
    }

    private fun folderSignature(): String {
        val base = soundsDir.toPath()
        return soundsDir.walkTopDown().filter { it.isFile && it.extension.equals("ogg", ignoreCase = true) }.sortedBy { it.path }
            .joinToString("|") { base.relativize(it.toPath()).toString() + ":" + it.length() + ":" + it.lastModified() }
    }

    /** Notices .ogg files added/changed/removed by hand and rebuilds without a /reload. */
    private fun startWatcher() {
        watcher?.cancel()
        watcher = null
        val secs = plugin.config.getInt("sounds.auto-detect-seconds", 10)
        if (secs <= 0) return
        val ticks = secs.coerceAtLeast(1) * 20L
        watcher = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try {
                if (folderSignature() == signature) return@Runnable
                val notes = ArrayList<String>()
                rescanAndBuild(notes)
                plugin.logger.info("Sounds folder changed: ${sounds.size} custom sound(s); resource packs rebuilt.")
                notes.forEach { plugin.logger.warning(it) }
                // sources that use a renamed sound resolve it on next play; nothing else to refresh
            } catch (t: Throwable) {
                plugin.logger.warning("Sound folder check failed: ${t.message}")
            }
        }, ticks, ticks)
    }

    // ------------------------------------------------------------------ scanning

    private fun sanitize(raw: String): String =
        raw.lowercase().replace(' ', '_').replace(Regex("[^a-z0-9_./-]"), "_").trim('/', '.')

    private fun scan(notes: MutableList<String>) {
        sounds.clear()
        val base = soundsDir.toPath()
        soundsDir.walkTopDown().filter { it.isFile && it.extension.equals("ogg", ignoreCase = true) }.sortedBy { it.path }
            .forEach { f ->
                val rel = base.relativize(f.toPath()).toString().replace(File.separatorChar, '/')
                val path = sanitize(rel.substring(0, rel.length - 4))
                if (path.isEmpty()) return@forEach
                val event = path.replace('/', '.')
                if (sounds.containsKey(event)) {
                    notes.add("Skipped ${f.name}: another file already maps to the sound name '$event'.")
                    return@forEach
                }
                val channels = oggChannels(f)
                if (channels == null) {
                    notes.add("Skipped $rel: not an Ogg Vorbis file (Minecraft only plays .ogg Vorbis).")
                    return@forEach
                }
                if (channels != 1) {
                    notes.add("'$event' is stereo ($channels channels): it will NOT fade with distance. Convert to mono.")
                }
                sounds[event] = CustomSound(event, path, f, channels)
            }
    }

    /** Reads the channel count from the Vorbis identification header, or null if this is not Ogg Vorbis. */
    private fun oggChannels(f: File): Int? = try {
        f.inputStream().use { input ->
            val head = ByteArray(512)
            val n = input.read(head)
            channelsFromBytes(head, n)
        }
    } catch (_: Exception) {
        null
    }

    private fun channelsFromBytes(head: ByteArray, n: Int): Int? {
        if (n < 40 || head[0] != 'O'.code.toByte() || head[1] != 'g'.code.toByte() ||
            head[2] != 'g'.code.toByte() || head[3] != 'S'.code.toByte()
        ) return null
        val marker = byteArrayOf(0x01, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
        outer@ for (i in 0..(n - 12)) {
            for (j in marker.indices) if (head[i + j] != marker[j]) continue@outer
            return head[i + 11].toInt() and 0xFF
        }
        return null
    }

    // ------------------------------------------------------------------ building

    /** Builds proxmitysource-pack.zip. Returns an error message, or null on success. */
    fun build(): String? {
        val cfg = plugin.config
        val fmtMin = cfg.getInt("resource-pack.pack-format-min", 15)
        val fmtMax = cfg.getInt("resource-pack.pack-format-max", 999)
        val description = (cfg.getString("resource-pack.description") ?: "ProxmitySource custom sounds").replace("\\", "").replace("\"", "'")
        val streamBytes = cfg.getInt("resource-pack.stream-threshold-kb", 512) * 1024L

        val mcmeta = """{"pack":{"pack_format":$fmtMin,"supported_formats":{"min_inclusive":$fmtMin,"max_inclusive":$fmtMax},"min_format":$fmtMin,"max_format":$fmtMax,"description":"$description"}}"""

        val json = StringBuilder("{\n")
        val it = sounds.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val stream = s.file.length() > streamBytes
            json.append("  \"${s.event}\": {\"sounds\": [{\"name\": \"$NAMESPACE:${s.path}\", \"stream\": $stream, \"attenuation_distance\": 16}]}")
            json.append(if (it.hasNext()) ",\n" else "\n")
        }
        json.append("}\n")

        val tmp = File(plugin.dataFolder, "proxmitysource-pack.zip.tmp")
        try {
            val fixed = LocalDateTime.of(2024, 1, 1, 0, 0) // fixed timestamps => identical hash for identical content
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    val e = ZipEntry(name)
                    e.setTimeLocal(fixed)
                    zip.putNextEntry(e)
                    zip.write(bytes)
                    zip.closeEntry()
                }
                put("pack.mcmeta", mcmeta.toByteArray(Charsets.UTF_8))
                put("assets/$NAMESPACE/sounds.json", json.toString().toByteArray(Charsets.UTF_8))
                for (s in sounds.values) put("assets/$NAMESPACE/sounds/${s.path}.ogg", s.file.readBytes())
            }
            if (packFile.exists()) packFile.delete()
            if (!tmp.renameTo(packFile)) return "could not write ${packFile.name}"
            sha1 = MessageDigest.getInstance("SHA-1").digest(packFile.readBytes())
            return null
        } catch (e: Exception) {
            tmp.delete()
            return e.message ?: e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------ hosting / sending

    fun publicUrl(): String? {
        val cfg = plugin.config
        if (cfg.getBoolean("resource-pack.host.enabled", false)) {
            val addr = cfg.getString("resource-pack.host.public-address")?.trim().orEmpty()
            if (addr.isEmpty()) return null
            return "http://$addr:${cfg.getInt("resource-pack.host.port", 8123)}/$PACK_NAME"
        }
        val url = cfg.getString("resource-pack.url")?.trim().orEmpty()
        return url.ifEmpty { null }
    }

    /** Direct link to the Bedrock pack, only when the built-in host is used (otherwise you host the file yourself). */
    fun bedrockUrl(): String? {
        val cfg = plugin.config
        if (!cfg.getBoolean("resource-pack.host.enabled", false)) return null
        val addr = cfg.getString("resource-pack.host.public-address")?.trim().orEmpty()
        if (addr.isEmpty()) return null
        return "http://$addr:${cfg.getInt("resource-pack.host.port", 8123)}/$BEDROCK_NAME"
    }

    private fun startHost(notes: MutableList<String>) {
        stopHost()
        val cfg = plugin.config
        if (!cfg.getBoolean("resource-pack.host.enabled", false)) return
        val port = cfg.getInt("resource-pack.host.port", 8123)
        try {
            val bind = cfg.getString("resource-pack.host.bind-address") ?: "0.0.0.0"
            val server = HttpServer.create(InetSocketAddress(bind, port), 0)
            server.createContext("/") { ex ->
                try {
                    val method = ex.requestMethod
                    val name = ex.requestURI.path.trimStart('/')
                    val file = when (name) {
                        PACK_NAME -> packFile
                        BEDROCK_NAME -> bedrockFile
                        else -> null
                    }
                    if (file == null || !file.exists() || (method != "GET" && method != "HEAD")) {
                        ex.sendResponseHeaders(404, -1)
                    } else {
                        val data = file.readBytes()
                        ex.responseHeaders.add("Content-Type", "application/zip")
                        if (method == "HEAD") {
                            ex.sendResponseHeaders(200, -1)
                        } else {
                            ex.sendResponseHeaders(200, data.size.toLong())
                            ex.responseBody.write(data)
                        }
                    }
                } catch (_: Exception) {
                    // client hung up; nothing to do
                } finally {
                    ex.close()
                }
            }
            val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "ProxmitySource-PackHost").apply { isDaemon = true } }
            server.executor = pool
            server.start()
            http = server
            httpExecutor = pool
            if (cfg.getString("resource-pack.host.public-address").isNullOrBlank()) {
                notes.add("Pack host is running on port $port, but resource-pack.host.public-address is empty, so players cannot be sent the URL.")
            }
        } catch (t: Throwable) {
            notes.add("Could not start the pack web server on port $port: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun stopHost() {
        watcher?.cancel()
        watcher = null
        try {
            http?.stop(0)
        } catch (_: Throwable) {
        }
        httpExecutor?.shutdownNow()
        http = null
        httpExecutor = null
    }

    /** Sends the pack to a player. Returns false if no URL/hash is available. */
    fun sendTo(p: Player): Boolean {
        val url = publicUrl() ?: return false
        val hash = sha1 ?: return false
        try {
            p.setResourcePack(url, hash)
        } catch (_: NoSuchMethodError) {
            // Newer API without the 2-arg overload: setResourcePack(UUID, String, byte[])
            val m = p.javaClass.getMethod("setResourcePack", java.util.UUID::class.java, String::class.java, ByteArray::class.java)
            m.invoke(p, java.util.UUID.nameUUIDFromBytes(url.toByteArray()), url, hash)
        }
        return true
    }

    fun isBedrock(p: Player): Boolean {
        // Floodgate gives Bedrock players UUIDs whose top 64 bits are zero. Plain Geyser (no Floodgate) players
        // are real Java-style accounts for this plugin and cannot be told apart, so they simply ignore the Java offer.
        return p.uniqueId.mostSignificantBits == 0L
    }

    fun isOptedOut(p: Player) = p.uniqueId in optedOut

    /** Toggles the player's opt-out. Returns true if they are now opted out. */
    fun toggleOptout(p: Player): Boolean {
        val now = if (optedOut.remove(p.uniqueId)) false else { optedOut.add(p.uniqueId); true }
        saveOptout()
        return now
    }

    fun clearOptout(p: Player) {
        if (optedOut.remove(p.uniqueId)) saveOptout()
    }

    /**
     * Offers the Java pack to a player. Minecraft shows its normal optional-pack prompt (we never force it).
     * Bedrock players and players who opted out are skipped unless [force] is set for an explicit request.
     */
    fun offer(p: Player, respectOptout: Boolean): Boolean {
        if (isBedrock(p)) return false
        if (respectOptout && isOptedOut(p)) return false
        if (publicUrl() == null || sha1 == null) return false
        offered.add(p.uniqueId)
        return sendTo(p)
    }

    @EventHandler
    fun onJoin(ev: PlayerJoinEvent) {
        val cfg = plugin.config
        // "send-on-join" was the old name of this setting.
        if (!cfg.getBoolean("resource-pack.offer-on-join", cfg.getBoolean("resource-pack.send-on-join", true))) return
        if (publicUrl() == null || sha1 == null) return
        val delay = cfg.getInt("resource-pack.send-delay-ticks", 40).coerceAtLeast(1).toLong()
        val id = ev.player.uniqueId
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.getPlayer(id)?.let { if (it.isOnline) offer(it, true) }
        }, delay)
    }

    @EventHandler
    fun onPackStatus(ev: PlayerResourcePackStatusEvent) {
        val id = ev.player.uniqueId
        if (id !in offered) return
        when (ev.status.name) {
            "DECLINED" -> {
                offered.remove(id)
                if (optedOut.add(id)) saveOptout()
            }
            "ACCEPTED" -> {
                // still downloading; keep waiting for the final result
                if (optedOut.remove(id)) saveOptout()
            }
            "SUCCESSFULLY_LOADED" -> offered.remove(id)
            "FAILED_DOWNLOAD", "FAILED_RELOAD", "INVALID_URL", "DISCARDED" -> offered.remove(id)
        }
    }

    private fun loadOptout() {
        optedOut.clear()
        if (!optoutFile.exists()) return
        YamlConfiguration.loadConfiguration(optoutFile).getStringList("declined").forEach {
            try { optedOut.add(UUID.fromString(it)) } catch (_: IllegalArgumentException) {}
        }
    }

    private fun saveOptout() {
        val y = YamlConfiguration()
        y.set("declined", optedOut.map { it.toString() })
        plugin.dataFolder.mkdirs()
        y.save(optoutFile)
    }

    // ------------------------------------------------------------------ add / remove

    fun validName(name: String) = Regex("[a-z0-9_-]{1,40}").matches(name)

    fun remove(name: String): Boolean {
        val s = sounds[name.lowercase()] ?: return false
        return s.file.delete()
    }

    /** Downloads an .ogg into sounds/ on a background thread. [done] runs on the main thread with an error or null. */
    fun download(name: String, url: String, done: (String?) -> Unit) {
        val maxBytes = plugin.config.getInt("sounds.max-download-mb", 10).coerceIn(1, 100) * 1024L * 1024L
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            var error: String? = null
            try {
                val uri = URI(url)
                require(uri.scheme == "http" || uri.scheme == "https") { "URL must start with http:// or https://" }
                val conn = uri.toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "ProxmitySource")
                if (conn.responseCode != 200) throw IllegalStateException("server answered HTTP ${conn.responseCode}")
                val out = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) throw IllegalStateException("file is larger than ${maxBytes / 1024 / 1024} MB")
                    }
                }
                val bytes = out.toByteArray()
                val ch = channelsFromBytes(bytes, minOf(bytes.size, 512))
                    ?: throw IllegalStateException("that is not an Ogg Vorbis (.ogg) file")
                soundsDir.mkdirs()
                File(soundsDir, "$name.ogg").writeBytes(bytes)
                if (ch != 1) error = "WARN_STEREO"
            } catch (t: Exception) {
                error = t.message ?: t.javaClass.simpleName
            }
            Bukkit.getScheduler().runTask(plugin, Runnable { done(error) })
        })
    }

    // ------------------------------------------------------------------ Bedrock (GeyserMC)

    /**
     * Builds proxmitysource-bedrock.mcpack: a Bedrock resource pack with the same .ogg files and a
     * sound_definitions.json. Geyser passes the Java sound key (e.g. "proxmitysource:toll") through to Bedrock
     * players, so the definitions are registered under that key (and a dotted alias).
     */
    private fun buildBedrock(): String? {
        val cfg = plugin.config
        val category = (cfg.getString("bedrock.category") ?: "block").lowercase().filter { it.isLetter() }.ifEmpty { "block" }
        val meta = YamlConfiguration.loadConfiguration(bedrockMeta)
        var headerUuid = meta.getString("header-uuid")
        var moduleUuid = meta.getString("module-uuid")
        if (headerUuid == null || moduleUuid == null) {
            headerUuid = UUID.randomUUID().toString()
            moduleUuid = UUID.randomUUID().toString()
        }
        val sig = (sha1Hex() ?: "") + "|" + category
        var build = meta.getInt("build", 0)
        if (meta.getString("signature") != sig || !bedrockFile.exists()) build++
        val streamBytes = cfg.getInt("resource-pack.stream-threshold-kb", 512) * 1024L

        val defs = StringBuilder("{\n  \"format_version\": \"1.14.0\",\n  \"sound_definitions\": {\n")
        val it = sounds.values.iterator()
        while (it.hasNext()) {
            val sd = it.next()
            val body = "{\"category\": \"$category\", \"max_distance\": 16.0, \"min_distance\": 0.0, " +
                "\"sounds\": [{\"name\": \"sounds/${sd.path}\", \"volume\": 1.0, \"is3D\": true, \"stream\": ${sd.file.length() > streamBytes}}]}"
            defs.append("    \"$NAMESPACE:${sd.path}\": $body,\n")
            defs.append("    \"$NAMESPACE.${sd.event}\": $body")
            defs.append(if (it.hasNext()) ",\n" else "\n")
        }
        defs.append("  }\n}\n")

        val desc = (cfg.getString("resource-pack.description") ?: "ProxmitySource custom sounds").replace("\\", "").replace("\"", "'")
        val manifest = """{
  "format_version": 2,
  "header": {"name": "ProxmitySource Sounds", "description": "$desc", "uuid": "$headerUuid", "version": [1, 0, $build], "min_engine_version": [1, 16, 0]},
  "modules": [{"type": "resources", "description": "$desc", "uuid": "$moduleUuid", "version": [1, 0, $build]}]
}
"""
        val tmp = File(plugin.dataFolder, "$BEDROCK_NAME.tmp")
        try {
            val fixed = LocalDateTime.of(2024, 1, 1, 0, 0)
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    val e = ZipEntry(name)
                    e.setTimeLocal(fixed)
                    zip.putNextEntry(e)
                    zip.write(bytes)
                    zip.closeEntry()
                }
                put("manifest.json", manifest.toByteArray(Charsets.UTF_8))
                put("sounds/sound_definitions.json", defs.toString().toByteArray(Charsets.UTF_8))
                for (sd in sounds.values) put("sounds/${sd.path}.ogg", sd.file.readBytes())
            }
            if (bedrockFile.exists()) bedrockFile.delete()
            if (!tmp.renameTo(bedrockFile)) return "could not write ${bedrockFile.name}"
            meta.set("header-uuid", headerUuid)
            meta.set("module-uuid", moduleUuid)
            meta.set("build", build)
            meta.set("signature", sig)
            meta.save(bedrockMeta)
            return null
        } catch (e: Exception) {
            tmp.delete()
            return e.message ?: e.javaClass.simpleName
        }
    }

    /** Optionally drops a copy of the Bedrock pack into Geyser's packs folder (bedrock.geyser-packs-folder). */
    private fun copyToGeyser(notes: MutableList<String>) {
        val cfg = plugin.config
        if (!cfg.getBoolean("bedrock.auto-copy-to-geyser", false)) return
        val dirName = cfg.getString("bedrock.geyser-packs-folder")?.trim().orEmpty()
        val dir = if (dirName.isNotEmpty()) File(dirName) else File(plugin.dataFolder.parentFile, "Geyser-Spigot/packs")
        try {
            if (!dir.isDirectory) {
                notes.add("bedrock.auto-copy-to-geyser is on but '${dir.path}' is not a folder. Set bedrock.geyser-packs-folder.")
                return
            }
            bedrockFile.copyTo(File(dir, BEDROCK_NAME), overwrite = true)
        } catch (e: Exception) {
            notes.add("Could not copy the Bedrock pack to Geyser: ${e.message}")
        }
    }

    companion object {
        const val NAMESPACE = "proxmitysource"
        const val PACK_NAME = "proxmitysource.zip"
        const val BEDROCK_NAME = "proxmitysource-bedrock.mcpack"
    }
}
