package dev.etran.proxmitysource

import kotlin.test.Test
import kotlin.test.assertTrue

/** Worst case for the flood: open air (every voxel in the sphere gets reached). */
class ProfilePerformanceTest {

    private fun bakeMillis(radius: Int): Long {
        val n = 2 * radius + 1
        val grid = ProfileCompute.Grid(0, 0, 0, n, n, n, ByteArray(n * n * n))
        val p = ProfileCompute.Params(radius.toDouble(), 0.5, 10.0, 2, 4, 16)
        val t0 = System.nanoTime()
        val r = ProfileCompute.run(grid, radius, radius, radius, p)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("PERF radius=$radius grid=${n}^3 voxels=${n.toLong() * n * n} reachableCells=${r.reachableCells} time=${ms}ms")
        return ms
    }

    @Test
    fun radius64BakesQuickly() {
        bakeMillis(32) // warm-up
        val ms = bakeMillis(64)
        assertTrue(ms < 20_000, "radius 64 took ${ms}ms")
    }

    @Test
    fun radius128StaysPractical() {
        val ms = bakeMillis(128)
        assertTrue(ms < 120_000, "radius 128 took ${ms}ms")
    }
}
