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

// ---------- VIBRATION ----------
// Short buzzes on crashes / pickups where the browser supports it (mostly Android Chrome);
// mirrors HapticsManager.kt. Silently does nothing elsewhere.
const VIBRATION_KEY = 'lane_dash_vibration';
let vibrationEnabled = localStorage.getItem(VIBRATION_KEY) !== 'off';
const VIBRATION_MS = { CRASH: 220, FUEL_EMPTY: 120, NEAR_MISS: 25, COIN: 25, POWERUP: 25, SHIELD_HIT: 25 };

function vibrateFor(event) {
  const ms = VIBRATION_MS[event];
  if (!vibrationEnabled || !ms || !navigator.vibrate) return;
  navigator.vibrate(ms);
}

// Shared game-over / share constants — keep in sync with GameScreen.kt.
const WEB_PLAY_URL = 'https://kwanghyeon-cpu.github.io/LaneDash/web/';
const STORE_URL = 'https://play.google.com/store/apps/details?id=com.khcompany.lanedash';

// Completed runs today (resets at local midnight), shown on the game-over card.
const PLAYS_DAY_KEY = 'lane_dash_plays_day';
const PLAYS_TODAY_KEY = 'lane_dash_plays_today';

function todayKey() {
  const d = new Date();
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

function recordPlayAndGetToday() {
  const today = todayKey();
  const count = (localStorage.getItem(PLAYS_DAY_KEY) === today ? Number(localStorage.getItem(PLAYS_TODAY_KEY) || 0) : 0) + 1;
  localStorage.setItem(PLAYS_DAY_KEY, today);
  localStorage.setItem(PLAYS_TODAY_KEY, String(count));
  return count;
}

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

const vibrationToggle = document.getElementById('vibration-toggle');

function syncSettingsUI() {
  bgmVolumeInput.value = Math.round(audio.bgmVolume * 100);
  sfxVolumeInput.value = Math.round(audio.sfxVolume * 100);
  bgmVolumeValue.textContent = bgmVolumeInput.value;
  sfxVolumeValue.textContent = sfxVolumeInput.value;
  vibrationToggle.checked = vibrationEnabled;
}

vibrationToggle.addEventListener('change', () => {
  vibrationEnabled = vibrationToggle.checked;
  localStorage.setItem(VIBRATION_KEY, vibrationEnabled ? 'on' : 'off');
  if (vibrationEnabled && navigator.vibrate) navigator.vibrate(30);
});

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
      <p class="trait">${city.traitText}</p>
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
let lastNearMissCount = 0;

function startGame() {
  showScreen('screen-game');
  document.getElementById('gameover-overlay').classList.add('hidden');
  document.getElementById('pause-overlay').classList.add('hidden');

  engine = new GameEngine((event) => {
    audio.play(event);
    vibrateFor(event);
  });
  engine.start(cityById(state.selectedCityId).traits);
  animatedLane = engine.laneIndex;
  lastNearMissCount = 0;
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

function setPowerupChip(id, timeLeft, label) {
  const el = document.getElementById(id);
  el.classList.toggle('hidden', timeLeft <= 0);
  if (timeLeft > 0) el.textContent = `${label} ${Math.ceil(timeLeft)}초`;
}

function showToast(text) {
  const el = document.getElementById('hud-toast');
  el.textContent = text;
  el.classList.remove('show');
  void el.offsetWidth; // restart the CSS animation if it's already mid-flight
  el.classList.add('show');
}

function updateHud() {
  const speedKmh = Math.round(engine.effectiveSpeed * 20);
  document.getElementById('hud-speed').textContent = `${speedKmh} KM/H`;
  setPowerupChip('hud-shield', engine.shieldTimeLeft, '🛡 방어막');
  setPowerupChip('hud-magnet', engine.magnetTimeLeft, '🧲 자석');
  if (engine.nearMissCount > lastNearMissCount) showToast('NEAR MISS!  +30');
  lastNearMissCount = engine.nearMissCount;
  document.getElementById('btn-pause').classList.toggle('hidden', engine.status !== 'RUNNING' || engine.paused);
  document.getElementById('pause-overlay').classList.toggle('hidden', !(engine.status === 'RUNNING' && engine.paused));
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
  document.getElementById('gameover-stats').textContent =
    `생존 ${Math.floor(engine.elapsedSeconds)}초 · 근접 회피 ${engine.nearMissCount}회 · 연료 ${engine.fuelCollected}개`;
  document.getElementById('gameover-plays').textContent = `오늘 ${recordPlayAndGetToday()}번째 판`;
  document.getElementById('gameover-top').textContent = '';

  const qualifyBlock = document.getElementById('gameover-qualify');
  qualifyBlock.classList.add('hidden');
  document.getElementById('score-name-input').value = '';
  const submitBtn = document.getElementById('btn-submit-score');
  submitBtn.disabled = false;
  submitBtn.textContent = '등록하기';

  document.getElementById('gameover-overlay').classList.remove('hidden');

  const score = engine.score;
  // Same rule as qualifiesForTop in leaderboard.js, but keeping the list lets us also say
  // how far a non-qualifying run was from the TOP 10 cutoff.
  fetchTopScores(10)
    .then(top => {
      if (score !== lastRunScore) return; // a retry/new run started while this was in flight
      const qualifies = top.length < 10 || score > top[top.length - 1].score;
      if (qualifies) {
        qualifyBlock.classList.remove('hidden');
        audio.play('NEW_BEST');
      } else {
        document.getElementById('gameover-top').textContent =
          `TOP 10까지 ${top[top.length - 1].score - score + 1}점!`;
      }
    })
    .catch(e => console.error('fetchTopScores failed', e));
}

document.getElementById('btn-share').addEventListener('click', async () => {
  const best = Math.max(state.highScore, lastRunScore);
  const city = cityById(state.selectedCityId);
  const text = `🚗💨 Lane Dash — ${city.name}에서 ${lastRunScore}점! (내 최고 ${best}점)\n` +
    `웹에서 바로 플레이 → ${WEB_PLAY_URL}\nGoogle Play → ${STORE_URL}`;
  const btn = document.getElementById('btn-share');
  try {
    if (navigator.share) {
      await navigator.share({ text });
    } else {
      await navigator.clipboard.writeText(text);
      btn.textContent = '복사됨!';
      setTimeout(() => { btn.textContent = '공유하기'; }, 1500);
    }
  } catch (e) {
    if (e && e.name !== 'AbortError') console.error('share failed', e);
  }
});

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
  restartRun();
});

function restartRun() {
  engine.start(cityById(state.selectedCityId).traits);
  lastNearMissCount = 0;
  lastFrameTime = performance.now();
  if (rafId) cancelAnimationFrame(rafId);
  rafId = requestAnimationFrame(loop);
}

// ---------- PAUSE ----------
function pauseGame() {
  if (!engine || engine.status !== 'RUNNING' || engine.paused) return;
  engine.pause();
  audio.pauseBgm();
}

function resumeGame() {
  if (!engine || !engine.paused) return;
  engine.resume();
  audio.resumeBgm();
  lastFrameTime = performance.now();
}

document.getElementById('btn-pause').addEventListener('click', pauseGame);
document.getElementById('btn-resume').addEventListener('click', resumeGame);
document.getElementById('btn-pause-restart').addEventListener('click', () => {
  document.getElementById('pause-overlay').classList.add('hidden');
  audio.resumeBgm();
  restartRun();
});
document.getElementById('btn-pause-home').addEventListener('click', goHome);

// A hidden tab throttles requestAnimationFrame, so pause instead of letting the run drift.
document.addEventListener('visibilitychange', () => {
  if (document.hidden) pauseGame();
});

document.getElementById('btn-gameover-home').addEventListener('click', goHome);

function goHome() {
  audio.stopBgm();
  if (rafId) cancelAnimationFrame(rafId);
  document.getElementById('gameover-overlay').classList.add('hidden');
  document.getElementById('pause-overlay').classList.add('hidden');
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
  } else if (e.key === 'p' || e.key === 'P' || e.key === 'Escape') {
    e.preventDefault();
    if (engine.paused) resumeGame(); else pauseGame();
  }
});

// ---------- Boot ----------
refreshHomeBest();
buildCityGrid();
buildCarGrid();
syncSettingsUI();
showScreen('screen-home');
