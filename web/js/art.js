// Drawing helpers shared by the game and the pickers. Ported from app/src/main/java/.../ui/components/GameArt.kt.

function mod(a, b) { return ((a % b) + b) % b; }

/**
 * Draws a tileable skyline image, scaled to exactly fill the sky height and repeated across the
 * screen width using its own aspect ratio for the tile width; scrolls with `parallaxUnits`.
 */
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
