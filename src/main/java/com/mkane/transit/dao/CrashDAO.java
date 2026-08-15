package com.mkane.transit.dao;

import com.mkane.transit.config.DatabaseManager;
import com.mkane.transit.model.CrashEvent;

import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CrashDAO — ACID Transaction Data Access Object
 * ================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Responsibility:
 *   Persists a CrashEvent using a strict 2-statement ACID transaction:
 *     Statement 1 → INSERT into Crash_Events
 *     Statement 2 → UPDATE Virtual_Fleet_Units SET is_active = 0
 *
 *   Both statements MUST succeed together or NEITHER persists.
 *   This guarantees referential and business-logic integrity:
 *     → No orphaned crash records for still-active units.
 *     → No deactivated units without a corresponding crash record.
 *
 * ACID Property Mapping:
 *   Atomicity   — conn.commit() / conn.rollback() wraps both statements.
 *   Consistency — FK constraints and NOT NULL columns enforced by MySQL.
 *   Isolation   — each thread holds its own Connection (READ COMMITTED level).
 *   Durability  — InnoDB redo log ensures committed data survives crashes.
 *
 * JDBC Mechanics:
 *   1. setAutoCommit(false)  → begin explicit transaction
 *   2. PreparedStatement #1  → INSERT into Crash_Events, capture generated key
 *   3. PreparedStatement #2  → UPDATE Virtual_Fleet_Units
 *   4. conn.commit()         → flush both changes atomically to InnoDB log
 *   5. conn.rollback()       → in catch — revert both if EITHER fails
 */
public class CrashDAO {

    private static final Logger LOG = Logger.getLogger(CrashDAO.class.getName());

    // ── Maximum G-force used as the denominator for severity normalisation ────
    // A "total loss" crash at 50g maps to severity 1.0000.
    // Derived from NHTSA 2006-era test data (Barrier Equivalent Velocity).
    private static final double MAX_CRASH_G = 50.0;

    // ── SQL Statement 1: Insert crash record ─────────────────────────────────
    private static final String INSERT_CRASH_SQL =
        "INSERT INTO Crash_Events " +
        "(unit_id, trigger_log_id, crash_time, peak_g_force, severity_score, " +
        " crash_latitude, crash_longitude, notes) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    // ── SQL Statement 2: Deactivate the crashed unit ─────────────────────────
    // Parameterised WHERE clause prevents SQL injection even for internal calls.
    private static final String UPDATE_UNIT_SQL =
        "UPDATE Virtual_Fleet_Units SET is_active = 0 WHERE unit_id = ?";

    /**
     * Persists a crash event using a single ACID transaction.
     *
     * <p>If either the INSERT or the UPDATE fails (network drop, constraint
     * violation, deadlock), {@code conn.rollback()} reverts both statements,
     * leaving the database in its pre-crash state. The caller's thread can
     * log the failure and optionally retry.
     *
     * @param event the {@link CrashEvent} built by the simulator thread
     * @throws SQLException if the transaction cannot be committed (already rolled back)
     */
    public void recordCrashTransaction(CrashEvent event) throws SQLException {

        // ── Open a dedicated, thread-local connection ─────────────────────────
        // Each simulator thread must have its own Connection so that
        // setAutoCommit(false) does not interfere with other threads' batches.
        try (Connection conn = DatabaseManager.getInstance().newConnection()) {

            // ── ACID Step 1: Disable auto-commit to open the transaction ──────
            // From this point MySQL will NOT persist any DML until conn.commit()
            // or discard it all on conn.rollback().
            conn.setAutoCommit(false);

            // Use a flag to track whether the transaction reached commit.
            boolean committed = false;

            try {

                // ── ACID Step 2: PreparedStatement #1 — INSERT crash record ───
                long generatedCrashId;
                try (PreparedStatement insertPs = conn.prepareStatement(
                        INSERT_CRASH_SQL, Statement.RETURN_GENERATED_KEYS)) {

                    insertPs.setInt       (1, event.getUnitId());
                    insertPs.setLong      (2, event.getTriggerLogId());
                    insertPs.setTimestamp (3, Timestamp.valueOf(event.getCrashTime()));
                    insertPs.setDouble    (4, event.getPeakGForce());
                    // Clamp severity to [0.0, 1.0] before writing.
                    double severity = Math.min(1.0, event.getPeakGForce() / MAX_CRASH_G);
                    insertPs.setDouble    (5, severity);
                    insertPs.setDouble    (6, event.getCrashLatitude());
                    insertPs.setDouble    (7, event.getCrashLongitude());
                    insertPs.setString    (8, event.getNotes());

                    // executeUpdate() sends exactly one SQL statement to MySQL.
                    // Returns the row-count affected (must be 1).
                    int rowsInserted = insertPs.executeUpdate();
                    if (rowsInserted != 1) {
                        throw new SQLException(
                            "INSERT into Crash_Events affected " + rowsInserted
                            + " rows; expected 1. Aborting transaction.");
                    }

                    // Retrieve the AUTO_INCREMENT crash_id for logging.
                    try (ResultSet keys = insertPs.getGeneratedKeys()) {
                        if (keys.next()) {
                            generatedCrashId = keys.getLong(1);
                            event.setCrashId((int) generatedCrashId);
                        } else {
                            throw new SQLException("No generated key returned for Crash_Events INSERT.");
                        }
                    }

                    LOG.info(String.format(
                        "[CrashDAO] Crash record inserted: crash_id=%d, unit_id=%d, G=%.4f",
                        generatedCrashId, event.getUnitId(), event.getPeakGForce()));
                }

                // ── ACID Step 3: PreparedStatement #2 — UPDATE fleet unit ─────
                // Executes WITHIN the same open transaction as the INSERT above.
                // The UPDATE acquires a row-level lock on Virtual_Fleet_Units;
                // that lock is released when we commit or rollback below.
                try (PreparedStatement updatePs = conn.prepareStatement(UPDATE_UNIT_SQL)) {

                    updatePs.setInt(1, event.getUnitId());

                    int rowsUpdated = updatePs.executeUpdate();
                    if (rowsUpdated != 1) {
                        throw new SQLException(
                            "UPDATE Virtual_Fleet_Units affected " + rowsUpdated
                            + " rows for unit_id=" + event.getUnitId()
                            + "; expected 1. Rolling back.");
                    }

                    LOG.info(String.format(
                        "[CrashDAO] Fleet unit deactivated: unit_id=%d", event.getUnitId()));
                }

                // ── ACID Step 4: Commit — flush both DML statements atomically ─
                // At this moment InnoDB writes both the INSERT and the UPDATE
                // to its redo log and makes them visible to all other connections.
                conn.commit();
                committed = true;

                LOG.info(String.format(
                    "[CrashDAO] ACID transaction committed successfully for unit_id=%d",
                    event.getUnitId()));

            } catch (SQLException e) {

                // ── ACID Step 5: Rollback — guarantee zero data corruption ─────
                // If we reach here, at least one statement failed (or we threw
                // manually). rollback() discards ALL un-committed DML in this
                // transaction. The DB is left exactly as it was before this method.
                if (!committed) {
                    LOG.log(Level.SEVERE,
                        "[CrashDAO] Transaction FAILED for unit_id=" + event.getUnitId()
                        + " — rolling back to prevent partial write.", e);
                    safeRollback(conn);
                }
                throw e; // re-throw to let the simulator thread handle it

            } finally {
                // Always restore auto-commit before returning the connection.
                // This is critical if the connection were pooled (HikariCP etc.)
                // because the next borrower would otherwise inherit autoCommit=false.
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) { /* best-effort */ }
            }
        }
    }

    /** Rolls back without suppressing the original exception. */
    private void safeRollback(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.rollback();
                LOG.info("[CrashDAO] Rollback completed — database is consistent.");
            }
        } catch (SQLException re) {
            LOG.log(Level.SEVERE,
                "[CrashDAO] CRITICAL: Rollback itself failed! Manual intervention required.", re);
        }
    }
}
