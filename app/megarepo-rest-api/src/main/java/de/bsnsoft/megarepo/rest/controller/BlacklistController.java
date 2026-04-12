package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.core.exception.NotFoundException;
import de.bsnsoft.megarepo.core.exception.ValidationException;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import de.bsnsoft.megarepo.database.entity.RepositoryEntity;
import de.bsnsoft.megarepo.database.repository.RepositoryJpaRepository;
import de.bsnsoft.megarepo.repository.proxy.BlacklistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

@RestController
@RequestMapping("/api/v1/repositories/{name}/blacklist")
public class BlacklistController {

    private final RepositoryConfigService repositoryConfigService;
    private final RepositoryJpaRepository repositoryJpaRepository;
    private final BlacklistService blacklistService;

    public BlacklistController(
            RepositoryConfigService repositoryConfigService,
            RepositoryJpaRepository repositoryJpaRepository,
            BlacklistService blacklistService) {
        this.repositoryConfigService = repositoryConfigService;
        this.repositoryJpaRepository = repositoryJpaRepository;
        this.blacklistService = blacklistService;
    }

    @GetMapping
    public ResponseEntity<List<String>> getPatterns(@PathVariable String name) {
        RepositoryConfig repo = findProxyRepo(name);
        List<String> patterns = blacklistService.getBlacklistPatterns(repo);
        return ResponseEntity.ok(patterns);
    }

    @PutMapping
    public ResponseEntity<List<String>> replacePatterns(
            @PathVariable String name, @RequestBody List<String> patterns) {
        RepositoryConfig repo = findProxyRepo(name);
        validatePatterns(patterns);

        RepositoryEntity entity = repositoryJpaRepository
                .findByName(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));

        Map<String, Object> attributes = new HashMap<>(entity.getAttributes());
        @SuppressWarnings("unchecked")
        Map<String, Object> proxy = attributes.containsKey("proxy")
                ? new HashMap<>((Map<String, Object>) attributes.get("proxy"))
                : new HashMap<>();
        proxy.put("blacklist", new ArrayList<>(patterns));
        attributes.put("proxy", proxy);

        entity.setAttributes(attributes);
        entity.setUpdatedAt(Instant.now());
        repositoryJpaRepository.save(entity);

        blacklistService.invalidateCache(repo.id());

        return ResponseEntity.ok(patterns);
    }

    @PostMapping("/check")
    public ResponseEntity<BlacklistCheckResponse> checkPath(
            @PathVariable String name, @RequestBody BlacklistCheckRequest request) {
        RepositoryConfig repo = findProxyRepo(name);
        if (request.path() == null || request.path().isBlank()) {
            throw new ValidationException("Path must not be empty");
        }
        boolean blocked = blacklistService.isBlacklisted(repo, request.path());
        return ResponseEntity.ok(new BlacklistCheckResponse(request.path(), blocked));
    }

    private RepositoryConfig findProxyRepo(String name) {
        RepositoryConfig repo = repositoryConfigService
                .getRepository(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));
        if (!"PROXY".equals(repo.type().name())) {
            throw new ValidationException("Blacklists are only supported for proxy repositories");
        }
        return repo;
    }

    private static final int MAX_PATTERNS = 500;
    private static final int MAX_PATTERN_LENGTH = 500;

    private void validatePatterns(List<String> patterns) {
        if (patterns.size() > MAX_PATTERNS) {
            throw new ValidationException(
                    "Too many blacklist patterns: %d (maximum %d)".formatted(patterns.size(), MAX_PATTERNS));
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                throw new ValidationException("Blacklist patterns must not be blank");
            }
            if (pattern.length() > MAX_PATTERN_LENGTH) {
                throw new ValidationException(
                        "Blacklist pattern too long (%d chars, maximum %d): %s..."
                                .formatted(pattern.length(), MAX_PATTERN_LENGTH, pattern.substring(0, 50)));
            }
            try {
                java.util.regex.Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new ValidationException("Invalid regex pattern '%s': %s".formatted(pattern, e.getMessage()));
            }
        }
    }

    public record BlacklistCheckRequest(String path) {}

    public record BlacklistCheckResponse(String path, boolean blocked) {}
}
