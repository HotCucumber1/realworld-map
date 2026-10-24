package com.worldexplorer.app.core.map

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.floor
import kotlin.random.Random

/** Procedural, seamlessly tiling cloud texture for the Fog of War fill pattern. */
object FogTexture {
    const val SIZE = 256
    const val DARK = 0xFF141D2B.toInt()
    const val LIGHT = 0xFF3B4A62.toInt()
    /** Average tone of the texture, used for the soft edge so it blends into the clouds. */
    const val AVERAGE = 0xFF263246.toInt()

    fun create(seed: Int = 7): Bitmap {
        val pixels = IntArray(SIZE * SIZE)
        val octaves = listOf(4 to 0.5, 8 to 0.25, 16 to 0.15, 32 to 0.1)
        val grids = octaves.map { (cells, _) -> val r = Random(seed + cells); Array(cells) { DoubleArray(cells) { r.nextDouble() } } }
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var v = 0.0
            octaves.forEachIndexed { i, (cells, weight) -> v += weight * sample(grids[i], cells, x.toDouble() / SIZE * cells, y.toDouble() / SIZE * cells) }
            val t = smooth(v.coerceIn(0.0, 1.0))
            pixels[y * SIZE + x] = lerp(DARK, LIGHT, t)
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    private fun sample(grid: Array<DoubleArray>, n: Int, x: Double, y: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        fun g(ix: Int, iy: Int) = grid[Math.floorMod(iy, n)][Math.floorMod(ix, n)] // wraps → seamless tiling
        val top = g(x0, y0) * (1 - fx) + g(x0 + 1, y0) * fx
        val bottom = g(x0, y0 + 1) * (1 - fx) + g(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    private fun lerp(a: Int, b: Int, t: Double): Int = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
