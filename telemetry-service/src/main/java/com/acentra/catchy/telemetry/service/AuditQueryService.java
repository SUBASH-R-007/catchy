package com.acentra.catchy.telemetry.service;

import com.acentra.catchy.telemetry.domain.AuditLogEntry;
import com.acentra.catchy.telemetry.domain.AuditRepository;
import com.acentra.catchy.telemetry.dto.OpsDtos.AuditLog;
import com.acentra.catchy.telemetry.web.ApiException;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to the audit trail (ADMIN only, enforced by the security configuration). */
@Service
public class AuditQueryService {
    private static final Pattern ACTION = Pattern.compile("^[A-Z_]{1,40}$");

    private final AuditRepository repository;

    public AuditQueryService(AuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AuditLog> query(Integer limit, String action, Long applicationId) {
        int effective = limit == null ? 100 : limit;
        if (effective < 1 || effective > 500) throw ApiException.badRequest("limit must be between 1 and 500");
        if (action != null && !action.isBlank() && !ACTION.matcher(action).matches()) {
            throw ApiException.badRequest("action must be an upper-case audit action name");
        }
        Specification<AuditLogEntry> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (action != null && !action.isBlank()) ps.add(cb.equal(root.get("action"), action));
            if (applicationId != null) ps.add(cb.equal(root.get("applicationId"), applicationId));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Sort sort = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));
        return repository.findAll(spec, PageRequest.of(0, effective, sort)).getContent().stream()
                .map(e -> new AuditLog(e.id, e.occurredAt, e.actor, e.actorRole, e.action, e.targetType, e.targetId,
                        e.applicationId, e.details, e.outcome)).toList();
    }
}
