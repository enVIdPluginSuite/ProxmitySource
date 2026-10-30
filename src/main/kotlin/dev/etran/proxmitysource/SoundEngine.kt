package dev.etran.proxmitysource

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.World
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Plays a source to every player in range, shaped by the source's baked profile.
 *
 * For each listener, per toll:
 *  - direct loudness comes from the profile's "effective distance" (distance + wall penalties),
 *  - echoes: count = echoiness x echo-max-count, strength = echoiness x how enclosed the LISTENER's
 *    surroundings are, delay scaled by the size of the space they stand in,
 *  - everything is delayed by how long sound would take to travel there.
 * If no profile has been baked yet, a plain distance falloff with no walls is used.
 *
 * Minecraft's client fades a sound linearly to silence over 16 blocks at volume 1. We compute the exact
 * gain each listener should hear and play the sound from a point on the line toward the real source,
 * placed so that the client's own 16-block fade produces exactly that gain (direction/panning stays right).
 *
 * Only plain string sound keys are used (never the Sound enum) so this works across the Sound
 * enum -> interface change in newer Minecraft versions.
 */
class SoundEngine(private val plugin: ProxmitySourcePlugin) {

    private class Hit(val id: UUID, val gain: Double, val pitch: Double)

    private class Ctx(
        val sourceId: String,
        val origin: Location,
        val sound: String,
        val category: SoundCategory,
        val radius: Double,
        val maxGain: Double,
        val amplification: Double,
        val falloff: Double,
        val pitch: Double,
        val echoiness: Double,
        val echoMax: Int,
        val echoDelay: Int,
        val echoDecay: Double,
        val echoShift: Double,
    )

    /** Fires a source. [tollsOverride] replaces the configured toll count (used for hour-based tolling). */
    fun play(s: Source, world: World, tollsOverride: Int? = null) {
        val cfg = plugin.config
        val ctx = Ctx(
            sourceId = s.id,
            origin = s.locationIn(world),
            sound = plugin.soundPack.resolve(s.str(Prop.SOUND, cfg)),
            category = parseCategory(s.str(Prop.CATEGORY, cfg)),
            radius = s.dbl(Prop.RADIUS, cfg),
            maxGain = s.dbl(Prop.VOLUME, cfg),
            amplification = s.dbl(Prop.AMPLIFICATION, cfg),
            falloff = s.dbl(Prop.FALLOFF, cfg).coerceIn(0.1, 8.0),
            pitch = s.dbl(Prop.PITCH, cfg),
            echoiness = s.dbl(Prop.ECHOINESS, cfg),
            echoMax = s.int(Prop.ECHO_MAX_COUNT, cfg),
            echoDelay = s.int(Prop.ECHO_DELAY, cfg),
            echoDecay = s.dbl(Prop.ECHO_DECAY, cfg),
            echoShift = s.dbl(Prop.ECHO_PITCH_SHIFT, cfg),
        )
        val tolls = tollsOverride ?: s.int(Prop.TOLLS, cfg)
        val intervalTicks = s.dbl(Prop.TOLL_INTERVAL, cfg) * 20.0
        for (t in 0 until tolls) {
            val delay = (t * intervalTicks).roundToInt()
            if (delay <= 0) tollStart(ctx)
            else Bukkit.getScheduler().runTaskLater(plugin, Runnable { tollStart(ctx) }, delay.toLong())
        }
    }

    private fun tollStart(ctx: Ctx) {
        val world = ctx.origin.world ?: return
        val cfg = plugin.config
        val minGain = cfg.getDouble("settings.min-audible-gain", 0.02)
        val propagation = cfg.getBoolean("settings.propagation-delay.enabled", true)
        val bpt = cfg.getDouble("settings.propagation-delay.blocks-per-tick", 17.15).coerceAtLeast(0.1)
        val profile = plugin.profiles[ctx.sourceId]
        val radius = profile?.radius ?: ctx.radius
        val echoCount = (ctx.echoiness * ctx.echoMax).roundToInt()

        val buckets = HashMap<Int, MutableList<Hit>>()
        fun add(tick: Int, hit: Hit) {
            buckets.getOrPut(tick) { ArrayList() }.add(hit)
        }

        for (p in world.players) {
            if (plugin.isMuted(p)) continue
            val eye = p.eyeLocation
            val d = eye.distance(ctx.origin)
            if (d >= radius) continue

            val sample: Profile.Sample = if (profile != null) {
                profile.sample(eye.x, eye.y, eye.z) ?: continue
            } else {
                Profile.Sample(d, 0.3, 0.6) // not baked yet: simple distance falloff
            }
            if (sample.effDistance >= radius) continue

            val direct = ctx.maxGain * ctx.amplification * (1.0 - sample.effDistance / radius).pow(ctx.falloff)
            if (direct < minGain) continue
            val travel = if (propagation) (d / bpt).toInt() else 0
            add(travel, Hit(p.uniqueId, direct, ctx.pitch))

            if (echoCount > 0) {
                val reverb = ctx.echoiness * (0.2 + 0.8 * sample.wet)
                val spacing = ctx.echoDelay * (0.6 + 0.8 * sample.size)
                for (i in 1..echoCount) {
                    val g = direct * reverb * ctx.echoDecay.pow(i)
                    if (g < minGain) break
                    add(travel + (i * spacing).roundToInt().coerceAtLeast(1), Hit(p.uniqueId, g, ctx.pitch * ctx.echoShift.pow(i)))
                }
            }
        }

        for ((tick, hits) in buckets) {
            if (tick <= 0) playHits(ctx, hits)
            else Bukkit.getScheduler().runTaskLater(plugin, Runnable { playHits(ctx, hits) }, tick.toLong())
        }
    }

    private fun playHits(ctx: Ctx, hits: List<Hit>) {
        val maxLayers = plugin.config.getInt("settings.max-layers", 4).coerceIn(1, 16)
        for (h in hits) {
            val p = Bukkit.getPlayer(h.id) ?: continue
            if (!p.isOnline || p.world != ctx.origin.world) continue

            val eye = p.eyeLocation
            val dx = ctx.origin.x - eye.x
            val dy = ctx.origin.y - eye.y
            val dz = ctx.origin.z - eye.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val pitch = h.pitch.coerceIn(0.5, 2.0).toFloat()

            // A gain above 1.0 (amplification) is played as extra identical copies layered on top of each other.
            var remaining = h.gain.coerceIn(0.0, maxLayers.toDouble())
            while (remaining > 0.001) {
                val gain = minOf(1.0, remaining)
                remaining -= gain
                // Place the sound on the line toward the real source so the client's 16-block fade yields `gain`.
                val virtualDist = 16.0 * (1.0 - gain)
                val at = if (d < 1.0e-3) eye else Location(
                    eye.world,
                    eye.x + dx / d * virtualDist,
                    eye.y + dy / d * virtualDist,
                    eye.z + dz / d * virtualDist,
                )
                p.playSound(at, ctx.sound, ctx.category, 1.0f, pitch)
            }
        }
    }

    companion object {
        fun parseCategory(s: String): SoundCategory =
            SoundCategory.values().firstOrNull { it.name.equals(s.trim(), ignoreCase = true) } ?: SoundCategory.BLOCKS
    }
}
