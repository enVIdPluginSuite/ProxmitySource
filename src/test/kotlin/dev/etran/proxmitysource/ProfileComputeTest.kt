package dev.etran.proxmitysource

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileComputeTest {
    private val n = 41
    private val c = 20 // source at the centre voxel
    private val radius = 16.0

    private fun grid(wallAt: (Int, Int, Int) -> Int = { _, _, _ -> 0 }): ProfileCompute.Grid {
        val w = ByteArray(n * n * n)
        val g = ProfileCompute.Grid(0, 0, 0, n, n, n, w)
        for (y in 0 until n) for (z in 0 until n) for (x in 0 until n) w[g.index(x, y, z)] = wallAt(x, y, z).toByte()
        return g
    }

    private fun params(pen: Double) = ProfileCompute.Params(radius, pen, 10.0, 1, 4, 16)

    /** Effective distance in blocks at a voxel, or null if silent. cell = 1, so eff index == voxel index. */
    private fun eff(r: ProfileCompute.Result, g: ProfileCompute.Grid, x: Int, y: Int, z: Int): Double? {
        val b = r.eff[g.index(x, y, z)].toInt() and 0xFF
        return if (b == 255) null else b / 254.0 * radius
    }

    @Test
    fun openAirIsEuclideanAndSilentBeyondRadius() {
        val g = grid()
        val r = ProfileCompute.run(g, c, c, c, params(0.5))
        assertTrue(abs(eff(r, g, 30, 20, 20)!! - 10.0) < 0.3, "axis distance")
        assertTrue(abs(eff(r, g, 27, 27, 27)!! - 12.12) < 0.4, "space diagonal")
        assertTrue(abs(eff(r, g, 30, 25, 20)!! - 11.18) < 1.0, "mixed direction (26-neighbour error is under 8%)")
        assertEquals(null, eff(r, g, 38, 20, 20), "18 blocks away is beyond the 16 block radius")
    }

    @Test
    fun wallBlocksSoundAndPenetrationLetsItThrough() {
        // one-block-thick solid plane at x = 24 across the whole grid
        val g = grid { x, _, _ -> if (x == 24) 50 else 0 }
        val blocked = ProfileCompute.run(g, c, c, c, params(0.0))
        assertEquals(null, eff(blocked, g, 28, 20, 20), "penetration 0: wall costs 11 extra, 8 + 10 = 18 > 16")

        val half = ProfileCompute.run(g, c, c, c, params(0.5))
        val h = eff(half, g, 28, 20, 20)!!
        assertTrue(abs(h - 13.0) < 0.5, "penetration 0.5: 8 blocks + 5 extra = 13, got $h")

        val open = ProfileCompute.run(g, c, c, c, params(1.0))
        val o = eff(open, g, 28, 20, 20)!!
        assertTrue(abs(o - 8.0) < 0.3, "penetration 1: the wall is transparent, got $o")
        assertTrue(eff(half, g, 28, 20, 20)!! > eff(open, g, 28, 20, 20)!!)
    }

    @Test
    fun soundFindsAnOpeningInTheWall() {
        // the same plane, with a doorway at y = 20, z = 20
        val g = grid { x, y, z -> if (x == 24 && !(y == 20 && z == 20)) 50 else 0 }
        val r = ProfileCompute.run(g, c, c, c, params(0.0))
        val behind = eff(r, g, 28, 20, 22)
        assertTrue(behind != null, "sound must reach behind the wall through the opening")
        assertTrue(behind!! < 11.0, "route through the doorway is about 8.5 blocks, got $behind")
        // a spot whose route through the doorway is longer than the radius (4 + sqrt(16 + 196) = 18.6) stays silent
        assertEquals(null, eff(r, g, 28, 20, 34), "too far around the doorway to be audible")
        // and without the doorway the same spot behind the wall is silent too
        val solid = grid { x, _, _ -> if (x == 24) 50 else 0 }
        assertEquals(null, eff(ProfileCompute.run(solid, c, c, c, params(0.0)), solid, 28, 20, 22), "no doorway, no sound")
    }

    @Test
    fun enclosedRoomIsEchoeyOpenFieldIsNot() {
        val ex = (n + 3) / 4
        fun wetAtCentre(r: ProfileCompute.Result): Int = r.wet[((5 * ex) + 5) * ex + 5].toInt() and 0xFF
        fun sizeAtCentre(r: ProfileCompute.Result): Int = r.size[((5 * ex) + 5) * ex + 5].toInt() and 0xFF

        val room = grid { x, y, z ->
            val m = maxOf(abs(x - c), abs(y - c), abs(z - c))
            if (m == 5) 50 else 0
        }
        val inRoom = ProfileCompute.run(room, c, c, c, params(1.0))
        val open = ProfileCompute.run(grid(), c, c, c, params(1.0))

        assertTrue(wetAtCentre(inRoom) > 240, "closed room: nearly every ray hits a wall, got ${wetAtCentre(inRoom)}")
        assertEquals(0, wetAtCentre(open), "open field: no ray hits anything")
        assertTrue(sizeAtCentre(inRoom) < sizeAtCentre(open), "a small room reads as smaller than open space")
    }

    @Test
    fun profileSurvivesSaveAndLoad() {
        val g = grid { x, _, _ -> if (x == 24) 50 else 0 }
        val r = ProfileCompute.run(g, c, c, c, params(0.5))
        val p = Profile("world", c, c, c, radius, "abc", 0, 0, 0, n, n, n, 1, r.eff, 4, r.wet, r.size, 2, 9, 123L)
        val f = kotlin.io.path.createTempFile("prx", ".prx").toFile()
        try {
            p.write(f)
            val q = Profile.read(f)!!
            assertEquals(p.hash, q.hash)
            assertEquals(p.missingChunks, q.missingChunks)
            assertTrue(p.eff.contentEquals(q.eff))
            val a = p.sample(28.5, 20.5, 20.5)!!
            val b = q.sample(28.5, 20.5, 20.5)!!
            assertEquals(a.effDistance, b.effDistance)
        } finally {
            f.delete()
        }
    }
}
