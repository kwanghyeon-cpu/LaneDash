// Behind-the-car "highway" mode — the simulation half. Ported from
// app/src/main/java/.../game/highway/HighwayEngine.kt and HighwayRoad.kt; keep the numbers in
// sync by hand. Pure logic (no DOM, no clocks): time only advances through tick(dt), and the
// random source is injectable, so node --test can drive it deterministically.

const HW_LANE_COUNT = 5;
/** Width of one lane in road half-widths (the whole road is 2). */
const HW_LANE_SPACING = 2 / HW_LANE_COUNT;
/** How far ahead (world units) the road is drawn and traffic is spawned. */
const HW_DRAW_DISTANCE = 60;
/** Depth, in world units, shown at the bottom screen row. */
const HW_D0 = 1;
/** Where the player's car sits, as a fraction of the road height measured from the horizon. */
const HW_PLAYER_T = 0.80;
/** Distance ahead of the camera at which the player's car is — traffic here is a collision. */
const HW_PLAYER_DZ = HW_D0 / HW_PLAYER_T;
/** The horizon's height as a fraction of the screen height. */
const HW_HORIZON_FRACTION = 0.36;
/** Lateral centers of the traffic lanes (road half-widths), left to right. */
const HW_LANE_CENTERS = Array.from({ length: HW_LANE_COUNT }, (_, i) => -1 + HW_LANE_SPACING * (i + 0.5));

/**
 * Road curvature at world distance z: smooth S-bends with straight stretches (the envelope
 * dips toward zero now and then). Positive bends the road to the right.
 */
function curveAt(z) {
  const a = Math.sin(z / 55);
  const b = Math.sin(z / 23 + 1.3);
  const envelope = Math.sin(z / 140) * 0.5 + 0.5;
  return (0.011 * a + 0.005 * b) * (0.25 + 0.75 * envelope);
}

/**
 * The visible road as horizontal strips from the bottom edge up to the horizon, recomputed every
 * frame into preallocated arrays. Screen row fraction t (0 at the horizon, 1 at the bottom) maps
 * to depth dz = HW_D0 / t; the road's half-width is proportional to t.
 */
class RoadStrips {
  constructor(capacity = 200) {
    this.capacity = capacity;
    this.count = 0;
    this.yTop = new Float32Array(capacity);
    this.yBottom = new Float32Array(capacity);
    /** Screen x of the road's center line in this strip (curve and camera offset applied). */
    this.centerX = new Float32Array(capacity);
    /** Screen pixels per road half-width in this strip. */
    this.halfWidth = new Float32Array(capacity);
    this.dz = new Float32Array(capacity);
    /** 0 or 1: which of the two alternating stripe shades this strip uses. */
    this.band = new Uint8Array(capacity);
  }

  compute(cameraZ, cameraX, screenWidth, horizonY, bottomY, rowHeight, stripeLength) {
    const roadHeight = bottomY - horizonY;
    const hw0 = screenWidth * 0.5;
    let n = 0;
    let prevDz = 0;
    let dxAcc = 0;
    let xOff = 0;
    let y = bottomY;
    while (n < this.capacity && y - rowHeight > horizonY + 1) {
      const t = ((y - rowHeight / 2) - horizonY) / roadHeight;
      const d = HW_D0 / t;
      const step = d - prevDz;
      // Double integral of the curvature: the road's lateral drift grows with distance.
      dxAcc += curveAt(cameraZ + d) * step;
      xOff += dxAcc * step;
      prevDz = d;

      this.yBottom[n] = y;
      this.yTop[n] = y - rowHeight;
      this.halfWidth[n] = hw0 * t;
      this.centerX[n] = screenWidth / 2 + (xOff - cameraX) * this.halfWidth[n];
      this.dz[n] = d;
      this.band[n] = Math.floor((cameraZ + d) / stripeLength) & 1;
      n++;
      y -= rowHeight;
    }
    this.count = n;
  }

  /** Index of the strip containing screen row y, clamped to the valid range. */
  indexForY(y, bottomY, rowHeight) {
    return Math.min(Math.max(0, this.count - 1), Math.max(0, Math.floor((bottomY - y) / rowHeight)));
  }
}

/**
 * Kinds of traffic ahead; wider/taller ones are slower. halfWidth is in road half-widths and
 * heightFactor (height / width) matches the rear-view sprite.
 */
const TRAFFIC_KINDS = {
  SEDAN: { name: 'SEDAN', halfWidth: 0.14, heightFactor: 0.74, minSpeed: 9, maxSpeed: 13, sprite: 'assets/rear/traffic_rear_sedan.png', color: '#8D99AE' },
  SUV: { name: 'SUV', halfWidth: 0.15, heightFactor: 0.88, minSpeed: 8.5, maxSpeed: 12.5, sprite: 'assets/rear/traffic_rear_suv.png', color: '#5C6478' },
  BUS: { name: 'BUS', halfWidth: 0.19, heightFactor: 1.05, minSpeed: 7, maxSpeed: 10.5, sprite: 'assets/rear/traffic_rear_bus.png', color: '#A7AFBF' },
  TRUCK: { name: 'TRUCK', halfWidth: 0.18, heightFactor: 1.29, minSpeed: 6, maxSpeed: 9.5, sprite: 'assets/rear/traffic_rear_truck.png', color: '#7F8CA8' },
};

const HW = {
  START_SPEED: 14,
  /** Top speed at the start of a run; it climbs to MAX_SPEED over RAMP_SECONDS. */
  EARLY_TOP_SPEED: 22,
  MAX_SPEED: 30,
  RAMP_SECONDS: 120,
  ACCELERATION: 1.2,
  /** The lane-chasing spring: ~22 rad/s, damping ratio ~0.7 — a lane takes ~0.15 s, a few percent overshoot. */
  LANE_SPRING: 480,
  LANE_DAMPING: 31,
  LATERAL_STEP: 1 / 120,
  CENTRIFUGAL: 40,
  /** Player's drawn width and its (slightly smaller) hitbox half-width, in road half-widths. */
  PLAYER_DRAW_WIDTH: 0.28,
  PLAYER_HALF_WIDTH: 0.125,
  COLLISION_DEPTH: 0.45,
  REMOVE_BEHIND: 4,
  SCORE_PER_UNIT: 2.4,
  NEAR_MISS_SCORE: 50,
  /** Extra lateral distance beyond touching that still counts as a near miss. */
  NEAR_MISS_MARGIN: 0.10,
  NEAR_MISS_FLASH_SECONDS: 0.9,
  // Traffic density: seconds between spawn attempts, easy -> hard.
  SPAWN_EASY_INTERVAL: 1.2,
  SPAWN_HARD_INTERVAL: 0.35,
  SPAWN_RETRY_INTERVAL: 0.08,
  // Minimum seconds between two cars passing the player in the same lane, easy -> hard.
  SEPARATION_EASY: 0.5,
  SEPARATION_HARD: 0.22,
  // Extra seconds required per lane of distance between two cars, easy -> hard.
  LANE_GAP_EASY: 0.3,
  LANE_GAP_HARD: 0.17,
  MIN_CLOSING_SPEED: 1,
};

/** Seconds two cars laneDistance lanes apart must pass the player apart at the given difficulty. */
function requiredGapSeconds(laneDistance, difficulty) {
  const separation = HW.SEPARATION_EASY + (HW.SEPARATION_HARD - HW.SEPARATION_EASY) * difficulty;
  const perLane = HW.LANE_GAP_EASY + (HW.LANE_GAP_HARD - HW.LANE_GAP_EASY) * difficulty;
  return separation + perLane * laneDistance;
}

function laneIndexOf(lateral) {
  let best = 0;
  for (let i = 1; i < HW_LANE_CENTERS.length; i++) {
    if (Math.abs(HW_LANE_CENTERS[i] - lateral) < Math.abs(HW_LANE_CENTERS[best] - lateral)) best = i;
  }
  return best;
}

class HighwayEngine {
  /** onSfx(eventName), random() -> [0,1) (inject a seeded one in tests). */
  constructor(onSfx = () => {}, random = Math.random) {
    this.onSfx = onSfx;
    this.random = random;
    this.status = 'READY';
    this.paused = false;
    this.gameOverReason = null;
    this.traffic = [];
    this.nextId = 0; // not reset by start(), so ids stay unique across restarts
    this._reset();
  }

  /** -1..1: how far the car still has to travel to its lane; the renderer leans the sprite by it. */
  get steerLean() {
    return Math.min(1, Math.max(-1, (HW_LANE_CENTERS[this.laneIndex] - this.playerX) / HW_LANE_SPACING));
  }

  _reset() {
    this.traffic = [];
    this.z = 0; // camera position along the road (world units)
    this.laneIndex = HW_LANE_CENTERS.length >> 1; // lane the player is heading for
    this.playerX = HW_LANE_CENTERS[this.laneIndex]; // lateral position in road half-widths (a spring chasing that lane)
    this.lateralVelocity = 0;
    this.speed = HW.START_SPEED;
    this.score = 0;
    this.bonusScore = 0;
    this.nearMissCount = 0;
    this.nearMissFlash = 0; // seconds left of the "NEAR MISS" flash
    this.elapsedSeconds = 0;
    this.heading = 0; // accumulated road heading; the renderer scrolls the skyline by it
    this.spawnCooldown = 1;
  }

  start() {
    this._reset();
    this.status = 'RUNNING';
    this.paused = false;
    this.gameOverReason = null;
  }

  pause() { if (this.status === 'RUNNING') this.paused = true; }
  resume() { this.paused = false; }

  /** Heads one lane left (-1) or right (+1); the car glides there in a fraction of a second. */
  moveLane(delta) {
    if (this.status !== 'RUNNING' || this.paused) return;
    this.laneIndex = Math.min(HW_LANE_CENTERS.length - 1, Math.max(0, this.laneIndex + delta));
  }

  tick(dtSeconds) {
    if (this.status !== 'RUNNING' || this.paused) return;
    const dt = Math.min(dtSeconds, 0.05);
    this.elapsedSeconds += dt;
    if (this.nearMissFlash > 0) this.nearMissFlash = Math.max(0, this.nearMissFlash - dt);

    // Difficulty eases from 0 to 1 over the first RAMP_SECONDS of a run, then stays maxed.
    const difficulty = Math.min(1, Math.max(0, this.elapsedSeconds / HW.RAMP_SECONDS));

    // Speed: auto-accelerates toward a top speed that creeps up with difficulty.
    const topSpeed = HW.EARLY_TOP_SPEED + (HW.MAX_SPEED - HW.EARLY_TOP_SPEED) * difficulty;
    this.speed = Math.min(topSpeed, this.speed + HW.ACCELERATION * dt);
    this.z += this.speed * dt;
    this.heading += curveAt(this.z) * this.speed * dt;

    // Chase the target lane with a damped spring (small sub-steps keep it stable), while bends in
    // the road push the car outward.
    const laneX = HW_LANE_CENTERS[this.laneIndex];
    const steps = Math.max(1, Math.ceil(dt / HW.LATERAL_STEP));
    const h = dt / steps;
    const push = curveAt(this.z + HW_PLAYER_DZ) * (this.speed / HW.MAX_SPEED) * HW.CENTRIFUGAL;
    for (let i = 0; i < steps; i++) {
      this.lateralVelocity += (HW.LANE_SPRING * (laneX - this.playerX) - HW.LANE_DAMPING * this.lateralVelocity) * h;
      this.playerX += (this.lateralVelocity - push) * h;
    }

    this.spawnCooldown -= dt;
    if (this.spawnCooldown <= 0) {
      this.spawnCooldown = this._spawnTraffic(difficulty)
        ? (HW.SPAWN_EASY_INTERVAL + (HW.SPAWN_HARD_INTERVAL - HW.SPAWN_EASY_INTERVAL) * difficulty) *
          (0.7 + 0.6 * this.random())
        : HW.SPAWN_RETRY_INTERVAL;
    }

    for (let i = this.traffic.length - 1; i >= 0; i--) {
      const car = this.traffic[i];
      car.z += car.speed * dt;
      const relative = car.z - (this.z + HW_PLAYER_DZ);
      const lateral = Math.abs(car.lx - this.playerX);
      if (Math.abs(relative) < HW.COLLISION_DEPTH && lateral < HW.PLAYER_HALF_WIDTH + car.kind.halfWidth) {
        this.onSfx('CRASH');
        this.status = 'GAME_OVER';
        this.gameOverReason = 'COLLISION';
        this.score = this._currentScore();
        return;
      }
      if (!car.passed && relative < -HW.COLLISION_DEPTH) {
        car.passed = true;
        if (lateral < HW.PLAYER_HALF_WIDTH + car.kind.halfWidth + HW.NEAR_MISS_MARGIN) {
          this.bonusScore += HW.NEAR_MISS_SCORE;
          this.nearMissCount++;
          this.nearMissFlash = HW.NEAR_MISS_FLASH_SECONDS;
          this.onSfx('NEAR_MISS');
        }
      }
      if (relative < -HW.REMOVE_BEHIND) this.traffic.splice(i, 1);
    }
    this.score = this._currentScore();
  }

  _currentScore() { return Math.floor(this.z * HW.SCORE_PER_UNIT) + this.bonusScore; }

  /** When car will reach the player's position at the current speeds (0 if it already has). */
  _secondsUntilPassing(car) {
    const gap = car.z - (this.z + HW_PLAYER_DZ);
    if (gap <= 0) return 0;
    return gap / Math.max(HW.MIN_CLOSING_SPEED, this.speed - car.speed);
  }

  /**
   * Adds one car, or none if no lane can take it fairly. A lane change takes ~half a second, so two
   * cars in different lanes must pass the player at least that far apart (scaled with the lane
   * distance) — otherwise random spawns create walls nobody can dodge. The required separation
   * tightens as difficulty rises. Returns whether a car was added.
   */
  _spawnTraffic(difficulty) {
    const pick = Math.floor(this.random() * 20);
    const kind = pick <= 7 ? TRAFFIC_KINDS.SEDAN : pick <= 13 ? TRAFFIC_KINDS.SUV : pick <= 16 ? TRAFFIC_KINDS.BUS : TRAFFIC_KINDS.TRUCK;
    const carSpeed = kind.minSpeed + (kind.maxSpeed - kind.minSpeed) * this.random();
    const spawnZ = this.z + HW_DRAW_DISTANCE - 1;
    const passIn = (spawnZ - (this.z + HW_PLAYER_DZ)) / Math.max(HW.MIN_CLOSING_SPEED, this.speed - carSpeed);

    const lanes = HW_LANE_CENTERS.map((_, i) => i);
    for (let i = lanes.length - 1; i > 0; i--) { // Fisher-Yates
      const j = Math.floor(this.random() * (i + 1));
      [lanes[i], lanes[j]] = [lanes[j], lanes[i]];
    }
    for (const lane of lanes) {
      const fair = !this.traffic.some((other) => {
        if (other.passed) return false;
        return Math.abs(this._secondsUntilPassing(other) - passIn) < requiredGapSeconds(Math.abs(lane - laneIndexOf(other.lx)), difficulty);
      });
      if (!fair) continue;
      this.traffic.push({ id: this.nextId++, lx: HW_LANE_CENTERS[lane], z: spawnZ, speed: carSpeed, kind, passed: false });
      return true;
    }
    return false;
  }
}
