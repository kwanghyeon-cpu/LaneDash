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

  // Scaled 0.7x ahead of release — mirrors GameScreen.kt.
  const playerCarHeight = laneCarHeight(animatedLane) * 3 * 0.7;
  const playerCarWidth = laneCarWidth(animatedLane) * 3 * 0.7;
  const playerCarY = laneBottomY(animatedLane) - playerCarHeight / 2;

  // Oversized sprites (OBSTACLE_SCALE) spill into neighboring lanes, so draw order has to
  // follow lane depth or a far-lane sprite can wrongly paint over a near-lane one. Lane 0
  // is the top (farthest) lane and LANE_COUNT-1 is the bottom (nearest) one, so sorting by
  // lane ascending and painting in that order puts nearer lanes on top — a simple painter's
  // algorithm. The player car sorts in by its own (possibly mid-tween) lane too.
  const drawables = [{
    lane: animatedLane,
    draw: () => drawFittedSprite(ctx, images[car.sprite], CAR_X_UNITS * pxPerUnit, playerCarY, playerCarWidth, playerCarHeight),
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
      drawables.push({ lane: entity.lane, draw: () => drawFittedSprite(ctx, images[entity.variant.sprite], ex, ey, w, h) });
    } else {
      const ey = (perspectiveY(entity.lane, horizonY, roadHeight) + perspectiveY(entity.lane + 1, horizonY, roadHeight)) / 2;
      const radius = entityCarHeight * 0.56;
      drawables.push({ lane: entity.lane, draw: () => drawFuelCanItem(ctx, ex, ey, radius) });
    }
  }

  drawables.sort((a, b) => a.lane - b.lane);
  for (const d of drawables) d.draw();
}
