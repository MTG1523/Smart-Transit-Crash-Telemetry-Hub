/**
 * charts.js — Vehicle Inspector Renderers + Analytics Charts
 * =============================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Provides:
 *   • Radial speed gauge (canvas) — enhanced with needle + glow
 *   • G-force sparkline (canvas)  — animated area fill
 *   • Animated bar fills for RPM, throttle, brake
 *   • Speed distribution histogram across the fleet
 */

const Charts = (() => {

  // ── Speed Gauge ─────────────────────────────────────────────────────────

  let gaugeCanvas, gaugeCtx;
  const MAX_SPEED = 220;
  let _lastSpeedKmh = 0;

  function initGauge(canvasEl) {
    gaugeCanvas = canvasEl;
    gaugeCtx    = canvasEl.getContext('2d');
    requestAnimationFrame(() => drawGauge(0));
  }

  function drawGauge(speedKmh) {
    _lastSpeedKmh = speedKmh;
    const c   = gaugeCtx;
    const rect = gaugeCanvas.getBoundingClientRect();
    const W   = gaugeCanvas.width  = Math.round(rect.width)  || gaugeCanvas.width  || 300;
    const H   = gaugeCanvas.height = Math.round(rect.height) || gaugeCanvas.height || 110;
    const cx  = W / 2;
    const cy  = H * 0.92;
    const R   = Math.min(W * 0.46, H * 1.8 * 0.46);

    c.clearRect(0, 0, W, H);

    const startAngle = Math.PI;
    const endAngle   = 2 * Math.PI;
    const speedFrac  = Math.min(1, speedKmh / MAX_SPEED);
    const fillAngle  = startAngle + speedFrac * Math.PI;

    // Deep background arc glow
    c.beginPath();
    c.arc(cx, cy, R + 6, startAngle, endAngle);
    c.strokeStyle = 'rgba(0,212,255,0.04)';
    c.lineWidth   = 18;
    c.lineCap     = 'round';
    c.stroke();

    // Background arc
    c.beginPath();
    c.arc(cx, cy, R, startAngle, endAngle);
    c.strokeStyle = 'rgba(255,255,255,0.06)';
    c.lineWidth   = 9;
    c.lineCap     = 'round';
    c.stroke();

    // Filled arc — gradient by speed
    if (speedKmh > 0) {
      const hue  = 160 - speedFrac * 160;
      const grad = c.createLinearGradient(cx - R, cy, cx + R, cy);
      grad.addColorStop(0, `hsl(160, 85%, 55%)`);
      grad.addColorStop(0.5, `hsl(50,  90%, 58%)`);
      grad.addColorStop(1,   `hsl(${hue}, 95%, 58%)`);

      c.beginPath();
      c.arc(cx, cy, R, startAngle, fillAngle);
      c.strokeStyle = grad;
      c.lineWidth   = 9;
      c.lineCap     = 'round';
      c.stroke();

      // Bright tip glow
      const tipX = cx + Math.cos(fillAngle) * R;
      const tipY = cy + Math.sin(fillAngle) * R;
      const tipColor = `hsl(${hue}, 100%, 72%)`;
      c.beginPath();
      c.arc(cx, cy, R, fillAngle - 0.008, fillAngle + 0.008);
      c.strokeStyle = tipColor;
      c.lineWidth   = 13;
      c.lineCap     = 'round';
      c.shadowColor = tipColor;
      c.shadowBlur  = 16;
      c.stroke();
      c.shadowBlur  = 0;

      // Needle line
      c.save();
      c.translate(cx, cy);
      c.rotate(fillAngle);
      const needleGrad = c.createLinearGradient(-R * 0.85, 0, 0, 0);
      needleGrad.addColorStop(0, 'rgba(255,255,255,0)');
      needleGrad.addColorStop(1, tipColor);
      c.beginPath();
      c.moveTo(-R * 0.82, 0);
      c.lineTo(0, 0);
      c.strokeStyle = needleGrad;
      c.lineWidth = 1.5;
      c.stroke();
      c.restore();
    }

    // Tick marks
    for (let v = 0; v <= MAX_SPEED; v += 20) {
      const angle = Math.PI + (v / MAX_SPEED) * Math.PI;
      const isMajor = v % 60 === 0;
      const isMed   = v % 40 === 0;
      const inner = R - (isMajor ? 16 : isMed ? 10 : 6);
      c.beginPath();
      c.moveTo(cx + Math.cos(angle) * R, cy + Math.sin(angle) * R);
      c.lineTo(cx + Math.cos(angle) * inner, cy + Math.sin(angle) * inner);
      c.strokeStyle = isMajor ? 'rgba(255,255,255,0.45)'
                    : isMed   ? 'rgba(255,255,255,0.22)'
                              : 'rgba(255,255,255,0.09)';
      c.lineWidth = isMajor ? 1.5 : 1;
      c.stroke();

      // Labels at major ticks
      if (isMajor && v <= MAX_SPEED) {
        const lx = cx + Math.cos(angle) * (inner - 8);
        const ly = cy + Math.sin(angle) * (inner - 8);
        c.textAlign    = 'center';
        c.textBaseline = 'middle';
        c.fillStyle    = 'rgba(122,139,168,0.55)';
        c.font         = `500 ${Math.round(R * 0.16)}px "JetBrains Mono"`;
        c.fillText(String(v), lx, ly);
      }
    }

    // Center hub dot
    c.beginPath();
    c.arc(cx, cy, 4, 0, Math.PI * 2);
    c.fillStyle = 'rgba(255,255,255,0.35)';
    c.fill();

    // Speed value
    c.textAlign    = 'center';
    c.textBaseline = 'bottom';
    c.fillStyle    = '#dde4f2';
    c.font         = `bold ${Math.round(R * 0.52)}px "JetBrains Mono"`;
    c.fillText(Math.round(speedKmh), cx, cy - 5);

    c.font      = `500 ${Math.round(R * 0.20)}px "Space Grotesk"`;
    c.fillStyle = 'rgba(122,139,168,0.7)';
    c.fillText('km/h', cx, cy + 5);
  }

  // ── G-Force Sparkline ────────────────────────────────────────────────────

  let gfCanvas, gfCtx;
  const SPARKLINE_MAX_G = 4.0;

  function initGForceChart(canvasEl) {
    gfCanvas = canvasEl;
    gfCtx    = canvasEl.getContext('2d');
  }

  function drawGForceChart(history) {
    const c = gfCtx;
    const rect = gfCanvas.getBoundingClientRect();
    const W = gfCanvas.width  = Math.round(rect.width)  || gfCanvas.width  || 300;
    const H = gfCanvas.height = Math.round(rect.height) || gfCanvas.height || 80;

    c.clearRect(0, 0, W, H);

    if (!history || history.length < 2) return;

    // Background
    c.fillStyle = 'rgba(255,255,255,0.018)';
    if (c.roundRect) { c.beginPath(); c.roundRect(0,0,W,H,6); c.fill(); }
    else { c.fillRect(0,0,W,H); }

    // Grid lines
    c.strokeStyle = 'rgba(255,255,255,0.04)';
    c.lineWidth = 1;
    for (let g = 1; g <= 4; g++) {
      const gy = H - (g / SPARKLINE_MAX_G) * H;
      c.beginPath();
      c.moveTo(0, gy);
      c.lineTo(W, gy);
      c.stroke();
    }

    // Danger threshold line (1g)
    const dangerY = H - (1.0 / SPARKLINE_MAX_G) * H;
    c.beginPath();
    c.moveTo(0, dangerY);
    c.lineTo(W, dangerY);
    c.strokeStyle = 'rgba(255,184,0,0.3)';
    c.setLineDash([4, 6]);
    c.lineWidth = 1;
    c.stroke();

    // Crash threshold (3g)
    const crashY = H - (3.0 / SPARKLINE_MAX_G) * H;
    c.beginPath();
    c.moveTo(0, crashY);
    c.lineTo(W, crashY);
    c.strokeStyle = 'rgba(255,61,90,0.25)';
    c.setLineDash([3, 5]);
    c.stroke();
    c.setLineDash([]);

    // Fill area under curve
    const step = W / (history.length - 1);
    const latest = history[history.length - 1];
    const fillColor = latest > 3 ? [255,61,90] : latest > 1 ? [255,184,0] : [0,212,255];

    const grad = c.createLinearGradient(0, 0, 0, H);
    grad.addColorStop(0, `rgba(${fillColor.join(',')},0.35)`);
    grad.addColorStop(0.6, `rgba(${fillColor.join(',')},0.08)`);
    grad.addColorStop(1, `rgba(${fillColor.join(',')},0.0)`);

    c.beginPath();
    c.moveTo(0, H);
    history.forEach((g, i) => {
      const x = i * step;
      const y = H - Math.min(1, g / SPARKLINE_MAX_G) * H;
      c.lineTo(x, y);
    });
    c.lineTo(W, H);
    c.closePath();
    c.fillStyle = grad;
    c.fill();

    // Line
    c.beginPath();
    history.forEach((g, i) => {
      const x = i * step;
      const y = H - Math.min(1, g / SPARKLINE_MAX_G) * H;
      i === 0 ? c.moveTo(x, y) : c.lineTo(x, y);
    });
    c.strokeStyle = `rgb(${fillColor.join(',')})`;
    c.lineWidth   = 1.5;
    c.lineJoin    = 'round';
    c.shadowColor = `rgb(${fillColor.join(',')})`;
    c.shadowBlur  = 4;
    c.stroke();
    c.shadowBlur  = 0;

    // Live dot at end
    const lastX = (history.length - 1) * step;
    const lastY = H - Math.min(1, latest / SPARKLINE_MAX_G) * H;
    c.beginPath();
    c.arc(lastX, lastY, 3, 0, Math.PI * 2);
    c.fillStyle = `rgb(${fillColor.join(',')})`;
    c.shadowColor = `rgb(${fillColor.join(',')})`;
    c.shadowBlur  = 8;
    c.fill();
    c.shadowBlur  = 0;
  }

  // ── Speed Distribution Histogram ─────────────────────────────────────────

  let histCanvas, histCtx;
  const HIST_BINS = 22;   // 0–220 km/h in 10 km/h buckets

  function initHistogram(canvasEl) {
    histCanvas = canvasEl;
    histCtx    = canvasEl.getContext('2d');
  }

  function drawHistogram(readings) {
    if (!histCanvas) return;
    const c = histCtx;
    const rect = histCanvas.getBoundingClientRect();
    const W = histCanvas.width  = Math.round(rect.width)  || histCanvas.width  || 800;
    const H = histCanvas.height = Math.round(rect.height) || histCanvas.height || 48;

    c.clearRect(0, 0, W, H);
    if (!readings || readings.length === 0) return;

    // Build histogram
    const bins = new Array(HIST_BINS).fill(0);
    let activeCount = 0;
    for (const r of readings) {
      if (!r) continue;
      const bin = Math.min(HIST_BINS - 1, Math.floor(r.speedKmh / 10));
      bins[bin]++;
      activeCount++;
    }

    const maxBin = Math.max(...bins, 1);
    const barW   = W / HIST_BINS;
    const gap    = 1.5;

    for (let i = 0; i < HIST_BINS; i++) {
      if (bins[i] === 0) continue;
      const frac = bins[i] / maxBin;
      const bH   = Math.max(3, frac * (H - 4));
      const x    = i * barW + gap * 0.5;
      const w    = barW - gap;
      const y    = H - bH;

      // Color by speed bucket
      const speedMid = i * 10 + 5;
      const t = speedMid / 200;
      const col = t < 0.4 ? `hsl(160,80%,55%)` : t < 0.7 ? `hsl(${160-(t-0.4)*400},80%,55%)` : `hsl(${40-(t-0.7)*133},90%,58%)`;

      // Bar gradient
      const grad = c.createLinearGradient(0, y, 0, H);
      grad.addColorStop(0, col.replace(')', ',0.9)').replace('hsl','hsla'));
      grad.addColorStop(1, col.replace(')', ',0.3)').replace('hsl','hsla'));

      c.fillStyle = grad;
      if (c.roundRect) {
        c.beginPath();
        c.roundRect(x, y, w, bH, [2,2,0,0]);
        c.fill();
      } else {
        c.fillRect(x, y, w, bH);
      }
    }

    // X axis labels
    c.fillStyle = 'rgba(122,139,168,0.45)';
    c.font      = '8px "JetBrains Mono"';
    c.textAlign = 'center';
    for (let i = 0; i <= HIST_BINS; i += 4) {
      const label = i * 10;
      const x     = (i / HIST_BINS) * W;
      c.fillText(String(label), x, H - 1);
    }
  }

  // ── Animated progress bar fills ──────────────────────────────────────────

  function updateBar(barId, pct, colorVar) {
    const el = document.getElementById(barId);
    if (!el) return;
    el.style.width      = `${Math.min(100, pct)}%`;
    el.style.background = colorVar;
  }

  return {
    initGauge, drawGauge,
    initGForceChart, drawGForceChart,
    initHistogram, drawHistogram,
    updateBar,
  };
})();
