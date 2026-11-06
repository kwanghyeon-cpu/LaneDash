// Usage: node design/city-backdrops/make-backdrops.cjs   (needs ImageMagick `magick`)
// Turns the four Gemini panoramas into game backdrops: trims the ground strip to a flat color that
// matches the game's grass, makes the left/right edges tile, and writes the PNGs for app and web.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const SRC = path.join(__dirname, 'gemini-originals');
const OUT = path.join(require('os').tmpdir(), 'lanedash-backdrops'); // then copy city_*.png to app/src/main/res/drawable-nodpi and web/assets/cities
fs.mkdirSync(OUT, { recursive: true });

const mix = (a, b, t) => {
  const pa = parseInt(a.slice(1), 16), pb = parseInt(b.slice(1), 16);
  const ch = (s) => Math.round(((pa >> s) & 255) * (1 - t) + ((pb >> s) & 255) * t);
  return '#' + [16, 8, 0].map((s) => ch(s).toString(16).padStart(2, '0')).join('').toUpperCase();
};

const CITIES = [
  { file: 'seoul.webp', id: 'seoul', haze: '#D39A8E' },
  { file: 'paris.webp', id: 'paris', haze: '#2B556B' },
  { file: 'newyork.webp', id: 'newyork', haze: '#9DCBE6' },
  { file: 'tokyo.webp', id: 'tokyo', haze: '#A3B8DA' },
];
const STRIP = 56;  // px of flat ground kept under the skyline
const SEAM = 48;   // px cross-faded so the right edge flows into the left edge

for (const c of CITIES) {
  const src = path.join(SRC, c.file);
  const dims = execFileSync('magick', ['identify', '-format', '%w %h', src]).toString().trim().split(' ').map(Number);
  const [W, H] = dims;
  const raw = execFileSync('magick', [src, '-depth', '8', 'rgb:-'], { maxBuffer: 1 << 28 });
  const px = (x, y) => { const o = (y * W + x) * 3; return raw[o] << 16 | raw[o + 1] << 8 | raw[o + 2]; };
  const near = (a, b) => Math.abs((a >> 16) - (b >> 16)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255)) <= 18;
  // The flat strip color = the pixel at the bottom-left; walk up while the row is (nearly) all that color.
  const flat = px(10, H - 1);
  let g = H - 1;
  for (; g > H / 2; g--) {
    let same = 0, total = 0;
    for (let x = 0; x < W - 160; x += 4) { total++; if (near(px(x, g), flat)) same++; }
    if (same / total < 0.97) break;
  }
  // g is the last non-flat row (the dark outline under the buildings); the strip starts below it.
  const grass = mix('#2E7D32', c.haze, 0.2);
  const keep = g + 1 + STRIP;
  const trimmed = path.join(OUT, c.id + '_trim.png');
  execFileSync('magick', [src, '-crop', `${W}x${keep}+0+0`, '+repage', '-fill', grass, '-draw', `rectangle 0,${g + 1} ${W},${keep}`, trimmed]);

  // Seam: out = [tail faded into head] + the middle, so the last column flows into the first.
  const tail = path.join(OUT, c.id + '_tail.png');
  const head = path.join(OUT, c.id + '_head.png');
  const mid = path.join(OUT, c.id + '_mid.png');
  const blend = path.join(OUT, c.id + '_blend.png');
  execFileSync('magick', [trimmed, '-crop', `${SEAM}x${keep}+${W - SEAM}+0`, '+repage', tail]);
  execFileSync('magick', [trimmed, '-crop', `${SEAM}x${keep}+0+0`, '+repage', head]);
  execFileSync('magick', [trimmed, '-crop', `${W - 2 * SEAM}x${keep}+${SEAM}+0`, '+repage', mid]);
  // head dominates on the right of the seam strip, tail on the left.
  execFileSync('magick', [tail, head, '(', '-size', `${keep}x${SEAM}`, 'gradient:black-white', '-rotate', '-90', ')', '-compose', 'over', '-composite', blend], { stdio: 'pipe' });
  const final = path.join(OUT, `city_${c.id}.png`);
  execFileSync('magick', [blend, mid, '+append', '+repage', '-colors', '160', '-dither', 'None', '-define', 'png:compression-level=9', final]);
  const sz = fs.statSync(final).size;
  console.log(c.id, `src ${W}x${H}, ground row ${g}, out ${W - SEAM}x${keep}`, `grass ${grass}`, `haze ${c.haze}`, (sz / 1e6).toFixed(2) + 'MB');
}
