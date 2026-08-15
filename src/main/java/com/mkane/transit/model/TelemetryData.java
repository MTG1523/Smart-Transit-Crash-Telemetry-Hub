package com.mkane.transit.model;

import java.time.LocalDateTime;

/**
 * TelemetryData — Domain Model / POJO
 * =====================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Represents one row of the `Telemetry_Logs` table.
 * Produced at 10 Hz by VirtualTelemetrySimulator and batch-inserted
 * by TelemetryDAO.insertBatch().
 *
 * All numeric fields use primitive double for memory efficiency when
 * collecting batches of 50+ objects in hot-path simulator threads.
 */
public class TelemetryData {

    // ── Identity ──────────────────────────────────────────────────────────────
    private long          logId;       // populated by DAO after INSERT (generated key)
    private final int     unitId;
    private final LocalDateTime logTime;

    // ── GPS ───────────────────────────────────────────────────────────────────
    private final double  latitude;
    private final double  longitude;
    private final double  altitudeM;

    // ── Kinematics ────────────────────────────────────────────────────────────
    private final double  speedKmh;
    private final double  rpm;
    private final double  throttlePct;
    private final double  brakePressure;

    // ── Accelerometer (g-force) ───────────────────────────────────────────────
    private final double  accelX;
    private final double  accelY;
    private final double  accelZ;
    private final double  gForceMagnitude;  // pre-computed: sqrt(x²+y²+z²)

    // ── Event flag ────────────────────────────────────────────────────────────
    private final boolean isCrashEvent;

    /**
     * Full constructor — used by VirtualTelemetrySimulator.
     * gForceMagnitude is computed by the simulator before construction.
     */
    public TelemetryData(int unitId, LocalDateTime logTime,
                         double latitude, double longitude, double altitudeM,
                         double speedKmh, double rpm,
                         double throttlePct, double brakePressure,
                         double accelX, double accelY, double accelZ,
                         double gForceMagnitude, boolean isCrashEvent) {
        this.unitId          = unitId;
        this.logTime         = logTime;
        this.latitude        = latitude;
        this.longitude       = longitude;
        this.altitudeM       = altitudeM;
        this.speedKmh        = speedKmh;
        this.rpm             = rpm;
        this.throttlePct     = throttlePct;
        this.brakePressure   = brakePressure;
        this.accelX          = accelX;
        this.accelY          = accelY;
        this.accelZ          = accelZ;
        this.gForceMagnitude = gForceMagnitude;
        this.isCrashEvent    = isCrashEvent;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public long          getLogId()           { return logId;           }
    public void          setLogId(long id)    { this.logId = id;        }
    public int           getUnitId()          { return unitId;          }
    public LocalDateTime getLogTime()         { return logTime;         }
    public double        getLatitude()        { return latitude;        }
    public double        getLongitude()       { return longitude;       }
    public double        getAltitudeM()       { return altitudeM;       }
    public double        getSpeedKmh()        { return speedKmh;        }
    public double        getRpm()             { return rpm;             }
    public double        getThrottlePct()     { return throttlePct;     }
    public double        getBrakePressure()   { return brakePressure;   }
    public double        getAccelX()          { return accelX;          }
    public double        getAccelY()          { return accelY;          }
    public double        getAccelZ()          { return accelZ;          }
    public double        getGForceMagnitude() { return gForceMagnitude; }
    public boolean       isCrashEvent()       { return isCrashEvent;    }

    @Override
    public String toString() {
        return String.format(
            "Telemetry{unit=%d, t=%s, speed=%.1f km/h, rpm=%.0f, G=%.4f, crash=%b}",
            unitId, logTime, speedKmh, rpm, gForceMagnitude, isCrashEvent);
    }
}
