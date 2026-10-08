// Ported from app/src/main/java/.../game/GameEngine.kt + Model.kt — keep formulas in sync.

const LANE_COUNT = 5;
const WORLD_WIDTH_UNITS = 18;
const CAR_X_UNITS = 3.2;
// Fuel-can pickup grab radius, in world units (the can itself renders at roughly 0.39
// world units of half-width at carHeight*0.56 — this adds a little grab-forgiveness on
// top, since a generous radius on a beneficial pickup is good game feel). Obstacles don't
// use this — their hitbox is derived per variant from each sprite's actual rendered size
// (see OBSTACLE_VARIANTS in catalog.js and computeObstacleHitHalfWidthUnits in render.js),
// so it can't drift out of sync with what's on screen the way this flat constant did before.
const HITBOX_HALF_WIDTH = 0.5;

// Difficulty bumped up again ahead of a content update — higher top speed, faster ramp to
// it, and denser spawns throughout (both at the min and max end). Mirrors GameEngine.kt.
const START_SPEED = 5.5;
const MAX_SPEED = 16;
const SPEED_RAMP_PER_SECOND = 0.5;
const MIN_SPAWN_INTERVAL = 0.4;
const MAX_SPAWN_INTERVAL = 1.1;
const ITEM_SPAWN_CHANCE = 0.15;
const POWERUP_SPAWN_CHANCE = 0.05;
const ITEM_SCORE_BONUS = 50;
const POWERUP_SCORE_BONUS = 25;
const MAX_FUEL = 100;
const FUEL_DRAIN_PER_SECOND = 4.5;
const FUEL_REFILL_AMOUNT = 26;

const NEAR_MISS_WINDOW_SECONDS = 0.3;
const NEAR_MISS_SCORE = 30;
const NEAR_MISS_FUEL = 3;

const SHIELD_DURATION_SECONDS = 6;
const MAGNET_DURATION_SECONDS = 8;
const MAGNET_RANGE_UNITS = 9;

const DEFAULT_TRAITS = { speed: 1, fuelDrain: 1, spawnDensity: 1, score: 1 };

// --- Pseudo-3D perspective ---
// Farther (lower-index, drawn toward the top of the road) lanes read smaller/narrower
// than nearer ones, like a road converging toward a vanishing point, instead of LANE_COUNT
// uniform bands. PERSPECTIVE_MIN_SCALE is the size ratio of the farthest lane vs. the
// nearest. Only LANE_COUNT and this ratio are needed to compute a resolution-independent
// size multiplier, so it's shared by collision (hitbox scale, here) and rendering (actual
// pixel Y positions, computed in render.js since only rendering needs canvas dimensions).
const PERSPECTIVE_MIN_SCALE = 0.6;

// Cumulative "depth-weighted" position from the horizon (p=0) to p (0..LANE_COUNT); scale
// increases linearly with depth, so this is its integral — a quadratic in p. Works for any
// real p, not just integers, so the same formula drives both discrete lanes and the
// player's continuously-tweened lane position.
function perspectiveCumulative(p) {
  const d = p / LANE_COUNT;
  return LANE_COUNT * (PERSPECTIVE_MIN_SCALE * d + (1 - PERSPECTIVE_MIN_SCALE) * d * d / 2);
}
const PERSPECTIVE_TOTAL = perspectiveCumulative(LANE_COUNT);

/** Average size multiplier over the lane-width window [lane, lane+1), vs. the old uniform size. */
function laneSizeScale(lane) {
  return LANE_COUNT * (perspectiveCumulative(lane + 1) - perspectiveCumulative(lane)) / PERSPECTIVE_TOTAL;
}

class GameEngine {
  constructor(onSfx, random) {
    this.onSfx = onSfx || (() => {});
    this.random = random || Math.random;
    this.status = 'READY';
    this.paused = false;
    this.laneIndex = Math.floor(LANE_COUNT / 2);
    this.distance = 0;
    this.speed = START_SPEED;
    this.score = 0;
    this.fuel = MAX_FUEL;
    this.gameOverReason = null;
    this.entities = [];
    this.traits = DEFAULT_TRAITS;
    this.shieldTimeLeft = 0;
    this.magnetTimeLeft = 0;
    this.nearMissCount = 0;
    this.fuelCollected = 0;
    this.elapsedSeconds = 0;
    this._bonusScore = 0;
    this._spawnCooldown = 1.2;
    this._nextEntityId = 0;
    this._fuelWarningPlayed = false;
    this._lastSpawnLane = -1;
  }

  /** Ground speed after the city's speed multiplier — what actually moves the world. */
  get effectiveSpeed() {
    return this.speed * this.traits.speed;
  }

  start(traits) {
    this.traits = traits || DEFAULT_TRAITS;
    this.entities = [];
    this.laneIndex = Math.floor(LANE_COUNT / 2);
    this.distance = 0;
    this.speed = START_SPEED;
    this.score = 0;
    this._bonusScore = 0;
    this.fuel = MAX_FUEL;
    this._spawnCooldown = 1.0;
    this.gameOverReason = null;
    this._fuelWarningPlayed = false;
    this._lastSpawnLane = -1;
    this.paused = false;
    this.shieldTimeLeft = 0;
    this.magnetTimeLeft = 0;
    this.nearMissCount = 0;
    this.fuelCollected = 0;
    this.elapsedSeconds = 0;
    this.status = 'RUNNING';
  }

  pause() {
    if (this.status === 'RUNNING') this.paused = true;
  }

  resume() {
    this.paused = false;
  }

  moveLaneUp() {
    this._changeLane(Math.max(0, this.laneIndex - 1));
  }

  moveLaneDown() {
    this._changeLane(Math.min(LANE_COUNT - 1, this.laneIndex + 1));
  }

  _changeLane(next) {
    if (this.status !== 'RUNNING' || this.paused || next === this.laneIndex) return;
    this._awardNearMisses(this.laneIndex);
    this.onSfx('LANE_CHANGE');
    this.laneIndex = next;
  }

  // Swerving out of a lane just before an obstacle in it arrives is a "near miss" — a
  // reward for risky dodging, which matters now that traffic is dense. Each obstacle pays
  // out at most once, and only if it was within a short, speed-scaled reach of the car.
  _awardNearMisses(fromLane) {
    const reach = this.effectiveSpeed * NEAR_MISS_WINDOW_SECONDS;
    let awarded = 0;
    for (const entity of this.entities) {
      if (entity.kind !== 'OBSTACLE' || entity.lane !== fromLane || entity.nearMissAwarded) continue;
      const halfWidth = entity.variant.hitHalfWidthUnits * laneSizeScale(entity.lane);
      const dx = entity.x - CAR_X_UNITS;
      if (dx > -halfWidth && dx < halfWidth + reach) {
        entity.nearMissAwarded = true;
        awarded++;
      }
    }
    if (awarded > 0) {
      this.nearMissCount += awarded;
      this._bonusScore += NEAR_MISS_SCORE * awarded;
      this.fuel = Math.min(MAX_FUEL, this.fuel + NEAR_MISS_FUEL * awarded);
      this.onSfx('NEAR_MISS');
    }
  }

  tick(dtSeconds) {
    if (this.status !== 'RUNNING' || this.paused) return;
    const dt = Math.min(dtSeconds, 0.05);

    this.elapsedSeconds += dt;
    this.speed = Math.min(MAX_SPEED, this.speed + SPEED_RAMP_PER_SECOND * dt);
    const groundSpeed = this.effectiveSpeed;
    this.distance += groundSpeed * dt;
    // The bonus is tracked separately because the distance part is recomputed every tick
    // and would otherwise wipe pickup/near-miss points one frame later.
    this.score = Math.floor(this.distance * 10 * this.traits.score) + this._bonusScore;

    this.shieldTimeLeft = Math.max(0, this.shieldTimeLeft - dt);
    this.magnetTimeLeft = Math.max(0, this.magnetTimeLeft - dt);

    this.fuel = Math.max(0, this.fuel - FUEL_DRAIN_PER_SECOND * this.traits.fuelDrain * dt);
    if (!this._fuelWarningPlayed && this.fuel <= MAX_FUEL * 0.2) {
      this._fuelWarningPlayed = true;
      this.onSfx('FUEL_LOW_WARNING');
    }
    if (this.fuel <= 0) {
      this.onSfx('FUEL_EMPTY');
      this._endGame('FUEL_EMPTY');
      return;
    }

    this._spawnCooldown -= dt;
    if (this._spawnCooldown <= 0) {
      this._spawnEntity();
      const speedFactor = Math.min(1, Math.max(0, this.speed / MAX_SPEED));
      this._spawnCooldown =
        (MAX_SPAWN_INTERVAL - (MAX_SPAWN_INTERVAL - MIN_SPAWN_INTERVAL) * speedFactor) / this.traits.spawnDensity;
    }

    for (let i = this.entities.length - 1; i >= 0; i--) {
      const entity = this.entities[i];
      entity.x -= groundSpeed * dt;

      // Scaled by the entity's own lane depth (see laneSizeScale) — the player sits in
      // that same lane when this fires, so both shrink together and the "touch" point
      // this half-width was calibrated at stays correct at any lane.
      const halfWidth = (entity.kind === 'OBSTACLE'
        ? entity.variant.hitHalfWidthUnits
        : HITBOX_HALF_WIDTH) * laneSizeScale(entity.lane);
      const touching = entity.lane === this.laneIndex && this._isOverlappingCar(entity.x, halfWidth);

      if (entity.kind === 'OBSTACLE') {
        if (touching) {
          if (this.shieldTimeLeft > 0) {
            this.onSfx('SHIELD_HIT');
            this.entities.splice(i, 1);
            continue;
          }
          this.onSfx('CRASH');
          this._endGame('COLLISION');
          return;
        }
      } else {
        const magnetPull = this.magnetTimeLeft > 0
          && entity.itemType === 'FUEL'
          && entity.x < CAR_X_UNITS + MAGNET_RANGE_UNITS
          && entity.x > CAR_X_UNITS - halfWidth;
        if (touching || magnetPull) {
          this._collect(entity);
          this.entities.splice(i, 1);
          continue;
        }
      }

      if (entity.x < -1.5) {
        this.entities.splice(i, 1);
      }
    }
  }

  _collect(entity) {
    if (entity.itemType === 'SHIELD') {
      this._bonusScore += POWERUP_SCORE_BONUS;
      this.shieldTimeLeft = SHIELD_DURATION_SECONDS;
      this.onSfx('POWERUP');
    } else if (entity.itemType === 'MAGNET') {
      this._bonusScore += POWERUP_SCORE_BONUS;
      this.magnetTimeLeft = MAGNET_DURATION_SECONDS;
      this.onSfx('POWERUP');
    } else {
      this._bonusScore += ITEM_SCORE_BONUS;
      this.fuel = Math.min(MAX_FUEL, this.fuel + FUEL_REFILL_AMOUNT);
      this.fuelCollected++;
      this.onSfx('COIN');
    }
  }

  _isOverlappingCar(entityX, halfWidth) {
    const dx = entityX - CAR_X_UNITS;
    return dx > -halfWidth && dx < halfWidth;
  }

  _spawnEntity() {
    // Never repeat the lane an entity just spawned in — a uniform pick has a flat
    // 1-in-LANE_COUNT chance of repeating, which reads as "the same lane keeps
    // getting stuff" far more often than it should over a run.
    let lane;
    if (this._lastSpawnLane >= 0 && this._lastSpawnLane < LANE_COUNT) {
      const choice = Math.floor(this.random() * (LANE_COUNT - 1));
      lane = choice < this._lastSpawnLane ? choice : choice + 1;
    } else {
      lane = Math.floor(this.random() * LANE_COUNT);
    }
    this._lastSpawnLane = lane;
    const x = WORLD_WIDTH_UNITS * 1.05;
    const id = this._nextEntityId++;
    const roll = this.random();
    if (roll < POWERUP_SPAWN_CHANCE) {
      const itemType = this.random() < 0.5 ? 'SHIELD' : 'MAGNET';
      this.entities.push({ id, lane, x, kind: 'ITEM', itemType });
    } else if (roll < POWERUP_SPAWN_CHANCE + ITEM_SPAWN_CHANCE) {
      this.entities.push({ id, lane, x, kind: 'ITEM', itemType: 'FUEL' });
    } else {
      const variant = WEIGHTED_OBSTACLE_VARIANTS[Math.floor(this.random() * WEIGHTED_OBSTACLE_VARIANTS.length)];
      this.entities.push({ id, lane, x, kind: 'OBSTACLE', variant, nearMissAwarded: false });
    }
  }

  _endGame(reason) {
    this.status = 'GAME_OVER';
    this.gameOverReason = reason;
  }
}
