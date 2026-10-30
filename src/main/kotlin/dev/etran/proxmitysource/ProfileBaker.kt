package dev.etran.proxmitysource

import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.scheduler.BukkitTask
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Bakes sound profiles without hurting the server:
 *   1. on the main thread, chunk copies (ChunkSnapshot) are taken a few per tick;
 *   2. the heavy search (ProfileCompute) runs on an async thread;
 *   3. the result is stored in memory and written to plugins/ProxmitySource/profiles/.
 * One bake runs at a time. Building or breaking blocks near a source schedules a re-bake once things settle.
 */
class ProfileBaker(private val plugin: ProxmitySourcePlugin) : Listener {

    private class Job(
        val id: String,
        val world: World,
        val sx: Int, val sy: Int, val sz: Int,
        val radius: Double,
        val hash: String,
        val rules: WallRules,
        val params: ProfileCompute.Params,
        val x0: Int, val y0: Int, val z0: Int, val x1: Int, val y1: Int, val z1: Int,
        val cx0: Int, val cz0: Int, val cx1: Int, val cz1: Int,
        val allowLoad: Boolean,
        val startedAt: Long,
    ) {
        val snapshots = ArrayList<ChunkSnapshot?>()
        val total = (cx1 - cx0 + 1) * (cz1 - cz0 + 1)
        var next = 0

        /** Chunks that could not be copied (unloaded/never generated). Written by the async step only. */
        @Volatile
        var missing = 0
    }

    private val queue = ArrayDeque<String>()
    private var current: Job? = null
    private var captureTask: BukkitTask? = null
    private var watcher: BukkitTask? = null
    private val dirty = HashMap<String, Long>()
    private val lastBake = HashMap<String, Long>()
    private val failures = HashMap<String, String>()

    // ------------------------------------------------------------------ parameters / staleness

    fun paramsFor(s: Source): ProfileCompute.Params {
        val cfg = plugin.config
        return ProfileCompute.Params(
            radius = s.dbl(Prop.RADIUS, cfg),
            penetration = s.dbl(Prop.PENETRATION, cfg),
            wallCost = cfg.getDouble("profile.wall-cost", 10.0),
            cell = cfg.getInt("profile.cell-size", 2).coerceIn(1, 4),
            echoCell = cfg.getInt("profile.echo-cell-size", 4).coerceIn(2, 16),
            rayLength = cfg.getInt("profile.echo-ray-length", 16).coerceIn(4, 64),
        )
    }

    /** Fingerprint of everything that shapes a profile. If it differs from the saved one, the profile is stale. */
    fun hashFor(s: Source): String {
        val p = paramsFor(s)
        val text = "v1|${s.world}|${s.blockX}|${s.blockY}|${s.blockZ}|${p.radius}|${p.penetration}|${p.wallCost}|" +
            "${p.cell}|${p.echoCell}|${p.rayLength}|${WallRules(plugin.config).signature()}"
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun isStale(s: Source): Boolean = plugin.profiles[s.id]?.let { it.hash != hashFor(s) } ?: false

    fun status(id: String): String = when {
        current?.id == id -> {
            val j = current!!
            "baking (${j.next}/${j.total} chunks read)"
        }
        id in queue -> "queued for baking"
        failures.containsKey(id) -> "last bake failed: ${failures[id]}"
        else -> ""
    }

    fun failure(id: String): String? = failures[id]

    // ------------------------------------------------------------------ queue

    /** Queues a bake. Returns false (with [reason] filled) if it cannot be done. */
    fun request(id: String): Boolean {
        plugin.registry.get(id) ?: return false
        if (id !in queue) queue.add(id)
        pump()
        return true
    }

    private fun pump() {
        if (current != null) return
        val id = queue.poll() ?: return
        val s = plugin.registry.get(id)
        if (s == null) {
            pump()
            return
        }
        val world = Bukkit.getWorld(s.world)
        if (world == null) {
            failures[id] = "world '${s.world}' is not loaded"
            pump()
            return
        }
        val cfg = plugin.config
        val params = paramsFor(s)
        val maxRadius = cfg.getInt("profile.max-radius", 128)
        if (params.radius > maxRadius) {
            failures[id] = "radius ${params.radius} is above profile.max-radius ($maxRadius)"
            plugin.logger.warning("Source '$id': ${failures[id]}")
            pump()
            return
        }
        failures.remove(id)

        val r = Math.ceil(params.radius).toInt()
        val x0 = s.blockX - r
        val x1 = s.blockX + r
        val z0 = s.blockZ - r
        val z1 = s.blockZ + r
        val y0 = maxOf(world.minHeight, s.blockY - r)
        val y1 = minOf(world.maxHeight - 1, s.blockY + r)
        if (s.blockY < y0 || s.blockY > y1) {
            failures[id] = "source is outside the world's height range"
            pump()
            return
        }
        val job = Job(
            id, world, s.blockX, s.blockY, s.blockZ, params.radius, hashFor(s), WallRules(cfg), params,
            x0, y0, z0, x1, y1, z1,
            Math.floorDiv(x0, 16), Math.floorDiv(z0, 16), Math.floorDiv(x1, 16), Math.floorDiv(z1, 16),
            cfg.getBoolean("profile.load-unloaded-chunks", true),
            System.currentTimeMillis(),
        )
        current = job
        val perTick = cfg.getInt("profile.chunks-per-tick", 4).coerceIn(1, 64)
        captureTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { capture(job, perTick) }, 1L, 1L)
    }

    // ------------------------------------------------------------------ step 1: chunk copies (main thread, sliced)

    private fun capture(job: Job, perTick: Int) {
        if (current !== job) {
            captureTask?.cancel()
            return
        }
        if (plugin.registry.get(job.id) == null) {
            abort(job)
            return
        }
        var done = 0
        while (done < perTick && job.next < job.total) {
            val width = job.cx1 - job.cx0 + 1
            val cx = job.cx0 + job.next % width
            val cz = job.cz0 + job.next / width
            job.snapshots.add(snapshot(job.world, cx, cz, job.allowLoad))
            job.next++
            done++
        }
        if (job.next >= job.total) {
            captureTask?.cancel()
            captureTask = null
            compute(job)
        }
    }

    private fun snapshot(world: World, cx: Int, cz: Int, allowLoad: Boolean): ChunkSnapshot? {
        try {
            if (!world.isChunkLoaded(cx, cz)) {
                if (!allowLoad) return null
                val generated = try {
                    world.isChunkGenerated(cx, cz)
                } catch (_: Throwable) {
                    false
                }
                if (!generated) return null // never generate chunks just to bake a sound
            }
            return world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false)
        } catch (_: Throwable) {
            return null
        }
    }

    // ------------------------------------------------------------------ step 2: heavy work (async)

    private fun compute(job: Job) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            var profile: Profile? = null
            var error: Throwable? = null
            try {
                val grid = voxelize(job)
                job.snapshots.clear()
                val res = ProfileCompute.run(grid, job.sx, job.sy, job.sz, job.params)
                profile = Profile(
                    world = job.world.name,
                    sx = job.sx, sy = job.sy, sz = job.sz,
                    radius = job.radius,
                    hash = job.hash,
                    x0 = grid.x0, y0 = grid.y0, z0 = grid.z0,
                    dx = grid.dx, dy = grid.dy, dz = grid.dz,
                    cell = job.params.cell,
                    eff = res.eff,
                    echoCell = job.params.echoCell,
                    wet = res.wet,
                    size = res.size,
                    missingChunks = job.missing,
                    totalChunks = job.total,
                    bakedAt = System.currentTimeMillis(),
                )
                profile.write(plugin.profileFile(job.id))
            } catch (t: Throwable) {
                error = t
            }
            if (plugin.isEnabled) {
                Bukkit.getScheduler().runTask(plugin, Runnable { finish(job, profile, error) })
            }
        })
    }

    /** Turns the chunk copies into the dense wall-factor grid the algorithm works on. */
    private fun voxelize(job: Job): ProfileCompute.Grid {
        val dx = job.x1 - job.x0 + 1
        val dy = job.y1 - job.y0 + 1
        val dz = job.z1 - job.z0 + 1
        val wall = ByteArray(dx * dy * dz)
        var missing = 0
        var i = 0
        for (cz in job.cz0..job.cz1) {
            for (cx in job.cx0..job.cx1) {
                val snap = job.snapshots[i++]
                if (snap == null) {
                    missing++
                    continue
                }
                for (lz in 0..15) {
                    val gz = cz * 16 + lz
                    if (gz < job.z0 || gz > job.z1) continue
                    for (lx in 0..15) {
                        val gx = cx * 16 + lx
                        if (gx < job.x0 || gx > job.x1) continue
                        var idx = (gz - job.z0) * dx + (gx - job.x0) // y index 0 (= y0)
                        val stride = dz * dx
                        for (y in job.y0..job.y1) {
                            val f = job.rules.factor50(snap.getBlockType(lx, y, lz))
                            if (f != 0) wall[idx] = f.toByte()
                            idx += stride
                        }
                    }
                }
            }
        }
        job.missing = missing
        return ProfileCompute.Grid(job.x0, job.y0, job.z0, dx, dy, dz, wall)
    }

    // ------------------------------------------------------------------ step 3: done

    private fun finish(job: Job, profile: Profile?, error: Throwable?) {
        if (current === job) current = null
        if (profile != null && plugin.registry.get(job.id) != null) {
            plugin.profiles[job.id] = profile
            lastBake[job.id] = System.currentTimeMillis()
            if ((dirty[job.id] ?: 0L) <= job.startedAt) dirty.remove(job.id)
            failures.remove(job.id)
            val missing = profile.missingChunks
            plugin.logger.info(
                "Baked '${job.id}': ${profile.dx}x${profile.dy}x${profile.dz} blocks in ${System.currentTimeMillis() - job.startedAt} ms" +
                    if (missing > 0) " ($missing of ${profile.totalChunks} chunks were not available and count as open air)" else "",
            )
        } else if (error != null) {
            val msg = if (error is OutOfMemoryError) "out of memory (lower the radius or raise the server's -Xmx)" else (error.message ?: error.javaClass.simpleName)
            failures[job.id] = msg
            plugin.logger.warning("Bake of '${job.id}' failed: $msg")
        }
        pump()
    }

    private fun abort(job: Job) {
        captureTask?.cancel()
        captureTask = null
        if (current === job) current = null
        pump()
    }

    // ------------------------------------------------------------------ auto re-bake when blocks change

    fun restartWatcher() {
        watcher?.cancel()
        watcher = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { checkDirty() }, 100L, 100L)
    }

    fun shutdown() {
        watcher?.cancel()
        captureTask?.cancel()
        watcher = null
        captureTask = null
        queue.clear()
        current = null
    }

    fun forget(id: String) {
        queue.remove(id)
        dirty.remove(id)
        lastBake.remove(id)
        failures.remove(id)
    }

    private fun checkDirty() {
        if (dirty.isEmpty()) return
        val cfg = plugin.config
        if (!cfg.getBoolean("profile.auto-rebake", true)) return
        val settle = cfg.getLong("profile.settle-seconds", 30L) * 1000
        val minInterval = cfg.getLong("profile.rebake-min-interval-seconds", 120L) * 1000
        val now = System.currentTimeMillis()
        for ((id, changedAt) in dirty.entries.toList()) {
            if (plugin.registry.get(id) == null) {
                dirty.remove(id)
                continue
            }
            if (current?.id == id || id in queue) continue
            if (now - changedAt < settle) continue
            if (now - (lastBake[id] ?: 0L) < minInterval) continue
            request(id)
        }
    }

    private fun markDirty(world: String, x: Double, y: Double, z: Double) {
        if (!plugin.config.getBoolean("profile.auto-rebake", true)) return
        val now = System.currentTimeMillis()
        for (s in plugin.registry.all()) {
            if (s.world != world) continue
            val reach = s.dbl(Prop.RADIUS, plugin.config) + 1.0
            if (s.distSq(x, y, z) <= reach * reach) dirty[s.id] = now
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(ev: BlockPlaceEvent) = markDirty(ev.block.world.name, ev.block.x + 0.5, ev.block.y + 0.5, ev.block.z + 0.5)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(ev: BlockBreakEvent) = markDirty(ev.block.world.name, ev.block.x + 0.5, ev.block.y + 0.5, ev.block.z + 0.5)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplode(ev: EntityExplodeEvent) {
        if (ev.blockList().isEmpty()) return
        val l = ev.location
        l.world?.let { markDirty(it.name, l.x, l.y, l.z) }
    }
}
