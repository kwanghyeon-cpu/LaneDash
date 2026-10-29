// App shell: screen navigation, asset preloading, select-screen previews, and the game loop.

// ---------- APP FRAME SIZING ----------
// The whole app (every screen, not just gameplay) lives inside a fixed-aspect "device"
// frame instead of stretching edge-to-edge across the browser window — see #app-frame in
// style.css. It fills as much of the browser window as it can while keeping GAME_ASPECT
// (catalog.js — also what the obstacle hitbox precomputation keys off, so the two must
// stay in sync), letterboxing rather than stretching when the window's own ratio differs.
const appFrame = document.getElementById('app-frame');

// The title and the mode picker are portrait; every screen after them follows the chosen mode
// (classic is landscape, highway is portrait) — the frame switches aspect in showScreen.
const HIGHWAY_ASPECT = 9 / 16;
let frameAspect = GAME_ASPECT;

function setFrameAspect(aspect) {
  if (aspect === frameAspect) return;
  frameAspect = aspect;
  resizeAppFrame();
}

function resizeAppFrame() {
  const availW = window.innerWidth;
  const availH = window.innerHeight;
  let w = availW;
  let h = w / frameAspect;
  if (h > availH) {
    h = availH;
    w = h * frameAspect;
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
const HIGHWAY_HIGH_SCORE_KEY = 'lane_dash_highway_high_score';
const MODE_KEY = 'lane_dash_mode';

const state = {
  // 'classic' (side view, 5 lanes) or 'highway' (behind the car, portrait). Each mode keeps its
  // own best score and leaderboard — their scores aren't comparable.
  mode: localStorage.getItem(MODE_KEY) === 'highway' ? 'highway' : 'classic',
  selectedCityId: CITIES[0].id,
  selectedCarId: CARS[0].id,
  highScore: Number(localStorage.getItem(HIGH_SCORE_KEY) || 0),
  highwayHighScore: Number(localStorage.getItem(HIGHWAY_HIGH_SCORE_KEY) || 0),
};

/** Mode of the run in progress / just finished (state.mode can't change mid-run, but the
 *  leaderboard screen is reachable afterwards, so it keeps its own copy). */
let runMode = state.mode;

function bestFor(mode) { return mode === 'highway' ? state.highwayHighScore : state.highScore; }

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
[
  ...CITIES.map(c => c.sprite),
  ...CARS.map(c => c.sprite),
  ...CARS.map(c => c.rearSprite),
  ...Object.values(OBSTACLE_VARIANTS).map(v => v.sprite),
  ...Object.values(TRAFFIC_KINDS).map(k => k.sprite),
].forEach(preloadImage);

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
      variant.hitHalfWidthUnits = computeObstacleHitHalfWidthUnits(variant, img.naturalWidth / img.naturalHeight);
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

/** Whether a screen is shown in the portrait frame: the title and mode picker always are; the
 *  pickers, the run and the leaderboard follow the mode (the leaderboard is reached from a run). */
function isPortraitScreen(id) {
  if (id === 'screen-home' || id === 'screen-mode') return true;
  if (id === 'screen-city' || id === 'screen-car') return state.mode === 'highway';
  return runMode === 'highway'; // screen-game, screen-leaderboard
}

function showScreen(id) {
  const portrait = isPortraitScreen(id);
  setFrameAspect(portrait ? HIGHWAY_ASPECT : GAME_ASPECT);
  appFrame.classList.toggle('portrait', portrait);
  document.querySelectorAll('.screen').forEach(el => el.classList.remove('active'));
  document.getElementById(id).classList.add('active');
}

function reportScore(score) {
  if (runMode === 'highway') {
    if (score > state.highwayHighScore) {
      state.highwayHighScore = score;
      localStorage.setItem(HIGHWAY_HIGH_SCORE_KEY, String(score));
    }
  } else if (score > state.highScore) {
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
  buildModeGrid();
  showScreen('screen-mode');
});

// ---------- MODE SELECT ----------
const MODES = [
  {
    id: 'classic', name: '클래식', tag: '가로 화면',
    lines: ['5개 차선을 위·아래로 오가며 장애물을 피해요', '연료, 아이템, 근접 회피 보너스'],
    previewSprites: ['assets/cars/car_gen1.png', 'assets/obstacles/obstacle_truck.png'],
  },
  {
    id: 'highway', name: '하이웨이', tag: '세로 화면 · NEW',
    lines: ['차 뒤에서 바라보는 아웃런 스타일', '5차선 도로를 좌우로 조향하며 커브를 달려요'],
    previewSprites: ['assets/rear/car_rear_1.png', 'assets/rear/traffic_rear_suv.png'],
  },
];

function buildModeGrid() {
  const grid = document.getElementById('mode-grid');
  grid.innerHTML = '';
  for (const mode of MODES) {
    const best = bestFor(mode.id);
    const card = document.createElement('div');
    card.className = 'pick-card mode-card' + (mode.id === state.mode ? ' selected' : '');
    card.innerHTML = `
      <p class="mode-tag">${mode.tag}</p>
      <p class="mode-name">${mode.name}</p>
      ${mode.lines.map(line => `<p class="mode-line">${line}</p>`).join('')}
      <div class="mode-thumb"><canvas width="360" height="150"></canvas></div>
      <p class="mode-best">${best > 0 ? `BEST ${best}` : ''}</p>
    `;
    const thumb = card.querySelector('canvas');
    const drawThumb = () => {
      const tctx = thumb.getContext('2d');
      tctx.clearRect(0, 0, thumb.width, thumb.height);
      mode.previewSprites.forEach((path, i) => {
        drawFittedSprite(tctx, images[path], thumb.width * (i === 0 ? 0.27 : 0.73), thumb.height * 0.5, thumb.width * 0.42, thumb.height * 0.84);
      });
    };
    drawThumb();
    for (const path of mode.previewSprites) images[path].addEventListener('load', drawThumb, { once: true });
    card.addEventListener('click', () => {
      state.mode = mode.id;
      localStorage.setItem(MODE_KEY, mode.id);
      buildModeGrid();
    });
    grid.appendChild(card);
  }
}

document.getElementById('btn-mode-next').addEventListener('click', () => {
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
// Raw lane position of the player's car. It follows engine.laneIndex through a lightly damped
// spring (see stepLaneGlide), so a lane change overshoots a touch and settles; drawFrame clamps it
// for placement and uses the raw value for the tilt and slip. Mirrors laneGlide in GameScreen.kt.
let animatedLane = Math.floor(LANE_COUNT / 2);
let laneVelocity = 0;
const LANE_SPRING_STIFFNESS = 650;
const LANE_SPRING_DAMPING = 2 * 0.6 * Math.sqrt(LANE_SPRING_STIFFNESS); // damping ratio 0.6

function stepLaneGlide(target, dt) {
  const steps = Math.max(1, Math.ceil(dt / (1 / 240))); // small sub-steps keep the spring stable
  const h = dt / steps;
  for (let i = 0; i < steps; i++) {
    laneVelocity += (LANE_SPRING_STIFFNESS * (target - animatedLane) - LANE_SPRING_DAMPING * laneVelocity) * h;
    animatedLane += laneVelocity * h;
  }
}
let lastFrameTime = 0;
let rafId = null;
let lastNearMissCount = 0;

const screenGame = document.getElementById('screen-game');
const isHighway = () => runMode === 'highway';

function startGame() {
  runMode = state.mode;
  screenGame.classList.toggle('mode-highway', isHighway());
  document.querySelector('.controls-hint').textContent = isHighway()
    ? '화면 왼쪽/오른쪽을 누르거나 ← / → 키로 조향, P 키로 일시정지'
    : '↑ / ↓ 키로 이동, P 키로 일시정지';
  showScreen('screen-game');
  document.getElementById('gameover-overlay').classList.add('hidden');
  document.getElementById('pause-overlay').classList.add('hidden');

  const onSfx = (event) => {
    audio.play(event);
    vibrateFor(event);
  };
  if (isHighway()) {
    engine = new HighwayEngine(onSfx);
    engine.start();
  } else {
    engine = new GameEngine(onSfx);
    engine.start(cityById(state.selectedCityId).traits);
    animatedLane = engine.laneIndex;
    laneVelocity = 0;
  }
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
    if (!engine.paused) updateFxQuality(dt, engine.elapsedSeconds);
  }

  const city = cityById(state.selectedCityId);
  const car = carById(state.selectedCarId);
  if (isHighway()) {
    drawHighwayFrame(ctx, canvas.width, canvas.height, engine, city, car, images);
  } else {
    stepLaneGlide(engine.laneIndex, dt);
    drawFrame(ctx, canvas.width, canvas.height, engine, city, car, images, animatedLane);
  }
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
  if (timeLeft > 0) el.querySelector('.hud-powerup-text').textContent = `${label} ${Math.ceil(timeLeft)}초`;
}

function showToast(text) {
  const el = document.getElementById('hud-toast');
  el.textContent = text;
  el.classList.remove('show');
  void el.offsetWidth; // restart the CSS animation if it's already mid-flight
  el.classList.add('show');
}

function updateHud() {
  if (isHighway()) {
    document.getElementById('hud-speed-text').textContent = `${Math.round(engine.speed * 7)} KM/H`;
    if (engine.nearMissCount > lastNearMissCount) showToast(`NEAR MISS!  +${HW.NEAR_MISS_SCORE}`);
    lastNearMissCount = engine.nearMissCount;
    document.getElementById('btn-pause').classList.toggle('hidden', engine.status !== 'RUNNING' || engine.paused);
    document.getElementById('pause-overlay').classList.toggle('hidden', !(engine.status === 'RUNNING' && engine.paused));
    document.getElementById('hud-score').textContent = `SCORE ${engine.score}`;
    document.getElementById('hud-best').textContent = `BEST ${bestFor('highway')}`;
    return;
  }
  const speedKmh = Math.round(engine.effectiveSpeed * 20);
  document.getElementById('hud-speed-text').textContent = `${speedKmh} KM/H`;
  setPowerupChip('hud-shield', engine.shieldTimeLeft, '방어막');
  setPowerupChip('hud-magnet', engine.magnetTimeLeft, '자석');
  if (engine.nearMissCount > lastNearMissCount) showToast('NEAR MISS!  +30');
  lastNearMissCount = engine.nearMissCount;
  document.getElementById('btn-pause').classList.toggle('hidden', engine.status !== 'RUNNING' || engine.paused);
  document.getElementById('pause-overlay').classList.toggle('hidden', !(engine.status === 'RUNNING' && engine.paused));
  document.getElementById('hud-score').textContent = `SCORE ${engine.score}`;
  document.getElementById('hud-best').textContent = `BEST ${bestFor('classic')}`;
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
    engine.score >= bestFor(runMode) ? 'NEW BEST!' : `BEST ${bestFor(runMode)}`;
  document.getElementById('gameover-stats').textContent = isHighway()
    ? `생존 ${Math.floor(engine.elapsedSeconds)}초 · 근접 회피 ${engine.nearMissCount}회`
    : `생존 ${Math.floor(engine.elapsedSeconds)}초 · 근접 회피 ${engine.nearMissCount}회 · 연료 ${engine.fuelCollected}개`;
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
  fetchTopScores(10, runMode)
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
  const best = Math.max(bestFor(runMode), lastRunScore);
  const city = cityById(state.selectedCityId);
  const modeName = isHighway() ? ' 하이웨이' : '';
  const text = `🚗💨 Lane Dash${modeName} — ${city.name}에서 ${lastRunScore}점! (내 최고 ${best}점)\n` +
    `웹에서 바로 플레이 → ${WEB_PLAY_URL}\nGoogle Play → ${STORE_URL}`;
  const btn = document.getElementById('btn-share');
  const label = btn.querySelector('.btn-label');
  try {
    if (navigator.share) {
      await navigator.share({ text });
    } else {
      await navigator.clipboard.writeText(text);
      label.textContent = '복사됨!';
      setTimeout(() => { label.textContent = '공유하기'; }, 1500);
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
  submitScore(name, score, runMode)
    .then(cleanName => { justSubmittedName = cleanName; })
    .catch(e => console.error('submitScore failed', e))
    .finally(() => {
      document.getElementById('gameover-overlay').classList.add('hidden');
      showLeaderboardScreen(justSubmittedName, score, runMode);
    });
});

document.getElementById('btn-view-leaderboard').addEventListener('click', () => {
  justSubmittedName = null;
  document.getElementById('gameover-overlay').classList.add('hidden');
  showLeaderboardScreen(null, lastRunScore, runMode);
});

document.getElementById('btn-retry').addEventListener('click', () => {
  document.getElementById('gameover-overlay').classList.add('hidden');
  restartRun();
});

function restartRun() {
  if (isHighway()) engine.start();
  else engine.start(cityById(state.selectedCityId).traits);
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
let leaderboardRequest = 0;

function showLeaderboardScreen(highlightName, highlightScore, mode = runMode) {
  showScreen('screen-leaderboard');
  document.querySelectorAll('.lb-tab').forEach(tab => {
    tab.classList.toggle('selected', tab.dataset.mode === mode);
    tab.onclick = () => showLeaderboardScreen(highlightName, highlightScore, tab.dataset.mode);
  });
  const list = document.getElementById('leaderboard-list');
  list.innerHTML = '<div class="leaderboard-status">불러오는 중...</div>';

  const request = ++leaderboardRequest;
  fetchTopScores(10, mode)
    .then(entries => {
      if (request !== leaderboardRequest) return; // the player already switched tabs
      if (entries.length === 0) {
        list.innerHTML = '<div class="leaderboard-status">아직 기록이 없어요.\n첫 번째 기록을 남겨보세요!</div>';
        return;
      }
      list.innerHTML = '';
      entries.forEach((entry, index) => {
        const rank = index + 1;
        const row = document.createElement('div');
        const highlighted = mode === runMode && highlightName != null &&
          entry.name === highlightName && entry.score === highlightScore;
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
      if (request !== leaderboardRequest) return;
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

// Highway steering: hold the left or right half of the screen, or the arrow / A-D keys. A held
// finger wins over the keyboard, and releasing both goes straight.
const steer = { pointer: 0, left: false, right: false };

function applySteer() {
  if (engine && isHighway()) engine.steerInput = steer.pointer || ((steer.right ? 1 : 0) - (steer.left ? 1 : 0));
}

function pointerSide(e) {
  const rect = canvas.getBoundingClientRect();
  return e.clientX - rect.left < rect.width / 2 ? -1 : 1;
}

canvas.addEventListener('pointerdown', (e) => {
  if (!isHighway()) return;
  e.preventDefault();
  canvas.setPointerCapture(e.pointerId);
  steer.pointer = pointerSide(e);
  applySteer();
});
canvas.addEventListener('pointermove', (e) => {
  if (!isHighway() || steer.pointer === 0) return;
  steer.pointer = pointerSide(e);
  applySteer();
});
for (const type of ['pointerup', 'pointercancel']) {
  canvas.addEventListener(type, () => {
    steer.pointer = 0;
    applySteer();
  });
}

window.addEventListener('keyup', (e) => {
  if (e.key === 'ArrowLeft' || e.key === 'a' || e.key === 'A') steer.left = false;
  else if (e.key === 'ArrowRight' || e.key === 'd' || e.key === 'D') steer.right = false;
  else return;
  applySteer();
});

window.addEventListener('keydown', (e) => {
  if (!engine || document.getElementById('screen-game').classList.contains('active') === false) return;
  if (isHighway()) {
    if (e.key === 'ArrowLeft' || e.key === 'a' || e.key === 'A') {
      e.preventDefault();
      steer.left = true;
      applySteer();
      return;
    }
    if (e.key === 'ArrowRight' || e.key === 'd' || e.key === 'D') {
      e.preventDefault();
      steer.right = true;
      applySteer();
      return;
    }
  } else if (e.key === 'ArrowUp' || e.key === 'w' || e.key === 'W') {
    e.preventDefault();
    engine.moveLaneUp();
    return;
  } else if (e.key === 'ArrowDown' || e.key === 's' || e.key === 'S') {
    e.preventDefault();
    engine.moveLaneDown();
    return;
  }
  if (e.key === 'p' || e.key === 'P' || e.key === 'Escape') {
    e.preventDefault();
    if (engine.paused) resumeGame(); else pauseGame();
  }
});

// ---------- Boot ----------
hydrateIcons();
refreshHomeBest();
buildCityGrid();
buildCarGrid();
syncSettingsUI();
showScreen('screen-home');
