package dev.etran.proxmitysource

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

/**
 * The sound-profile algorithm. Pure maths over a dense voxel grid, no Bukkit classes, safe to run on any thread.
 *
 *  1. WALL FIELD  - every voxel has a "wall factor" (0 = open air, 50 = a normal solid block, see WallRules).
 *  2. PROXIMITY   - a Dijkstra flood from the source over the 26-neighbourhood. A step costs its length
 *                   (1, 1.41 or 1.73 blocks) times (1 + wallCost x (1 - penetration) x factor of the voxel
 *                   entered). The cheapest route wins, so sound leaks through doors/windows and tunnels
 *                   around corners, while thick walls cost a lot. The result is an "effective distance"
 *                   per voxel; the plugin turns it into loudness at play time.
 *  3. ECHO FIELD  - per echo cell, 14 rays measure how enclosed the spot is (wet) and how big the open
 *                   space is (size).
 *  4. PACKING     - effective distances are down-sampled into cells (keeping the loudest voxel) and
 *                   quantised to one byte (0..254 = fraction of the radius, 255 = silent).
 */
object ProfileCompute {
    const val UNIT = 32 // distance units per block inside the search (radius 1024 x 32 still fits an unsigned short)
    const val ECHO_WALL_THRESHOLD = 25 // wall factor (x50) from which a block reflects sound for the echo rays

    class Grid(val x0: Int, val y0: Int, val z0: Int, val dx: Int, val dy: Int, val dz: Int, val wall: ByteArray) {
        init {
            require(wall.size == dx * dy * dz) { "wall array does not match the grid size" }
        }

        fun index(x: Int, y: Int, z: Int): Int = ((y * dz) + z) * dx + x
    }

    class Params(
        val radius: Double,
        val penetration: Double,
        val wallCost: Double,
        val cell: Int,
        val echoCell: Int,
        val rayLength: Int,
    )

    class Result(val eff: ByteArray, val wet: ByteArray, val size: ByteArray, val reachableCells: Int)

    /** [srcX]/[srcY]/[srcZ] are absolute voxel coordinates (they must lie inside the grid). */
    fun run(g: Grid, srcX: Int, srcY: Int, srcZ: Int, p: Params): Result {
        val sx = srcX - g.x0
        val sy = srcY - g.y0
        val sz = srcZ - g.z0
        require(sx in 0 until g.dx && sy in 0 until g.dy && sz in 0 until g.dz) { "source is outside the grid" }
        val limit = (p.radius * UNIT).toInt()
        val dist = distanceField(g, sx, sy, sz, p, limit)
        val echo = echoFields(g, dist, p, limit)
        return packEff(g, dist, p, limit, echo.first, echo.second)
    }

    // ------------------------------------------------------------------ 2. proximity (Dijkstra)

    private class LongHeap(cap: Int = 1 shl 16) {
        private var a = LongArray(cap)
        var n = 0
            private set

        fun push(v: Long) {
            if (n == a.size) a = a.copyOf(n * 2)
            var i = n++
            while (i > 0) {
                val parent = (i - 1) shr 1
                if (a[parent] <= v) break
                a[i] = a[parent]
                i = parent
            }
            a[i] = v
        }

        fun pop(): Long {
            val top = a[0]
            val last = a[--n]
            if (n > 0) {
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= n) break
                    if (c + 1 < n && a[c + 1] < a[c]) c++
                    if (a[c] >= last) break
                    a[i] = a[c]
                    i = c
                }
                a[i] = last
            }
            return top
        }
    }

    /** Effective distance per voxel in 1/UNIT blocks (unsigned short, 0xFFFF = not reached within the radius). */
    internal fun distanceField(g: Grid, sx: Int, sy: Int, sz: Int, p: Params, limit: Int): ShortArray {
        val dx = g.dx
        val dy = g.dy
        val dz = g.dz
        val n = dx * dy * dz
        val dist = ShortArray(n)
        dist.fill(-1)

        // 26 directions and a precomputed step cost for every (direction, wall factor) pair.
        val ddx = IntArray(26)
        val ddy = IntArray(26)
        val ddz = IntArray(26)
        val off = IntArray(26)
        val costTab = IntArray(26 * 256)
        val wallMul = 1.0 - p.penetration
        var k = 0
        for (z in -1..1) for (y in -1..1) for (x in -1..1) {
            if (x == 0 && y == 0 && z == 0) continue
            ddx[k] = x
            ddy[k] = y
            ddz[k] = z
            off[k] = (y * dz + z) * dx + x
            val len = sqrt((x * x + y * y + z * z).toDouble()) * UNIT
            for (w in 0..255) {
                val mult = 1.0 + p.wallCost * wallMul * (w / 50.0)
                costTab[k * 256 + w] = (len * mult + 0.5).toInt().coerceAtLeast(1)
            }
            k++
        }

        val wall = g.wall
        val heap = LongHeap()
        val srcIdx = g.index(sx, sy, sz)
        dist[srcIdx] = 0
        heap.push(srcIdx.toLong())

        while (heap.n > 0) {
            val top = heap.pop()
            val c = (top ushr 32).toInt()
            val idx = (top and 0xFFFFFFFFL).toInt()
            if (c > (dist[idx].toInt() and 0xFFFF)) continue
            val x = idx % dx
            val t = idx / dx
            val z = t % dz
            val y = t / dz
            for (d in 0 until 26) {
                val nx = x + ddx[d]
                if (nx < 0 || nx >= dx) continue
                val ny = y + ddy[d]
                if (ny < 0 || ny >= dy) continue
                val nz = z + ddz[d]
                if (nz < 0 || nz >= dz) continue
                val nidx = idx + off[d]
                val nc = c + costTab[d * 256 + (wall[nidx].toInt() and 0xFF)]
                if (nc > limit) continue
                if (nc < (dist[nidx].toInt() and 0xFFFF)) {
                    dist[nidx] = nc.toShort()
                    heap.push((nc.toLong() shl 32) or nidx.toLong())
                }
            }
        }
        return dist
    }

    // ------------------------------------------------------------------ 3. echo / enclosure

    private val rayDirs: Array<DoubleArray> = run {
        val list = ArrayList<DoubleArray>()
        for (z in -1..1) for (y in -1..1) for (x in -1..1) {
            if (x == 0 && y == 0 && z == 0) continue
            val axes = (if (x != 0) 1 else 0) + (if (y != 0) 1 else 0) + (if (z != 0) 1 else 0)
            if (axes == 2) continue // 6 axis rays + 8 corner rays = 14
            val len = sqrt((x * x + y * y + z * z).toDouble())
            list.add(doubleArrayOf(x / len, y / len, z / len))
        }
        list.toTypedArray()
    }

    /** Returns (wet, size) byte arrays over the echo-cell grid. */
    private fun echoFields(g: Grid, dist: ShortArray, p: Params, limit: Int): Pair<ByteArray, ByteArray> {
        val ec = p.echoCell
        val ex = ceilDiv(g.dx, ec)
        val ey = ceilDiv(g.dy, ec)
        val ez = ceilDiv(g.dz, ec)
        val wet = ByteArray(ex * ey * ez)
        val size = ByteArray(ex * ey * ez)
        val len = p.rayLength.coerceAtLeast(1)
        for (iz in 0 until ez) for (iy in 0 until ey) for (ix in 0 until ex) {
            val vx = min(ix * ec + ec / 2, g.dx - 1)
            val vy = min(iy * ec + ec / 2, g.dy - 1)
            val vz = min(iz * ec + ec / 2, g.dz - 1)
            if ((dist[g.index(vx, vy, vz)].toInt() and 0xFFFF) > limit) continue // silent here, skip the rays
            var hits = 0
            var sum = 0.0
            for (dir in rayDirs) {
                var hitAt = -1
                for (s in 1..len) {
                    val px = vx + (dir[0] * s).roundToInt()
                    val py = vy + (dir[1] * s).roundToInt()
                    val pz = vz + (dir[2] * s).roundToInt()
                    if (px < 0 || px >= g.dx || py < 0 || py >= g.dy || pz < 0 || pz >= g.dz) break // beyond the grid = open
                    if ((g.wall[g.index(px, py, pz)].toInt() and 0xFF) >= ECHO_WALL_THRESHOLD) {
                        hitAt = s
                        break
                    }
                }
                if (hitAt > 0) {
                    hits++
                    sum += hitAt
                } else sum += len
            }
            val ci = ((iy * ez) + iz) * ex + ix
            wet[ci] = (hits / rayDirs.size.toDouble() * 255).roundToInt().coerceIn(0, 255).toByte()
            size[ci] = (sum / (rayDirs.size * len) * 255).roundToInt().coerceIn(0, 255).toByte()
        }
        return Pair(wet, size)
    }

    // ------------------------------------------------------------------ 4. packing

    private fun packEff(g: Grid, dist: ShortArray, p: Params, limit: Int, wet: ByteArray, size: ByteArray): Result {
        val cs = p.cell
        val cx = ceilDiv(g.dx, cs)
        val cy = ceilDiv(g.dy, cs)
        val cz = ceilDiv(g.dz, cs)
        val eff = ByteArray(cx * cy * cz)
        var reachable = 0
        for (iz in 0 until cz) for (iy in 0 until cy) for (ix in 0 until cx) {
            var m = 0xFFFF
            for (z in iz * cs until min(g.dz, (iz + 1) * cs))
                for (y in iy * cs until min(g.dy, (iy + 1) * cs))
                    for (x in ix * cs until min(g.dx, (ix + 1) * cs)) {
                        val d = dist[g.index(x, y, z)].toInt() and 0xFFFF
                        if (d < m) m = d
                    }
            val ci = ((iy * cz) + iz) * cx + ix
            if (m > limit) {
                eff[ci] = -1 // 255 = silent
            } else {
                eff[ci] = min(254, (m.toDouble() / limit * 254).roundToInt()).toByte()
                reachable++
            }
        }
        return Result(eff, wet, size, reachable)
    }
}
