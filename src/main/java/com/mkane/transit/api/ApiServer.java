package com.mkane.transit.api;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ApiServer — Lightweight REST HTTP Server (No Framework)
 * =========================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Uses JDK's built-in {@code com.sun.net.httpserver.HttpServer}
 * (available since Java 6, part of the JDK — no external dependency).
 *
 * Endpoints:
 *   GET /api/fleet            → JSON list of all Virtual_Fleet_Units
 *   GET /api/telemetry/latest → JSON of most recent telemetry per unit
 *   GET /api/crashes          → JSON list of all Crash_Events
 *
 * CORS:
 *   All responses include Access-Control-Allow-Origin: * so that the
 *   frontend served from a different port (e.g. file://) can fetch freely.
 *
 * Usage:
 *   ApiServer server = new ApiServer(8080);
 *   server.start();
 *   // ... simulation runs ...
 *   server.stop();
 */
public class ApiServer {

    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());

    private final int        port;
    private       HttpServer httpServer;

    public ApiServer(int port) {
        this.port = port;
    }

    /**
     * Starts the HTTP server and registers all route handlers.
     *
     * @throws IOException if the server socket cannot be bound
     */
    public void start() throws IOException {
        // Bind to 0.0.0.0:port — InetSocketAddress with no host binds all interfaces.
        httpServer = HttpServer.create(new InetSocketAddress(port), /* backlog */ 50);

        // ── Route registration ─────────────────────────────────────────────
        httpServer.createContext("/api/fleet",             new FleetHandler());
        httpServer.createContext("/api/telemetry/latest",  new TelemetryHandler());
        httpServer.createContext("/api/crashes",           new CrashHandler());
        httpServer.createContext("/api/health",            new HealthHandler());

        // Use a small thread pool for concurrent request handling.
        // 4 threads is sufficient — these are read-only JDBC queries.
        httpServer.setExecutor(Executors.newFixedThreadPool(4));

        httpServer.start();
        LOG.info(String.format("[ApiServer] HTTP server started on http://localhost:%d/", port));
        LOG.info("[ApiServer] Endpoints: /api/fleet | /api/telemetry/latest | /api/crashes | /api/health");
    }

    /** Gracefully stops the server, waiting up to 2 seconds for in-flight requests. */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(2);
            LOG.info("[ApiServer] HTTP server stopped.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared utilities for all handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a JSON response to the given {@link HttpExchange}.
     *
     * @param exchange  the HTTP exchange to respond to
     * @param status    HTTP status code (200, 500, etc.)
     * @param json      the JSON string body
     */
    static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes("UTF-8");

        // ── CORS headers — required so the browser dashboard can fetch ───────
        exchange.getResponseHeaders().set("Content-Type",                "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods","GET, OPTIONS");
        exchange.getResponseHeaders().set("Cache-Control",               "no-cache");

        // sendResponseHeaders(status, contentLength) — must be called before getResponseBody()
        exchange.sendResponseHeaders(status, body.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
            // OutputStream.close() flushes and signals end-of-body to the client.
        }
    }

    /** Handles pre-flight OPTIONS requests from browser dashboards. */
    static boolean handleOptions(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    /** Health check handler — useful for monitoring. */
    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            sendJson(exchange, 200, "{\"status\":\"ok\",\"service\":\"smart-transit-hub\"}");
        }
    }
}
