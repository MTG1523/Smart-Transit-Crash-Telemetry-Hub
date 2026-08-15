package com.mkane.transit.dao;

import com.mkane.transit.config.DatabaseManager;
import com.mkane.transit.model.TelemetryData;

import java.sql.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TelemetryDAO — Batch Insert Data Access Object
 * ================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Responsibility:
 *   Accepts a List<TelemetryData> produced by VirtualTelemetrySimulator
 *   and writes it to `Telemetry_Logs` in a single optimised JDBC batch.
 *
 * JDBC Mechanics Explained:
 *   1. setAutoCommit(false) — disables MySQL's per-statement auto-commit.
 *      All INSERTs within the batch become one atomic logical unit that the
 *      MySQL InnoDB engine writes in a single transaction log entry.
 *      This reduces fsync() syscalls from N (one per INSERT) to 1.
 *
 *   2. PreparedStatement — compiled once, bound N times.
 *      MySQL parses the SQL template once; each addBatch() call just
 *      supplies new parameter values without re-parsing.
 *      This eliminates SQL injection and is ~3x faster than Statement.
 *
 *   3. addBatch() — queues the current parameter set into an in-memory buffer.
 *      No network I/O occurs at this point.
 *
 *   4. executeBatch() — flushes the entire buffer to MySQL in one network
 *      round-trip (when `rewriteBatchedStatements=true` is in the JDBC URL,
 *      the driver rewrites N individual INSERTs into one multi-row INSERT,
 *      providing another 10–20x throughput improvement on large batches).
 *
 *   5. conn.commit() — writes the InnoDB transaction to disk.
 *
 *   6. conn.rollback() in catch — if any batch element fails (e.g. FK
 *      violation, duplicate key), the entire batch is reverted. No partial
 *      writes leak into the table.
 */
public class TelemetryDAO {

    private static final Logger LOG = Logger.getLogger(TelemetryDAO.class.getName());

    // Parameterised INSERT — ? placeholders map 1:1 to TelemetryData fields.
    // Column order matches the PreparedStatement.setXxx() call order below.
    private static final String INSERT_SQL =
        "INSERT INTO Telemetry_Logs " +
        "(unit_id, log_time, latitude, longitude, altitude_m, " +
        " speed_kmh, rpm, throttle_pct, brake_pressure, " +
        " accel_x, accel_y, accel_z, g_force_magnitude, is_crash_event) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Batch-inserts a list of telemetry readings into `Telemetry_Logs`.
     *
     * <p>Each thread gets its own dedicated {@link Connection} via
     * {@code DatabaseManager.newConnection()} so that transactions are fully
     * isolated between simulator threads.
     *
     * @param batch the list of {@link TelemetryData} to persist (typically 10 items)
     * @throws SQLException if any batch element violates a constraint or the
     *                      network drops — the entire batch is rolled back
     */
    public void insertBatch(List<TelemetryData> batch) throws SQLException {

        if (batch == null || batch.isEmpty()) {
            LOG.fine("[TelemetryDAO] insertBatch called with empty list — no-op.");
            return;
        }

        // ── Open a dedicated connection for this thread's batch ───────────────
        // Using try-with-resources: Connection.close() is called automatically
        // even if an exception is thrown anywhere in the try block.
        try (Connection conn = DatabaseManager.getInstance().newConnection()) {

            // ── JDBC Mechanic #1: Disable auto-commit ─────────────────────────
            // By default MySQL commits after every single statement.
            // Turning this off groups all addBatch() calls into one transaction,
            // reducing InnoDB journal writes from N to 1.
            conn.setAutoCommit(false);

            // ── JDBC Mechanic #2: Compile the PreparedStatement once ──────────
            // Statement.RETURN_GENERATED_KEYS asks the driver to capture the
            // AUTO_INCREMENT log_id values so we can populate TelemetryData.logId.
            try (PreparedStatement ps = conn.prepareStatement(
                    INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {

                for (TelemetryData td : batch) {

                    // ── JDBC Mechanic #3: Bind parameters for this row ────────
                    // The positional indices (1-based) must match the SQL column
                    // order in INSERT_SQL exactly.
                    ps.setInt       (1,  td.getUnitId());
                    ps.setTimestamp (2,  Timestamp.valueOf(td.getLogTime()));
                    ps.setDouble    (3,  td.getLatitude());
                    ps.setDouble    (4,  td.getLongitude());
                    ps.setDouble    (5,  td.getAltitudeM());
                    ps.setDouble    (6,  td.getSpeedKmh());
                    ps.setDouble    (7,  td.getRpm());
                    ps.setDouble    (8,  td.getThrottlePct());
                    ps.setDouble    (9,  td.getBrakePressure());
                    ps.setDouble    (10, td.getAccelX());
                    ps.setDouble    (11, td.getAccelY());
                    ps.setDouble    (12, td.getAccelZ());
                    ps.setDouble    (13, td.getGForceMagnitude());
                    ps.setBoolean   (14, td.isCrashEvent());

                    // ── JDBC Mechanic #4: Queue into the in-memory buffer ─────
                    // No network I/O here — just appends the bound parameters
                    // to the driver's internal ArrayList<byte[]>.
                    ps.addBatch();
                }

                // ── JDBC Mechanic #5: Single round-trip flush to MySQL ────────
                // With rewriteBatchedStatements=true in the JDBC URL, the
                // Connector/J driver rewrites the queued statements into:
                //   INSERT INTO Telemetry_Logs (...) VALUES (...),(...),(...)
                // This is one network packet instead of N, massively reducing
                // per-row latency at 50 concurrent threads.
                int[] results = ps.executeBatch();

                // ── Retrieve generated AUTO_INCREMENT keys ────────────────────
                // getGeneratedKeys() returns a ResultSet of log_id values in
                // the same order as the batch elements.
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    int i = 0;
                    while (keys.next() && i < batch.size()) {
                        batch.get(i++).setLogId(keys.getLong(1));
                    }
                }

                // ── JDBC Mechanic #6: Commit the transaction ──────────────────
                // Writes the InnoDB redo log to disk and releases row locks.
                conn.commit();

                LOG.fine(String.format("[TelemetryDAO] Batch committed: %d rows for unit_id=%d",
                         results.length, batch.get(0).getUnitId()));

            } catch (BatchUpdateException bue) {
                // BatchUpdateException contains an array of per-statement update
                // counts; -3 (EXECUTE_FAILED) flags the failing row index.
                LOG.log(Level.SEVERE,
                    "[TelemetryDAO] Batch partially failed. Counts: "
                    + java.util.Arrays.toString(bue.getUpdateCounts())
                    + " — rolling back entire batch.", bue);

                // ── JDBC Mechanic #7: Rollback on failure ─────────────────────
                // Guarantees zero partial writes — all-or-nothing semantics.
                safeRollback(conn);
                throw bue; // re-throw so the simulator thread can log it

            } catch (SQLException e) {
                LOG.log(Level.SEVERE, "[TelemetryDAO] SQL error during batch insert.", e);
                safeRollback(conn);
                throw e;
            }
        }
        // Connection.close() called automatically here by try-with-resources.
    }

    /**
     * Convenience helper — rolls back without swallowing the original exception.
     * Logs any secondary SQLException that occurs during rollback itself.
     */
    private void safeRollback(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.rollback();
                LOG.info("[TelemetryDAO] Transaction rolled back successfully.");
            }
        } catch (SQLException re) {
            LOG.log(Level.SEVERE, "[TelemetryDAO] Rollback failed — potential data inconsistency!", re);
        }
    }
}
