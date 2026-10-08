// Run with: node --test web/test/engine.test.mjs
// Mirrors app/src/test/.../GameEngineTest.kt. Pure simulation: no clocks, no sleeping —
// time only advances through tick(dt), so the whole file runs in milliseconds.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const jsDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'js');

// The game files are plain browser scripts (top-level const/class), so evaluate them in one
// context and expose what the tests need.
function loadApi() {
  const src = ['catalog.js', 'engine.js']
    .map((f) => fs.readFileSync(path.join(jsDir, f), 'utf8'))
    .join('\n');
  const ctx = vm.createContext({ Math, console });
  vm.runInContext(
    src + '\n;globalThis.__api = { GameEngine, CAR_X_UNITS, OBSTACLE_VARIANTS, cityById, SHIELD_DURATION_SECONDS };',
    ctx,
  );
  return ctx.__api;
}

const api = loadApi();
const { GameEngine, CAR_X_UNITS, OBSTACLE_VARIANTS, cityById, SHIELD_DURATION_SECONDS } = api;

let engine;
let sfx;
let nextId;

beforeEach(() => {
  sfx = [];
  nextId = 1000;
  engine = new GameEngine((e) => sfx.push(e));
  engine.start();
});

const addObstacle = (lane, x, variant = OBSTACLE_VARIANTS.CONE) =>
  engine.entities.push({ id: nextId++, lane, x, kind: 'OBSTACLE', variant, nearMissAwarded: false });
const addItem = (lane, x, itemType) =>
  engine.entities.push({ id: nextId++, lane, x, kind: 'ITEM', itemType });

test('near miss: swerving out just before impact awards a bonus that survives the next tick', () => {
  addObstacle(engine.laneIndex, CAR_X_UNITS + 1.5);
  engine.moveLaneUp();
  assert.equal(engine.nearMissCount, 1);
  assert.ok(sfx.includes('NEAR_MISS'));
  engine.tick(0.016);
  assert.ok(engine.score >= 30);
});

test('near miss: not awarded for a distant obstacle', () => {
  addObstacle(engine.laneIndex, CAR_X_UNITS + 10);
  engine.moveLaneUp();
  assert.equal(engine.nearMissCount, 0);
});

test('near miss: pays out once per obstacle', () => {
  addObstacle(engine.laneIndex, CAR_X_UNITS + 1.5);
  engine.moveLaneUp();
  engine.moveLaneDown();
  engine.moveLaneUp();
  assert.equal(engine.nearMissCount, 1);
});

test('collision ends the run without a shield', () => {
  addObstacle(engine.laneIndex, CAR_X_UNITS);
  engine.tick(0.016);
  assert.equal(engine.status, 'GAME_OVER');
  assert.equal(engine.gameOverReason, 'COLLISION');
});

test('shield absorbs an obstacle, then expires', () => {
  addItem(engine.laneIndex, CAR_X_UNITS, 'SHIELD');
  engine.tick(0.016);
  assert.ok(engine.shieldTimeLeft > 0);

  addObstacle(engine.laneIndex, CAR_X_UNITS);
  engine.tick(0.016);
  assert.equal(engine.status, 'RUNNING');
  assert.ok(sfx.includes('SHIELD_HIT'));

  for (let t = 0; t < SHIELD_DURATION_SECONDS + 1; t += 0.05) engine.tick(0.05);
  assert.equal(engine.shieldTimeLeft, 0);
});

test('magnet collects fuel cans from other lanes', () => {
  addItem(engine.laneIndex, CAR_X_UNITS, 'MAGNET');
  engine.tick(0.016);
  assert.ok(engine.magnetTimeLeft > 0);

  addItem(engine.laneIndex === 0 ? 1 : 0, CAR_X_UNITS + 5, 'FUEL');
  engine.tick(0.016);
  assert.equal(engine.fuelCollected, 1);
});

test('fuel can score bonus is not wiped by the next tick', () => {
  addItem(engine.laneIndex, CAR_X_UNITS, 'FUEL');
  engine.tick(0.016);
  engine.tick(0.016);
  assert.ok(engine.score >= 50);
});

test('pause freezes the simulation and ignores input', () => {
  engine.tick(0.016);
  const { distance, laneIndex } = engine;
  engine.pause();
  engine.tick(0.05);
  engine.moveLaneUp();
  assert.equal(engine.distance, distance);
  assert.equal(engine.laneIndex, laneIndex);

  engine.resume();
  engine.tick(0.016);
  assert.ok(engine.distance > distance);
});

test('city traits scale ground speed and fuel drain', () => {
  const tokyo = cityById('tokyo').traits;
  engine.start(tokyo);
  assert.ok(Math.abs(engine.effectiveSpeed - engine.speed * 1.15) < 1e-9);

  const base = new GameEngine();
  base.start();
  const heavy = new GameEngine();
  heavy.start(cityById('paris').traits);
  base.tick(0.05);
  heavy.tick(0.05);
  assert.ok(heavy.fuel < base.fuel);
});

test('restart resets run stats', () => {
  addObstacle(engine.laneIndex, CAR_X_UNITS + 1.5);
  engine.moveLaneUp();
  engine.start();
  assert.equal(engine.nearMissCount, 0);
  assert.equal(engine.elapsedSeconds, 0);
  assert.equal(engine.entities.length, 0);
});
