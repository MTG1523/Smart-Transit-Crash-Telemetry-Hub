package com.mkane.transit.model;

import java.time.LocalDateTime;

/**
 * FleetUnit — Domain Model / POJO
 * ================================
 * Author  : m.kane
 * Project : Smart Transit & Crash Telemetry Hub
 *
 * Mirrors the `Virtual_Fleet_Units` table row.
 * Immutable after construction; use the builder-style constructor.
 */
public class FleetUnit {

    private final int       unitId;
    private final String    unitName;
    private final int       modelYear;
    private final String    vinCode;
    private       boolean   isActive;       // mutable — flipped on crash
    private final LocalDateTime createdAt;

    public FleetUnit(int unitId, String unitName, int modelYear, String vinCode) {
        this.unitId    = unitId;
        this.unitName  = unitName;
        this.modelYear = modelYear;
        this.vinCode   = vinCode;
        this.isActive  = true;
        this.createdAt = LocalDateTime.now();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int          getUnitId()    { return unitId;    }
    public String       getUnitName()  { return unitName;  }
    public int          getModelYear() { return modelYear; }
    public String       getVinCode()   { return vinCode;   }
    public boolean      isActive()     { return isActive;  }
    public LocalDateTime getCreatedAt(){ return createdAt; }

    /** Called by CrashDAO after a successful crash transaction. */
    public void deactivate() { this.isActive = false; }

    @Override
    public String toString() {
        return String.format("FleetUnit{id=%d, name='%s', vin='%s', active=%b}",
                             unitId, unitName, vinCode, isActive);
    }
}
