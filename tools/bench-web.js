// Draw-cost benchmark for the web build: paste into the browser console on a running game page.
// Times drawFrame() on a busy fixed scene, forcing the canvas to flush each frame (getImageData),
// so raster time is included. Compare before/after a rendering change in the SAME session —
// absolute numbers shift a lot between machines and runs (expect roughly +-1 ms of noise).
(async () => {
  state.selectedCityId = 'newyork'; state.selectedCarId = 'suv';
  startGame();
  const city = cityById('newyork'), car = carById('suv');
  const stage = () => {
    engine.start(city.traits);
    engine.entities.length = 0; engine._spawnCooldown = 999;
    const V = OBSTACLE_VARIANTS;
    const add = (lane, x, v) => engine.entities.push({ id: Math.random(), lane, x, kind: 'OBSTACLE', variant: V[v], nearMissAwarded: true });
    add(0, 8, 'SUV'); add(1, 11, 'SEDAN'); add(2, 6.5, 'TRUCK'); add(3, 13, 'BUS'); add(4, 9.5, 'SEDAN'); add(0, 16, 'CONE'); add(3, 17, 'BARRIER');
    engine.entities.push({ id: 9, lane: 4, x: 15, kind: 'ITEM', itemType: 'FUEL' });
    engine.laneIndex = 1; engine.shieldTimeLeft = 3;
  };
  const times = [];
  for (let round = 0; round < 3; round++) {
    for (let i = 0; i < 200; i++) {
      stage();
      engine.distance = 40 + i * 0.7;
      engine.elapsedSeconds = 3 + i * 0.016;
      animatedLane = engine.laneIndex;
      const t0 = performance.now();
      drawFrame(ctx, canvas.width, canvas.height, engine, city, car, images, animatedLane);
      ctx.getImageData(0, 0, 1, 1); // force the GPU work to finish so raster time is included
      const t1 = performance.now();
      if (round > 0) times.push(t1 - t0); // first round warms up caches
    }
  }
  times.sort((a, b) => a - b);
  const q = (p) => times[Math.floor(times.length * p)].toFixed(2);
  return { canvas: canvas.width + 'x' + canvas.height, n: times.length, p50: q(0.5), p90: q(0.9), p99: q(0.99), max: times[times.length - 1].toFixed(2) };
})()
