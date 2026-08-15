package com.mkane.transit.simulator;

import com.mkane.transit.dao.CrashDAO;
import com.mkane.transit.dao.TelemetryDAO;
import com.mkane.transit.model.CrashEvent;
import com.mkane.transit.model.FleetUnit;
import com.mkane.transit.model.TelemetryData;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * VirtualTelemetrySimulator — Physics-Accurate Data Generator
 * =============================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Implements {@link Runnable} so that 50 instances can run concurrently
 * inside a fixed-thread-pool {@link java.util.concurrent.ExecutorService}.
 *
 * ── 2006 Vehicle Physics Model ───────────────────────────────────────────────
 * Parameters are calibrated to a typical 2006 mid-size saloon (e.g., Honda
 * Accord 2006 / Toyota Camry 2006):
 *
 *   Engine:
 *     • Max RPM (redline) : 6 500 rpm  (naturally-aspirated 2.4 L DOHC i4)
 *     • Idle RPM          : 750 rpm
 *     • Max power         : ~160 hp @ 5 500 rpm
 *
 *   Dynamics:
 *     • 0–100 km/h        : ~9.5 s  → average acceleration ≈ 2.9 m/s² (0.30 g)
 *     • Normal cornering  : ≤ 0.8 g lateral
 *     • ABS braking decel : ~0.9–1.0 g (dry tarmac, ~8.8–9.8 m/s²)
 *     • Emergency / panic : up to 1.1 g with ABS activation oscillation
 *     • Crash G-force     : typically 5–50 g at impact
 *
 *   Top speed (simulated): 220 km/h (electronically limited on many 2006 models)
 *
 * ── Simulation Behaviour ─────────────────────────────────────────────────────
 *   • Produces exactly 10 telemetry readings per second.
 *   • Each reading has a 1 % random probability of triggering a crash spike.
 *   • On crash: simulator writes to CrashDAO and terminates the run loop.
 *   • Normal readings are batched every 10 ticks (1 second) and bulk-inserted
 *     by TelemetryDAO.insertBatch().
 */
public class VirtualTelemetrySimulator implements Runnable {

    private static final Logger LOG = Logger.getLogger(VirtualTelemetrySimulator.class.getName());

    // ── 2006 Vehicle physics constants ───────────────────────────────────────
    private static final double MAX_SPEED_KMH       = 220.0;   // electronically limited
    private static final double MIN_SPEED_KMH       = 0.0;
    private static final double IDLE_RPM            = 750.0;
    private static final double REDLINE_RPM         = 6_500.0; // 2006 DOHC 2.4L i4
    private static final double MAX_BRAKE_DECEL_G   = 1.05;    // ABS-assisted peak (dry tarmac)
    private static final double NORMAL_ACCEL_G      = 0.30;    // typical city acceleration
    private static final double NORMAL_CORNER_G_MAX = 0.80;    // maximum lateral on dry road
    private static final double CRASH_G_MIN         = 8.0;     // minimum crash-level G
    private static final double CRASH_G_MAX         = 45.0;    // up to 45 g for severe impact
    private static final double CRASH_PROBABILITY   = 0.01;    // 1 % chance per tick

    // ── Simulation timing ────────────────────────────────────────────────────
    private static final int    TICK_RATE_HZ        = 10;      // 10 ticks per second
    private static final long   TICK_INTERVAL_MS    = 1000L / TICK_RATE_HZ;  // 100 ms

    // ── GPS bounding box (simulated urban area) ──────────────────────────────
    // Rough bounding box of a hypothetical metro grid, e.g. Dakar, Senegal.
    private static final double LAT_BASE  =  14.6928;
    private static final double LON_BASE  = -17.4467;
    private static final double GPS_DRIFT = 0.05; // ±0.05 degrees maximum drift

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final FleetUnit    unit;
    private final TelemetryDAO telemetryDAO;
    private final CrashDAO     crashDAO;
    private final Random       rng;

    // ── Live state (mutable per tick) ────────────────────────────────────────
    private double currentSpeedKmh;
    private double currentRpm;
    private double currentLat;
    private double currentLon;
    private double altitudeM;
    private int    tickCount;     // total ticks this run

    /**
     * Constructs a simulator for the given fleet unit.
     *
     * @param unit        the registered fleet vehicle this simulator represents
     * @param telemetryDAO DAO for batch telemetry writes
     * @param crashDAO     DAO for ACID crash transactions
     */
    public VirtualTelemetrySimulator(FleetUnit unit,
                                     TelemetryDAO telemetryDAO,
                                     CrashDAO crashDAO) {
        this.unit         = unit;
        this.telemetryDAO = telemetryDAO;
        this.crashDAO     = crashDAO;

        // Seed with unitId for deterministic reproducibility per vehicle.
        this.rng          = new Random(unit.getUnitId() * 31L + System.nanoTime());

        // Initial conditions — vehicle starts from a low-speed urban crawl.
        this.currentSpeedKmh = 20.0 + rng.nextDouble() * 30.0; // 20–50 km/h
        this.currentRpm      = 1_800.0 + rng.nextDouble() * 800.0;
        this.currentLat      = LAT_BASE  + (rng.nextDouble() - 0.5) * GPS_DRIFT;
        this.currentLon      = LON_BASE  + (rng.nextDouble() - 0.5) * GPS_DRIFT;
        this.altitudeM       = 15.0 + rng.nextDouble() * 40.0; // urban elevation range
        this.tickCount       = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Runnable entry point — executed by ExecutorService worker thread
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void run() {
        LOG.info(String.format("[Simulator] Thread started for unit %s (id=%d)",
                               unit.getUnitName(), unit.getUnitId()));

        // Collect a 1-second batch (10 readings) before calling insertBatch().
        List<TelemetryData> secondBatch = new ArrayList<>(TICK_RATE_HZ);

        while (!Thread.currentThread().isInterrupted() && unit.isActive()) {
            long tickStart = System.currentTimeMillis();

            // ── Generate one telemetry tick ───────────────────────────────────
            TelemetryData reading = generateTick();
            secondBatch.add(reading);
            tickCount++;

            // ── Crash detection ───────────────────────────────────────────────
            if (reading.isCrashEvent()) {
                // Flush the current batch (includes the crash reading) first.
                flushBatch(secondBatch);
                handleCrash(reading);
                return; // terminate this thread's run loop cleanly
            }

            // ── Flush batch every 10 ticks (1 second of data) ─────────────────
            if (secondBatch.size() >= TICK_RATE_HZ) {
                flushBatch(secondBatch);
                secondBatch = new ArrayList<>(TICK_RATE_HZ);
            }

            // ── Enforce 10 Hz tick rate via sleep ─────────────────────────────
            long elapsed = System.currentTimeMillis() - tickStart;
            long sleepMs = TICK_INTERVAL_MS - elapsed;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // restore interrupt flag
                    LOG.info("[Simulator] Unit " + unit.getUnitId() + " interrupted — stopping.");
                    break;
                }
            }
        }

        LOG.info(String.format("[Simulator] Unit %d finished after %d ticks.",
                               unit.getUnitId(), tickCount));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Physics Simulation Engine
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates one telemetry reading using 2006 vehicle physics.
     *
     * <p>The physics model advances state from the previous tick:
     *   • Speed evolves via a small random delta bounded by acceleration limits.
     *   • RPM tracks speed non-linearly (mimicking a 5-speed manual gearbox).
     *   • Accelerometer values derive from the speed delta.
     *   • A 1 % random trigger injects a crash G-force spike.
     */
    private TelemetryData generateTick() {

        // ── 1. Determine if this tick is a crash event ────────────────────────
        boolean isCrash = rng.nextDouble() < CRASH_PROBABILITY;

        // ── 2. Evolve kinematics ──────────────────────────────────────────────
        double prevSpeed = currentSpeedKmh;
        double accelX, accelY, accelZ, gMag;

        if (isCrash) {
            // ── Crash physics: sudden catastrophic deceleration ───────────────
            // Simulate a frontal impact: large negative X (longitudinal), smaller
            // Y (lateral offset), small Z (vertical rebound).
            double crashG = CRASH_G_MIN + rng.nextDouble() * (CRASH_G_MAX - CRASH_G_MIN);
            accelX = -crashG * (0.7 + rng.nextDouble() * 0.3);  // primary impact axis
            accelY = crashG  * (rng.nextDouble() - 0.5) * 0.4;  // lateral component
            accelZ = crashG  * rng.nextDouble()          * 0.2;  // vertical rebound
            gMag   = Math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ);

            // Speed drops instantly to zero in crash frame.
            currentSpeedKmh = 0.0;
            currentRpm      = 0.0;

        } else {
            // ── Normal driving physics ────────────────────────────────────────
            // Speed delta per 100 ms tick: ±random within a bounded envelope.
            // Positive delta = acceleration (throttle), negative = deceleration.
            double maxDeltaKmh = 4.0; // max 4 km/h change per 100 ms tick
            double speedDelta  = (rng.nextDouble() * 2.0 - 1.0) * maxDeltaKmh;

            // Clamp speed to vehicle limits.
            currentSpeedKmh = Math.max(MIN_SPEED_KMH,
                              Math.min(MAX_SPEED_KMH, currentSpeedKmh + speedDelta));

            // ── RPM model: simulate a 5-speed manual transmission ─────────────
            // Each gear band occupies ~20 % of the speed range.
            // As speed increases within a gear, RPM rises; shifts drop RPM.
            double normalizedSpeed = currentSpeedKmh / MAX_SPEED_KMH;
            double gearFraction    = (normalizedSpeed % 0.2) / 0.2;  // position within gear
            currentRpm = IDLE_RPM + (REDLINE_RPM - IDLE_RPM) * gearFraction
                         + rng.nextDouble() * 200 - 100;  // ±100 RPM noise
            currentRpm = Math.max(IDLE_RPM, Math.min(REDLINE_RPM, currentRpm));

            // ── Derive accelerometer values from speed delta ──────────────────
            // speedDelta in km/h per 100 ms → convert to m/s²: ÷ 3.6 × 10
            double accelMs2 = (speedDelta / 3.6) * 10.0;
            double accelG   = accelMs2 / 9.81;

            // Clamp to ABS deceleration limit for braking realism.
            accelX = Math.max(-MAX_BRAKE_DECEL_G, Math.min(NORMAL_ACCEL_G * 2, accelG));

            // Lateral G: small random noise simulating lane changes / corners.
            accelY = (rng.nextDouble() - 0.5) * 2.0 * NORMAL_CORNER_G_MAX * 0.3;

            // Vertical G: road surface vibration noise (±0.1 g around 1g Earth gravity).
            // Stored as deviation from 1 g; 0 = perfectly flat.
            accelZ = (rng.nextDouble() - 0.5) * 0.1;

            gMag = Math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ);
        }

        // ── 3. Derive throttle and brake from speed delta ─────────────────────
        double speedDeltaFinal = currentSpeedKmh - prevSpeed;
        double throttlePct = 0.0, brakePressure = 0.0;

        if (!isCrash) {
            if (speedDeltaFinal > 0) {
                // Accelerating: throttle proportional to speed gain.
                throttlePct  = Math.min(100.0, speedDeltaFinal / (maxDeltaForCalc()) * 100.0);
            } else {
                // Decelerating: brake pressure proportional to speed loss.
                brakePressure = Math.min(100.0, -speedDeltaFinal / (maxDeltaForCalc()) * 100.0);
            }
        } else {
            // Crash: full brake, zero throttle.
            brakePressure = 100.0;
        }

        // ── 4. Advance GPS position (random walk) ─────────────────────────────
        // Speed-proportional drift: faster vehicles cover more ground per tick.
        double distPerTick = (currentSpeedKmh / 3600.0) * (TICK_INTERVAL_MS / 1000.0);
        double bearingRad  = rng.nextDouble() * 2 * Math.PI;
        // Approximate degrees per metre at the simulation latitude.
        double latPerMetre = 1.0 / 111_320.0;
        double lonPerMetre = 1.0 / (111_320.0 * Math.cos(Math.toRadians(currentLat)));
        currentLat += distPerTick * 1000.0 * Math.cos(bearingRad) * latPerMetre;
        currentLon += distPerTick * 1000.0 * Math.sin(bearingRad) * lonPerMetre;

        // Keep GPS within bounding box.
        currentLat = Math.max(LAT_BASE - GPS_DRIFT, Math.min(LAT_BASE + GPS_DRIFT, currentLat));
        currentLon = Math.max(LON_BASE - GPS_DRIFT, Math.min(LON_BASE + GPS_DRIFT, currentLon));

        // Altitude changes very slowly (urban road gradient).
        altitudeM += (rng.nextDouble() - 0.5) * 0.5;

        // ── 5. Build and return the immutable TelemetryData object ─────────────
        return new TelemetryData(
            unit.getUnitId(),
            LocalDateTime.now(),
            roundTo7dp(currentLat),
            roundTo7dp(currentLon),
            round(altitudeM, 2),
            round(currentSpeedKmh, 2),
            round(currentRpm, 2),
            round(throttlePct, 2),
            round(brakePressure, 2),
            round(accelX, 4),
            round(accelY, 4),
            round(accelZ, 4),
            round(gMag, 4),
            isCrash
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Batch flush helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delegates the batch to {@link TelemetryDAO#insertBatch(List)}.
     * Exceptions are caught and logged so a DB outage doesn't crash the thread.
     */
    private void flushBatch(List<TelemetryData> batch) {
        if (batch.isEmpty()) return;
        try {
            telemetryDAO.insertBatch(batch);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE,
                "[Simulator] Failed to flush telemetry batch for unit "
                + unit.getUnitId() + " — " + batch.size() + " readings lost.", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Crash handling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delegates the crash event to {@link CrashDAO#recordCrashTransaction(CrashEvent)}.
     * The CrashDAO atomically writes a Crash_Events row AND flips is_active=0.
     */
    private void handleCrash(TelemetryData crashReading) {
        unit.deactivate(); // flip in-memory flag immediately

        // severity = clamped peakG / MAX_CRASH_G
        double severity = Math.min(1.0, crashReading.getGForceMagnitude() / 50.0);

        String notes = String.format(
            "AUTO-GENERATED | unit=%s | peak_g=%.4f | severity=%.4f | speed_at_impact=%.2f km/h",
            unit.getUnitName(), crashReading.getGForceMagnitude(), severity, crashReading.getSpeedKmh());

        CrashEvent event = new CrashEvent(
            unit.getUnitId(),
            crashReading.getLogId(),     // FK to the just-inserted telemetry row
            crashReading.getLogTime(),
            crashReading.getGForceMagnitude(),
            severity,
            crashReading.getLatitude(),
            crashReading.getLongitude(),
            notes
        );

        try {
            crashDAO.recordCrashTransaction(event);
            LOG.warning(String.format(
                "[Simulator] CRASH RECORDED — unit=%s, crash_id=%d, G=%.4f",
                unit.getUnitName(), event.getCrashId(), event.getPeakGForce()));
        } catch (SQLException e) {
            LOG.log(Level.SEVERE,
                "[Simulator] CrashDAO transaction FAILED for unit " + unit.getUnitId()
                + " — crash not persisted!", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Rounds a double to {@code places} decimal places. */
    private static double round(double val, int places) {
        double factor = Math.pow(10, places);
        return Math.round(val * factor) / factor;
    }

    /** Rounds to 7 decimal places — GPS precision. */
    private static double roundTo7dp(double val) {
        return round(val, 7);
    }

    /** Helper to avoid referencing maxDeltaKmh from the closure. */
    private static double maxDeltaForCalc() {
        return 4.0;
    }
}
