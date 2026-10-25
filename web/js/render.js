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
function computeObstacleHitHalfWidthUnits(variant, spriteAspect) {
  const roadFraction = 1 - SKY_FRACTION;
  const laneHeightOverCanvasHeight = roadFraction / LANE_COUNT;
  const carHeightOverCanvasHeight = laneHeightOverCanvasHeight * 0.58;
  const carWidthOverCanvasHeight = carHeightOverCanvasHeight * 1.6;

  let renderedWidthOverCanvasHeight;
  if (variant.lengthMeters) {
    // Vehicles: width comes straight from real length (drawFrame draws them width-first).
    renderedWidthOverCanvasHeight = carWidthOverCanvasHeight * VEHICLE_WIDTH_PER_METER * variant.lengthMeters;
  } else {
    const widthBoxOverCanvasHeight = carWidthOverCanvasHeight * 0.85 * OBSTACLE_SCALE * variant.hitWidthScale;
    const heightBoxOverCanvasHeight = carHeightOverCanvasHeight * 0.95 * OBSTACLE_SCALE * variant.heightScale;
    renderedWidthOverCanvasHeight = Math.min(widthBoxOverCanvasHeight, heightBoxOverCanvasHeight * spriteAspect);
  }

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

// Pickups are Phosphor glyphs (icons.js) on a dark badge with a colored ring — flat, legible at
// small sizes, and told apart at a glance by ring color. Mirrors drawItemBadge in GameArt.kt.
const FUEL_ITEM_COLOR = '#FFA23C';
const SHIELD_ITEM_COLOR = '#4DA3FF';
const MAGNET_ITEM_COLOR = '#E63946';

function drawItemBadge(ctx, glyph, cx, cy, radius, color) {
  ctx.fillStyle = 'rgba(27,16,53,0.9)';
  ctx.beginPath();
  ctx.arc(cx, cy, radius, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = color;
  ctx.lineWidth = radius * 0.14;
  ctx.beginPath();
  ctx.arc(cx, cy, radius * 0.93, 0, Math.PI * 2);
  ctx.stroke();
  drawIconGlyph(ctx, glyph, cx, cy, radius * 1.15, color);
}

function drawFuelCanItem(ctx, cx, cy, radius) { drawItemBadge(ctx, 'fuelCan', cx, cy, radius, FUEL_ITEM_COLOR); }
function drawShieldItem(ctx, cx, cy, radius) { drawItemBadge(ctx, 'shield', cx, cy, radius, SHIELD_ITEM_COLOR); }
function drawMagnetItem(ctx, cx, cy, radius) { drawItemBadge(ctx, 'magnet', cx, cy, radius, MAGNET_ITEM_COLOR); }

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
  // Vehicle widths come from real-world lengths (car.lengthMeters / variant.lengthMeters) on one
  // shared scale, so the player's car and the traffic are in true proportion; each sprite's own
  // aspect ratio then sets its height. The box height only needs to be generous enough not to
  // clamp any sprite (every vehicle is wider than 1.7:1) — mirrors GameScreen.kt.
  const playerCarWidth = laneCarWidth(animatedLane) * car.lengthMeters * VEHICLE_WIDTH_PER_METER;
  const playerCarHeight = playerCarWidth / 1.7;
  const playerCarY = laneBottomY(animatedLane) - playerCarHeight / 2;
  const playerFit = fittedSize(images[car.sprite], playerCarWidth, playerCarHeight);

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
        drawMagnetAura(ctx, carX, carBottom - playerFit.h / 2, playerFit.w * 0.62, playerFit.h * 0.95, engine.elapsedSeconds * 60);
      }
      if (engine.shieldTimeLeft > 0) {
        // Blinks during the last 1.5s so the player sees it about to run out.
        const ending = engine.shieldTimeLeft < 1.5;
        const alpha = ending && Math.floor(engine.shieldTimeLeft * 8) % 2 === 0 ? 0.3 : 1;
        drawShieldBubble(ctx, carX, carBottom - playerFit.h / 2, playerFit.w * 0.58, playerFit.h * 0.85, alpha);
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
      const vehicle = entity.variant.lengthMeters > 0;
      const w = vehicle
        ? entityCarWidth * entity.variant.lengthMeters * VEHICLE_WIDTH_PER_METER
        : entityCarWidth * 0.85 * OBSTACLE_SCALE * entity.variant.hitWidthScale;
      const h = vehicle
        ? w / 1.7
        : entityCarHeight * 0.95 * OBSTACLE_SCALE * entity.variant.heightScale;
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
