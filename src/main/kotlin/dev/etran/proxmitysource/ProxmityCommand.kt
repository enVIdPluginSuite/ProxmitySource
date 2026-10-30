package dev.etran.proxmitysource

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.SoundCategory
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import kotlin.math.pow

class ProxmityCommand(private val plugin: ProxmitySourcePlugin) : CommandExecutor, TabCompleter {

    private val idPattern = Regex("[a-z0-9_-]{1,32}")

    private val adminSubs = listOf(
        "help", "create", "clone", "move", "delete", "list", "info", "set", "trigger", "filter", "hours",
        "enable", "disable", "play", "bake", "probe", "reload", "config", "sound", "pack", "mute",
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase() ?: "help"
        if (sub == "mute") return mute(sender)
        val packSub = args.getOrNull(1)?.lowercase()
        if (sub == "pack" && (packSub == "get" || packSub == "optout")) return playerPack(sender, packSub)
        if (!sender.hasPermission("proxmitysource.admin")) {
            plugin.msg(sender, "&cYou do not have permission to do that.")
            return true
        }
        try {
            when (sub) {
                "help" -> help(sender, label)
                "create" -> create(sender, args)
                "clone" -> clone(sender, args)
                "move" -> move(sender, args)
                "delete" -> delete(sender, args)
                "list" -> list(sender)
                "info" -> info(sender, args)
                "set" -> set(sender, args)
                "trigger" -> trigger(sender, args)
                "filter" -> filter(sender, args)
                "hours" -> hours(sender, args)
                "enable" -> enable(sender, args, true)
                "disable" -> enable(sender, args, false)
                "play" -> play(sender, args)
                "bake" -> bake(sender, args)
                "probe" -> probe(sender, args)
                "reload" -> reload(sender)
                "config" -> config(sender, args)
                "sound" -> sound(sender, args)
                "pack" -> pack(sender, args)
                else -> plugin.msg(sender, "&cUnknown subcommand. Try &f/$label help")
            }
        } catch (e: IllegalArgumentException) {
            plugin.msg(sender, "&c${e.message}")
        }
        return true
    }

    /** Player-facing pack commands: "get" (ask for the pack now) and "optout" (toggle the automatic offer). */
    private fun playerPack(sender: CommandSender, what: String): Boolean {
        if (!sender.hasPermission("proxmitysource.pack")) {
            plugin.msg(sender, "&cYou do not have permission to do that.")
            return true
        }
        val p = player(sender) ?: return true
        val sp = plugin.soundPack
        if (sp.isBedrock(p)) {
            plugin.msg(p, "&7You are on Bedrock: the sound pack is delivered by the server's Geyser when you connect. If you cannot hear custom sounds, rejoin (or fully restart the game after it was updated) and accept the pack.")
            return true
        }
        when (what) {
            "get" -> {
                sp.clearOptout(p)
                if (sp.offer(p, false)) plugin.msg(p, "&aSent. Accept the prompt to hear the custom sounds.")
                else plugin.msg(p, "&eThere is no sound pack available right now (no custom sounds, or no pack URL is configured).")
            }
            else -> {
                val now = sp.toggleOptout(p)
                plugin.msg(p, if (now) "&7You will no longer be offered the sound pack. Use &f/proxsource pack get&7 to fetch it later." else "&aYou will be offered the sound pack again when you join or when it changes.")
            }
        }
        return true
    }

    // ------------------------------------------------------------------ helpers

    private fun player(sender: CommandSender): Player? {
        if (sender is Player) return sender
        plugin.msg(sender, "&cThis command must be run by a player.")
        return null
    }

    private fun source(sender: CommandSender, args: Array<out String>, idx: Int = 1): Source? {
        val id = args.getOrNull(idx)
        if (id == null) {
            plugin.msg(sender, "&cMissing source id.")
            return null
        }
        val s = plugin.registry.get(id)
        if (s == null) plugin.msg(sender, "&cNo source named '&f$id&c'. See &f/proxsource list")
        return s
    }

    /** Persists sources and refreshes trigger tracking. */
    private fun changed() {
        plugin.registry.save()
        plugin.triggers.resetTracking()
    }

    private fun maxRadius(): Int = plugin.config.getInt("profile.max-radius", 128)

    private fun checkRadius(r: Double) {
        require(r <= maxRadius()) { "Radius ${r} is above profile.max-radius (${maxRadius()}). Raise it with /proxsource config set profile.max-radius <n> (memory grows with radius cubed)." }
    }

    /** The block the player is looking at (centre of it), else the spot just above their feet. */
    private fun aimPoint(p: Player): DoubleArray {
        val b = p.getTargetBlockExact(10)
        return if (b != null) doubleArrayOf(b.x + 0.5, b.y + 0.5, b.z + 0.5)
        else doubleArrayOf(p.location.x, p.location.y + 1.0, p.location.z)
    }

    private fun help(s: CommandSender, label: String) {
        plugin.msg(s, "&eProxmitySource commands &7(a source is a point that makes sound; walls shape how it spreads)")
        val lines = listOf(
            "create <id> [radius] [echoiness] [penetration]" to "make a source where you look (or stand)",
            "move <id>" to "move a source to where you look/stand",
            "clone <id> <newId>" to "copy a source (all settings) to where you look/stand",
            "set <id> <property> <value|default>" to "radius echoiness penetration amplification falloff sound volume pitch ...",
            "trigger <id> add|remove <type>" to "PLAYER_ENTER PLAYER_EXIT PLAYER_INTERACT ENTITY_ENTER REDSTONE TIME TIMER",
            "filter <id> entity|material add|remove|clear [name]" to "limit ENTITY_ENTER / PLAYER_INTERACT",
            "hours <id> <6,12,18|all>" to "clock hours for the TIME trigger",
            "set <id> frequency-seconds|tolls|toll-interval-seconds" to "TIMER: every X s, ring Y times, Z s apart",
            "play <id>" to "ring it now (ignores cooldown)",
            "probe <id>" to "how loud is it where I stand, and why",
            "bake <id|all>" to "re-bake the wall/echo profile now",
            "info <id> / list / delete <id> / enable|disable <id>" to "",
            "config list|get|set" to "edit config.yml in-game",
            "sound list|add|remove|test" to "custom sounds",
            "pack build|send|info" to "custom sound resource pack (Java + Bedrock)",
            "pack get|optout" to "players: fetch the pack / stop the automatic offer",
            "reload" to "reload config, sources and sounds",
            "mute" to "mute/unmute ProxmitySource sounds for yourself (everyone)",
        )
        for ((c, d) in lines) s.sendMessage("§7/$label §f$c" + if (d.isEmpty()) "" else " §8- §7$d")
    }

    // ------------------------------------------------------------------ player-facing

    private fun mute(sender: CommandSender): Boolean {
        val p = player(sender) ?: return true
        if (!p.hasPermission("proxmitysource.mute") && !p.hasPermission("proxmitysource.admin")) {
            plugin.msg(p, "&cYou do not have permission to do that.")
            return true
        }
        val muted = plugin.toggleMute(p)
        plugin.msg(p, if (muted) "&7ProxmitySource sounds are now &cmuted&7 for you." else "&7ProxmitySource sounds are now &aunmuted&7 for you.")
        return true
    }

    // ------------------------------------------------------------------ create / move / delete

    private fun create(sender: CommandSender, args: Array<out String>) {
        val p = player(sender) ?: return
        val id = args.getOrNull(1)?.lowercase()
        if (id == null) {
            plugin.msg(p, "&cUsage: /proxsource create <id> [radius] [echoiness 0-1] [penetration 0-1]")
            return
        }
        if (!idPattern.matches(id)) throw IllegalArgumentException("Ids may only use a-z, 0-9, '_' and '-' (max 32 characters).")
        if (plugin.registry.get(id) != null) throw IllegalArgumentException("A source named '$id' already exists. Use /proxsource move $id to relocate it.")

        val radius = args.getOrNull(2)?.let { Prop.RADIUS.parse(it) as Double }
        val echo = args.getOrNull(3)?.let { Prop.ECHOINESS.parse(it) as Double }
        val pen = args.getOrNull(4)?.let { Prop.PENETRATION.parse(it) as Double }
        checkRadius(radius ?: plugin.config.getDouble("defaults.radius", 64.0))

        val at = aimPoint(p)
        val s = Source(id, p.world.name, at[0], at[1], at[2])
        radius?.let { s.overrides[Prop.RADIUS] = it }
        echo?.let { s.overrides[Prop.ECHOINESS] = it }
        pen?.let { s.overrides[Prop.PENETRATION] = it }
        plugin.registry.add(s)
        changed()
        plugin.baker.request(id)
        plugin.msg(p, "&aCreated source &f$id&a at &f%.1f, %.1f, %.1f".format(at[0], at[1], at[2]) +
            " &7(radius ${s.valueText(Prop.RADIUS, plugin.config)}, echoiness ${s.valueText(Prop.ECHOINESS, plugin.config)}, penetration ${s.valueText(Prop.PENETRATION, plugin.config)})")
        plugin.msg(p, "&7Baking its sound profile in the background. Add a trigger: &f/proxsource trigger $id add <type>&7, test: &f/proxsource play $id")
    }

    private fun move(sender: CommandSender, args: Array<out String>) {
        val s = source(sender, args) ?: return
        val p = player(sender) ?: return
        val at = aimPoint(p)
        s.world = p.world.name
        s.x = at[0]
        s.y = at[1]
        s.z = at[2]
        changed()
        plugin.baker.request(s.id)
        plugin.msg(p, "&aMoved &f${s.id}&a to &f%.1f, %.1f, %.1f&a. &7Re-baking its profile.".format(at[0], at[1], at[2]))
    }

    private fun clone(sender: CommandSender, args: Array<out String>) {
        val original = source(sender, args) ?: return
        val p = player(sender) ?: return
        val id = args.getOrNull(2)?.lowercase() ?: throw IllegalArgumentException("Usage: /proxsource clone <id> <newId>  (the copy is placed where you look/stand)")
        if (!idPattern.matches(id)) throw IllegalArgumentException("Ids may only use a-z, 0-9, '_' and '-' (max 32 characters).")
        if (plugin.registry.get(id) != null) throw IllegalArgumentException("A source named '$id' already exists.")

        val at = aimPoint(p)
        val copy = Source(id, p.world.name, at[0], at[1], at[2])
        copy.enabled = original.enabled
        copy.triggers.addAll(original.triggers)
        copy.entityTypes.addAll(original.entityTypes)
        copy.materials.addAll(original.materials)
        copy.hours.addAll(original.hours)
        copy.overrides.putAll(original.overrides)
        plugin.registry.add(copy)
        changed()
        plugin.baker.request(id)
        plugin.msg(p, "&aCloned &f${original.id}&a to &f$id&a at &f%.1f, %.1f, %.1f&a. &7Baking its own sound profile for the new spot.".format(at[0], at[1], at[2]))
        plugin.msg(p, "&7To relocate the original instead, use &f/proxsource move ${original.id}&7; to finish a move by cloning, run &f/proxsource delete ${original.id}&7.")
    }

    private fun delete(sender: CommandSender, args: Array<out String>) {
        val s = source(sender, args) ?: return
        plugin.registry.remove(s.id)
        plugin.profiles.remove(s.id)
        plugin.baker.forget(s.id)
        plugin.profileFile(s.id).delete()
        changed()
        plugin.msg(sender, "&aDeleted source &f${s.id}&a.")
    }

    private fun list(sender: CommandSender) {
        val all = plugin.registry.all()
        if (all.isEmpty()) {
            plugin.msg(sender, "&7No sources yet. Look at a block and run &f/proxsource create <id>&7.")
            return
        }
        plugin.msg(sender, "&eSources (${all.size}):")
        for (s in all) {
            val state = if (s.enabled) "§aon" else "§coff"
            val prof = when {
                plugin.baker.status(s.id).startsWith("baking") || plugin.baker.status(s.id).startsWith("queued") -> "§ebaking"
                plugin.profiles[s.id] != null -> "§abaked"
                else -> "§cno profile"
            }
            sender.sendMessage("§7- §f${s.id} §8[$state§8] [$prof§8] §7${s.world} §8| §7${s.triggers.joinToString(", ") { it.name }.ifEmpty { "no triggers" }}")
        }
    }

    private fun info(sender: CommandSender, args: Array<out String>) {
        val s = source(sender, args) ?: return
        val cfg = plugin.config
        fun line(k: String, v: String) = sender.sendMessage("§7$k: §f$v")
        plugin.msg(sender, "&eSource &f${s.id} &8(${if (s.enabled) "enabled" else "disabled"})")
        line("World", s.world)
        line("Position", "%.1f, %.1f, %.1f".format(s.x, s.y, s.z))
        line("Triggers", s.triggers.joinToString(", ") { it.name }.ifEmpty { "none" })
        if (TriggerType.ENTITY_ENTER in s.triggers) line("Entity filter", s.entityTypes.joinToString(", ").ifEmpty { "any living mob" })
        if (TriggerType.PLAYER_INTERACT in s.triggers) line("Block filter", s.materials.joinToString(", ").ifEmpty { "any block" })
        if (TriggerType.TIME in s.triggers) line("Hours", s.hours.joinToString(", ").ifEmpty { "every hour" })
        if (TriggerType.TIMER in s.triggers) {
            line("Timer", "every ${s.valueText(Prop.FREQUENCY, cfg)} s, ${s.valueText(Prop.TOLLS, cfg)} time(s), ${s.valueText(Prop.TOLL_INTERVAL, cfg)} s apart")
        }
        for (p in Prop.entries) {
            val v = s.valueText(p, cfg)
            line(p.key, if (p in s.overrides) "$v §e(set here)" else "$v §8(default)")
        }
        val pr = plugin.profiles[s.id]
        val st = plugin.baker.status(s.id)
        when {
            st.isNotEmpty() -> line("Profile", st)
            pr == null -> line("Profile", "not baked yet (run /proxsource bake ${s.id})")
            else -> {
                val ageMin = (System.currentTimeMillis() - pr.bakedAt) / 60000
                val stale = plugin.baker.isStale(s)
                line("Profile", "baked ${if (ageMin < 1) "just now" else "$ageMin min ago"}, ${pr.dx}x${pr.dy}x${pr.dz} blocks, ${pr.memoryBytes() / 1024} KB" +
                    if (stale) " §e(out of date, a re-bake is needed)" else "")
                if (pr.missingChunks > 0) line("Warning", "${pr.missingChunks} of ${pr.totalChunks} chunks were not available when baking and count as open air")
            }
        }
    }

    // ------------------------------------------------------------------ editing

    private fun set(sender: CommandSender, args: Array<out String>) {
        val s = source(sender, args) ?: return
        val key = args.getOrNull(2)
        val raw = args.drop(3).joinToString(" ")
        if (key == null || raw.isEmpty()) {
            plugin.msg(sender, "&cUsage: /proxsource set <id> <property> <value|default>")
            plugin.msg(sender, "&7Properties: &f${Prop.entries.joinToString(", ") { it.key }}")
            return
        }
        val prop = Prop.byKey(key) ?: throw IllegalArgumentException("Unknown property '$key'. Properties: ${Prop.entries.joinToString(", ") { it.key }}")
        if (raw.equals("default", ignoreCase = true) || raw.equals("reset", ignoreCase = true)) {
            s.overrides.remove(prop)
            if (prop == Prop.RADIUS) checkRadius(s.dbl(Prop.RADIUS, plugin.config))
            changed()
            if (prop.rebake) plugin.baker.request(s.id)
            plugin.msg(sender, "&a${prop.key} of &f${s.id}&a now follows the config default (&f${s.valueText(prop, plugin.config)}&a).")
            return
        }
        val value = prop.parse(raw)
        if (prop == Prop.RADIUS) checkRadius(value as Double)
        s.overrides[prop] = value
        changed()
        plugin.msg(sender, "&a${prop.key} of &f${s.id}&a set to &f${s.valueText(prop, plugin.config)}&a.")
        if (prop.rebake) {
            plugin.baker.request(s.id)
            plugin.msg(sender, "&7This changes how sound spreads, so the profile is being re-baked.")
        }
        if (prop == Prop.SOUND) {
            val resolved = plugin.soundPack.resolve(raw)
            if (!raw.contains(':') && resolved == raw && !raw.contains('.')) {
                plugin.msg(sender, "&7Note: no custom sound named '$raw' exists; it will be played as a vanilla key.")
            }
        }
    }

    private fun trigger(sender: CommandSender, args: Array<out String>) {
        val s = source(sender, args) ?: return
        val action = args.getOrNull(2)?.lowercase()
        val t = args.getOrNull(3)?.let { TriggerType.parse(it) }
        if ((action != "add" && action != "remove") || t == null) {
            plugin.msg(sender, "&cUsage: /proxsource trigger <id> add|remove <type>")
            TriggerType.entries.forEach { sender.sendMessage("§7- §f${it.name} §8- §7${it.help}") }
            return
        }
        if (action == "add") s.triggers.add(t) else s.triggers.remove(t)
        changed()
        plugin.msg(sender, "&aTriggers of &f${s.id}&a: &f${s.triggers.joinToString(", ") { it.name }.ifEmpty { "none" }}")
    }

    private fun filter(sender: CommandSender, args: Array<out String>) {
        val s = source(sender, args) ?: return
        val kind = args.getOrNull(2)?.lowercase()
        val action = args.getOrNull(3)?.lowercase()
        val value = args.getOrNull(4)?.uppercase()
        if ((kind != "entity" && kind != "material") || action !in listOf("add", "remove", "clear")) {
            plugin.msg(sender, "&cUsage: /proxsource filter <id> entity|material add|remove|clear [name]")
            return
        }
        val set = if (kind == "entity") s.entityTypes else s.materials
        if (action == "clear") {
            set.clear()
        } else {
            if (value == null) throw IllegalArgumentException("Give a $kind name, e.g. ${if (kind == "entity") "ZOMBIE" else "BELL"}.")
            if (action == "add") {
                if (kind == "entity") {
                    val known = entityNames()
                    if (known.isNotEmpty() && value !in known) throw IllegalArgumentException("Unknown entity type '$value'. Use Tab to see valid names.")
                } else if (Material.matchMaterial(value) == null) {
                    throw IllegalArgumentException("Unknown material '$value'.")
                }
                set.add(value)
            } else set.remove(value)
        }
        changed()
        plugin.msg(sender, "&a$kind filter of &f${s.id}&a: &f${set.joinToString(", ").ifEmpty { "(any)" }}")
    }

    private fun entityNames(): Set<String> = try {
        EntityType.values().map { it.name }.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun hours(sender: CommandSender, args: Array<out String>) {
        val s = source(sender, args) ?: return
        val raw = args.getOrNull(2)
        if (raw == null) {
            plugin.msg(sender, "&cUsage: /proxsource hours <id> <6,12,18 | all>  &7(0-23 clock time; 6 = sunrise, 12 = noon, 18 = sunset)")
            return
        }
        s.hours.clear()
        if (!raw.equals("all", ignoreCase = true)) {
            for (part in raw.split(',')) {
                val h = part.trim().toIntOrNull()
                if (h == null || h !in 0..23) throw IllegalArgumentException("'$part' is not an hour from 0 to 23.")
                s.hours.add(h)
            }
        }
        changed()
        plugin.msg(sender, "&aHours of &f${s.id}&a: &f${s.hours.joinToString(", ").ifEmpty { "every hour" }}")
        if (TriggerType.TIME !in s.triggers) plugin.msg(sender, "&7(Add the trigger with &f/proxsource trigger ${s.id} add TIME&7.)")
    }

    private fun enable(sender: CommandSender, args: Array<out String>, on: Boolean) {
        val s = source(sender, args) ?: return
        s.enabled = on
        changed()
        plugin.msg(sender, "&a${s.id} is now ${if (on) "enabled" else "disabled"}.")
    }

    private fun play(sender: CommandSender, args: Array<out String>) {
        val s = source(sender, args) ?: return
        val world = Bukkit.getWorld(s.world)
        if (world == null) {
            plugin.msg(sender, "&cWorld '${s.world}' is not loaded.")
            return
        }
        plugin.engine.play(s, world)
        plugin.msg(sender, "&aRinging &f${s.id}&a.")
        if (plugin.profiles[s.id] == null) plugin.msg(sender, "&7(No profile baked yet, so walls are ignored for now: ${plugin.baker.status(s.id).ifEmpty { "run /proxsource bake ${s.id}" }}.)")
    }

    // ------------------------------------------------------------------ bake / probe

    private fun bake(sender: CommandSender, args: Array<out String>) {
        val target = args.getOrNull(1) ?: throw IllegalArgumentException("Usage: /proxsource bake <id|all>")
        val ids = if (target.equals("all", ignoreCase = true)) plugin.registry.ids() else listOf(source(sender, args)?.id ?: return)
        ids.forEach { plugin.baker.request(it) }
        plugin.msg(sender, "&aQueued ${ids.size} bake(s). &7Reading chunks a few per tick; the heavy part runs in the background. See progress with &f/proxsource info <id>")
    }

    private fun probe(sender: CommandSender, args: Array<out String>) {
        val s = source(sender, args) ?: return
        val p = player(sender) ?: return
        val pr = plugin.profiles[s.id]
        if (pr == null) {
            plugin.msg(p, "&cNo profile yet. ${plugin.baker.status(s.id).ifEmpty { "Run /proxsource bake ${s.id}." }}")
            return
        }
        if (p.world.name != s.world) throw IllegalArgumentException("You are not in the source's world (${s.world}).")
        val eye = p.eyeLocation
        val d = Math.sqrt(s.distSq(eye.x, eye.y, eye.z))
        val sample = pr.sample(eye.x, eye.y, eye.z)
        plugin.msg(p, "&eProbe for &f${s.id}")
        p.sendMessage("§7Straight-line distance: §f%.1f blocks §7(radius %.0f)".format(d, pr.radius))
        if (sample == null) {
            p.sendMessage("§7Result: §cinaudible here §7(beyond the radius, or walls absorb it completely)")
            return
        }
        val cfg = plugin.config
        val gain = s.dbl(Prop.VOLUME, cfg) * s.dbl(Prop.AMPLIFICATION, cfg) * (1.0 - sample.effDistance / pr.radius).pow(s.dbl(Prop.FALLOFF, cfg))
        p.sendMessage("§7Effective distance: §f%.1f blocks §7(walls add %.1f)".format(sample.effDistance, maxOf(0.0, sample.effDistance - d)))
        val layers = Math.ceil(gain.coerceAtMost(cfg.getInt("settings.max-layers", 4).toDouble())).toInt()
        p.sendMessage("§7Loudness: §f%d%% §7of a normal sound §7(%d layer%s)".format((gain * 100).toInt(), layers, if (layers == 1) "" else "s"))
        p.sendMessage("§7Surroundings: §f%d%% enclosed§7, space size §f%d%%".format((sample.wet * 100).toInt(), (sample.size * 100).toInt()))
    }

    // ------------------------------------------------------------------ reload / config

    private fun reload(sender: CommandSender) {
        val notes = plugin.reloadAll()
        plugin.msg(sender, "&aReloaded. &7${plugin.registry.all().size} source(s), ${plugin.soundPack.count()} custom sound(s).")
        notes.forEach { plugin.msg(sender, "&e$it") }
    }

    private fun leafKeys(): List<String> =
        plugin.config.getKeys(true).filter { !plugin.config.isConfigurationSection(it) }.sorted()

    private fun config(sender: CommandSender, args: Array<out String>) {
        val cfg = plugin.config
        when (args.getOrNull(1)?.lowercase()) {
            "list" -> {
                plugin.msg(sender, "&eConfig values &7(change with /proxsource config set <path> <value>)")
                for (k in leafKeys()) sender.sendMessage("§7$k §8= §f${cfg.get(k)}")
            }
            "get" -> {
                val path = args.getOrNull(2) ?: throw IllegalArgumentException("Usage: /proxsource config get <path>")
                if (path !in leafKeys()) throw IllegalArgumentException("No such setting '$path'. See /proxsource config list")
                sender.sendMessage("§7$path §8= §f${cfg.get(path)}")
            }
            "set" -> {
                val path = args.getOrNull(2)
                val raw = args.drop(3).joinToString(" ")
                if (path == null || raw.isEmpty()) throw IllegalArgumentException("Usage: /proxsource config set <path> <value>")
                if (path !in leafKeys()) throw IllegalArgumentException("No such setting '$path'. See /proxsource config list")
                val value: Any = when (cfg.get(path)) {
                    is Boolean -> when (raw.lowercase()) {
                        "true", "on", "yes" -> true
                        "false", "off", "no" -> false
                        else -> throw IllegalArgumentException("'$raw' is not true/false.")
                    }
                    is Int -> raw.toIntOrNull() ?: throw IllegalArgumentException("'$raw' is not a whole number.")
                    is Number -> raw.toDoubleOrNull() ?: throw IllegalArgumentException("'$raw' is not a number.")
                    is List<*> -> throw IllegalArgumentException("Lists can only be edited in config.yml.")
                    else -> raw.trim('"')
                }
                cfg.set(path, value)
                plugin.saveConfig()
                val notes = plugin.reloadAll()
                plugin.msg(sender, "&a$path &7= &f$value &7(saved and applied)")
                notes.forEach { plugin.msg(sender, "&e$it") }
                if (path.startsWith("profile.") && !path.startsWith("profile.auto") && !path.startsWith("profile.settle") &&
                    !path.startsWith("profile.rebake") && !path.startsWith("profile.chunks") && !path.startsWith("profile.load")
                ) {
                    plugin.msg(sender, "&7This affects baking; out-of-date profiles are being re-baked${if (cfg.getBoolean("profile.auto-rebake", true)) "" else " (auto-rebake is off: run /proxsource bake all)"}.")
                }
            }
            else -> plugin.msg(sender, "&cUsage: /proxsource config list | get <path> | set <path> <value>")
        }
    }

    // ------------------------------------------------------------------ custom sounds & pack

    private fun sound(sender: CommandSender, args: Array<out String>) {
        val sp = plugin.soundPack
        when (args.getOrNull(1)?.lowercase()) {
            "list" -> {
                if (sp.count() == 0) {
                    plugin.msg(sender, "&7No custom sounds. Put .ogg files in plugins/ProxmitySource/sounds/ and run &f/proxsource reload&7, or use &f/proxsource sound add <name> <url>")
                } else {
                    plugin.msg(sender, "&eCustom sounds: &f${sp.names().joinToString(", ")}")
                }
            }
            "add" -> {
                val name = args.getOrNull(2)?.lowercase()
                val url = args.getOrNull(3)
                if (name == null || url == null) throw IllegalArgumentException("Usage: /proxsource sound add <name> <direct-url-to-.ogg>")
                if (!sp.validName(name)) throw IllegalArgumentException("Names may only use a-z, 0-9, '_' and '-' (max 40).")
                if (!plugin.config.getBoolean("sounds.allow-url-download", true)) throw IllegalArgumentException("URL downloads are disabled in config (sounds.allow-url-download).")
                plugin.msg(sender, "&7Downloading...")
                sp.download(name, url) { err ->
                    if (err != null && err != "WARN_STEREO") {
                        plugin.msg(sender, "&cDownload failed: $err")
                    } else {
                        val notes = plugin.reloadAll()
                        plugin.msg(sender, "&aSaved sound &f$name&a and rebuilt the pack. &7Use it with &f/proxsource set <id> sound $name")
                        if (err == "WARN_STEREO") plugin.msg(sender, "&eThat file is stereo, it will not fade with distance. Use a mono file.")
                        notes.forEach { plugin.msg(sender, "&e$it") }
                        plugin.msg(sender, "&7Online Java players are offered the updated pack automatically (resource-pack.offer-on-update). Bedrock players get it from Geyser at their next login.")
                    }
                }
            }
            "remove" -> {
                val name = args.getOrNull(2)?.lowercase() ?: throw IllegalArgumentException("Usage: /proxsource sound remove <name>")
                if (!sp.remove(name)) throw IllegalArgumentException("No custom sound '$name'.")
                val notes = plugin.reloadAll()
                plugin.msg(sender, "&aRemoved &f$name&a and rebuilt the pack.")
                notes.forEach { plugin.msg(sender, "&e$it") }
            }
            "test" -> {
                val p = player(sender) ?: return
                val key = args.getOrNull(2) ?: throw IllegalArgumentException("Usage: /proxsource sound test <name|namespace:key>")
                val resolved = sp.resolve(key)
                p.playSound(p.location, resolved, SoundCategory.MASTER, 1.0f, 1.0f)
                plugin.msg(p, "&7Played &f$resolved&7 at your position. &7(Silent? You need the resource pack for custom sounds.)")
            }
            else -> plugin.msg(sender, "&cUsage: /proxsource sound list | add <name> <url> | remove <name> | test <name>")
        }
    }

    private fun pack(sender: CommandSender, args: Array<out String>) {
        val sp = plugin.soundPack
        when (args.getOrNull(1)?.lowercase()) {
            "build" -> {
                val notes = plugin.reloadAll()
                plugin.msg(sender, "&aRescanned sounds and rebuilt the pack (${sp.count()} sound(s)).")
                notes.forEach { plugin.msg(sender, "&e$it") }
            }
            "info" -> {
                plugin.msg(sender, "&eResource pack")
                sender.sendMessage("§7Sounds: §f${sp.count()}")
                sender.sendMessage("§7File: §f${if (sp.packFile.exists()) sp.packFile.path + " (" + sp.packFile.length() / 1024 + " KB)" else "not built"}")
                sender.sendMessage("§7SHA-1: §f${sp.sha1Hex() ?: "-"}")
                sender.sendMessage("§7URL: §f${sp.publicUrl() ?: "none configured (see resource-pack.* in config.yml)"}")
                plugin.msg(sender, "&eBedrock (GeyserMC)")
                sender.sendMessage("§7File: §f${if (sp.bedrockFile.exists()) sp.bedrockFile.path + " (" + sp.bedrockFile.length() / 1024 + " KB)" else "not built (bedrock.enabled false, or no sounds)"}")
                sender.sendMessage("§7URL: §f${sp.bedrockUrl() ?: "none (host it yourself, or enable resource-pack.host)"}")
                sender.sendMessage("§7Add that URL to Geyser's config.yml under resource-pack-urls, or set bedrock.auto-copy-to-geyser. Bedrock players receive packs only when they log in.")
            }
            "send" -> {
                val targetName = args.getOrNull(2)
                val targets: List<Player> = when {
                    targetName == null && sender is Player -> listOf(sender)
                    targetName.equals("all", ignoreCase = true) -> Bukkit.getOnlinePlayers().toList()
                    targetName != null -> listOfNotNull(Bukkit.getPlayerExact(targetName))
                    else -> emptyList()
                }
                if (targets.isEmpty()) throw IllegalArgumentException("Usage: /proxsource pack send [player|all]")
                if (sp.publicUrl() == null || sp.sha1 == null) {
                    throw IllegalArgumentException("No pack URL/file available. Add sounds and set resource-pack.url or enable resource-pack.host in config.yml.")
                }
                targets.forEach { sp.sendTo(it) }
                plugin.msg(sender, "&aSent the pack to ${targets.size} player(s).")
            }
            else -> plugin.msg(sender, "&cUsage: /proxsource pack build | info | send [player|all] | get | optout")
        }
    }

    // ------------------------------------------------------------------ tab completion

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("proxmitysource.admin")) {
            val pre = args.last().lowercase()
            val opts = when {
                args.size == 1 -> listOf("mute", "pack")
                args.size == 2 && args[0].equals("pack", true) -> listOf("get", "optout")
                else -> emptyList()
            }
            return opts.filter { it.startsWith(pre) }
        }
        val out: List<String> = when (args.size) {
            1 -> adminSubs
            2 -> when (args[0].lowercase()) {
                "clone", "move", "delete", "info", "set", "trigger", "filter", "hours", "enable", "disable", "play", "probe" -> plugin.registry.ids()
                "bake" -> listOf("all") + plugin.registry.ids()
                "config" -> listOf("list", "get", "set")
                "sound" -> listOf("list", "add", "remove", "test")
                "pack" -> listOf("build", "info", "send", "get", "optout")
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "set" -> Prop.entries.map { it.key }
                "trigger" -> listOf("add", "remove")
                "filter" -> listOf("entity", "material")
                "hours" -> listOf("all", "6,12,18", "12")
                "config" -> if (args[1].lowercase() in listOf("get", "set")) leafKeys() else emptyList()
                "sound" -> if (args[1].lowercase() in listOf("remove", "test")) plugin.soundPack.names() else emptyList()
                "pack" -> if (args[1].lowercase() == "send") listOf("all") + Bukkit.getOnlinePlayers().map { it.name } else emptyList()
                else -> emptyList()
            }
            4 -> when (args[0].lowercase()) {
                "set" -> {
                    val prop = Prop.byKey(args[2])
                    when {
                        prop == Prop.SOUND -> plugin.soundPack.names() + listOf("minecraft:block.bell.use", "minecraft:block.bell.resonate", "minecraft:block.note_block.bell", "default")
                        prop == Prop.CATEGORY -> SoundCategory.values().map { it.name }
                        prop == null -> emptyList()
                        prop.type == ValueType.BOOL -> listOf("true", "false", "default")
                        else -> listOf("default")
                    }
                }
                "trigger" -> TriggerType.entries.map { it.name }
                "filter" -> listOf("add", "remove", "clear")
                else -> emptyList()
            }
            5 -> if (args[0].equals("filter", true) && args[3].lowercase() in listOf("add", "remove")) {
                if (args[2].equals("entity", true)) entityNames().toList() else Material.values().map { it.name }
            } else emptyList()
            else -> emptyList()
        }
        val prefix = args.last().lowercase()
        return out.filter { it.lowercase().startsWith(prefix) }.take(80)
    }
}
