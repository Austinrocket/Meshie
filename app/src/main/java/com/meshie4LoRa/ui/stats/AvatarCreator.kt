package com.meshie4LoRa.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshie4LoRa.ui.stats.MoodAnalyzer.Mood

private val AvatarSaver = Saver<Avatar, String>({ it.toJson() }, { Avatar.parse(it) })

private enum class Layer(val label: String) { SCENE("Scene"), BODY("Body"), HAIR("Hair"), EYES("Eyes"), FACE("Face"), EARS("Ears"), HAT("Hat"), OUTFIT("Outfit"), EXTRAS("Extras") }

/** Layer-based chibi builder: presets, randomize, and one tab per layer with colour pickers. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AvatarCreator(initial: Avatar, name: String, onSave: (Avatar) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var av by rememberSaveable(stateSaver = AvatarSaver) { mutableStateOf(initial) }
    var mood by rememberSaveable { mutableStateOf<Mood?>(null) }
    var layer by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("$name's look") },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { Button(onClick = { onSave(av) }, modifier = Modifier.padding(end = 8.dp)) { Text("Save") } },
        )
        Box(
            Modifier.padding(top = 4.dp).fillMaxWidth(0.5f).aspectRatio(1f).align(Alignment.CenterHorizontally)
                .background(av.frame, RoundedCornerShape(16.dp)).padding(6.dp)
                .clip(RoundedCornerShape(12.dp)).border(3.dp, Color(0xFF263238), RoundedCornerShape(12.dp)),
        ) {
            BackdropCanvas(av.backdrop, Modifier.fillMaxSize())
            ChibiChan(mood, Modifier.fillMaxSize(0.85f).align(Alignment.Center), av)
        }
        Column(Modifier.weight(1f).fillMaxWidth().clipToBounds().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(mood == null, { mood = null }, { Text("🙂") })
                Mood.entries.forEach { m -> FilterChip(mood == m, { mood = m }, { Text(m.emoji) }) }
            }
            Text("Tap a mood to preview the face. On the radio it follows the persona's real mood.", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { av = Avatar.random() }) { Text("🎲 Randomize") }
                OutlinedButton(onClick = { av = initial }) { Text("Undo all") }
            }

            Text("Presets", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Avatar.PRESETS, key = { it.first }) { (label, preset) ->
                    Column(
                        Modifier.width(76.dp).clickable { av = preset },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(76.dp).clip(RoundedCornerShape(10.dp))) {
                            BackdropCanvas(preset.backdrop, Modifier.fillMaxSize(), animate = false)
                            ChibiChan(null, Modifier.fillMaxSize().padding(4.dp), preset, animate = false)
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }

            PrimaryScrollableTabRow(selectedTabIndex = layer, edgePadding = 0.dp) {
                Layer.entries.forEachIndexed { i, l -> Tab(layer == i, { layer = i }, text = { Text(l.label) }) }
            }

            when (Layer.entries[layer]) {
                Layer.SCENE -> {
                    Text("Background", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Backdrop.entries.forEach { b ->
                            Column(Modifier.width(72.dp).clickable { av = av.copy(backdrop = b) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                BackdropCanvas(
                                    b,
                                    Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).border(
                                        if (av.backdrop == b) 3.dp else 1.dp,
                                        if (av.backdrop == b) MaterialTheme.colorScheme.primary else Color.Gray, RoundedCornerShape(10.dp),
                                    ),
                                    animate = av.backdrop == b,
                                )
                                Text(b.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                    ColorPicker("Pokédex colour", av.frame, DexFrameColors) { av = av.copy(frame = it) }
                }
                Layer.BODY -> {
                    Options(BodyType.entries, av.body, { it.label }) { av = av.copy(body = it) }
                    ColorPicker("Skin", av.skin, Avatar.SKIN_TONES + Avatar.FANTASY_SKIN) { av = av.copy(skin = it) }
                }
                Layer.HAIR -> {
                    Options(HairStyle.entries, av.hair, { it.label }) { av = av.copy(hair = it) }
                    ColorPicker("Hair colour", av.hairColor, Avatar.HAIR_COLORS) { av = av.copy(hairColor = it) }
                }
                Layer.EYES -> {
                    Options(EyeStyle.entries, av.eyes, { it.label }) { av = av.copy(eyes = it) }
                    ColorPicker(if (av.eyes == EyeStyle.VISOR) "Visor glow" else "Eye colour", av.eyeColor, Brights) { av = av.copy(eyeColor = it) }
                }
                Layer.FACE -> Options(FaceStyle.entries, av.face, { it.label }) { av = av.copy(face = it) }
                Layer.EARS -> Options(EarStyle.entries, av.ears, { it.label }) { av = av.copy(ears = it) }
                Layer.HAT -> {
                    Options(HatStyle.entries, av.hat, { it.label }) { av = av.copy(hat = it) }
                    ColorPicker("Hat colour", av.hatColor, Brights) { av = av.copy(hatColor = it) }
                }
                Layer.OUTFIT -> {
                    Options(OutfitStyle.entries, av.outfit, { it.label }) { av = av.copy(outfit = it) }
                    ColorPicker("Outfit colour", av.outfitColor, Brights) { av = av.copy(outfitColor = it) }
                    ColorPicker("Accent colour (logos, ties, trims)", av.accent, Brights) { av = av.copy(accent = it) }
                }
                Layer.EXTRAS -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Extra.entries.forEach { e ->
                            FilterChip(e in av.extras, { av = av.copy(extras = if (e in av.extras) av.extras - e else av.extras + e) }, { Text(e.label) })
                        }
                    }
                    ColorPicker("Accent colour", av.accent, Brights) { av = av.copy(accent = it) }
                }
            }
        }
    }
}

private val Brights = listOf(
    Color(0xFF111111), Color(0xFFFAFAFA), Color(0xFFE53935), Color(0xFFFF7A00), Color(0xFFFFC107), Color(0xFF5FD39B),
    Color(0xFF00E5FF), Color(0xFF1E88E5), Color(0xFF7B3FE4), Color(0xFFFF4FD8), Color(0xFF8D5A2B), Color(0xFF78909C),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Options(all: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        all.forEach { o -> FilterChip(o == selected, { onPick(o) }, { Text(label(o)) }) }
    }
}

/** Swatches for quick picks plus hue / saturation / brightness sliders for anything else. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(title: String, color: Color, swatches: List<Color>, onPick: (Color) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(22.dp).background(color, CircleShape).border(1.dp, Color.Gray, CircleShape))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            swatches.forEach { s ->
                Box(
                    Modifier.size(30.dp).background(s, CircleShape)
                        .border(if (s.toArgb() == color.toArgb()) 3.dp else 1.dp, if (s.toArgb() == color.toArgb()) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                        .clickable { onPick(s) },
                )
            }
        }
        HsvSlider("Hue", hsv[0] / 360f, Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })) {
            onPick(Color.hsv(it * 360f, hsv[1].coerceAtLeast(0.05f), hsv[2].coerceAtLeast(0.1f)))
        }
        HsvSlider("Saturation", hsv[1], Brush.horizontalGradient(listOf(Color.hsv(hsv[0], 0f, hsv[2]), Color.hsv(hsv[0], 1f, hsv[2])))) {
            onPick(Color.hsv(hsv[0], it, hsv[2]))
        }
        HsvSlider("Brightness", hsv[2], Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hsv[0], hsv[1], 1f)))) {
            onPick(Color.hsv(hsv[0], hsv[1], it))
        }
    }
}

@Composable
private fun HsvSlider(label: String, value: Float, track: Brush, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(82.dp), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(8.dp).background(track, RoundedCornerShape(4.dp)))
            Slider(
                value.coerceIn(0f, 1f), onChange,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}
