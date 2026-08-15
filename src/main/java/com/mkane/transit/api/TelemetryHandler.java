package com.mkane.transit.api;

import com.mkane.transit.config.DatabaseManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TelemetryHandler — GET /api/telemetry/latest
 * ==============================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Returns the most recent telemetry row per fleet unit.
 * The frontend uses this to update the inspector panel in live API mode.
 *
 * Uses a correlated subquery (MAX log_id per unit) to efficiently
 * retrieve only the latest row without a full table scan.
 *
 * Response shape:
 * [
 *   { "unitId": 1, "logTime": "...", "speedKmh": 87.5,
 *     "rpm": 3200, "gForceMagnitude": 0.312, ... }
 * ]
 */
public class TelemetryHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(TelemetryHandler.class.getName());

    // Correlated subquery: for each unit, select only the row with max log_id.
    // This leverages the idx_unit_time index defined in schema.sql.
    private static final String QUERY =
        "SELECT t.unit_id, t.log_time, t.latitude, t.longitude, t.altitude_m, " +
        "       t.speed_kmh, t.rpm, t.throttle_pct, t.brake_pressure, " +
        "       t.accel_x, t.accel_y, t.accel_z, t.g_force_magnitude, t.is_crash_event " +
        "FROM Telemetry_Logs t " +
        "INNER JOIN (" +
        "  SELECT unit_id, MAX(log_id) AS max_id " +
        "  FROM Telemetry_Logs GROUP BY unit_id" +
        ") latest ON t.unit_id = latest.unit_id AND t.log_id = latest.max_id " +
        "ORDER BY t.unit_id ASC";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiServer.handleOptions(exchange)) return;

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiServer.sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        try {
            String json = buildJson();
            ApiServer.sendJson(exchange, 200, json);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "[TelemetryHandler] DB error", e);
            ApiServer.sendJson(exchange, 500,
                "{\"error\":\"Database error: " + escJson(e.getMessage()) + "\"}");
        }
    }

    private String buildJson() throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;

        Connection conn = DatabaseManager.getInstance().getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs   = stmt.executeQuery(QUERY)) {

            while (rs.next()) {
                if (!first) sb.append(",");
                first = false;

                Timestamp ts = rs.getTimestamp("log_time");
                sb.append(String.format(
                    "{\"unitId\":%d,\"logTime\":\"%s\"," +
                    "\"latitude\":%.7f,\"longitude\":%.7f,\"altitudeM\":%.2f," +
                    "\"speedKmh\":%.2f,\"rpm\":%.2f," +
                    "\"throttlePct\":%.2f,\"brakePressure\":%.2f," +
                    "\"accelX\":%.4f,\"accelY\":%.4f,\"accelZ\":%.4f," +
                    "\"gForceMagnitude\":%.4f,\"isCrashEvent\":%b}",
                    rs.getInt("unit_id"),
                    ts != null ? ts.toLocalDateTime().toString() : "",
                    rs.getDouble("latitude"),
                    rs.getDouble("longitude"),
                    rs.getDouble("altitude_m"),
                    rs.getDouble("speed_kmh"),
                    rs.getDouble("rpm"),
                    rs.getDouble("throttle_pct"),
                    rs.getDouble("brake_pressure"),
                    rs.getDouble("accel_x"),
                    rs.getDouble("accel_y"),
                    rs.getDouble("accel_z"),
                    rs.getDouble("g_force_magnitude"),
                    rs.getBoolean("is_crash_event")
                ));
            }
        }

        sb.append("]");
        return sb.toString();
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
