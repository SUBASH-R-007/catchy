package com.acentra.catchy.telemetry.seed;

import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.ApplicationService;
import com.acentra.catchy.telemetry.domain.Project;
import com.acentra.catchy.telemetry.domain.ProjectRepository;
import com.acentra.catchy.telemetry.security.ApiKeys;
import com.acentra.catchy.telemetry.service.ApiKeyService;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent demo data: three projects, the claims and eligibility applications and, when supplied through the
 * environment, their API keys (registered hashed). Never generates or logs a plaintext key.
 */
@Component
@Order(2)
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final CatchyProperties props;
    private final ProjectRepository projects;
    private final ApplicationRepository applications;
    private final ApiKeyService apiKeys;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DataSeeder(CatchyProperties props, ProjectRepository projects, ApplicationRepository applications,
                      ApiKeyService apiKeys, TransactionTemplate tx, Clock clock) {
        this.props = props;
        this.projects = projects;
        this.applications = applications;
        this.apiKeys = apiKeys;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seed().enabled()) return;
        tx.executeWithoutResult(status -> {
            Project claims = project("Claims Platform", "Claims adjudication and processing services");
            Project eligibility = project("Eligibility Platform", "Member eligibility and benefits verification services");
            project("Prior Authorization Platform", "Prior authorization review and decision services");
            ApplicationService claimsApp = application(claims, "claims-service", "Claims Service", "staging",
                    "Claims adjudication API");
            ApplicationService eligibilityApp = application(eligibility, "eligibility-service", "Eligibility Service", "staging",
                    "Eligibility verification API");
            seedKey(claimsApp, props.seed().claimsApiKey());
            seedKey(eligibilityApp, props.seed().eligibilityApiKey());
        });
        log.info("Demo data seeded (projects, applications, optional API keys from the environment).");
    }

    private Project project(String name, String description) {
        return projects.findByNameLower(name.toLowerCase(Locale.ROOT)).orElseGet(() -> {
            Project p = new Project();
            p.name = name;
            p.nameLower = name.toLowerCase(Locale.ROOT);
            p.description = description;
            p.createdAt = Times.now(clock);
            return projects.save(p);
        });
    }

    private ApplicationService application(Project project, String name, String displayName, String environment, String description) {
        Optional<ApplicationService> existing = applications.findByNameAndEnvironment(name, environment);
        if (existing.isPresent()) return existing.get();
        ApplicationService a = new ApplicationService();
        a.projectId = project.id;
        a.name = name;
        a.displayName = displayName;
        a.environment = environment;
        a.description = description;
        a.createdAt = Times.now(clock);
        return applications.save(a);
    }

    private void seedKey(ApplicationService app, String key) {
        if (key == null || key.isBlank()) return;
        if (!ApiKeys.isWellFormed(key.trim())) {
            log.warn("Seed API key for {} ignored: it does not match the format acc_<8 hex>_<32 hex>.", app.name);
            return;
        }
        apiKeys.register(app.id, "Seeded key (environment)", key.trim());
    }
}
