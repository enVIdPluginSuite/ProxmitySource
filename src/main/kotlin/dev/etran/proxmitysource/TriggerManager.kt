package dev.etran.proxmitysource

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Detects triggers and fires sources. Every trigger looks at a sphere of `trigger-radius` around the source.
 *  - PLAYER_INTERACT and REDSTONE are event driven.
 *  - PLAYER_ENTER / PLAYER_EXIT / ENTITY_ENTER / TIME are checked by one light polling task.
 */
class TriggerManager(private val plugin: ProxmitySourcePlugin) : Listener {

    private val lastFire = HashMap<String, Long>()
    private val insidePlayers = HashMap<String, Set<UUID>>()
    private val insideEntities = HashMap<String, Set<UUID>>()
    private val lastHour = HashMap<String, Long>()
    private val lastTimer = HashMap<String, Long>()
    private var task: BukkitTask? = null

    fun restart() {
        stop()
        resetTracking()
        val interval = plugin.config.getInt("settings.poll-interval-ticks", 10).coerceIn(1, 200).toLong()
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { poll() }, interval, interval)
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun triggerRadiusSq(s: Source): Double {
        val r = s.dbl(Prop.TRIGGER_RADIUS, plugin.config)
        return r * r
    }

    /** Re-reads who/what is currently near every source WITHOUT firing anything (so reloads never ring bells). */
    fun resetTracking() {
        insidePlayers.clear()
        insideEntities.clear()
        lastHour.clear()
        lastTimer.clear()
        lastFire.keys.retainAll(plugin.registry.ids().toSet())
        val now = System.currentTimeMillis()
        for (s in plugin.registry.all()) {
            lastTimer[s.id] = now // first timer firing is one full interval from now, never right at reload
            val world = Bukkit.getWorld(s.world) ?: continue
            insidePlayers[s.id] = playersNear(s, world)
            if (TriggerType.ENTITY_ENTER in s.triggers) insideEntities[s.id] = entitiesNear(s, world)
            lastHour[s.id] = Math.floorDiv(world.fullTime, 1000L)
        }
    }

    /** Fires a source if it is enabled and off cooldown. Returns true if it actually fired. */
    fun fire(s: Source, tollsOverride: Int? = null, ignoreCooldown: Boolean = false): Boolean {
        if (!s.enabled) return false
        val world = Bukkit.getWorld(s.world) ?: return false
        val now = System.currentTimeMillis()
        val cooldownMs = (s.dbl(Prop.COOLDOWN, plugin.config) * 1000).toLong()
        val last = lastFire[s.id]
        if (!ignoreCooldown && last != null && now - last < cooldownMs) return false
        lastFire[s.id] = now
        plugin.engine.play(s, world, tollsOverride)
        return true
    }

    // ---------------------------------------------------------------- polling

    private fun poll() {
        for (s in plugin.registry.all()) {
            if (!s.enabled || s.triggers.isEmpty()) continue
            val world = Bukkit.getWorld(s.world) ?: continue
            if (TriggerType.PLAYER_ENTER in s.triggers || TriggerType.PLAYER_EXIT in s.triggers) pollPlayers(s, world)
            if (TriggerType.ENTITY_ENTER in s.triggers) pollEntities(s, world)
            if (TriggerType.TIME in s.triggers) pollTime(s, world)
            if (TriggerType.TIMER in s.triggers) pollTimer(s)
        }
    }

    /** Fires every frequency-seconds of real time. Intentionally ignores the cooldown (the frequency IS the rhythm). */
    private fun pollTimer(s: Source) {
        val now = System.currentTimeMillis()
        val everyMs = (s.dbl(Prop.FREQUENCY, plugin.config) * 1000).toLong()
        val last = lastTimer.getOrPut(s.id) { now }
        if (now - last < everyMs) return
        // Keep a steady rhythm: advance by whole intervals instead of "now" so polling jitter does not accumulate.
        lastTimer[s.id] = last + everyMs * ((now - last) / everyMs)
        fire(s, ignoreCooldown = true)
    }

    private fun playersNear(s: Source, world: World): Set<UUID> {
        val ignoreSpectators = plugin.config.getBoolean("settings.ignore-spectators", true)
        val r2 = triggerRadiusSq(s)
        val out = HashSet<UUID>()
        for (p in world.players) {
            if (ignoreSpectators && p.gameMode == GameMode.SPECTATOR) continue
            val l = p.location
            if (s.distSq(l.x, l.y, l.z) <= r2) out.add(p.uniqueId)
        }
        return out
    }

    private fun pollPlayers(s: Source, world: World) {
        val previous = insidePlayers[s.id] ?: emptySet()
        val now = playersNear(s, world)
        insidePlayers[s.id] = now
        val entered = now.any { it !in previous }
        // Someone who simply logged out is not an "exit".
        val exited = previous.any { it !in now && Bukkit.getPlayer(it)?.isOnline == true }
        if (entered && TriggerType.PLAYER_ENTER in s.triggers) fire(s)
        else if (exited && TriggerType.PLAYER_EXIT in s.triggers) fire(s)
    }

    private fun accepts(s: Source, ent: Entity): Boolean {
        if (ent is Player) return false
        if (s.entityTypes.isEmpty()) return ent is LivingEntity && ent !is ArmorStand
        return entityName(ent) in s.entityTypes
    }

    private fun entitiesNear(s: Source, world: World): Set<UUID> {
        val r = s.dbl(Prop.TRIGGER_RADIUS, plugin.config)
        val r2 = r * r
        val out = HashSet<UUID>()
        for (ent in world.getNearbyEntities(s.locationIn(world), r, r, r)) {
            val l = ent.location
            if (s.distSq(l.x, l.y, l.z) <= r2 && accepts(s, ent)) out.add(ent.uniqueId)
        }
        return out
    }

    private fun pollEntities(s: Source, world: World) {
        val previous = insideEntities[s.id] ?: emptySet()
        val now = entitiesNear(s, world)
        insideEntities[s.id] = now
        if (now.any { it !in previous }) fire(s)
    }

    private fun pollTime(s: Source, world: World) {
        val hourIndex = Math.floorDiv(world.fullTime, 1000L)
        val prev = lastHour.put(s.id, hourIndex) ?: return
        if (hourIndex <= prev) return
        // World tick 0 is 06:00 on the in-game clock; every 1000 ticks is one hour.
        val hour = (((hourIndex % 24) + 6) % 24).toInt()
        if (s.hours.isNotEmpty() && hour !in s.hours) return
        val tolls = if (s.bool(Prop.TOLL_BY_HOUR, plugin.config)) (if (hour % 12 == 0) 12 else hour % 12) else null
        fire(s, tolls)
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(ev: PlayerInteractEvent) {
        if (ev.action != Action.RIGHT_CLICK_BLOCK || ev.hand != EquipmentSlot.HAND) return
        val block = ev.clickedBlock ?: return
        val worldName = block.world.name
        val matName = block.type.name
        for (s in plugin.registry.all()) {
            if (TriggerType.PLAYER_INTERACT !in s.triggers || !s.enabled) continue
            if (s.world != worldName) continue
            if (s.distSq(block.x + 0.5, block.y + 0.5, block.z + 0.5) > triggerRadiusSq(s)) continue
            if (s.materials.isNotEmpty() && matName !in s.materials) continue
            fire(s)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRedstone(ev: BlockRedstoneEvent) {
        // Only the rising edge (off -> on).
        if (ev.oldCurrent > 0 || ev.newCurrent <= 0) return
        val b = ev.block
        val worldName = b.world.name
        for (s in plugin.registry.all()) {
            if (TriggerType.REDSTONE !in s.triggers || !s.enabled) continue
            if (s.world != worldName) continue
            if (s.distSq(b.x + 0.5, b.y + 0.5, b.z + 0.5) <= triggerRadiusSq(s)) fire(s)
        }
    }

    @EventHandler
    fun onJoin(ev: PlayerJoinEvent) {
        // Logging in while standing near a source should not count as walking up to it.
        val p = ev.player
        val l = p.location
        for (s in plugin.registry.all()) {
            if (s.world == p.world.name && s.distSq(l.x, l.y, l.z) <= triggerRadiusSq(s)) {
                insidePlayers[s.id] = (insidePlayers[s.id] ?: emptySet()) + p.uniqueId
            }
        }
    }

    companion object {
        fun entityName(ent: Entity): String = try {
            ent.type.name
        } catch (_: Throwable) {
            ent.javaClass.simpleName.removePrefix("Craft").uppercase()
        }
    }
}
