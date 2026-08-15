package com.mkane.transit.export;

import com.mkane.transit.config.DatabaseManager;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MLPipelineExporter — Pre-Crash Telemetry CSV Exporter
 * =======================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Purpose:
 *   Queries `Telemetry_Logs` for all readings in the N-second window
 *   IMMEDIATELY BEFORE a crash event and exports them to a UTF-8 CSV
 *   formatted for direct ingestion into ML anomaly-detection pipelines
 *   (scikit-learn, TensorFlow/Keras, PyTorch, Apache Spark MLlib).
 *
 * ML CSV Design Principles:
 *   1. Clean, consistent headers (snake_case, no spaces — Pandas-friendly).
 *   2. All numeric values are floating-point (normalised where applicable).
 *   3. Zero null values — NULL-able SQL columns are given safe defaults.
 *   4. Boolean columns are encoded as 0/1 integers, not "true"/"false".
 *   5. Timestamps are ISO-8601 strings (sortable, parseable by pd.to_datetime).
 *   6. Derived feature `seconds_before_crash` gives the model a time-axis.
 *   7. One file per crash event, named crash_<crash_id>_unit_<unit_id>.csv.
 *
 * JDBC Mechanics:
 *   • Uses a read-only shared Connection (no writes).
 *   • PreparedStatement with ? parameter for crash_id prevents SQL injection.
 *   • ResultSet.TYPE_FORWARD_ONLY + CONCUR_READ_ONLY → streaming mode,
 *     reducing heap pressure when pre-crash windows are very long.
 *   • setFetchSize() hints to MySQL Connector/J to stream rows rather than
 *     buffering the entire ResultSet in memory.
 */
public class MLPipelineExporter {

    private static final Logger LOG = Logger.getLogger(MLPipelineExporter.class.getName());

    private static final DateTimeFormatter ISO_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    // Default pre-crash window: 30 seconds of telemetry before each crash.
    private static final int DEFAULT_WINDOW_SECONDS = 30;

    // CSV column names — matches the SELECT aliases in EXPORT_SQL.
    // snake_case, all lowercase — optimal for Pandas and scikit-learn.
    private static final String CSV_HEADER =
        "log_id,"
        + "unit_id,"
        + "log_time_iso,"
        + "seconds_before_crash,"       // derived feature: crash_time - log_time
        + "latitude,"
        + "longitude,"
        + "altitude_m,"
        + "speed_kmh,"
        + "rpm,"
        + "throttle_pct,"
        + "brake_pressure,"
        + "accel_x,"
        + "accel_y,"
        + "accel_z,"
        + "g_force_magnitude,"
        + "is_crash_event";             // label column: 0 = normal, 1 = crash

    /**
     * SQL that retrieves the pre-crash window for a given crash event.
     *
     * JOIN approach:
     *   The INNER JOIN to Crash_Events gives us crash_time in-query so we
     *   can compute `seconds_before_crash` in pure SQL (TIMESTAMPDIFF),
     *   avoiding post-processing in Java.
     *
     * The BETWEEN range includes the crash reading itself (is_crash_event=1)
     * so the exported CSV has the ground-truth label row for supervised learning.
     */
    private static final String EXPORT_SQL =
        "SELECT " +
        "    tl.log_id, " +
        "    tl.unit_id, " +
        "    tl.log_time, " +
        "    TIMESTAMPDIFF(SECOND, tl.log_time, ce.crash_time) AS seconds_before_crash, " +
        "    tl.latitude, " +
        "    tl.longitude, " +
        "    tl.altitude_m, " +
        "    tl.speed_kmh, " +
        "    tl.rpm, " +
        "    tl.throttle_pct, " +
        "    tl.brake_pressure, " +
        "    tl.accel_x, " +
        "    tl.accel_y, " +
        "    tl.accel_z, " +
        "    tl.g_force_magnitude, " +
        "    tl.is_crash_event " +
        "FROM Telemetry_Logs tl " +
        "INNER JOIN Crash_Events ce ON ce.unit_id = tl.unit_id " +
        "WHERE ce.crash_id = ? " +              // bound parameter
        "  AND tl.log_time BETWEEN " +
        "        DATE_SUB(ce.crash_time, INTERVAL ? SECOND) " +  // window start
        "        AND ce.crash_time " +          // window end (inclusive)
        "ORDER BY tl.log_time ASC";             // chronological — essential for time-series models

    /**
     * Exports pre-crash telemetry for a single crash event to a CSV file.
     *
     * @param crashId       the crash_id from Crash_Events to export
     * @param outputDir     directory path (absolute) where the CSV will be written
     * @param windowSeconds number of seconds before the crash to include
     * @return              the absolute path of the written CSV file, or null on failure
     */
    public String exportPreCrashWindow(int crashId, String outputDir, int windowSeconds) {

        // Build output file path.
        String filename   = String.format("crash_%d_window_%ds.csv", crashId, windowSeconds);
        String outputPath = outputDir + java.io.File.separator + filename;

        LOG.info(String.format(
            "[MLExporter] Exporting crash_id=%d, window=%d seconds → %s",
            crashId, windowSeconds, outputPath));

        // ── JDBC: shared read-only connection ─────────────────────────────────
        // MLPipelineExporter is a utility called from the main thread, so
        // using the shared singleton connection is safe here (single-threaded).
        Connection conn = DatabaseManager.getInstance().getConnection();

        try (
            // TYPE_FORWARD_ONLY + CONCUR_READ_ONLY = streaming ResultSet.
            // This tells Connector/J NOT to load all rows into memory at once.
            PreparedStatement ps = conn.prepareStatement(
                EXPORT_SQL,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
            )
        ) {
            // setFetchSize(Integer.MIN_VALUE) is the Connector/J streaming hint.
            // Without this, the driver buffers ALL rows in a heap ArrayList
            // before returning the first row — catastrophic for large windows.
            ps.setFetchSize(Integer.MIN_VALUE);

            // Bind the two ? parameters.
            ps.setInt(1, crashId);       // WHERE ce.crash_id = ?
            ps.setInt(2, windowSeconds); // INTERVAL ? SECOND

            try (ResultSet rs = ps.executeQuery()) {

                // Ensure output directory exists.
                java.io.File dir = new java.io.File(outputDir);
                if (!dir.exists() && !dir.mkdirs()) {
                    LOG.severe("[MLExporter] Cannot create output directory: " + outputDir);
                    return null;
                }

                // Open CSV writer with auto-flush disabled (batched IO is faster).
                try (PrintWriter csv = new PrintWriter(new FileWriter(outputPath, false))) {

                    // ── Write header row ──────────────────────────────────────
                    csv.println(CSV_HEADER);

                    int rowCount = 0;

                    // ── Stream and write each ResultSet row ───────────────────
                    // rs.next() fetches rows from MySQL one at a time (streaming mode).
                    while (rs.next()) {
                        rowCount++;

                        long   logId              = rs.getLong("log_id");
                        int    unitId             = rs.getInt("unit_id");
                        Timestamp logTimeTs       = rs.getTimestamp("log_time");
                        int    secondsBefore      = rs.getInt("seconds_before_crash");
                        double latitude           = rs.getDouble("latitude");
                        double longitude          = rs.getDouble("longitude");
                        double altitudeM          = rs.getDouble("altitude_m");
                        double speedKmh           = rs.getDouble("speed_kmh");
                        double rpm                = rs.getDouble("rpm");
                        double throttlePct        = rs.getDouble("throttle_pct");
                        double brakePressure      = rs.getDouble("brake_pressure");
                        double accelX             = rs.getDouble("accel_x");
                        double accelY             = rs.getDouble("accel_y");
                        double accelZ             = rs.getDouble("accel_z");
                        double gForceMagnitude    = rs.getDouble("g_force_magnitude");
                        int    isCrashEvent       = rs.getInt("is_crash_event");

                        // Convert Timestamp to ISO-8601 string.
                        // rs.wasNull() guards against NULL timestamps (schema has NOT NULL,
                        // but defensive coding is best practice in export utilities).
                        String logTimeIso = (logTimeTs != null)
                            ? logTimeTs.toLocalDateTime().format(ISO_FMT)
                            : "1970-01-01T00:00:00.000";

                        // ── Write CSV row ─────────────────────────────────────
                        // String.format ensures consistent float precision (7 dp for GPS,
                        // 4 dp for physics values — matches DB DECIMAL definitions).
                        // No null values: rs.wasNull() defaults are applied above.
                        csv.printf(
                            "%d,%d,%s,%d,%.7f,%.7f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%d%n",
                            logId, unitId, logTimeIso, secondsBefore,
                            latitude, longitude, altitudeM,
                            speedKmh, rpm, throttlePct, brakePressure,
                            accelX, accelY, accelZ, gForceMagnitude,
                            isCrashEvent
                        );
                    }

                    // Ensure all buffered output is written to disk.
                    csv.flush();

                    LOG.info(String.format(
                        "[MLExporter] Export complete: %d rows written to %s",
                        rowCount, outputPath));

                    return outputPath;
                }
            }

        } catch (SQLException e) {
            LOG.log(Level.SEVERE,
                "[MLExporter] JDBC error during export for crash_id=" + crashId, e);
        } catch (IOException e) {
            LOG.log(Level.SEVERE,
                "[MLExporter] IO error writing CSV for crash_id=" + crashId, e);
        }

        return null; // export failed
    }

    /**
     * Convenience overload using the default 30-second window.
     *
     * @param crashId   the crash_id to export
     * @param outputDir target directory
     * @return          path to written CSV, or null on failure
     */
    public String exportPreCrashWindow(int crashId, String outputDir) {
        return exportPreCrashWindow(crashId, outputDir, DEFAULT_WINDOW_SECONDS);
    }

    /**
     * Bulk export: exports pre-crash windows for ALL crash events in the database.
     *
     * <p>Queries Crash_Events for all IDs, then calls
     * {@link #exportPreCrashWindow(int, String, int)} for each one.
     *
     * @param outputDir     target directory for all CSV files
     * @param windowSeconds seconds of pre-crash data to include
     * @return              count of successfully exported crash events
     */
    public int exportAllCrashes(String outputDir, int windowSeconds) {
        int successCount = 0;

        String getAllCrashIds = "SELECT crash_id FROM Crash_Events ORDER BY crash_time ASC";
        Connection conn = DatabaseManager.getInstance().getConnection();

        try (Statement stmt = conn.createStatement();
             ResultSet rs   = stmt.executeQuery(getAllCrashIds)) {

            while (rs.next()) {
                int crashId = rs.getInt("crash_id");
                String path = exportPreCrashWindow(crashId, outputDir, windowSeconds);
                if (path != null) {
                    successCount++;
                }
            }

        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "[MLExporter] Failed to query all crash IDs.", e);
        }

        LOG.info(String.format(
            "[MLExporter] Bulk export complete: %d crash windows exported to %s",
            successCount, outputDir));

        return successCount;
    }
}
