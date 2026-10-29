// Behind-the-car "highway" mode — the drawing half. Ported from ui/screens/HighwayScreen.kt and
// HighwayFx.kt. Needs highway-engine.js and render.js (drawCitySkylineSprite, drawRoadHaze,
// drawGroundShadow, hexA, updateFxQuality / fxState) to be loaded first.

const HW_STRIPE_LENGTH = 2;

// Lane dividers are short dashes, like a real highway: one dash per HW_DASH_PERIOD world units. Far
// away a dash is thinner than a screen row and would shimmer, so past HW_DASH_FADE_DISTANCE the
// divider is drawn as a faint continuous line instead.
const HW_DASH_PERIOD = 1.5;
const HW_DASH_FRACTION = 0.33;
const HW_DASH_FADE_DISTANCE = 9;
const HW_GRASS = '#2E7D32';
const HW_GRASS_DARK = '#276B2A';
const HW_RUMBLE_RED = '#C4343A';
const HW_RUMBLE_WHITE = '#D8D8D8';
/** Scale applied to the road layer so its vibration never exposes an empty edge. */
const HW_SHAKE_ZOOM = 1.02;
const HW_MAX_LEAN_DEGREES = 4;
const HW_VIGNETTE_MAX_ALPHA = 0.5;

const hwStrips = new RoadStrips();

/** Blends two #RRGGBB colors; t = 0 gives a, t = 1 gives b. */
function mixHex(a, b, t) {
  const pa = parseInt(a.slice(1), 16);
  const pb = parseInt(b.slice(1), 16);
  const ch = (shift) => Math.round(((pa >> shift) & 255) * (1 - t) + ((pb >> shift) & 255) * t);
  return `rgb(${ch(16)},${ch(8)},${ch(0)})`;
}

const HW_ROADSIDE_SPACING = 5;
/** Lateral position (road half-widths) of lamp posts and bushes, just outside the rumble strip. */
const HW_LAMP_LATERAL = 1.34;
const HW_BUSH_LATERAL = 1.62;

/**
 * Lamp posts on one side and bushes on the other, alternating, scrolling past at the road's own
 * pace — nothing tells you how fast you are going like objects whizzing by at the edge. Drawn
 * before the road haze so far ones fade into it.
 */
function drawHighwayRoadside(ctx, h, cameraZ, horizonY, rowH, city) {
  const roadH = h - horizonY;
  const posts = new Path2D();
  const lamps = new Path2D();
  const bushes = new Path2D();
  const firstIndex = Math.floor(cameraZ / HW_ROADSIDE_SPACING) + 1;
  const slots = Math.floor(HW_DRAW_DISTANCE / HW_ROADSIDE_SPACING) + 1;
  for (let n = slots; n >= 0; n--) { // far to near
    const index = firstIndex + n;
    const dz = index * HW_ROADSIDE_SPACING - cameraZ;
    if (dz < HW_D0 || dz > HW_DRAW_DISTANCE) continue;
    const y = horizonY + roadH * (HW_D0 / dz);
    const i = hwStrips.indexForY(y, h, rowH);
    const hw = hwStrips.halfWidth[i];
    const cx = hwStrips.centerX[i];
    const lampSide = index % 2 === 0 ? -1 : 1;

    const postW = Math.max(1, hw * 0.035);
    const postH = hw * 1.5;
    const lampX = cx + lampSide * HW_LAMP_LATERAL * hw;
    posts.rect(lampX - postW / 2, y - postH, postW, postH);
    // The arm reaches over the road, with the light at its tip.
    const armEndX = lampX - lampSide * hw * 0.26;
    posts.rect(Math.min(lampX, armEndX), y - postH, Math.abs(armEndX - lampX), postW);
    const lampW = hw * 0.11;
    lamps.rect(armEndX - lampW / 2, y - postH + postW, lampW, postW * 1.2);

    const bushR = hw * 0.16;
    const bushX = cx - lampSide * HW_BUSH_LATERAL * hw;
    bushes.moveTo(bushX + bushR * 1.3, y - bushR * 0.75);
    bushes.ellipse(bushX, y - bushR * 0.75, bushR * 1.3, bushR * 0.75, 0, 0, Math.PI * 2);
  }
  ctx.fillStyle = mixHex('#1F5E27', city.haze, 0.2);
  ctx.fill(bushes);
  ctx.fillStyle = mixHex('#3A3F4B', city.haze, 0.15);
  ctx.fill(posts);
  ctx.fillStyle = '#FFE9A8';
  ctx.fill(lamps);
}

const HW_SPEED_LINE_COUNT = 14;

/**
 * Streaks rushing outward from near the vanishing point, only once you are well past cruising
 * speed (speedRatio 0..1 of the top speed), so the top end feels different from the start.
 */
function drawHighwaySpeedLines(ctx, w, h, speedRatio, elapsedSeconds, focalY) {
  const strength = Math.min(1, Math.max(0, (speedRatio - 0.6) / 0.4));
  if (strength <= 0.02) return;
  const cx = w / 2;
  const maxR = Math.hypot(cx, h - focalY);
  const stroke = Math.max(2, w * 0.0035);
  ctx.lineCap = 'butt';
  for (let i = 0; i < HW_SPEED_LINE_COUNT; i++) {
    const phase = (elapsedSeconds * 1.9 + i * 0.371) % 1;
    const side = i % 2 === 0 ? 1 : -1;
    const spread = (((i * 53) % 100) / 100 - 0.5) * 1.7; // ~ -49..49 degrees in radians
    const dirX = side * Math.cos(spread);
    const dirY = Math.sin(spread);
    const r0 = maxR * (0.28 + 0.62 * phase);
    const r1 = r0 + maxR * (0.04 + 0.14 * phase);
    ctx.strokeStyle = `rgba(255,255,255,${(strength * 0.42 * phase).toFixed(3)})`;
    ctx.lineWidth = stroke * (0.6 + phase);
    ctx.beginPath();
    ctx.moveTo(cx + dirX * r0, focalY + dirY * r0);
    ctx.lineTo(cx + dirX * r1, focalY + dirY * r1);
    ctx.stroke();
  }
}

/** Rear-view sprite, bottom-anchored; the height follows the image's own aspect ratio. */
function drawHighwayRearSprite(ctx, img, cx, bottomY, width, leanRadians, fallbackColor) {
  if (!img || !img.complete || img.naturalWidth === 0) {
    const height = width * 0.8;
    drawGroundShadow(ctx, cx, bottomY, width, height);
    ctx.fillStyle = fallbackColor;
    ctx.fillRect(cx - width / 2, bottomY - height, width, height);
    return;
  }
  const height = width * img.naturalHeight / img.naturalWidth;
  drawGroundShadow(ctx, cx, bottomY, width, height);
  // Leaning pivots on the tires' contact line so the car rolls into the turn, not off its shadow.
  ctx.save();
  ctx.translate(cx, bottomY);
  ctx.rotate(leanRadians);
  ctx.drawImage(img, -width / 2, -height, width, height);
  ctx.restore();
}

let hwVignette = { key: '', gradient: null };

/** Tunnel vision: the screen edges darken as the speed climbs. */
function drawHighwayVignette(ctx, w, h, speedRatio) {
  const alpha = Math.min(1, Math.max(0, (speedRatio - 0.5) / 0.5)) * HW_VIGNETTE_MAX_ALPHA;
  if (alpha <= 0.01) return;
  const key = `${w}x${h}`;
  if (hwVignette.key !== key) {
    const g = ctx.createRadialGradient(w / 2, h * 0.55, 0, w / 2, h * 0.55, Math.hypot(w, h) * 0.55);
    g.addColorStop(0.5, 'rgba(0,0,0,0)');
    g.addColorStop(1, 'rgba(0,0,0,1)');
    hwVignette = { key, gradient: g };
  }
  ctx.globalAlpha = alpha;
  ctx.fillStyle = hwVignette.gradient;
  ctx.fillRect(0, 0, w, h);
  ctx.globalAlpha = 1;
}

/** Draws one full highway frame. `images` maps sprite path -> HTMLImageElement. */
function drawHighwayFrame(ctx, w, h, engine, city, car, images) {
  const horizonY = h * HW_HORIZON_FRACTION;
  const roadH = h - horizonY;
  const rowH = Math.max(3, roadH / 110);
  const speedRatio = Math.min(1, Math.max(0, engine.speed / HW.MAX_SPEED));
  const fancy = !fxState.low;

  // Road vibration: grows with speed. The slight zoom
  // keeps the shifted frame from showing an empty edge.
  const amplitude = h * 0.0009 * speedRatio * speedRatio;
  const shakeY = (Math.sin(engine.z * 1.7) * 0.6 + Math.sin(engine.z * 4.1) * 0.4) * amplitude;
  ctx.save();
  ctx.translate(w / 2, h / 2 + shakeY);
  ctx.scale(HW_SHAKE_ZOOM, HW_SHAKE_ZOOM);
  ctx.translate(-w / 2, -h / 2);

  const drewSky = drawCitySkylineSprite(ctx, images[city.sprite], w, horizonY, engine.heading * w * 1.2, 1);
  if (!drewSky) {
    const grad = ctx.createLinearGradient(0, 0, 0, horizonY);
    grad.addColorStop(0, city.skyTop);
    grad.addColorStop(1, city.skyBottom);
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, w, horizonY);
  }

  ctx.fillStyle = mixHex(HW_GRASS, city.haze, 0.2);
  ctx.fillRect(0, horizonY, w, roadH);

  hwStrips.compute(engine.z, engine.playerX, w, horizonY, h, rowH, HW_STRIPE_LENGTH);
  const darkGrass = new Path2D();
  const roadLight = new Path2D();
  const roadDark = new Path2D();
  const rumbleRed = new Path2D();
  const rumbleWhite = new Path2D();
  const laneMarks = new Path2D();
  const laneMarksFar = new Path2D();
  for (let i = 0; i < hwStrips.count; i++) {
    const top = hwStrips.yTop[i];
    const height = hwStrips.yBottom[i] + 0.5 - top;
    const cx = hwStrips.centerX[i];
    const hw = hwStrips.halfWidth[i];
    const odd = hwStrips.band[i] === 1;
    if (odd) darkGrass.rect(0, top, w, height);
    const rumble = hw * 0.14;
    const rumblePath = odd ? rumbleWhite : rumbleRed;
    rumblePath.rect(cx - hw - rumble, top, rumble, height);
    rumblePath.rect(cx + hw, top, rumble, height);
    (odd ? roadDark : roadLight).rect(cx - hw, top, hw * 2, height);
    const depth = hwStrips.dz[i];
    const dashPhase = (engine.z + depth) / HW_DASH_PERIOD;
    const far = depth > HW_DASH_FADE_DISTANCE;
    if (far || dashPhase - Math.floor(dashPhase) < HW_DASH_FRACTION) {
      const half = hw * 0.02;
      const marks = far ? laneMarksFar : laneMarks;
      for (let divider = 1; divider < HW_LANE_COUNT; divider++) {
        marks.rect(cx + (-1 + HW_LANE_SPACING * divider) * hw - half, top, half * 2, height);
      }
    }
  }
  ctx.fillStyle = mixHex(HW_GRASS_DARK, city.haze, 0.2);
  ctx.fill(darkGrass);
  ctx.fillStyle = HW_RUMBLE_RED;
  ctx.fill(rumbleRed);
  ctx.fillStyle = HW_RUMBLE_WHITE;
  ctx.fill(rumbleWhite);
  ctx.fillStyle = mixHex(city.roadColor, '#FFFFFF', 0.10);
  ctx.fill(roadLight);
  ctx.fillStyle = mixHex(city.roadColor, '#FFFFFF', 0.05);
  ctx.fill(roadDark);
  ctx.fillStyle = hexA(city.roadLineColor, 0.4);
  ctx.fill(laneMarksFar);
  ctx.fillStyle = hexA(city.roadLineColor, 0.9);
  ctx.fill(laneMarks);

  drawHighwayRoadside(ctx, h, engine.z, horizonY, rowH, city);
  if (fancy) {
    drawRoadHaze(ctx, city, w, horizonY, roadH);
    drawHighwaySpeedLines(ctx, w, h, speedRatio, engine.elapsedSeconds, horizonY + roadH * 0.2);
  }

  // Traffic, far to near so closer cars paint over farther ones.
  const sorted = engine.traffic.slice().sort((a, b) => b.z - a.z);
  for (const traffic of sorted) {
    const dz = traffic.z - engine.z;
    if (dz < 1 || dz > HW_DRAW_DISTANCE) continue;
    const y = horizonY + roadH * (HW_D0 / dz);
    const idx = hwStrips.indexForY(y, h, rowH);
    const width = traffic.kind.halfWidth * 2 * hwStrips.halfWidth[idx];
    const cx = hwStrips.centerX[idx] + traffic.lx * hwStrips.halfWidth[idx];
    drawHighwayRearSprite(ctx, images[traffic.kind.sprite], cx, y, width, 0, traffic.kind.color);
  }

  // The player stays at the screen's horizontal center; the road slides under them.
  const playerY = horizonY + roadH * HW_PLAYER_T;
  const playerIdx = hwStrips.indexForY(playerY, h, rowH);
  const playerWidth = HW.PLAYER_DRAW_WIDTH * hwStrips.halfWidth[playerIdx];
  drawHighwayRearSprite(
    ctx, images[car.rearSprite], w / 2, playerY, playerWidth,
    engine.steerLean * HW_MAX_LEAN_DEGREES * Math.PI / 180, '#E63946',
  );

  ctx.restore();
  if (fancy) drawHighwayVignette(ctx, w, h, speedRatio);
}
