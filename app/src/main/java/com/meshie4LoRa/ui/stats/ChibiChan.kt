package com.meshie4LoRa.ui.stats

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import com.meshie4LoRa.ui.stats.MoodAnalyzer.Mood
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF2B2140)
private val Blush = Color(0xFFFF8FAB)
private val Gold = Color(0xFFFFC93C)
private val Pink = Color(0xFFFF9EB5)

private enum class Eyes { NORMAL, HAPPY, HEARTS, STARS, WINK, SPIRALS, ANGRY, SAD }
private enum class Mouth { SMILE, GRIN, FROWN, O, SMIRK, WAVY }

private fun faceFor(mood: Mood?): Pair<Eyes, Mouth> = when (mood) {
    Mood.HYPE -> Eyes.STARS to Mouth.GRIN
    Mood.LOVE -> Eyes.HEARTS to Mouth.SMILE
    Mood.FLIRTY -> Eyes.WINK to Mouth.SMIRK
    Mood.CHAOS -> Eyes.SPIRALS to Mouth.WAVY
    Mood.SALTY -> Eyes.ANGRY to Mouth.FROWN
    Mood.SAD -> Eyes.SAD to Mouth.FROWN
    Mood.CURIOUS -> Eyes.NORMAL to Mouth.O
    Mood.CONFUSED -> Eyes.SPIRALS to Mouth.O
    Mood.CHILL -> Eyes.HAPPY to Mouth.SMILE
    Mood.PARTY -> Eyes.HAPPY to Mouth.GRIN
    null -> Eyes.NORMAL to Mouth.SMILE
}

/** Default hair colours for personas without a custom look (index 0 is Meshtastic green). */
val personaHair = listOf(Color(0xFF5FD39B), Color(0xFFFF8FC8), Color(0xFF8FB8FF), Color(0xFFFFC857), Color(0xFFB98FFF), Color(0xFFFF7B6B))

/** Vector chibi built from [avatar]'s layers; the expression follows [mood]. Coordinates are in a 100×100 box. */
@Composable
fun ChibiChan(mood: Mood?, modifier: Modifier = Modifier, avatar: Avatar = Avatar.DEFAULT, animate: Boolean = true) {
    val t = rememberInfiniteTransition(label = "chan")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "bob")
    val blink by t.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(keyframes { durationMillis = 4200; 1f at 3900; 0.1f at 4000; 1f at 4150 }),
        label = "blink",
    )
    val (eyes, mouth) = faceFor(mood)
    Canvas(modifier) {
        val u = size.minDimension / 100f
        val dy = if (animate) sin(bob * 2 * PI).toFloat() * 1.6f else 0f
        translate(left = (size.width - 100 * u) / 2, top = (size.height - 100 * u) / 2 + dy * u) {
            with(ChibiPainter(u, avatar)) { draw(eyes, mouth, if (eyes == Eyes.NORMAL && animate) blink else 1f) }
        }
    }
}

private fun shade(c: Color, k: Float = 0.75f) = Color(c.red * k, c.green * k, c.blue * k, c.alpha)

private class ChibiPainter(val u: Float, val a: Avatar) {
    val hair = a.hairColor
    val hairShade = shade(a.hairColor)
    val skin = a.skin
    val bodyW = a.body.width
    val faceW = when (a.body) { BodyType.CHUBBY -> 50f; BodyType.ROUND -> 54f; else -> 46f }
    val stroke get() = Stroke(1.5f * u, cap = StrokeCap.Round)

    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun s(w: Float, h: Float) = Size(w * u, h * u)

    /** Meshie's chest emblem: a pink heart with two signal arcs, centred on the heart at ([cx], [cy]). */
    fun DrawScope.emblem(cx: Float, cy: Float) {
        val heart = Path().apply {
            moveTo(cx * u, (cy + 4f) * u)
            cubicTo((cx - 4.5f) * u, (cy + 0.8f) * u, (cx - 5f) * u, (cy - 1.5f) * u, (cx - 5f) * u, (cy - 2.2f) * u)
            cubicTo((cx - 5f) * u, (cy - 5f) * u, (cx - 1f) * u, (cy - 5.5f) * u, cx * u, (cy - 2.6f) * u)
            cubicTo((cx + 1f) * u, (cy - 5.5f) * u, (cx + 5f) * u, (cy - 5f) * u, (cx + 5f) * u, (cy - 2.2f) * u)
            cubicTo((cx + 5f) * u, (cy - 1.5f) * u, (cx + 4.5f) * u, (cy + 0.8f) * u, cx * u, (cy + 4f) * u)
            close()
        }
        drawPath(heart, Color(0xFFFF8FC8))
        drawArc(a.accent, -50f, 100f, false, p(cx + 3f, cy - 4f), s(7f, 7f), style = Stroke(1.3f * u, cap = StrokeCap.Round))
        drawArc(a.accent, -50f, 100f, false, p(cx + 2f, cy - 7f), s(13f, 13f), style = Stroke(1.3f * u, cap = StrokeCap.Round))
    }
    fun poly(vararg xy: Float, close: Boolean = true) = Path().apply {
        moveTo(xy[0] * u, xy[1] * u)
        for (i in 2 until xy.size step 2) lineTo(xy[i] * u, xy[i + 1] * u)
        if (close) close()
    }

    fun DrawScope.draw(eyes: Eyes, mouth: Mouth, blink: Float) {
        drawBackExtras()
        drawEarsBack()
        drawHairBack()
        drawBody()
        drawEarsSide()
        drawOval(skin, p(50f - faceW / 2, 24f), s(faceW, 46f))
        drawHairFront()
        drawEarsFront()
        drawCheeks()
        drawEyes(eyes, blink)
        drawMouthAndFace(mouth)
        drawFrontExtras()
        drawHat()
    }

    // ---- back layer: wings, tail, antennae ----
    fun DrawScope.drawBackExtras() {
        if (Extra.ANGEL_WINGS in a.extras) for (side in listOf(-1f, 1f)) {
            for (i in 0..2) {
                val x = 50f + side * (bodyW / 2 + 4f + i * 5f)
                rotate(side * (25f + i * 12f), p(50f + side * bodyW / 2, 78f)) {
                    drawOval(Color.White, p(x - 5f, 66f - i * 2f), s(10f, 22f))
                    drawOval(Color(0xFFE0E0E0), p(x - 5f, 66f - i * 2f), s(10f, 22f), style = Stroke(0.6f * u))
                }
            }
        }
        if (Extra.BAT_WINGS in a.extras) for (side in listOf(-1f, 1f)) {
            val x0 = 50f + side * bodyW / 2
            val wing = poly(x0, 74f, x0 + side * 22f, 56f, x0 + side * 20f, 70f, x0 + side * 26f, 76f, x0 + side * 16f, 80f, x0 + side * 14f, 88f, x0, 84f)
            drawPath(wing, Color(0xFF37233F))
        }
        if (Extra.CAT_TAIL in a.extras) {
            val tail = Path().apply {
                moveTo((50f + bodyW / 2 - 4f) * u, 94f * u)
                cubicTo(95f * u, 98f * u, 98f * u, 70f * u, 88f * u, 62f * u)
            }
            drawPath(tail, hairShade, style = Stroke(4.5f * u, cap = StrokeCap.Round))
        }
        if (a.ears == EarStyle.ANTENNAE) for (side in listOf(-1f, 1f)) {
            drawLine(Ink, p(50f + side * 8f, 22f), p(50f + side * 16f, 4f), 1.4f * u, StrokeCap.Round)
            drawCircle(a.accent, 3f * u, p(50f + side * 16f, 4f))
        }
    }

    fun DrawScope.drawEarsBack() {
        when (a.ears) {
            EarStyle.BUNNY -> for (side in listOf(-1f, 1f)) rotate(side * 12f, p(50f + side * 12f, 26f)) {
                drawOval(hair, p(50f + side * 12f - 5f, 0f), s(10f, 28f))
                drawOval(Pink, p(50f + side * 12f - 2.5f, 4f), s(5f, 19f))
            }
            EarStyle.BEAR -> for (side in listOf(-1f, 1f)) {
                drawCircle(hair, 7.5f * u, p(50f + side * 20f, 22f))
                drawCircle(Pink, 4f * u, p(50f + side * 20f, 22f))
            }
            else -> {}
        }
    }

    // ---- hair ----
    fun DrawScope.drawHairBack() {
        when (a.hair) {
            HairStyle.TWIN_TAILS -> {
                rotate(18f, p(20f, 58f)) { drawOval(hairShade, p(10f, 38f), s(18f, 40f)) }
                rotate(-18f, p(80f, 58f)) { drawOval(hairShade, p(72f, 38f), s(18f, 40f)) }
                drawCircle(hair, 7f * u, p(26f, 30f)); drawCircle(hair, 7f * u, p(74f, 30f))
                drawCircle(hair, 28f * u, p(50f, 42f))
            }
            HairStyle.LONG -> {
                drawRoundRect(hairShade, p(22f, 34f), s(56f, 46f), CornerRadius(10f * u))
                drawCircle(hair, 28f * u, p(50f, 42f))
            }
            HairStyle.PONYTAIL -> {
                rotate(-25f, p(78f, 30f)) { drawOval(hairShade, p(72f, 26f), s(16f, 40f)) }
                drawCircle(a.accent, 3f * u, p(77f, 28f))
                drawCircle(hair, 28f * u, p(50f, 42f))
            }
            HairStyle.BUNS -> {
                drawCircle(hairShade, 10f * u, p(27f, 20f)); drawCircle(hairShade, 10f * u, p(73f, 20f))
                drawCircle(hair, 28f * u, p(50f, 42f))
            }
            HairStyle.BOB -> {
                drawRoundRect(hairShade, p(22f, 36f), s(56f, 28f), CornerRadius(8f * u))
                drawCircle(hair, 28f * u, p(50f, 42f))
            }
            HairStyle.SHORT, HairStyle.SPIKY -> drawOval(hair, p(50f - faceW / 2 - 2f, 18f), s(faceW + 4f, 36f))
            HairStyle.AFRO -> {
                for (i in 0 until 12) {
                    val ang = i * PI / 6
                    drawCircle(hair, 10f * u, p(50f + 30f * cos(ang).toFloat(), 38f + 26f * sin(ang).toFloat()))
                }
                drawCircle(hair, 32f * u, p(50f, 38f))
            }
            HairStyle.MOHAWK, HairStyle.BALD -> {}
        }
    }

    fun DrawScope.drawHairFront() {
        when (a.hair) {
            HairStyle.TWIN_TAILS, HairStyle.LONG, HairStyle.PONYTAIL, HairStyle.BUNS -> drawPath(
                Path().apply {
                    moveTo(25f * u, 44f * u)
                    quadraticTo(26f * u, 18f * u, 50f * u, 17f * u)
                    quadraticTo(74f * u, 18f * u, 75f * u, 44f * u)
                    lineTo(68f * u, 34f * u); lineTo(62f * u, 40f * u); lineTo(56f * u, 31f * u)
                    lineTo(50f * u, 38f * u); lineTo(44f * u, 31f * u); lineTo(38f * u, 40f * u); lineTo(32f * u, 34f * u)
                    close()
                },
                hair,
            )
            HairStyle.BOB -> drawPath(
                Path().apply {
                    moveTo(24f * u, 48f * u)
                    quadraticTo(26f * u, 17f * u, 50f * u, 17f * u)
                    quadraticTo(74f * u, 17f * u, 76f * u, 48f * u)
                    lineTo(70f * u, 48f * u); lineTo(68f * u, 36f * u); lineTo(32f * u, 36f * u); lineTo(30f * u, 48f * u)
                    close()
                },
                hair,
            )
            HairStyle.SHORT -> drawPath(
                Path().apply {
                    moveTo((50f - faceW / 2) * u, 42f * u)
                    quadraticTo(28f * u, 20f * u, 50f * u, 19f * u)
                    quadraticTo(72f * u, 20f * u, (50f + faceW / 2) * u, 42f * u)
                    lineTo(70f * u, 32f * u); quadraticTo(52f * u, 36f * u, 38f * u, 30f * u); lineTo(30f * u, 36f * u)
                    close()
                },
                hair,
            )
            HairStyle.SPIKY -> drawPath(
                poly(24f, 42f, 20f, 26f, 30f, 28f, 30f, 12f, 40f, 20f, 46f, 6f, 52f, 18f, 60f, 6f, 63f, 20f, 74f, 12f, 71f, 27f, 81f, 26f, 76f, 42f,
                    68f, 32f, 60f, 37f, 52f, 30f, 44f, 37f, 36f, 31f, 30f, 38f),
                hair,
            )
            HairStyle.MOHAWK -> drawPath(poly(44f, 30f, 42f, 18f, 46f, 4f, 50f, 14f, 54f, 2f, 58f, 16f, 56f, 30f), hair)
            HairStyle.AFRO -> for (i in 0..6) drawCircle(hair, 5.5f * u, p(30f + i * 6.7f, 27f + if (i % 2 == 0) 0f else 2f))
            HairStyle.BALD -> drawArc(Color.White.copy(alpha = 0.5f), 200f, 50f, false, p(32f, 28f), s(24f, 20f), style = Stroke(2f * u, cap = StrokeCap.Round))
        }
    }

    // ---- ears that sit on the head ----
    fun DrawScope.drawEarsSide() {
        when (a.ears) {
            EarStyle.ELF -> for (side in listOf(-1f, 1f)) {
                val x = 50f + side * faceW / 2
                drawPath(poly(x - side * 3f, 42f, x + side * 12f, 32f, x - side * 1f, 54f), skin)
            }
            else -> {}
        }
    }

    fun DrawScope.drawEarsFront() {
        when (a.ears) {
            EarStyle.CAT -> for (side in listOf(-1f, 1f)) {
                drawPath(poly(50f + side * 26f, 30f, 50f + side * 20f, 8f, 50f + side * 8f, 22f), hair)
                drawPath(poly(50f + side * 22f, 26f, 50f + side * 19f, 13f, 50f + side * 12f, 22f), Pink)
            }
            EarStyle.BOLTS -> for (side in listOf(-1f, 1f)) {
                val x = 50f + side * (faceW / 2 + 1f)
                drawRoundRect(Color(0xFF607D8B), p(x - 3f, 42f), s(6f, 12f), CornerRadius(2f * u))
                drawCircle(Color(0xFFCFD8DC), 1.6f * u, p(x, 48f))
            }
            else -> {}
        }
    }

    // ---- body / outfit ----
    fun DrawScope.drawBody() {
        val x = 50f - bodyW / 2
        val top = if (a.body == BodyType.ROUND) 67f else 70f
        val h = 100f - top
        val r = CornerRadius((if (a.body == BodyType.ROUND) 16f else 12f) * u)
        val oc = a.outfit.let { if (it == OutfitStyle.TANK) skin else a.outfitColor }
        drawRoundRect(oc, p(x, top), s(bodyW, h), r)
        when (a.outfit) {
            OutfitStyle.HOODIE -> {
                drawRoundRect(a.accent, p(x, top), s(bodyW, 4f), CornerRadius(3f * u))
                emblem(48f, 84f)
            }
            OutfitStyle.TEE -> {
                drawArc(skin, 0f, 180f, true, p(43f, top - 4f), s(14f, 10f))
                drawCircle(a.accent, 4f * u, p(50f, top + 16f))
            }
            OutfitStyle.TANK -> {
                val w = bodyW * 0.62f
                drawRoundRect(a.outfitColor, p(50f - w / 2, top + 5f), s(w, h - 5f), CornerRadius(6f * u))
                drawLine(a.outfitColor, p(50f - w / 2 + 3f, top + 6f), p(50f - w / 2 + 4f, top), 2.5f * u)
                drawLine(a.outfitColor, p(50f + w / 2 - 3f, top + 6f), p(50f + w / 2 - 4f, top), 2.5f * u)
                drawPath(poly(44f, top + 12f, 50f, top + 20f, 56f, top + 12f, close = false), a.accent, style = Stroke(2f * u, cap = StrokeCap.Round))
            }
            OutfitStyle.CHEER -> {
                drawPath(poly(x + 4f, top, 50f, top + 14f, x + bodyW - 4f, top, close = false), a.accent, style = Stroke(2.5f * u))
                emblem(48f, top + 20f)
                for (i in 0..5) {
                    val px = x + 3f + i * (bodyW - 6f) / 6
                    drawPath(poly(px, 100f, px + (bodyW - 6f) / 12, 93f, px + (bodyW - 6f) / 6, 100f), a.accent)
                }
            }
            OutfitStyle.SUIT -> {
                drawPath(poly(43f, top, 50f, top + 16f, 57f, top), Color.White)
                drawPath(poly(48.5f, top + 3f, 51.5f, top + 3f, 52.5f, top + 16f, 50f, top + 19f, 47.5f, top + 16f), a.accent)
                drawCircle(Color(0xFFBDBDBD), 1f * u, p(50f, top + 23f))
            }
            OutfitStyle.VEST -> {
                drawRoundRect(Color(0xFFF5E6C8), p(x, top), s(bodyW, h), r)
                drawPath(poly(x, top + 4f, 46f, top, 46f, 100f, x, 100f), a.outfitColor)
                drawPath(poly(x + bodyW, top + 4f, 54f, top, 54f, 100f, x + bodyW, 100f), a.outfitColor)
                star(this, 40f, top + 14f, 3.4f, Gold)
            }
            OutfitStyle.OVERALLS -> {
                drawRoundRect(a.accent, p(x, top), s(bodyW, h), r)
                drawRoundRect(a.outfitColor, p(40f, top + 8f), s(20f, h - 8f), CornerRadius(3f * u))
                drawRoundRect(a.outfitColor, p(x, top + 20f), s(bodyW, h - 20f), CornerRadius(6f * u))
                drawLine(a.outfitColor, p(41f, top + 9f), p(38f, top), 2.5f * u); drawLine(a.outfitColor, p(59f, top + 9f), p(62f, top), 2.5f * u)
                drawCircle(Gold, 1.3f * u, p(42.5f, top + 11f)); drawCircle(Gold, 1.3f * u, p(57.5f, top + 11f))
            }
            OutfitStyle.ROBOT -> {
                drawRoundRect(shade(a.outfitColor, 0.6f), p(46f, top - 3f), s(8f, 5f))
                drawRoundRect(shade(a.outfitColor, 0.85f), p(38f, top + 7f), s(24f, 14f), CornerRadius(2f * u))
                listOf(a.accent, Color(0xFFFF5252), Color(0xFFFFEB3B)).forEachIndexed { i, c -> drawCircle(c, 2f * u, p(43f + i * 7f, top + 14f)) }
                drawLine(shade(a.outfitColor, 0.6f), p(x + 3f, top + 25f), p(x + bodyW - 3f, top + 25f), 1f * u)
            }
            OutfitStyle.SPACESUIT -> {
                drawRoundRect(Color(0xFFB0BEC5), p(42f, top - 2f), s(16f, 4f), CornerRadius(2f * u))
                drawRoundRect(Color(0xFF455A64), p(40f, top + 8f), s(20f, 12f), CornerRadius(2f * u))
                listOf(Color(0xFFFF5252), Color(0xFF69F0AE), Color(0xFF40C4FF)).forEachIndexed { i, c -> drawCircle(c, 1.6f * u, p(44f + i * 6f, top + 14f)) }
                drawRect(a.accent, p(x, top + 23f), s(bodyW, 3f))
            }
        }
    }

    fun DrawScope.drawCheeks() {
        val cx = faceW / 2 - 8f
        when (a.face) {
            FaceStyle.BLUSH, FaceStyle.WHISKERS -> {
                drawOval(Blush.copy(alpha = 0.55f), p(50f - cx - 6f, 54f), s(8f, 4f))
                drawOval(Blush.copy(alpha = 0.55f), p(50f + cx - 2f, 54f), s(8f, 4f))
            }
            FaceStyle.FRECKLES -> for (side in listOf(-1f, 1f)) for ((dx, dy) in listOf(0f to 0f, 3f to 1.5f, -2f to 2f, 1.5f to -1.5f)) {
                drawCircle(Color(0xFFB5651D).copy(alpha = 0.7f), 0.7f * u, p(50f + side * (cx + dx), 55f + dy))
            }
            else -> {}
        }
        if (a.face == FaceStyle.WHISKERS) for (side in listOf(-1f, 1f)) for (dy in listOf(-1.5f, 1.5f)) {
            drawLine(Ink, p(50f + side * (cx - 1f), 56f + dy), p(50f + side * (cx + 9f), 55f + dy * 2f), 0.6f * u, StrokeCap.Round)
        }
    }

    // ---- eyes ----
    fun DrawScope.drawEyes(eyes: Eyes, blink: Float) {
        when (a.eyes) {
            EyeStyle.VISOR -> {
                drawRoundRect(Color(0xFF101418), p(29f, 42f), s(42f, 13f), CornerRadius(6f * u))
                eye(41f, 48.5f, eyes, true, blink, a.eyeColor, a.eyeColor, EyeStyle.DOTS)
                eye(59f, 48.5f, eyes, false, blink, a.eyeColor, a.eyeColor, EyeStyle.DOTS)
            }
            EyeStyle.SHADES -> {
                if (eyes == Eyes.HEARTS) {
                    heart(this, 41f, 49f, 7f, Color(0xFFFF4F7B)); heart(this, 59f, 49f, 7f, Color(0xFFFF4F7B))
                } else {
                    for (cx in listOf(41f, 59f)) drawRoundRect(Color(0xFF111111), p(cx - 7f, 44f), s(14f, 9f), CornerRadius(4f * u))
                    for (cx in listOf(41f, 59f)) drawLine(Color.White.copy(alpha = 0.6f), p(cx - 4f, 46f), p(cx - 1f, 46f), 1.2f * u, StrokeCap.Round)
                    if (eyes == Eyes.STARS) { star(this, 45f, 45f, 2.2f, Color.White); star(this, 63f, 45f, 2.2f, Color.White) }
                }
                drawLine(Color(0xFF111111), p(34f, 46f), p(66f, 46f), 1.2f * u)
                brows(eyes)
            }
            EyeStyle.CYCLOPS -> scale(1.7f, pivot = p(50f, 47f)) { eye(50f, 47f, eyes, false, blink, a.eyeColor, Ink, EyeStyle.ANIME) }
            else -> {
                eye(41f, 49f, eyes, true, blink, a.eyeColor, Ink, a.eyes)
                eye(59f, 49f, eyes, false, blink, a.eyeColor, Ink, a.eyes)
            }
        }
        if (eyes == Eyes.SAD && a.eyes != EyeStyle.VISOR) drawOval(Color(0xFF7EC8FF), p(36.5f, 54f), s(2.4f, 4f))
    }

    fun DrawScope.brows(eyes: Eyes) {
        for (left in listOf(true, false)) {
            val cx = if (left) 41f else 59f
            val cy = 49f
            if (eyes == Eyes.ANGRY) {
                val (a1, b1) = if (left) (cx - 5f to cy - 8f) to (cx + 3f to cy - 5.5f) else (cx - 3f to cy - 5.5f) to (cx + 5f to cy - 8f)
                drawLine(Ink, p(a1.first, a1.second), p(b1.first, b1.second), 1.8f * u, StrokeCap.Round)
            }
            if (eyes == Eyes.SAD) {
                val (a1, b1) = if (left) (cx - 4f to cy - 6f) to (cx + 3f to cy - 8f) else (cx - 3f to cy - 8f) to (cx + 4f to cy - 6f)
                drawLine(Ink, p(a1.first, a1.second), p(b1.first, b1.second), 1.4f * u, StrokeCap.Round)
            }
        }
    }

    fun DrawScope.eye(cx: Float, cy: Float, eyes: Eyes, left: Boolean, blink: Float, iris: Color, ink: Color, style: EyeStyle) {
        val st = Stroke(1.6f * u, cap = StrokeCap.Round)
        when (eyes) {
            Eyes.NORMAL, Eyes.ANGRY, Eyes.SAD -> {
                openEye(cx, cy, blink, iris, ink, style)
                if (style != EyeStyle.DOTS || ink == Ink) {
                    val cy2 = if (style == EyeStyle.ALIEN) cy - 2f else cy
                    if (eyes == Eyes.ANGRY) {
                        val (a1, b1) = if (left) (cx - 5f to cy2 - 8f) to (cx + 3f to cy2 - 5.5f) else (cx - 3f to cy2 - 5.5f) to (cx + 5f to cy2 - 8f)
                        drawLine(ink, p(a1.first, a1.second), p(b1.first, b1.second), 1.8f * u, StrokeCap.Round)
                    }
                    if (eyes == Eyes.SAD) {
                        val (a1, b1) = if (left) (cx - 4f to cy2 - 6f) to (cx + 3f to cy2 - 8f) else (cx - 3f to cy2 - 8f) to (cx + 4f to cy2 - 6f)
                        drawLine(ink, p(a1.first, a1.second), p(b1.first, b1.second), 1.4f * u, StrokeCap.Round)
                    }
                }
            }
            Eyes.HAPPY -> drawArc(ink, 200f, 140f, false, p(cx - 4f, cy - 2f), s(8f, 6f), style = st)
            Eyes.WINK -> if (left) drawArc(ink, 200f, 140f, false, p(cx - 4f, cy - 2f), s(8f, 6f), style = st)
            else openEye(cx, cy, 1f, iris, ink, style)
            Eyes.HEARTS -> heart(this, cx, cy, 6f, if (ink == Ink) Color(0xFFFF4F7B) else ink)
            Eyes.STARS -> star(this, cx, cy, 4.6f, if (ink == Ink) Gold else ink)
            Eyes.SPIRALS -> {
                val sp = Path()
                for (i in 0..40) {
                    val ang = i * 0.45
                    val r = 0.12f * i
                    val x = cx + r * cos(ang).toFloat()
                    val y = cy + r * sin(ang).toFloat()
                    if (i == 0) sp.moveTo(x * u, y * u) else sp.lineTo(x * u, y * u)
                }
                drawPath(sp, ink, style = Stroke(1.1f * u))
            }
        }
    }

    fun DrawScope.openEye(cx: Float, cy: Float, blink: Float, iris: Color, ink: Color, style: EyeStyle) {
        when (style) {
            EyeStyle.ALIEN -> {
                val h = 11f * blink
                rotate(if (cx < 50f) 25f else -25f, p(cx, cy)) { drawOval(Color(0xFF0D0D0D), p(cx - 5f, cy - h / 2), s(10f, h)) }
                if (blink > 0.5f) drawCircle(Color.White.copy(alpha = 0.8f), 1.4f * u, p(cx - 1.5f, cy - 2f))
            }
            EyeStyle.DOTS -> drawOval(ink, p(cx - 2f, cy - 2.5f * blink), s(4f, 5f * blink))
            else -> {
                val h = 9f * blink
                drawOval(Ink, p(cx - 3.6f, cy - h / 2), s(7.2f, h))
                if (blink > 0.5f) {
                    drawOval(iris, p(cx - 2.8f, cy - h / 2 + 1.5f), s(5.6f, h - 2.5f))
                    if (style == EyeStyle.CAT) drawOval(Ink, p(cx - 0.8f, cy - h / 2 + 1.8f), s(1.6f, h - 3.2f))
                    drawCircle(Color.White, 1.3f * u, p(cx - 1.2f, cy - 2f))
                }
                if (style == EyeStyle.SLEEPY) {
                    drawRect(skin, p(cx - 4.2f, cy - 5f), s(8.4f, 5f))
                    drawLine(Ink, p(cx - 4f, cy), p(cx + 4f, cy), 1.4f * u, StrokeCap.Round)
                }
            }
        }
    }

    // ---- mouth and facial hair ----
    fun DrawScope.drawMouthAndFace(mouth: Mouth) {
        if (a.face == FaceStyle.BEARD) {
            val beard = Path().apply {
                moveTo((50f - faceW / 2 + 1f) * u, 46f * u)
                quadraticTo((50f - faceW / 2 + 2f) * u, 72f * u, 50f * u, 74f * u)
                quadraticTo((50f + faceW / 2 - 2f) * u, 72f * u, (50f + faceW / 2 - 1f) * u, 46f * u)
                lineTo((50f + faceW / 2 - 6f) * u, 54f * u)
                quadraticTo(50f * u, 50f * u, (50f - faceW / 2 + 6f) * u, 54f * u)
                close()
            }
            drawPath(beard, hair)
            drawOval(lerp(skin, hair, 0.2f), p(44f, 54.5f), s(12f, 8f))
        }
        val ink = if (a.face == FaceStyle.GRILL) a.eyeColor else Ink
        if (a.face == FaceStyle.GRILL) {
            drawRoundRect(Color(0xFF263238), p(42f, 54f), s(16f, 8f), CornerRadius(2f * u))
            for (i in 1..3) drawLine(Color(0xFF455A64), p(42f + i * 4f, 54.5f), p(42f + i * 4f, 61.5f), 0.6f * u)
        }
        val st = stroke
        when (mouth) {
            Mouth.SMILE -> drawArc(ink, 20f, 140f, false, p(46f, 55f), s(8f, 5f), style = st)
            Mouth.GRIN -> {
                drawPath(Path().apply { moveTo(44.5f * u, 57f * u); lineTo(55.5f * u, 57f * u); quadraticTo(50f * u, 65f * u, 44.5f * u, 57f * u) }, ink)
                if (a.face != FaceStyle.GRILL) drawOval(Color(0xFFFF7B93), p(47.5f, 59.5f), s(5f, 2.6f))
            }
            Mouth.FROWN -> drawArc(ink, 200f, 140f, false, p(46f, 58f), s(8f, 5f), style = st)
            Mouth.O -> drawOval(ink, p(48.3f, 57f), s(3.4f, 4f))
            Mouth.SMIRK -> drawArc(ink, 0f, 100f, false, p(48f, 55f), s(8f, 4.5f), style = st)
            Mouth.WAVY -> drawPath(
                Path().apply { moveTo(44f * u, 58f * u); for (i in 1..6) lineTo((44f + i * 2f) * u, (if (i % 2 == 0) 58f else 60f) * u) },
                ink, style = st,
            )
        }
        when (a.face) {
            FaceStyle.MUSTACHE -> for (side in listOf(-1f, 1f)) {
                rotate(side * -12f, p(50f, 54f)) { drawOval(hair, p(if (side < 0) 42f else 50f, 52f), s(8f, 3.6f)) }
            }
            FaceStyle.FANGS -> {
                drawPath(poly(46.5f, 57.5f, 48.3f, 57.5f, 47.4f, 60.5f), Color.White)
                drawPath(poly(51.7f, 57.5f, 53.5f, 57.5f, 52.6f, 60.5f), Color.White)
            }
            else -> {}
        }
    }

    // ---- accessories on top of the body ----
    fun DrawScope.drawFrontExtras() {
        val top = if (a.body == BodyType.ROUND) 67f else 70f
        if (Extra.SCARF in a.extras) drawPath(poly(36f, top - 1f, 64f, top - 1f, 50f, top + 11f), a.accent)
        if (Extra.GOLD_CHAIN in a.extras) {
            for (i in 0..10) {
                val ang = PI * i / 10
                drawCircle(Gold, 1.1f * u, p(50f - 9f * cos(ang).toFloat(), top + 1f + 9f * sin(ang).toFloat()))
            }
            drawCircle(Gold, 2.6f * u, p(50f, top + 12f))
        }
        if (Extra.BOWTIE in a.extras) {
            drawPath(poly(50f, top + 1.5f, 44f, top - 2f, 44f, top + 5f), a.accent)
            drawPath(poly(50f, top + 1.5f, 56f, top - 2f, 56f, top + 5f), a.accent)
            drawCircle(shade(a.accent), 1.5f * u, p(50f, top + 1.5f))
        }
        if (Extra.POMPOMS in a.extras) for (side in listOf(-1f, 1f)) {
            val cx = 50f + side * (bodyW / 2 + 2f)
            for (i in 0 until 8) {
                val ang = i * PI / 4
                drawCircle(if (i % 2 == 0) a.accent else a.outfitColor, 3.4f * u, p(cx + 4f * cos(ang).toFloat(), 86f + 4f * sin(ang).toFloat()))
            }
            drawCircle(a.accent, 3.4f * u, p(cx, 86f))
        }
        if (Extra.HEADPHONES in a.extras) {
            drawArc(Color(0xFF212121), 180f, 180f, false, p(50f - faceW / 2 - 2f, 16f), s(faceW + 4f, 56f), style = Stroke(2.6f * u))
            for (side in listOf(-1f, 1f)) {
                drawRoundRect(Color(0xFF212121), p(50f + side * (faceW / 2 + 1f) - 4f, 40f), s(8f, 14f), CornerRadius(3f * u))
                drawRoundRect(a.accent, p(50f + side * (faceW / 2 + 1f) - 2f, 43f), s(4f, 8f), CornerRadius(2f * u))
            }
        }
    }

    // ---- hats ----
    fun DrawScope.drawHat() {
        val c = a.hatColor
        when (a.hat) {
            HatStyle.NONE -> {}
            HatStyle.ANTENNA_CLIP -> {
                drawLine(Ink, p(64f, 22f), p(70f, 9f), 1.6f * u, StrokeCap.Round)
                drawCircle(c, 2.4f * u, p(70f, 9f))
                for (r in listOf(4.5f, 7.5f)) drawArc(c, -60f, 60f, false, p(70f - r, 9f - r), s(r * 2, r * 2), style = Stroke(1.2f * u, cap = StrokeCap.Round))
            }
            HatStyle.COWBOY -> {
                drawRoundRect(c, p(33f, 4f), s(34f, 22f), CornerRadius(8f * u))
                drawLine(shade(c, 0.6f), p(50f, 6f), p(50f, 14f), 1.4f * u, StrokeCap.Round)
                drawRect(a.accent, p(33f, 19f), s(34f, 4f))
                drawPath(Path().apply {
                    moveTo(12f * u, 20f * u); quadraticTo(20f * u, 30f * u, 50f * u, 30f * u); quadraticTo(80f * u, 30f * u, 88f * u, 20f * u)
                    quadraticTo(50f * u, 26f * u, 12f * u, 20f * u); close()
                }, c)
            }
            HatStyle.CAP -> {
                drawArc(c, 180f, 180f, true, p(24f, 12f), s(52f, 30f))
                drawOval(shade(c, 0.8f), p(46f, 22f), s(36f, 7f))
                drawCircle(a.accent, 1.8f * u, p(50f, 12f))
            }
            HatStyle.BEANIE -> {
                drawArc(c, 180f, 180f, true, p(23f, 10f), s(54f, 38f))
                drawRoundRect(shade(c, 0.8f), p(23f, 25f), s(54f, 8f), CornerRadius(3f * u))
                drawCircle(a.accent, 5f * u, p(50f, 9f))
            }
            HatStyle.CROWN -> {
                drawPath(poly(34f, 22f, 33f, 6f, 41f, 14f, 50f, 3f, 59f, 14f, 67f, 6f, 66f, 22f), c)
                listOf(a.accent, Color(0xFF29B6F6), a.accent).forEachIndexed { i, g -> drawCircle(g, 1.8f * u, p(42f + i * 8f, 18f)) }
            }
            HatStyle.HALO -> drawOval(c, p(32f, 2f), s(36f, 8f), style = Stroke(2.4f * u))
            HatStyle.HORNS -> for (side in listOf(-1f, 1f)) {
                drawPath(Path().apply {
                    moveTo((50f + side * 10f) * u, 22f * u)
                    quadraticTo((50f + side * 24f) * u, 20f * u, (50f + side * 26f) * u, 2f * u)
                    quadraticTo((50f + side * 18f) * u, 14f * u, (50f + side * 18f) * u, 25f * u); close()
                }, c)
            }
            HatStyle.BOW -> {
                drawPath(poly(68f, 20f, 58f, 12f, 60f, 26f), c)
                drawPath(poly(68f, 20f, 78f, 12f, 76f, 26f), c)
                drawCircle(shade(c), 2.6f * u, p(68f, 20f))
            }
            HatStyle.WIZARD -> {
                drawPath(Path().apply {
                    moveTo(30f * u, 24f * u); quadraticTo(52f * u, 10f * u, 64f * u, 0f * u); lineTo(72f * u, 24f * u); close()
                }, c)
                drawOval(c, p(18f, 20f), s(64f, 9f))
                star(this, 50f, 15f, 2.6f, a.accent); star(this, 60f, 8f, 1.8f, a.accent)
            }
            HatStyle.TOP_HAT -> {
                drawRect(c, p(35f, 0f), s(30f, 22f))
                drawRect(a.accent, p(35f, 16f), s(30f, 4f))
                drawOval(c, p(26f, 19f), s(48f, 7f))
            }
            HatStyle.PARTY -> {
                drawPath(poly(39f, 22f, 61f, 22f, 52f, 0f), c)
                drawLine(a.accent, p(43f, 14f), p(56f, 12f), 1.4f * u)
                drawLine(a.accent, p(41f, 19f), p(59f, 17f), 1.4f * u)
                drawCircle(a.accent, 3f * u, p(52f, 0f))
            }
            HatStyle.BUBBLE -> {
                drawCircle(c.copy(alpha = 0.22f), 34f * u, p(50f, 44f))
                drawCircle(Color.White.copy(alpha = 0.7f), 34f * u, p(50f, 44f), style = Stroke(1.6f * u))
                drawArc(Color.White.copy(alpha = 0.8f), 200f, 50f, false, p(22f, 16f), s(40f, 40f), style = Stroke(2.2f * u, cap = StrokeCap.Round))
            }
        }
    }

    fun star(ds: DrawScope, cx: Float, cy: Float, r: Float, color: Color) {
        val sp = Path()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.43f
            val ang = -PI / 2 + i * PI / 5
            val x = cx + rr * cos(ang).toFloat()
            val y = cy + rr * sin(ang).toFloat()
            if (i == 0) sp.moveTo(x * u, y * u) else sp.lineTo(x * u, y * u)
        }
        sp.close()
        ds.drawPath(sp, color)
    }

    fun heart(ds: DrawScope, cx: Float, cy: Float, size: Float, color: Color) {
        val k = size / 6f
        val hp = Path().apply {
            moveTo(cx * u, (cy + 4f * k) * u)
            cubicTo((cx - 6f * k) * u, (cy - 1f * k) * u, (cx - 3f * k) * u, (cy - 6f * k) * u, cx * u, (cy - 2.5f * k) * u)
            cubicTo((cx + 3f * k) * u, (cy - 6f * k) * u, (cx + 6f * k) * u, (cy - 1f * k) * u, cx * u, (cy + 4f * k) * u)
        }
        ds.drawPath(hp, color)
    }
}
