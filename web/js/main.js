// App shell: screen navigation, asset preloading, select-screen previews, and the game loop.

// ---------- APP FRAME SIZING ----------
// The whole app (every screen, not just gameplay) lives inside a fixed-aspect "device"
// frame instead of stretching edge-to-edge across the browser window — see #app-frame in
// style.css. It fills as much of the browser window as it can while keeping GAME_ASPECT
// (catalog.js — also what the obstacle hitbox precomputation keys off, so the two must
// stay in sync), letterboxing rather than stretching when the window's own ratio differs.
const appFrame = document.getElementById('app-frame');

function resizeAppFrame() {
  const availW = window.innerWidth;
  const availH = window.innerHeight;
  let w = availW;
  let h = w / GAME_ASPECT;
  if (h > availH) {
    h = availH;
    w = h * GAME_ASPECT;
  }
  w = Math.round(w);
  h = Math.round(h);
  appFrame.style.width = w + 'px';
  appFrame.style.height = h + 'px';
  const canvas = document.getElementById('game-canvas');
  canvas.width = w;
  canvas.height = h;
}
window.addEventListener('resize', resizeAppFrame);
resizeAppFrame();

const HIGH_SCORE_KEY = 'lane_dash_high_score';

const state = {
  selectedCityId: CITIES[0].id,
  selectedCarId: CARS[0].id,
  highScore: Number(localStorage.getItem(HIGH_SCORE_KEY) || 0),
};

// Bump this whenever an asset file is replaced in place (same filename, new pixels) — the
// dev server sends no cache-control headers, so browsers otherwise keep serving stale
// bytes for a path they've already fetched, same issue as the JS/CSS ?v= busters.
const ASSET_VERSION = 2;

const images = {};
function preloadImage(path) {
  if (images[path]) return images[path];
  const img = new Image();
  img.src = path + '?v=' + ASSET_VERSION;
  images[path] = img;
  return img;
}
[...CITIES.map(c => c.sprite), ...CARS.map(c => c.sprite), ...Object.values(OBSTACLE_VARIANTS).map(v => v.sprite)]
  .forEach(preloadImage);

// Refines the hand-computed hitHalfWidthUnits fallbacks in catalog.js against each
// sprite's real natural size, once it's actually loaded (see computeObstacleHitHalfWidthUnits
// in render.js). Fire-and-forget: gameplay can't start until the player clicks through
// three screens, so this always wins the race in practice, and the fallbacks cover it either way.
(async () => {
  for (const variant of Object.values(OBSTACLE_VARIANTS)) {
    const img = images[variant.sprite];
    try {
      await img.decode();
    } catch {
      continue;
    }
    if (img.naturalWidth > 0 && img.naturalHeight > 0) {
      variant.hitHalfWidthUnits = computeObstacleHitHalfWidthUnits(
        variant.hitWidthScale,
        variant.heightScale,
        img.naturalWidth / img.naturalHeight,
      );
    }
  }
})();

const audio = new AudioManager();

function showScreen(id) {
  document.querySelectorAll('.screen').forEach(el => el.classList.remove('active'));
  document.getElementById(id).classList.add('active');
}

function reportScore(score) {
  if (score > state.highScore) {
    state.highScore = score;
    localStorage.setItem(HIGH_SCORE_KEY, String(score));
  }
}

// ---------- SOUND SETTINGS ----------
const settingsPanel = document.getElementById('settings-panel');
const bgmVolumeInput = document.getElementById('bgm-volume');
const sfxVolumeInput = document.getElementById('sfx-volume');
const bgmVolumeValue = document.getElementById('bgm-volume-value');
const sfxVolumeValue = document.getElementById('sfx-volume-value');

function syncSettingsUI() {
  bgmVolumeInput.value = Math.round(audio.bgmVolume * 100);
  sfxVolumeInput.value = Math.round(audio.sfxVolume * 100);
  bgmVolumeValue.textContent = bgmVolumeInput.value;
  sfxVolumeValue.textContent = sfxVolumeInput.value;
}

document.getElementById('btn-settings').addEventListener('click', () => {
  audio.unlock();
  syncSettingsUI();
  settingsPanel.classList.remove('hidden');
});
document.getElementById('btn-settings-close').addEventListener('click', () => {
  settingsPanel.classList.add('hidden');
});

bgmVolumeInput.addEventListener('input', () => {
  audio.setBgmVolume(Number(bgmVolumeInput.value) / 100);
  bgmVolumeValue.textContent = bgmVolumeInput.value;
});
sfxVolumeInput.addEventListener('input', () => {
  audio.setSfxVolume(Number(sfxVolumeInput.value) / 100);
  sfxVolumeValue.textContent = sfxVolumeInput.value;
  audio.play('LANE_CHANGE'); // immediate preview so the change is audible
});

// ---------- HOME ----------
function refreshHomeBest() {
  const el = document.getElementById('home-best');
  el.textContent = state.highScore > 0 ? `BEST  ${state.highScore}` : '';
}

document.getElementById('btn-play').addEventListener('click', () => {
  audio.unlock();
  showScreen('screen-city');
});

// ---------- CITY SELECT ----------
function buildCityGrid() {
  const grid = document.getElementById('city-grid');
  grid.innerHTML = '';
  for (const city of CITIES) {
    const card = document.createElement('div');
    card.className = 'pick-card' + (city.id === state.selectedCityId ? ' selected' : '');
    card.innerHTML = `
      <div class="thumb"><canvas width="300" height="231"></canvas></div>
      <p class="name">${city.name}</p>
      <p class="name-en">${city.nameEn}</p>
    `;
    card.addEventListener('click', () => {
      state.selectedCityId = city.id;
      buildCityGrid();
    });
    grid.appendChild(card);

    const canvas = card.querySelector('canvas');
    const draw = () => {
      const ctx = canvas.getContext('2d');
      const w = canvas.width, h = canvas.height;
      const horizonY = h * 0.82;
      ctx.clearRect(0, 0, w, h);
      const drew = drawCitySkylineSprite(ctx, images[city.sprite], w, horizonY, 0, 1);
      if (!drew) {
        const grad = ctx.createLinearGradient(0, 0, 0, horizonY);
        grad.addColorStop(0, city.skyTop);
        grad.addColorStop(1, city.skyBottom);
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, w, horizonY);
      }
      ctx.fillStyle = city.roadColor;
      ctx.fillRect(0, horizonY, w, h - horizonY);
    };
    draw();
    images[city.sprite].addEventListener('load', draw, { once: true });
  }
}

document.getElementById('btn-city-next').addEventListener('click', () => {
  showScreen('screen-car');
});

// ---------- CAR SELECT ----------
function buildCarGrid() {
  const grid = document.getElementById('car-grid');
  grid.innerHTML = '';
  for (const car of CARS) {
    const card = document.createElement('div');
    card.className = 'pick-card' + (car.id === state.selectedCarId ? ' selected' : '');
    card.innerHTML = `
      <div class="thumb"><canvas width="300" height="231"></canvas></div>
      <p class="name">${car.name}</p>
      <p class="name-en">${car.nameEn}</p>
    `;
    card.addEventListener('click', () => {
      state.selectedCarId = car.id;
      buildCarGrid();
    });
    grid.appendChild(card);

    const canvas = card.querySelector('canvas');
    const draw = () => {
      const ctx = canvas.getContext('2d');
      const w = canvas.width, h = canvas.height;
      ctx.clearRect(0, 0, w, h);
      drawFittedSprite(ctx, images[car.sprite], w / 2, h * 0.55, w * 0.8, h * 0.7);
    };
    draw();
    images[car.sprite].addEventListener('load', draw, { once: true });
  }
}

document.getElementById('btn-car-start').addEventListener('click', () => {
  startGame();
});

// ---------- GAME ----------
const canvas = document.getElementById('game-canvas');
const ctx = canvas.getContext('2d');
let engine = null;
let animatedLane = Math.floor(LANE_COUNT / 2);
let lastFrameTime = 0;
let rafId = null;

function startGame() {
  showScreen('screen-game');
  document.getElementById('gameover-overlay').classList.add('hidden');

  engine = new GameEngine((event) => audio.play(event));
  engine.start();
  animatedLane = engine.laneIndex;
  audio.startBgm();

  lastFrameTime = performance.now();
  if (rafId) cancelAnimationFrame(rafId);
  rafId = requestAnimationFrame(loop);
}

function loop(now) {
  const dt = Math.min(0.05, (now - lastFrameTime) / 1000);
  lastFrameTime = now;

  if (engine.status === 'RUNNING') {
    engine.tick(dt);
  }

  // Lane tween, similar feel to the app's 160ms animateFloatAsState.
  const smoothing = 1 - Math.pow(0.001, dt / 0.16);
  animatedLane += (engine.laneIndex - animatedLane) * smoothing;

  const city = cityById(state.selectedCityId);
  const car = carById(state.selectedCarId);
  drawFrame(ctx, canvas.width, canvas.height, engine, city, car, images, animatedLane);
  updateHud();

  if (engine.status === 'GAME_OVER') {
    showGameOver();
    return; // stop the loop; overlay buttons restart it
  }
  rafId = requestAnimationFrame(loop);
}

function updateHud() {
  const speedKmh = Math.round(engine.speed * 20);
  document.getElementById('hud-speed').textContent = `${speedKmh} KM/H`;
  document.getElementById('hud-score').textContent = `SCORE ${engine.score}`;
  document.getElementById('hud-best').textContent = `BEST ${state.highScore}`;
  const fuelPct = Math.max(0, Math.min(100, engine.fuel));
  const fuelBar = document.getElementById('fuel-bar');
  fuelBar.style.width = fuelPct + '%';
  fuelBar.style.background = fuelPct > 50 ? '#4CAF50' : fuelPct > 20 ? '#FFC93C' : '#E63946';
}

// Score from the run that just ended, plus what (if anything) got submitted for it — lets
// the leaderboard screen highlight the player's own new row. Mirrors lastRunScore /
// justSubmittedName in GameSessionViewModel.kt.
let lastRunScore = 0;
let justSubmittedName = null;

function showGameOver() {
  lastRunScore = engine.score;
  justSubmittedName = null;
  reportScore(engine.score);
  document.getElementById('gameover-reason').textContent =
    engine.gameOverReason === 'FUEL_EMPTY' ? '연료 소진' : '충돌 발생';
  document.getElementById('gameover-score').textContent = `SCORE ${engine.score}`;
  document.getElementById('gameover-best').textContent =
    engine.score >= state.highScore ? 'NEW BEST!' : `BEST ${state.highScore}`;

  const qualifyBlock = document.getElementById('gameover-qualify');
  qualifyBlock.classList.add('hidden');
  document.getElementById('score-name-input').value = '';
  const submitBtn = document.getElementById('btn-submit-score');
  submitBtn.disabled = false;
  submitBtn.textContent = '등록하기';

  document.getElementById('gameover-overlay').classList.remove('hidden');

  const score = engine.score;
  qualifiesForTop(score)
    .then(qualifies => {
      if (score !== lastRunScore) return; // a retry/new run started while this was in flight
      if (qualifies) {
        qualifyBlock.classList.remove('hidden');
        audio.play('NEW_BEST');
      }
    })
    .catch(e => console.error('qualifiesForTop failed', e));
}

document.getElementById('btn-submit-score').addEventListener('click', () => {
  const submitBtn = document.getElementById('btn-submit-score');
  const name = document.getElementById('score-name-input').value;
  const score = lastRunScore;
  submitBtn.disabled = true;
  submitBtn.textContent = '등록 중...';
  submitScore(name, score)
    .then(cleanName => { justSubmittedName = cleanName; })
    .catch(e => console.error('submitScore failed', e))
    .finally(() => {
      document.getElementById('gameover-overlay').classList.add('hidden');
      showLeaderboardScreen(justSubmittedName, score);
    });
});

document.getElementById('btn-view-leaderboard').addEventListener('click', () => {
  justSubmittedName = null;
  document.getElementById('gameover-overlay').classList.add('hidden');
  showLeaderboardScreen(null, lastRunScore);
});

document.getElementById('btn-retry').addEventListener('click', () => {
  document.getElementById('gameover-overlay').classList.add('hidden');
  engine.start();
  lastFrameTime = performance.now();
  rafId = requestAnimationFrame(loop);
});

document.getElementById('btn-gameover-home').addEventListener('click', goHome);

function goHome() {
  audio.stopBgm();
  if (rafId) cancelAnimationFrame(rafId);
  document.getElementById('gameover-overlay').classList.add('hidden');
  refreshHomeBest();
  showScreen('screen-home');
}

// ---------- LEADERBOARD ----------
function showLeaderboardScreen(highlightName, highlightScore) {
  showScreen('screen-leaderboard');
  const list = document.getElementById('leaderboard-list');
  list.innerHTML = '<div class="leaderboard-status">불러오는 중...</div>';

  fetchTopScores(10)
    .then(entries => {
      if (entries.length === 0) {
        list.innerHTML = '<div class="leaderboard-status">아직 기록이 없어요.\n첫 번째 기록을 남겨보세요!</div>';
        return;
      }
      list.innerHTML = '';
      entries.forEach((entry, index) => {
        const rank = index + 1;
        const row = document.createElement('div');
        const highlighted = highlightName != null && entry.name === highlightName && entry.score === highlightScore;
        row.className = 'leaderboard-row' + (highlighted ? ' highlighted' : '');
        row.innerHTML = `
          <span class="leaderboard-rank${rank <= 3 ? ' rank-' + rank : ''}">#${rank}</span>
          <span class="leaderboard-name"></span>
          <span class="leaderboard-score"></span>
        `;
        row.querySelector('.leaderboard-name').textContent = entry.name;
        row.querySelector('.leaderboard-score').textContent = entry.score;
        list.appendChild(row);
      });
    })
    .catch(e => {
      console.error('fetchTopScores failed', e);
      list.innerHTML = '<div class="leaderboard-status">순위표를 불러오지 못했어요.\n네트워크 연결을 확인해주세요.</div>';
    });
}

document.getElementById('btn-leaderboard-home').addEventListener('click', () => {
  refreshHomeBest();
  showScreen('screen-home');
});

// ---------- Controls: keyboard + on-screen buttons ----------
document.getElementById('btn-lane-up').addEventListener('click', () => engine && engine.moveLaneUp());
document.getElementById('btn-lane-down').addEventListener('click', () => engine && engine.moveLaneDown());

window.addEventListener('keydown', (e) => {
  if (!engine || document.getElementById('screen-game').classList.contains('active') === false) return;
  if (e.key === 'ArrowUp' || e.key === 'w' || e.key === 'W') {
    e.preventDefault();
    engine.moveLaneUp();
  } else if (e.key === 'ArrowDown' || e.key === 's' || e.key === 'S') {
    e.preventDefault();
    engine.moveLaneDown();
  }
});

// ---------- Boot ----------
refreshHomeBest();
buildCityGrid();
buildCarGrid();
syncSettingsUI();
showScreen('screen-home');
