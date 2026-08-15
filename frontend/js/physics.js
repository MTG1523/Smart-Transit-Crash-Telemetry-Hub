/**
 * physics.js — 2006 Vehicle Physics Engine (JavaScript Mirror)
 * ==============================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Mirrors VirtualTelemetrySimulator.java's physics model exactly.
 * Used by fleet.js to generate telemetry in the browser without
 * a running Java backend.
 */

const Physics = (() => {

  // ── 2006 Vehicle constants (identical to Java simulator) ────────────────
  const MAX_SPEED_KMH       = 220.0;
  const IDLE_RPM            = 750.0;
  const REDLINE_RPM         = 6500.0;
  const MAX_BRAKE_DECEL_G   = 1.05;
  const NORMAL_ACCEL_G      = 0.30;
  const NORMAL_CORNER_G_MAX = 0.80;
  const CRASH_G_MIN         = 8.0;
  const CRASH_G_MAX         = 45.0;
  const CRASH_PROBABILITY   = 0.01;   // 1 % per tick
  const MAX_DELTA_KMH       = 4.0;    // max speed change per 100 ms tick

  // GPS bounding box (Dakar, Senegal — same as Java)
  const LAT_BASE  =  14.6928;
  const LON_BASE  = -17.4467;
  const GPS_DRIFT =   0.05;

  /**
   * Creates initial vehicle state for a fleet unit.
   * @param {number} unitId
   */
  function createState(unitId) {
    const rng = seededRng(unitId * 31 + Date.now());
    return {
      unitId,
      rng,
      speedKmh   : 20 + rng() * 30,
      rpm        : 1800 + rng() * 800,
      lat        : LAT_BASE  + (rng() - 0.5) * GPS_DRIFT,
      lon        : LON_BASE  + (rng() - 0.5) * GPS_DRIFT,
      altitudeM  : 15 + rng() * 40,
      isActive   : true,
      trail      : [],  // last N GPS positions for the map trail
    };
  }

  /**
   * Advances a vehicle state by one 100 ms tick.
   * Returns { state, reading } — mutates state in place.
   *
   * @param {object} state  mutable vehicle state
   * @returns {object}      telemetry reading for this tick
   */
  function tick(state) {
    const rng = state.rng;
    const isCrash = rng() < CRASH_PROBABILITY;

    let accelX, accelY, accelZ, gMag;
    let throttlePct = 0, brakePressure = 0;
    const prevSpeed = state.speedKmh;

    if (isCrash) {
      // ── Crash: catastrophic deceleration ────────────────────────────────
      const crashG = CRASH_G_MIN + rng() * (CRASH_G_MAX - CRASH_G_MIN);
      accelX = -crashG * (0.7 + rng() * 0.3);
      accelY =  crashG * (rng() - 0.5) * 0.4;
      accelZ =  crashG * rng() * 0.2;
      gMag   = Math.sqrt(accelX**2 + accelY**2 + accelZ**2);
      state.speedKmh = 0;
      state.rpm      = 0;
      brakePressure  = 100;
      state.isActive = false;
    } else {
      // ── Normal driving ───────────────────────────────────────────────────
      const delta = (rng() * 2 - 1) * MAX_DELTA_KMH;
      state.speedKmh = Math.max(0, Math.min(MAX_SPEED_KMH, state.speedKmh + delta));

      // RPM: 5-speed gearbox model
      const normSpeed    = state.speedKmh / MAX_SPEED_KMH;
      const gearFraction = (normSpeed % 0.2) / 0.2;
      state.rpm = IDLE_RPM + (REDLINE_RPM - IDLE_RPM) * gearFraction + rng() * 200 - 100;
      state.rpm = Math.max(IDLE_RPM, Math.min(REDLINE_RPM, state.rpm));

      // Accelerometer
      const accelMs2 = ((state.speedKmh - prevSpeed) / 3.6) * 10;
      const accelG   = accelMs2 / 9.81;
      accelX = Math.max(-MAX_BRAKE_DECEL_G, Math.min(NORMAL_ACCEL_G * 2, accelG));
      accelY = (rng() - 0.5) * 2 * NORMAL_CORNER_G_MAX * 0.3;
      accelZ = (rng() - 0.5) * 0.1;
      gMag   = Math.sqrt(accelX**2 + accelY**2 + accelZ**2);

      const actualDelta = state.speedKmh - prevSpeed;
      if (actualDelta > 0) {
        throttlePct  = Math.min(100, (actualDelta / MAX_DELTA_KMH) * 100);
      } else {
        brakePressure = Math.min(100, (-actualDelta / MAX_DELTA_KMH) * 100);
      }
    }

    // ── GPS random walk ────────────────────────────────────────────────────
    if (!isCrash) {
      const distPerTick = (state.speedKmh / 3600) * 0.1; // 100 ms
      const bearing     = rng() * 2 * Math.PI;
      const latPerM     = 1 / 111320;
      const lonPerM     = 1 / (111320 * Math.cos(state.lat * Math.PI / 180));
      state.lat += distPerTick * 1000 * Math.cos(bearing) * latPerM;
      state.lon += distPerTick * 1000 * Math.sin(bearing) * lonPerM;
      state.lat  = Math.max(LAT_BASE - GPS_DRIFT, Math.min(LAT_BASE + GPS_DRIFT, state.lat));
      state.lon  = Math.max(LON_BASE - GPS_DRIFT, Math.min(LON_BASE + GPS_DRIFT, state.lon));
      state.altitudeM += (rng() - 0.5) * 0.5;
    }

    // Store trail (max 12 points)
    state.trail.push({ lat: state.lat, lon: state.lon });
    if (state.trail.length > 12) state.trail.shift();

    return {
      unitId      : state.unitId,
      logTime     : new Date(),
      latitude    : round(state.lat, 7),
      longitude   : round(state.lon, 7),
      altitudeM   : round(state.altitudeM, 2),
      speedKmh    : round(state.speedKmh, 2),
      rpm         : round(state.rpm, 2),
      throttlePct : round(throttlePct, 2),
      brakePressure: round(brakePressure, 2),
      accelX      : round(accelX, 4),
      accelY      : round(accelY, 4),
      accelZ      : round(accelZ, 4),
      gForceMag   : round(gMag, 4),
      isCrash,
    };
  }

  // ── Utilities ─────────────────────────────────────────────────────────────

  function round(v, dp) {
    const f = Math.pow(10, dp);
    return Math.round(v * f) / f;
  }

  /**
   * Simple mulberry32 seeded PRNG — deterministic per unit.
   * Produces same sequence as Java's seeded Random (close enough for display).
   */
  function seededRng(seed) {
    let s = seed >>> 0;
    return function() {
      s += 0x6d2b79f5;
      let t = Math.imul(s ^ (s >>> 15), 1 | s);
      t = t + Math.imul(t ^ (t >>> 7), 61 | t) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  return { createState, tick, LAT_BASE, LON_BASE, GPS_DRIFT };
})();
