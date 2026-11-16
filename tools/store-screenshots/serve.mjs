// Local, throwaway: serves the web build under /web/, the screenshot page at /shots.html, and takes PNG
// uploads (POST /save?name=...) into store-assets/screenshots-<version>/ (default folder below).
// Usage: node tools/store-screenshots/serve.mjs [out-folder-name]
import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const webRoot = path.resolve(here, '..', '..', 'web');
const outDir = path.resolve(here, '..', '..', 'store-assets', process.argv[2] || 'screenshots-3.1');
fs.mkdirSync(outDir, { recursive: true });
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.wav': 'audio/wav' };
http.createServer((q, r) => {
  const url = new URL(q.url, 'http://x');
  if (q.method === 'POST' && url.pathname === '/save') {
    const name = path.basename(url.searchParams.get('name') || 'shot.png');
    const chunks = [];
    q.on('data', (c) => chunks.push(c));
    q.on('end', () => { fs.writeFileSync(path.join(outDir, name), Buffer.concat(chunks)); r.end('saved ' + name); });
    return;
  }
  if (url.pathname === '/shots.html') { r.setHeader('content-type', 'text/html'); return r.end(fs.readFileSync(path.join(here, 'shots.html'))); }
  const p = decodeURIComponent(url.pathname);
  if (!p.startsWith('/web/')) { r.statusCode = 404; return r.end('nf'); }
  const f = path.join(webRoot, p.slice(5));
  if (!f.startsWith(webRoot) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { r.statusCode = 404; return r.end('nf'); }
  r.setHeader('content-type', types[path.extname(f)] || 'application/octet-stream'); r.end(fs.readFileSync(f));
}).listen(8145, '127.0.0.1');
console.log('screenshots server on http://127.0.0.1:8145/shots.html?save=1 -> ' + outDir);
