package com.acentra.catchy.telemetry.service;

import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.AuditLogEntry;
import com.acentra.catchy.telemetry.domain.AuditRepository;
import com.acentra.catchy.telemetry.security.AuthenticatedUser;
import com.acentra.catchy.telemetry.security.IngestPrincipal;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the audit trail. Successful actions join the caller's transaction so the audit row commits atomically with
 * the change; denials and failures use their own transaction so they survive a rolled-back request.
 * Callers must never pass keys, cached values, tokens or credentials in {@code details}.
 */
@Service
public class AuditService {

    public enum Outcome { SUCCESS, DENIED, FAILURE }

    public record Actor(String name, String role) {
        public static Actor system() {
            return new Actor("system", "SYSTEM");
        }
    }

    private static final int MAX_DETAILS = 1000;

    private final AuditRepository repository;
    private final Clock clock;
    private final TransactionTemplate independent;

    public AuditService(AuditRepository repository, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The actor of the current request: dashboard user, API key (by prefix) or anonymous. */
    public Actor currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) {
            return new Actor(u.username(), u.role().name());
        }
        if (auth != null && auth.getPrincipal() instanceof IngestPrincipal p) {
            return new Actor("api-key:" + p.keyPrefix(), "INGEST");
        }
        return new Actor("anonymous", "NONE");
    }

    @Transactional
    public void success(AuditAction action, String targetType, String targetId, Long applicationId, String details) {
        write(currentActor(), action, targetType, targetId, applicationId, details, Outcome.SUCCESS);
    }

    @Transactional
    public void successAs(Actor actor, AuditAction action, String targetType, String targetId, Long applicationId, String details) {
        write(actor, action, targetType, targetId, applicationId, details, Outcome.SUCCESS);
    }

    public void denied(AuditAction action, String targetType, String targetId, Long applicationId, String details) {
        deniedAs(currentActor(), action, targetType, targetId, applicationId, details);
    }

    public void deniedAs(Actor actor, AuditAction action, String targetType, String targetId, Long applicationId, String details) {
        independent.executeWithoutResult(s -> write(actor, action, targetType, targetId, applicationId, details, Outcome.DENIED));
    }

    public void failureAs(Actor actor, AuditAction action, String targetType, String targetId, Long applicationId, String details) {
        independent.executeWithoutResult(s -> write(actor, action, targetType, targetId, applicationId, details, Outcome.FAILURE));
    }

    private void write(Actor actor, AuditAction action, String targetType, String targetId, Long applicationId,
                       String details, Outcome outcome) {
        AuditLogEntry e = new AuditLogEntry();
        e.occurredAt = Times.now(clock);
        e.actor = limit(actor.name(), 80);
        e.actorRole = limit(actor.role(), 16);
        e.action = action.name();
        e.targetType = targetType;
        e.targetId = limit(targetId, 80);
        e.applicationId = applicationId;
        e.details = details == null ? null : limit(sanitize(details), MAX_DETAILS);
        e.outcome = outcome.name();
        repository.save(e);
    }

    /** Collapses control characters so a detail line can never forge additional log lines. */
    private static String sanitize(String s) {
        return s.replaceAll("[\\p{Cntrl}]+", " ");
    }

    private static String limit(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
