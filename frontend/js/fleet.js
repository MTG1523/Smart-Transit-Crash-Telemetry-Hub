/**
 * fleet.js — 50-Vehicle State Manager & Tick Loop
 * =================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Manages:
 *   • Initialising 50 vehicle states via Physics.createState()
 *   • Running a 10 Hz tick loop using setInterval
 *   • Maintaining per-unit telemetry history for charts
 *   • Emitting events via a simple pub/sub EventBus
 */

const Fleet = (() => {

  const FLEET_SIZE     = 50;
  const TICK_MS        = 100;    // 10 Hz
  const HISTORY_LEN    = 60;     // keep last 6 seconds of G-force history

  // Per-unit state (from Physics), keyed by unit index 0..49
  let states   = [];
  // Latest telemetry reading per unit
  let readings = [];
  // G-force history per unit (for chart)
  let gHistory = [];
  // Crash events accumulated this session
  let crashes  = [];
  // Total ticks and readings fired
  let totalReadings = 0;
  let tickIntervalId = null;

  // EventBus subscriptions
  const subs = {};

  function on(event, cb) {
    if (!subs[event]) subs[event] = [];
    subs[event].push(cb);
  }
  function emit(event, data) {
    (subs[event] || []).forEach(cb => cb(data));
  }

  /**
   * Initialise 50 vehicles and start the 10 Hz tick loop.
   */
  function start() {
    for (let i = 0; i < FLEET_SIZE; i++) {
      states.push(Physics.createState(i + 1));
      readings.push(null);
      gHistory.push([]);
    }
    tickIntervalId = setInterval(tick, TICK_MS);
    emit('ready', { fleetSize: FLEET_SIZE });
  }

  function stop() {
    clearInterval(tickIntervalId);
  }

  /**
   * One simulation tick — advances all active vehicles.
   * Called every 100 ms by setInterval.
   */
  function tick() {
    const batchReadings = [];

    for (let i = 0; i < FLEET_SIZE; i++) {
      const state = states[i];
      if (!state.isActive) continue;

      const reading = Physics.tick(state);
      readings[i]   = reading;
      totalReadings++;

      // Push to G-force history
      gHistory[i].push(reading.gForceMag);
      if (gHistory[i].length > HISTORY_LEN) gHistory[i].shift();

      if (reading.isCrash) {
        const crashEntry = {
          time      : new Date(),
          unitId    : i + 1,
          unitName  : `VFU-${String(i + 1).padStart(2, '0')}`,
          peakG     : reading.gForceMag,
          severity  : Math.min(1, reading.gForceMag / 50),
          lat       : reading.latitude,
          lon       : reading.longitude,
          speedAtImpact: reading.speedKmh,
        };
        crashes.push(crashEntry);
        emit('crash', crashEntry);
      }

      batchReadings.push(reading);
    }

    emit('tick', {
      readings      : readings.slice(),   // shallow copy is fine
      totalReadings,
      activeCount   : states.filter(s => s.isActive).length,
      crashCount    : crashes.length,
    });
  }

  // ── Public accessors ────────────────────────────────────────────────────

  function getState(idx)    { return states[idx]; }
  function getReading(idx)  { return readings[idx]; }
  function getGHistory(idx) { return gHistory[idx]; }
  function getCrashes()     { return crashes; }
  function getStates()      { return states; }

  function dataRate() {
    // readings per second across all active vehicles
    const active = states.filter(s => s.isActive).length;
    return active * 10;
  }

  return { start, stop, on, getState, getReading, getGHistory, getCrashes, getStates, dataRate };
})();
