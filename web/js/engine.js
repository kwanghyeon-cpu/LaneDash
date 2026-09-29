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

const START_SPEED = 4.5;
const MAX_SPEED = 12;
const SPEED_RAMP_PER_SECOND = 0.3;
const MIN_SPAWN_INTERVAL = 0.55;
const MAX_SPAWN_INTERVAL = 1.35;
const ITEM_SPAWN_CHANCE = 0.15;
const ITEM_SCORE_BONUS = 50;
const MAX_FUEL = 100;
const FUEL_DRAIN_PER_SECOND = 4.5;
const FUEL_REFILL_AMOUNT = 26;

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
  constructor(onSfx) {
    this.onSfx = onSfx || (() => {});
    this.status = 'READY';
    this.laneIndex = Math.floor(LANE_COUNT / 2);
    this.distance = 0;
    this.speed = START_SPEED;
    this.score = 0;
    this.fuel = MAX_FUEL;
    this.gameOverReason = null;
    this.entities = [];
    this._spawnCooldown = 1.2;
    this._nextEntityId = 0;
    this._fuelWarningPlayed = false;
    this._lastSpawnLane = -1;
  }

  start() {
    this.entities = [];
    this.laneIndex = Math.floor(LANE_COUNT / 2);
    this.distance = 0;
    this.speed = START_SPEED;
    this.score = 0;
    this.fuel = MAX_FUEL;
    this._spawnCooldown = 1.0;
    this.gameOverReason = null;
    this._fuelWarningPlayed = false;
    this._lastSpawnLane = -1;
    this.status = 'RUNNING';
  }

  moveLaneUp() {
    if (this.status !== 'RUNNING') return;
    const next = Math.max(0, this.laneIndex - 1);
    if (next !== this.laneIndex) this.onSfx('LANE_CHANGE');
    this.laneIndex = next;
  }

  moveLaneDown() {
    if (this.status !== 'RUNNING') return;
    const next = Math.min(LANE_COUNT - 1, this.laneIndex + 1);
    if (next !== this.laneIndex) this.onSfx('LANE_CHANGE');
    this.laneIndex = next;
  }

  tick(dtSeconds) {
    if (this.status !== 'RUNNING') return;
    const dt = Math.min(dtSeconds, 0.05);

    this.speed = Math.min(MAX_SPEED, this.speed + SPEED_RAMP_PER_SECOND * dt);
    this.distance += this.speed * dt;
    this.score = Math.floor(this.distance * 10);

    this.fuel = Math.max(0, this.fuel - FUEL_DRAIN_PER_SECOND * dt);
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
      this._spawnCooldown = MAX_SPAWN_INTERVAL - (MAX_SPAWN_INTERVAL - MIN_SPAWN_INTERVAL) * speedFactor;
    }

    for (let i = this.entities.length - 1; i >= 0; i--) {
      const entity = this.entities[i];
      entity.x -= this.speed * dt;

      // Scaled by the entity's own lane depth (see laneSizeScale) — the player sits in
      // that same lane when this fires, so both shrink together and the "touch" point
      // this half-width was calibrated at stays correct at any lane.
      const halfWidth = (entity.kind === 'OBSTACLE'
        ? entity.variant.hitHalfWidthUnits
        : HITBOX_HALF_WIDTH) * laneSizeScale(entity.lane);

      if (entity.lane === this.laneIndex && this._isOverlappingCar(entity.x, halfWidth)) {
        if (entity.kind === 'OBSTACLE') {
          this.onSfx('CRASH');
          this._endGame('COLLISION');
          return;
        } else {
          this.score += ITEM_SCORE_BONUS;
          this.fuel = Math.min(MAX_FUEL, this.fuel + FUEL_REFILL_AMOUNT);
          this.onSfx('COIN');
          this.entities.splice(i, 1);
          continue;
        }
      }

      if (entity.x < -1.5) {
        this.entities.splice(i, 1);
      }
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
      const choice = Math.floor(Math.random() * (LANE_COUNT - 1));
      lane = choice < this._lastSpawnLane ? choice : choice + 1;
    } else {
      lane = Math.floor(Math.random() * LANE_COUNT);
    }
    this._lastSpawnLane = lane;
    const spawnItem = Math.random() < ITEM_SPAWN_CHANCE;
    if (spawnItem) {
      this.entities.push({ id: this._nextEntityId++, lane, x: WORLD_WIDTH_UNITS * 1.05, kind: 'ITEM' });
    } else {
      const variant = WEIGHTED_OBSTACLE_VARIANTS[Math.floor(Math.random() * WEIGHTED_OBSTACLE_VARIANTS.length)];
      this.entities.push({ id: this._nextEntityId++, lane, x: WORLD_WIDTH_UNITS * 1.05, kind: 'OBSTACLE', variant });
    }
  }

  _endGame(reason) {
    this.status = 'GAME_OVER';
    this.gameOverReason = reason;
  }
}
