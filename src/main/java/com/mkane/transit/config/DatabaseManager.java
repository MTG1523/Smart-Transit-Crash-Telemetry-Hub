package com.mkane.transit.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DatabaseManager — Thread-Safe JDBC Connection Singleton
 * =========================================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Design:
 *   • "Initialization-on-demand holder" (Bill Pugh Singleton) guarantees
 *     thread-safe lazy initialization WITHOUT synchronization overhead on
 *     every getConnection() call.
 *   • Provides one shared {@link Connection} for light usage; for true
 *     production concurrency at 50+ threads, swap the single connection
 *     for a connection pool (HikariCP) — the interface here is compatible.
 *
 * JDBC mechanics:
 *   1. Class.forName() is NOT required for JDBC 4.0+ drivers (Java 6+), as
 *      the driver self-registers via ServiceLoader.  It is included here for
 *      explicit clarity and compatibility with older JARs.
 *   2. DriverManager.getConnection(url, user, pass) blocks until the TCP
 *      handshake and authentication complete — keep this out of hot paths.
 */
public final class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());

    // ── Connection parameters — externalise to a .properties file in prod ──
    private static final String JDBC_URL      = "jdbc:mysql://localhost:3306/smart_transit_hub"
                                                + "?useSSL=false"
                                                + "&serverTimezone=UTC"
                                                + "&rewriteBatchedStatements=true"  // crucial for batch perf
                                                + "&allowPublicKeyRetrieval=true";
    private static final String JDBC_USER     = "transit_user";
    private static final String JDBC_PASSWORD = "transit_pass";
    private static final String JDBC_DRIVER   = "com.mysql.cj.jdbc.Driver";

    /** Singleton instance, lazily created and held by the inner holder class. */
    private Connection connection;

    // Private constructor prevents external instantiation.
    private DatabaseManager() {
        initConnection();
    }

    /**
     * Bill Pugh Singleton Holder.
     * The JVM loads this static inner class only when {@code getInstance()} is
     * first called, providing lazy initialisation without any synchronized block.
     */
    private static final class Holder {
        private static final DatabaseManager INSTANCE = new DatabaseManager();
    }

    /** Returns the singleton DatabaseManager instance. */
    public static DatabaseManager getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Establishes the underlying JDBC connection.
     * Called once from the private constructor.
     */
    private void initConnection() {
        try {
            // Explicit driver registration for compatibility with JDBC 3 environments.
            Class.forName(JDBC_DRIVER);

            // DriverManager.getConnection performs the TCP handshake + MySQL auth.
            this.connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);

            LOG.info("[DatabaseManager] JDBC connection established → " + JDBC_URL);

        } catch (ClassNotFoundException e) {
            LOG.log(Level.SEVERE,
                "[DatabaseManager] MySQL Connector/J driver not found on classpath. "
                + "Add mysql-connector-j-*.jar to /lib.", e);
            throw new RuntimeException("JDBC driver class not found.", e);

        } catch (SQLException e) {
            LOG.log(Level.SEVERE,
                "[DatabaseManager] Failed to open JDBC connection. "
                + "Check host, port, credentials, and that the DB exists.", e);
            throw new RuntimeException("Cannot initialise JDBC connection.", e);
        }
    }

    /**
     * Returns the shared {@link Connection}.
     *
     * <p><strong>Thread-safety note:</strong> A single {@code Connection} is NOT
     * thread-safe for concurrent writes.  In {@code MainController} each
     * {@code VirtualTelemetrySimulator} thread calls this method, so in the
     * current design each DAO opens a <em>fresh</em> connection via
     * {@link #newConnection()}.  This shared connection is provided as a
     * convenience for single-threaded utilities (e.g. {@code MLPipelineExporter}).
     */
    public Connection getConnection() {
        try {
            // Auto-reconnect: if connection dropped, re-initialise.
            if (connection == null || connection.isClosed()) {
                LOG.warning("[DatabaseManager] Connection was closed — reconnecting.");
                initConnection();
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[DatabaseManager] isClosed() check failed.", e);
            initConnection();
        }
        return connection;
    }

    /**
     * Opens and returns a <em>brand-new</em> JDBC {@link Connection}.
     *
     * <p>Used by every DAO method called from a worker thread so that each
     * thread gets its own independent connection (and therefore its own
     * auto-commit state, transaction scope, etc.).
     *
     * @return a fresh, open Connection (caller is responsible for closing it)
     * @throws SQLException if the database cannot be reached
     */
    public Connection newConnection() throws SQLException {
        try {
            Class.forName(JDBC_DRIVER);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not found.", e);
        }
        // Each call to getConnection opens a distinct socket/auth session.
        return DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
    }

    /**
     * Gracefully closes the shared connection.
     * Call this from a JVM shutdown hook or application teardown.
     */
    public void shutdown() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                    LOG.info("[DatabaseManager] Shared JDBC connection closed cleanly.");
                }
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "[DatabaseManager] Error closing connection.", e);
            }
        }
    }
}
