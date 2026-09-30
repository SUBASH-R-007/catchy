package com.acentra.catchy.telemetry.web;

import com.acentra.catchy.telemetry.dto.CatalogDtos.ApiKey;
import com.acentra.catchy.telemetry.dto.CatalogDtos.ApiKeyCreated;
import com.acentra.catchy.telemetry.dto.CatalogDtos.Application;
import com.acentra.catchy.telemetry.dto.CatalogDtos.CreateApiKeyRequest;
import com.acentra.catchy.telemetry.dto.CatalogDtos.CreateApplicationRequest;
import com.acentra.catchy.telemetry.dto.CatalogDtos.CreateProjectRequest;
import com.acentra.catchy.telemetry.dto.CatalogDtos.Project;
import com.acentra.catchy.telemetry.service.ApiKeyService;
import com.acentra.catchy.telemetry.service.CatalogService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Projects, applications and API keys. Minimum roles are enforced by the security configuration. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalog;
    private final ApiKeyService apiKeys;

    public CatalogController(CatalogService catalog, ApiKeyService apiKeys) {
        this.catalog = catalog;
        this.apiKeys = apiKeys;
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Project createProject(@Valid @RequestBody CreateProjectRequest body) {
        return catalog.createProject(body);
    }

    @GetMapping("/projects")
    public List<Project> projects() {
        return catalog.listProjects();
    }

    @GetMapping("/projects/{projectId}")
    public Project project(@PathVariable Long projectId) {
        return catalog.getProject(projectId);
    }

    @PostMapping("/projects/{projectId}/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public Application createApplication(@PathVariable Long projectId, @Valid @RequestBody CreateApplicationRequest body) {
        return catalog.createApplication(projectId, body);
    }

    @GetMapping("/projects/{projectId}/applications")
    public List<Application> projectApplications(@PathVariable Long projectId) {
        return catalog.listApplications(projectId);
    }

    @GetMapping("/applications")
    public List<Application> applications() {
        return catalog.listAllApplications();
    }

    @GetMapping("/applications/{applicationId}")
    public Application application(@PathVariable Long applicationId) {
        return catalog.getApplication(applicationId);
    }

    @PostMapping("/applications/{applicationId}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyCreated createApiKey(@PathVariable Long applicationId,
                                      @Valid @RequestBody(required = false) CreateApiKeyRequest body) {
        return apiKeys.create(applicationId, body == null ? null : body.label());
    }

    @GetMapping("/applications/{applicationId}/api-keys")
    public List<ApiKey> apiKeys(@PathVariable Long applicationId) {
        return apiKeys.list(applicationId);
    }

    @DeleteMapping("/api-keys/{apiKeyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(@PathVariable Long apiKeyId) {
        apiKeys.revoke(apiKeyId);
    }
}
