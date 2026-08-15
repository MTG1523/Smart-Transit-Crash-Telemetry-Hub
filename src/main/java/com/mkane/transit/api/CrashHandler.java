package com.mkane.transit.api;

import com.mkane.transit.config.DatabaseManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CrashHandler — GET /api/crashes
 * =================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Returns crash events from Crash_Events, most recent first.
 * Supports an optional ?limit=N query parameter (default 20).
 *
 * Response shape:
 * [
 *   { "crashId": 3, "unitId": 7, "crashTime": "...",
 *     "peakGForce": 22.4501, "severityScore": 0.4490,
 *     "crashLatitude": 14.6912345, "crashLongitude": -17.4489123,
 *     "notes": "AUTO-GENERATED | ..." }
 * ]
 */
public class CrashHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(CrashHandler.class.getName());
    private static final int DEFAULT_LIMIT = 20;

    private static final String QUERY =
        "SELECT crash_id, unit_id, crash_time, peak_g_force, severity_score, " +
        "       crash_latitude, crash_longitude, notes " +
        "FROM Crash_Events " +
        "ORDER BY crash_time DESC " +
        "LIMIT ?";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (ApiServer.handleOptions(exchange)) return;

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ApiServer.sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        // Parse ?limit=N from query string
        int limit = parseLimit(exchange.getRequestURI());

        try {
            String json = buildJson(limit);
            ApiServer.sendJson(exchange, 200, json);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "[CrashHandler] DB error", e);
            ApiServer.sendJson(exchange, 500,
                "{\"error\":\"Database error: " + escJson(e.getMessage()) + "\"}");
        }
    }

    private String buildJson(int limit) throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;

        Connection conn = DatabaseManager.getInstance().getConnection();

        // PreparedStatement with LIMIT ? — prevents injection even on internal query params.
        try (PreparedStatement ps = conn.prepareStatement(QUERY)) {
            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) sb.append(",");
                    first = false;

                    Timestamp ts = rs.getTimestamp("crash_time");
                    sb.append(String.format(
                        "{\"crashId\":%d,\"unitId\":%d,\"crashTime\":\"%s\"," +
                        "\"peakGForce\":%.4f,\"severityScore\":%.4f," +
                        "\"crashLatitude\":%.7f,\"crashLongitude\":%.7f," +
                        "\"notes\":\"%s\"}",
                        rs.getInt("crash_id"),
                        rs.getInt("unit_id"),
                        ts != null ? ts.toLocalDateTime().toString() : "",
                        rs.getDouble("peak_g_force"),
                        rs.getDouble("severity_score"),
                        rs.getDouble("crash_latitude"),
                        rs.getDouble("crash_longitude"),
                        escJson(rs.getString("notes"))
                    ));
                }
            }
        }

        sb.append("]");
        return sb.toString();
    }

    private int parseLimit(URI uri) {
        String query = uri.getQuery();
        if (query == null) return DEFAULT_LIMIT;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "limit".equalsIgnoreCase(kv[0])) {
                try { return Math.min(200, Math.max(1, Integer.parseInt(kv[1]))); }
                catch (NumberFormatException ignored) { /* fall through */ }
            }
        }
        return DEFAULT_LIMIT;
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ");
    }
}
