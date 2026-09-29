// Mirrors app/src/main/java/.../audio/SoundManager.kt — same events, same WAV assets.

const SFX_PATHS = {
  COIN: 'assets/sfx/sfx_coin.wav',
  CRASH: 'assets/sfx/sfx_crash.wav',
  FUEL_EMPTY: 'assets/sfx/sfx_gameover.wav',
  LANE_CHANGE: 'assets/sfx/sfx_lane.wav',
  FUEL_LOW_WARNING: 'assets/sfx/sfx_fuel_low.wav',
  NEW_BEST: 'assets/sfx/sfx_newbest.wav',
};

const BGM_VOLUME_KEY = 'lane_dash_bgm_volume';
const SFX_VOLUME_KEY = 'lane_dash_sfx_volume';

function loadVolume(key, fallback) {
  const raw = localStorage.getItem(key);
  if (raw === null) return fallback;
  const n = Number(raw);
  return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : fallback;
}

class AudioManager {
  constructor() {
    this.buffers = {};
    for (const [key, path] of Object.entries(SFX_PATHS)) {
      const el = new Audio(path);
      el.preload = 'auto';
      this.buffers[key] = el;
    }
    this.bgm = new Audio('assets/bgm/bgm_loop.wav');
    this.bgm.loop = true;
    this.unlocked = false;

    this.bgmVolume = loadVolume(BGM_VOLUME_KEY, 0.85);
    this.sfxVolume = loadVolume(SFX_VOLUME_KEY, 1.0);
    this.bgm.volume = this.bgmVolume;
  }

  // Browsers block audio until a user gesture; call this from the first tap/click.
  unlock() {
    if (this.unlocked) return;
    this.unlocked = true;
  }

  setBgmVolume(v) {
    this.bgmVolume = Math.min(1, Math.max(0, v));
    this.bgm.volume = this.bgmVolume;
    localStorage.setItem(BGM_VOLUME_KEY, String(this.bgmVolume));
  }

  setSfxVolume(v) {
    this.sfxVolume = Math.min(1, Math.max(0, v));
    localStorage.setItem(SFX_VOLUME_KEY, String(this.sfxVolume));
  }

  play(event) {
    if (this.sfxVolume <= 0) return;
    const src = this.buffers[event];
    if (!src) return;
    // Clone so overlapping triggers (e.g. rapid coin pickups) don't cut each other off.
    const node = src.cloneNode();
    node.volume = this.sfxVolume;
    node.play().catch(() => {});
  }

  startBgm() {
    this.bgm.currentTime = 0;
    this.bgm.volume = this.bgmVolume;
    this.bgm.play().catch(() => {});
  }

  stopBgm() {
    this.bgm.pause();
    this.bgm.currentTime = 0;
  }
}
