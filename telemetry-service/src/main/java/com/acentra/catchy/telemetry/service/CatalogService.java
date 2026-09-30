package com.acentra.catchy.telemetry.service;

import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.ApplicationService;
import com.acentra.catchy.telemetry.domain.Project;
import com.acentra.catchy.telemetry.domain.ProjectRepository;
import com.acentra.catchy.telemetry.dto.CatalogDtos.Application;
import com.acentra.catchy.telemetry.dto.CatalogDtos.CreateApplicationRequest;
import com.acentra.catchy.telemetry.dto.CatalogDtos.CreateProjectRequest;
import com.acentra.catchy.telemetry.web.ApiException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects and applications (the monitored catalog). */
@Service
public class CatalogService {

    private final ProjectRepository projects;
    private final ApplicationRepository applications;
    private final AuditService audit;
    private final Clock clock;

    public CatalogService(ProjectRepository projects, ApplicationRepository applications, AuditService audit, Clock clock) {
        this.projects = projects;
        this.applications = applications;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- projects ----------------------------------------------------------------------------------------------------

    @Transactional
    public com.acentra.catchy.telemetry.dto.CatalogDtos.Project createProject(CreateProjectRequest body) {
        String name = body.name().trim();
        if (name.length() < 2) throw ApiException.badRequest("Validation failed", List.of("name: size must be between 2 and 80"));
        String lower = name.toLowerCase(Locale.ROOT);
        if (projects.existsByNameLower(lower)) throw ApiException.conflict("A project with this name already exists");
        Project p = new Project();
        p.name = name;
        p.nameLower = lower;
        p.description = body.description() == null || body.description().isBlank() ? null : body.description().trim();
        p.createdAt = Times.now(clock);
        try {
            p = projects.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("A project with this name already exists");
        }
        audit.success(AuditAction.PROJECT_CREATED, "PROJECT", String.valueOf(p.id), null, "Created project '" + p.name + "'");
        return toDto(p, 0);
    }

    @Transactional(readOnly = true)
    public List<com.acentra.catchy.telemetry.dto.CatalogDtos.Project> listProjects() {
        return projects.findAll().stream().sorted((a, b) -> a.id.compareTo(b.id))
                .map(p -> toDto(p, applications.countByProjectId(p.id))).toList();
    }

    @Transactional(readOnly = true)
    public com.acentra.catchy.telemetry.dto.CatalogDtos.Project getProject(Long id) {
        Project p = projects.findById(id).orElseThrow(() -> ApiException.notFound("Project not found"));
        return toDto(p, applications.countByProjectId(id));
    }

    // ---- applications ------------------------------------------------------------------------------------------------

    @Transactional
    public Application createApplication(Long projectId, CreateApplicationRequest body) {
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("Project not found"));
        if (applications.existsByNameAndEnvironment(body.name(), body.environment())) {
            throw ApiException.conflict("An application with this name already exists in this environment");
        }
        ApplicationService a = new ApplicationService();
        a.projectId = projectId;
        a.name = body.name();
        a.environment = body.environment();
        a.displayName = body.displayName() == null || body.displayName().isBlank() ? body.name() : body.displayName().trim();
        a.description = body.description() == null || body.description().isBlank() ? null : body.description().trim();
        a.createdAt = Times.now(clock);
        try {
            a = applications.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("An application with this name already exists in this environment");
        }
        audit.success(AuditAction.APPLICATION_CREATED, "APPLICATION", String.valueOf(a.id), a.id,
                "Created application '" + a.name + "' (" + a.environment + ") in project '" + project.name + "'");
        return toDto(a, project.name);
    }

    @Transactional(readOnly = true)
    public List<Application> listApplications(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("Project not found"));
        return applications.findByProjectIdOrderById(projectId).stream().map(a -> toDto(a, project.name)).toList();
    }

    @Transactional(readOnly = true)
    public List<Application> listAllApplications() {
        Map<Long, String> names = new HashMap<>();
        projects.findAll().forEach(p -> names.put(p.id, p.name));
        return applications.findAllByOrderById().stream().map(a -> toDto(a, names.getOrDefault(a.projectId, "unknown"))).toList();
    }

    @Transactional(readOnly = true)
    public Application getApplication(Long id) {
        ApplicationService a = applications.findById(id).orElseThrow(() -> ApiException.notFound("Application not found"));
        return toDto(a, projects.findById(a.projectId).map(p -> p.name).orElse("unknown"));
    }

    private static com.acentra.catchy.telemetry.dto.CatalogDtos.Project toDto(Project p, long applicationCount) {
        return new com.acentra.catchy.telemetry.dto.CatalogDtos.Project(p.id, p.name, p.description, p.createdAt, applicationCount);
    }

    private static Application toDto(ApplicationService a, String projectName) {
        return new Application(a.id, a.projectId, projectName, a.name, a.displayName, a.environment, a.description,
                a.createdAt, a.lastTelemetryAt, a.regionCount);
    }
}
