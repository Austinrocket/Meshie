package com.meshie4LoRa.ui.stats

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

enum class BodyType(val label: String, val width: Float) { SLIM("Slim", 34f), REGULAR("Regular", 40f), CHUBBY("Chubby", 50f), ROUND("Round", 62f) }

enum class HairStyle(val label: String) {
    TWIN_TAILS("Twin tails"), LONG("Long"), PONYTAIL("Ponytail"), BUNS("Space buns"), BOB("Bob"), SHORT("Short"),
    SPIKY("Spiky"), MOHAWK("Mohawk"), AFRO("Afro"), BALD("Bald"),
}

enum class EyeStyle(val label: String) {
    ANIME("Anime"), CAT("Cat"), ALIEN("Alien"), SLEEPY("Sleepy"), DOTS("Dots"), CYCLOPS("Cyclops"), VISOR("Visor"), SHADES("Shades"),
}

enum class FaceStyle(val label: String) {
    BLUSH("Blush"), NONE("Plain"), FRECKLES("Freckles"), WHISKERS("Whiskers"), MUSTACHE("Mustache"), BEARD("Beard"),
    FANGS("Fangs"), GRILL("Robot grill"),
}

enum class EarStyle(val label: String) { NONE("None"), CAT("Cat"), BUNNY("Bunny"), BEAR("Bear"), ELF("Elf"), ANTENNAE("Antennae"), BOLTS("Bolts") }

enum class HatStyle(val label: String) {
    NONE("None"), ANTENNA_CLIP("Antenna clip"), COWBOY("Cowboy"), CAP("Cap"), BEANIE("Beanie"), CROWN("Crown"), HALO("Halo"),
    HORNS("Horns"), BOW("Bow"), WIZARD("Wizard"), TOP_HAT("Top hat"), PARTY("Party"), BUBBLE("Space helmet"),
}

enum class OutfitStyle(val label: String) {
    HOODIE("Hoodie"), TEE("Tee"), TANK("Tank top"), CHEER("Cheer"), SUIT("Suit"), VEST("Cowboy vest"), OVERALLS("Overalls"),
    ROBOT("Robot chassis"), SPACESUIT("Spacesuit"),
}

enum class Extra(val label: String) {
    POMPOMS("Pom-poms"), BOWTIE("Bow tie"), GOLD_CHAIN("Gold chain"), SCARF("Bandana"), HEADPHONES("Headphones"),
    ANGEL_WINGS("Angel wings"), BAT_WINGS("Bat wings"), CAT_TAIL("Tail"),
}

/** A persona's chibi look, built from layers. Stored as JSON in CharacterEntity.avatar; the mood still drives the expression. */
data class Avatar(
    val body: BodyType = BodyType.REGULAR,
    val skin: Color = Color(0xFFFFE6D6),
    val hair: HairStyle = HairStyle.TWIN_TAILS,
    val hairColor: Color = Color(0xFF5FD39B),
    val eyes: EyeStyle = EyeStyle.ANIME,
    val eyeColor: Color = Color(0xFF7B3FE4),
    val face: FaceStyle = FaceStyle.BLUSH,
    val ears: EarStyle = EarStyle.NONE,
    val hat: HatStyle = HatStyle.ANTENNA_CLIP,
    val hatColor: Color = Color(0xFF5FD39B),
    val outfit: OutfitStyle = OutfitStyle.HOODIE,
    val outfitColor: Color = Color(0xFF2B2F36),
    val accent: Color = Color(0xFF5FD39B),
    val extras: Set<Extra> = emptySet(),
    val backdrop: Backdrop = Backdrop.CLASSIC,
    /** Colour of the Pokédex card around the window. */
    val frame: Color = Color(0xFFD9363E),
) {
    fun toJson(): String = JSONObject().apply {
        put("body", body.name); put("skin", hex(skin)); put("hair", hair.name); put("hairColor", hex(hairColor))
        put("eyes", eyes.name); put("eyeColor", hex(eyeColor)); put("face", face.name); put("ears", ears.name)
        put("hat", hat.name); put("hatColor", hex(hatColor)); put("outfit", outfit.name); put("outfitColor", hex(outfitColor))
        put("accent", hex(accent)); put("extras", JSONArray(extras.map { it.name }))
        put("backdrop", backdrop.name); put("frame", hex(frame))
    }.toString()

    companion object {
        val DEFAULT = Avatar()

        /** Unknown or missing fields fall back to the default, so older or hand-edited JSON still loads. */
        fun parse(json: String, fallbackHair: Color = DEFAULT.hairColor): Avatar {
            val base = DEFAULT.copy(hairColor = fallbackHair, hatColor = fallbackHair, accent = fallbackHair)
            if (json.isBlank()) return base
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return base
            fun color(key: String, def: Color) = o.optString(key).takeIf { it.isNotBlank() }
                ?.let { runCatching { Color(it.toLong(16).toInt()) }.getOrNull() } ?: def
            val extras = o.optJSONArray("extras")?.let { a -> (0 until a.length()).mapNotNull { enumOrNull<Extra>(a.optString(it)) } }
            return Avatar(
                body = enumOrNull<BodyType>(o.optString("body")) ?: base.body,
                skin = color("skin", base.skin),
                hair = enumOrNull<HairStyle>(o.optString("hair")) ?: base.hair,
                hairColor = color("hairColor", base.hairColor),
                eyes = enumOrNull<EyeStyle>(o.optString("eyes")) ?: base.eyes,
                eyeColor = color("eyeColor", base.eyeColor),
                face = enumOrNull<FaceStyle>(o.optString("face")) ?: base.face,
                ears = enumOrNull<EarStyle>(o.optString("ears")) ?: base.ears,
                hat = enumOrNull<HatStyle>(o.optString("hat")) ?: base.hat,
                hatColor = color("hatColor", base.hatColor),
                outfit = enumOrNull<OutfitStyle>(o.optString("outfit")) ?: base.outfit,
                outfitColor = color("outfitColor", base.outfitColor),
                accent = color("accent", base.accent),
                extras = extras?.toSet() ?: base.extras,
                backdrop = enumOrNull<Backdrop>(o.optString("backdrop")) ?: base.backdrop,
                frame = color("frame", base.frame),
            )
        }

        fun random(rnd: Random = Random.Default): Avatar {
            fun pick(list: List<Color>) = list[rnd.nextInt(list.size)]
            fun bright() = Color.hsv(rnd.nextFloat() * 360f, 0.45f + rnd.nextFloat() * 0.5f, 0.55f + rnd.nextFloat() * 0.45f)
            return Avatar(
                body = BodyType.entries.random(rnd),
                skin = if (rnd.nextFloat() < 0.75f) pick(SKIN_TONES) else pick(FANTASY_SKIN),
                hair = HairStyle.entries.random(rnd),
                hairColor = if (rnd.nextBoolean()) pick(HAIR_COLORS) else bright(),
                eyes = EyeStyle.entries.random(rnd),
                eyeColor = bright(),
                face = FaceStyle.entries.random(rnd),
                ears = if (rnd.nextFloat() < 0.5f) EarStyle.NONE else EarStyle.entries.random(rnd),
                hat = if (rnd.nextFloat() < 0.3f) HatStyle.NONE else HatStyle.entries.random(rnd),
                hatColor = bright(),
                outfit = OutfitStyle.entries.random(rnd),
                outfitColor = bright(),
                accent = bright(),
                extras = Extra.entries.shuffled(rnd).take(rnd.nextInt(3)).toSet(),
                backdrop = Backdrop.entries.random(rnd),
                frame = DexFrameColors.random(rnd),
            )
        }

        val SKIN_TONES = listOf(
            Color(0xFFFFE6D6), Color(0xFFFFD7B5), Color(0xFFF1C27D), Color(0xFFE0AC69),
            Color(0xFFC68642), Color(0xFF8D5524), Color(0xFF6B4226), Color(0xFF4A2C1A),
        )
        val FANTASY_SKIN = listOf(
            Color(0xFF9BE37B), Color(0xFF8FD3FF), Color(0xFFB0BEC5), Color(0xFFC9A7FF), Color(0xFFFF8A80), Color(0xFFFFF59D),
        )
        val HAIR_COLORS = listOf(
            Color(0xFF1B1B1B), Color(0xFF4E342E), Color(0xFF8D6E63), Color(0xFFFFE082), Color(0xFFE65100),
            Color(0xFFEEEEEE), Color(0xFF9E9E9E), Color(0xFF5FD39B), Color(0xFFFF8FC8), Color(0xFF8FB8FF), Color(0xFFB98FFF),
        )

        val PRESETS: List<Pair<String, Avatar>> by lazy {
            PRESET_LOOKS.map { (name, a) -> name to (PRESET_SCENES[name]?.let { (b, f) -> a.copy(backdrop = b, frame = f) } ?: a) }
        }

        private val PRESET_SCENES = mapOf(
            "Alien" to (Backdrop.GALAXY to Color(0xFF43A047)), "Cowboy" to (Backdrop.WILD_WEST to Color(0xFF6D4C41)),
            "Robot" to (Backdrop.CIRCUIT to Color(0xFF212121)), "Cheerleader" to (Backdrop.FESTIVAL to Color(0xFFE53935)),
            "Catgirl" to (Backdrop.SAKURA to Color(0xFFFF4FD8)), "Big Homie" to (Backdrop.NEON_CITY to Color(0xFF1E88E5)),
            "DJ" to (Backdrop.RAVE to Color(0xFF8E24AA)), "Wizard" to (Backdrop.GALAXY to Color(0xFF4527A0)),
            "Punk" to (Backdrop.MATRIX to Color(0xFF212121)), "Angel" to (Backdrop.HEAVEN to Color(0xFFF5F5F5)),
            "Demon" to (Backdrop.HELLFIRE to Color(0xFF212121)), "Blob" to (Backdrop.UNDERWATER to Color(0xFF00ACC1)),
            "Bunny" to (Backdrop.CANDY to Color(0xFFFF4FD8)), "Grandpa" to (Backdrop.CAMPFIRE to Color(0xFF6D4C41)),
            "Royalty" to (Backdrop.DISCO to Color(0xFFFFB300)), "Bear bro" to (Backdrop.CAMPFIRE to Color(0xFF43A047)),
        )

        private val PRESET_LOOKS: List<Pair<String, Avatar>> = listOf(
            "Classic" to DEFAULT,
            "Alien" to Avatar(
                skin = Color(0xFF9BE37B), hair = HairStyle.BALD, eyes = EyeStyle.ALIEN, face = FaceStyle.NONE, ears = EarStyle.ANTENNAE,
                hat = HatStyle.BUBBLE, hatColor = Color(0xFFB3E5FC), outfit = OutfitStyle.SPACESUIT, outfitColor = Color(0xFFECEFF1),
                accent = Color(0xFF7B3FE4), eyeColor = Color(0xFF111111),
            ),
            "Cowboy" to Avatar(
                skin = Color(0xFFE0AC69), hair = HairStyle.SHORT, hairColor = Color(0xFF4E342E), face = FaceStyle.MUSTACHE,
                eyeColor = Color(0xFF2E7D32), hat = HatStyle.COWBOY, hatColor = Color(0xFF8D5A2B), outfit = OutfitStyle.VEST,
                outfitColor = Color(0xFF6D4C41), accent = Color(0xFFC62828), extras = setOf(Extra.SCARF),
            ),
            "Robot" to Avatar(
                skin = Color(0xFFB0BEC5), hair = HairStyle.BALD, eyes = EyeStyle.VISOR, eyeColor = Color(0xFF00E5FF),
                face = FaceStyle.GRILL, ears = EarStyle.BOLTS, hat = HatStyle.ANTENNA_CLIP, hatColor = Color(0xFF00E5FF),
                outfit = OutfitStyle.ROBOT, outfitColor = Color(0xFF78909C), accent = Color(0xFF00E5FF),
            ),
            "Cheerleader" to Avatar(
                body = BodyType.SLIM, skin = Color(0xFFFFD7B5), hair = HairStyle.PONYTAIL, hairColor = Color(0xFFFFE082),
                eyeColor = Color(0xFF1E88E5), hat = HatStyle.BOW, hatColor = Color(0xFFE53935), outfit = OutfitStyle.CHEER,
                outfitColor = Color(0xFFE53935), accent = Color(0xFFFFFFFF), extras = setOf(Extra.POMPOMS),
            ),
            "Catgirl" to Avatar(
                hair = HairStyle.LONG, hairColor = Color(0xFFB98FFF), eyes = EyeStyle.CAT, eyeColor = Color(0xFFFFC107),
                face = FaceStyle.WHISKERS, ears = EarStyle.CAT, hat = HatStyle.NONE, outfit = OutfitStyle.HOODIE,
                outfitColor = Color(0xFFFF8FC8), accent = Color(0xFFFFFFFF), extras = setOf(Extra.CAT_TAIL, Extra.BOWTIE),
            ),
            "Big Homie" to Avatar(
                body = BodyType.ROUND, skin = Color(0xFF6B4226), hair = HairStyle.SHORT, hairColor = Color(0xFF1B1B1B),
                eyes = EyeStyle.SHADES, face = FaceStyle.BEARD, hat = HatStyle.CAP, hatColor = Color(0xFF1565C0),
                outfit = OutfitStyle.TEE, outfitColor = Color(0xFFFAFAFA), accent = Color(0xFF1565C0), extras = setOf(Extra.GOLD_CHAIN),
            ),
            "DJ" to Avatar(
                skin = Color(0xFFC68642), hair = HairStyle.AFRO, hairColor = Color(0xFFFF4FD8), eyes = EyeStyle.SHADES,
                hat = HatStyle.NONE, outfit = OutfitStyle.TANK, outfitColor = Color(0xFF111111), accent = Color(0xFF00E5FF),
                extras = setOf(Extra.HEADPHONES, Extra.GOLD_CHAIN),
            ),
            "Wizard" to Avatar(
                hair = HairStyle.LONG, hairColor = Color(0xFFEEEEEE), eyes = EyeStyle.SLEEPY, eyeColor = Color(0xFF1E88E5),
                face = FaceStyle.BEARD, hat = HatStyle.WIZARD, hatColor = Color(0xFF4527A0), outfit = OutfitStyle.HOODIE,
                outfitColor = Color(0xFF4527A0), accent = Color(0xFFFFD54F),
            ),
            "Punk" to Avatar(
                body = BodyType.SLIM, hair = HairStyle.MOHAWK, hairColor = Color(0xFFFF1744), eyeColor = Color(0xFF00C853),
                face = FaceStyle.FANGS, hat = HatStyle.NONE, outfit = OutfitStyle.TANK, outfitColor = Color(0xFF212121),
                accent = Color(0xFFFF1744), extras = setOf(Extra.GOLD_CHAIN),
            ),
            "Angel" to Avatar(
                hair = HairStyle.BOB, hairColor = Color(0xFFFFE082), eyeColor = Color(0xFF64B5F6), hat = HatStyle.HALO,
                hatColor = Color(0xFFFFD54F), outfit = OutfitStyle.TEE, outfitColor = Color(0xFFFFFFFF), accent = Color(0xFFFFD54F),
                extras = setOf(Extra.ANGEL_WINGS),
            ),
            "Demon" to Avatar(
                skin = Color(0xFFFF8A80), hair = HairStyle.SPIKY, hairColor = Color(0xFF212121), eyeColor = Color(0xFFFFEB3B),
                face = FaceStyle.FANGS, ears = EarStyle.ELF, hat = HatStyle.HORNS, hatColor = Color(0xFF424242),
                outfit = OutfitStyle.SUIT, outfitColor = Color(0xFF212121), accent = Color(0xFFD50000), extras = setOf(Extra.BAT_WINGS, Extra.CAT_TAIL),
            ),
            "Blob" to Avatar(
                body = BodyType.ROUND, skin = Color(0xFF8FD3FF), hair = HairStyle.BALD, eyes = EyeStyle.CYCLOPS, eyeColor = Color(0xFFE91E63),
                face = FaceStyle.NONE, ears = EarStyle.ANTENNAE, hat = HatStyle.PARTY, hatColor = Color(0xFFFFC107),
                outfit = OutfitStyle.OVERALLS, outfitColor = Color(0xFF3949AB), accent = Color(0xFFFFEB3B),
            ),
            "Bunny" to Avatar(
                hair = HairStyle.BOB, hairColor = Color(0xFFFAFAFA), eyeColor = Color(0xFFE53935), ears = EarStyle.BUNNY,
                hat = HatStyle.NONE, outfit = OutfitStyle.HOODIE, outfitColor = Color(0xFFFFCDD2), accent = Color(0xFFE91E63),
            ),
            "Grandpa" to Avatar(
                skin = Color(0xFFF1C27D), hair = HairStyle.BALD, hairColor = Color(0xFFBDBDBD), eyes = EyeStyle.SLEEPY,
                face = FaceStyle.MUSTACHE, hat = HatStyle.NONE, outfit = OutfitStyle.SUIT, outfitColor = Color(0xFF6D4C41),
                accent = Color(0xFFC62828), extras = setOf(Extra.BOWTIE),
            ),
            "Royalty" to Avatar(
                hair = HairStyle.BUNS, hairColor = Color(0xFF4E342E), skin = Color(0xFF8D5524), eyeColor = Color(0xFF6A1B9A),
                hat = HatStyle.CROWN, hatColor = Color(0xFFFFC107), outfit = OutfitStyle.SUIT, outfitColor = Color(0xFF6A1B9A),
                accent = Color(0xFFFFC107), extras = setOf(Extra.GOLD_CHAIN),
            ),
            "Bear bro" to Avatar(
                body = BodyType.CHUBBY, skin = Color(0xFFFFD7B5), hair = HairStyle.SHORT, hairColor = Color(0xFF8D6E63),
                face = FaceStyle.BEARD, ears = EarStyle.BEAR, hat = HatStyle.BEANIE, hatColor = Color(0xFF2E7D32),
                outfit = OutfitStyle.OVERALLS, outfitColor = Color(0xFF1565C0), accent = Color(0xFFC62828),
            ),
        )

        private fun hex(c: Color) = "%08x".format(c.toArgb())
        private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }
    }
}
