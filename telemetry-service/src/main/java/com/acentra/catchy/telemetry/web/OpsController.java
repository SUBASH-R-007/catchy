package com.acentra.catchy.telemetry.web;

import com.acentra.catchy.telemetry.dto.OpsDtos.AuditLog;
import com.acentra.catchy.telemetry.dto.OpsDtos.SimulationRequest;
import com.acentra.catchy.telemetry.dto.OpsDtos.SimulationResult;
import com.acentra.catchy.telemetry.service.AuditAction;
import com.acentra.catchy.telemetry.service.AuditQueryService;
import com.acentra.catchy.telemetry.service.AuditService;
import com.acentra.catchy.telemetry.sim.SimulationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit log (ADMIN) and simulations (ENGINEER). */
@RestController
@RequestMapping("/api/v1")
public class OpsController {

    private final AuditQueryService auditQuery;
    private final AuditService audit;
    private final SimulationService simulations;

    public OpsController(AuditQueryService auditQuery, AuditService audit, SimulationService simulations) {
        this.auditQuery = auditQuery;
        this.audit = audit;
        this.simulations = simulations;
    }

    @GetMapping("/audit-logs")
    public List<AuditLog> auditLogs(@RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String action,
                                    @RequestParam(required = false) Long applicationId) {
        return auditQuery.query(limit, action, applicationId);
    }

    @PostMapping("/applications/{applicationId}/simulate/{kind}")
    public SimulationResult simulate(@PathVariable Long applicationId, @PathVariable String kind,
                                     @Valid @RequestBody(required = false) SimulationRequest body) {
        SimulationResult result = simulations.run(applicationId, kind, body == null ? null : body.requests());
        audit.success(AuditAction.SIMULATION_RUN, "SIMULATION", result.simulationId(), applicationId,
                "Simulation '" + kind + "' run (id " + result.simulationId() + ", regions " + String.join(", ", result.cacheRegions())
                        + ", " + result.durationMs() + " ms)");
        return result;
    }
}
