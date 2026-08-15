package com.mkane.transit.api;

import com.mkane.transit.config.DatabaseManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FleetHandler — GET /api/fleet
 * ==============================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Queries Virtual_Fleet_Units and returns a JSON array.
 * The frontend dashboard polls this to update metric cards.
 *
 * Response shape:
 * {
 *   "activeCount": 48,
 *   "totalUnits": 50,
 *   "units": [
 *     { "unitId": 1, "unitName": "VFU-01", "modelYear": 2006,
 *       "vinCode": "VFU01...", "isActive": true }
 *   ]
 * }
 */
public class FleetHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(FleetHandler.class.getName());

    private static final String QUERY =
        "SELECT unit_id, unit_name, model_year, vin_code, is_active " +
        "FROM Virtual_Fleet_Units ORDER BY unit_id ASC";

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
            LOG.log(Level.SEVERE, "[FleetHandler] DB error", e);
            ApiServer.sendJson(exchange, 500,
                "{\"error\":\"Database error: " + escJson(e.getMessage()) + "\"}");
        }
    }

    private String buildJson() throws SQLException {
        StringBuilder units = new StringBuilder("[");
        int total  = 0;
        int active = 0;
        boolean first = true;

        // Each API handler uses the singleton's shared connection (read-only query).
        Connection conn = DatabaseManager.getInstance().getConnection();

        try (Statement stmt = conn.createStatement();
             ResultSet rs   = stmt.executeQuery(QUERY)) {

            while (rs.next()) {
                total++;
                boolean isActive = rs.getBoolean("is_active");
                if (isActive) active++;

                if (!first) units.append(",");
                first = false;

                units.append(String.format(
                    "{\"unitId\":%d,\"unitName\":\"%s\",\"modelYear\":%d," +
                    "\"vinCode\":\"%s\",\"isActive\":%b}",
                    rs.getInt("unit_id"),
                    escJson(rs.getString("unit_name")),
                    rs.getInt("model_year"),
                    escJson(rs.getString("vin_code")),
                    isActive
                ));
            }
        }

        units.append("]");
        return String.format("{\"activeCount\":%d,\"totalUnits\":%d,\"units\":%s}",
                             active, total, units);
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
