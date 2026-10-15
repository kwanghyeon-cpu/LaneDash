import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const dir = path.dirname(fileURLToPath(import.meta.url));
const Y = '#FFC93C';

const j = await (await fetch('https://api.iconify.design/tabler.json?icons=car-suv')).json();
const car = j.icons['car-suv'];
const CW = car.width || j.width || 24;
const CH = car.height || j.height || 24;

// Tabler's "car-suv" outline glyph at 320px wide, centered at (290,256) — same layout as concept B.
const carW = 320;
const s = carW / CW;
const carG = `<g transform="translate(${290 - carW / 2} ${256 - (CH * s) / 2}) scale(${s})" style="color:${Y}" stroke-width="1.6">${car.body}</g>`;

const content = `
<g stroke="${Y}" stroke-linecap="round" stroke-width="16" opacity="0.55">
<line x1="40" y1="212" x2="118" y2="212"/><line x1="64" y1="256" x2="124" y2="256"/><line x1="40" y1="300" x2="118" y2="300"/></g>
${carG}
<line x1="110" y1="408" x2="402" y2="408" stroke="${Y}" stroke-width="14" stroke-linecap="round" stroke-dasharray="1 38" opacity="0.8"/>`;

const bgDefs = `<defs><radialGradient id="g" cx="0.5" cy="0.45" r="0.7"><stop offset="0" stop-color="#4B2A78"/><stop offset="1" stop-color="#1B1035"/></radialGradient></defs>`;
const bgRect = `<rect width="512" height="512" fill="url(#g)"/>`;
const wrap = (inner) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">${inner}</svg>`;

const write = (name, svg) => fs.writeFileSync(path.join(dir, name), svg);
const render = (svg, png, size = 512) =>
  execFileSync('magick', ['-background', 'none', '-density', '288', svg, '-resize', `${size}x${size}`, '-depth', '8', `PNG32:${png}`]);

// 1) Store icon (full-bleed square; Google Play applies its own corner mask).
write('f_store.svg', wrap(bgDefs + bgRect + content));
render(path.join(dir, 'f_store.svg'), path.join(dir, 'f_store.png'));

// 2) Background layer: gradient only.
write('f_bg.svg', wrap(bgDefs + bgRect));
render(path.join(dir, 'f_bg.svg'), path.join(dir, 'f_bg.png'));

// 3) Foreground layer: the artwork, centered and scaled into the adaptive-icon safe zone
// (a circle of 66dp on a 108dp canvas => radius ~156px on a 512px canvas).
write('f_content.svg', wrap(content));
render(path.join(dir, 'f_content.svg'), path.join(dir, 'f_content.png'), 1024);
const bbox = execFileSync('magick', [path.join(dir, 'f_content.png'), '-format', '%@', 'info:']).toString().trim();
const m = bbox.match(/(\d+)x(\d+)\+(\d+)\+(\d+)/);
const [bw, bh, bx, by] = m.slice(1).map(Number).map((v) => v / 2); // 1024px render -> 512 units
const cx = bx + bw / 2;
const cy = by + bh / 2;
const halfDiag = Math.hypot(bw / 2, bh / 2);
const R = 154;
const k = Math.min(1, R / halfDiag);
console.log({ bbox, cx, cy, halfDiag: halfDiag.toFixed(1), scale: k.toFixed(3) });
write('f_fg.svg', wrap(`<g transform="translate(256 256) scale(${k}) translate(${-cx} ${-cy})">${content}</g>`));
render(path.join(dir, 'f_fg.svg'), path.join(dir, 'f_fg.png'));
