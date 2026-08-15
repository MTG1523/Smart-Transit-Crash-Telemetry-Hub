/**
 * dashboard.js — Main Orchestrator, API Bridge & Feature Controller
 * ==================================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub v2
 *
 * New in v2:
 *   • Auto-respawn crashed vehicles
 *   • Sound alerts (Web Audio API)
 *   • Keyboard shortcuts (1-9, R, S, C, E, ?)
 *   • Mini fleet-health grid
 *   • Speed histogram (live)
 *   • Per-card sparkbars
 *   • Map analytics overlay (Peak G, Max Speed, Density)
 *   • CSV export
 *   • Vehicle status badge (ACTIVE / CRASHED)
 *   • Engine temperature simulation
 *   • Avg speed header stat
 *   • Animated metric counters
 */

// ── Config ──────────────────────────────────────────────────────────────────
const API_BASE    = 'http://localhost:8080/api';
const API_POLL_MS = 500;
const FLEET_SIZE  = 50;

// ── State ───────────────────────────────────────────────────────────────────
let mode         = 'sim';
let selectedUnit = 0;
let apiPollId    = null;
let crashCount   = 0;
let lastRenderTs = 0;
let autoRespawn  = true;
let soundEnabled = true;
let sessionCrashes = [];   // for CSV export

// Displayed values for smooth counter animation
let displayedReadings = 0;
let displayedCrashes  = 0;

// Engine temp simulation per unit (°C)
const engineTemps = new Array(FLEET_SIZE).fill(85);

// Map analytics stats
let peakG    = 0;
let maxSpeed = 0;

// Sparkbar history (circular buffers, 30 entries each)
const SPARKBAR_LEN = 30;
const sparkHistory = {
  active:   new Array(SPARKBAR_LEN).fill(50),
  crashes:  new Array(SPARKBAR_LEN).fill(0),
  readings: new Array(SPARKBAR_LEN).fill(0),
  avgspeed: new Array(SPARKBAR_LEN).fill(50),
};

// AudioContext for click/crash sounds
let audioCtx = null;
function getAudio() {
  if (!audioCtx) {
    try { audioCtx = new (window.AudioContext || window.webkitAudioContext)(); }
    catch(e) { soundEnabled = false; }
  }
  return audioCtx;
}

// ── DOM helpers ──────────────────────────────────────────────────────────────
function el(id) { return document.getElementById(id); }

function setText(id, val) {
  const node = el(id);
  if (node && node.textContent !== String(val)) node.textContent = val;
}

function setClass(id, cls) {
  const node = el(id);
  if (node) node.className = cls;
}

// ── Boot ─────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {

  // Particle system
  ParticleSystem.init();

  // Tooltip init
  Tooltip.init();

  // Fleet map
  FleetMap.init(el('fleet-canvas'), idx => {
    selectedUnit = idx;
    updateInspectorHeader(idx);
    playSelectSound();
  });

  // Charts
  Charts.initGauge(el('speed-gauge'));
  Charts.initGForceChart(el('gforce-chart'));
  Charts.initHistogram(el('histogram-canvas'));

  // Build fleet grid
  buildFleetGrid();

  // Live clock
  setInterval(updateClock, 1000);
  updateClock();

  // Uptime counter
  const startTs = Date.now();
  setInterval(() => {
    const s   = Math.floor((Date.now() - startTs) / 1000);
    const min = Math.floor(s / 60);
    const sec = s % 60;
    const str = `${String(min).padStart(2,'0')}:${String(sec).padStart(2,'0')}`;
    setText('m-uptime', str);
  }, 1000);

  // Mode toggle buttons
  document.querySelectorAll('.mode-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const newMode = btn.dataset.mode;
      if (newMode !== mode) switchMode(newMode);
    });
  });

  // Crash log clear
  el('crash-log-clear')?.addEventListener('click', clearCrashLog);

  // Export CSV
  el('crash-log-export')?.addEventListener('click', exportCrashCSV);

  // Respawn toggle
  const respawnBtn = el('respawn-btn');
  respawnBtn?.addEventListener('click', () => {
    autoRespawn = !autoRespawn;
    respawnBtn.classList.toggle('active', autoRespawn);
    showInfoToast(autoRespawn ? '🔄 Auto-respawn enabled' : '⏸ Auto-respawn disabled');
  });
  respawnBtn?.classList.add('active'); // default ON

  // Sound toggle
  const soundBtn = el('sound-btn');
  soundBtn?.addEventListener('click', toggleSound);

  // Shortcuts modal
  el('shortcuts-btn')?.addEventListener('click', () => toggleShortcutsModal(true));
  el('shortcuts-close')?.addEventListener('click', () => toggleShortcutsModal(false));
  el('shortcuts-modal')?.addEventListener('click', e => {
    if (e.target.classList.contains('modal-backdrop')) toggleShortcutsModal(false);
  });

  // Keyboard shortcuts
  document.addEventListener('keydown', handleKeyDown);

  // Default selected unit
  updateInspectorHeader(0);

  // Start sim
  startSim();

  // Animation loop
  requestAnimationFrame(renderLoop);
});

// ── Fleet Grid ────────────────────────────────────────────────────────────────

function buildFleetGrid() {
  const grid = el('fleet-grid');
  if (!grid) return;
  grid.innerHTML = '';
  for (let i = 0; i < FLEET_SIZE; i++) {
    const cell = document.createElement('div');
    cell.className = 'fleet-cell';
    cell.title     = `VFU-${String(i+1).padStart(2,'0')}`;
    cell.id        = `fcell-${i}`;
    cell.addEventListener('click', () => {
      selectedUnit = i;
      updateInspectorHeader(i);
      playSelectSound();
    });
    grid.appendChild(cell);
  }
}

function updateFleetGrid(states) {
  const activeCount = states.filter(s => s.isActive).length;
  const pct = Math.round((activeCount / FLEET_SIZE) * 100);
  setText('fleet-health-pct', pct + '%');

  for (let i = 0; i < states.length; i++) {
    const cell = el(`fcell-${i}`);
    if (!cell) continue;
    const isActive   = states[i]?.isActive;
    const isSelected = i === selectedUnit;
    cell.className = `fleet-cell${isActive ? '' : ' crashed'}${isSelected ? ' selected' : ''}`;
  }
}

// ── Simulation mode ──────────────────────────────────────────────────────────

function startSim() {
  Fleet.on('tick',  onTick);
  Fleet.on('crash', onCrash);
  Fleet.start();

  el('mode-sim')?.classList.add('active');
  el('mode-live')?.classList.remove('active');
  setClass('status-pill', 'status-pill live');
  setText('status-pill-text', 'SIM LIVE');
}

function onTick(data) {
  // Animated counters
  animateCounter('m-readings', displayedReadings, data.totalReadings, v => {
    displayedReadings = v;
    setText('m-readings', formatK(v));
  });
  animateCounter('m-crashes', displayedCrashes, data.crashCount, v => {
    displayedCrashes = v;
    setText('m-crashes', v);
  });

  setText('m-active',  data.activeCount);
  setText('m-rate',    data.activeCount * 10);

  // Avg speed
  let speedSum = 0, speedCount = 0;
  for (let i = 0; i < FLEET_SIZE; i++) {
    const r = Fleet.getReading(i);
    const s = Fleet.getState(i);
    if (r && s?.isActive) { speedSum += r.speedKmh; speedCount++; }
  }
  const avgSpeed = speedCount > 0 ? (speedSum / speedCount) : 0;
  setText('m-avgspeed', `${avgSpeed.toFixed(0)} km/h`);
  setText('h-avgspeed', `${avgSpeed.toFixed(0)} km/h`);

  // Header stats
  setText('h-active',  data.activeCount);
  setText('h-crashes', data.crashCount);
  setText('h-rate',    data.activeCount * 10 + '/s');

  // Sparkbars
  pushSparkHistory('active',   data.activeCount,                   FLEET_SIZE);
  pushSparkHistory('crashes',  Math.min(data.crashCount, 20),       20);
  pushSparkHistory('readings', Math.min(data.totalReadings, 100000), 100000);
  pushSparkHistory('avgspeed', avgSpeed,                             MAX_AVG_SPEED);

  // Analytics overlay stats
  let sessionMaxSpeed = 0, sessionPeakG = 0;
  for (let i = 0; i < FLEET_SIZE; i++) {
    const r = Fleet.getReading(i);
    if (!r) continue;
    if (r.speedKmh > sessionMaxSpeed) sessionMaxSpeed = r.speedKmh;
    if (r.gForceMag > sessionPeakG)   sessionPeakG    = r.gForceMag;
  }
  if (sessionMaxSpeed > maxSpeed) maxSpeed = sessionMaxSpeed;
  if (sessionPeakG   > peakG)    peakG    = sessionPeakG;
  setText('mao-peakg',   peakG.toFixed(1) + ' g');
  setText('mao-maxspd',  maxSpeed.toFixed(0) + ' km/h');
  const density = `${data.activeCount}/${FLEET_SIZE}`;
  setText('mao-density', density);
  setText('analytics-active-count', `${data.activeCount} vehicles`);

  // Inspector
  const r = Fleet.getReading(selectedUnit);
  if (r) updateInspector(r);

  // Fleet grid
  updateFleetGrid(Fleet.getStates());

  // Auto-respawn crashed vehicles
  if (autoRespawn) {
    const states = Fleet.getStates();
    for (let i = 0; i < states.length; i++) {
      if (!states[i].isActive) {
        // Respawn after ~8 seconds (80 ticks)
        if (!states[i]._respawnTimer) {
          states[i]._respawnTimer = 80;
        } else {
          states[i]._respawnTimer--;
          if (states[i]._respawnTimer <= 0) {
            respawnVehicle(i);
          }
        }
      }
    }
  }
}

const MAX_AVG_SPEED = 160;

function respawnVehicle(idx) {
  const states = Fleet.getStates();
  const newState = Physics.createState(idx + 1);
  // Re-inject into the states array
  Object.assign(states[idx], newState);
  states[idx]._respawnTimer = undefined;
  // Tiny notification
  showInfoToast(`🔄 ${`VFU-${String(idx+1).padStart(2,'00')}`} respawned`);
}

function onCrash(crashEntry) {
  crashCount++;
  sessionCrashes.unshift(crashEntry);

  // Screen flash
  const flash = el('crash-flash');
  if (flash) {
    flash.classList.add('active');
    setTimeout(() => flash.classList.remove('active'), 200);
  }

  // Map burst ring
  FleetMap.addCrashEffect(crashEntry.lat, crashEntry.lon);

  // Crash log entry
  appendCrashLog(crashEntry);

  // Toast popup
  showCrashToast(crashEntry);

  // Badge update
  setText('crash-log-count', crashCount);

  // Sound
  playCrashSound(crashEntry.severity);
}

// ── Live API mode ─────────────────────────────────────────────────────────────

function startLiveAPI() {
  Fleet.stop();
  if (apiPollId) clearInterval(apiPollId);

  setClass('status-pill', 'status-pill api');
  setText('status-pill-text', 'API LIVE');

  apiPollId = setInterval(async () => {
    try {
      const [fr, cr] = await Promise.all([
        fetch(`${API_BASE}/fleet`),
        fetch(`${API_BASE}/crashes?limit=20`),
      ]);
      if (!fr.ok || !cr.ok) throw new Error('HTTP error');
      const fleetData  = await fr.json();
      const crashData  = await cr.json();

      setText('m-active',   fleetData.activeCount ?? '—');
      setText('m-readings', formatK(fleetData.totalReadings ?? 0));
      setText('m-rate',     (fleetData.activeCount ?? 0) * 10);
      setText('m-crashes',  Array.isArray(crashData) ? crashData.length : '—');

    } catch (e) {
      console.warn('[Dashboard] API poll failed:', e.message);
      setText('status-pill-text', 'API ERROR');
    }
  }, API_POLL_MS);
}

// ── Mode switcher ─────────────────────────────────────────────────────────────

function switchMode(newMode) {
  mode = newMode;
  document.querySelectorAll('.mode-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.mode === newMode);
  });
  if (newMode === 'sim')  { if (apiPollId) clearInterval(apiPollId); startSim(); }
  if (newMode === 'live') startLiveAPI();
}

// ── Animation loop ────────────────────────────────────────────────────────────

function renderLoop(ts) {
  try {
    if (ts - lastRenderTs >= 33) {
      const states   = Fleet.getStates();
      const readings = [];
      for (let i = 0; i < FLEET_SIZE; i++) readings.push(Fleet.getReading(i));
      FleetMap.render(states, readings);
      Charts.drawHistogram(readings);
      lastRenderTs = ts;
    }

    // G-force sparkline at full frame rate
    const hist = Fleet.getGHistory(selectedUnit);
    if (hist && hist.length > 1) Charts.drawGForceChart(hist);

  } catch (e) {
    // Per-frame errors swallowed to keep loop alive
  }

  requestAnimationFrame(renderLoop);
}

// ── Inspector ─────────────────────────────────────────────────────────────────

function updateInspectorHeader(idx) {
  const name   = `VFU-${String(idx + 1).padStart(2,'0')}`;
  const state  = Fleet.getState(idx);
  const active = state ? state.isActive : true;
  setText('selected-unit-name', name);
  setClass('selected-unit-status',
    `unit-status-badge ${active ? 'active' : 'crashed'}`);
  setText('selected-unit-status', active ? 'ACTIVE' : 'CRASHED');
  FleetMap.setSelected(idx);
}

function updateInspector(r) {
  const state = Fleet.getState(selectedUnit);
  const isActive = state ? state.isActive : true;

  // Keep header in sync
  const badge = el('selected-unit-status');
  if (badge) {
    const wantActive = isActive;
    const hasActive  = badge.classList.contains('active');
    if (wantActive !== hasActive) {
      badge.className = `unit-status-badge ${isActive ? 'active' : 'crashed'}`;
      badge.textContent = isActive ? 'ACTIVE' : 'CRASHED';
    }
  }

  // Speed gauge (canvas)
  Charts.drawGauge(r.speedKmh);

  // Engine temperature (simulated: rises with RPM, cools slowly)
  const tempTarget = 70 + (r.rpm / 6500) * 60 + (r.throttlePct / 100) * 20;
  engineTemps[selectedUnit] += (tempTarget - engineTemps[selectedUnit]) * 0.04;
  const temp    = Math.round(engineTemps[selectedUnit]);
  const tempPct = Math.min(100, ((temp - 60) / 80) * 100);
  const tempEl  = el('engine-temp-bar');
  if (tempEl) {
    tempEl.style.width = tempPct + '%';
    const col = temp < 90 ? 'linear-gradient(90deg,var(--cyan),var(--green))'
              : temp < 100 ? 'linear-gradient(90deg,var(--green),var(--amber))'
              :               'linear-gradient(90deg,var(--amber),var(--red))';
    tempEl.style.background = col;
  }
  const tempValEl = el('engine-temp-val');
  if (tempValEl) {
    tempValEl.textContent = temp + '°C';
    tempValEl.style.color = temp < 90 ? 'var(--cyan)' : temp < 100 ? 'var(--amber)' : 'var(--red)';
  }

  // RPM bar (0–6500)
  const rpmPct   = (r.rpm / 6500) * 100;
  const rpmColor = rpmPct > 80
    ? 'linear-gradient(90deg,#ff8800,#ff3d5a)'
    : 'linear-gradient(90deg,#00d4ff,#00ff9d)';
  setText('rpm-val', Math.round(r.rpm));
  Charts.updateBar('rpm-bar', rpmPct, rpmColor);

  // Throttle
  setText('throt-val', r.throttlePct.toFixed(1) + '%');
  Charts.updateBar('throt-bar', r.throttlePct, 'linear-gradient(90deg,#00d4ff,#00ff9d)');

  // Brake
  setText('brake-val', r.brakePressure.toFixed(1) + '%');
  Charts.updateBar('brake-bar', r.brakePressure, 'linear-gradient(90deg,#ffb800,#ff3d5a)');

  // Accelerometer axes
  const axisColor = v => v >= 0 ? '#00ff9d' : '#ff3d5a';
  const setAxis = (id, val) => {
    const node = el(id);
    if (!node) return;
    node.textContent = val.toFixed(3) + ' g';
    node.style.color = axisColor(val);
  };
  setAxis('gx-val', r.accelX);
  setAxis('gy-val', r.accelY);
  setAxis('gz-val', r.accelZ);

  // G-force magnitude
  const gNode = el('gmag-val');
  if (gNode) {
    gNode.textContent = r.gForceMag.toFixed(4) + ' g';
    gNode.style.color = r.gForceMag > 3 ? '#ff3d5a'
                      : r.gForceMag > 1 ? '#ffb800' : '#00d4ff';
  }

  // GPS
  setText('gps-lat', r.latitude.toFixed(7));
  setText('gps-lon', r.longitude.toFixed(7));
  setText('gps-alt', r.altitudeM.toFixed(1) + ' m');
}

// ── Crash log ─────────────────────────────────────────────────────────────────

function appendCrashLog(c) {
  const log = el('crash-log-entries');
  if (!log) return;

  const sev    = c.severity > 0.7 ? 'critical' : c.severity > 0.4 ? 'major' : 'minor';
  const sevLbl = { critical:'CRITICAL', major:'MAJOR', minor:'MINOR' }[sev];

  const entry = document.createElement('div');
  entry.className = 'crash-entry';
  entry.innerHTML = `
    <span class="crash-entry-time">${formatTime(c.time)}</span>
    <span class="crash-entry-unit">${c.unitName}</span>
    <span class="crash-entry-desc">Impact (${c.lat.toFixed(4)}, ${c.lon.toFixed(4)}) · ${c.speedAtImpact.toFixed(0)} km/h</span>
    <span class="crash-entry-g">⚡ ${c.peakG.toFixed(2)} g</span>
    <span class="severity-badge severity-${sev}">${sevLbl}</span>
  `;

  log.insertBefore(entry, log.firstChild);
  while (log.children.length > 60) log.removeChild(log.lastChild);
}

function clearCrashLog() {
  const log = el('crash-log-entries');
  if (log) log.innerHTML = '';
  crashCount = 0;
  displayedCrashes = 0;
  sessionCrashes = [];
  setText('crash-log-count', '0');
}

function exportCrashCSV() {
  if (!sessionCrashes.length) {
    showInfoToast('ℹ No crash data to export');
    return;
  }
  const header = 'Time,Unit,Lat,Lon,SpeedKmh,PeakG,Severity\n';
  const rows   = sessionCrashes.map(c =>
    `${formatTime(c.time)},${c.unitName},${c.lat.toFixed(7)},${c.lon.toFixed(7)},${c.speedAtImpact.toFixed(1)},${c.peakG.toFixed(3)},${c.severity.toFixed(3)}`
  ).join('\n');
  const blob   = new Blob([header + rows], { type: 'text/csv' });
  const url    = URL.createObjectURL(blob);
  const a      = document.createElement('a');
  a.href       = url;
  a.download   = `crash_log_${Date.now()}.csv`;
  a.click();
  URL.revokeObjectURL(url);
  showInfoToast('📁 CSV exported');
}

// ── Toast notifications ────────────────────────────────────────────────────────

function showCrashToast(c) {
  const container = el('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = 'crash-toast';
  toast.innerHTML = `
    <span class="toast-icon">💥</span>
    <div class="toast-body">
      <div class="toast-title">CRASH DETECTED</div>
      <div class="toast-detail">${c.unitName} · <strong>${c.peakG.toFixed(2)} g</strong> · ${c.speedAtImpact.toFixed(0)} km/h</div>
    </div>
  `;
  container.appendChild(toast);
  fadeOutToast(toast, 3000);
}

function showInfoToast(message) {
  const container = el('toast-container');
  if (!container) return;
  const toast = document.createElement('div');
  toast.className = 'crash-toast';
  toast.style.borderLeftColor = 'var(--cyan)';
  toast.style.borderColor     = 'rgba(0,212,255,0.35)';
  toast.innerHTML = `<div class="toast-body"><div class="toast-detail" style="color:var(--text-primary)">${message}</div></div>`;
  container.appendChild(toast);
  fadeOutToast(toast, 2200);
}

function fadeOutToast(toast, delay) {
  setTimeout(() => { toast.style.transition = 'opacity 0.38s'; }, delay);
  setTimeout(() => { toast.style.opacity = '0'; },               delay + 100);
  setTimeout(() => { toast.remove(); },                           delay + 480);
}

// ── Sparkbars ─────────────────────────────────────────────────────────────────

function pushSparkHistory(key, val, max) {
  sparkHistory[key].shift();
  sparkHistory[key].push(val);
  const latest = sparkHistory[key][sparkHistory[key].length - 1];
  const pct    = Math.min(100, (latest / max) * 100);
  const barEl  = el(`spark-${key}`);
  if (barEl) barEl.style.setProperty('--pct', pct + '%');
}

// ── Animated counter ──────────────────────────────────────────────────────────

function animateCounter(id, from, to, setter) {
  if (from === to) return;
  // Just jump if delta > 1000
  if (Math.abs(to - from) > 1000) { setter(to); return; }
  const step = Math.ceil((to - from) / 6) || 1;
  const next = Math.abs(to - from) > Math.abs(step) ? from + step : to;
  setter(next);
}

// ── Sound effects (Web Audio API) ─────────────────────────────────────────────

function playCrashSound(severity = 0.5) {
  if (!soundEnabled) return;
  try {
    const ctx  = getAudio();
    if (!ctx) return;

    // Low-frequency thud
    const osc  = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type   = 'sawtooth';
    osc.frequency.setValueAtTime(80 + severity * 60, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(30, ctx.currentTime + 0.3);
    gain.gain.setValueAtTime(0.22, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.35);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.36);
  } catch(e) { /* ignore */ }
}

function playSelectSound() {
  if (!soundEnabled) return;
  try {
    const ctx  = getAudio();
    if (!ctx) return;
    const osc  = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type   = 'sine';
    osc.frequency.setValueAtTime(880, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(1100, ctx.currentTime + 0.08);
    gain.gain.setValueAtTime(0.05, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.1);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.11);
  } catch(e) { /* ignore */ }
}

function toggleSound() {
  soundEnabled = !soundEnabled;
  el('sound-icon-on').style.display  = soundEnabled ? '' : 'none';
  el('sound-icon-off').style.display = soundEnabled ? 'none' : '';
  el('sound-btn')?.classList.toggle('active', !soundEnabled);
  showInfoToast(soundEnabled ? '🔊 Sound enabled' : '🔇 Sound disabled');
}

// ── Keyboard shortcuts ────────────────────────────────────────────────────────

function handleKeyDown(e) {
  // Don't trigger inside inputs
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

  if (e.key >= '1' && e.key <= '9') {
    const idx = parseInt(e.key) - 1;
    selectedUnit = idx;
    updateInspectorHeader(idx);
    playSelectSound();
    return;
  }

  switch (e.key.toLowerCase()) {
    case 'r':
      autoRespawn = !autoRespawn;
      el('respawn-btn')?.classList.toggle('active', autoRespawn);
      showInfoToast(autoRespawn ? '🔄 Auto-respawn ON' : '⏸ Auto-respawn OFF');
      break;
    case 's':
      toggleSound();
      break;
    case 'c':
      clearCrashLog();
      break;
    case 'e':
      exportCrashCSV();
      break;
    case '?':
      toggleShortcutsModal();
      break;
    case 'escape':
      toggleShortcutsModal(false);
      break;
  }
}

function toggleShortcutsModal(show) {
  const modal = el('shortcuts-modal');
  if (!modal) return;
  const isHidden = modal.hasAttribute('hidden');
  const shouldShow = show !== undefined ? show : isHidden;
  if (shouldShow) {
    modal.removeAttribute('hidden');
  } else {
    modal.setAttribute('hidden', '');
  }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function updateClock() {
  setText('live-clock', new Date().toLocaleTimeString('en-GB', { hour12: false }));
}

function formatTime(d) {
  return d instanceof Date
    ? d.toLocaleTimeString('en-GB', { hour12: false })
    : '--:--:--';
}

function formatK(n) {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000)     return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}
