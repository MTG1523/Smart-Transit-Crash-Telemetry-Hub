/**
 * map.js — Canvas Fleet Map Renderer v2
 * ========================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * v2 improvements:
 *   • Neon-glowing road network on offscreen canvas
 *   • Speed heatmap density glow per cluster
 *   • Multi-ring shockwave on crash
 *   • Vehicle direction arrows
 *   • Selected vehicle pulse ring animation
 *   • Landmark dots (districts)
 *   • Trail fade (alpha diminishes with age)
 */

const FleetMap = (() => {

  let canvas, ctx;
  let width, height;
  let selectedUnit = -1;
  let onSelectCb   = null;

  // Crash shockwave animations: { x, y, rings: [{radius, alpha}] }
  let crashEffects = [];
  // Selected vehicle pulse ring
  let selectPulse  = { radius: 0, alpha: 0 };

  // Offscreen grid canvas
  let gridCanvas, gridCtx;

  // Bearing per vehicle (computed from trail for direction arrow)
  const bearings = new Array(50).fill(0);

  function init(canvasEl, onSelect) {
    canvas     = canvasEl;
    ctx        = canvas.getContext('2d');
    onSelectCb = onSelect;

    const ro = new ResizeObserver(() => resize());
    ro.observe(canvas.parentElement);

    requestAnimationFrame(() => requestAnimationFrame(() => resize()));
    window.addEventListener('resize', () => resize());

    canvas.addEventListener('click',     handleClick);
    canvas.addEventListener('mousemove', handleMouseMove);
    canvas.addEventListener('mouseleave', () => Tooltip.hide());
  }

  function resize() {
    const rect = canvas.parentElement.getBoundingClientRect();
    canvas.width  = rect.width;
    canvas.height = rect.height;
    width  = canvas.width;
    height = canvas.height;
    buildGrid();
  }

  // ── City grid (offscreen) ────────────────────────────────────────────────

  function buildGrid() {
    gridCanvas        = document.createElement('canvas');
    gridCanvas.width  = width;
    gridCanvas.height = height;
    gridCtx           = gridCanvas.getContext('2d');
    drawGrid(gridCtx);
  }

  function drawGrid(c) {
    // Deep background gradient
    const bg = c.createRadialGradient(width * 0.5, height * 0.4, 0, width * 0.5, height * 0.5, Math.max(width, height) * 0.8);
    bg.addColorStop(0, '#0a0f1e');
    bg.addColorStop(1, '#050810');
    c.fillStyle = bg;
    c.fillRect(0, 0, width, height);

    // District fill zones (city blocks)
    const districts = [
      { x:0.08, y:0.08, w:0.28, h:0.28, hue:195 },
      { x:0.38, y:0.08, w:0.24, h:0.20, hue:275 },
      { x:0.65, y:0.08, w:0.28, h:0.28, hue:130 },
      { x:0.08, y:0.60, w:0.22, h:0.32, hue:195 },
      { x:0.38, y:0.50, w:0.24, h:0.42, hue:275 },
      { x:0.65, y:0.60, w:0.28, h:0.32, hue:30  },
    ];
    for (const d of districts) {
      const grad = c.createRadialGradient(
        (d.x + d.w * 0.5) * width, (d.y + d.h * 0.5) * height, 0,
        (d.x + d.w * 0.5) * width, (d.y + d.h * 0.5) * height, Math.max(d.w * width, d.h * height) * 0.7
      );
      grad.addColorStop(0, `hsla(${d.hue},60%,18%,0.08)`);
      grad.addColorStop(1, `hsla(${d.hue},60%,10%,0.0)`);
      c.fillStyle = grad;
      c.fillRect(d.x * width, d.y * height, d.w * width, d.h * height);
    }

    // Major roads — neon glow
    const majorH = [0.14, 0.33, 0.52, 0.71, 0.87];
    const majorV = [0.11, 0.26, 0.43, 0.60, 0.76, 0.90];

    // Glow pass (blurred wide line)
    c.save();
    c.strokeStyle = 'rgba(0,212,255,0.045)';
    c.lineWidth   = 8;
    for (const t of majorH) {
      c.beginPath();
      c.moveTo(0, t * height);
      c.lineTo(width, t * height);
      c.stroke();
    }
    for (const t of majorV) {
      c.beginPath();
      c.moveTo(t * width, 0);
      c.lineTo(t * width, height);
      c.stroke();
    }
    c.restore();

    // Crisp road line
    c.strokeStyle = 'rgba(255,255,255,0.07)';
    c.lineWidth   = 1.5;
    for (const t of majorH) {
      c.beginPath(); c.moveTo(0, t * height); c.lineTo(width, t * height); c.stroke();
    }
    for (const t of majorV) {
      c.beginPath(); c.moveTo(t * width, 0); c.lineTo(t * width, height); c.stroke();
    }

    // Minor roads
    c.strokeStyle = 'rgba(255,255,255,0.025)';
    c.lineWidth   = 0.8;
    const minorCount = 20;
    for (let i = 1; i < minorCount; i++) {
      const t = i / minorCount;
      c.beginPath(); c.moveTo(0, t * height); c.lineTo(width, t * height); c.stroke();
      c.beginPath(); c.moveTo(t * width, 0);  c.lineTo(t * width, height); c.stroke();
    }

    // District landmark dots
    for (const d of districts) {
      const cx2 = (d.x + d.w * 0.5) * width;
      const cy2 = (d.y + d.h * 0.5) * height;
      c.beginPath();
      c.arc(cx2, cy2, 3, 0, Math.PI * 2);
      c.fillStyle = `hsla(${d.hue},80%,65%,0.15)`;
      c.fill();
    }

    // Compass rose (top right)
    c.save();
    c.translate(width - 34, 34);
    c.strokeStyle = 'rgba(255,255,255,0.12)';
    c.fillStyle   = 'rgba(255,255,255,0.12)';
    c.font        = `bold 9px "Space Grotesk"`;
    c.textAlign   = 'center';
    c.lineWidth   = 1;
    for (const [angle, label] of [[0,'N'],[90,'E'],[180,'S'],[270,'W']]) {
      const rad = angle * Math.PI / 180;
      const x   = Math.sin(rad) * 14;
      const y   = -Math.cos(rad) * 14;
      c.fillText(label, x, y + 3);
    }
    c.restore();
  }

  // ── GPS → canvas coordinates ─────────────────────────────────────────────

  const { LAT_BASE, LON_BASE, GPS_DRIFT } = Physics;

  function latLonToXY(lat, lon) {
    const nx = (lon - (LON_BASE - GPS_DRIFT)) / (2 * GPS_DRIFT);
    const ny = 1 - (lat - (LAT_BASE - GPS_DRIFT)) / (2 * GPS_DRIFT);
    const margin = 0.06;
    return {
      x: (margin + nx * (1 - 2 * margin)) * width,
      y: (margin + ny * (1 - 2 * margin)) * height,
    };
  }

  // ── Speed → colour ───────────────────────────────────────────────────────

  function speedColor(speedKmh, alpha = 1) {
    const t = Math.min(1, speedKmh / 160);
    if (t < 0.4) return `hsla(160, 80%, ${45 + t * 20}%, ${alpha})`;
    if (t < 0.7) return `hsla(${160 - (t - 0.4) * 400}, 80%, 55%, ${alpha})`;
    return `hsla(${40 - (t - 0.7) * 133}, 90%, 60%, ${alpha})`;
  }

  // ── Compute bearing from trail ───────────────────────────────────────────

  function computeBearing(trail) {
    if (!trail || trail.length < 2) return 0;
    const last = trail[trail.length - 1];
    const prev = trail[trail.length - 2];
    return Math.atan2(last.lon - prev.lon, last.lat - prev.lat);
  }

  // ── Main render ──────────────────────────────────────────────────────────

  function render(states, readings) {
    if (!gridCanvas || !width || !height) return;

    ctx.clearRect(0, 0, width, height);

    // 1. City grid
    ctx.drawImage(gridCanvas, 0, 0);

    // 2. Trails with age-based alpha fade
    for (let i = 0; i < states.length; i++) {
      const state = states[i];
      if (!state || state.trail.length < 2) continue;
      const isSelected = i === selectedUnit;

      const len  = state.trail.length;
      const step = 1 / (len - 1);

      for (let j = 1; j < len; j++) {
        const a0 = (j - 1) * step;
        const a1 = j * step;
        const alpha = state.isActive
          ? (isSelected ? a1 * 0.55 : a1 * 0.12)
          : a1 * 0.06;
        const p0 = latLonToXY(state.trail[j-1].lat, state.trail[j-1].lon);
        const p1 = latLonToXY(state.trail[j].lat,   state.trail[j].lon);
        const r  = readings[i];
        const col = r
          ? speedColor(r.speedKmh, alpha)
          : `rgba(122,139,168,${alpha})`;
        ctx.beginPath();
        ctx.moveTo(p0.x, p0.y);
        ctx.lineTo(p1.x, p1.y);
        ctx.strokeStyle = col;
        ctx.lineWidth   = isSelected ? 2 : 1;
        ctx.stroke();
      }
    }

    // 3. Crash shockwave effects
    crashEffects = crashEffects.filter(e => e.rings.some(r => r.alpha > 0));
    for (const ef of crashEffects) {
      for (const ring of ef.rings) {
        ring.radius += ring.speed;
        ring.alpha  -= 0.014;
        if (ring.alpha <= 0) continue;
        ctx.beginPath();
        ctx.arc(ef.x, ef.y, ring.radius, 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(255,61,90,${ring.alpha})`;
        ctx.lineWidth   = ring.width;
        ctx.stroke();
      }
    }

    // 4. Selected vehicle pulse ring
    if (selectPulse.alpha > 0) {
      const r    = readings[selectedUnit];
      const sel  = states[selectedUnit];
      if (r && sel?.isActive) {
        const { x, y } = latLonToXY(r.latitude, r.longitude);
        ctx.beginPath();
        ctx.arc(x, y, selectPulse.radius, 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(0,212,255,${selectPulse.alpha})`;
        ctx.lineWidth   = 1.5;
        ctx.stroke();
      }
      selectPulse.radius += 1.2;
      selectPulse.alpha  -= 0.025;
    }

    // 5. Vehicle dots
    for (let i = 0; i < states.length; i++) {
      const state   = states[i];
      const reading = readings[i];
      if (!state || !reading) continue;

      const { x, y } = latLonToXY(reading.latitude, reading.longitude);
      const isSelected = i === selectedUnit;

      if (!state.isActive) {
        // Crashed — blinking X marker
        const now  = Date.now();
        const blink = Math.sin(now / 400) > 0;
        ctx.save();
        ctx.translate(x, y);
        ctx.strokeStyle = blink ? 'rgba(255,61,90,0.75)' : 'rgba(255,61,90,0.3)';
        ctx.lineWidth   = 1.5;
        const s = 4;
        ctx.beginPath();
        ctx.moveTo(-s,-s); ctx.lineTo(s,s);
        ctx.moveTo(s,-s);  ctx.lineTo(-s,s);
        ctx.stroke();
        ctx.restore();
        continue;
      }

      // Update bearing
      bearings[i] = computeBearing(state.trail);

      // Glow halo for selected
      if (isSelected) {
        const grad = ctx.createRadialGradient(x,y,0, x,y,20);
        grad.addColorStop(0, 'rgba(0,212,255,0.28)');
        grad.addColorStop(0.5,'rgba(0,212,255,0.08)');
        grad.addColorStop(1, 'rgba(0,212,255,0)');
        ctx.beginPath();
        ctx.arc(x, y, 20, 0, Math.PI * 2);
        ctx.fillStyle = grad;
        ctx.fill();
      }

      // Speed glow aura (subtle)
      const spd = reading.speedKmh;
      if (spd > 80) {
        const auraR  = 10 + (spd / 220) * 8;
        const auraGrad = ctx.createRadialGradient(x,y,0, x,y,auraR);
        auraGrad.addColorStop(0, speedColor(spd, 0.18));
        auraGrad.addColorStop(1, speedColor(spd, 0));
        ctx.beginPath();
        ctx.arc(x, y, auraR, 0, Math.PI * 2);
        ctx.fillStyle = auraGrad;
        ctx.fill();
      }

      // Vehicle dot
      const dotR = isSelected ? 5.5 : 4;
      const col  = speedColor(reading.speedKmh);
      ctx.beginPath();
      ctx.arc(x, y, dotR, 0, Math.PI * 2);
      ctx.fillStyle = col;
      ctx.shadowColor = col;
      ctx.shadowBlur  = isSelected ? 10 : 5;
      ctx.fill();
      ctx.shadowBlur  = 0;

      // White border
      ctx.strokeStyle = isSelected ? 'rgba(255,255,255,0.9)' : 'rgba(255,255,255,0.25)';
      ctx.lineWidth   = isSelected ? 1.5 : 0.7;
      ctx.stroke();

      // Direction arrow
      if (state.trail.length >= 2 && isSelected) {
        const bear = bearings[i];
        const aLen = 12;
        ctx.save();
        ctx.translate(x, y);
        ctx.rotate(-bear);
        ctx.beginPath();
        ctx.moveTo(0, -(dotR + aLen));
        ctx.lineTo(-3, -(dotR + aLen * 0.5));
        ctx.lineTo(3,  -(dotR + aLen * 0.5));
        ctx.closePath();
        ctx.fillStyle = col;
        ctx.fill();
        ctx.restore();
      }
    }
  }

  // ── Crash shockwave ──────────────────────────────────────────────────────

  function addCrashEffect(lat, lon) {
    const { x, y } = latLonToXY(lat, lon);
    // Multi-ring at staggered start times
    const rings = [
      { radius: 6, alpha: 1.0, speed: 3.0, width: 2.0 },
      { radius: 4, alpha: 0.0, speed: 2.2, width: 1.5 },
      { radius: 4, alpha: 0.0, speed: 1.4, width: 1.0 },
    ];
    const ef = { x, y, rings };
    crashEffects.push(ef);
    // Stagger subsequent rings
    setTimeout(() => { ef.rings[1].alpha = 0.75; }, 160);
    setTimeout(() => { ef.rings[2].alpha = 0.45; }, 340);
  }

  // ── Input handling ───────────────────────────────────────────────────────

  function handleClick(e) {
    const rect   = canvas.getBoundingClientRect();
    const mx     = (e.clientX - rect.left) * (canvas.width  / rect.width);
    const my     = (e.clientY - rect.top)  * (canvas.height / rect.height);
    const states  = Fleet.getStates();
    const readings = [];
    for (let i = 0; i < 50; i++) readings.push(Fleet.getReading(i));

    let closest = -1, minDist = 22;
    for (let i = 0; i < states.length; i++) {
      const r = readings[i];
      if (!r || !states[i].isActive) continue;
      const { x, y } = latLonToXY(r.latitude, r.longitude);
      const d = Math.hypot(x - mx, y - my);
      if (d < minDist) { minDist = d; closest = i; }
    }
    if (closest !== -1) {
      selectedUnit = closest;
      // Trigger pulse ring
      selectPulse = { radius: 6, alpha: 0.9 };
      if (onSelectCb) onSelectCb(closest);
    }
  }

  function handleMouseMove(e) {
    const rect   = canvas.getBoundingClientRect();
    const mx     = (e.clientX - rect.left) * (canvas.width  / rect.width);
    const my     = (e.clientY - rect.top)  * (canvas.height / rect.height);
    const states  = Fleet.getStates();
    const readings = [];
    for (let i = 0; i < 50; i++) readings.push(Fleet.getReading(i));

    let hovered = -1, minDist = 18;
    for (let i = 0; i < states.length; i++) {
      const r = readings[i];
      if (!r || !states[i].isActive) continue;
      const { x, y } = latLonToXY(r.latitude, r.longitude);
      const d = Math.hypot(x - mx, y - my);
      if (d < minDist) { minDist = d; hovered = i; }
    }

    if (hovered !== -1) {
      const r    = readings[hovered];
      const name = `VFU-${String(hovered + 1).padStart(2,'0')}`;
      Tooltip.show(e.clientX + 14, e.clientY - 10, {
        name,
        speed : `${r.speedKmh.toFixed(1)} km/h`,
        rpm   : `${Math.round(r.rpm)} rpm`,
        G     : `${r.gForceMag.toFixed(3)} g`,
        accel : `${r.throttlePct.toFixed(0)}% / brk ${r.brakePressure.toFixed(0)}%`,
      });
      canvas.style.cursor = 'pointer';
    } else {
      Tooltip.hide();
      canvas.style.cursor = 'default';
    }
  }

  function setSelected(idx) {
    selectedUnit = idx;
    selectPulse  = { radius: 6, alpha: 0.9 };
  }

  return { init, render, addCrashEffect, setSelected, resize };
})();

// ── Tooltip helper ──────────────────────────────────────────────────────────
const Tooltip = (() => {
  let el;
  function init() {
    el = document.getElementById('tooltip');
  }
  function show(x, y, data) {
    el.innerHTML = `
      <div class="tt-name">${data.name}</div>
      <div class="tt-row">Speed   <span>${data.speed}</span></div>
      <div class="tt-row">RPM     <span>${data.rpm}</span></div>
      <div class="tt-row">G-Force <span>${data.G}</span></div>
      <div class="tt-row">Inputs  <span>${data.accel}</span></div>
    `;
    el.style.left = x + 'px';
    el.style.top  = y + 'px';
    el.classList.add('visible');
  }
  function hide() { el?.classList.remove('visible'); }
  return { init, show, hide };
})();
