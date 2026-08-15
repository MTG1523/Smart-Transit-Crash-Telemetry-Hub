# Smart Transit & Crash Telemetry Hub

**Author:** MTG  
**Stack:** Pure Java 17 · Raw JDBC · MySQL 8 · Maven  
**Architecture:** DAO Pattern · Singleton · Runnable / ExecutorService  

---

## Project Overview

A software-only, multithreaded telemetry simulation system that models a fleet of 50 virtual 2006-era vehicles. It pumps high-velocity telemetry data into a MySQL database using production-grade JDBC patterns: batch inserts, ACID crash transactions, and a streaming ML CSV exporter.

**There is no physical hardware.** Every vehicle, sensor reading, and crash is simulated in pure Java.

---

## Project Structure

```
smart-transit-hub/
├── pom.xml                                          Maven build (Java 17, Shade plugin)
├── sql/
│   └── schema.sql                                   MySQL DDL (3 tables, FK, indexes)
└── src/main/java/com/mkane/transit/
    ├── config/
    │   └── DatabaseManager.java                     Bill Pugh singleton JDBC manager
    ├── model/
    │   ├── FleetUnit.java                           POJO — Virtual_Fleet_Units row
    │   ├── TelemetryData.java                       POJO — Telemetry_Logs row
    │   └── CrashEvent.java                          POJO — Crash_Events row
    ├── dao/
    │   ├── TelemetryDAO.java                        Batch INSERT (addBatch/executeBatch)
    │   └── CrashDAO.java                            ACID 2-query transaction + rollback
    ├── simulator/
    │   └── VirtualTelemetrySimulator.java           Runnable — 2006 physics, 10 Hz, 1% crash
    ├── export/
    │   └── MLPipelineExporter.java                  Pre-crash → ML-ready CSV exporter
    └── MainController.java                          50-thread ExecutorService orchestrator
```

---

## Architecture Decisions

| Concern | Solution | Rationale |
|---|---|---|
| Thread safety | Each thread gets its own `Connection` via `newConnection()` | `Connection` is not thread-safe; sharing causes data corruption |
| Batch throughput | `addBatch()` + `executeBatch()` + `rewriteBatchedStatements=true` | Rewrites N INSERTs → 1 multi-row INSERT; ~10–20x faster |
| Crash atomicity | `setAutoCommit(false)` + manual `commit()` / `rollback()` | Guarantees both `Crash_Events` INSERT and fleet UPDATE succeed together |
| Memory efficiency | `ResultSet.TYPE_FORWARD_ONLY` + `setFetchSize(MIN_VALUE)` | Streams rows from MySQL instead of loading all into heap |
| Driver loading | `Class.forName()` + JDBC 4.0 ServiceLoader fallback | Works with both modern and legacy Connector/J JARs |

---

## Physics Model — 2006 Mid-Size Saloon

| Parameter | Value | Source |
|---|---|---|
| Idle RPM | 750 rpm | Honda Accord 2006 spec |
| Redline | 6,500 rpm | DOHC 2.4L i4 spec |
| Top speed | 220 km/h | Electronic limiter |
| 0–100 km/h | ~9.5 s (≈ 0.30 g avg) | Road test data |
| ABS braking decel | 1.05 g peak | NHTSA 2006 dry tarmac |
| Max lateral G (corner) | 0.80 g | Typical OEM tyre limit |
| Crash G-range | 8–45 g | NHTSA BEV impact data |

---

## Quick Start

### 1. Prerequisites
- Java 17+
- Maven 3.8+
- MySQL 8.0+

### 2. Create the database
```sql
CREATE DATABASE smart_transit_hub CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'transit_user'@'localhost' IDENTIFIED BY 'transit_pass';
GRANT ALL PRIVILEGES ON smart_transit_hub.* TO 'transit_user'@'localhost';
FLUSH PRIVILEGES;
```

### 3. Apply the schema
```bash
mysql -u transit_user -p smart_transit_hub < sql/schema.sql
```

### 4. Build the fat JAR
```bash
mvn clean package -DskipTests
```

### 5. Run the simulation
```bash
java -jar target/smart-transit-hub-1.0.0-SNAPSHOT.jar
```

The simulation runs 50 concurrent vehicle threads for 60 seconds, then exports ML CSV files to `output/ml_exports/`.

---

## ML CSV Export

Each exported CSV covers the 30-second window before a crash:

```
log_id,unit_id,log_time_iso,seconds_before_crash,latitude,longitude,altitude_m,
speed_kmh,rpm,throttle_pct,brake_pressure,accel_x,accel_y,accel_z,
g_force_magnitude,is_crash_event
```

- **Label column:** `is_crash_event` (0 = normal, 1 = crash)
- **Time feature:** `seconds_before_crash` (counts down to 0 at impact)
- All floats use consistent precision matching the DB `DECIMAL` columns
- Zero null values — safe defaults applied

### Loading in Python
```python
import pandas as pd
df = pd.read_csv("output/ml_exports/crash_1_window_30s.csv",
                 parse_dates=["log_time_iso"])
X = df.drop(columns=["is_crash_event", "log_id", "log_time_iso"])
y = df["is_crash_event"]
```

---

## Key JDBC Patterns Reference

```java
// TelemetryDAO — Batch insert
conn.setAutoCommit(false);
PreparedStatement ps = conn.prepareStatement(SQL, Statement.RETURN_GENERATED_KEYS);
for (TelemetryData td : batch) {
    ps.setInt(1, td.getUnitId()); // ...bind all fields
    ps.addBatch();
}
ps.executeBatch();  // single network round-trip
conn.commit();

// CrashDAO — ACID transaction
conn.setAutoCommit(false);
try {
    insertPs.executeUpdate(); // Crash_Events INSERT
    updatePs.executeUpdate(); // Virtual_Fleet_Units UPDATE
    conn.commit();            // atomic flush
} catch (SQLException e) {
    conn.rollback();          // guarantee zero partial writes
    throw e;
}

// MLPipelineExporter — streaming ResultSet
ps.setFetchSize(Integer.MIN_VALUE); // MySQL streaming hint
ResultSet rs = ps.executeQuery();   // rows fetched one-by-one
```

---

## Configuration

Edit `DatabaseManager.java` to change connection details, or externalise to a `.properties` file for production:

```
JDBC_URL      = jdbc:mysql://localhost:3306/smart_transit_hub?rewriteBatchedStatements=true
JDBC_USER     = transit_user
JDBC_PASSWORD = transit_pass
```
