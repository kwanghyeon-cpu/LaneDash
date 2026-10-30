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

function addItem(type, lx, relativeZ) {
  const item = { id: nextId++, lx, z: engine.z + HW_PLAYER_DZ + relativeZ, type };
  engine.items.push(item);
  return item;
}

/** Runs the road with a "ghost" player: nothing can end the run (fuel is kept full and a crash is undone). */
function ghostRun(seed, seconds, onTick) {
  const e = new HighwayEngine(() => {}, mulberry32(seed));
  e.start();
  for (let i = 0; i < seconds * 60; i++) {
    if (e.status !== 'RUNNING') { e.status = 'RUNNING'; e.gameOverReason = null; }
    e.fuel = HW.MAX_FUEL;
    e.tick(1 / 60);
    onTick(e, i / 60);
  }
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

test('starts in the middle lane of five', () => {
  assert.equal(HW_LANE_CENTERS.length, 5);
  assert.equal(engine.laneIndex, 2);
  assert.ok(Math.abs(engine.playerX) < 1e-4);
});

test('the car leans into a move, then settles', () => {
  assert.equal(engine.steerLean, 0);
  engine.moveLane(1);
  engine.tick(0.016);
  assert.ok(engine.steerLean > 0.3);
  for (let i = 0; i < 60; i++) engine.tick(0.016);
  assert.ok(Math.abs(engine.steerLean) < 0.02);
  engine.moveLane(-1);
  engine.tick(0.016);
  assert.ok(engine.steerLean < -0.3);
});

test('a tap glides to the next lane fast enough to dodge, and settles on its center', () => {
  engine.moveLane(1);
  assert.equal(engine.laneIndex, 3);
  let seconds = 0;
  while (engine.playerX < HW_LANE_CENTERS[3] - 0.04 && seconds < 2) { engine.tick(0.016); seconds += 0.016; }
  assert.ok(seconds < 0.3, 'a lane takes ' + seconds + 's');
  for (let i = 0; i < 60; i++) engine.tick(0.016);
  assert.ok(Math.abs(engine.playerX - HW_LANE_CENTERS[3]) < 0.02);
});

test('taps stack up to cross several lanes, and stop at the road edges', () => {
  engine.moveLane(1); engine.moveLane(1);
  assert.equal(engine.laneIndex, 4);
  for (let i = 0; i < 10; i++) engine.moveLane(1);
  assert.equal(engine.laneIndex, HW_LANE_CENTERS.length - 1);
  for (let i = 0; i < 10; i++) engine.moveLane(-1);
  assert.equal(engine.laneIndex, 0);
});

test('moves are ignored while paused', () => {
  engine.pause();
  engine.moveLane(1);
  assert.equal(engine.laneIndex, 2);
});

test('road bends push the car but it stays in its lane', () => {
  let widest = 0;
  for (let i = 0; i < 20 * 60; i++) {
    engine.traffic.length = 0;
    engine.items.length = 0;
    engine.fuel = HW.MAX_FUEL;
    engine.tick(1 / 60);
    widest = Math.max(widest, Math.abs(engine.playerX - HW_LANE_CENTERS[engine.laneIndex]));
  }
  assert.ok(widest > 0.005 && widest < HW_LANE_SPACING / 2, 'pushed ' + widest + ' off the lane center');
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

test('the top speed climbs with the run', () => {
  for (let i = 0; i < 100 * 20; i++) { // 100 s, no obstacles
    engine.traffic.length = 0;
    engine.items.length = 0;
    engine.fuel = HW.MAX_FUEL;
    engine.tick(0.05);
  }
  assert.ok(engine.speed > HW.EARLY_TOP_SPEED + 5);
  assert.ok(engine.speed <= HW.MAX_SPEED);
});

test('fuel drains, and an empty tank ends the run', () => {
  for (let i = 0; i < 60 * 30 && engine.status === 'RUNNING'; i++) { engine.traffic.length = 0; engine.items.length = 0; engine.tick(1 / 60); }
  assert.equal(engine.status, 'GAME_OVER');
  assert.equal(engine.gameOverReason, 'FUEL_EMPTY');
  assert.ok(sfx.includes('FUEL_EMPTY'));
  assert.ok(sfx.includes('FUEL_LOW_WARNING'));
});

test('a fuel can refuels you and scores', () => {
  engine.fuel = 50;
  addItem('FUEL', engine.playerX, 0);
  engine.tick(0.016);
  assert.ok(engine.fuel > 50 + HW.FUEL_REFILL_AMOUNT - 1);
  assert.equal(engine.fuelCollected, 1);
  assert.equal(engine.items.length, 0);
  assert.ok(sfx.includes('COIN'));
  assert.ok(engine.score >= HW.ITEM_SCORE_BONUS);
});

test('a pickup in another lane is left alone', () => {
  engine.fuel = 50;
  addItem('FUEL', HW_LANE_CENTERS[4], 0);
  engine.tick(0.016);
  assert.equal(engine.fuelCollected, 0);
});

test('the shield lets you plow through an obstacle, and then wears off', () => {
  addItem('SHIELD', engine.playerX, 0);
  engine.tick(0.016);
  assert.ok(Math.abs(engine.shieldTimeLeft - HW.SHIELD_DURATION_SECONDS) < 0.05);
  addTraffic(engine.playerX, 0);
  engine.tick(0.016);
  assert.equal(engine.status, 'RUNNING');
  assert.equal(engine.traffic.length, 0, 'the obstacle is smashed');
  assert.ok(sfx.includes('SHIELD_HIT'));
  for (let i = 0; i < 60 * 6.2; i++) { engine.traffic.length = 0; engine.items.length = 0; engine.fuel = HW.MAX_FUEL; engine.tick(1 / 60); }
  assert.equal(engine.shieldTimeLeft, 0);
  addTraffic(engine.playerX, 0);
  engine.tick(0.016);
  assert.equal(engine.status, 'GAME_OVER');
});

test('the magnet reels fuel cans in from other lanes', () => {
  addItem('MAGNET', engine.playerX, 0);
  engine.tick(0.016);
  assert.ok(engine.magnetTimeLeft > 7.9);
  engine.fuel = 40;
  addItem('FUEL', HW_LANE_CENTERS[4], 6); // two lanes' width away and still ahead
  for (let i = 0; i < 40 && engine.fuelCollected === 0; i++) engine.tick(1 / 60);
  assert.equal(engine.fuelCollected, 1);
});

test('without the magnet that far lane stays out of reach', () => {
  engine.fuel = 40;
  addItem('FUEL', HW_LANE_CENTERS[4], 6);
  for (let i = 0; i < 60 && engine.items.length > 0; i++) engine.tick(1 / 60);
  assert.equal(engine.fuelCollected, 0);
});

test('a near miss tops up a little fuel', () => {
  engine.fuel = 50;
  const car = addTraffic(0.3, 1);
  const before = engine.fuel;
  tickUntilPassed(car);
  const drained = HW.FUEL_DRAIN_PER_SECOND * engine.elapsedSeconds;
  assert.ok(Math.abs(engine.fuel - (before - drained + HW.NEAR_MISS_FUEL)) < 0.01);
});

test('the road is as busy as the classic mode: dense obstacles from early on, with pickups mixed in', () => {
  const obstacles = {}; const pickups = {};
  const seen = new Set(); const seenItems = new Set();
  const kinds = new Set(); let staticSpeeds = 0;
  for (let seed = 1; seed <= 20; seed++) {
    ghostRun(seed, 60, (e, t) => {
      const w = Math.floor(t / 20);
      for (const c of e.traffic) {
        const key = seed + ':' + c.id;
        if (!seen.has(key)) { seen.add(key); obstacles[w] = (obstacles[w] || 0) + 1; kinds.add(c.kind.name); if (c.speed === 0) staticSpeeds++; }
      }
      for (const c of e.items) {
        const key = seed + ':' + c.id;
        if (!seenItems.has(key)) { seenItems.add(key); pickups[w] = (pickups[w] || 0) + 1; }
      }
    });
  }
  const perSecond = (w) => obstacles[w] / 20 / 20;
  console.log('DENSITY obstacles/s by 20 s block: ' + [0, 1, 2].map((w) => perSecond(w).toFixed(2)).join(', ') +
    ' | pickups/s: ' + [0, 1, 2].map((w) => (pickups[w] / 20 / 20).toFixed(2)).join(', '));
  assert.ok(perSecond(0) >= 0.85, 'first 20 s: ' + perSecond(0));
  assert.ok(perSecond(1) >= 1.15, 'second 20 s: ' + perSecond(1));
  assert.ok(perSecond(2) >= 1.35, 'third 20 s: ' + perSecond(2));
  const share = (pickups[1] + pickups[2]) / (pickups[1] + pickups[2] + obstacles[1] + obstacles[2]);
  assert.ok(share > 0.12 && share < 0.32, 'pickup share ' + share);
  assert.ok(kinds.has('CONE') && kinds.has('BARRIER'), 'cones and barriers appear');
  assert.ok(staticSpeeds > 0, 'and they stand still');
});

// ---------- balance: scripted players with different reaction times ----------
// Targets (median survival): casual (0.5 s) 20-80 s, average (0.3 s) 60-150 s, and even the
// expert (0.15 s) should usually die before 5 minutes. The bots are steadier than people.
function makeBot(eng, reaction, safeSeconds = 1.6) {
  const pending = [];
  let target = HW_LANE_CENTERS.length >> 1;
  let nextLook = 0;
  let nextTap = 0;
  const TAP_INTERVAL = 0.14; // a finger can't tap faster than about this
  const CROSSING_SECONDS_PER_LANE = 0.2;
  const timeToImpact = (lane) => {
    let best = 99;
    const playerZ = eng.z + HW_PLAYER_DZ;
    for (const car of eng.traffic) {
      if (Math.abs(car.lx - HW_LANE_CENTERS[lane]) > HW_LANE_SPACING / 2) continue;
      const gap = car.z - playerZ;
      if (gap < -1) continue;
      const closing = eng.speed - car.speed;
      const t = gap <= 0 ? 0 : closing > 0.1 ? gap / closing : 99;
      if (t < best) best = t;
    }
    return best;
  };
  const lanes = HW_LANE_CENTERS.map((_, i) => i);
  const laneClearFor = (here, lane) => {
    for (let l = Math.min(here, lane); l <= Math.max(here, lane); l++) {
      if (l !== here && !(timeToImpact(l) > Math.abs(l - here) * CROSSING_SECONDS_PER_LANE + 0.3)) return false;
    }
    return true;
  };
  /** The lane of a pickup worth steering to (fuel when running low, power-ups always), if it can be reached safely. */
  const pickupLane = (here) => {
    const playerZ = eng.z + HW_PLAYER_DZ;
    let best = null;
    for (const item of eng.items) {
      const wanted = item.type === 'FUEL' ? eng.fuel < 85 : true;
      const gap = item.z - playerZ;
      if (!wanted || gap < 0 || gap > eng.speed * 3.2) continue;
      const lane = laneIndexOfX(item.lx);
      const arrival = gap / eng.speed;
      if (Math.abs(lane - here) * CROSSING_SECONDS_PER_LANE + 0.2 > arrival) continue;
      if (!laneClearFor(here, lane) || timeToImpact(lane) < arrival + 0.5) continue;
      if (!best || arrival < best.arrival) best = { lane, arrival };
    }
    return best ? best.lane : null;
  };
  const laneIndexOfX = (x) => lanes.reduce((b, i) => (Math.abs(HW_LANE_CENTERS[i] - x) < Math.abs(HW_LANE_CENTERS[b] - x) ? i : b), 0);
  const chooseTarget = () => {
    // Judge from the lane already commanded (the car follows within ~0.15 s) so decisions don't flip mid-move.
    const here = eng.laneIndex;
    const hereTime = timeToImpact(here);
    if (hereTime >= safeSeconds) {
      const lane = pickupLane(here);
      return lane === null ? here : lane;
    }
    let best = here;
    let bestScore = hereTime;
    for (const candidate of lanes) {
      if (candidate === here) continue;
      let clear = true;
      for (let lane = Math.min(here, candidate); lane <= Math.max(here, candidate); lane++) {
        if (lane !== here && !(timeToImpact(lane) > Math.abs(lane - here) * CROSSING_SECONDS_PER_LANE + 0.3)) clear = false;
      }
      const score = timeToImpact(candidate) - Math.abs(candidate - here) * 0.2;
      if (clear && score > bestScore) { best = candidate; bestScore = score; }
    }
    return best;
  };
  return (now) => {
    if (now >= nextLook) {
      nextLook = now + 0.1;
      pending.push([now + reaction, chooseTarget()]);
    }
    while (pending.length && pending[0][0] <= now) target = pending.shift()[1];
    if (target !== eng.laneIndex && now >= nextTap) {
      eng.moveLane(target > eng.laneIndex ? 1 : -1);
      nextTap = now + TAP_INTERVAL;
    }
  };
}

function medianSurvival(reaction, runs = 120, cap = 300) {
  const seconds = [];
  const reasons = { COLLISION: 0, FUEL_EMPTY: 0, alive: 0 };
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
    reasons[eng.status === 'RUNNING' ? 'alive' : eng.gameOverReason]++;
  }
  seconds.sort((a, b) => a - b);
  const median = seconds[runs >> 1];
  console.log(`BALANCE reaction=${reaction} p10=${seconds[runs / 10 | 0].toFixed(0)}s p50=${median.toFixed(0)}s p90=${seconds[runs * 9 / 10 | 0].toFixed(0)}s deaths: collision=${reasons.COLLISION} fuel=${reasons.FUEL_EMPTY} alive=${reasons.alive}`);
  return median;
}

test('a slower player survives less, and nobody is immortal or doomed', () => {
  const expert = medianSurvival(0.15);
  const average = medianSurvival(0.30);
  const casual = medianSurvival(0.50);
  // The bots plan one move ahead, so the two quicker ones end up close together; only the slow one
  // clearly falls behind.
  assert.ok(casual < average && casual < expert, 'a slow reaction survives least');
  assert.ok(casual >= 15 && casual <= 60, `casual median ${casual}s`);
  assert.ok(average >= 20 && average <= 100, `average median ${average}s`);
  assert.ok(expert >= 20 && expert < 300, `expert median ${expert}s`);
});
