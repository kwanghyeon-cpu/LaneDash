// Run with: node --test web/test/highway.test.mjs
// Mirrors app/src/test/.../highway/HighwayTest.kt and HighwayBalanceTest.kt. Pure simulation:
// time only advances through tick(dt), and the random source is seeded.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const jsDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'js');

function loadApi() {
  const src = fs.readFileSync(path.join(jsDir, 'highway-engine.js'), 'utf8');
  const ctx = vm.createContext({ Math, console });
  vm.runInContext(
    src + '\n;globalThis.__api = { HighwayEngine, RoadStrips, HW, TRAFFIC_KINDS, HW_PLAYER_DZ, HW_LANE_CENTERS, HW_LANE_SPACING, HW_HORIZON_FRACTION, curveAt, requiredGapSeconds };',
    ctx,
  );
  return ctx.__api;
}

const { HighwayEngine, RoadStrips, HW, TRAFFIC_KINDS, HW_PLAYER_DZ, HW_LANE_CENTERS, HW_LANE_SPACING, HW_HORIZON_FRACTION, curveAt, requiredGapSeconds } = loadApi();

/** Small seeded PRNG so every run is reproducible. */
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------- road ----------
const W = 1080, H = 2220, ROW = 12;
const horizon = H * HW_HORIZON_FRACTION;
function strips(cameraZ = 100, cameraX = 0) {
  const s = new RoadStrips();
  s.compute(cameraZ, cameraX, W, horizon, H, ROW, 4);
  return s;
}

test('strips run from the bottom edge up to the horizon and shrink with distance', () => {
  const s = strips();
  assert.ok(s.count > 50);
  assert.equal(s.yBottom[0], H);
  assert.ok(s.yTop[s.count - 1] > horizon);
  for (let i = 1; i < s.count; i++) {
    assert.ok(s.yBottom[i] < s.yBottom[i - 1], 'rows go up the screen');
    assert.ok(s.halfWidth[i] < s.halfWidth[i - 1], 'road narrows with distance');
    assert.ok(s.dz[i] > s.dz[i - 1], 'depth grows');
  }
});

test('camera offset slides the whole road one half-width per unit', () => {
  const a = strips(100, 0);
  const b = strips(100, 1);
  for (let i = 0; i < a.count; i++) assert.ok(Math.abs(a.centerX[i] - a.halfWidth[i] - b.centerX[i]) < 0.05);
});

test('indexForY maps rows to strips and clamps', () => {
  const s = strips();
  assert.equal(s.indexForY(H, H, ROW), 0);
  assert.equal(s.indexForY(0, H, ROW), s.count - 1);
  assert.equal(s.indexForY(H - 30, H, ROW), 2);
});

test('curvature stays gentle', () => {
  for (let z = 0; z < 2000; z += 3) assert.ok(Math.abs(curveAt(z)) < 0.02);
});

// ---------- engine ----------
let engine;
let sfx;
let nextId;

beforeEach(() => {
  sfx = [];
  nextId = 500;
  engine = new HighwayEngine((e) => sfx.push(e), mulberry32(7));
  engine.start();
});

function addTraffic(lx, relativeZ, speed = 8, kind = TRAFFIC_KINDS.SEDAN) {
  const car = { id: nextId++, lx, z: engine.z + HW_PLAYER_DZ + relativeZ, speed, kind, passed: false };
  engine.traffic.push(car);
  return car;
}

function tickUntilPassed(car) {
  let guard = 0;
  while (!car.passed && engine.status === 'RUNNING' && guard++ < 400) engine.tick(0.016);
}

test('starts running and accelerates forward', () => {
  assert.equal(engine.status, 'RUNNING');
  for (let i = 0; i < 20; i++) engine.tick(0.05);
  assert.ok(engine.z > 0);
  assert.ok(engine.speed > HW.START_SPEED);
});

test('colliding with traffic in your lane ends the run', () => {
  addTraffic(engine.playerX, 0);
  engine.tick(0.016);
  assert.equal(engine.status, 'GAME_OVER');
  assert.deepEqual(sfx, ['CRASH']);
});

test('traffic in another lane is harmless', () => {
  addTraffic(0.8, 0);
  engine.tick(0.016);
  assert.equal(engine.status, 'RUNNING');
});

test('steering moves the car, and the grass clamps and slows it', () => {
  engine.steerInput = 1;
  for (let i = 0; i < 20; i++) engine.tick(0.05);
  assert.ok(engine.playerX > 0.3, 'steered right');
  for (let i = 0; i < 80; i++) engine.tick(0.05);
  assert.equal(engine.playerX, HW.MAX_LATERAL);
  assert.ok(engine.offRoad);
  assert.ok(engine.speed <= HW.OFFROAD_SPEED + 0.5, 'grass drags the speed down');
});

test('pause freezes the simulation', () => {
  engine.tick(0.05);
  const z = engine.z;
  engine.pause();
  engine.tick(0.05);
  assert.equal(engine.z, z);
  engine.resume();
  engine.tick(0.05);
  assert.ok(engine.z > z);
});

test('traffic spawns ahead and is removed once behind', () => {
  for (let i = 0; i < 40; i++) engine.tick(0.05);
  assert.ok(engine.traffic.length > 0);
  engine.traffic.length = 0;
  addTraffic(0.8, -HW.REMOVE_BEHIND - 1);
  engine.tick(0.016);
  assert.ok(engine.traffic.every((c) => c.lx !== 0.8));
});

test('restart resets the run', () => {
  for (let i = 0; i < 10; i++) engine.tick(0.05);
  engine.start();
  assert.equal(engine.z, 0);
  assert.equal(engine.traffic.length, 0);
  assert.equal(engine.score, 0);
});

test('passing close to a car earns a near-miss bonus', () => {
  const car = addTraffic(0.3, 1); // not touching (0.265), but inside the near-miss margin
  tickUntilPassed(car);
  assert.equal(engine.status, 'RUNNING');
  assert.equal(engine.score, Math.floor(engine.z * HW.SCORE_PER_UNIT) + HW.NEAR_MISS_SCORE);
  assert.equal(engine.nearMissCount, 1);
  assert.ok(sfx.includes('NEAR_MISS'));
});

test('passing far from a car earns nothing extra', () => {
  const car = addTraffic(0.95, 1);
  tickUntilPassed(car);
  assert.equal(engine.score, Math.floor(engine.z * HW.SCORE_PER_UNIT));
  assert.equal(engine.nearMissCount, 0);
});

test('a near miss flashes briefly then fades', () => {
  tickUntilPassed(addTraffic(0.3, 1));
  assert.ok(Math.abs(engine.nearMissFlash - HW.NEAR_MISS_FLASH_SECONDS) < 0.05);
  for (let i = 0; i < 70; i++) { engine.traffic.length = 0; engine.tick(0.016); }
  assert.equal(engine.nearMissFlash, 0);
});

test('the car leans into the turn', () => {
  assert.equal(engine.steerLean, 0);
  engine.steerInput = 1;
  for (let i = 0; i < 10; i++) engine.tick(0.016);
  assert.ok(engine.steerLean > 0.5);
  engine.steerInput = -1;
  for (let i = 0; i < 30; i++) engine.tick(0.016);
  assert.ok(engine.steerLean < -0.5);
});

test('traffic is spawned fairly: never a wall nobody could dodge', () => {
  const seen = new Set();
  for (let step = 0; step < 6000; step++) { // 100 s at 60 fps, steering nothing
    engine.traffic = engine.traffic.filter((c) => c.z >= engine.z + HW_PLAYER_DZ - 1);
    engine.tick(1 / 60);
    if (engine.status !== 'RUNNING') engine.start();
    const difficulty = Math.min(1, Math.max(0, engine.elapsedSeconds / HW.RAMP_SECONDS));
    const passIn = (c) => (c.z - engine.z - HW_PLAYER_DZ) / Math.max(1, engine.speed - c.speed);
    const laneOf = (c) => HW_LANE_CENTERS.reduce((b, v, i) => (Math.abs(v - c.lx) < Math.abs(HW_LANE_CENTERS[b] - c.lx) ? i : b), 0);
    for (const fresh of engine.traffic.filter((c) => !seen.has(c.id))) {
      seen.add(fresh.id);
      for (const other of engine.traffic) {
        if (other === fresh || other.id > fresh.id || other.passed) continue;
        const needed = requiredGapSeconds(Math.abs(laneOf(fresh) - laneOf(other)), difficulty);
        assert.ok(Math.abs(passIn(fresh) - passIn(other)) >= needed - 0.05, `cars ${other.id}/${fresh.id} pass too close together`);
      }
    }
  }
  assert.ok(seen.size > 20);
});

test('five lanes, and every car fits its lane', () => {
  assert.equal(HW_LANE_CENTERS.length, 5);
  const widest = Math.max(...Object.values(TRAFFIC_KINDS).map((k) => k.halfWidth));
  assert.ok(HW.PLAYER_HALF_WIDTH + widest < HW_LANE_SPACING, 'an adjacent-lane car never touches');
  assert.ok(HW.PLAYER_DRAW_WIDTH < HW_LANE_SPACING, 'a drawn car fits inside a lane');
});

test('a full steer crosses one lane fast enough to dodge', () => {
  engine.steerInput = 1;
  let seconds = 0;
  while (engine.playerX < HW_LANE_SPACING && seconds < 2) { engine.tick(0.016); seconds += 0.016; }
  assert.ok(seconds < 0.4, 'a lane takes ' + seconds + 's');
});

test('the top speed climbs with the run', () => {
  for (let i = 0; i < 100 * 20; i++) { engine.traffic.length = 0; engine.tick(0.05); } // 100 s, no obstacles
  assert.ok(engine.speed > HW.EARLY_TOP_SPEED + 8);
  assert.ok(engine.speed <= HW.MAX_SPEED);
});

// ---------- balance: scripted players with different reaction times ----------
// Targets (median survival): casual (0.5 s) 20-60 s, average (0.3 s) 60-150 s, and even the
// expert (0.15 s) should usually die before 5 minutes. The bots are steadier than people.
function makeBot(eng, reaction, safeSeconds = 1.6) {
  const pending = [];
  let target = 0;
  let nextLook = 0;
  const timeToImpact = (laneX) => {
    let best = 99;
    const playerZ = eng.z + HW_PLAYER_DZ;
    for (const car of eng.traffic) {
      if (Math.abs(car.lx - laneX) > HW_LANE_SPACING / 2) continue;
      const gap = car.z - playerZ;
      if (gap < -1) continue;
      const closing = eng.speed - car.speed;
      const t = gap <= 0 ? 0 : closing > 0.1 ? gap / closing : 99;
      if (t < best) best = t;
    }
    return best;
  };
  const chooseTarget = () => {
    const here = HW_LANE_CENTERS.map((_, i) => i).reduce((b, i) => (Math.abs(HW_LANE_CENTERS[i] - eng.playerX) < Math.abs(HW_LANE_CENTERS[b] - eng.playerX) ? i : b), 0);
    const hereTime = timeToImpact(HW_LANE_CENTERS[here]);
    if (hereTime >= safeSeconds) return HW_LANE_CENTERS[here];
    let best = here;
    let bestScore = hereTime;
    for (const candidate of HW_LANE_CENTERS.keys()) {
      if (candidate === here) continue;
      let clear = true;
      for (let lane = Math.min(here, candidate); lane <= Math.max(here, candidate); lane++) {
        if (lane !== here && !(timeToImpact(HW_LANE_CENTERS[lane]) > Math.abs(lane - here) * 0.25 + 0.3)) clear = false;
      }
      const score = timeToImpact(HW_LANE_CENTERS[candidate]) - Math.abs(candidate - here) * 0.2;
      if (clear && score > bestScore) { best = candidate; bestScore = score; }
    }
    return HW_LANE_CENTERS[best];
  };
  return (now) => {
    if (now >= nextLook) {
      nextLook = now + 0.1;
      pending.push([now + reaction, chooseTarget()]);
    }
    while (pending.length && pending[0][0] <= now) target = pending.shift()[1];
    const dx = target - eng.playerX;
    eng.steerInput = dx > 0.04 ? 1 : dx < -0.04 ? -1 : 0;
  };
}

function medianSurvival(reaction, runs = 120, cap = 300) {
  const seconds = [];
  for (let seed = 1; seed <= runs; seed++) {
    const eng = new HighwayEngine(() => {}, mulberry32(seed));
    eng.start();
    const act = makeBot(eng, reaction);
    let now = 0;
    while (eng.status === 'RUNNING' && now < cap) {
      act(now);
      eng.tick(1 / 60);
      now += 1 / 60;
    }
    seconds.push(now);
  }
  seconds.sort((a, b) => a - b);
  const median = seconds[runs >> 1];
  console.log(`BALANCE reaction=${reaction} p10=${seconds[runs / 10 | 0].toFixed(0)}s p50=${median.toFixed(0)}s p90=${seconds[runs * 9 / 10 | 0].toFixed(0)}s`);
  return median;
}

test('survival follows skill, and nobody is immortal or doomed', () => {
  const expert = medianSurvival(0.15);
  const average = medianSurvival(0.30);
  const casual = medianSurvival(0.50);
  assert.ok(expert > average && average > casual, 'faster reactions survive longer');
  assert.ok(casual >= 20 && casual <= 60, `casual median ${casual}s`);
  assert.ok(average >= 60 && average <= 150, `average median ${average}s`);
  assert.ok(expert < 300, `expert median ${expert}s`);
});
