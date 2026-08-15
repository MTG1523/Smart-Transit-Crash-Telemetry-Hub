package com.mkane.transit.model;

import java.time.LocalDateTime;

/**
 * CrashEvent — Domain Model / POJO
 * ==================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Represents one row of the `Crash_Events` table.
 * Built by VirtualTelemetrySimulator on crash detection and persisted
 * atomically by CrashDAO within a 2-statement ACID transaction.
 */
public class CrashEvent {

    private int           crashId;           // populated by DAO after INSERT
    private final int     unitId;
    private final long    triggerLogId;      // FK → Telemetry_Logs.log_id
    private final LocalDateTime crashTime;
    private final double  peakGForce;
    private final double  severityScore;     // normalised 0.0–1.0
    private final double  crashLatitude;
    private final double  crashLongitude;
    private final String  notes;

    /**
     * Full constructor.
     *
     * @param severityScore normalised 0.0–1.0 (computed as peakGForce / MAX_CRASH_G)
     */
    public CrashEvent(int unitId, long triggerLogId, LocalDateTime crashTime,
                      double peakGForce, double severityScore,
                      double crashLatitude, double crashLongitude, String notes) {
        this.unitId         = unitId;
        this.triggerLogId   = triggerLogId;
        this.crashTime      = crashTime;
        this.peakGForce     = peakGForce;
        this.severityScore  = severityScore;
        this.crashLatitude  = crashLatitude;
        this.crashLongitude = crashLongitude;
        this.notes          = notes;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int           getCrashId()        { return crashId;        }
    public void          setCrashId(int id)  { this.crashId = id;     }
    public int           getUnitId()         { return unitId;         }
    public long          getTriggerLogId()   { return triggerLogId;   }
    public LocalDateTime getCrashTime()      { return crashTime;      }
    public double        getPeakGForce()     { return peakGForce;     }
    public double        getSeverityScore()  { return severityScore;  }
    public double        getCrashLatitude()  { return crashLatitude;  }
    public double        getCrashLongitude() { return crashLongitude; }
    public String        getNotes()          { return notes;          }

    @Override
    public String toString() {
        return String.format(
            "CrashEvent{unit=%d, triggerLog=%d, peakG=%.4f, severity=%.4f, loc=(%.7f,%.7f)}",
            unitId, triggerLogId, peakGForce, severityScore, crashLatitude, crashLongitude);
    }
}
