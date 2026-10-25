package com.khcompany.lanedash.game

import androidx.compose.ui.graphics.Color

const val LANE_COUNT = 5

/** Visible width of the play field, in arbitrary world units (resolution independent). */
const val WORLD_WIDTH_UNITS = 18f

/** Fixed x position of the player's car, in world units from the left edge. */
const val CAR_X_UNITS = 3.2f

/**
 * Fuel-can pickup grab radius, in world units. Obstacles don't use this — their hitbox is
 * derived per variant from each sprite's actual rendered size (see [ObstacleVariant.hitHalfWidthUnits]).
 */
const val HITBOX_HALF_WIDTH = 0.5f

// --- Pseudo-3D perspective ---
// Farther (lower-index, drawn toward the top of the road) lanes read smaller/narrower
// than nearer ones, like a road converging toward a vanishing point, instead of LANE_COUNT
// uniform bands. PERSPECTIVE_MIN_SCALE is the size ratio of the farthest lane vs. the
// nearest. Only LANE_COUNT and this ratio are needed to compute a resolution-independent
// size multiplier, so it's shared by collision (hitbox scale, GameEngine) and rendering
// (actual pixel Y positions, computed in GameScreen since only rendering needs canvas
// dimensions). Mirrors PERSPECTIVE_MIN_SCALE/perspectiveCumulative/laneSizeScale in the
// web build's engine.js — keep in sync.
const val PERSPECTIVE_MIN_SCALE = 0.6f

/**
 * Cumulative "depth-weighted" position from the horizon (p=0) to p (0..LANE_COUNT); scale
 * increases linearly with depth, so this is its integral — a quadratic in p. Works for any
 * real p, not just integers, so the same formula drives both discrete lanes and the
 * player's continuously-tweened lane position.
 */
fun perspectiveCumulative(p: Float): Float {
    val d = p / LANE_COUNT
    return LANE_COUNT * (PERSPECTIVE_MIN_SCALE * d + (1f - PERSPECTIVE_MIN_SCALE) * d * d / 2f)
}

val PERSPECTIVE_TOTAL = perspectiveCumulative(LANE_COUNT.toFloat())

/** Average size multiplier over the lane-width window [lane, lane+1), vs. the old uniform size. */
fun laneSizeScale(lane: Float): Float {
    return LANE_COUNT * (perspectiveCumulative(lane + 1f) - perspectiveCumulative(lane)) / PERSPECTIVE_TOTAL
}

enum class GameStatus { READY, RUNNING, GAME_OVER }

enum class EntityKind { OBSTACLE, ITEM }

/** What a pickup does: refuel, a timed invincibility shield, or a timed fuel magnet. */
enum class ItemType { FUEL, SHIELD, MAGNET }

/**
 * Per-city gameplay tuning. Harder cities give a score bonus so picking them is worth it
 * on the shared leaderboard. Mirrors CITIES[].traits in the web build's catalog.js.
 */
data class RunTraits(
    val speed: Float = 1f,
    val fuelDrain: Float = 1f,
    val spawnDensity: Float = 1f,
    val score: Float = 1f,
) {
    companion object {
        val DEFAULT = RunTraits()
    }
}

/**
 * Drawn width, in "base car widths", per meter of real vehicle length. Anchored on the player's
 * SUV (4.4 m) at 1.47 base widths, then enlarged 10% (1.617); every other vehicle scales from its
 * real length on the same scale (a 4.2 m sports car comes out narrower, an 11 m bus 2.5x wider).
 * Mirrors VEHICLE_WIDTH_PER_METER in the web catalog.js.
 */
const val VEHICLE_WIDTH_PER_METER = 1.47f * 1.1f / 4.4f

/**
 * Obstacle sub-appearance; items are always a fuel-can pickup.
 * [hitWidthScale] and [heightScale] control how big the sprite is DRAWN (see GameScreen's
 * obstacleWidth/obstacleHeight) — they don't directly control collision. GameScreen fits
 * each sprite by width first, then shrinks it to fit by height if it would overflow, so a
 * tall/narrow sprite (a cone) can render much narrower on screen than hitWidthScale alone
 * suggests. [hitHalfWidthUnits] is precomputed from each sprite's actual rendered geometry
 * (mirrors computeObstacleHitHalfWidthUnits in the web build's render.js, assuming the same
 * ~16:9 landscape reference the web canvas is locked to) so collision stays matched to what's
 * on screen instead of drifting after every size/asset change.
 * [spriteName] is an optional drawable name (no extension), e.g. "obstacle_bus" — drop a
 * matching file into res/drawable and it's used automatically; otherwise the game falls
 * back to the procedural vector art for that variant.
 *
 * Vehicles (lengthMeters > 0) ignore the two scales above: their drawn width comes from their
 * real-world length, on the same meters-to-width scale as the player's car
 * ([VEHICLE_WIDTH_PER_METER]), so a bus really is ~2.4x a car and a cement mixer ~1.9x. Their
 * height then follows from the sprite's own aspect ratio. Cones and barriers stay gameplay-sized
 * (true-to-scale they'd be a few pixels wide).
 */
enum class ObstacleVariant(
    val hitWidthScale: Float,
    val heightScale: Float,
    val spriteName: String,
    val hitHalfWidthUnits: Float,
    val lengthMeters: Float = 0f,
) {
    // The cone was shrunk 30% (0.3/0.45 -> 0.21/0.315; its hitbox 0.46 -> 0.322).
    CONE(0.21f, 0.315f, "obstacle_cone", 0.322f),
    BARRIER(1.0f, 0.55f, "obstacle_barrier", 0.70f),

    // hitHalfWidthUnits for vehicles = 0.5 * lengthMeters * VEHICLE_WIDTH_PER_METER * 1.1275
    // (world units per base car width at the 16:9 reference) * 0.92 (graze forgiveness).
    SEDAN(0f, 0f, "obstacle_sedan", 0.895f, lengthMeters = 4.7f),
    SUV(0f, 0f, "obstacle_suv", 0.915f, lengthMeters = 4.8f),
    BUS(0f, 0f, "obstacle_bus", 2.097f, lengthMeters = 11.0f),
    TRUCK(0f, 0f, "obstacle_truck", 1.620f, lengthMeters = 8.5f),
}

data class RoadEntity(
    val id: Long,
    val lane: Int,
    var x: Float,
    val kind: EntityKind,
    val obstacleVariant: ObstacleVariant = ObstacleVariant.CONE,
    val itemType: ItemType = ItemType.FUEL,
    var nearMissAwarded: Boolean = false,
)

data class CityTheme(
    val id: String,
    val displayName: String,
    val englishName: String,
    val skyTop: Color,
    val skyBottom: Color,
    val buildingColors: List<Color>,
    val windowColor: Color,
    val landmarkColor: Color,
    val roadColor: Color,
    val roadLineColor: Color,
    /**
     * Optional drawable name (no extension) for a tileable skyline backdrop image, e.g.
     * "city_seoul". Drop a matching file into res/drawable and it's used automatically,
     * tiled/scrolled to fill the sky area; otherwise the game falls back to the procedural
     * gradient + building silhouettes.
     */
    val spriteName: String = "",
    /**
     * Horizon color of the skyline sprite (the procedural sky colors above don't match the
     * sprites) — tints the far-lane fog and road haze. Mirrors `haze` in the web catalog.js.
     */
    val haze: Color = Color(0xFF8E83B5),
    val traits: RunTraits = RunTraits.DEFAULT,
    /** One-line summary of [traits] shown on the city select card. */
    val traitText: String = "",
)

/**
 * Original silhouette families loosely evoking how compact SUV design has evolved
 * decade to decade — not a reproduction of any specific real model.
 */
enum class CarBodyStyle { BOXY_90S, ROUNDED_2000S, SMOOTH_2010S, SHARP_MODERN, FACETED_CURRENT }

data class CarOption(
    val id: String,
    val displayName: String,
    val englishName: String,
    val bodyStyle: CarBodyStyle,
    val bodyColor: Color,
    val bodyShade: Color,
    val roofColor: Color,
    val wheelColor: Color = Color(0xFF1A1A1A),
    /**
     * Optional drawable name (no extension) for a PNG/sprite replacement, e.g. "car_gen1".
     * Drop a matching file into res/drawable and it's used automatically; otherwise the
     * game falls back to the procedural vector silhouette for [bodyStyle].
     */
    val spriteName: String = "",
    /** Real-world vehicle length, which sets how wide it's drawn relative to the others. */
    val lengthMeters: Float = 4.4f,
)

object GameCatalog {

    val cities = listOf(
        CityTheme(
            id = "seoul",
            displayName = "서울",
            englishName = "SEOUL",
            skyTop = Color(0xFF2E1A47),
            skyBottom = Color(0xFFFF8C69),
            buildingColors = listOf(Color(0xFF3A2F55), Color(0xFF5C4B7A), Color(0xFF7A5C99)),
            windowColor = Color(0xFFFFD166),
            landmarkColor = Color(0xFFFF4D6D),
            roadColor = Color(0xFF241830),
            roadLineColor = Color(0xFFFFD166),
            spriteName = "city_seoul",
            haze = Color(0xFFD58E86),
            traitText = "균형 잡힌 기본 코스",
        ),
        CityTheme(
            id = "tokyo",
            displayName = "도쿄",
            englishName = "TOKYO",
            skyTop = Color(0xFF05021A),
            skyBottom = Color(0xFF1B0F3D),
            buildingColors = listOf(Color(0xFF150A33), Color(0xFF1F1247), Color(0xFF2A1758)),
            windowColor = Color(0xFF00E5FF),
            landmarkColor = Color(0xFFFF2E92),
            roadColor = Color(0xFF0D0620),
            roadLineColor = Color(0xFFFF2E92),
            spriteName = "city_tokyo",
            haze = Color(0xFF9EC4E6),
            traits = RunTraits(speed = 1.15f),
            traitText = "속도 +15%",
        ),
        CityTheme(
            id = "newyork",
            displayName = "뉴욕",
            englishName = "NEW YORK",
            skyTop = Color(0xFFFF9A56),
            skyBottom = Color(0xFFFFD56B),
            buildingColors = listOf(Color(0xFF6B4A3A), Color(0xFF8C6450), Color(0xFFA9795E)),
            windowColor = Color(0xFFFFF3B0),
            landmarkColor = Color(0xFFC94C4C),
            roadColor = Color(0xFF3A2E28),
            roadLineColor = Color(0xFFFFF3B0),
            spriteName = "city_newyork",
            haze = Color(0xFF8EBDE8),
            traits = RunTraits(spawnDensity = 1.25f, score = 1.1f),
            traitText = "교통량 +25% · 점수 +10%",
        ),
        CityTheme(
            id = "paris",
            displayName = "파리",
            englishName = "PARIS",
            skyTop = Color(0xFFBFE3FF),
            skyBottom = Color(0xFFF6E7D8),
            buildingColors = listOf(Color(0xFFE8D9C0), Color(0xFFD8C3A5), Color(0xFFC7AE8D)),
            windowColor = Color(0xFF5B7C99),
            landmarkColor = Color(0xFF708090),
            roadColor = Color(0xFF4A4038),
            roadLineColor = Color(0xFFFFFFFF),
            spriteName = "city_paris",
            haze = Color(0xFF1B2641),
            traits = RunTraits(fuelDrain = 1.3f, score = 1.1f),
            traitText = "연료 소모 +30% · 점수 +10%",
        ),
    )

    val cars = listOf(
        CarOption(
            id = "suv",
            displayName = "SUV",
            englishName = "SUV",
            bodyStyle = CarBodyStyle.BOXY_90S,
            bodyColor = Color(0xFFE63946),
            bodyShade = Color(0xFFB22C39),
            roofColor = Color(0xFF1D1D2C),
            spriteName = "car_gen1",
        ),
        CarOption(
            id = "sports_car",
            displayName = "스포츠카",
            englishName = "SPORTS CAR",
            bodyStyle = CarBodyStyle.ROUNDED_2000S,
            bodyColor = Color(0xFFFFA23C),
            bodyShade = Color(0xFFCC7A1E),
            roofColor = Color(0xFF2B2B2B),
            spriteName = "car_gen2",
            lengthMeters = 4.2f,
        ),
        CarOption(
            id = "sedan",
            displayName = "세단",
            englishName = "SEDAN",
            bodyStyle = CarBodyStyle.SMOOTH_2010S,
            bodyColor = Color(0xFF3D8BFF),
            bodyShade = Color(0xFF2A63BF),
            roofColor = Color(0xFF1A2340),
            spriteName = "car_gen3",
        ),
        CarOption(
            id = "van",
            displayName = "승합차",
            englishName = "VAN",
            bodyStyle = CarBodyStyle.SHARP_MODERN,
            bodyColor = Color(0xFF4CAF50),
            bodyShade = Color(0xFF357A38),
            roofColor = Color(0xFF22331F),
            spriteName = "car_gen4",
            lengthMeters = 4.8f,
        ),
    )

    fun cityById(id: String?): CityTheme = cities.firstOrNull { it.id == id } ?: cities.first()
    fun carById(id: String?): CarOption = cars.firstOrNull { it.id == id } ?: cars.first()
}
