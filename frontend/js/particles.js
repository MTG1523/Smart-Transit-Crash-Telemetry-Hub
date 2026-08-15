/**
 * particles.js — Ambient Background Particle System
 * ===================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Provides a subtle floating particle canvas behind the UI
 * to give the dashboard a living, breathable aesthetic.
 */

const ParticleSystem = (() => {

  let canvas, ctx, W, H;
  let particles = [];
  let rafId = null;
  const COUNT = 55;

  function createParticle() {
    return {
      x    : Math.random() * W,
      y    : Math.random() * H,
      r    : 0.5 + Math.random() * 1.5,
      vx   : (Math.random() - 0.5) * 0.18,
      vy   : -0.05 - Math.random() * 0.12,
      alpha: 0.06 + Math.random() * 0.22,
      hue  : Math.random() < 0.65 ? 195 : (Math.random() < 0.5 ? 275 : 130), // cyan / purple / green
      life : 0,
      maxLife: 260 + Math.random() * 400,
    };
  }

  function init() {
    canvas = document.getElementById('particle-canvas');
    if (!canvas) return;
    ctx = canvas.getContext('2d');
    resize();
    for (let i = 0; i < COUNT; i++) {
      const p = createParticle();
      p.life = Math.random() * p.maxLife; // stagger start
      particles.push(p);
    }
    window.addEventListener('resize', resize);
    loop();
  }

  function resize() {
    W = canvas.width  = window.innerWidth;
    H = canvas.height = window.innerHeight;
  }

  function loop() {
    ctx.clearRect(0, 0, W, H);
    for (let i = 0; i < particles.length; i++) {
      const p = particles[i];
      p.life++;
      p.x += p.vx;
      p.y += p.vy;

      // Fade in/out
      const frac = p.life / p.maxLife;
      const fade = frac < 0.1 ? frac / 0.1 : frac > 0.85 ? (1 - frac) / 0.15 : 1;

      ctx.beginPath();
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
      ctx.fillStyle = `hsla(${p.hue}, 90%, 65%, ${p.alpha * fade})`;
      ctx.fill();

      if (p.life >= p.maxLife || p.y < -10) {
        particles[i] = createParticle();
        particles[i].y = H + 5;
      }
    }
    rafId = requestAnimationFrame(loop);
  }

  function stop() {
    if (rafId) cancelAnimationFrame(rafId);
  }

  return { init, stop };
})();
