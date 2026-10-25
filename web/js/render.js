// Ported from app/src/main/java/.../ui/components/GameArt.kt + GameScreen.kt.

const SKY_FRACTION = 0.4;
const OBSTACLE_SCALE = 4.5;

// Pickups (fuel cans) stay forgiving — a wide grab radius on a beneficial item is good
// game feel. Obstacles get a slight shrink so grazes favor the player instead of feeling
// cheap, without being so generous it stops feeling like it's actually the car crashing.
const OBSTACLE_HITBOX_FORGIVENESS = 0.92;

function mod(a, b) { return ((a % b) + b) % b; }

/**
 * The world-unit half-width an obstacle actually renders at, mirroring drawFrame's box
 * math and drawFittedSprite's fit-by-width-then-height logic exactly. hitWidthScale and
 * heightScale only control the fitting BOX; a tall/narrow sprite (e.g. a cone) can end
 * up height-constrained and render much narrower than hitWidthScale alone implies, so
 * collision derived straight from hitWidthScale (independent of each sprite's own aspect
 * ratio) drifts from what's on screen — that's what let cars "hit" a cone or barrier
 * before actually touching it. Deriving the hitbox from the same fit math keeps it
 * locked to whatever's actually drawn, for every sprite, with no per-variant retuning.
 */
function computeObstacleHitHalfWidthUnits(hitWidthScale, heightScale, spriteAspect) {
  const roadFraction = 1 - SKY_FRACTION;
  const laneHeightOverCanvasHeight = roadFraction / LANE_COUNT;
  const carHeightOverCanvasHeight = laneHeightOverCanvasHeight * 0.58;
  const carWidthOverCanvasHeight = carHeightOverCanvasHeight * 1.6;

  const widthBoxOverCanvasHeight = carWidthOverCanvasHeight * 0.85 * OBSTACLE_SCALE * hitWidthScale;
  const heightBoxOverCanvasHeight = carHeightOverCanvasHeight * 0.95 * OBSTACLE_SCALE * heightScale;
  const renderedWidthOverCanvasHeight = Math.min(widthBoxOverCanvasHeight, heightBoxOverCanvasHeight * spriteAspect);

  // canvasHeight/canvasWidth is fixed (GAME_ASPECT), and pxPerUnit = canvasWidth / WORLD_WIDTH_UNITS,
  // so world-unit width = renderedWidthPx / pxPerUnit = renderedWidthOverCanvasHeight * (canvasHeight/canvasWidth) * WORLD_WIDTH_UNITS.
  const renderedWidthUnits = renderedWidthOverCanvasHeight * (1 / GAME_ASPECT) * WORLD_WIDTH_UNITS;
  return (renderedWidthUnits / 2) * OBSTACLE_HITBOX_FORGIVENESS;
}

function drawCitySkylineSprite(ctx, img, canvasWidth, horizonY, parallaxUnits, pxPerUnit) {
  if (!img || !img.complete || img.naturalWidth === 0) return false;
  const tileWidthPx = horizonY * (img.naturalWidth / img.naturalHeight);
  if (tileWidthPx <= 0) return false;
  const scrollPx = mod(parallaxUnits * pxPerUnit, tileWidthPx);
  const tilesNeeded = Math.ceil(canvasWidth / tileWidthPx) + 2;
  for (let t = -1; t <= tilesNeeded; t++) {
    const tileBaseX = t * tileWidthPx - scrollPx;
    ctx.drawImage(img, tileBaseX, 0, tileWidthPx, horizonY);
  }
  return true;
}

/** Pixel Y for perspective position p (0 = horizon, LANE_COUNT = bottom of road). */
function perspectiveY(p, horizonY, roadHeight) {
  return horizonY + roadHeight * perspectiveCumulative(p) / PERSPECTIVE_TOTAL;
}

/** Local (instantaneous, not lane-averaged) perspective scale at position p. */
function localScaleAt(p) {
  return PERSPECTIVE_MIN_SCALE + (1 - PERSPECTIVE_MIN_SCALE) * (p / LANE_COUNT);
}

function drawRoad(ctx, city, laneCount, horizonY, canvasWidth, canvasHeight, worldWidthUnits, distanceUnits) {
  const roadHeight = canvasHeight - horizonY;
  ctx.fillStyle = city.roadColor;
  ctx.fillRect(0, horizonY, canvasWidth, roadHeight);

  const pxPerUnit = canvasWidth / worldWidthUnits;
  const baseDash = pxPerUnit * 0.9;
  const baseGap = pxPerUnit * 0.6;

  // Each divider line's own dash size/width/spacing shrinks toward the horizon (its own
  // localScaleAt), instead of all LANE_COUNT-1 lines sharing one uniform dash pattern — so
  // the road markings themselves read as converging into the distance.
  ctx.save();
  ctx.strokeStyle = city.roadLineColor;
  ctx.globalAlpha = 0.7;
  for (let i = 1; i < laneCount; i++) {
    const y = perspectiveY(i, horizonY, roadHeight);
    const scale = localScaleAt(i);
    const dash = baseDash * scale;
    const gap = baseGap * scale;
    const phase = mod(distanceUnits * pxPerUnit, dash + gap);
    ctx.lineWidth = Math.max(1, roadHeight * 0.015 * scale);
    ctx.setLineDash([dash, gap]);
    ctx.lineDashOffset = -phase;
    ctx.beginPath();
    ctx.moveTo(canvasWidth, y);
    ctx.lineTo(0, y);
    ctx.stroke();
  }
  ctx.restore();
}

/** Fits an image into a box, bottom-anchored (never vertically centered) — mirrors drawCarSprite. */
function drawFittedSprite(ctx, img, centerX, centerY, boxWidth, boxHeight) {
  if (!img || !img.complete || img.naturalWidth === 0) return;
  const srcAspect = img.naturalWidth / img.naturalHeight;
  let drawWidth = boxWidth;
  let drawHeight = boxWidth / srcAspect;
  if (drawHeight > boxHeight) {
    drawHeight = boxHeight;
    drawWidth = boxHeight * srcAspect;
  }
  const boxBottom = centerY + boxHeight / 2;
  const left = centerX - drawWidth / 2;
  const top = boxBottom - drawHeight;
  ctx.drawImage(img, left, top, drawWidth, drawHeight);
}

function roundRectPath(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y);
  ctx.arcTo(x + w, y, x + w, y + r, r);
  ctx.lineTo(x + w, y + h - r);
  ctx.arcTo(x + w, y + h, x + w - r, y + h, r);
  ctx.lineTo(x + r, y + h);
  ctx.arcTo(x, y + h, x, y + h - r, r);
  ctx.lineTo(x, y + r);
  ctx.arcTo(x, y, x + r, y, r);
  ctx.closePath();
}

/** Fuel refill pickup, drawn as a small retro jerry can (matches what the item does). */
function drawFuelCanItem(ctx, cx, cy, radius) {
  const w = radius * 1.7;
  const h = radius * 2.0;
  const left = cx - w / 2;
  const top = cy - h / 2 + radius * 0.12;
  const r = radius * 0.22;
  const bodyPath = () => roundRectPath(ctx, left, top, w, h, r);

  ctx.strokeStyle = '#2A1B0E';
  ctx.lineWidth = Math.max(1.5, radius * 0.18);
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.arc(cx, top, radius * 0.4, Math.PI * 1.1, Math.PI * 1.9);
  ctx.stroke();

  ctx.fillStyle = '#FFC93C';
  const capW = w * 0.24, capH = radius * 0.3;
  ctx.fillRect(cx - capW / 2, top - capH * 0.55, capW, capH);

  ctx.fillStyle = '#E63946';
  bodyPath();
  ctx.fill();

  ctx.save();
  bodyPath();
  ctx.clip();

  ctx.fillStyle = 'rgba(0,0,0,0.2)';
  ctx.fillRect(left, top + h * 0.66, w, h * 0.34);

  ctx.fillStyle = 'rgba(0,0,0,0.28)';
  const gripW = w * 0.52, gripH = h * 0.15;
  ctx.fillRect(cx - gripW / 2, top + h * 0.3, gripW, gripH);

  ctx.fillStyle = 'rgba(255,255,255,0.3)';
  ctx.fillRect(left + w * 0.12, top + h * 0.1, w * 0.14, h * 0.28);

  ctx.fillStyle = 'rgba(255,255,255,0.85)';
  const dropX = cx, dropY = top + h * 0.56, dropR = radius * 0.15;
  ctx.beginPath();
  ctx.moveTo(dropX, dropY - dropR * 1.4);
  ctx.quadraticCurveTo(dropX + dropR * 1.15, dropY + dropR * 0.4, dropX, dropY + dropR * 1.15);
  ctx.quadraticCurveTo(dropX - dropR * 1.15, dropY + dropR * 0.4, dropX, dropY - dropR * 1.4);
  ctx.fill();

  ctx.restore();
}

/** Timed-invincibility pickup, drawn as a blue shield. Mirrors drawShieldItem in GameArt.kt. */
function drawShieldItem(ctx, cx, cy, radius) {
  const w = radius * 1.7, h = radius * 2.0;
  const left = cx - w / 2, right = cx + w / 2, top = cy - h / 2;
  const shield = () => {
    ctx.beginPath();
    ctx.moveTo(left, top + h * 0.1);
    ctx.lineTo(right, top + h * 0.1);
    ctx.lineTo(right, top + h * 0.55);
    ctx.quadraticCurveTo(right, top + h * 0.88, cx, top + h);
    ctx.quadraticCurveTo(left, top + h * 0.88, left, top + h * 0.55);
    ctx.closePath();
  };
  ctx.fillStyle = '#3D8BFF';
  shield();
  ctx.fill();
  ctx.save();
  shield();
  ctx.clip();
  ctx.fillStyle = 'rgba(255,255,255,0.28)';
  ctx.fillRect(left, top, w * 0.5, h);
  ctx.fillStyle = 'rgba(0,0,0,0.18)';
  ctx.fillRect(cx, top, w * 0.5, h);
  ctx.restore();
  ctx.strokeStyle = '#1A2340';
  ctx.lineWidth = Math.max(1.5, radius * 0.14);
  shield();
  ctx.stroke();

  const star = [
    [0, -0.45], [0.14, -0.1], [0.5, -0.1], [0.2, 0.12], [0.32, 0.5],
    [0, 0.27], [-0.32, 0.5], [-0.2, 0.12], [-0.5, -0.1], [-0.14, -0.1],
  ];
  ctx.fillStyle = 'rgba(255,255,255,0.92)';
  ctx.beginPath();
  star.forEach(([dx, dy], i) => {
    const px = cx + dx * radius, py = cy + dy * radius;
    if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
  });
  ctx.closePath();
  ctx.fill();
}

/** Timed fuel-magnet pickup, drawn as a red horseshoe magnet. Mirrors drawMagnetItem in GameArt.kt. */
function drawMagnetItem(ctx, cx, cy, radius) {
  const arcR = radius * 0.62;
  const stroke = Math.max(3, radius * 0.5);
  const shift = radius * 0.2;
  const legTop = cy - arcR * 0.95 + shift;
  const legBottom = cy + shift;
  ctx.lineWidth = stroke;
  ctx.lineCap = 'butt';
  ctx.strokeStyle = '#E63946';
  ctx.beginPath();
  ctx.arc(cx, cy + shift, arcR, 0, Math.PI);
  ctx.stroke();
  for (const sign of [-1, 1]) {
    const x = cx + sign * arcR;
    ctx.strokeStyle = '#E63946';
    ctx.beginPath();
    ctx.moveTo(x, legTop);
    ctx.lineTo(x, legBottom);
    ctx.stroke();
    ctx.strokeStyle = '#E8ECF4';
    ctx.beginPath();
    ctx.moveTo(x, legTop);
    ctx.lineTo(x, legTop + stroke * 0.95);
    ctx.stroke();
  }
}

/** Translucent bubble drawn around the player while the shield is active. */
function drawShieldBubble(ctx, cx, cy, rx, ry, alpha) {
  ctx.save();
  ctx.fillStyle = `rgba(111,214,255,${0.2 * alpha})`;
  ctx.strokeStyle = `rgba(189,235,255,${0.9 * alpha})`;
  ctx.lineWidth = 3;
  ctx.beginPath();
  ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.stroke();
  ctx.restore();
}

/** Dashed ring around the player while the fuel magnet is active; `phase` animates it. */
function drawMagnetAura(ctx, cx, cy, rx, ry, phase) {
  ctx.save();
  ctx.strokeStyle = 'rgba(255,90,110,0.85)';
  ctx.lineWidth = 3;
  ctx.setLineDash([14, 10]);
  ctx.lineDashOffset = -phase;
  ctx.beginPath();
  ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
  ctx.stroke();
  ctx.restore();
}

// ---------- Depth cues (mirror GameArt.kt: drawGroundShadow / drawCurbs / drawLampPosts /
// drawRoadHaze / drawSpeedStreaks). Everything here is flat rects/ellipses/gradients — no
// blur, no offscreen layers — so the per-frame cost stays tiny. ----------

/** Each lane's veil of city haze; stacked, so the farthest lane reads the hazier. */
const FOG_ALPHA_PER_LANE = 0.075;

// Safety net for devices we can't benchmark: if frames run slow for a stretch, the purely
// decorative effects (haze, lamps, streaks, fog veils) switch off for the rest of the session.
// Shadows, curbs and the car tilt are cheap enough to always keep. Mirrors FxQuality in GameScreen.kt.
const fxState = { low: false, ema: 1 / 60 };
const FX_SLOW_FRAME_SECONDS = 0.024; // ~42 fps
function updateFxQuality(dt, runSeconds) {
  fxState.ema = fxState.ema * 0.95 + dt * 0.05;
  if (!fxState.low && runSeconds > 3 && fxState.ema > FX_SLOW_FRAME_SECONDS) fxState.low = true;
}

function hexA(hex, a) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

/** The size drawFittedSprite will actually draw an image at inside a box. */
function fittedSize(img, boxW, boxH) {
  if (!img || !img.naturalWidth) return { w: boxW, h: boxH };
  const aspect = img.naturalWidth / img.naturalHeight;
  let w = boxW, h = boxW / aspect;
  if (h > boxH) { h = boxH; w = boxH * aspect; }
  return { w, h };
}

/** Soft elliptical shadow on the asphalt — anchors a vehicle/pickup to the road. */
function drawGroundShadow(ctx, cx, bottomY, width, height, alpha = 0.38) {
  ctx.fillStyle = `rgba(0,0,0,${alpha})`;
  ctx.beginPath();
  ctx.ellipse(cx, bottomY - height * 0.05, width * 0.5, Math.max(2, height * 0.075), 0, 0, Math.PI * 2);
  ctx.fill();
}

/** Far-end haze (road melts into the skyline) and a darker near edge (vignette). */
// The gradient objects never change within a run, so they're built once (re-made only when
// the city or canvas size changes) and reused every frame. Measured: reusing them is the
// cheapest option — pre-rendering the layer to an offscreen canvas/ImageBitmap and blitting
// it was several times slower.
let hazeCache = { key: '', far: 0, nearTop: 0, hz: null, vg: null };

function drawRoadHaze(ctx, city, w, horizonY, roadHeight) {
  const key = `${city.id}|${w}|${Math.round(horizonY)}|${Math.round(roadHeight)}`;
  if (hazeCache.key !== key) {
    const far = roadHeight * 0.42;
    const nearTop = horizonY + roadHeight * 0.72;
    const hz = ctx.createLinearGradient(0, horizonY, 0, horizonY + far);
    hz.addColorStop(0, hexA(city.haze, 0.55));
    hz.addColorStop(1, hexA(city.haze, 0));
    const vg = ctx.createLinearGradient(0, nearTop, 0, horizonY + roadHeight);
    vg.addColorStop(0, 'rgba(0,0,0,0)');
    vg.addColorStop(1, 'rgba(0,0,0,0.38)');
    hazeCache = { key, far, nearTop, hz, vg };
  }
  ctx.fillStyle = hazeCache.hz;
  ctx.fillRect(0, horizonY, w, hazeCache.far);
  ctx.fillStyle = hazeCache.vg;
  ctx.fillRect(0, hazeCache.nearTop, w, horizonY + roadHeight - hazeCache.nearTop);
}

/** Rumble-strip curbs on both road edges; the near one scrolls faster than the far one. */
function drawCurbs(ctx, w, horizonY, roadHeight, pxPerUnit, distance) {
  const topH = Math.max(3, roadHeight * 0.014);
  ctx.fillStyle = 'rgba(255,255,255,0.22)';
  ctx.fillRect(0, horizonY, w, topH);
  const farBlock = 1.1 * pxPerUnit;
  const farOff = mod(distance * pxPerUnit * 0.9, farBlock * 2);
  ctx.fillStyle = 'rgba(0,0,0,0.28)';
  for (let x = -farOff; x < w; x += farBlock * 2) ctx.fillRect(x, horizonY, farBlock, topH);

  const botH = Math.max(3, roadHeight * 0.013);
  const y = horizonY + roadHeight - botH;
  ctx.fillStyle = 'rgba(255,255,255,0.20)';
  ctx.fillRect(0, y, w, botH);
  const nearBlock = 2.2 * pxPerUnit;
  const nearOff = mod(distance * pxPerUnit * 1.7, nearBlock * 2);
  ctx.fillStyle = 'rgba(0,0,0,0.45)';
  for (let x = -nearOff; x < w; x += nearBlock * 2) ctx.fillRect(x, y, nearBlock, botH);
}

/** Street lamps standing on the far curb; they scroll faster than the skyline (parallax). */
function drawLampPosts(ctx, w, horizonY, roadHeight, pxPerUnit, distance) {
  const spacing = 4.5 * pxPerUnit;
  const off = mod(distance * pxPerUnit * 0.85, spacing);
  const poleH = roadHeight * 0.16;
  const poleW = Math.max(2, roadHeight * 0.009);
  const top = horizonY - poleH * 0.72;
  for (let x = -off; x < w + spacing; x += spacing) {
    ctx.fillStyle = 'rgba(12,8,24,0.72)';
    ctx.fillRect(x, top, poleW, poleH);
    ctx.fillRect(x - poleW * 3, top, poleW * 4, poleW * 1.1);
    ctx.fillStyle = 'rgba(255,214,120,0.85)';
    ctx.fillRect(x - poleW * 3, top + poleW * 1.1, poleW * 2.6, poleW * 0.9);
  }
}

/** Thin streaks racing past on the asphalt; stronger the faster the car goes. */
function drawSpeedStreaks(ctx, w, horizonY, roadHeight, pxPerUnit, distance, speedFactor) {
  if (speedFactor < 0.2) return;
  ctx.lineCap = 'butt';
  for (let i = 0; i < 10; i++) {
    const fy = (i * 0.6180339) % 1;
    const y = horizonY + roadHeight * (0.12 + 0.86 * fy);
    const len = (0.9 + (i % 3) * 0.5) * pxPerUnit * (0.5 + speedFactor);
    const k = 2.2 + (i % 4) * 0.4;
    const x = mod(i * w * 0.173 - distance * pxPerUnit * k, w + len) - len;
    ctx.strokeStyle = `rgba(255,255,255,${(0.05 + 0.16 * speedFactor * (0.4 + fy)).toFixed(3)})`;
    ctx.lineWidth = Math.max(1, roadHeight * 0.004 * (0.4 + fy));
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.lineTo(x + len, y);
    ctx.stroke();
  }
}

/**
 * Draws one full game frame. `images` maps sprite path -> HTMLImageElement.
 * `animatedLane` is the (possibly mid-tween) lane the player car is drawn at.
 */
function drawFrame(ctx, canvasWidth, canvasHeight, engine, city, car, images, animatedLane) {
  const horizonY = canvasHeight * SKY_FRACTION;
  const roadHeight = canvasHeight - horizonY;
  const pxPerUnit = canvasWidth / WORLD_WIDTH_UNITS;

  const citySprite = images[city.sprite];
  const drewCity = drawCitySkylineSprite(ctx, citySprite, canvasWidth, horizonY, engine.distance * 0.4, pxPerUnit);
  if (!drewCity) {
    const grad = ctx.createLinearGradient(0, 0, 0, horizonY);
    grad.addColorStop(0, city.skyTop);
    grad.addColorStop(1, city.skyBottom);
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, canvasWidth, horizonY);
  }

  drawRoad(ctx, city, LANE_COUNT, horizonY, canvasWidth, canvasHeight, WORLD_WIDTH_UNITS, engine.distance);
  const fancy = !fxState.low;
  if (fancy) drawRoadHaze(ctx, city, canvasWidth, horizonY, roadHeight);
  drawCurbs(ctx, canvasWidth, horizonY, roadHeight, pxPerUnit, engine.distance);
  if (fancy) {
    drawLampPosts(ctx, canvasWidth, horizonY, roadHeight, pxPerUnit, engine.distance);
    drawSpeedStreaks(ctx, canvasWidth, horizonY, roadHeight, pxPerUnit, engine.distance, Math.min(1, engine.speed / MAX_SPEED));
  }

  // Baseline (old uniform-lane-equivalent) car size — each entity scales this by its own
  // lane's perspective size (laneSizeScale, engine.js) so farther lanes read smaller, and
  // lane boundaries themselves converge toward the horizon (perspectiveY) instead of being
  // evenly spaced. Works for the player's continuously-tweened animatedLane too, since
  // laneSizeScale/perspectiveY are defined for any real lane position, not just integers.
  const baseCarHeight = (roadHeight / LANE_COUNT) * 0.58;
  const baseCarWidth = baseCarHeight * 1.6;
  const laneCarHeight = (lane) => baseCarHeight * laneSizeScale(lane);
  const laneCarWidth = (lane) => laneCarHeight(lane) * 1.6;
  const laneBottomY = (lane) => {
    const top = perspectiveY(lane, horizonY, roadHeight);
    const bottom = perspectiveY(lane + 1, horizonY, roadHeight);
    return bottom - (bottom - top) * 0.06;
  };

  // Scaled 0.7x twice (0.49x) ahead of release — mirrors GameScreen.kt.
  const playerCarHeight = laneCarHeight(animatedLane) * 3 * 0.49;
  const playerCarWidth = laneCarWidth(animatedLane) * 3 * 0.49;
  const playerCarY = laneBottomY(animatedLane) - playerCarHeight / 2;

  // Oversized sprites (OBSTACLE_SCALE) spill into neighboring lanes, so draw order has to
  // follow lane depth or a far-lane sprite can wrongly paint over a near-lane one. Lane 0
  // is the top (farthest) lane and LANE_COUNT-1 is the bottom (nearest) one, so sorting by
  // lane ascending and painting in that order puts nearer lanes on top — a simple painter's
  // algorithm. The player car sorts in by its own (possibly mid-tween) lane too.
  const drawables = [{
    lane: animatedLane,
    draw: () => {
      const carX = CAR_X_UNITS * pxPerUnit;
      const carBottom = playerCarY + playerCarHeight / 2;
      const carImg = images[car.sprite];
      const fs = fittedSize(carImg, playerCarWidth, playerCarHeight);
      drawGroundShadow(ctx, carX, carBottom, fs.w, fs.h);
      // The nose leans toward the direction of a lane change, pivoting on the wheels.
      const tilt = Math.max(-0.12, Math.min(0.12, (engine.laneIndex - animatedLane) * 0.09));
      ctx.save();
      ctx.translate(carX, carBottom);
      ctx.rotate(tilt);
      drawFittedSprite(ctx, carImg, 0, -playerCarHeight / 2, playerCarWidth, playerCarHeight);
      ctx.restore();
      if (engine.magnetTimeLeft > 0) {
        drawMagnetAura(ctx, carX, playerCarY, playerCarWidth * 0.7, playerCarHeight * 0.85, engine.elapsedSeconds * 60);
      }
      if (engine.shieldTimeLeft > 0) {
        // Blinks during the last 1.5s so the player sees it about to run out.
        const ending = engine.shieldTimeLeft < 1.5;
        const alpha = ending && Math.floor(engine.shieldTimeLeft * 8) % 2 === 0 ? 0.3 : 1;
        drawShieldBubble(ctx, carX, playerCarY, playerCarWidth * 0.62, playerCarHeight * 0.78, alpha);
      }
    },
  }];

  const cullMargin = baseCarWidth * 8;
  for (const entity of engine.entities) {
    const ex = entity.x * pxPerUnit;
    if (ex < -cullMargin || ex > canvasWidth + cullMargin) continue;

    const entityCarHeight = laneCarHeight(entity.lane);
    const entityCarWidth = laneCarWidth(entity.lane);
    if (entity.kind === 'OBSTACLE') {
      const w = entityCarWidth * 0.85 * OBSTACLE_SCALE * entity.variant.hitWidthScale;
      const h = entityCarHeight * 0.95 * OBSTACLE_SCALE * entity.variant.heightScale;
      const ey = laneBottomY(entity.lane) - h / 2;
      drawables.push({
        lane: entity.lane,
        draw: () => {
          const img = images[entity.variant.sprite];
          const fs = fittedSize(img, w, h);
          drawGroundShadow(ctx, ex, ey + h / 2, fs.w, fs.h);
          drawFittedSprite(ctx, img, ex, ey, w, h);
        },
      });
    } else {
      const ey = (perspectiveY(entity.lane, horizonY, roadHeight) + perspectiveY(entity.lane + 1, horizonY, roadHeight)) / 2;
      const radius = entityCarHeight * 0.56;
      const drawItem = entity.itemType === 'SHIELD' ? drawShieldItem
        : entity.itemType === 'MAGNET' ? drawMagnetItem
        : drawFuelCanItem;
      drawables.push({
        lane: entity.lane,
        draw: () => {
          drawGroundShadow(ctx, ex, ey + radius * 1.05, radius * 1.7, radius * 1.2, 0.3);
          drawItem(ctx, ex, ey, radius);
        },
      });
    }
  }

  // Paint lane by lane, far to near, laying a thin veil of city haze over everything drawn so
  // far after each lane. The farthest lane ends up under the most veils (hazier, softer), the
  // nearest under none — atmospheric perspective for the price of a few translucent rects.
  drawables.sort((a, b) => a.lane - b.lane);
  let next = 0;
  for (let lane = 0; lane < LANE_COUNT; lane++) {
    while (next < drawables.length && Math.round(drawables[next].lane) <= lane) drawables[next++].draw();
    if (fancy && lane < LANE_COUNT - 1) {
      ctx.fillStyle = hexA(city.haze, FOG_ALPHA_PER_LANE);
      ctx.fillRect(0, horizonY, canvasWidth, perspectiveY(lane + 1, horizonY, roadHeight) - horizonY);
    }
  }
}
