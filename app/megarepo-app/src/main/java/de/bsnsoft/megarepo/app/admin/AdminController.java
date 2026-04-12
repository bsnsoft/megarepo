package de.bsnsoft.megarepo.app.admin;

import de.bsnsoft.megarepo.app.setup.RepositoryPresetLoader;
import de.bsnsoft.megarepo.app.setup.RepositoryPresetLoader.PresetLoadResult;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for bulk operations such as importing/exporting repository presets.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final RepositoryPresetLoader presetLoader;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final GroupMemberJpaRepository groupMemberJpaRepository;

    public AdminController(
            RepositoryPresetLoader presetLoader,
            RepositoryJpaRepository repositoryJpaRepository,
            GroupMemberJpaRepository groupMemberJpaRepository) {
        this.presetLoader = presetLoader;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.groupMemberJpaRepository = groupMemberJpaRepository;
    }

    /**
     * Import repositories from a YAML preset string.
     * The request body should be raw YAML text (not JSON-wrapped).
     */
    @PostMapping(value = "/import-repos", consumes = {"text/yaml", "text/x-yaml", "application/x-yaml", "text/plain"})
    public ResponseEntity<ImportReposResponse> importRepos(@RequestBody String yamlContent) {
        PresetLoadResult result = presetLoader.loadFromYamlString(yamlContent);
        return ResponseEntity.ok(new ImportReposResponse(result.created(), result.skipped()));
    }

    /**
     * Export all repository configurations as YAML.
     * The output matches the preset import format so it can be re-imported.
     */
    @GetMapping(value = "/export-repos", produces = "text/yaml")
    public ResponseEntity<String> exportRepos() {
        List<RepositoryEntity> repos = repositoryJpaRepository.findAll();
        String yaml = buildYaml(repos);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"megarepo-repositories.yml\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/yaml; charset=UTF-8")
                .body(yaml);
    }

    @SuppressWarnings("unchecked")
    private String buildYaml(List<RepositoryEntity> repos) {
        var sb = new StringBuilder();
        sb.append("repositories:\n");

        for (RepositoryEntity repo : repos) {
            sb.append("  - name: ").append(yamlEscape(repo.getName())).append('\n');
            sb.append("    format: ").append(yamlEscape(repo.getFormat())).append('\n');
            sb.append("    type: ").append(repo.getType().toLowerCase()).append('\n');

            if ("PROXY".equalsIgnoreCase(repo.getType())) {
                var attrs = repo.getAttributes();
                var proxyConfig = (Map<String, Object>) attrs.get("proxy");
                if (proxyConfig != null && proxyConfig.get("remoteUrl") != null) {
                    sb.append("    remoteUrl: ").append(yamlEscape(proxyConfig.get("remoteUrl").toString())).append('\n');
                }
            }

            if ("GROUP".equalsIgnoreCase(repo.getType())) {
                var members = groupMemberJpaRepository.findByGroupRepoIdOrderBySortOrder(repo.getId());
                if (!members.isEmpty()) {
                    sb.append("    members:\n");
                    for (var member : members) {
                        var memberRepo = repositoryJpaRepository.findById(member.getMemberRepoId());
                        memberRepo.ifPresent(r -> sb.append("      - ").append(yamlEscape(r.getName())).append('\n'));
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Escapes a YAML string value if it contains special characters.
     */
    private String yamlEscape(String value) {
        if (value == null) {
            return "null";
        }
        if (value.isEmpty() || value.contains(":") || value.contains("#") || value.contains("\"")
                || value.contains("'") || value.contains("{") || value.contains("}")
                || value.contains("[") || value.contains("]") || value.contains(",")
                || value.contains("&") || value.contains("*") || value.contains("!")
                || value.contains("|") || value.contains(">") || value.contains("%")
                || value.contains("@") || value.contains("`")
                || value.startsWith(" ") || value.endsWith(" ")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    public record ImportReposResponse(java.util.List<String> created, java.util.List<String> skipped) {}
}
