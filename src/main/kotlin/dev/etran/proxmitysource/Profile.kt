package dev.etran.proxmitysource

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor

/**
 * The saved, baked sound profile of one source: for every cell of blocks around it, how far the sound
 * effectively has to travel (walls included) and how echoey the surroundings are.
 */
class Profile(
    val world: String,
    val sx: Int, val sy: Int, val sz: Int,
    val radius: Double,
    val hash: String,
    val x0: Int, val y0: Int, val z0: Int,
    val dx: Int, val dy: Int, val dz: Int,
    val cell: Int,
    val eff: ByteArray,
    val echoCell: Int,
    val wet: ByteArray,
    val size: ByteArray,
    val missingChunks: Int,
    val totalChunks: Int,
    val bakedAt: Long,
) {
    private val cx = ceilDiv(dx, cell)
    private val cz = ceilDiv(dz, cell)
    private val ex = ceilDiv(dx, echoCell)
    private val ez = ceilDiv(dz, echoCell)

    class Sample(val effDistance: Double, val wet: Double, val size: Double)

    /** Profile values at a position, or null if the spot is outside the profile or beyond the audible range. */
    fun sample(x: Double, y: Double, z: Double): Sample? {
        val vx = floor(x).toInt() - x0
        val vy = floor(y).toInt() - y0
        val vz = floor(z).toInt() - z0
        if (vx < 0 || vx >= dx || vy < 0 || vy >= dy || vz < 0 || vz >= dz) return null
        val b = eff[((vy / cell) * cz + (vz / cell)) * cx + (vx / cell)].toInt() and 0xFF
        if (b == 255) return null
        val ei = ((vy / echoCell) * ez + (vz / echoCell)) * ex + (vx / echoCell)
        return Sample(
            effDistance = b / 254.0 * radius,
            wet = (wet[ei].toInt() and 0xFF) / 255.0,
            size = (size[ei].toInt() and 0xFF) / 255.0,
        )
    }

    fun memoryBytes(): Long = eff.size.toLong() + wet.size + size.size

    fun write(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(GZIPOutputStream(BufferedOutputStream(FileOutputStream(tmp)))).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(VERSION)
            o.writeUTF(world)
            o.writeInt(sx); o.writeInt(sy); o.writeInt(sz)
            o.writeDouble(radius)
            o.writeUTF(hash)
            o.writeInt(x0); o.writeInt(y0); o.writeInt(z0)
            o.writeInt(dx); o.writeInt(dy); o.writeInt(dz)
            o.writeInt(cell)
            o.writeInt(echoCell)
            o.writeInt(missingChunks)
            o.writeInt(totalChunks)
            o.writeLong(bakedAt)
            o.writeInt(eff.size); o.write(eff)
            o.writeInt(wet.size); o.write(wet)
            o.writeInt(size.size); o.write(size)
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val MAGIC = 0x50525831 // "PRX1"
        private const val VERSION = 1

        /** Reads a saved profile, or null if the file is missing, damaged or from an incompatible version. */
        fun read(file: File): Profile? = try {
            DataInputStream(GZIPInputStream(BufferedInputStream(FileInputStream(file)))).use { i ->
                if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
                val world = i.readUTF()
                val sx = i.readInt(); val sy = i.readInt(); val sz = i.readInt()
                val radius = i.readDouble()
                val hash = i.readUTF()
                val x0 = i.readInt(); val y0 = i.readInt(); val z0 = i.readInt()
                val dx = i.readInt(); val dy = i.readInt(); val dz = i.readInt()
                val cell = i.readInt()
                val echoCell = i.readInt()
                val missing = i.readInt()
                val total = i.readInt()
                val bakedAt = i.readLong()
                val eff = ByteArray(i.readInt()).also { i.readFully(it) }
                val wet = ByteArray(i.readInt()).also { i.readFully(it) }
                val size = ByteArray(i.readInt()).also { i.readFully(it) }
                if (cell < 1 || echoCell < 1) return null
                val p = Profile(world, sx, sy, sz, radius, hash, x0, y0, z0, dx, dy, dz, cell, eff, echoCell, wet, size, missing, total, bakedAt)
                // sanity: array sizes must match the dimensions
                if (eff.size != ceilDiv(dx, cell) * ceilDiv(dy, cell) * ceilDiv(dz, cell)) return null
                if (wet.size != ceilDiv(dx, echoCell) * ceilDiv(dy, echoCell) * ceilDiv(dz, echoCell)) return null
                p
            }
        } catch (_: Exception) {
            null
        }
    }
}
