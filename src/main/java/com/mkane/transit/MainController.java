package com.mkane.transit;

import com.mkane.transit.api.ApiServer;
import com.mkane.transit.config.DatabaseManager;
import com.mkane.transit.dao.CrashDAO;
import com.mkane.transit.dao.TelemetryDAO;
import com.mkane.transit.export.MLPipelineExporter;
import com.mkane.transit.model.FleetUnit;
import com.mkane.transit.simulator.VirtualTelemetrySimulator;

import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MainController — Stress-Test Entry Point & Orchestrator
 * =========================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Responsibilities:
 *   1. Register 50 virtual fleet units in `Virtual_Fleet_Units`.
 *   2. Instantiate one {@link VirtualTelemetrySimulator} per unit.
 *   3. Submit all simulators to a fixed-size {@link ExecutorService} thread pool.
 *   4. Wait for all threads to complete (or until interrupted).
 *   5. Trigger the {@link MLPipelineExporter} to export pre-crash CSV files.
 *   6. Print a final summary report to stdout.
 *
 * Thread Pool Design:
 *   A fixed thread pool of 50 threads is used to match the 50 vehicle simulators
 *   exactly (1:1 mapping). Each thread owns its own JDBC Connection, ensuring
 *   no contention on Connection state (autoCommit, open transactions, etc.).
 *
 *   For production scenarios with thousands of vehicles, switch to a bounded
 *   queue + rejection handler:
 *     new ThreadPoolExecutor(50, 200, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000))
 *
 * Shutdown Hook:
 *   A JVM shutdown hook ensures the ExecutorService is gracefully shut down and
 *   the DatabaseManager's shared connection is closed even on Ctrl+C / SIGTERM.
 */
public class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    // ── Configuration constants ───────────────────────────────────────────────
    private static final int    FLEET_SIZE         = 50;
    private static final int    SIM_DURATION_SECS  = 60;
    private static final String CSV_OUTPUT_DIR     = "output/ml_exports";
    private static final String UNIT_NAME_PREFIX   = "VFU";
    // Railway injects $PORT; fallback to 8080 for local dev
    private static final int    API_PORT           =
        Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    public static void main(String[] args) {

        LOG.info("═══════════════════════════════════════════════════════════════");
        LOG.info("  Smart Transit & Crash Telemetry Hub — m.kane");
        LOG.info("  Spinning up " + FLEET_SIZE + " virtual vehicle threads...");
        LOG.info("═══════════════════════════════════════════════════════════════");

        // ── Step 1: Verify JDBC connectivity ─────────────────────────────────
        DatabaseManager dbManager = DatabaseManager.getInstance();
        LOG.info("[Main] JDBC connection verified.");

        // ── Step 1b: Start REST API server (serves dashboard in live mode) ─────
        ApiServer apiServer = new ApiServer(API_PORT);
        try {
            apiServer.start();
            LOG.info("[Main] REST API running → http://localhost:" + API_PORT
                     + " (open frontend/index.html?mode=live in browser)");
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                "[Main] Could not start API server on port " + API_PORT
                + " — continuing without REST endpoints.", e);
        }

        // ── Step 2: Register fleet units in the database ──────────────────────
        List<FleetUnit> fleet = registerFleet(dbManager, FLEET_SIZE);
        if (fleet.isEmpty()) {
            LOG.severe("[Main] Fleet registration failed. Aborting.");
            return;
        }
        LOG.info("[Main] Registered " + fleet.size() + " fleet units in Virtual_Fleet_Units.");

        // ── Step 3: Create shared DAO instances ───────────────────────────────
        // Each DAO creates its own Connection per method call (thread-safe).
        TelemetryDAO telemetryDAO = new TelemetryDAO();
        CrashDAO     crashDAO     = new CrashDAO();

        // ── Step 4: Build fixed-size thread pool ──────────────────────────────
        // newFixedThreadPool(N) creates exactly N worker threads backed by an
        // unbounded LinkedBlockingQueue. All 50 tasks start concurrently since
        // pool size == task count.
        ExecutorService pool = Executors.newFixedThreadPool(FLEET_SIZE,
            r -> {
                Thread t = new Thread(r);
                t.setDaemon(false); // non-daemon: JVM waits for all threads to finish
                t.setName("VehicleSim-" + t.getId());
                return t;
            }
        );

        // ── Step 5: Register JVM shutdown hook ───────────────────────────────
        // Catches Ctrl+C, SIGTERM, and normal JVM exit.
        final ApiServer finalApiServer = apiServer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("[Shutdown] Signal received — draining thread pool...");
            pool.shutdownNow(); // interrupt all running threads
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOG.warning("[Shutdown] Thread pool did not terminate within 10 s.");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            finalApiServer.stop();
            dbManager.shutdown();
            LOG.info("[Shutdown] All services stopped. Goodbye.");
        }, "ShutdownHook"));

        // ── Step 6: Submit all 50 simulators to the pool ──────────────────────
        List<Future<?>> futures = new ArrayList<>(fleet.size());
        for (FleetUnit unit : fleet) {
            VirtualTelemetrySimulator sim =
                new VirtualTelemetrySimulator(unit, telemetryDAO, crashDAO);

            // ExecutorService.submit(Runnable) wraps the Runnable in a
            // FutureTask and queues it for immediate execution. Returns a
            // Future<?> so we can track completion / catch exceptions.
            Future<?> future = pool.submit(sim);
            futures.add(future);
        }

        LOG.info("[Main] All " + FLEET_SIZE + " simulator threads submitted to pool. Running...");

        // ── Step 7: Controlled shutdown after SIM_DURATION_SECS ──────────────
        // shutdown() prevents new tasks from being accepted but lets running
        // threads complete naturally. Combined with awaitTermination() this
        // gives a graceful drain period.
        pool.shutdown();

        try {
            boolean finished = pool.awaitTermination(SIM_DURATION_SECS, TimeUnit.SECONDS);
            if (finished) {
                LOG.info("[Main] All simulator threads completed within " + SIM_DURATION_SECS + " s.");
            } else {
                LOG.warning("[Main] SIM_DURATION_SECS (" + SIM_DURATION_SECS
                    + " s) elapsed — forcing shutdown of remaining threads.");
                pool.shutdownNow(); // interrupt any threads still running
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.SEVERE, "[Main] Main thread interrupted during pool await.", e);
            pool.shutdownNow();
        }

        // ── Step 8: Collect results from all futures ──────────────────────────
        int crashedCount  = 0;
        int exceptionCount = 0;
        for (int i = 0; i < futures.size(); i++) {
            Future<?> f = futures.get(i);
            try {
                f.get(0, TimeUnit.MILLISECONDS); // non-blocking: just check status
            } catch (ExecutionException ee) {
                exceptionCount++;
                LOG.log(Level.WARNING,
                    "[Main] Simulator for unit index " + i + " threw an exception.", ee.getCause());
            } catch (TimeoutException | InterruptedException ignored) {
                // Thread may still be running or was interrupted — acceptable.
            }
        }

        // Count vehicles that crashed (is_active == false after simulation).
        for (FleetUnit u : fleet) {
            if (!u.isActive()) crashedCount++;
        }

        // ── Step 9: Export ML CSV files ───────────────────────────────────────
        LOG.info("[Main] Starting ML pipeline export...");
        MLPipelineExporter exporter = new MLPipelineExporter();
        int exported = exporter.exportAllCrashes(CSV_OUTPUT_DIR, 30);

        // ── Step 10: Final summary ────────────────────────────────────────────
        LOG.info("═══════════════════════════════════════════════════════════════");
        LOG.info("  SIMULATION COMPLETE — Summary");
        LOG.info("  Fleet size        : " + FLEET_SIZE);
        LOG.info("  Crashed units     : " + crashedCount);
        LOG.info("  Thread exceptions : " + exceptionCount);
        LOG.info("  ML CSVs exported  : " + exported + " → " + CSV_OUTPUT_DIR);
        LOG.info("═══════════════════════════════════════════════════════════════");

        dbManager.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fleet Registration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inserts {@code count} rows into `Virtual_Fleet_Units` and returns the
     * corresponding {@link FleetUnit} objects (with auto-generated unit_ids).
     *
     * Uses a single PreparedStatement with batched inserts for efficiency.
     *
     * @param dbManager the singleton DatabaseManager
     * @param count     number of fleet units to register
     * @return list of registered FleetUnit POJOs
     */
    private static List<FleetUnit> registerFleet(DatabaseManager dbManager, int count) {
        List<FleetUnit> fleet = new ArrayList<>(count);

        String insertSql =
            "INSERT INTO Virtual_Fleet_Units (unit_name, model_year, vin_code, is_active) " +
            "VALUES (?, 2006, ?, 1)";

        // Use the shared connection for this one-time registration operation.
        try (Connection conn = dbManager.newConnection();
             PreparedStatement ps = conn.prepareStatement(
                 insertSql, Statement.RETURN_GENERATED_KEYS)) {

            conn.setAutoCommit(false);

            // Prepare FLEET_SIZE unit rows in a single batch.
            List<String[]> unitMeta = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                String unitName = String.format("%s-%02d", UNIT_NAME_PREFIX, i);
                // Generate a 17-character simulated VIN.
                // Format: VFU + zero-padded index + 8 random hex chars + model year.
                String vin = String.format("VFU%02d%s2006",
                    i, UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());

                ps.setString(1, unitName);
                ps.setString(2, vin);
                ps.addBatch();

                unitMeta.add(new String[]{unitName, vin});
            }

            ps.executeBatch();
            conn.commit();

            // Map AUTO_INCREMENT keys back to FleetUnit objects.
            try (ResultSet keys = ps.getGeneratedKeys()) {
                int idx = 0;
                while (keys.next() && idx < unitMeta.size()) {
                    int    generatedId = keys.getInt(1);
                    String unitName    = unitMeta.get(idx)[0];
                    String vin         = unitMeta.get(idx)[1];
                    fleet.add(new FleetUnit(generatedId, unitName, 2006, vin));
                    idx++;
                }
            }

            LOG.info("[Main] Fleet registration batch committed: " + fleet.size() + " units.");

        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "[Main] Fleet registration failed.", e);
        }

        return fleet;
    }
}
