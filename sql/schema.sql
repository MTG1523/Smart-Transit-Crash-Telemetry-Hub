-- =============================================================================
-- Smart Transit & Crash Telemetry Hub — MySQL Schema
-- Author  : m.kane
-- Purpose : Define the three core relational tables that back the telemetry
--           simulator, the crash-event ACID pipeline, and the ML CSV exporter.
-- Engine  : InnoDB (required for FK enforcement and ACID transactions)
-- Charset : utf8mb4 (full Unicode including emoji in notes fields)
-- =============================================================================

-- Drop order respects FK constraints: children before parents.
DROP TABLE IF EXISTS `Crash_Events`;
DROP TABLE IF EXISTS `Telemetry_Logs`;
DROP TABLE IF EXISTS `Virtual_Fleet_Units`;

-- -----------------------------------------------------------------------------
-- TABLE 1: Virtual_Fleet_Units
--   The parent / registry table. One row = one simulated vehicle (one thread).
--   `is_active` is atomically flipped to 0 by CrashDAO inside a transaction.
-- -----------------------------------------------------------------------------
CREATE TABLE `Virtual_Fleet_Units` (
    `unit_id`        INT            NOT NULL AUTO_INCREMENT,
    `unit_name`      VARCHAR(64)    NOT NULL COMMENT 'Human-readable label, e.g. "VFU-07"',
    `model_year`     SMALLINT       NOT NULL DEFAULT 2006
                                   COMMENT 'Simulated vehicle model year (2006)',
    `vin_code`       CHAR(17)       NOT NULL
                                   COMMENT '17-character simulated VIN (ISO 3779)',
    `is_active`      TINYINT(1)     NOT NULL DEFAULT 1
                                   COMMENT '1=actively transmitting, 0=crashed or offline',
    `created_at`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`unit_id`),
    UNIQUE  KEY `uq_vin`    (`vin_code`),
    INDEX         `idx_active` (`is_active`)
        COMMENT 'Allows fast filter of active vehicles'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='Registry of all simulated virtual fleet vehicles.';


-- -----------------------------------------------------------------------------
-- TABLE 2: Telemetry_Logs
--   High-frequency append-only child table: 10 rows/second per vehicle thread.
--   At 50 threads this is 500 INSERT rows/second.
--
--   Precision rationale:
--     DECIMAL(10,7) for GPS  → sub-metre accuracy (1e-7 deg ≈ 1.1 cm at equator)
--     DECIMAL(7,4)  for G    → 4 decimal places sufficient for ±50 g range
--     DECIMAL(6,2)  for speed → 0–9999.99 km/h (covers any realistic simulation)
-- -----------------------------------------------------------------------------
CREATE TABLE `Telemetry_Logs` (
    `log_id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `unit_id`           INT           NOT NULL
                                      COMMENT 'FK → Virtual_Fleet_Units.unit_id',
    `log_time`          DATETIME(3)   NOT NULL
                                      COMMENT 'Millisecond-resolution timestamp',

    -- ── GPS Position ──────────────────────────────────────────────────────────
    `latitude`          DECIMAL(10,7) NOT NULL COMMENT 'WGS-84 latitude  (range: -90 to +90)',
    `longitude`         DECIMAL(10,7) NOT NULL COMMENT 'WGS-84 longitude (range: -180 to +180)',
    `altitude_m`        DECIMAL(7,2)  NOT NULL DEFAULT 0.00
                                      COMMENT 'Altitude above sea level in metres',

    -- ── Kinematics (tuned to 2006 vehicle physics envelope) ──────────────────
    `speed_kmh`         DECIMAL(6,2)  NOT NULL
                                      COMMENT 'Instantaneous speed in km/h (0 to 240)',
    `rpm`               DECIMAL(7,2)  NOT NULL
                                      COMMENT 'Engine RPM (idle ~750, redline ~6500 for 2006)',
    `throttle_pct`      DECIMAL(5,2)  NOT NULL
                                      COMMENT 'Throttle pedal position 0.00 to 100.00 %',
    `brake_pressure`    DECIMAL(5,2)  NOT NULL
                                      COMMENT 'Brake hydraulic pressure 0.00 to 100.00 %',

    -- ── 3-Axis Accelerometer (MEMS g-force sensor output) ────────────────────
    `accel_x`           DECIMAL(7,4)  NOT NULL COMMENT 'Longitudinal axis g-force',
    `accel_y`           DECIMAL(7,4)  NOT NULL COMMENT 'Lateral axis      g-force',
    `accel_z`           DECIMAL(7,4)  NOT NULL COMMENT 'Vertical axis     g-force',

    -- ── Derived Magnitude (pre-computed in Java for ML pipeline efficiency) ───
    `g_force_magnitude` DECIMAL(7,4)  NOT NULL
                                      COMMENT 'Resultant vector magnitude: sqrt(x^2+y^2+z^2)',

    -- ── Event flag ────────────────────────────────────────────────────────────
    `is_crash_event`    TINYINT(1)    NOT NULL DEFAULT 0
                                      COMMENT '1 if this reading triggered a crash detection',

    PRIMARY KEY (`log_id`),
    INDEX `idx_unit_time`  (`unit_id`, `log_time`)
        COMMENT 'Covers pre-crash window range-scan queries',
    INDEX `idx_crash_flag` (`is_crash_event`)
        COMMENT 'Fast retrieval of crash-flagged rows for export',

    CONSTRAINT `fk_tel_unit`
        FOREIGN KEY (`unit_id`)
        REFERENCES  `Virtual_Fleet_Units` (`unit_id`)
        ON DELETE CASCADE
        ON UPDATE RESTRICT
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='High-frequency telemetry stream — 10 Hz per virtual vehicle thread.';


-- -----------------------------------------------------------------------------
-- TABLE 3: Crash_Events
--   Written atomically inside a 2-statement ACID transaction in CrashDAO.java.
--   Every row here is guaranteed to have a corresponding is_active=0 in
--   Virtual_Fleet_Units — atomicity is enforced in Java via conn.commit() /
--   conn.rollback() pattern.
-- -----------------------------------------------------------------------------
CREATE TABLE `Crash_Events` (
    `crash_id`         INT           NOT NULL AUTO_INCREMENT,
    `unit_id`          INT           NOT NULL
                                     COMMENT 'FK → Virtual_Fleet_Units.unit_id',
    `trigger_log_id`   BIGINT        NOT NULL
                                     COMMENT 'FK → Telemetry_Logs.log_id (the reading that fired the crash)',
    `crash_time`       DATETIME(3)   NOT NULL
                                     COMMENT 'Millisecond-precise timestamp of crash detection',
    `peak_g_force`     DECIMAL(7,4)  NOT NULL
                                     COMMENT 'Maximum resultant G-force recorded at impact',
    `severity_score`   DECIMAL(5,4)  NOT NULL
                                     COMMENT 'Normalised severity 0.0000 (minor) to 1.0000 (total loss)',
    `crash_latitude`   DECIMAL(10,7) NOT NULL COMMENT 'GPS latitude  at point of impact',
    `crash_longitude`  DECIMAL(10,7) NOT NULL COMMENT 'GPS longitude at point of impact',
    `notes`            TEXT                   COMMENT 'Auto-generated human-readable crash description',
    `recorded_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (`crash_id`),
    INDEX `idx_crash_unit` (`unit_id`),
    INDEX `idx_crash_time` (`crash_time`),

    CONSTRAINT `fk_crash_unit`
        FOREIGN KEY (`unit_id`)
        REFERENCES  `Virtual_Fleet_Units` (`unit_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_crash_log`
        FOREIGN KEY (`trigger_log_id`)
        REFERENCES  `Telemetry_Logs` (`log_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='ACID-written crash event records. One row per detected impact.';
