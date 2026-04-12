package de.bsnsoft.megarepo.app.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.BlobStoreJpaRepository;
import de.bsnsoft.megarepo.database.repository.GroupMemberJpaRepository;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class NexusMigrationService {

    private static final Logger log = LoggerFactory.getLogger(NexusMigrationService.class);
    private static final String DEFAULT_BLOB_STORE = "default";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static final Set<String> SUPPORTED_FORMATS = Set.of("maven2", "npm", "pypi", "raw", "docker");

    private final ObjectMapper objectMapper;
    private final FormatRegistry formatRegistry;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final BlobStoreJpaRepository blobStoreJpaRepository;
    private final GroupMemberJpaRepository groupMemberJpaRepository;

    public NexusMigrationService(
            ObjectMapper objectMapper,
            FormatRegistry formatRegistry,
            RepositoryJpaRepository repositoryJpaRepository,
            BlobStoreJpaRepository blobStoreJpaRepository,
            GroupMemberJpaRepository groupMemberJpaRepository) {
        this.objectMapper = objectMapper;
        this.formatRegistry = formatRegistry;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.blobStoreJpaRepository = blobStoreJpaRepository;
        this.groupMemberJpaRepository = groupMemberJpaRepository;
    }

    /**
     * Fetches repositories from a Nexus instance and returns a preview of what would be created.
     */
    public NexusMigrationPreview preview(NexusMigrationRequest request) {
        var nexusRepos = fetchNexusRepositories(request);
        return buildPreview(nexusRepos);
    }

    /**
     * Fetches repositories from a Nexus instance and creates them in MegaRepo.
     */
    public NexusMigrationResult execute(NexusMigrationRequest request) {
        var nexusRepos = fetchNexusRepositories(request);
        var preview = buildPreview(nexusRepos);
        return createRepositories(preview);
    }

    private List<JsonNode> fetchNexusRepositories(NexusMigrationRequest request) {
        String nexusUrl = request.nexusUrl().replaceAll("/+$", "");
        String apiUrl = nexusUrl + "/service/rest/v1/repositories";

        String credentials = Base64.getEncoder()
                .encodeToString((request.username() + ":" + request.password()).getBytes());

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Basic " + credentials)
                    .header("Accept", "application/json")
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new NexusMigrationException("Authentication failed. Please check your Nexus credentials.");
            }

            if (response.statusCode() != 200) {
                throw new NexusMigrationException(
                        "Nexus API returned HTTP " + response.statusCode() + ". Is the URL correct?");
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray()) {
                throw new NexusMigrationException("Unexpected response format from Nexus API");
            }

            List<JsonNode> repos = new ArrayList<>();
            root.forEach(repos::add);
            return repos;

        } catch (NexusMigrationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to connect to Nexus at {}", apiUrl, e);
            throw new NexusMigrationException("Failed to connect to Nexus: " + e.getMessage());
        }
    }

    private NexusMigrationPreview buildPreview(List<JsonNode> nexusRepos) {
        var importable = new ArrayList<NexusMigrationPreview.RepoPreview>();
        var skipped = new ArrayList<NexusMigrationPreview.SkippedRepo>();

        for (JsonNode repo : nexusRepos) {
            String name = repo.path("name").asText("");
            String format = repo.path("format").asText("");
            String type = repo.path("type").asText("").toUpperCase();

            if (name.isBlank() || format.isBlank()) {
                continue;
            }

            // Map Nexus format names to MegaRepo format names
            String mappedFormat = mapFormat(format);

            if (mappedFormat == null || !SUPPORTED_FORMATS.contains(mappedFormat)) {
                skipped.add(new NexusMigrationPreview.SkippedRepo(
                        name, format, type, "Unsupported format: " + format));
                continue;
            }

            // Check if repo already exists in MegaRepo
            boolean exists = repositoryJpaRepository.findByName(name).isPresent();

            String mappedType = mapType(type);
            String remoteUrl = null;
            List<String> groupMembers = null;

            if ("PROXY".equals(mappedType)) {
                remoteUrl = extractRemoteUrl(repo);
            }

            if ("GROUP".equals(mappedType)) {
                groupMembers = extractGroupMembers(repo);
            }

            importable.add(new NexusMigrationPreview.RepoPreview(
                    name, mappedFormat, mappedType, remoteUrl, groupMembers, exists));
        }

        return new NexusMigrationPreview(importable, skipped);
    }

    private NexusMigrationResult createRepositories(NexusMigrationPreview preview) {
        int created = 0;
        int skippedExisting = 0;
        var errors = new ArrayList<String>();

        // Create non-group repos first so group members exist
        var groupRepos = new ArrayList<NexusMigrationPreview.RepoPreview>();

        for (var repo : preview.importable()) {
            if (repo.alreadyExists()) {
                skippedExisting++;
                continue;
            }
            if ("GROUP".equals(repo.type())) {
                groupRepos.add(repo);
                continue;
            }
            try {
                createRepository(repo);
                created++;
            } catch (Exception e) {
                log.error("Failed to create repository {}", repo.name(), e);
                errors.add(repo.name() + ": " + e.getMessage());
            }
        }

        // Now create group repos
        for (var repo : groupRepos) {
            try {
                createRepository(repo);
                created++;
            } catch (Exception e) {
                log.error("Failed to create group repository {}", repo.name(), e);
                errors.add(repo.name() + ": " + e.getMessage());
            }
        }

        return new NexusMigrationResult(
                created, skippedExisting, preview.skipped().size(), errors);
    }

    private void createRepository(NexusMigrationPreview.RepoPreview repo) {
        if (!blobStoreJpaRepository.existsById(DEFAULT_BLOB_STORE)) {
            throw new NexusMigrationException("Default blob store not found. Please create it first.");
        }

        var entity = new RepositoryEntity();
        entity.setName(repo.name());
        entity.setFormat(repo.format());
        entity.setType(repo.type());
        entity.setOnline(true);
        entity.setBlobStoreName(DEFAULT_BLOB_STORE);

        Map<String, Object> attributes = new HashMap<>();

        if ("PROXY".equals(repo.type()) && repo.remoteUrl() != null) {
            attributes.put("proxy", Map.of("remoteUrl", repo.remoteUrl()));
        }

        if ("GROUP".equals(repo.type()) && repo.groupMembers() != null) {
            attributes.put("group", Map.of("memberNames", repo.groupMembers()));
        }

        entity.setAttributes(attributes);

        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        var saved = repositoryJpaRepository.save(entity);

        // Wire group members
        if ("GROUP".equals(repo.type()) && repo.groupMembers() != null) {
            wireGroupMembers(saved, repo.groupMembers());
        }
    }

    private void wireGroupMembers(RepositoryEntity groupEntity, List<String> memberNames) {
        for (int i = 0; i < memberNames.size(); i++) {
            var memberRepo = repositoryJpaRepository.findByName(memberNames.get(i));
            if (memberRepo.isPresent()) {
                var member = new GroupMemberEntity();
                member.setGroupRepoId(groupEntity.getId());
                member.setMemberRepoId(memberRepo.get().getId());
                member.setSortOrder(i);
                groupMemberJpaRepository.save(member);
            }
        }
    }

    private String mapFormat(String nexusFormat) {
        return switch (nexusFormat.toLowerCase()) {
            case "maven2" -> "maven2";
            case "npm" -> "npm";
            case "pypi" -> "pypi";
            case "raw" -> "raw";
            case "docker" -> "docker";
            default -> null;
        };
    }

    private String mapType(String nexusType) {
        return switch (nexusType.toUpperCase()) {
            case "HOSTED" -> "HOSTED";
            case "PROXY" -> "PROXY";
            case "GROUP" -> "GROUP";
            default -> "HOSTED";
        };
    }

    @SuppressWarnings("unchecked")
    private String extractRemoteUrl(JsonNode repo) {
        // Nexus API returns proxy config in different places depending on the API version
        // Try: attributes.proxy.remoteUrl, then proxy.remoteUrl, then remoteUrl
        JsonNode proxy = repo.path("attributes").path("proxy");
        if (proxy.has("remoteUrl")) {
            return proxy.path("remoteUrl").asText(null);
        }
        proxy = repo.path("proxy");
        if (proxy.has("remoteUrl")) {
            return proxy.path("remoteUrl").asText(null);
        }
        return repo.path("remoteUrl").asText(null);
    }

    private List<String> extractGroupMembers(JsonNode repo) {
        // Try: attributes.group.memberNames, then group.memberNames
        JsonNode group = repo.path("attributes").path("group");
        if (group.has("memberNames")) {
            return jsonArrayToList(group.path("memberNames"));
        }
        group = repo.path("group");
        if (group.has("memberNames")) {
            return jsonArrayToList(group.path("memberNames"));
        }
        return List.of();
    }

    private List<String> jsonArrayToList(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return List.of();
        }
        var result = new ArrayList<String>();
        arrayNode.forEach(node -> result.add(node.asText()));
        return result;
    }
}
