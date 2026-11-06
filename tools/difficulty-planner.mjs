// Difficulty ruler for the Highway engine: a planner bot that looks 3 s ahead and finds a collision-free lane
// path (DP over 0.1 s steps), preferring pickups. "margin" widens the danger windows (a bigger margin = a
// weaker, more cautious player). Use it to see how a change to the difficulty curve moves survival time.
// Usage: RUNS=30 CAP=600 REACTIONS=0.1,0.2,0.35,0.5 node tools/difficulty-planner.mjs [engine.js] [label]
import vm from 'node:vm';
import fs from 'node:fs';

import path from 'node:path';
import { fileURLToPath } from 'node:url';
const file = process.argv[2] || path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'web', 'js', 'highway-engine.js');
const src = fs.readFileSync(file, 'utf8');
const ctx = vm.createContext({ Math, console });
vm.runInContext(src + '\n;globalThis.__api = { HighwayEngine, HW, HW_LANE_CENTERS, HW_PLAYER_DZ, TRAFFIC_KINDS };', ctx);
const { HighwayEngine, HW, HW_LANE_CENTERS, HW_PLAYER_DZ } = ctx.__api;
const LANES = HW_LANE_CENTERS.length;

function mulberry32(seed) { let a = seed >>> 0; return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
const laneOf = (x) => HW_LANE_CENTERS.reduce((b, c, i) => (Math.abs(c - x) < Math.abs(HW_LANE_CENTERS[b] - x) ? i : b), 0);

const STEP = 0.1, STEPS = 30, CROSS = 2; // 3 s ahead; a lane change takes 2 steps (0.2 s)
const HALF_LEN = 0.45 + 0.35; // collision depth plus margin

function plan(eng, here, margin) {
  const forced = 0;
  const pz = eng.z + HW_PLAYER_DZ;
  // blocked[l][k]: lane l unsafe during step k (a window of [k*STEP, (k+1)*STEP])
  const blocked = Array.from({ length: LANES }, () => new Uint8Array(STEPS + CROSS + 1));
  const reward = Array.from({ length: LANES }, () => new Float32Array(STEPS + CROSS + 1));
  for (const c of eng.traffic) {
    const g0 = c.z - pz;
    const closing = eng.speed + 0.5 - c.speed;
    if (g0 < -HALF_LEN - 1) continue;
    const l = laneOf(c.lx);
    const tIn = (closing > 0.05 ? (g0 - HALF_LEN) / closing : (g0 < HALF_LEN ? 0 : 99)) - margin;
    const tOut = (closing > 0.05 ? (g0 + HALF_LEN) / closing : 99) + margin * 0.5;
    for (let k = 0; k <= STEPS + CROSS; k++) {
      const a = k * STEP, b = (k + 1) * STEP;
      if (b > tIn && a < tOut) blocked[l][k] = 1;
    }
  }
  for (const it of eng.items) {
    const g0 = it.z - pz;
    if (g0 < 0) continue;
    const t = g0 / (eng.speed + 0.5);
    const k = Math.round(t / STEP);
    const l = laneOf(it.lx);
    if (k >= 0 && k <= STEPS) reward[l][k] += it.type === 'FUEL' ? (eng.fuel < 70 ? 3 : 0.5) : 2;
  }
  // DP from the end: best[k][l] = best total reward from (k,l), -inf if doomed
  const NEG = -1e9;
  const best = Array.from({ length: STEPS + 1 }, () => new Float32Array(LANES).fill(NEG));
  const choice = Array.from({ length: STEPS + 1 }, () => new Int8Array(LANES));
  for (let l = 0; l < LANES; l++) if (!blocked[l][STEPS]) best[STEPS][l] = 0;
  for (let k = STEPS - 1; k >= 0; k--) {
    for (let l = 0; l < LANES; l++) {
      if (blocked[l][k]) continue;
      // stay
      let b = best[k + 1][l] + reward[l][k];
      let ch = 0;
      for (const d of (k < forced ? [] : [-1, 1])) {
        const n = l + d;
        if (n < 0 || n >= LANES) continue;
        if (blocked[n][k] || blocked[l][k + 1] || blocked[n][k + 1]) continue;
        const to = Math.min(STEPS, k + CROSS);
        if (best[to][n] <= NEG / 2) continue;
        const v = best[to][n] + reward[n][to] * 0.0 - 0.15;
        if (v > b) { b = v; ch = d; }
      }
      best[k][l] = b;
      choice[k][l] = ch;
    }
  }
  if (best[0][here] <= NEG / 2) {
    // doomed on paper: take the neighboring lane with the best outlook anyway
    let bl = here, bv = NEG;
    for (let l = Math.max(0, here - 1); l <= Math.min(LANES - 1, here + 1); l++) if (best[0][l] > bv) { bv = best[0][l]; bl = l; }
    return bl;
  }
  return here + choice[forced][here];
}

function run(seed, reaction, cap) {
  const eng = new HighwayEngine(() => {}, mulberry32(seed));
  eng.start();
  let now = 0, nextLook = 0, nextTap = 0, target = eng.laneIndex;
  const pending = [];
  while (eng.status === 'RUNNING' && now < cap) {
    if (now >= nextLook) { nextLook = now + STEP; target = plan(eng, eng.laneIndex, reaction); }
    if (target !== eng.laneIndex && now >= nextTap) { eng.moveLane(target > eng.laneIndex ? 1 : -1); nextTap = now + 0.14; }
    eng.tick(1 / 60); now += 1 / 60;
  }
  return { now, status: eng.status, reason: eng.gameOverReason, score: eng.score };
}

const label = process.argv[3] || 'engine';
const runs = Number(process.env.RUNS || 30), cap = Number(process.env.CAP || 600);
for (const reaction of (process.env.REACTIONS || '0.1,0.2,0.35,0.5').split(',').map(Number)) {
  const res = [];
  for (let s = 1; s <= runs; s++) res.push(run(s, reaction, cap));
  const t = res.map(r => r.now).sort((a, b) => a - b);
  const alive = res.filter(r => r.status === 'RUNNING').length;
  const fuel = res.filter(r => r.reason === 'FUEL_EMPTY').length;
  console.log(`${label} reaction=${reaction}: p10=${t[runs * 0.1 | 0].toFixed(0)}s p50=${t[runs >> 1].toFixed(0)}s p90=${t[runs * 0.9 | 0].toFixed(0)}s  survived-to-cap=${alive}/${runs} fuelDeaths=${fuel}`);
}
