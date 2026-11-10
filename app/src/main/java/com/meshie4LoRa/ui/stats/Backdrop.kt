package com.meshie4LoRa.ui.stats

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Scene behind the chibi in the Pokédex window. */
enum class Backdrop(val label: String) {
    CLASSIC("Mesh mint"), MESH("Radio waves"), RAVE("Rave stage"), FESTIVAL("Festival"), DISCO("Disco"),
    GALAXY("Galaxy"), NEON_CITY("Neon city"), SUNSET("Sunset"), UNDERWATER("Deep sea"), SAKURA("Blossoms"),
    WILD_WEST("Wild west"), CAMPFIRE("Campfire"), HEAVEN("Cloud nine"), HELLFIRE("Hellfire"), MATRIX("The Matrix"),
    CIRCUIT("Circuit board"), CANDY("Candyland"),
}

// Fixed pseudo-random positions so scenes don't jump around between frames.
private val scatter: List<Triple<Float, Float, Float>> = Random(42).let { r -> List(40) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } }

/** Draws [backdrop] filling the box; [animate] = false for static thumbnails. */
@Composable
fun BackdropCanvas(backdrop: Backdrop, modifier: Modifier = Modifier, animate: Boolean = true) {
    val loop = rememberInfiniteTransition(label = "backdrop")
    val tAnim by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val t = if (animate) tAnim else 0.3f
    Canvas(modifier) {
        val u = size.width / 100f
        val v = size.height / 100f
        with(Scene(u, v, t)) { draw(backdrop) }
    }
}

private class Scene(val u: Float, val v: Float, val t: Float) {
    fun p(x: Float, y: Float) = Offset(x * u, y * v)
    fun s(w: Float, h: Float) = Size(w * u, h * v)
    fun wave(speed: Float = 1f, phase: Float = 0f) = sin((t * speed + phase) * 2 * PI).toFloat()

    fun DrawScope.sky(c1: Color, c2: Color, c3: Color? = null) = drawRect(Brush.verticalGradient(listOfNotNull(c1, c2, c3)))

    fun DrawScope.draw(b: Backdrop) {
        when (b) {
            Backdrop.CLASSIC -> {
                drawRect(Color(0xFFE8F6EE))
                for (i in 1..9) {
                    drawLine(Color(0x2267EA94), p(i * 10f, 0f), p(i * 10f, 100f), 0.4f * u)
                    drawLine(Color(0x2267EA94), p(0f, i * 10f), p(100f, i * 10f), 0.4f * u)
                }
            }
            Backdrop.MESH -> {
                sky(Color(0xFF0E2A1F), Color(0xFF1B4D3A))
                for (i in 0..3) {
                    val r = ((t + i / 4f) % 1f) * 70f
                    drawCircle(Color(0xFF67EA94).copy(alpha = 1f - r / 70f), r * u, p(50f, 92f), style = Stroke(1.2f * u))
                }
                drawPath(Path().apply { moveTo(46f * u, 100f * v); lineTo(50f * u, 80f * v); lineTo(54f * u, 100f * v); close() }, Color(0xFF9AE6B4))
                drawCircle(Color(0xFF67EA94), 2.2f * u, p(50f, 79f))
                scatter.take(12).forEach { (x, y, z) -> drawCircle(Color(0xFF67EA94).copy(alpha = 0.3f + 0.4f * z), 0.9f * u, p(x * 100, y * 60)) }
            }
            Backdrop.RAVE -> {
                sky(Color(0xFF12002B), Color(0xFF3A0A5E))
                val beams = listOf(Color(0xFFFF2BD6), Color(0xFF00E5FF), Color(0xFF7CFF00), Color(0xFFFFE600))
                beams.forEachIndexed { i, c ->
                    val from = if (i % 2 == 0) p(8f, 100f) else p(92f, 100f)
                    val ang = (if (i % 2 == 0) -60f else -120f) + wave(1f, i * 0.25f) * 25f
                    val rad = ang * PI.toFloat() / 180f
                    drawLine(c.copy(alpha = 0.55f), from, Offset(from.x + cos(rad) * 140 * u, from.y + sin(rad) * 140 * v), 1.6f * u, StrokeCap.Round)
                }
                drawRect(Color(0xFF1A1A1A), p(0f, 86f), s(100f, 14f))
                for (i in 0 until 10) drawCircle(beams[i % 4].copy(alpha = 0.5f + 0.5f * abs(wave(2f, i * 0.1f))), 1.6f * u, p(5f + i * 10f, 90f))
            }
            Backdrop.FESTIVAL -> {
                sky(Color(0xFF6EC6FF), Color(0xFFBDE8FF))
                drawRect(Color(0xFF7CB342), p(0f, 68f), s(100f, 32f))
                for (i in 0 until 6) {
                    val x = 4f + i * 16f
                    drawRoundRect(Color(0xFF1E88E5), p(x, 44f), s(12f, 26f), CornerRadius(2f * u))
                    drawRoundRect(Color(0xFF1565C0), p(x - 0.5f, 41f), s(13f, 4f), CornerRadius(2f * u))
                    drawRect(Color(0xFFFFFFFF), p(x + 3f, 52f), s(6f, 2.5f))
                    drawCircle(if (i % 3 == 0) Color(0xFFE53935) else Color(0xFF43A047), 0.9f * u, p(x + 9f, 58f))
                }
                for (i in 0 until 8) {
                    val x = i * 13f
                    drawPath(Path().apply { moveTo(x * u, 12f * v); lineTo((x + 6) * u, 18f * v); lineTo((x + 12) * u, 12f * v) },
                        listOf(Color(0xFFFF4081), Color(0xFFFFEB3B), Color(0xFF00E5FF))[i % 3])
                }
                drawLine(Color(0xFF5D4037), p(0f, 12f), p(100f, 12f), 0.4f * u)
                scatter.take(16).forEach { (x, _, z) -> drawCircle(Color(0xFF3E2723).copy(alpha = 0.7f), (1.4f + z) * u, p(x * 100, 82f + z * 14 + wave(3f, x) * 0.8f)) }
            }
            Backdrop.DISCO -> {
                sky(Color(0xFF1A0033), Color(0xFF4A148C))
                val tile = 12.5f
                for (r in 0 until 3) for (c in 0 until 8) {
                    val lit = (r + c + (t * 8).toInt()) % 3 == 0
                    drawRect(if (lit) listOf(Color(0xFFFF4081), Color(0xFF00E5FF), Color(0xFFFFEB3B))[(r + c) % 3] else Color(0xFF311B92),
                        p(c * tile, 64f + r * 12f), s(tile - 0.6f, 11.4f))
                }
                drawLine(Color.Gray, p(50f, 0f), p(50f, 8f), 0.5f * u)
                drawCircle(Color(0xFFCFD8DC), 9f * u, p(50f, 16f))
                for (i in 0..4) drawLine(Color(0xFF90A4AE), p(41f + i * 4.5f, 8f), p(41f + i * 4.5f, 24f), 0.3f * u)
                scatter.take(10).forEach { (x, y, z) ->
                    val tw = abs(wave(2f, z))
                    drawCircle(Color.White.copy(alpha = tw), (0.6f + tw) * u, p(x * 100, 10f + y * 50))
                }
            }
            Backdrop.GALAXY -> {
                sky(Color(0xFF020024), Color(0xFF1A0A4A), Color(0xFF3D1A78))
                drawCircle(Color(0x33B388FF), 30f * u, p(25f, 30f))
                drawCircle(Color(0x2240C4FF), 25f * u, p(80f, 70f))
                scatter.forEach { (x, y, z) -> drawCircle(Color.White.copy(alpha = 0.3f + 0.7f * abs(wave(1f, z))), (0.4f + z * 0.8f) * u, p(x * 100, y * 100)) }
                drawCircle(Color(0xFFFFB74D), 7f * u, p(78f, 22f))
                rotate(-20f, p(78f, 22f)) { drawOval(Color(0xFFFFE0B2), p(66f, 20f), s(24f, 4f), style = Stroke(1f * u)) }
            }
            Backdrop.NEON_CITY -> {
                sky(Color(0xFF0B0221), Color(0xFF2D0B4E))
                drawCircle(Color(0xFFFF2BD6).copy(alpha = 0.25f), 18f * u, p(70f, 30f))
                val heights = listOf(40f, 55f, 32f, 62f, 45f, 52f, 36f)
                heights.forEachIndexed { i, h ->
                    val x = i * 14.5f
                    drawRect(Color(0xFF140A2E), p(x, 100f - h), s(13.5f, h))
                    for (wy in 0 until (h / 6).toInt()) for (wx in 0..2) {
                        if ((i * 7 + wy * 3 + wx + (t * 4).toInt()) % 4 != 0) {
                            drawRect((if ((wx + wy) % 2 == 0) Color(0xFF00E5FF) else Color(0xFFFF2BD6)).copy(alpha = 0.7f),
                                p(x + 2f + wx * 4f, 100f - h + 3f + wy * 6f), s(2f, 2.5f))
                        }
                    }
                }
                drawLine(Color(0xFF00E5FF), p(0f, 99f), p(100f, 99f), 1.2f * u)
            }
            Backdrop.SUNSET -> {
                sky(Color(0xFFFF7E5F), Color(0xFFFEB47B), Color(0xFFFFD194))
                drawCircle(Color(0xFFFFF176), 14f * u, p(50f, 62f))
                drawRect(Brush.verticalGradient(listOf(Color(0xFF26A69A), Color(0xFF00695C)), startY = 62f * v, endY = 100f * v), p(0f, 62f), s(100f, 38f))
                for (i in 0 until 5) drawLine(Color.White.copy(alpha = 0.35f), p(10f + i * 18f + wave(1f, i * 0.2f) * 3f, 70f + i * 5f), p(22f + i * 18f + wave(1f, i * 0.2f) * 3f, 70f + i * 5f), 0.6f * u)
                drawLine(Color(0xFF4E342E), p(12f, 100f), p(18f, 55f), 2f * u, StrokeCap.Round)
                for (a in listOf(-150f, -110f, -70f, -30f, 10f)) {
                    val rad = (a + wave(0.5f) * 5f) * PI.toFloat() / 180f
                    drawLine(Color(0xFF2E7D32), p(18f, 55f), Offset(18f * u + cos(rad) * 14 * u, 55f * v + sin(rad) * 14 * v), 1.6f * u, StrokeCap.Round)
                }
            }
            Backdrop.UNDERWATER -> {
                sky(Color(0xFF4FC3F7), Color(0xFF0277BD), Color(0xFF01395E))
                for (i in 0 until 5) {
                    val x = 8f + i * 22f
                    val sway = wave(0.7f, i * 0.3f) * 4f
                    drawPath(Path().apply { moveTo(x * u, 100f * v); quadraticTo((x + sway) * u, 85f * v, (x + sway * 1.5f) * u, 70f * v) },
                        Color(0xFF2E7D32), style = Stroke(2f * u, cap = StrokeCap.Round))
                }
                scatter.take(14).forEach { (x, _, z) ->
                    val y = 100f - ((t * (0.5f + z) + z) % 1f) * 100f
                    drawCircle(Color.White.copy(alpha = 0.5f), (0.8f + z * 1.5f) * u, p(x * 100 + wave(2f, z) * 1.5f, y), style = Stroke(0.4f * u))
                }
            }
            Backdrop.SAKURA -> {
                sky(Color(0xFFFFF0F5), Color(0xFFFCE4EC))
                drawLine(Color(0xFF5D4037), p(0f, 25f), p(35f, 10f), 2.2f * u, StrokeCap.Round)
                listOf(8f to 18f, 20f to 12f, 30f to 9f, 15f to 26f).forEach { (x, y) -> drawCircle(Color(0xFFF8BBD0), 6f * u, p(x, y)) }
                scatter.take(20).forEach { (x, _, z) ->
                    val y = ((t * (0.4f + z * 0.4f) + z) % 1f) * 110f - 5f
                    rotate(t * 360f + z * 180f, p(x * 100 + wave(1f, z) * 4f, y)) {
                        drawOval(Color(0xFFF48FB1), p(x * 100 + wave(1f, z) * 4f - 1.2f, y - 0.7f), s(2.4f, 1.4f))
                    }
                }
            }
            Backdrop.WILD_WEST -> {
                sky(Color(0xFFFFCC80), Color(0xFFFFE0B2))
                drawCircle(Color(0xFFFF7043), 11f * u, p(75f, 30f))
                drawPath(Path().apply { moveTo(0f, 70f * v); quadraticTo(30f * u, 58f * v, 60f * u, 70f * v); quadraticTo(80f * u, 76f * v, 100f * u, 66f * v); lineTo(100f * u, 100f * v); lineTo(0f, 100f * v); close() }, Color(0xFFD7A86E))
                drawRect(Color(0xFFC1874F), p(0f, 82f), s(100f, 18f))
                fun cactus(x: Float, h: Float) {
                    drawRoundRect(Color(0xFF558B2F), p(x, 82f - h), s(4f, h), CornerRadius(2f * u))
                    drawRoundRect(Color(0xFF558B2F), p(x - 4f, 82f - h * 0.7f), s(3f, h * 0.35f), CornerRadius(1.5f * u))
                    drawRoundRect(Color(0xFF558B2F), p(x + 5f, 82f - h * 0.85f), s(3f, h * 0.3f), CornerRadius(1.5f * u))
                }
                cactus(12f, 22f); cactus(84f, 16f)
                val tx = ((t * 1.3f) % 1f) * 120f - 10f
                rotate(t * 720f, p(tx, 90f)) { drawCircle(Color(0xFF8D6E63), 3f * u, p(tx, 90f), style = Stroke(0.8f * u)) }
            }
            Backdrop.CAMPFIRE -> {
                sky(Color(0xFF0D1B2A), Color(0xFF1B263B))
                scatter.take(15).forEach { (x, y, z) -> drawCircle(Color.White.copy(alpha = 0.4f + 0.5f * abs(wave(1f, z))), 0.5f * u, p(x * 100, y * 40)) }
                for (i in 0 until 7) {
                    val x = i * 16f - 4f
                    drawPath(Path().apply { moveTo(x * u, 85f * v); lineTo((x + 8) * u, 48f * v); lineTo((x + 16) * u, 85f * v); close() }, Color(0xFF1B4332))
                }
                drawRect(Color(0xFF2D1E12), p(0f, 85f), s(100f, 15f))
                val flick = 1f + 0.15f * wave(5f)
                drawCircle(Color(0x55FF9800), 16f * flick * u, p(50f, 88f))
                drawPath(Path().apply { moveTo(44f * u, 92f * v); quadraticTo(46f * u, (80f - 4f * flick) * v, 50f * u, (76f - 4f * flick) * v); quadraticTo(54f * u, 82f * v, 56f * u, 92f * v); close() }, Color(0xFFFF6D00))
                drawPath(Path().apply { moveTo(47f * u, 92f * v); quadraticTo(49f * u, 84f * v, 50f * u, (81f - 2f * flick) * v); quadraticTo(52f * u, 86f * v, 53f * u, 92f * v); close() }, Color(0xFFFFD54F))
            }
            Backdrop.HEAVEN -> {
                sky(Color(0xFF81D4FA), Color(0xFFE1F5FE))
                for (i in 0 until 6) {
                    rotate(-30f + i * 12f + wave(0.3f) * 3f, p(50f, -10f)) { drawRect(Color.White.copy(alpha = 0.18f), p(48f, -10f), s(4f, 120f)) }
                }
                fun cloud(x: Float, y: Float, k: Float) {
                    listOf(0f to 0f, 6f to -3f, 12f to 0f, 6f to 2f).forEach { (dx, dy) -> drawCircle(Color.White, 6f * k * u, p(x + dx * k, y + dy * k)) }
                }
                cloud(8f + wave(0.3f) * 2f, 80f, 1.4f); cloud(62f - wave(0.3f) * 2f, 86f, 1.6f); cloud(70f, 20f, 0.9f)
            }
            Backdrop.HELLFIRE -> {
                sky(Color(0xFF1A0000), Color(0xFF4A0000), Color(0xFFB71C1C))
                for (i in 0 until 12) {
                    val x = i * 9f
                    val h = 18f + 8f * abs(wave(2f, i * 0.17f))
                    drawPath(Path().apply { moveTo((x - 2) * u, 100f * v); quadraticTo((x + 2) * u, (100f - h) * v, (x + 4) * u, (100f - h - 6f) * v); quadraticTo((x + 8) * u, (100f - h / 2) * v, (x + 10) * u, 100f * v); close() },
                        if (i % 2 == 0) Color(0xFFFF6F00) else Color(0xFFFFC400))
                }
                scatter.take(10).forEach { (x, _, z) ->
                    val y = 100f - ((t * (0.6f + z) + z) % 1f) * 80f
                    drawCircle(Color(0xFFFFAB40).copy(alpha = 0.8f), 0.6f * u, p(x * 100, y))
                }
            }
            Backdrop.MATRIX -> {
                drawRect(Color.Black)
                for (col in 0 until 14) {
                    val z = scatter[col].third
                    val head = ((t * (0.8f + z) + z) % 1f) * 130f - 15f
                    for (k in 0 until 9) {
                        val y = head - k * 4f
                        if (y in 0f..100f) drawRect(Color(0xFF00FF41).copy(alpha = (1f - k / 9f) * 0.9f), p(2f + col * 7f, y), s(2.2f, 2.8f))
                    }
                }
            }
            Backdrop.CIRCUIT -> {
                drawRect(Color(0xFF0B5D1E))
                val gold = Color(0xFFFFD54F)
                listOf(
                    listOf(0f to 20f, 30f to 20f, 40f to 30f, 100f to 30f),
                    listOf(10f to 100f, 10f to 60f, 25f to 45f, 60f to 45f),
                    listOf(100f to 70f, 70f to 70f, 60f to 80f, 60f to 100f),
                    listOf(50f to 0f, 50f to 12f, 80f to 12f),
                ).forEachIndexed { i, pts ->
                    val path = Path().apply { pts.forEachIndexed { j, (x, y) -> if (j == 0) moveTo(x * u, y * v) else lineTo(x * u, y * v) } }
                    drawPath(path, gold.copy(alpha = 0.7f), style = Stroke(1.1f * u))
                    pts.forEach { (x, y) -> drawCircle(gold, 1.4f * u, p(x, y)) }
                    val k = ((t + i * 0.25f) % 1f) * (pts.size - 1)
                    val a = pts[k.toInt()]; val b = pts[(k.toInt() + 1).coerceAtMost(pts.size - 1)]; val f = k - k.toInt()
                    drawCircle(Color(0xFF69F0AE), 1.3f * u, p(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f))
                }
                drawRect(Color(0xFF212121), p(70f, 50f), s(16f, 12f))
            }
            Backdrop.CANDY -> {
                drawRect(Color(0xFFFFF1F8))
                for (i in -10 until 20) {
                    rotate(35f, p(50f, 50f)) { drawRect(listOf(Color(0x33FF80AB), Color(0x3380D8FF), Color(0x33FFF59D))[(i + 30) % 3], p(i * 8f, -40f), s(4f, 180f)) }
                }
                fun lolly(x: Float, y: Float, c: Color) {
                    drawLine(Color.White, p(x, y), p(x, y + 22f), 1.2f * u)
                    drawCircle(c, 6f * u, p(x, y))
                    rotate(t * 360f, p(x, y)) { drawArc(Color.White, 0f, 270f, false, p(x - 3.5f, y - 3.5f), s(7f, 7f), style = Stroke(1f * u)) }
                }
                lolly(12f, 70f, Color(0xFFFF4081)); lolly(88f, 66f, Color(0xFF7C4DFF)); lolly(80f, 18f, Color(0xFF00BFA5))
            }
        }
    }
}

/** Pokédex frame colours to pick from (the full picker is in the creator). */
val DexFrameColors = listOf(
    Color(0xFFD9363E), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFFB300), Color(0xFF8E24AA),
    Color(0xFFFF4FD8), Color(0xFF00ACC1), Color(0xFF212121), Color(0xFFF5F5F5), Color(0xFF6D4C41),
)
